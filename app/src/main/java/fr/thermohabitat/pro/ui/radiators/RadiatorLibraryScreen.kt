package fr.thermohabitat.pro.ui.radiators

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.thermohabitat.pro.data.repository.ProjectRepository
import fr.thermohabitat.pro.ui.components.ConfirmDialog
import fr.thermohabitat.pro.ui.components.InfoBanner
import fr.thermohabitat.pro.ui.components.RadiatorIllustration
import fr.thermohabitat.pro.ui.components.ScreenScaffold
import fr.thermohabitat.pro.ui.components.ScrollColumn
import fr.thermohabitat.pro.ui.navigation.RadiatorLibraryRoute
import fr.thermohabitat.pro.ui.util.Fmt
import fr.thermohabitat.thermal.library.RadiatorCategory
import fr.thermohabitat.thermal.library.RadiatorLibrary
import fr.thermohabitat.thermal.model.IdentificationMethod
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RadiatorLibraryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: ProjectRepository,
) : ViewModel() {
    val radiatorId = savedStateHandle.toRoute<RadiatorLibraryRoute>().radiatorId
    val radiator = repository.observeRadiator(radiatorId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Applique la catégorie choisie : la fiche doit ensuite être validée par l'utilisateur. */
    /** Évite un double choix (double appui) qui fermerait aussi la fiche radiateur. */
    private var choosing = false

    fun choose(cat: RadiatorCategory, onDone: () -> Unit) {
        if (choosing) return
        choosing = true
        viewModelScope.launch {
            val r = repository.getRadiator(radiatorId)
            if (r == null) { choosing = false; return@launch }
            repository.updateRadiator(r.copy(
                type = cat.type, material = cat.type.defaultMaterial, era = cat.era, categoryCode = cat.code,
                columns = cat.columns ?: r.columns, panelType = cat.panelType ?: r.panelType,
                identificationMethod = IdentificationMethod.LIBRARY, identificationConfidence = null, validated = false,
                updatedAt = System.currentTimeMillis(),
            ))
            onDone()
        }
    }
}

@Composable
fun RadiatorLibraryScreen(nav: NavController, vm: RadiatorLibraryViewModel = hiltViewModel()) {
    val r by vm.radiator.collectAsStateWithLifecycle()
    // Un appui ne remplace la fiche qu'après confirmation (appui involontaire en faisant défiler la liste).
    var pending by remember { mutableStateOf<RadiatorCategory?>(null) }
    ScreenScaffold(title = "Bibliothèque illustrée", onBack = { nav.popBackStack() }) { padding ->
        val ranked = r?.let { RadiatorLibrary.plausibleCategories(it.depthMm, it.columns, null) } ?: RadiatorLibrary.categories
        val others = RadiatorLibrary.categories.filter { it !in ranked }
        ScrollColumn(padding) {
            InfoBanner(
                if (r?.depthMm != null || r?.columns != null)
                    "Catégories classées selon les mesures saisies (profondeur ${Fmt.mm(r?.depthMm)}, colonnes ${r?.columns ?: "–"}). Comparez avec votre radiateur, touchez la bonne catégorie puis confirmez."
                else "Comparez votre radiateur aux illustrations (vue de face et vue de dessus), touchez la bonne catégorie puis confirmez. Mesurer la profondeur et compter les colonnes permet de classer les catégories plausibles."
            )
            (ranked + others).forEachIndexed { index, cat ->
                val selected = r?.categoryCode == cat.code
                Card(
                    Modifier.fillMaxWidth().clickable { pending = cat },
                    shape = RoundedCornerShape(14.dp),
                    colors = if (selected) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else CardDefaults.cardColors(),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        // Titre sur toute la largeur : il n'est plus coincé à côté de l'illustration.
                        Text(cat.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        if (selected) Text("Catégorie actuelle de la fiche", color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        else if (index < 3 && (r?.depthMm != null || r?.columns != null)) Text("Catégorie plausible", color = MaterialTheme.colorScheme.secondary,
                            style = MaterialTheme.typography.labelMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadiatorIllustration(cat, Modifier.size(140.dp, 90.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                cat.depthRangeMm?.let { Text("Profondeur usuelle ${it.first}–${it.last} mm", style = MaterialTheme.typography.bodySmall) }
                                cat.sectionPitchMm?.let { Text("Pas d'élément ≈ $it mm", style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                        Text(cat.description, style = MaterialTheme.typography.bodyMedium)
                        cat.visualCues.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                        Text("À relever : " + cat.measureTips.joinToString(" "), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    pending?.let { cat ->
        ConfirmDialog(
            "Retenir « ${cat.title} » ?",
            "Le type, le matériau et l'époque de la fiche seront remplacés par ceux de cette catégorie, ainsi que les colonnes ou le type " +
                "de panneau s'ils lui sont propres. La fiche repassera « à valider ».",
            "Retenir",
            onConfirm = { vm.choose(cat) { nav.popBackStack() } },
            onDismiss = { pending = null },
        )
    }
}
