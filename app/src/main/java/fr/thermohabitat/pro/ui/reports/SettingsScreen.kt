package fr.thermohabitat.pro.ui.reports

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.thermohabitat.pro.BuildConfig
import fr.thermohabitat.pro.data.repository.AppPreferences
import fr.thermohabitat.pro.data.repository.SettingsRepository
import fr.thermohabitat.pro.data.repository.ThemeMode
import fr.thermohabitat.pro.recognition.RadiatorClassifier
import fr.thermohabitat.pro.recognition.TfLiteRadiatorClassifier
import fr.thermohabitat.pro.ui.components.ChoiceChips
import fr.thermohabitat.pro.ui.components.ScreenScaffold
import fr.thermohabitat.pro.ui.components.ScrollColumn
import fr.thermohabitat.pro.ui.components.SectionCard
import fr.thermohabitat.pro.ui.components.SwitchRow
import fr.thermohabitat.pro.ui.components.ValueRow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    classifier: RadiatorClassifier,
) : ViewModel() {
    val prefs = settings.preferences.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppPreferences())
    val aiAvailable = classifier.isAvailable
    fun setTheme(m: ThemeMode) = viewModelScope.launch { settings.setTheme(m) }
    fun setDynamic(on: Boolean) = viewModelScope.launch { settings.setDynamicColor(on) }
}

@Composable
fun SettingsScreen(nav: NavController, vm: SettingsViewModel = hiltViewModel()) {
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    ScreenScaffold(title = "Réglages", onBack = { nav.popBackStack() }) { padding ->
        ScrollColumn(padding) {
            SectionCard("Affichage", icon = Icons.Filled.Tune) {
                Text("Thème", style = MaterialTheme.typography.labelLarge)
                ChoiceChips(ThemeMode.entries, prefs.themeMode, { it.label }, { vm.setTheme(it) })
                SwitchRow("Couleurs dynamiques (Android 12+)", prefs.dynamicColor, { vm.setDynamic(it) }, description = "Utilise les couleurs du fond d'écran")
            }
            SectionCard("Reconnaissance des radiateurs", icon = Icons.Filled.AutoAwesome) {
                ValueRow("Modèle d'IA embarqué", if (vm.aiAvailable) "Installé" else "Non installé")
                Text(
                    if (vm.aiAvailable) "Les propositions du modèle sont affichées avec leur indice de confiance et doivent être validées."
                    else "Aucun modèle entraîné et validé n'est fourni : l'identification utilise la bibliothèque illustrée, le comptage assisté des éléments " +
                        "(analyse de périodicité) et la lecture de plaque (OCR embarqué). Un modèle LiteRT/TensorFlow Lite peut être ajouté dans " +
                        "assets/${TfLiteRadiatorClassifier.MODEL_ASSET} avec ses étiquettes (${TfLiteRadiatorClassifier.LABELS_ASSET}).",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            SectionCard("Méthodes de calcul", icon = Icons.Filled.Info) {
                listOf(
                    "Déperditions : principes de la NF EN 12831-1 (pièce par pièce), EN ISO 6946 (R, U), EN ISO 13370 (terre-plein).",
                    "Radiateurs : correction NF EN 442 par l'écart logarithmique et l'exposant n ; estimations géométriques avec plage d'incertitude.",
                    "PAC : loi d'eau, données constructeur ou modèle de Carnot générique, SCOP par classes de température NF EN 14825.",
                    "Dynamique : modèle RC à 2 nœuds par pièce (inspiré de la NF EN ISO 13790), inertie selon la NF EN ISO 13786.",
                    "Énergie : méthode des degrés-jours, rendements saisonniers et prix saisis par l'utilisateur.",
                ).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                Text("Les résultats sont indicatifs et ne remplacent pas une étude thermique réglementaire.", style = MaterialTheme.typography.bodySmall)
            }
            SectionCard("À propos") {
                ValueRow("Version", BuildConfig.VERSION_NAME)
                ValueRow("Données", "100 % locales, sans compte ni abonnement")
            }
        }
    }
}
