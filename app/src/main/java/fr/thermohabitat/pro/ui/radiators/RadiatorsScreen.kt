package fr.thermohabitat.pro.ui.radiators

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
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
import fr.thermohabitat.pro.data.db.PhotoOwner
import fr.thermohabitat.pro.data.repository.ProjectRepository
import fr.thermohabitat.pro.domain.StudyService
import fr.thermohabitat.pro.ui.components.BannerKind
import fr.thermohabitat.pro.ui.components.ChoiceChips
import fr.thermohabitat.pro.ui.components.ConfirmDialog
import fr.thermohabitat.pro.ui.components.EmptyState
import fr.thermohabitat.pro.ui.components.InfoBanner
import fr.thermohabitat.pro.ui.components.NumberField
import fr.thermohabitat.pro.ui.components.PhotoThumbnail
import fr.thermohabitat.pro.ui.components.ScreenScaffold
import fr.thermohabitat.pro.ui.components.ScrollColumn
import fr.thermohabitat.pro.ui.components.SectionCard
import fr.thermohabitat.pro.ui.components.StatusChip
import fr.thermohabitat.pro.ui.components.ValueRow
import fr.thermohabitat.pro.ui.navigation.CaptureRoute
import fr.thermohabitat.pro.ui.navigation.HomeRoute
import fr.thermohabitat.pro.ui.navigation.HouseRoute
import fr.thermohabitat.pro.ui.navigation.RadiatorEditRoute
import fr.thermohabitat.pro.ui.navigation.navigateToTab
import fr.thermohabitat.pro.ui.util.Fmt
import fr.thermohabitat.thermal.calc.CoverageStatus
import fr.thermohabitat.thermal.calc.PowerBasis
import fr.thermohabitat.thermal.calc.RadiatorPower
import fr.thermohabitat.thermal.calc.Regime
import fr.thermohabitat.thermal.model.Radiator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RadiatorsViewModel @Inject constructor(
    studyService: StudyService,
    private val repository: ProjectRepository,
) : ViewModel() {
    val project = studyService.observeActive().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val regime = MutableStateFlow<Regime?>(null)
    val ambient = MutableStateFlow<Double?>(null)

    suspend fun newDraft(): CaptureRoute? {
        val b = project.value?.project?.id ?: return null
        val id = repository.createRadiatorDraft(b)
        return CaptureRoute(PhotoOwner.RADIATOR.name, id, b)
    }

    suspend fun newManual(): Long? {
        val b = project.value?.project?.id ?: return null
        return repository.createRadiatorDraft(b)
    }

    suspend fun duplicate(id: Long) = repository.duplicateRadiator(id)
    fun delete(id: Long) = viewModelScope.launch { repository.deleteRadiator(id) }
}

