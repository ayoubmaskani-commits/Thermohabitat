package fr.thermohabitat.pro.ui.simulations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material3.AlertDialog
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
import fr.thermohabitat.pro.data.repository.ProjectRepository
import fr.thermohabitat.pro.domain.AnalyzedProject
import fr.thermohabitat.pro.domain.StudyService
import fr.thermohabitat.pro.ui.components.BannerKind
import fr.thermohabitat.pro.ui.components.ChartPalette
import fr.thermohabitat.pro.ui.components.InfoBanner
import fr.thermohabitat.pro.ui.components.LineChart
import fr.thermohabitat.pro.ui.components.LineSeries
import fr.thermohabitat.pro.ui.components.NumberField
import fr.thermohabitat.pro.ui.components.ScreenScaffold
import fr.thermohabitat.pro.ui.components.ScrollColumn
import fr.thermohabitat.pro.ui.components.SectionCard
import fr.thermohabitat.pro.ui.components.TextInput
import fr.thermohabitat.pro.ui.components.ValueRow
import fr.thermohabitat.pro.ui.util.Fmt
import fr.thermohabitat.thermal.StudySettings
import fr.thermohabitat.thermal.calc.BivalenceResult
import fr.thermohabitat.thermal.calc.HeatPumpAnalysis
import fr.thermohabitat.thermal.calc.HeatPumpPerformance
import fr.thermohabitat.thermal.calc.HeatPumpPoint
import fr.thermohabitat.thermal.calc.HeatingCurve
import fr.thermohabitat.thermal.calc.SeasonalPerformance
import fr.thermohabitat.thermal.calc.SupplyTemperatureSolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.ceil

data class HeatPumpData(
    val analyzed: AnalyzedProject,
    val interiorC: Double,
    /** Température de départ nécessaire selon la température extérieure (radiateurs validés). */
    val requiredCurve: List<Pair<Double, Double>>,
    val bivalence: BivalenceResult,
    val seasonal: SeasonalPerformance,
) {
    val settings: StudySettings get() = analyzed.project.settings
}

