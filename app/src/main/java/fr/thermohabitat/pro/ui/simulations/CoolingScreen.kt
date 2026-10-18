package fr.thermohabitat.pro.ui.simulations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.thermohabitat.pro.data.db.MeasurementSeriesEntity
import fr.thermohabitat.pro.data.repository.ProjectRepository
import fr.thermohabitat.pro.domain.AnalyzedProject
import fr.thermohabitat.pro.domain.StudyService
import fr.thermohabitat.pro.ui.components.BannerKind
import fr.thermohabitat.pro.ui.components.ChartPalette
import fr.thermohabitat.pro.ui.components.ChoiceChips
import fr.thermohabitat.pro.ui.components.DropdownField
import fr.thermohabitat.pro.ui.components.InfoBanner
import fr.thermohabitat.pro.ui.components.LineChart
import fr.thermohabitat.pro.ui.components.LineSeries
import fr.thermohabitat.pro.ui.components.NumberField
import fr.thermohabitat.pro.ui.components.ScreenScaffold
import fr.thermohabitat.pro.ui.components.ScrollColumn
import fr.thermohabitat.pro.ui.components.SectionCard
import fr.thermohabitat.pro.ui.components.SwitchRow
import fr.thermohabitat.pro.ui.components.TextInput
import fr.thermohabitat.pro.ui.components.ValueRow
import fr.thermohabitat.pro.ui.util.Fmt
import fr.thermohabitat.thermal.StudySettings
import fr.thermohabitat.thermal.calc.CoverageAnalyzer
import fr.thermohabitat.thermal.calc.HeatLossCalculator
import fr.thermohabitat.thermal.model.Building
import fr.thermohabitat.thermal.scenario.Scenario
import fr.thermohabitat.thermal.scenario.ScenarioEngine
import fr.thermohabitat.thermal.sim.CalibrationResult
import fr.thermohabitat.thermal.sim.Calibrator
import fr.thermohabitat.thermal.sim.HeatingPeriod
import fr.thermohabitat.thermal.sim.MeasuredPoint
import fr.thermohabitat.thermal.sim.OutdoorProfile
import fr.thermohabitat.thermal.sim.RcSimulator
import fr.thermohabitat.thermal.sim.SimulationConfig
import fr.thermohabitat.thermal.sim.SimulationResult
import fr.thermohabitat.thermal.sim.SolarProfile
import fr.thermohabitat.thermal.sim.UncertainSimulation
import fr.thermohabitat.thermal.sim.ZoneModel
import fr.thermohabitat.thermal.sim.ZoneModelBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class CoolingOptions(
    val startHour: Double = 22.0,
    val durationH: Double = 8.0,
    val dailyOutdoor: Boolean = false,
    val outdoorC: Double = 0.0,
    val outdoorMinC: Double = -3.0,
    val outdoorMaxC: Double = 5.0,
    val initialC: Double = 20.0,
    val heatingSchedule: Boolean = false,
    val heatOnHour: Double = 6.0,
    val heatOffHour: Double = 22.0,
    val solar: SolarProfile = SolarProfile.NONE,
    val internalGains: Boolean = false,
    val multizone: Boolean = false,
    val roomId: Long? = null,
    val scenarioId: Long? = null,
    val band: Boolean = true,
) {
    fun config(): SimulationConfig = SimulationConfig(
        durationH = durationH, timeStepS = 120.0, startClockHour = startHour,
        outdoor = if (dailyOutdoor) OutdoorProfile.Daily(outdoorMinC, outdoorMaxC) else OutdoorProfile.Constant(outdoorC),
        heatingPeriods = if (heatingSchedule) listOf(HeatingPeriod(heatOnHour, heatOffHour)) else emptyList(),
        solar = solar, includeInternalGains = internalGains, initialTempC = initialC, recordEveryS = 600.0,
    )
}

