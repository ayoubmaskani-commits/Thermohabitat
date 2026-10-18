package fr.thermohabitat.pro.ui.simulations

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import fr.thermohabitat.pro.ui.components.ConfirmDialog
import fr.thermohabitat.pro.ui.components.EmptyState
import fr.thermohabitat.pro.ui.components.InfoBanner
import fr.thermohabitat.pro.ui.components.ScreenScaffold
import fr.thermohabitat.pro.ui.components.ScrollColumn
import fr.thermohabitat.pro.ui.components.ValueRow
import fr.thermohabitat.pro.ui.navigation.ScenarioEditRoute
import fr.thermohabitat.pro.ui.util.Fmt
import fr.thermohabitat.thermal.ScenarioComparison
import fr.thermohabitat.thermal.StudyAnalyzer
import fr.thermohabitat.thermal.calc.EnergyCarrier
import fr.thermohabitat.thermal.calc.Regime
import fr.thermohabitat.thermal.library.MaterialLibrary
import fr.thermohabitat.thermal.scenario.InsulationPosition
import fr.thermohabitat.thermal.scenario.InsulationTarget
import fr.thermohabitat.thermal.scenario.Modification
import fr.thermohabitat.thermal.scenario.Scenario
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Modèles de scénarios proposés comme point de départ (modifiables). */
object ScenarioTemplates {
    fun all(): List<Scenario> = listOf(
        Scenario(name = "Isolation des combles (ouate 30 cm)", modifications = listOf(
            Modification.AddInsulation(InsulationTarget.TOP_CEILINGS, MaterialLibrary.byCode("ouate_cellulose"), 0.30, InsulationPosition.EXTERIOR),
        )),
        Scenario(name = "Remplacement des fenêtres (Uw 1,3)", modifications = listOf(
            Modification.ReplaceWindows(1.3, 0.6), Modification.ImproveAirtightness(6.0),
        )),
        Scenario(name = "Isolation intérieure des murs (fibre de bois 10 cm)", modifications = listOf(
            Modification.AddInsulation(InsulationTarget.EXTERIOR_WALLS, MaterialLibrary.byCode("fibre_bois_rigide"), 0.10, InsulationPosition.INTERIOR,
                finish = MaterialLibrary.byCode("enduit_terre"), finishThicknessM = 0.015),
        )),
        Scenario(name = "Isolation du plancher bas (PSE 8 cm)", modifications = listOf(
            Modification.AddInsulation(InsulationTarget.LOW_FLOORS, MaterialLibrary.byCode("pse"), 0.08, InsulationPosition.EXTERIOR),
        )),
        Scenario(name = "Passage en basse température 45/35 °C", modifications = emptyList(), regime = Regime(45.0, 35.0)),
        Scenario(name = "Rénovation globale + PAC 50/40 °C", modifications = listOf(
            Modification.AddInsulation(InsulationTarget.TOP_CEILINGS, MaterialLibrary.byCode("ouate_cellulose"), 0.30, InsulationPosition.EXTERIOR),
            Modification.ReplaceWindows(1.3, 0.6),
            Modification.ImproveAirtightness(5.0),
            Modification.AddInsulation(InsulationTarget.EXTERIOR_WALLS, MaterialLibrary.byCode("fibre_bois_rigide"), 0.10, InsulationPosition.INTERIOR,
                finish = MaterialLibrary.byCode("enduit_terre"), finishThicknessM = 0.015),
        ), regime = Regime(50.0, 40.0)),
    )
}

