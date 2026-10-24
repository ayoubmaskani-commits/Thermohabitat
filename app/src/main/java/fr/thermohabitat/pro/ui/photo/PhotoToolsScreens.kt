package fr.thermohabitat.pro.ui.photo

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
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
import fr.thermohabitat.pro.data.files.PhotoStorage
import fr.thermohabitat.pro.data.repository.ProjectRepository
import fr.thermohabitat.pro.ui.components.BannerKind
import fr.thermohabitat.pro.ui.components.DropdownField
import fr.thermohabitat.pro.ui.components.EmptyState
import fr.thermohabitat.pro.ui.components.InfoBanner
import fr.thermohabitat.pro.ui.components.LargeActionButton
import fr.thermohabitat.pro.ui.components.NumberField
import fr.thermohabitat.pro.ui.components.ScreenScaffold
import fr.thermohabitat.pro.ui.components.ScrollColumn
import fr.thermohabitat.pro.ui.components.SectionCard
import fr.thermohabitat.pro.ui.components.ValueRow
import fr.thermohabitat.pro.ui.navigation.MeasureRoute
import fr.thermohabitat.pro.ui.navigation.PhotoEditRoute
import fr.thermohabitat.pro.ui.util.Fmt
import fr.thermohabitat.thermal.model.ValueOrigin
import fr.thermohabitat.thermal.vision.ImagePoint
import fr.thermohabitat.thermal.vision.MeasurementResult
import fr.thermohabitat.thermal.vision.ReferenceMeasurement
import fr.thermohabitat.thermal.vision.ReferenceObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Chargement de la photo, ou message explicite si son fichier est introuvable ou illisible. */
@Composable
private fun PhotoLoading(failed: Boolean, onBack: () -> Unit) {
    if (failed) {
        EmptyState("Photo illisible", "Le fichier de cette photo est introuvable ou endommagé. Reprenez la photo depuis la fiche.",
            actionLabel = "Retour", onAction = onBack)
    } else {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(24.dp))
            Text("Chargement de la photo…")
        }
    }
}

// =====================================================================================
// Recadrage et rotation
// =====================================================================================

@HiltViewModel
class PhotoEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: ProjectRepository,
    private val storage: PhotoStorage,
) : ViewModel() {
    private val photoId = savedStateHandle.toRoute<PhotoEditRoute>().photoId
    val bitmap = MutableStateFlow<Bitmap?>(null)
    val saving = MutableStateFlow(false)
    /** Photo introuvable ou fichier illisible : l'écran l'explique au lieu de charger indéfiniment. */
    val failed = MutableStateFlow(false)
    private var fileName: String? = null

    init {
        viewModelScope.launch {
            val p = repository.getPhoto(photoId)
            if (p != null) fileName = p.fileName
            val bmp = p?.let { storage.loadBitmap(it.fileName, PhotoStorage.MAX_SIDE_PX) }
            if (bmp == null) failed.value = true else bitmap.value = bmp
        }
    }

    fun rotate() {
        val b = bitmap.value ?: return
        bitmap.value = Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(90f) }, true)
    }

    fun save(rect: androidx.compose.ui.geometry.Rect, onDone: () -> Unit) = viewModelScope.launch {
        val b = bitmap.value ?: return@launch
        val oldName = fileName ?: return@launch
        if (saving.value) return@launch
        saving.value = true
        // Nouveau fichier : le cache d'images est indexé par le chemin, réécrire le même fichier laisserait
        // les vignettes afficher l'image d'origine.
        val prefix = oldName.substringBefore('_').substringBefore('.').ifBlank { "photo" }
        val newFile = storage.newPhotoFile(prefix)
        withContext(Dispatchers.Default) {
            val l = rect.left.toInt().coerceIn(0, b.width - 1); val t = rect.top.toInt().coerceIn(0, b.height - 1)
            val w = (rect.width.toInt()).coerceIn(1, b.width - l); val h = (rect.height.toInt()).coerceIn(1, b.height - t)
            val cropped = Bitmap.createBitmap(b, l, t, w, h)
            storage.saveBitmap(cropped, newFile.name)
        }
        val p = repository.getPhoto(photoId)
        if (p != null) {
            val (w, h) = storage.dimensions(newFile.name)
            repository.updatePhoto(p.copy(fileName = newFile.name, width = w, height = h))
            storage.delete(oldName)
            fileName = newFile.name
        } else {
            storage.delete(newFile.name) // photo supprimée entre-temps : pas de fichier orphelin
        }
        saving.value = false
        onDone()
    }
}