data class CoolingData(
    val analyzed: AnalyzedProject,
    val model: ZoneModel,
    val simulation: UncertainSimulation,
    val scenario: Scenario?,
    val scenarioResult: SimulationResult?,
    val scenarioTimeConstantH: Double?,
)

@HiltViewModel
class CoolingViewModel @Inject constructor(
    studyService: StudyService,
    private val repository: ProjectRepository,
) : ViewModel() {
    val options = MutableStateFlow(CoolingOptions())
    private val simulator = RcSimulator()
    private val project = studyService.observeActive().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val scenarios = project.map { it?.project?.id }.distinctUntilChanged().flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else repository.observeScenarios(id).map { list -> list.map { it.first.id to it.second } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val measurements = MutableStateFlow<List<MeasuredPoint>>(emptyList())
    val calibration = MutableStateFlow<CalibrationResult?>(null)
    @OptIn(ExperimentalCoroutinesApi::class)
    val savedSeries = project.map { it?.project?.id }.distinctUntilChanged().flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else repository.observeMeasurements(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val data = combine(project, options, scenarios) { p, o, sc ->
        if (p == null || p.project.building.rooms.isEmpty()) null else runCatching { compute(p, o, sc) }.getOrNull()
    }
        .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private fun heatingPowers(building: Building, settings: StudySettings): Map<Long, Double> {
        val hl = HeatLossCalculator().calculate(building)
        return CoverageAnalyzer.analyze(building, hl, settings.studyRegime, settings.modeFor(settings.studyRegime)).rooms.associate { it.roomId to it.availableW }
    }

    private fun compute(a: AnalyzedProject, o: CoolingOptions, scenarios: List<Pair<Long, Scenario>>): CoolingData {
        val b = a.project.building
        val s = a.project.settings
        val model = ZoneModelBuilder.build(b, a.result.heatLoss, heatingPowers(b, s), singleZone = !o.multizone)
        val config = o.config()
        val sim = simulator.simulateWithUncertainty(model.zones, model.couplings, config, model.capacityUncertainty, model.conductanceUncertainty)
        val scenario = scenarios.firstOrNull { it.first == o.scenarioId }?.second
        var scenarioResult: SimulationResult? = null
        var tau: Double? = null
        if (scenario != null) {
            val improved = ScenarioEngine.apply(b, scenario.modifications)
            val hl2 = HeatLossCalculator().calculate(improved)
            val m2 = ZoneModelBuilder.build(improved, hl2, heatingPowers(improved, scenario.regime?.let { s.copy(studyRegime = it) } ?: s), singleZone = !o.multizone)
            scenarioResult = simulator.simulate(m2.zones, m2.couplings, config)
            tau = m2.zones.sumOf { it.totalCapacityJk } / m2.zones.sumOf { it.steadyStateHWk }.coerceAtLeast(1e-9) / 3600.0
        }
        return CoolingData(a, model, sim, scenario, scenarioResult, tau)
    }

    fun update(t: (CoolingOptions) -> CoolingOptions) { options.value = t(options.value); calibration.value = null }

    fun addMeasurement(timeH: Double, tempC: Double) { measurements.value = (measurements.value + MeasuredPoint(timeH, tempC)).sortedBy { it.timeH } }
    fun removeMeasurement(p: MeasuredPoint) { measurements.value = measurements.value - p }

    fun calibrate() = viewModelScope.launch {
        val d = data.value ?: return@launch
        val pts = measurements.value
        if (pts.size < 2) return@launch
        val o = options.value
        calibration.value = withContext(Dispatchers.Default) {
            runCatching { Calibrator.calibrate(d.model, o.config(), pts, zoneId = if (o.multizone) o.roomId else null, fitConductance = pts.size >= 6) }.getOrNull()
        }
    }

    fun saveSeries(name: String) = viewModelScope.launch {
        val p = project.value ?: return@launch
        val o = options.value
        repository.saveMeasurements(
            MeasurementSeriesEntity(buildingId = p.project.id, name = name.ifBlank { "Mesures" }, roomId = if (o.multizone) o.roomId else null,
                outdoorC = if (o.dailyOutdoor) null else o.outdoorC, startClockHour = o.startHour),
            measurements.value,
        )
    }

    fun loadSeries(e: MeasurementSeriesEntity, points: List<MeasuredPoint>) {
        measurements.value = points
        update { it.copy(startHour = e.startClockHour, outdoorC = e.outdoorC ?: it.outdoorC, dailyOutdoor = e.outdoorC == null && it.dailyOutdoor,
            roomId = e.roomId, multizone = e.roomId != null, durationH = maxOf(it.durationH, points.maxOfOrNull { p -> p.timeH } ?: 0.0)) }
    }

    fun deleteSeries(id: Long) = viewModelScope.launch { repository.deleteMeasurements(id) }
}

@Composable
fun CoolingScreen(nav: NavController, vm: CoolingViewModel = hiltViewModel()) {
    val data by vm.data.collectAsStateWithLifecycle()
    val o by vm.options.collectAsStateWithLifecycle()
    val scenarios by vm.scenarios.collectAsStateWithLifecycle()
    val measurements by vm.measurements.collectAsStateWithLifecycle()
    val calibration by vm.calibration.collectAsStateWithLifecycle()
    val saved by vm.savedSeries.collectAsStateWithLifecycle()
    var addPoint by remember { mutableStateOf(false) }
    var seriesName by remember { mutableStateOf("") }

    ScreenScaffold(title = "Simulation dynamique", onBack = { nav.popBackStack() }) { padding ->
        ScrollColumn(padding) {
            SectionCard("Conditions simulées", icon = Icons.Filled.AcUnit) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("Heure de début", o.startHour, { v -> v?.let { vm.update { x -> x.copy(startHour = it % 24) } } }, Modifier.weight(1f), unit = "h", decimals = 1, nullable = false, min = 0.0, max = 24.0)
                    NumberField("Durée", o.durationH, { v -> v?.let { vm.update { x -> x.copy(durationH = it) } } }, Modifier.weight(1f), unit = "h", decimals = 0, nullable = false, min = 1.0, max = 168.0)
                }
                NumberField("Température intérieure initiale", o.initialC, { v -> v?.let { vm.update { x -> x.copy(initialC = it) } } }, unit = "°C", decimals = 1, nullable = false, min = 0.0, max = 35.0)
                ChoiceChips(listOf(false, true), o.dailyOutdoor, { if (it) "Extérieur variable (jour/nuit)" else "Extérieur constant" }, { v -> vm.update { it.copy(dailyOutdoor = v) } })
                if (o.dailyOutdoor) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumberField("Minimum (6 h)", o.outdoorMinC, { v -> v?.let { vm.update { x -> x.copy(outdoorMinC = it) } } }, Modifier.weight(1f), unit = "°C", decimals = 1, nullable = false)
                        NumberField("Maximum (18 h)", o.outdoorMaxC, { v -> v?.let { vm.update { x -> x.copy(outdoorMaxC = it) } } }, Modifier.weight(1f), unit = "°C", decimals = 1, nullable = false)
                    }
                } else {
                    NumberField("Température extérieure", o.outdoorC, { v -> v?.let { vm.update { x -> x.copy(outdoorC = it) } } }, unit = "°C", decimals = 1, nullable = false, min = -40.0, max = 40.0)
                }
                SwitchRow("Programmation du chauffage", o.heatingSchedule, { v -> vm.update { it.copy(heatingSchedule = v) } },
                    description = if (o.heatingSchedule) "Chauffage actif de ${Fmt.num(o.heatOnHour, 0)} h à ${Fmt.num(o.heatOffHour, 0)} h (puissance des radiateurs au régime d'étude)" else "Chauffage coupé pendant toute la simulation")
                if (o.heatingSchedule) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumberField("Marche", o.heatOnHour, { v -> v?.let { vm.update { x -> x.copy(heatOnHour = it) } } }, Modifier.weight(1f), unit = "h", decimals = 1, nullable = false, min = 0.0, max = 24.0)
                        NumberField("Arrêt", o.heatOffHour, { v -> v?.let { vm.update { x -> x.copy(heatOffHour = it) } } }, Modifier.weight(1f), unit = "h", decimals = 1, nullable = false, min = 0.0, max = 24.0)
                    }
                }
                DropdownField("Apports solaires", SolarProfile.entries, o.solar, { it.label }, { v -> vm.update { it.copy(solar = v) } })
                SwitchRow("Apports internes des pièces", o.internalGains, { v -> vm.update { it.copy(internalGains = v) } })
                ChoiceChips(listOf(false, true), o.multizone, { if (it) "Pièce par pièce" else "Maison entière" }, { v -> vm.update { it.copy(multizone = v) } })
                val rooms = data?.analyzed?.project?.building?.rooms.orEmpty()
                if (o.multizone) DropdownField("Pièce affichée", listOf<Long?>(null) + rooms.map { it.id }, o.roomId,
                    { id -> rooms.firstOrNull { it.id == id }?.name ?: "Toutes les pièces" }, { v -> vm.update { it.copy(roomId = v) } })
                DropdownField("Comparer avec un scénario", listOf<Long?>(null) + scenarios.map { it.first }, o.scenarioId,
                    { id -> scenarios.firstOrNull { it.first == id }?.second?.name ?: "Aucun" }, { v -> vm.update { it.copy(scenarioId = v) } })
                SwitchRow("Afficher la bande d'incertitude", o.band, { v -> vm.update { it.copy(band = v) } })
            }

            val d = data ?: return@ScrollColumn
            val sim = d.simulation.nominal
            val label: (Double) -> String = { x -> "${((o.startHour + x) % 24).toInt()} h" }
            SectionCard("Évolution des températures") {
                val series = mutableListOf<LineSeries>()
                val shown = if (o.multizone && o.roomId != null) sim.zones.filter { it.zoneId == o.roomId } else sim.zones
                shown.take(6).forEachIndexed { i, z ->
                    series += LineSeries(z.name + if (d.scenario != null) " (actuel)" else "", sim.timesH, z.airTempC, ChartPalette[i % ChartPalette.size],
                        low = if (o.band) d.simulation.lowAir[z.zoneId] else null, high = if (o.band) d.simulation.highAir[z.zoneId] else null)
                }
                d.scenarioResult?.let { r ->
                    val sz = if (o.multizone && o.roomId != null) r.zones.filter { it.zoneId == o.roomId } else r.zones
                    sz.take(6).forEachIndexed { i, z -> series += LineSeries("${z.name} (${d.scenario?.name})", r.timesH, z.airTempC, ChartPalette[(i + 3) % ChartPalette.size], dashed = true) }
                }
                series += LineSeries("Extérieur", sim.timesH, sim.outdoorC, MaterialTheme.colorScheme.outline, dashed = true)
                if (measurements.isNotEmpty()) series += LineSeries("Mesures", measurements.map { it.timeH }, measurements.map { it.tempC }, ChartPalette[4], pointsOnly = true)
                calibration?.let { c ->
                    val rid = o.roomId
                    val z = if (o.multizone && rid != null) c.calibrated.zone(rid) else c.calibrated.zones.firstOrNull()
                    z?.let { series += LineSeries("Modèle calé", c.calibrated.timesH, it.airTempC, ChartPalette[2]) }
                }
                LineChart(series, xLabel = label, height = 280.dp)
                HorizontalDivider()
                shown.forEach { z ->
                    val after = d.scenarioResult?.zone(z.zoneId)?.airTempC?.lastOrNull()
                    ValueRow("${z.name} à ${label(sim.timesH.last())}", Fmt.temp(z.airTempC.last()) + (after?.let { " → ${Fmt.temp(it)} (scénario)" } ?: ""))
                }
                val zones = d.model.zones
                val tau = zones.sumOf { it.totalCapacityJk } / zones.sumOf { it.steadyStateHWk }.coerceAtLeast(1e-9) / 3600.0
                ValueRow("Constante de temps du logement", "${Fmt.num(tau, 0)} h" + (d.scenarioTimeConstantH?.let { " → ${Fmt.num(it, 0)} h" } ?: ""))
                d.model.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                InfoBanner("Modèle RC à deux nœuds (air + masse des parois) par pièce, inertie évaluée selon la NF EN ISO 13786 (règle des 10 cm). " +
                    "La bande colorée représente l'incertitude liée à l'inertie et aux coefficients estimés.", kind = BannerKind.INFO)
            }

            SectionCard("Calage sur des températures mesurées", icon = Icons.Filled.Science,
                subtitle = "Relevez la température intérieure pendant une nuit chauffage coupé (heures écoulées depuis le début)") {
                if (measurements.isEmpty()) Text("Aucune mesure saisie.", style = MaterialTheme.typography.bodySmall)
                measurements.forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("t = ${Fmt.num(p.timeH, 1)} h (${label(p.timeH)}) : ${Fmt.temp(p.tempC)}", Modifier.weight(1f))
                        IconButton(onClick = { vm.removeMeasurement(p) }) { Icon(Icons.Filled.Delete, "Supprimer") }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { addPoint = true }) { Icon(Icons.Filled.Add, null); Text(" Mesure") }
                    Button(enabled = measurements.size >= 2, onClick = { vm.calibrate() }) { Text("Caler le modèle") }
                }
                calibration?.let { c ->
                    ValueRow("Facteur sur la capacité thermique", Fmt.num(c.capacityFactor, 2), emphasize = true)
                    ValueRow("Facteur sur les déperditions", Fmt.num(c.conductanceFactor, 2))
                    ValueRow("Écart quadratique avant / après", "${Fmt.num(c.rmseBeforeC, 2)} K / ${Fmt.num(c.rmseAfterC, 2)} K")
                    ValueRow("Constante de temps calée", "${Fmt.num(c.timeConstantH, 0)} h")
                    Text(if (measurements.size >= 6) "Capacité et déperditions calées (≥ 6 mesures)." else "Seule la capacité est calée (ajoutez au moins 6 mesures pour caler aussi les déperditions).",
                        style = MaterialTheme.typography.bodySmall)
                }
                if (measurements.isNotEmpty()) {
                    TextInput("Nom de la série", seriesName, { seriesName = it })
                    OutlinedButton(onClick = { vm.saveSeries(seriesName) }) { Text("Enregistrer la série") }
                }
                saved.forEach { (e, pts) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${e.name} · ${pts.size} mesures", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { vm.loadSeries(e, pts) }) { Text("Charger") }
                        IconButton(onClick = { vm.deleteSeries(e.id) }) { Icon(Icons.Filled.Delete, "Supprimer la série") }
                    }
                }
            }
        }
    }
    if (addPoint) {
        var t by remember { mutableStateOf<Double?>(null) }
        var temp by remember { mutableStateOf<Double?>(null) }
        AlertDialog(
            onDismissRequest = { addPoint = false },
            title = { Text("Mesure de température") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("Temps écoulé depuis ${Fmt.num(o.startHour, 0)} h", t, { t = it }, unit = "h", decimals = 2, min = 0.0, max = 168.0)
                    NumberField("Température mesurée", temp, { temp = it }, unit = "°C", decimals = 1, min = -20.0, max = 40.0)
                }
            },
            confirmButton = { TextButton(enabled = t != null && temp != null, onClick = { vm.addMeasurement(t!!, temp!!); addPoint = false }) { Text("Ajouter") } },
            dismissButton = { TextButton(onClick = { addPoint = false }) { Text("Annuler") } },
        )
    }
}
