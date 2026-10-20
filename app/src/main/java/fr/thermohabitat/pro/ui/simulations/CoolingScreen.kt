package fr.thermohabitat.pro.ui.simulations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import fr.thermohabitat.pro.ui.components.EmptyState
import fr.thermohabitat.pro.ui.components.FieldRow
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
import fr.thermohabitat.pro.ui.navigation.HomeRoute
import fr.thermohabitat.pro.ui.navigation.HouseRoute
import fr.thermohabitat.pro.ui.navigation.RadiatorsRoute
import fr.thermohabitat.pro.ui.navigation.navigateToTab
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
import kotlin.math.roundToLong

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

/** Ce qui manque pour simuler : affiché à la place d'un écran vide. */
enum class CoolingInput { NO_STUDY, NO_ROOMS, READY }

/** Heure d'horloge [startHour] + [elapsedH], arrondie à la minute : « 23 h 20 », ou « 23h20 » ([compact], axe du graphique). */
private fun coolingClock(startHour: Double, elapsedH: Double, compact: Boolean = false): String {
    val total = ((startHour + elapsedH) * 60.0).roundToLong().mod(1440L)
    val h = total / 60
    val m = (total % 60).toString().padStart(2, '0')
    return when {
        compact -> if (m == "00") "${h}h" else "${h}h$m"
        m == "00" -> "$h h"
        else -> "$h h $m"
    }
}