@Composable
fun PhotoEditScreen(nav: NavController, vm: PhotoEditViewModel = hiltViewModel()) {
    val bitmap by vm.bitmap.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()
    val failed by vm.failed.collectAsStateWithLifecycle()
    ScreenScaffold(title = "Recadrage", onBack = { nav.popBackStack() }) { padding ->
        ScrollColumn(padding) {
            val b = bitmap
            if (b == null) { PhotoLoading(failed) { nav.popBackStack() }; return@ScrollColumn }
            val crop = remember(b) { CropController(b.width, b.height, 0.02f) }
            val accent = MaterialTheme.colorScheme.secondary
            ImageWorkArea(
                b,
                onDragStart = { p, minTolerance -> crop.start(p, maxOf(maxOf(b.width, b.height) * 0.07f, minTolerance)) },
                onDrag = { _, delta -> crop.drag(delta) },
            ) { frame -> drawCrop(frame, crop.rect, accent) }
            Text("Faites glisser les coins pour recadrer sur le radiateur, ou le cadre pour le déplacer.", style = MaterialTheme.typography.bodySmall)
            // Conséquence irréversible : annoncée avant le bouton, pas après.
            InfoBanner("L'image d'origine sera remplacée par la version recadrée.", kind = BannerKind.WARNING)
            // Boutons pleine largeur empilés : aucun libellé coupé, même avec une grande police.
            LargeActionButton(if (saving) "Enregistrement…" else "Enregistrer le recadrage", Icons.Filled.Check,
                onClick = { if (!saving) vm.save(crop.rect) { nav.popBackStack() } })
            LargeActionButton("Pivoter de 90°", Icons.Filled.Rotate90DegreesCw, onClick = { if (!saving) vm.rotate() }, outlined = true)
        }
    }
}

// =====================================================================================
// Mesure assistée avec objet de référence
// =====================================================================================

enum class MeasureTarget(val label: String) { HEIGHT("Hauteur"), LENGTH("Longueur"), DEPTH("Profondeur") }

