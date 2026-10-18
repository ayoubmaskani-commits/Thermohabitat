package fr.thermohabitat.pro.ui.simulations

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import fr.thermohabitat.pro.ui.components.ScreenScaffold
import fr.thermohabitat.pro.ui.components.ScrollColumn

// Écrans provisoires (compilation intermédiaire) – remplacés par les écrans définitifs.
@Composable private fun Pending(nav: NavController, title: String, tab: Boolean = false) =
    ScreenScaffold(title, onBack = if (tab) null else ({ nav.popBackStack() }), isTab = tab) { p -> ScrollColumn(p) { Text(title) } }

@Composable fun SimulationsScreen(nav: NavController) = Pending(nav, "Simulations", true)
@Composable fun ComparisonScreen(nav: NavController) = Pending(nav, "Comparaison")
@Composable fun HeatPumpScreen(nav: NavController) = Pending(nav, "Pompe à chaleur")
@Composable fun CoolingScreen(nav: NavController) = Pending(nav, "Refroidissement")
@Composable fun ScenariosScreen(nav: NavController) = Pending(nav, "Scénarios")
@Composable fun ScenarioEditScreen(nav: NavController) = Pending(nav, "Scénario")
@Composable fun EnergyScreen(nav: NavController) = Pending(nav, "Énergie")
