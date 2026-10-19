package fr.thermohabitat.pro.ui.simulations

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
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
import fr.thermohabitat.pro.ui.components.ChoiceChips
import fr.thermohabitat.pro.ui.components.FloorPlan
import fr.thermohabitat.pro.ui.components.InfoBanner
import fr.thermohabitat.pro.ui.components.LegendDot
import fr.thermohabitat.pro.ui.components.NeedVsAvailableChart
import fr.thermohabitat.pro.ui.components.NumberField
import fr.thermohabitat.pro.ui.components.PlanRoom
import fr.thermohabitat.pro.ui.components.ScreenScaffold
import fr.thermohabitat.pro.ui.components.ScrollColumn
import fr.thermohabitat.pro.ui.components.SectionCard
import fr.thermohabitat.pro.ui.components.StatusChip
import fr.thermohabitat.pro.ui.components.SwitchRow
import fr.thermohabitat.pro.ui.components.ValueRow
import fr.thermohabitat.pro.ui.components.statusColor
import fr.thermohabitat.pro.ui.house.floorLabel
import fr.thermohabitat.pro.ui.navigation.RoomEditRoute
import fr.thermohabitat.pro.ui.theme.LocalStatusColors
import fr.thermohabitat.pro.ui.util.Fmt
import fr.thermohabitat.thermal.calc.BuildingCoverage
import fr.thermohabitat.thermal.calc.BuildingSupplyRequirement
import fr.thermohabitat.thermal.calc.CoverageAnalyzer
import fr.thermohabitat.thermal.calc.CoverageStatus
import fr.thermohabitat.thermal.calc.HydraulicMode
import fr.thermohabitat.thermal.calc.RadiatorPower
import fr.thermohabitat.thermal.calc.Regime
import fr.thermohabitat.thermal.calc.SupplyTemperatureSolver
import fr.thermohabitat.thermal.model.RatingCondition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ComparisonOptions(val regime: Regime? = null, val fixedFlow: Boolean? = null, val conservative: Boolean = false)

data class ComparisonData(
    val analyzed: AnalyzedProject,
    val regime: Regime,
    val fixedFlow: Boolean,
    val coverage: BuildingCoverage,
    val supply: BuildingSupplyRequirement,
    val supplyConservative: BuildingSupplyRequirement,
)