@HiltViewModel
class MeasureViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: ProjectRepository,
    private val storage: PhotoStorage,
) : ViewModel() {
    private val route = savedStateHandle.toRoute<MeasureRoute>()
    val bitmap = MutableStateFlow<Bitmap?>(null)
    val failed = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            val bmp = repository.getPhoto(route.photoId)?.let { storage.loadBitmap(it.fileName, 2048) }
            if (bmp == null) failed.value = true else bitmap.value = bmp
        }
    }

    private var applying = false

    fun apply(target: MeasureTarget, valueMm: Double, onDone: () -> Unit) {
        if (applying) return
        applying = true
        viewModelScope.launch {
            val r = repository.getRadiator(route.radiatorId)
            if (r == null) { applying = false; return@launch }
            val v = Math.round(valueMm).toDouble()
            val updated = when (target) {
                MeasureTarget.HEIGHT -> r.copy(heightMm = v)
                MeasureTarget.LENGTH -> r.copy(lengthMm = v)
                MeasureTarget.DEPTH -> r.copy(depthMm = v)
            }.copy(dimensionsOrigin = ValueOrigin.ESTIMATED, validated = false, updatedAt = System.currentTimeMillis())
            repository.updateRadiator(updated)
            onDone()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MeasureScreen(nav: NavController, vm: MeasureViewModel = hiltViewModel()) {
    val bitmap by vm.bitmap.collectAsStateWithLifecycle()
    val failed by vm.failed.collectAsStateWithLifecycle()
    val points = remember { mutableStateListOf<Offset>() }
    var reference by remember { mutableStateOf(ReferenceObject.BANK_CARD_LONG) }
    var customLength by remember { mutableStateOf<Double?>(null) }
    var target by remember { mutableStateOf(MeasureTarget.HEIGHT) }
    // Calcul avant l'affichage : la consigne et les messages dépendent du résultat (ou de la raison de l'échec).
    val refLength = reference.lengthMm ?: customLength
    val attempt: Result<MeasurementResult>? = if (points.size == 4 && refLength != null) runCatching {
        ReferenceMeasurement.measure(
            ImagePoint(points[0].x.toDouble(), points[0].y.toDouble()), ImagePoint(points[1].x.toDouble(), points[1].y.toDouble()), refLength,
            ImagePoint(points[2].x.toDouble(), points[2].y.toDouble()), ImagePoint(points[3].x.toDouble(), points[3].y.toDouble()),
        )
    } else null
    val result: MeasurementResult? = attempt?.getOrNull()
    val resultBring = remember { BringIntoViewRequester() }
    // Défilement vers le résultat à la pose du 4e point seulement : pas pendant la saisie de la longueur réelle,
    // qui doit rester visible au-dessus du clavier.
    LaunchedEffect(points.size == 4) { if (result != null) { withFrameNanos { }; resultBring.bringIntoView() } }
    ScreenScaffold(title = "Mesure sur photo", onBack = { nav.popBackStack() }) { padding ->
        ScrollColumn(padding) {
            val b = bitmap
            if (b == null) { PhotoLoading(failed) { nav.popBackStack() }; return@ScrollColumn }
            InfoBanner("Posez une carte bancaire ou une feuille A4 contre le radiateur. Touchez ses 2 extrémités, puis celles de la dimension à mesurer.")
            DropdownField("Objet de référence", ReferenceObject.entries, reference,
                { r -> r.label + (r.lengthMm?.let { " (${Fmt.num(it, 1)} mm)" } ?: "") }, { reference = it })
            if (reference.lengthMm == null) {
                NumberField("Longueur réelle", customLength, { customLength = it }, unit = "mm", decimals = 1, min = 10.0, max = 5000.0,
                    supportingText = "Longueur connue de l'objet de référence")
            }
            DropdownField("Dimension mesurée", MeasureTarget.entries, target, { it.label }, { target = it })
            // Consigne pas à pas juste au-dessus de l'image (visible pendant les appuis), avec retour arrière.
            val stepText = when (points.size) {
                0 -> "Touchez la 1re extrémité de la référence (bleu)."
                1 -> "Touchez la 2e extrémité de la référence."
                2 -> "Touchez la 1re extrémité de la ${target.label.lowercase()} (orange)."
                3 -> "Touchez la 2e extrémité de la ${target.label.lowercase()}."
                else -> if (result != null) "Mesure prête, résultat ci-dessous." else "Mesure impossible, voir ci-dessous."
            }
            Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp)) {
                Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${minOf(points.size + 1, 4)}/4 · $stepText", Modifier.weight(1f).padding(vertical = 10.dp),
                        style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    IconButton(enabled = points.isNotEmpty(), onClick = { points.removeAt(points.lastIndex) }) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Annuler le dernier point")
                    }
                }
            }
            val refColor = Color(0xFF00E5FF); val tgtColor = Color(0xFFFF6E40)
            ImageWorkArea(b, onTap = { p -> if (points.size >= 4) points.clear(); points.add(p) }) { frame ->
                // Tailles en dp : points et traits visibles quelle que soit la densité de l'écran.
                points.forEachIndexed { i, p ->
                    val c = if (i < 2) refColor else tgtColor
                    drawCircle(c, 10.dp.toPx(), frame.toScreen(p), style = Stroke(width = 3.dp.toPx()))
                }
                if (points.size >= 2) drawLine(refColor, frame.toScreen(points[0]), frame.toScreen(points[1]), strokeWidth = 3.dp.toPx())
                if (points.size >= 4) drawLine(tgtColor, frame.toScreen(points[2]), frame.toScreen(points[3]), strokeWidth = 3.dp.toPx())
            }
            if (points.size == 4 && result == null) {
                InfoBanner(
                    if (refLength == null) "Indiquez la longueur réelle de la référence (champ ci-dessus) pour obtenir la mesure."
                    else "Mesure impossible : ${attempt?.exceptionOrNull()?.message ?: "points invalides"}. Touchez l'image pour replacer les 4 points.",
                    kind = BannerKind.WARNING,
                )
            }
            result?.let { m ->
                SectionCard("Résultat", Modifier.bringIntoViewRequester(resultBring)) {
                    ValueRow(target.label, "${Fmt.num(m.valueMm, 0)} mm ± ${Fmt.num(m.uncertaintyMm, 0)} mm", emphasize = true)
                    m.warnings.forEach { InfoBanner(it, kind = BannerKind.WARNING) }
                    Text("La fiche repassera « à valider » : confirmez-la ensuite pour l'inclure dans l'étude.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    // Boutons empilés pleine largeur : « Recommencer » n'est plus écrasé par « Reporter sur la fiche ».
                    Button(onClick = { vm.apply(target, m.valueMm) { nav.popBackStack() } }, modifier = Modifier.fillMaxWidth()) {
                        Text("Reporter sur la fiche")
                    }
                    TextButton(onClick = { points.clear() }, modifier = Modifier.fillMaxWidth()) { Text("Recommencer") }
                }
            }
        }
    }
}
