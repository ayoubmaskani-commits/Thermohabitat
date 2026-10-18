package fr.thermohabitat.pro

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.activity.viewModels
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.thermohabitat.pro.data.repository.AppPreferences
import fr.thermohabitat.pro.data.repository.MaterialRepository
import fr.thermohabitat.pro.data.repository.SettingsRepository
import fr.thermohabitat.pro.ui.navigation.AppNavHost
import fr.thermohabitat.pro.ui.theme.ThermoHabitatTheme
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    settings: SettingsRepository,
    materials: MaterialRepository,
) : ViewModel() {
    val preferences = settings.preferences.stateIn(viewModelScope, SharingStarted.Eagerly, AppPreferences())

    init {
        // Initialisation de la bibliothèque de matériaux intégrée (hors ligne).
        viewModelScope.launch { materials.ensureSeeded() }
    }
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val prefs by viewModel.preferences.collectAsStateWithLifecycle()
            ThermoHabitatTheme(themeMode = prefs.themeMode, dynamicColor = prefs.dynamicColor) {
                AppNavHost()
            }
        }
    }
}
