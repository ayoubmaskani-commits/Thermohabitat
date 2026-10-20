package fr.thermohabitat.pro.ui.simulations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import fr.thermohabitat.pro.ui.components.EmptyState
import fr.thermohabitat.pro.ui.components.FieldRow
import fr.thermohabitat.pro.ui.components.InfoBanner
import fr.thermohabitat.pro.ui.components.LineChart
import fr.thermohabitat.pro.ui.components.LineSeries
import fr.thermohabitat.pro.ui.components.NumberField
import fr.thermohabitat.pro.ui.components.ScreenScaffold
import fr.thermohabitat.pro.ui.components.ScrollColumn
import fr.thermohabitat.pro.ui.components.SectionCard
import fr.thermohabitat.pro.ui.components.TextInput
import fr.thermohabitat.pro.ui.components.ValueRow
import fr.thermohabitat.pro.ui.navigation.HouseRoute
import fr.thermohabitat.pro.ui.navigation.navigateToTab
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
            if (hl.totalW <= 0.0) {
                // Sans besoin calculé, charge, SCOP et bivalence seraient des zéros trompeurs.
                EmptyState("Aucun besoin calculé", "Saisissez les pièces de la maison (dimensions, parois) pour dimensionner la pompe à chaleur.",
                    actionLabel = "Saisir les pièces", onAction = { nav.navigateToTab(HouseRoute) })
                return@ScrollColumn
            }
            val curve = s.heatingCurve
            val supply = d.analyzed.result.supply
            val bv = d.bivalence
            // Température extérieure de base écrite en clair plutôt que « θe ».
            val base = Fmt.temp(hl.designOutdoorC, 0)
            val scopText = if (d.seasonal.scop > 0) Fmt.num(d.seasonal.scop, 2) else "–"

            // L'essentiel d'abord ; le détail et les saisies suivent.
            SectionCard("En résumé", icon = Icons.Filled.LocalFireDepartment) {
                ValueRow("Puissance nécessaire par grand froid ($base)", Fmt.kw(hl.totalW), emphasize = true)
                ValueRow("SCOP estimé", scopText, emphasize = true)
                ValueRow("Point de bivalence", when {
                    bv.capacityAtDesignKw == null -> "Indéterminé (puissance PAC inconnue)"
                    bv.bivalentTempC == null -> "Aucun (PAC suffisante)"
                    else -> Fmt.temp(bv.bivalentTempC, 0)
                })
                ValueRow("Départ d'eau nécessaire à $base", Fmt.supply(supply))
            }

            SectionCard("Besoins et émetteurs", subtitle = "Couverture par les radiateurs validés selon le régime d'eau") {
                ValueRow("Déperdition par degré d'écart (H)", "${Fmt.num(hl.totalHWk, 0)} W/K")
                d.analyzed.result.regimeCoverages.filter { it.supplyC in listOf(45.0, 50.0, 55.0, 60.0) }.forEach { c ->
                    ValueRow("Couverture à ${c.supplyC.toInt()}/${c.returnC?.toInt() ?: "–"} °C", "${Fmt.coverage(c)} · ${c.insufficientRooms.size} pièce(s) insuffisante(s)")
                }
            }

            SectionCard("Loi d'eau", icon = Icons.AutoMirrored.Filled.ShowChart, subtitle = "Température de départ selon la température extérieure") {
                Text("Point de base (grand froid)", style = MaterialTheme.typography.labelLarge)
                FieldRow {
                    NumberField("Temp. extérieure", curve.designOutdoorC, { v -> v?.let { vm.updateCurve { c -> c.copy(designOutdoorC = it) } } }, unit = "°C", decimals = 1, nullable = false)
                    NumberField("Départ d'eau", curve.designSupplyC, { v -> v?.let { vm.updateCurve { c -> c.copy(designSupplyC = it) } } }, unit = "°C", decimals = 0, nullable = false, min = 20.0, max = 90.0)
                }
                Text("Pied de courbe (redoux)", style = MaterialTheme.typography.labelLarge)
                FieldRow {
                    NumberField("Temp. extérieure", curve.footOutdoorC, { v -> v?.let { vm.updateCurve { c -> c.copy(footOutdoorC = it) } } }, unit = "°C", decimals = 1, nullable = false)
                    NumberField("Départ d'eau", curve.footSupplyC, { v -> v?.let { vm.updateCurve { c -> c.copy(footSupplyC = it) } } }, unit = "°C", decimals = 0, nullable = false, min = 15.0, max = 60.0)
                }
                Text("Limites du départ d'eau", style = MaterialTheme.typography.labelLarge)
                FieldRow {
                    NumberField("Minimum", curve.minSupplyC, { v -> v?.let { vm.updateCurve { c -> c.copy(minSupplyC = it) } } }, unit = "°C", decimals = 0, nullable = false, min = 15.0, max = 90.0)
                    NumberField("Maximum", curve.maxSupplyC, { v -> v?.let { vm.updateCurve { c -> c.copy(maxSupplyC = it) } } }, unit = "°C", decimals = 0, nullable = false, min = 15.0, max = 90.0)
                }
                if (curve.minSupplyC > curve.maxSupplyC) {
                    InfoBanner("Le départ minimal dépasse le départ maximal : la loi d'eau est bornée entre ces deux valeurs dans l'ordre croissant.", kind = BannerKind.WARNING)
                }
                ValueRow("Pente", Fmt.num(curve.slope, 2))
                // Ajustement possible seulement si le départ nécessaire est connu (radiateurs validés, départ atteignable).
                val canFit = supply.requiredSupplyC != null
                OutlinedButton(onClick = { vm.fitCurveToNeed() }, enabled = canFit) { Text("Ajuster la loi d'eau sur le besoin des radiateurs") }
                if (!canFit) {
                    Text(
                        if (supply.rooms.none { it.hasEmitters }) "Validez au moins un radiateur pour ajuster la loi d'eau et tracer le départ nécessaire."
                        else "Départ nécessaire non atteignable sous 90 °C : ajustement impossible.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val xs = (0..30).map { curve.designOutdoorC + it * (20.0 - curve.designOutdoorC) / 30.0 }
                LineChart(
                    listOfNotNull(
                        LineSeries("Loi d'eau", xs, xs.map { curve.supplyAt(it) }, ChartPalette[0]),
                        if (d.requiredCurve.isNotEmpty()) LineSeries("Départ nécessaire", d.requiredCurve.map { it.first }, d.requiredCurve.map { it.second }, ChartPalette[1], dashed = true) else null,
                    ),
                    xLabel = { "${Fmt.num(it, 0)}°" },
                )
                Text(
                    "Abscisse : température extérieure." + if (d.requiredCurve.isNotEmpty())
                        " Si la courbe « départ nécessaire » passe au-dessus de la loi d'eau, des pièces seront sous-chauffées." else "",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            SectionCard("Pompe à chaleur air/eau étudiée") {
                TextInput("Désignation", s.heatPump.name, { v -> vm.updatePump { it.copy(name = v) } })
                FieldRow {
                    TextInput("Fabricant", s.heatPump.manufacturer, { v -> vm.updatePump { it.copy(manufacturer = v) } })
                    TextInput("Référence", s.heatPump.reference, { v -> vm.updatePump { it.copy(reference = v) } })
                }
                InfoBanner("Le besoin du bâtiment (${Fmt.kw(hl.totalW)} à $base) n'est pas la puissance commerciale de la PAC : " +
                    "celle-ci est souvent annoncée à +7 °C extérieur et 35 °C d'eau, et diminue par grand froid.", kind = BannerKind.WARNING)
                NumberField("Puissance nominale", s.heatPump.nominalCapacityKw, { v -> vm.updatePump { it.copy(nominalCapacityKw = v) } },
                    unit = "kW", decimals = 1, min = 0.5, max = 100.0,
                    supportingText = "Information commerciale, à air ${Fmt.temp(s.heatPump.nominalOutdoorC, 0)} et eau ${Fmt.temp(s.heatPump.nominalWaterC, 0)}")
                Text("Données constructeur (puissance et COP selon les températures)", style = MaterialTheme.typography.labelLarge)
                if (s.heatPump.points.isEmpty()) {
                    InfoBanner("Sans données constructeur, le COP est estimé par un modèle générique (${Fmt.pct(s.genericCarnotEfficiency)} du COP de Carnot) " +
                        "et la puissance n'est connue qu'à partir de la puissance nominale (dégradation estimée). Saisissez les points de la fiche technique.")
                }
                s.heatPump.points.sortedWith(compareBy({ it.waterC }, { it.outdoorC })).forEachIndexed { _, p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Air ${Fmt.temp(p.outdoorC, 0)} · eau ${Fmt.temp(p.waterC, 0)} : ${p.capacityKw?.let { "${Fmt.num(it, 1)} kW" } ?: "–"} · COP ${p.cop?.let { Fmt.num(it, 2) } ?: "–"}",
                            Modifier.weight(1f))
                        IconButton(onClick = { vm.updatePump { m -> m.copy(points = m.points - p) } }) { Icon(Icons.Filled.Delete, "Supprimer") }
                    }
                }
                OutlinedButton(onClick = { addPoint = true }) { Icon(Icons.Filled.Add, null); Text(" Ajouter un point constructeur") }
                NumberField("Efficacité du modèle générique", s.genericCarnotEfficiency * 100, { v -> v?.let { vm.setCarnot(it / 100) } },
                    unit = "%", decimals = 0, nullable = false, min = 20.0, max = 70.0,
                    supportingText = "Part du COP théorique (Carnot) retenue sans données constructeur")
            }

            SectionCard("Bivalence et performance saisonnière") {
                ValueRow("Puissance de la PAC à $base", bv.capacityAtDesignKw?.let { "${Fmt.num(it, 1)} kW" } ?: "Inconnue (données manquantes)")
                ValueRow("Point de bivalence", when {
                    bv.capacityAtDesignKw == null -> "Indéterminé"
                    bv.bivalentTempC == null -> "PAC suffisante jusqu'à $base"
                    else -> Fmt.temp(bv.bivalentTempC)
                }, emphasize = true)
                bv.backupAtDesignKw?.takeIf { it > 0 }?.let { ValueRow("Appoint nécessaire à $base", "${Fmt.num(it, 1)} kW") }
                if (bv.capacityExtrapolated) {
                    InfoBanner("La température de base ($base) est hors de la plage des données constructeur : la puissance à cette température est transposée " +
                        "depuis le point le plus froid (−2,5 %/K, −0,5 %/K d'eau). Saisissez le point de la fiche à cette température pour la confirmer.", kind = BannerKind.WARNING)
                }
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
                ValueRow("SCOP estimé (${d.seasonal.climateLabel})", scopText, emphasize = true)
                ValueRow("Part d'appoint électrique", if (d.seasonal.capacityKnown) Fmt.pct(d.seasonal.backupShare) else "Non évaluée (puissance PAC inconnue)")
                Text(
                    when {
                        d.seasonal.generic -> "SCOP issu du modèle générique : ordre de grandeur à confirmer avec les données constructeur."
                        d.seasonal.extrapolated -> "SCOP calculé à partir des données constructeur et de la loi d'eau ; hors de leur plage, les valeurs sont transposées (Carnot) : à confirmer."
                        else -> "SCOP calculé à partir des données constructeur saisies et de la loi d'eau."
                    } + if (!d.seasonal.capacityKnown) " Sans puissance connue, la PAC est supposée couvrir toute la charge : SCOP optimiste." else "",
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
            // Défilable : à police agrandie, clavier ouvert, les derniers champs restent accessibles.
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Recopiez un point de la fiche technique (ex. air −7 °C / eau 55 °C). Renseignez au moins la puissance ou le COP.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
