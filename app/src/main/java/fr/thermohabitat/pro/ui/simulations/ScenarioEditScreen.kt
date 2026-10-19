package fr.thermohabitat.pro.ui.simulations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SwapHoriz
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
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.thermohabitat.pro.data.db.toModel
import fr.thermohabitat.pro.data.repository.MaterialRepository
import fr.thermohabitat.pro.data.repository.ProjectRepository
import fr.thermohabitat.pro.domain.StudyService
import fr.thermohabitat.pro.ui.components.ChoiceChips
import fr.thermohabitat.pro.ui.components.DropdownField
import fr.thermohabitat.pro.ui.components.InfoBanner
import fr.thermohabitat.pro.ui.components.NumberField
import fr.thermohabitat.pro.ui.components.ScreenScaffold
import fr.thermohabitat.pro.ui.components.ScrollColumn
import fr.thermohabitat.pro.ui.components.SectionCard
import fr.thermohabitat.pro.ui.components.StatusChip
import fr.thermohabitat.pro.ui.components.TextInput
import fr.thermohabitat.pro.ui.components.ValueRow
import fr.thermohabitat.pro.ui.navigation.ScenarioEditRoute
import fr.thermohabitat.pro.ui.util.Fmt
import fr.thermohabitat.thermal.ScenarioComparison
import fr.thermohabitat.thermal.StudyAnalyzer
import fr.thermohabitat.thermal.calc.EnergyCarrier
import fr.thermohabitat.thermal.calc.LossCategory
import fr.thermohabitat.thermal.calc.Regime
import fr.thermohabitat.thermal.library.MaterialLibrary
import fr.thermohabitat.thermal.library.OpeningPresets
import fr.thermohabitat.thermal.model.AirtightnessPreset
import fr.thermohabitat.thermal.model.Material
import fr.thermohabitat.thermal.model.PanelType
import fr.thermohabitat.thermal.model.Radiator
import fr.thermohabitat.thermal.model.RadiatorMaterial
import fr.thermohabitat.thermal.model.RadiatorType
import fr.thermohabitat.thermal.model.Room
import fr.thermohabitat.thermal.model.ValueOrigin
import fr.thermohabitat.thermal.model.VentilationType
import fr.thermohabitat.thermal.scenario.InsulationPosition
import fr.thermohabitat.thermal.scenario.InsulationTarget
import fr.thermohabitat.thermal.scenario.Modification
import fr.thermohabitat.thermal.scenario.Scenario
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ScenarioEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: ProjectRepository,
    materials: MaterialRepository,
    studyService: StudyService,
    private val analyzer: StudyAnalyzer,
) : ViewModel() {
    private val scenarioId = savedStateHandle.toRoute<ScenarioEditRoute>().scenarioId
    val scenario = MutableStateFlow<Scenario?>(null)
    private var buildingId: Long? = null
    val project = studyService.observeActive().stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val insulants = materials.observeAll().map { list -> list.filter { it.isInsulation || it.code == "chaux_chanvre" }.map { it.toModel() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            val (e, sc) = repository.observeScenario(scenarioId).filterNotNull().first()
            buildingId = e.buildingId
            scenario.value = sc
        }
    }

    val comparison = combine(project, scenario) { p, sc ->
        if (p == null || sc == null) null else ScenarioComparison(sc, p.result, analyzer.analyzeScenario(p.project.building, p.project.settings, sc))
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun update(t: (Scenario) -> Scenario) {
        val next = t(scenario.value ?: return)
        scenario.value = next
        val b = buildingId ?: return
        viewModelScope.launch { repository.saveScenario(b, next, scenarioId) }
    }
}

@Composable
fun ScenarioEditScreen(nav: NavController, vm: ScenarioEditViewModel = hiltViewModel()) {
    val scenario by vm.scenario.collectAsStateWithLifecycle()
    val comparison by vm.comparison.collectAsStateWithLifecycle()
    val project by vm.project.collectAsStateWithLifecycle()
    val insulants by vm.insulants.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    ScreenScaffold(title = scenario?.name ?: "Scénario", onBack = { nav.popBackStack() }) { padding ->
        ScrollColumn(padding) {
            val sc = scenario ?: return@ScrollColumn
            SectionCard("Scénario") {
                TextInput("Nom", sc.name, { v -> vm.update { it.copy(name = v) } })
                TextInput("Description", sc.description, { v -> vm.update { it.copy(description = v) } }, singleLine = false)
                Text("Régime d'eau étudié dans ce scénario", style = MaterialTheme.typography.labelLarge)
                ChoiceChips(listOf<Regime?>(null) + Regime.STANDARD, sc.regime, { it?.label ?: "Régime de l'étude" }, { r -> vm.update { it.copy(regime = r) } })
            }
            SectionCard("Modifications", action = { IconButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, "Ajouter") } }) {
                if (sc.modifications.isEmpty()) Text("Aucune modification : ajoutez des travaux ou changez le régime d'eau.", style = MaterialTheme.typography.bodySmall)
                sc.modifications.forEachIndexed { i, m ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(m.description, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        IconButton(onClick = { vm.update { s -> s.copy(modifications = s.modifications.filterIndexed { k, _ -> k != i }) } }) {
                            Icon(Icons.Filled.Delete, "Retirer")
                        }
                    }
                }
                OutlinedButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, null); Text(" Ajouter une modification") }
            }
            comparison?.let { c -> ComparisonSection(c) }
        }
    }
    if (adding) {
        AddModificationDialog(
            insulants = insulants,
            rooms = project?.project?.building?.rooms.orEmpty(),
            radiators = project?.project?.building?.radiators.orEmpty(),
            onDismiss = { adding = false },
            onAdd = { m -> adding = false; vm.update { it.copy(modifications = it.modifications + m) } },
        )
    }
}

