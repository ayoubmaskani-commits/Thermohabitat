package fr.thermohabitat.pro.ui.reports

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import fr.thermohabitat.pro.ui.components.ScreenScaffold
import fr.thermohabitat.pro.ui.components.ScrollColumn

// Écrans provisoires (compilation intermédiaire) – remplacés par les écrans définitifs.
@Composable fun ReportsScreen(nav: NavController) = ScreenScaffold("Rapports", isTab = true) { p -> ScrollColumn(p) { Text("Rapports") } }
@Composable fun SettingsScreen(nav: NavController) = ScreenScaffold("Réglages", onBack = { nav.popBackStack() }) { p -> ScrollColumn(p) { Text("Réglages") } }
