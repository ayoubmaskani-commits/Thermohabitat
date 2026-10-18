package fr.thermohabitat.pro.ui.simulations

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Euro
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.thermohabitat.pro.domain.StudyService
import fr.thermohabitat.pro.ui.components.EmptyState
import fr.thermohabitat.pro.ui.components.ScreenScaffold
import fr.thermohabitat.pro.ui.components.ScrollColumn
import fr.thermohabitat.pro.ui.components.SectionCard
import fr.thermohabitat.pro.ui.components.ValueRow
import fr.thermohabitat.pro.ui.navigation.ComparisonRoute
import fr.thermohabitat.pro.ui.navigation.CoolingRoute
import fr.thermohabitat.pro.ui.navigation.EnergyRoute
import fr.thermohabitat.pro.ui.navigation.HeatPumpRoute
import fr.thermohabitat.pro.ui.navigation.HomeRoute
import fr.thermohabitat.pro.ui.navigation.ScenariosRoute
import fr.thermohabitat.pro.ui.navigation.navigateToTab
import fr.thermohabitat.pro.ui.util.Fmt
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class SimulationsViewModel @Inject constructor(studyService: StudyService) : ViewModel() {
    val project = studyService.observeActive().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
}

@Composable
fun SimulationsScreen(nav: NavController, vm: SimulationsViewModel = hiltViewModel()) {
    val p by vm.project.collectAsStateWithLifecycle()
    ScreenScaffold(title = "Simulations", isTab = true) { padding ->
        ScrollColumn(padding) {
            val a = p
            if (a == null) {
                EmptyState("Aucune étude", "Créez une étude pour lancer les simulations.", actionLabel = "Accueil", onAction = { nav.navigateToTab(HomeRoute) })
                return@ScrollColumn
            }
            SectionCard(a.project.entity.name, subtitle = "Synthèse au régime d'étude ${a.result.studyCoverage.supplyC.toInt()}/${a.result.studyCoverage.returnC?.toInt() ?: "–"} °C") {
                ValueRow("Puissance nécessaire", Fmt.kw(a.result.heatLoss.totalW))
                ValueRow("Besoins couverts par les radiateurs validés", Fmt.pct(a.result.studyCoverage.coveredFraction))
                ValueRow("Température de départ nécessaire", Fmt.supply(a.result.supply))
                ValueRow("Compatibilité PAC estimée", a.result.compatibility.label, emphasize = true)
                Text(a.result.compatibility.description, style = MaterialTheme.typography.bodySmall)
            }
            ToolCard(Icons.Filled.SwapHoriz, "Comparer besoins et radiateurs",
                "Pièce par pièce, à chaque régime d'eau : déficits, code couleur, plan, régime minimal nécessaire.") { nav.navigate(ComparisonRoute) }
            ToolCard(Icons.Filled.LocalFireDepartment, "Pompe à chaleur et loi d'eau",
                "Loi d'eau paramétrable, données constructeur (puissance, COP), point de bivalence, SCOP estimé.") { nav.navigate(HeatPumpRoute) }
            ToolCard(Icons.Filled.AcUnit, "Refroidissement et inertie",
                "Simulation dynamique RC : coupure du chauffage, comparaison avant/après, calage sur mesures.") { nav.navigate(CoolingRoute) }
            ToolCard(Icons.AutoMirrored.Filled.ShowChart, "Scénarios d'amélioration",
                "Isolation, fenêtres, combles, plancher, radiateurs, basse température : comparateur avant/après.") { nav.navigate(ScenariosRoute) }
            ToolCard(Icons.Filled.Euro, "Consommations et coûts",
                "Degrés-jours, rendements, prix de l'énergie : fioul, gaz, électricité, PAC, bois.") { nav.navigate(EnergyRoute) }
        }
    }
}

@Composable
private fun ToolCard(icon: ImageVector, title: String, description: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