@Composable
private fun ComparisonSection(c: ScenarioComparison) {
    val b = c.before; val a = c.after
    SectionCard("Comparateur avant / après", icon = Icons.Filled.SwapHoriz) {
        ValueRow("Déperditions totales", "${Fmt.kw(b.heatLoss.totalW)} → ${Fmt.kw(a.heatLoss.totalW)}", emphasize = true)
        ValueRow("Réduction", "${Fmt.watts(c.heatLossReductionW)} (${Fmt.pct(c.heatLossReductionPct)})")
        ValueRow("Coefficient H", "${Fmt.num(b.heatLoss.totalHWk, 0)} → ${Fmt.num(a.heatLoss.totalHWk, 0)} W/K")
        HorizontalDivider()
        LossCategory.entries.forEach { cat ->
            val vb = b.heatLoss.byCategoryW[cat] ?: 0.0; val va = a.heatLoss.byCategoryW[cat] ?: 0.0
            if (vb > 1 || va > 1) ValueRow(cat.label, "${Fmt.watts(vb)} → ${Fmt.watts(va)}")
        }
        HorizontalDivider()
        ValueRow("Régime étudié", "${b.studyCoverage.supplyC.toInt()} → ${a.studyCoverage.supplyC.toInt()} °C")
        ValueRow("Besoins couverts", "${Fmt.coverage(b.studyCoverage)} → ${Fmt.coverage(a.studyCoverage)}")
        ValueRow("Départ nécessaire", "${b.supply.requiredSupplyC?.let { Fmt.temp(it) } ?: "–"} → ${a.supply.requiredSupplyC?.let { Fmt.temp(it) } ?: "–"}")
        ValueRow("Compatibilité PAC", "${b.compatibility.label} → ${a.compatibility.label}")
        HorizontalDivider()
        ValueRow("Besoins annuels de chauffage", "${Fmt.num(b.energy.annualNeedKwh, 0)} → ${Fmt.num(a.energy.annualNeedKwh, 0)} kWh")
        listOf(EnergyCarrier.FUEL_OIL, EnergyCarrier.HEAT_PUMP).forEach { carrier ->
            val cb = b.energy.carriers.firstOrNull { it.carrier == carrier }; val ca = a.energy.carriers.firstOrNull { it.carrier == carrier }
            if (cb != null && ca != null) ValueRow("Coût ${carrier.label.lowercase()}", "${Fmt.eur(cb.costEur)} → ${Fmt.eur(ca.costEur)} /an")
        }
        HorizontalDivider()
        Text("Pièce par pièce", style = MaterialTheme.typography.labelLarge)
        a.studyCoverage.rooms.forEach { ra ->
            val rb = b.studyCoverage.rooms.firstOrNull { it.roomId == ra.roomId }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(ra.roomName, style = MaterialTheme.typography.bodyMedium)
                    Text("Besoin ${Fmt.watts(rb?.needW)} → ${Fmt.watts(ra.needW)} · dispo ${Fmt.watts(ra.availableW)}", style = MaterialTheme.typography.bodySmall)
                }
                StatusChip(ra.status)
            }
        }
        if (c.after.building.radiators.any { it.id < 0 }) InfoBanner("Les radiateurs ajoutés par le scénario sont supposés documentés et validés.")
    }
}