data class ScenarioRow(val id: Long, val comparison: ScenarioComparison)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ScenariosViewModel @Inject constructor(
    studyService: StudyService,
    private val repository: ProjectRepository,
    private val analyzer: StudyAnalyzer,
) : ViewModel() {
    val project = studyService.observeActive().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val rows = project.flatMapLatest { p ->
        if (p == null) flowOf(emptyList())
        else repository.observeScenarios(p.project.id).map { list ->
            list.map { (e, sc) -> ScenarioRow(e.id, ScenarioComparison(sc, p.result, analyzer.analyzeScenario(p.project.building, p.project.settings, sc))) }
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    suspend fun create(template: Scenario): Long? {
        val p = project.value ?: return null
        return repository.saveScenario(p.project.id, template)
    }

    fun delete(id: Long) = viewModelScope.launch { repository.deleteScenario(id) }
}

@Composable
fun ScenariosScreen(nav: NavController, vm: ScenariosViewModel = hiltViewModel()) {
    val p by vm.project.collectAsStateWithLifecycle()
    val rows by vm.rows.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var showTemplates by remember { mutableStateOf(false) }
    var deleteId by remember { mutableStateOf<Long?>(null) }
    ScreenScaffold(
        title = "Scénarios d'amélioration",
        onBack = { nav.popBackStack() },
        floatingActionButton = {
            if (p != null) ExtendedFloatingActionButton(onClick = { showTemplates = true }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Nouveau scénario") })
        },
    ) { padding ->
        ScrollColumn(padding) {
            val a: AnalyzedProject = p ?: return@ScrollColumn
            InfoBanner("Chaque scénario est appliqué à une copie de l'étude : déperditions, besoins, couverture des radiateurs, température de départ et consommations sont recalculés.")
            if (rows.isEmpty()) EmptyState("Aucun scénario", "Créez un scénario à partir d'un modèle : isolation, fenêtres, combles, radiateurs, basse température…")
            rows.forEach { row ->
                val c = row.comparison
                Card(Modifier.fillMaxWidth().clickable { nav.navigate(ScenarioEditRoute(row.id)) }, shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.AutoMirrored.Filled.ShowChart, null, tint = MaterialTheme.colorScheme.primary)
                            Text("  ${c.scenario.name}", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                            IconButton(onClick = { deleteId = row.id }) { Icon(Icons.Filled.Delete, "Supprimer") }
                        }
                        c.scenario.modifications.forEach { Text("• ${it.description}", style = MaterialTheme.typography.bodySmall) }
                        c.scenario.regime?.let { Text("• Régime étudié : ${it.label}", style = MaterialTheme.typography.bodySmall) }
                        HorizontalDivider()
                        ValueRow("Déperditions", "${Fmt.kw(c.before.heatLoss.totalW)} → ${Fmt.kw(c.after.heatLoss.totalW)} (−${Fmt.pct(c.heatLossReductionPct)})")
                        ValueRow("Départ nécessaire", "${c.before.supply.requiredSupplyC?.let { Fmt.temp(it, 0) } ?: "–"} → ${c.after.supply.requiredSupplyC?.let { Fmt.temp(it, 0) } ?: "–"}")
                        ValueRow("Couverture au régime étudié", "${Fmt.pct(c.before.studyCoverage.coveredFraction)} → ${Fmt.pct(c.after.studyCoverage.coveredFraction)}")
                        ValueRow("Compatibilité PAC", "${c.before.compatibility.label} → ${c.after.compatibility.label}")
                        val pacBefore = c.before.energy.carriers.firstOrNull { it.carrier == EnergyCarrier.HEAT_PUMP }
                        val pacAfter = c.after.energy.carriers.firstOrNull { it.carrier == EnergyCarrier.HEAT_PUMP }
                        ValueRow("Besoins annuels", "${Fmt.num(c.before.energy.annualNeedKwh, 0)} → ${Fmt.num(c.after.energy.annualNeedKwh, 0)} kWh")
                        if (pacBefore != null && pacAfter != null) ValueRow("Coût PAC estimé", "${Fmt.eur(pacBefore.costEur)} → ${Fmt.eur(pacAfter.costEur)} /an")
                    }
                }
            }
        }
    }
    if (showTemplates) {
        AlertDialog(
            onDismissRequest = { showTemplates = false },
            title = { Text("Partir d'un modèle") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    (listOf(Scenario(name = "Scénario personnalisé")) + ScenarioTemplates.all()).forEach { t ->
                        TextButton(onClick = {
                            showTemplates = false
                            scope.launch { vm.create(t)?.let { nav.navigate(ScenarioEditRoute(it)) } }
                        }) { Text(t.name) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showTemplates = false }) { Text("Fermer") } },
        )
    }
    deleteId?.let { id ->
        ConfirmDialog("Supprimer le scénario ?", "Le scénario sera effacé (l'étude n'est pas modifiée).", "Supprimer",
            onConfirm = { vm.delete(id) }, onDismiss = { deleteId = null }, destructive = true)
    }
}