@HiltViewModel
class HeatPumpViewModel @Inject constructor(
    studyService: StudyService,
    private val repository: ProjectRepository,
) : ViewModel() {
    val data = studyService.observeActive().map { p ->
        p?.let { a ->
            val b = a.project.building
            val hl = a.result.heatLoss
            val s = a.project.settings
            val thetaE = b.climate.designOutdoorTempC
            val interior = if (hl.heatedVolumeM3 > 0) b.rooms.sumOf { it.setpointC * it.volumeM3 } / hl.heatedVolumeM3 else b.setpointC
            val required = mutableListOf<Pair<Double, Double>>()
            var t = thetaE
            while (t <= interior - 2) {
                val factor = ((interior - t) / (interior - thetaE)).coerceIn(0.0, 1.5)
                SupplyTemperatureSolver.solve(b, hl, s.solverMode, loadFactor = factor).requiredSupplyC?.let { required += t to it }
                t += 1.0
            }
            val perf = HeatPumpPerformance(s.heatPump, s.genericCarnotEfficiency)
            HeatPumpData(
                a, interior, required,
                HeatPumpAnalysis.bivalence(hl.totalKw, interior, thetaE, s.heatingCurve, perf),
                HeatPumpAnalysis.seasonal(hl.totalKw, thetaE, s.heatingCurve, perf),
            )
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private fun edit(transform: (StudySettings) -> StudySettings) = viewModelScope.launch {
        val id = data.value?.analyzed?.project?.id ?: return@launch
        repository.updateSettings(id, transform)
    }

    fun updateCurve(t: (HeatingCurve) -> HeatingCurve) = edit { it.copy(heatingCurve = t(it.heatingCurve)) }
    fun updatePump(t: (fr.thermohabitat.thermal.calc.HeatPumpModel) -> fr.thermohabitat.thermal.calc.HeatPumpModel) = edit { it.copy(heatPump = t(it.heatPump)) }
    fun setCarnot(v: Double) = edit { it.copy(genericCarnotEfficiency = v) }

    /** Cale le point de base de la loi d'eau sur le besoin réel des émetteurs à θe. */
    fun fitCurveToNeed() {
        val d = data.value ?: return
        val req = d.analyzed.result.supply.requiredSupplyC ?: return
        val thetaE = d.analyzed.project.building.climate.designOutdoorTempC
        updateCurve { it.copy(designOutdoorC = thetaE, designSupplyC = ceil(req), designDeltaK = d.settings.systemDeltaK, maxSupplyC = maxOf(it.maxSupplyC, ceil(req))) }
    }
}

@Composable
fun HeatPumpScreen(nav: NavController, vm: HeatPumpViewModel = hiltViewModel()) {
    val data by vm.data.collectAsStateWithLifecycle()
    var addPoint by remember { mutableStateOf(false) }
    ScreenScaffold(title = "Pompe à chaleur", onBack = { nav.popBackStack() }) { padding ->
        ScrollColumn(padding) {
            val d = data ?: return@ScrollColumn
            val s = d.settings
            val hl = d.analyzed.result.heatLoss
            val curve = s.heatingCurve
            InfoBanner("Le besoin du bâtiment (${Fmt.kw(hl.totalW)} à ${Fmt.temp(hl.designOutdoorC)}) n'est pas la puissance commerciale de la PAC : " +
                "celle-ci est souvent annoncée à +7 °C extérieur et 35 °C d'eau, et diminue par grand froid.", kind = BannerKind.WARNING)

            SectionCard("Besoins et émetteurs", icon = Icons.Filled.LocalFireDepartment) {
                ValueRow("Charge de conception à θe", Fmt.kw(hl.totalW), emphasize = true)
                ValueRow("Coefficient H", "${Fmt.num(hl.totalHWk, 1)} W/K")
                ValueRow("Départ nécessaire à θe", d.analyzed.result.supply.requiredSupplyC?.let { Fmt.temp(it) } ?: "Non atteignable")
                d.analyzed.result.regimeCoverages.filter { it.supplyC in listOf(45.0, 50.0, 55.0, 60.0) }.forEach { c ->
                    ValueRow("Couverture à ${c.supplyC.toInt()}/${c.returnC?.toInt() ?: "–"} °C", "${Fmt.pct(c.coveredFraction)} · ${c.insufficientRooms.size} pièce(s) insuffisante(s)")
                }
            }

            SectionCard("Loi d'eau", icon = Icons.AutoMirrored.Filled.ShowChart, subtitle = "Température de départ selon la température extérieure") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("T. ext. de base", curve.designOutdoorC, { v -> v?.let { vm.updateCurve { c -> c.copy(designOutdoorC = it) } } }, Modifier.weight(1f), unit = "°C", decimals = 1, nullable = false)
                    NumberField("Départ à la base", curve.designSupplyC, { v -> v?.let { vm.updateCurve { c -> c.copy(designSupplyC = it) } } }, Modifier.weight(1f), unit = "°C", decimals = 0, nullable = false, min = 20.0, max = 90.0)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("T. ext. pied de courbe", curve.footOutdoorC, { v -> v?.let { vm.updateCurve { c -> c.copy(footOutdoorC = it) } } }, Modifier.weight(1f), unit = "°C", decimals = 1, nullable = false)
                    NumberField("Départ au pied", curve.footSupplyC, { v -> v?.let { vm.updateCurve { c -> c.copy(footSupplyC = it) } } }, Modifier.weight(1f), unit = "°C", decimals = 0, nullable = false, min = 15.0, max = 60.0)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("Départ minimal", curve.minSupplyC, { v -> v?.let { vm.updateCurve { c -> c.copy(minSupplyC = it) } } }, Modifier.weight(1f), unit = "°C", decimals = 0, nullable = false)
                    NumberField("Départ maximal", curve.maxSupplyC, { v -> v?.let { vm.updateCurve { c -> c.copy(maxSupplyC = it) } } }, Modifier.weight(1f), unit = "°C", decimals = 0, nullable = false)
                }
                ValueRow("Pente", Fmt.num(curve.slope, 2))
                OutlinedButton(onClick = { vm.fitCurveToNeed() }) { Text("Ajuster la loi d'eau sur le besoin des radiateurs") }
                val xs = (0..30).map { curve.designOutdoorC + it * (20.0 - curve.designOutdoorC) / 30.0 }
                LineChart(
                    listOfNotNull(
                        LineSeries("Loi d'eau", xs, xs.map { curve.supplyAt(it) }, ChartPalette[0]),
                        if (d.requiredCurve.isNotEmpty()) LineSeries("Départ nécessaire", d.requiredCurve.map { it.first }, d.requiredCurve.map { it.second }, ChartPalette[1], dashed = true) else null,
                    ),
                    xLabel = { "${Fmt.num(it, 0)}°" },
                )
                Text("Abscisse : température extérieure. Si la courbe « départ nécessaire » passe au-dessus de la loi d'eau, des pièces seront sous-chauffées.",
                    style = MaterialTheme.typography.bodySmall)
            }

            SectionCard("Pompe à chaleur air/eau étudiée") {
                TextInput("Désignation", s.heatPump.name, { v -> vm.updatePump { it.copy(name = v) } })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextInput("Fabricant", s.heatPump.manufacturer, { v -> vm.updatePump { it.copy(manufacturer = v) } }, Modifier.weight(1f))
                    TextInput("Référence", s.heatPump.reference, { v -> vm.updatePump { it.copy(reference = v) } }, Modifier.weight(1f))
                }
                NumberField("Puissance nominale annoncée (information)", s.heatPump.nominalCapacityKw, { v -> vm.updatePump { it.copy(nominalCapacityKw = v) } },
                    unit = "kW", decimals = 1, min = 0.5, max = 100.0, supportingText = "Conditions : A${Fmt.num(s.heatPump.nominalOutdoorC, 0)}/W${Fmt.num(s.heatPump.nominalWaterC, 0)}")
                Text("Données constructeur (puissance et COP selon les températures)", style = MaterialTheme.typography.labelLarge)
                if (s.heatPump.points.isEmpty()) {
                    InfoBanner("Sans données constructeur, le COP est estimé par un modèle générique (${Fmt.pct(s.genericCarnotEfficiency)} du COP de Carnot) " +
                        "et la puissance n'est connue qu'à partir de la puissance nominale (dégradation estimée). Saisissez les points de la fiche technique.")
                }
                s.heatPump.points.sortedWith(compareBy({ it.waterC }, { it.outdoorC })).forEachIndexed { _, p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("A${Fmt.num(p.outdoorC, 0)}/W${Fmt.num(p.waterC, 0)} : ${p.capacityKw?.let { "${Fmt.num(it, 1)} kW" } ?: "–"} · COP ${p.cop?.let { Fmt.num(it, 2) } ?: "–"}",
                            Modifier.weight(1f))
                        IconButton(onClick = { vm.updatePump { m -> m.copy(points = m.points - p) } }) { Icon(Icons.Filled.Delete, "Supprimer") }
                    }
                }
                OutlinedButton(onClick = { addPoint = true }) { Icon(Icons.Filled.Add, null); Text(" Ajouter un point constructeur") }
                NumberField("Rendement exergétique du modèle générique", s.genericCarnotEfficiency * 100, { v -> v?.let { vm.setCarnot(it / 100) } },
                    unit = "%", decimals = 0, nullable = false, min = 20.0, max = 70.0)
            }

            SectionCard("Bivalence et performance saisonnière") {
                val bv = d.bivalence
                ValueRow("Puissance PAC disponible à θe", bv.capacityAtDesignKw?.let { "${Fmt.num(it, 1)} kW" } ?: "Inconnue (données manquantes)")
                ValueRow("Point de bivalence", when {
                    bv.capacityAtDesignKw == null -> "Indéterminé"
                    bv.bivalentTempC == null -> "PAC suffisante jusqu'à θe"
                    else -> Fmt.temp(bv.bivalentTempC)
                }, emphasize = true)
                bv.backupAtDesignKw?.takeIf { it > 0 }?.let { ValueRow("Appoint nécessaire à θe", "${Fmt.num(it, 1)} kW") }
                val withCap = bv.points.filter { it.capacityKw != null }
                if (withCap.isNotEmpty()) {
                    LineChart(
                        listOf(
                            LineSeries("Besoin", bv.points.map { it.outdoorC }, bv.points.map { it.loadKw }, ChartPalette[1]),
                            LineSeries("Puissance PAC", withCap.map { it.outdoorC }, withCap.map { it.capacityKw!! }, ChartPalette[0]),
                        ),
                        xLabel = { "${Fmt.num(it, 0)}°" }, yUnit = "kW",
                    )
                }
                HorizontalDivider()
                ValueRow("SCOP estimé (${d.seasonal.climateLabel})", Fmt.num(d.seasonal.scop, 2), emphasize = true)
                ValueRow("Part d'appoint électrique", Fmt.pct(d.seasonal.backupShare))
                Text(
                    if (d.seasonal.generic) "SCOP issu du modèle générique : ordre de grandeur à confirmer avec les données constructeur."
                    else "SCOP calculé à partir des données constructeur saisies et de la loi d'eau.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
    if (addPoint) AddHeatPumpPointDialog(onDismiss = { addPoint = false }) { p -> addPoint = false; vm.updatePump { it.copy(points = it.points + p) } }
}

@Composable
private fun AddHeatPumpPointDialog(onDismiss: () -> Unit, onAdd: (HeatPumpPoint) -> Unit) {
    var outdoor by remember { mutableStateOf<Double?>(-7.0) }
    var water by remember { mutableStateOf<Double?>(55.0) }
    var capacity by remember { mutableStateOf<Double?>(null) }
    var cop by remember { mutableStateOf<Double?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Point constructeur") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("Température extérieure", outdoor, { outdoor = it }, unit = "°C", decimals = 0)
                NumberField("Température d'eau (départ)", water, { water = it }, unit = "°C", decimals = 0)
                NumberField("Puissance calorifique", capacity, { capacity = it }, unit = "kW", decimals = 2, min = 0.0)
                NumberField("COP", cop, { cop = it }, decimals = 2, min = 0.5, max = 10.0)
            }
        },
        confirmButton = {
            TextButton(enabled = outdoor != null && water != null && (capacity != null || cop != null),
                onClick = { onAdd(HeatPumpPoint(outdoor!!, water!!, capacity, cop)) }) { Text("Ajouter") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