@Composable
fun RadiatorsScreen(nav: NavController, vm: RadiatorsViewModel = hiltViewModel()) {
    val analyzed by vm.project.collectAsStateWithLifecycle()
    val regimeOverride by vm.regime.collectAsStateWithLifecycle()
    val ambientOverride by vm.ambient.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var deleteId by remember { mutableStateOf<Long?>(null) }

    ScreenScaffold(
        title = "Radiateurs",
        isTab = true,
        floatingActionButton = {
            if (analyzed != null) {
                ExtendedFloatingActionButton(
                    onClick = { scope.launch { vm.newDraft()?.let { nav.navigate(it) } } },
                    icon = { Icon(Icons.Filled.CameraAlt, null) }, text = { Text("Scanner un radiateur") },
                )
            }
        },
    ) { padding ->
        val p = analyzed
        if (p == null) {
            ScrollColumn(padding) {
                EmptyState("Aucune étude", "Créez une étude pour y enregistrer vos radiateurs.", actionLabel = "Accueil", onAction = { nav.navigateToTab(HomeRoute) })
            }
            return@ScreenScaffold
        }
        val settings = p.project.settings
        val regime = regimeOverride ?: settings.studyRegime
        val radiators = p.project.building.radiators
        val rooms = p.project.building.rooms.associateBy { it.id }
        fun ambientFor(r: Radiator) = ambientOverride ?: r.roomId?.let { rooms[it]?.setpointC } ?: settings.radiatorSheetAmbientC
        val outputs = radiators.associate { r -> r.id to RadiatorPower.outputOf(r, regime.supplyC, regime.returnC, ambientFor(r)) }
        // Même critère que le bilan : validé, puissance établie, attribué à une pièce existante.
        val validatedOut = radiators.filter { r -> RadiatorPower.countsInStudy(r) && r.roomId?.let { it in rooms } == true }
            .mapNotNull { outputs[it.id] }
        val need = p.result.heatLoss.sumOfRoomLoadsW
        val newManual: () -> Unit = { scope.launch { vm.newManual()?.let { nav.navigate(RadiatorEditRoute(it)) } } }

        ScrollColumn(padding) {
            // Inventaire vide : l'invitation à saisir passe avant les réglages.
            if (radiators.isEmpty()) {
                EmptyState("Aucun radiateur", "Photographiez chaque radiateur avec « Scanner un radiateur » : l'application vous guide pour l'identifier, " +
                    "le mesurer et estimer sa puissance.", actionLabel = "Saisie manuelle", onAction = newManual)
            }
            SectionCard("Inventaire photographique", icon = Icons.Filled.Thermostat, subtitle = "${radiators.size} radiateur(s) · ${radiators.count { it.validated }} validé(s)") {
                // L'essentiel d'abord : puissance disponible et besoin, sans « 0 W » qui ressemblerait à un résultat.
                if (radiators.isNotEmpty()) {
                    if (validatedOut.isEmpty()) {
                        InfoBanner("Aucun radiateur validé et attribué à une pièce : ouvrez chaque fiche, vérifiez-la puis validez-la.", kind = BannerKind.WARNING)
                    } else {
                        // « validés » reste dans le libellé : un total partiel ne doit pas passer pour la puissance de tous les radiateurs.
                        ValueRow("Puissance des radiateurs validés à ${regime.label}", Fmt.watts(validatedOut.sumOf { it.powerW }), emphasize = true)
                        ValueRow("Plage d'incertitude", "${Fmt.watts(validatedOut.sumOf { it.lowW })} – ${Fmt.watts(validatedOut.sumOf { it.highW })}")
                        val notCounted = radiators.size - validatedOut.size
                        if (notCounted > 0) {
                            Text("$notCounted radiateur(s) non compté(s) : à valider, à compléter ou à attribuer à une pièce.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                ValueRow("Besoin du bâtiment", when {
                    need > 0 -> Fmt.watts(need)
                    rooms.isEmpty() -> "Pièces à saisir"
                    else -> "Pièces à compléter"
                })
                if (need <= 0) {
                    TextButton(onClick = { nav.navigateToTab(HouseRoute) }) { Text(if (rooms.isEmpty()) "Saisir les pièces" else "Compléter les pièces") }
                }
                if (radiators.isNotEmpty()) {
                    Text("Régime de chauffage", style = MaterialTheme.typography.labelLarge)
                    ChoiceChips(Regime.STANDARD, regime, { it.label }, { vm.regime.value = it })
                    NumberField("Température ambiante", ambientOverride, { vm.ambient.value = it }, unit = "°C", decimals = 1, min = 5.0, max = 30.0,
                        supportingText = "Vide : consigne de chaque pièce")
                    OutlinedButton(onClick = newManual) { Text("Saisie manuelle d'un radiateur") }
                }
            }
            radiators.forEach { r -> androidx.compose.runtime.key(r.id) {
                val out = outputs[r.id]
                val nominal = RadiatorPower.resolveNominal(r)
                val photo = p.project.photosOf(PhotoOwner.RADIATOR, r.id).firstOrNull()
                var menu by remember { mutableStateOf(false) }
                Card(Modifier.fillMaxWidth().clickable { nav.navigate(RadiatorEditRoute(r.id)) }, shape = RoundedCornerShape(14.dp)) {
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (photo != null) PhotoThumbnail(photo.fileName, Modifier.size(84.dp))
                        else Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Thermostat, null) }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${r.code} · ${r.label.ifBlank { r.type.label }}", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                                IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.MoreVert, "Actions") }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(text = { Text("Modifier") }, leadingIcon = { Icon(Icons.Filled.Edit, null) },
                                        onClick = { menu = false; nav.navigate(RadiatorEditRoute(r.id)) })
                                    DropdownMenuItem(text = { Text("Dupliquer") }, leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
                                        onClick = { menu = false; scope.launch { vm.duplicate(r.id)?.let { nav.navigate(RadiatorEditRoute(it)) } } })
                                    DropdownMenuItem(text = { Text("Supprimer") }, leadingIcon = { Icon(Icons.Filled.Delete, null) },
                                        onClick = { menu = false; deleteId = r.id })
                                }
                            }
                            Text(r.roomId?.let { rooms[it]?.name } ?: "Pièce non attribuée", style = MaterialTheme.typography.bodySmall)
                            Text(dimensionsText(r), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                when (nominal?.basis) {
                                    PowerBasis.DOCUMENTED -> "Nominale : ${Fmt.watts(nominal.nominalW)} à ${nominal.rating.label}"
                                    PowerBasis.ESTIMATED -> "Estimée : ${Fmt.watts(nominal.nominalW)} (${Fmt.watts(nominal.lowW)}–${Fmt.watts(nominal.highW)}) à " +
                                        if (nominal.rating == fr.thermohabitat.thermal.model.RatingCondition.EN442) "ΔT 50 K" else nominal.rating.label
                                    else -> "Puissance inconnue : compléter la fiche"
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                            // Puissance au régime : résultat principal de la carte, jamais un « 0 W » trompeur.
                            when {
                                nominal == null -> Unit // « Puissance inconnue : compléter la fiche » est affiché juste au-dessus.
                                out == null || out.powerW <= 0.0 -> Text("À ${regime.label} : non calculable, vérifiez la fiche et l'ambiance",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                else -> Text("À ${regime.label} : ${Fmt.watts(out.powerW)}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            }
                            StatusChip(
                                if (r.validated && r.roomId != null) CoverageStatus.SUFFICIENT else CoverageStatus.LIMIT,
                                text = when {
                                    !r.validated -> "À valider – non compté dans l'étude"
                                    r.roomId == null -> "Validé, sans pièce – exclu du bilan"
                                    else -> "Caractéristiques validées"
                                },
                            )
                        }
                    }
                }
            } }
        }
    }
    deleteId?.let { id ->
        ConfirmDialog("Supprimer ce radiateur ?", "La fiche et ses photos seront effacées du téléphone.", "Supprimer",
            onConfirm = { vm.delete(id) }, onDismiss = { deleteId = null }, destructive = true)
    }
}

fun dimensionsText(r: Radiator): String {
    val parts = mutableListOf<String>()
    if (r.heightMm != null) parts += "H ${Fmt.num(r.heightMm, 0)}"
    if (r.lengthMm != null) parts += "L ${Fmt.num(r.lengthMm, 0)}"
    if (r.depthMm != null) parts += "P ${Fmt.num(r.depthMm, 0)}"
    val dims = if (parts.isEmpty()) "Dimensions à relever" else parts.joinToString(" × ") + " mm"
    val sec = listOfNotNull(r.sections?.let { "$it éléments" }, r.columns?.let { "$it colonnes" }, r.panelType?.name?.removePrefix("T")?.let { "type $it" })
    return (listOf(dims) + sec).joinToString(" · ")
}
