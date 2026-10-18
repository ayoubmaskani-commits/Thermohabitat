package fr.thermohabitat.pro.ui.navigation

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.House
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import fr.thermohabitat.pro.ui.building.BuildingEditScreen
import fr.thermohabitat.pro.ui.home.HomeScreen
import fr.thermohabitat.pro.ui.house.HouseScreen
import fr.thermohabitat.pro.ui.house.RoomEditScreen
import fr.thermohabitat.pro.ui.house.SurfaceEditScreen
import fr.thermohabitat.pro.ui.materials.MaterialsScreen
import fr.thermohabitat.pro.ui.photo.CaptureScreen
import fr.thermohabitat.pro.ui.photo.ElementCountScreen
import fr.thermohabitat.pro.ui.photo.MeasureScreen
import fr.thermohabitat.pro.ui.photo.PhotoEditScreen
import fr.thermohabitat.pro.ui.radiators.RadiatorEditScreen
import fr.thermohabitat.pro.ui.radiators.RadiatorLibraryScreen
import fr.thermohabitat.pro.ui.radiators.RadiatorsScreen
import fr.thermohabitat.pro.ui.reports.ReportsScreen
import fr.thermohabitat.pro.ui.reports.SettingsScreen
import fr.thermohabitat.pro.ui.simulations.ComparisonScreen
import fr.thermohabitat.pro.ui.simulations.CoolingScreen
import fr.thermohabitat.pro.ui.simulations.EnergyScreen
import fr.thermohabitat.pro.ui.simulations.HeatPumpScreen
import fr.thermohabitat.pro.ui.simulations.ScenarioEditScreen
import fr.thermohabitat.pro.ui.simulations.ScenariosScreen
import fr.thermohabitat.pro.ui.simulations.SimulationsScreen
import kotlin.reflect.KClass

private data class Tab(val route: Any, val routeClass: KClass<*>, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(HomeRoute, HomeRoute::class, "Accueil", Icons.Filled.Home),
    Tab(HouseRoute, HouseRoute::class, "Maison", Icons.Filled.House),
    Tab(RadiatorsRoute, RadiatorsRoute::class, "Radiateurs", Icons.Filled.Thermostat),
    Tab(SimulationsRoute, SimulationsRoute::class, "Simulations", Icons.Filled.Insights),
    Tab(ReportsRoute, ReportsRoute::class, "Rapports", Icons.Filled.Description),
)

/** Ouvre une section principale en conservant l'état des autres sections. */
fun NavController.navigateToTab(route: Any) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

/** Navigation de l'application : cinq sections principales et écrans de détail. */
@Composable
fun AppNavHost(nav: NavHostController = rememberNavController()) {
    val entry by nav.currentBackStackEntryAsState()
    val destination = entry?.destination
    val showBar = tabs.any { t -> destination?.hasRoute(t.routeClass) == true }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBar) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = destination?.hierarchy?.any { it.hasRoute(tab.routeClass) } == true,
                            onClick = { nav.navigateToTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = HomeRoute, modifier = Modifier.padding(padding)) {
            composable<HomeRoute> { HomeScreen(nav) }
            composable<HouseRoute> { HouseScreen(nav) }
            composable<RadiatorsRoute> { RadiatorsScreen(nav) }
            composable<SimulationsRoute> { SimulationsScreen(nav) }
            composable<ReportsRoute> { ReportsScreen(nav) }

            composable<BuildingEditRoute> { BuildingEditScreen(nav) }
            composable<RoomEditRoute> { RoomEditScreen(nav) }
            composable<SurfaceEditRoute> { SurfaceEditScreen(nav) }
            composable<MaterialsRoute> { MaterialsScreen(nav) }

            composable<RadiatorEditRoute> { RadiatorEditScreen(nav) }
            composable<CaptureRoute> { CaptureScreen(nav) }
            composable<PhotoEditRoute> { PhotoEditScreen(nav) }
            composable<ElementCountRoute> { ElementCountScreen(nav) }
            composable<MeasureRoute> { MeasureScreen(nav) }
            composable<RadiatorLibraryRoute> { RadiatorLibraryScreen(nav) }

            composable<ComparisonRoute> { ComparisonScreen(nav) }
            composable<HeatPumpRoute> { HeatPumpScreen(nav) }
            composable<CoolingRoute> { CoolingScreen(nav) }
            composable<ScenariosRoute> { ScenariosScreen(nav) }
            composable<ScenarioEditRoute> { ScenarioEditScreen(nav) }
            composable<EnergyRoute> { EnergyScreen(nav) }
            composable<SettingsRoute> { SettingsScreen(nav) }
        }
    }
}
