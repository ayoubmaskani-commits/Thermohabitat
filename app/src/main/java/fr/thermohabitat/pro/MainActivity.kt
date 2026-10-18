package fr.thermohabitat.pro

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.thermohabitat.pro.data.db.BuildingDao
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable
import javax.inject.Inject

@Serializable
data object ProbeRoute

@HiltViewModel
class ProbeViewModel @Inject constructor(buildingDao: BuildingDao) : ViewModel() {
    val count = buildingDao.observeAll().map { it.size }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val nav = rememberNavController()
                NavHost(nav, startDestination = ProbeRoute) {
                    composable<ProbeRoute> {
                        val vm: ProbeViewModel = hiltViewModel()
                        val count by vm.count.collectAsStateWithLifecycle()
                        Scaffold { padding ->
                            Box(Modifier.fillMaxSize().padding(padding)) {
                                Text("ThermoHabitat Pro – $count projet(s)")
                            }
                        }
                    }
                }
            }
        }
    }
}