@HiltViewModel
class ComparisonViewModel @Inject constructor(
    studyService: StudyService,
    private val repository: ProjectRepository,
) : ViewModel() {
    val options = MutableStateFlow(ComparisonOptions())
    val data = combine(studyService.observeActive(), options) { p, o ->
        p?.let {
            val s = it.project.settings
            val regime = o.regime ?: s.studyRegime
            val fixedFlow = o.fixedFlow ?: s.fixedFlow
            val mode = if (fixedFlow) HydraulicMode.FixedFlow else HydraulicMode.FixedDeltaT(regime.supplyC - regime.returnC)
            val solverMode = if (fixedFlow) HydraulicMode.FixedFlow else HydraulicMode.FixedDeltaT(s.systemDeltaK)
            val b = it.project.building
            val hl = it.result.heatLoss
            ComparisonData(
                it, regime, fixedFlow,
                CoverageAnalyzer.analyze(b, hl, regime, mode),
                SupplyTemperatureSolver.solve(b, hl, solverMode),
                SupplyTemperatureSolver.solve(b, hl, solverMode, conservative = true),
            )
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun setAsStudyRegime() = viewModelScope.launch {
        val d = data.value ?: return@launch
        repository.updateSettings(d.analyzed.project.id) { it.copy(studyRegime = d.regime, fixedFlow = d.fixedFlow) }
    }

    fun setSystemDelta(delta: Double) = viewModelScope.launch {
        val d = data.value ?: return@launch
        repository.updateSettings(d.analyzed.project.id) { it.copy(systemDeltaK = delta) }
    }
}

@Composable
fun ComparisonScreen(nav: NavController, vm: ComparisonViewModel = hiltViewModel()) {
    val data by vm.data.collectAsStateWithLifecycle()
    val options by vm.options.collectAsStateWithLifecycle()
    ScreenScaffold(title = "Besoins et puissances", onBack = { nav.popBackStack() }) { padding ->
        ScrollColumn(padding) {
            val d = data ?: return@ScrollColumn
            val settings = d.analyzed.project.settings
            SectionCard("Régime d'eau étudié", icon = Icons.Filled.Thermostat) {
                ChoiceChips(Regime.STANDARD, d.regime, { it.label }, { vm.options.value = options.copy(regime = it) })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("Départ", d.regime.supplyC, { v -> v?.let { vm.options.value = options.copy(regime = Regime(it, minOf(d.regime.returnC, it - 1))) } },
                        Modifier.weight(1f), unit = "°C", decimals = 0, nullable = false, min = 25.0, max = 90.0)
                    NumberField("Retour", d.regime.returnC, { v -> v?.let { vm.options.value = options.copy(regime = Regime(d.regime.supplyC, minOf(it, d.regime.supplyC - 1))) } },
                        Modifier.weight(1f), unit = "°C", decimals = 0, nullable = false, min = 20.0, max = 89.0)
                }
                Text("Hydraulique", style = MaterialTheme.typography.labelLarge)
                ChoiceChips(listOf(false, true), d.fixedFlow, { if (it) "Débit nominal conservé" else "Écart départ/retour constant" },
                    { vm.options.value = options.copy(fixedFlow = it) })
                if (d.fixedFlow) Text("Chaque radiateur conserve son débit (saisi ou nominal) : l'écart départ/retour diminue quand la température baisse.",
                    style = MaterialTheme.typography.bodySmall)
                SwitchRow("Hypothèse prudente", options.conservative, { vm.options.value = options.copy(conservative = it) },
                    description = "Utilise la borne basse des puissances estimées pour le régime minimal")
                OutlinedButton(onClick = { vm.setAsStudyRegime() }) { Text("Définir comme régime d'étude du projet") }
            }

            val c = d.coverage
            SectionCard("Bilan du bâtiment", icon = Icons.Filled.SwapHoriz) {
                ValueRow("Besoins (somme des pièces)", Fmt.watts(c.totalNeedW))
                ValueRow("Puissance disponible à ${d.regime.label}", Fmt.watts(c.totalAvailableW))
                ValueRow("Besoins couverts", Fmt.coverage(c), emphasize = true)
                ValueRow("Pièces insuffisantes / limites", "${c.insufficientRooms.size} / ${c.limitRooms.size}")
                val pending = c.rooms.sumOf { it.pendingRadiators }
                if (pending > 0) InfoBanner("$pending radiateur(s) en attente de validation ne sont pas comptés.", kind = BannerKind.WARNING)
            }

            SectionCard("Régime d'eau minimal nécessaire") {
                val sup = if (options.conservative) d.supplyConservative else d.supply
                ValueRow("Température de départ minimale", Fmt.supply(sup), emphasize = true)
                if (!d.fixedFlow) {
                    NumberField("Écart départ/retour du réseau", settings.systemDeltaK, { v -> v?.let { vm.setSystemDelta(it) } }, unit = "K", decimals = 0,
                        nullable = false, min = 3.0, max = 25.0, supportingText = "PAC : souvent 5 à 7 K ; chaudière : 10 à 20 K")
                }
                sup.limitingRoom?.let { ValueRow("Pièce dimensionnante", it.roomName) }
                sup.uncoverableRooms.forEach { InfoBanner("${it.roomName} : besoin non couvert même à 90 °C.", kind = BannerKind.WARNING) }
                sup.roomsWithoutEmitters.forEach { InfoBanner("${it.roomName} : aucun radiateur validé.", kind = BannerKind.WARNING) }
                HorizontalDivider()
                sup.rooms.filter { it.hasEmitters }.forEach { ValueRow(it.roomName, it.requiredSupplyC?.let { t -> Fmt.temp(t) } ?: "> 90 °C") }
            }

            SectionCard("Pièce par pièce", subtitle = "Touchez une pièce pour l'ouvrir") {
                c.rooms.forEach { rc ->
                    val ratio = if (rc.needW > 0) rc.availableW / rc.needW else null
                    Column(Modifier.fillMaxWidth().clickable { nav.navigate(RoomEditRoute(rc.roomId)) }.padding(vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(rc.roomName, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                            StatusChip(rc.status)
                        }
                        Text("${Fmt.area(rc.floorAreaM2)} · ${Fmt.temp(rc.setpointC, 0)} · besoin ${Fmt.watts(rc.needW)}", style = MaterialTheme.typography.bodySmall)
                        Text("Disponible ${Fmt.watts(rc.availableW)}" + (if (rc.availableLowW != rc.availableHighW) " (${Fmt.watts(rc.availableLowW)}–${Fmt.watts(rc.availableHighW)})" else "") +
                            " · couverture ${Fmt.pct(ratio)}", style = MaterialTheme.typography.bodySmall)
                        if (rc.deficitW > 0 && rc.status != CoverageStatus.NO_NEED) {
                            val factor = RadiatorPower.correctionFactor(RatingCondition.EN442, 1.3, d.regime.supplyC, d.regime.returnC, rc.setpointC)
                            val nominalNeeded = if (factor > 0) rc.deficitW / factor else Double.NaN
                            Text("Déficit ${Fmt.watts(rc.deficitW)} : prévoir ≈ ${Fmt.watts(nominalNeeded)} de puissance nominale supplémentaire (ΔT 50 K, n = 1,3) ou réduire les déperditions.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    HorizontalDivider()
                }
            }

            SectionCard("Graphique") {
                NeedVsAvailableChart(c.rooms.map { Triple(it.roomName, it.needW, it.availableW) }, c.rooms.map { statusColor(it.status) })
            }

            val building = d.analyzed.project.building
            val floors = building.rooms.map { it.floorLevel }.distinct().sorted()
            if (floors.isNotEmpty()) {
                var floor by remember(floors) { mutableIntStateOf(floors.first()) }
                SectionCard("Plan coloré", icon = Icons.Filled.Map) {
                    ChoiceChips(floors, floor, { floorLabel(it) }, { floor = it })
                    FloorPlan(building.rooms.filter { it.floorLevel == floor }.map { r ->
                        val rc = c.rooms.firstOrNull { it.roomId == r.id }
                        PlanRoom(r.id, r.name, r.lengthM, r.widthM, r.planX, r.planY, statusColor(rc?.status ?: CoverageStatus.NO_NEED),
                            rc?.let { "${Fmt.watts(it.availableW)} / ${Fmt.watts(it.needW)}" })
                    }, onRoomClick = { nav.navigate(RoomEditRoute(it)) })
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val s = LocalStatusColors.current
                        LegendDot(s.sufficient, "Suffisant"); LegendDot(s.limit, "Limite"); LegendDot(s.insufficient, "Insuffisant")
                    }
                }
            }
            InfoBanner("Vert : même la borne basse de l'estimation couvre le besoin. Orange : couverture incertaine ou juste. Rouge : moins de 90 % du besoin.")
        }
    }
}