private enum class ModKind(val label: String) {
    INSULATION("Isolation (murs, combles, plancher)"),
    WINDOWS("Remplacement des fenêtres"),
    DOORS("Remplacement des portes"),
    AIRTIGHTNESS("Étanchéité à l'air"),
    VENTILATION("Ventilation"),
    SETPOINT("Température de chauffage"),
    BRIDGES("Traitement des ponts thermiques"),
    REPLACE_RADIATOR("Remplacer un radiateur"),
    ADD_RADIATOR("Ajouter un radiateur"),
}

private val finishOptions: List<Pair<String?, String>> = listOf(
    null to "Aucun parement", "ba13" to "Plaque de plâtre", "enduit_terre" to "Enduit terre", "enduit_chaux" to "Enduit chaux", "enduit_platre" to "Enduit plâtre",
)

@Composable
private fun AddModificationDialog(
    insulants: List<Material>,
    rooms: List<Room>,
    radiators: List<Radiator>,
    onDismiss: () -> Unit,
    onAdd: (Modification) -> Unit,
) {
    var kind by remember { mutableStateOf(ModKind.INSULATION) }
    // Isolation
    var target by remember { mutableStateOf(InsulationTarget.TOP_CEILINGS) }
    var position by remember { mutableStateOf(InsulationPosition.EXTERIOR) }
    var material by remember { mutableStateOf<Material?>(null) }
    var thicknessCm by remember { mutableStateOf<Double?>(20.0) }
    var finish by remember { mutableStateOf<String?>(null) }
    // Valeurs diverses
    var u by remember { mutableStateOf<Double?>(1.3) }
    var g by remember { mutableStateOf<Double?>(0.6) }
    var n50 by remember { mutableStateOf<Double?>(5.0) }
    var ventType by remember { mutableStateOf(VentilationType.EXTRACT) }
    var flow by remember { mutableStateOf<Double?>(null) }
    var efficiency by remember { mutableStateOf<Double?>(85.0) }
    var setpoint by remember { mutableStateOf<Double?>(19.0) }
    var deltaU by remember { mutableStateOf<Double?>(0.05) }
    // Radiateurs
    var radiator by remember { mutableStateOf(radiators.firstOrNull()) }
    var room by remember { mutableStateOf(rooms.firstOrNull()) }
    var panel by remember { mutableStateOf(PanelType.T22) }
    var heightMm by remember { mutableStateOf<Double?>(600.0) }
    var lengthMm by remember { mutableStateOf<Double?>(1000.0) }
    var nominal by remember { mutableStateOf<Double?>(null) }

    fun newRadiator(roomId: Long?, code: String): Radiator = if (nominal != null) {
        Radiator(code = code, roomId = roomId, label = "Radiateur documenté", type = RadiatorType.STEEL_PANEL, material = RadiatorMaterial.STEEL,
            nominalPowerW = nominal, nominalPowerOrigin = ValueOrigin.DECLARED, validated = true)
    } else {
        Radiator(code = code, roomId = roomId, label = "Panneau ${panel.name.removePrefix("T")} ${heightMm?.toInt()}×${lengthMm?.toInt()}",
            type = RadiatorType.STEEL_PANEL, material = RadiatorMaterial.STEEL, panelType = panel, heightMm = heightMm, lengthMm = lengthMm,
            dimensionsOrigin = ValueOrigin.DECLARED, validated = true)
    }

    val result: Modification? = when (kind) {
        ModKind.INSULATION -> material?.let { m -> thicknessCm?.let { t ->
            Modification.AddInsulation(target, m, t / 100, position,
                finish = if (position == InsulationPosition.INTERIOR) finish?.let { MaterialLibrary.find(it) } else null,
                finishThicknessM = if (finish == "ba13") 0.0125 else 0.015)
        } }
        ModKind.WINDOWS -> u?.let { Modification.ReplaceWindows(it, g ?: 0.6) }
        ModKind.DOORS -> u?.let { Modification.ReplaceDoors(it) }
        ModKind.AIRTIGHTNESS -> n50?.let { Modification.ImproveAirtightness(it) }
        ModKind.VENTILATION -> Modification.ChangeVentilation(ventType, flow, if (ventType == VentilationType.BALANCED) (efficiency ?: 0.0) / 100 else 0.0)
        ModKind.SETPOINT -> setpoint?.let { Modification.ChangeSetpoint(it) }
        ModKind.BRIDGES -> deltaU?.let { Modification.ChangeThermalBridges(it) }
        ModKind.REPLACE_RADIATOR -> radiator?.let { r -> Modification.ReplaceRadiator(r.id, newRadiator(r.roomId, r.code)) }
        ModKind.ADD_RADIATOR -> room?.let { rm -> Modification.AddRadiator(newRadiator(rm.id, "N")) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ajouter une modification") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DropdownField("Type de travaux", ModKind.entries, kind, { it.label }, { kind = it })
                when (kind) {
                    ModKind.INSULATION -> {
                        DropdownField("Parois", InsulationTarget.entries.filter { it != InsulationTarget.SELECTED }, target, { it.label }, { target = it })
                        ChoiceChips(InsulationPosition.entries, position, { it.label }, { position = it })
                        DropdownField("Isolant", insulants, material, { "${it.name} (λ ${Fmt.num(it.lambda, 3)})" }, { material = it })
                        NumberField("Épaisseur", thicknessCm, { thicknessCm = it }, unit = "cm", decimals = 1, min = 1.0, max = 60.0)
                        if (position == InsulationPosition.INTERIOR) {
                            DropdownField("Parement intérieur", finishOptions, finishOptions.first { it.first == finish }, { it.second }, { finish = it.first })
                            InfoBanner("L'isolation par l'intérieur masque l'inertie des murs lourds (pisé, pierre) : le logement se refroidira plus vite chauffage coupé.")
                        }
                    }
                    ModKind.WINDOWS -> {
                        DropdownField("Menuiserie (valeur indicative)", OpeningPresets.windows, null, { "${it.label} – Uw ${Fmt.num(it.uValue, 1)}" },
                            { u = it.uValue; g = it.gValue }, placeholder = "Choisir…")
                        NumberField("Uw", u, { u = it }, unit = "W/(m²·K)", decimals = 2, min = 0.5, max = 6.0)
                        NumberField("Facteur solaire g", g, { g = it }, decimals = 2, min = 0.1, max = 0.9)
                    }
                    ModKind.DOORS -> NumberField("Ud", u, { u = it }, unit = "W/(m²·K)", decimals = 2, min = 0.5, max = 6.0)
                    ModKind.AIRTIGHTNESS -> {
                        DropdownField("Niveau visé", AirtightnessPreset.entries, null, { "${it.label} – n50 ${Fmt.num(it.n50, 1)}" }, { n50 = it.n50 }, placeholder = "Choisir…")
                        NumberField("n50", n50, { n50 = it }, unit = "h⁻¹", decimals = 1, min = 0.3, max = 30.0)
                    }
                    ModKind.VENTILATION -> {
                        DropdownField("Système", VentilationType.entries, ventType, { it.label }, { ventType = it })
                        NumberField("Débit (vide : débits minimaux)", flow, { flow = it }, unit = "m³/h", decimals = 0, min = 0.0)
                        if (ventType == VentilationType.BALANCED) NumberField("Efficacité de récupération", efficiency, { efficiency = it }, unit = "%", decimals = 0, min = 0.0, max = 95.0)
                    }
                    ModKind.SETPOINT -> NumberField("Consigne de toutes les pièces", setpoint, { setpoint = it }, unit = "°C", decimals = 1, min = 10.0, max = 26.0)
                    ModKind.BRIDGES -> NumberField("ΔU_TB après traitement", deltaU, { deltaU = it }, unit = "W/(m²·K)", decimals = 2, min = 0.0, max = 0.3)
                    ModKind.REPLACE_RADIATOR, ModKind.ADD_RADIATOR -> {
                        if (kind == ModKind.REPLACE_RADIATOR) DropdownField("Radiateur remplacé", radiators, radiator, { "${it.code} · ${it.label.ifBlank { it.type.label }}" }, { radiator = it })
                        else DropdownField("Pièce", rooms, room, { it.name }, { room = it })
                        Text("Nouveau radiateur : panneau acier ou puissance documentée", style = MaterialTheme.typography.labelLarge)
                        DropdownField("Type de panneau", PanelType.entries, panel, { it.label }, { panel = it })
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NumberField("Hauteur", heightMm, { heightMm = it }, Modifier.weight(1f), unit = "mm", decimals = 0, min = 200.0, max = 2500.0)
                            NumberField("Longueur", lengthMm, { lengthMm = it }, Modifier.weight(1f), unit = "mm", decimals = 0, min = 200.0, max = 3000.0)
                        }
                        NumberField("Ou puissance nominale documentée (75/65/20)", nominal, { nominal = it }, unit = "W", decimals = 0, min = 50.0)
                    }
                }
                result?.let { Text("→ ${it.description}", style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(enabled = result != null, onClick = { result?.let(onAdd) }) { Text("Ajouter") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