@HiltViewModel
class CoolingViewModel @Inject constructor(
    studyService: StudyService,
    private val repository: ProjectRepository,
) : ViewModel() {
    val options = MutableStateFlow(CoolingOptions())
    private val simulator = RcSimulator()
    /** Étude active enveloppée : null pendant le chargement, [Active.project] null s'il n'y a aucune étude. */
    private class Active(val project: AnalyzedProject?)
    private val active = studyService.observeActive().map { Active(it) }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val project = active.map { it?.project }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Étude ou pièces manquantes (null pendant le chargement), pour guider l'utilisateur au lieu d'un écran vide. */
    val input = active.map { a ->
        when {
            a == null -> null
            a.project == null -> CoolingInput.NO_STUDY
            a.project.project.building.rooms.isEmpty() -> CoolingInput.NO_ROOMS
            else -> CoolingInput.READY
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Vrai si le dernier calcul a échoué (paramètres incohérents) : signalé au lieu d'un écran vide. */
    val failed = MutableStateFlow(false)

    @OptIn(ExperimentalCoroutinesApi::class)
    val scenarios = project.map { it?.project?.id }.distinctUntilChanged().flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else repository.observeScenarios(id).map { list -> list.map { it.first.id to it.second } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val measurements = MutableStateFlow<List<MeasuredPoint>>(emptyList())
    val calibration = MutableStateFlow<CalibrationResult?>(null)
    /** Série rechargée enregistrée sans ses conditions (version antérieure) : à vérifier avant le calage. */
    val conditionsUnknown = MutableStateFlow(false)
    @OptIn(ExperimentalCoroutinesApi::class)
    val savedSeries = project.map { it?.project?.id }.distinctUntilChanged().flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else repository.observeMeasurements(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val data = combine(project, options, scenarios) { p, o, sc ->
        if (p == null || p.project.building.rooms.isEmpty()) null
        else runCatching { compute(p, o, sc) }.also { failed.value = it.isFailure }.getOrNull()
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
                outdoorC = if (o.dailyOutdoor) null else o.outdoorC,
                outdoorMinC = if (o.dailyOutdoor) o.outdoorMinC else null, outdoorMaxC = if (o.dailyOutdoor) o.outdoorMaxC else null,
                initialC = o.initialC, durationH = o.durationH, startClockHour = o.startHour),
            measurements.value,
        )
    }

    fun loadSeries(e: MeasurementSeriesEntity, points: List<MeasuredPoint>) {
        measurements.value = points
        // Les conditions de la mesure sont rétablies pour que le calage porte sur les bonnes hypothèses.
        val daily = e.outdoorMinC != null && e.outdoorMaxC != null
        update { it.copy(
            startHour = e.startClockHour,
            dailyOutdoor = if (daily) true else if (e.outdoorC != null) false else it.dailyOutdoor,
            outdoorC = e.outdoorC ?: it.outdoorC,
            outdoorMinC = e.outdoorMinC ?: it.outdoorMinC,
            outdoorMaxC = e.outdoorMaxC ?: it.outdoorMaxC,
            initialC = e.initialC ?: points.firstOrNull { p -> p.timeH <= 0.01 }?.tempC ?: it.initialC,
            roomId = e.roomId, multizone = e.roomId != null,
            durationH = maxOf(e.durationH ?: it.durationH, points.maxOfOrNull { p -> p.timeH } ?: 0.0),
        ) }
        conditionsUnknown.value = e.initialC == null
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
    val conditionsUnknown by vm.conditionsUnknown.collectAsStateWithLifecycle()
    val input by vm.input.collectAsStateWithLifecycle()
    val failed by vm.failed.collectAsStateWithLifecycle()
    var addPoint by remember { mutableStateOf(false) }
    var seriesName by remember { mutableStateOf("") }

    ScreenScaffold(title = "Simulation dynamique", onBack = { nav.popBackStack() }) { padding ->
        ScrollColumn(padding) {
            // Sans étude ou sans pièce, rien n'est simulable : on guide vers la saisie au lieu d'un formulaire sans résultat.
            when (input) {
                CoolingInput.NO_STUDY -> {
                    EmptyState("Aucune étude ouverte", "Ouvrez ou créez une étude depuis l'accueil pour simuler le refroidissement du logement.",
                        actionLabel = "Aller à l'accueil", onAction = { nav.navigateToTab(HomeRoute) })
                    return@ScrollColumn
                }
                CoolingInput.NO_ROOMS -> {
                    EmptyState("Aucune pièce à simuler", "La simulation a besoin des pièces et de leurs parois : saisissez-les dans l'onglet Maison.",
                        actionLabel = "Saisir la maison", onAction = { nav.navigateToTab(HouseRoute) })
                    return@ScrollColumn
                }
                else -> Unit
            }
            SectionCard("Conditions simulées", icon = Icons.Filled.AcUnit) {
                FieldRow {
                    NumberField("Heure de début", o.startHour, { v -> v?.let { vm.update { x -> x.copy(startHour = it % 24) } } }, unit = "h", decimals = 1, nullable = false, min = 0.0, max = 24.0)
                    NumberField("Durée", o.durationH, { v -> v?.let { vm.update { x -> x.copy(durationH = it) } } }, unit = "h", decimals = 0, nullable = false, min = 1.0, max = 168.0)
                }
                NumberField("Température initiale", o.initialC, { v -> v?.let { vm.update { x -> x.copy(initialC = it) } } }, unit = "°C", decimals = 1, nullable = false, min = 0.0, max = 35.0,
                    supportingText = "Air intérieur au début de la simulation")
                ChoiceChips(listOf(false, true), o.dailyOutdoor, { if (it) "Extérieur variable (jour/nuit)" else "Extérieur constant" }, { v -> vm.update { it.copy(dailyOutdoor = v) } })
                if (o.dailyOutdoor) {
                    FieldRow {
                        NumberField("Minimum (6 h)", o.outdoorMinC, { v -> v?.let { vm.update { x -> x.copy(outdoorMinC = it) } } }, unit = "°C", decimals = 1, nullable = false)
                        NumberField("Maximum (18 h)", o.outdoorMaxC, { v -> v?.let { vm.update { x -> x.copy(outdoorMaxC = it) } } }, unit = "°C", decimals = 1, nullable = false)
                    }
                } else {
                    NumberField("Température extérieure", o.outdoorC, { v -> v?.let { vm.update { x -> x.copy(outdoorC = it) } } }, unit = "°C", decimals = 1, nullable = false, min = -40.0, max = 40.0)
                }
                SwitchRow("Programmation du chauffage", o.heatingSchedule, { v -> vm.update { it.copy(heatingSchedule = v) } },
                    description = if (o.heatingSchedule) "Chauffage actif de ${Fmt.num(o.heatOnHour, 0)} h à ${Fmt.num(o.heatOffHour, 0)} h (puissance des radiateurs au régime d'étude)" else "Chauffage coupé pendant toute la simulation")
                if (o.heatingSchedule) {
                    FieldRow {
                        NumberField("Marche", o.heatOnHour, { v -> v?.let { vm.update { x -> x.copy(heatOnHour = it) } } }, unit = "h", decimals = 1, nullable = false, min = 0.0, max = 24.0)
                        NumberField("Arrêt", o.heatOffHour, { v -> v?.let { vm.update { x -> x.copy(heatOffHour = it) } } }, unit = "h", decimals = 1, nullable = false, min = 0.0, max = 24.0)
                    }
                    // Sans radiateur validé, la programmation n'apporte aucune puissance : la courbe baisserait sans explication.
                    if (data?.model?.zones?.sumOf { it.maxHeatingW }?.let { it <= 0.0 } == true) {
                        InfoBanner("Aucun radiateur validé : la programmation n'apporte aucune puissance dans la simulation.", kind = BannerKind.WARNING)
                        TextButton(onClick = { nav.navigateToTab(RadiatorsRoute) }) { Text("Valider les radiateurs") }
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

            val d = data
            if (d == null) {
                // Calcul en cours ou en échec : jamais d'écran vide sous le formulaire.
                if (failed) InfoBanner("Simulation impossible avec ces paramètres : vérifiez la durée, les heures et les températures.", kind = BannerKind.WARNING)
                else Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Calcul en cours…", style = MaterialTheme.typography.bodyMedium)
                }
                return@ScrollColumn
            }
            val sim = d.simulation.nominal
            // Heures exactes (à la minute) : les graduations tombent toutes les 1 h 20 sur 8 h, l'arrondi à l'heure les faussait.
            val clock: (Double) -> String = { x -> coolingClock(o.startHour, x) }
            val axisLabel: (Double) -> String = { x -> coolingClock(o.startHour, x, compact = true) }
            SectionCard("Évolution des températures") {
                val series = mutableListOf<LineSeries>()
                val shown = if (o.multizone && o.roomId != null) sim.zones.filter { it.zoneId == o.roomId } else sim.zones
                // Toutes les pièces avec un scénario : au plus 3 paires de courbes, sans bande, pour rester lisible.
                val crowded = o.multizone && o.roomId == null && d.scenarioResult != null
                val plotted = shown.take(if (crowded) 3 else 6)
                val band = o.band && !crowded
                plotted.forEachIndexed { i, z ->
                    // Une couleur par pièce : trait plein pour l'état actuel, pointillé de même couleur pour le scénario.
                    val color = ChartPalette[i % ChartPalette.size]
                    series += LineSeries(z.name + if (d.scenario != null) " (actuel)" else "", sim.timesH, z.airTempC, color,
                        low = if (band) d.simulation.lowAir[z.zoneId] else null, high = if (band) d.simulation.highAir[z.zoneId] else null)
                    d.scenarioResult?.let { r ->
                        r.zone(z.zoneId)?.let { sz -> series += LineSeries("${z.name} (scénario)", r.timesH, sz.airTempC, color, dashed = true) }
                    }
                }
                series += LineSeries("Extérieur", sim.timesH, sim.outdoorC, MaterialTheme.colorScheme.outline, dashed = true)
                // Couleurs neutres, distinctes de celles des pièces.
                if (measurements.isNotEmpty()) series += LineSeries("Mesures", measurements.map { it.timeH }, measurements.map { it.tempC }, MaterialTheme.colorScheme.onSurface, pointsOnly = true)
                calibration?.let { c ->
                    val rid = o.roomId
                    val z = if (o.multizone && rid != null) c.calibrated.zone(rid) else c.calibrated.zones.firstOrNull()
                    z?.let { series += LineSeries("Modèle calé", c.calibrated.timesH, it.airTempC, MaterialTheme.colorScheme.tertiary) }
                }
                d.scenario?.let { Text("Pointillés : scénario « ${it.name} »", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                LineChart(series, xLabel = axisLabel, height = 280.dp)
                val limited = shown.size > plotted.size
                val bandHidden = crowded && o.band
                if (limited || bandHidden) {
                    Text(
                        when {
                            limited && bandHidden -> "Seules ${plotted.size} pièces sont tracées, sans bande d'incertitude : choisissez une pièce pour la voir en détail."
                            limited -> "Seules les ${plotted.size} premières pièces sont tracées : choisissez une pièce pour la voir en détail."
                            else -> "Bande d'incertitude masquée pour comparer toutes les pièces : choisissez une pièce pour l'afficher."
                        },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider()
                shown.forEach { z ->
                    val after = d.scenarioResult?.zone(z.zoneId)?.airTempC?.lastOrNull()
                    ValueRow("${z.name} à ${clock(sim.timesH.last())}", Fmt.temp(z.airTempC.last()) + (after?.let { " → ${Fmt.temp(it)} (scénario)" } ?: ""))
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
                if (conditionsUnknown && measurements.isNotEmpty()) {
                    InfoBanner("Conditions de cette série non enregistrées (version antérieure) : vérifiez la température initiale et " +
                        "l'extérieur ci-dessus avant le calage.", kind = BannerKind.WARNING)
                }
                measurements.forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("t = ${Fmt.num(p.timeH, 1)} h (${clock(p.timeH)}) : ${Fmt.temp(p.tempC)}", Modifier.weight(1f))
                        IconButton(onClick = { vm.removeMeasurement(p) }) { Icon(Icons.Filled.Delete, "Supprimer") }
                    }
                }
                // Boutons pleine largeur empilés : avec une grande police, côte à côte, « Caler le modèle » passait sur 3 lignes.
                OutlinedButton(onClick = { addPoint = true }, Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Add, null); Spacer(Modifier.width(8.dp)); Text("Ajouter une mesure")
                }
                Button(onClick = { vm.calibrate() }, Modifier.fillMaxWidth(), enabled = measurements.size >= 2) { Text("Caler le modèle") }
                if (measurements.size < 2) {
                    Text("Saisissez au moins 2 mesures pour caler le modèle (6 pour caler aussi les déperditions).",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    OutlinedButton(onClick = { vm.saveSeries(seriesName) }, Modifier.fillMaxWidth()) { Text("Enregistrer la série") }
                }
                saved.forEach { (e, pts) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(e.name, style = MaterialTheme.typography.bodyMedium)
                            Text("${pts.size} mesures", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
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
                    NumberField("Temps écoulé", t, { t = it }, unit = "h", decimals = 2, min = 0.0, max = 168.0,
                        supportingText = "Heures depuis le début (${coolingClock(o.startHour, 0.0)})")
                    NumberField("Température", temp, { temp = it }, unit = "°C", decimals = 1, min = -20.0, max = 40.0)
                }
            },
            confirmButton = { TextButton(enabled = t != null && temp != null, onClick = { vm.addMeasurement(t!!, temp!!); addPoint = false }) { Text("Ajouter") } },
            dismissButton = { TextButton(onClick = { addPoint = false }) { Text("Annuler") } },
        )
    }
}
