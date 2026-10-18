package fr.thermohabitat.pro.ui.photo

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
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
import fr.thermohabitat.pro.ui.components.InfoBanner
import fr.thermohabitat.pro.ui.components.NumberField
import fr.thermohabitat.pro.ui.components.ScreenScaffold
import fr.thermohabitat.pro.ui.components.ScrollColumn
import fr.thermohabitat.pro.ui.components.SectionCard
import fr.thermohabitat.pro.ui.components.ValueRow
import fr.thermohabitat.pro.ui.navigation.ElementCountRoute
import fr.thermohabitat.pro.ui.navigation.MeasureRoute
import fr.thermohabitat.pro.ui.navigation.PhotoEditRoute
import fr.thermohabitat.pro.ui.util.Fmt
import fr.thermohabitat.thermal.model.ValueOrigin
import fr.thermohabitat.thermal.vision.ElementCountResult
import fr.thermohabitat.thermal.vision.ElementCounter
import fr.thermohabitat.thermal.vision.ImagePoint
import fr.thermohabitat.thermal.vision.MeasurementResult
import fr.thermohabitat.thermal.vision.ReferenceMeasurement
import fr.thermohabitat.thermal.vision.ReferenceObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

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
    private var fileName: String? = null

    init {
        viewModelScope.launch {
            val p = repository.getPhoto(photoId) ?: return@launch
            fileName = p.fileName
            bitmap.value = storage.loadBitmap(p.fileName, PhotoStorage.MAX_SIDE_PX)
        }
    }

    fun rotate() {
        val b = bitmap.value ?: return
        bitmap.value = Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(90f) }, true)
    }

    fun save(rect: androidx.compose.ui.geometry.Rect, onDone: () -> Unit) = viewModelScope.launch {
        val b = bitmap.value ?: return@launch
        val name = fileName ?: return@launch
        saving.value = true
        withContext(Dispatchers.Default) {
            val l = rect.left.toInt().coerceIn(0, b.width - 1); val t = rect.top.toInt().coerceIn(0, b.height - 1)
            val w = (rect.width.toInt()).coerceIn(1, b.width - l); val h = (rect.height.toInt()).coerceIn(1, b.height - t)
            val cropped = Bitmap.createBitmap(b, l, t, w, h)
            storage.saveBitmap(cropped, name)
        }
        repository.getPhoto(photoId)?.let { p ->
            val (w, h) = storage.dimensions(name)
            repository.updatePhoto(p.copy(width = w, height = h))
        }
        saving.value = false
        onDone()
    }
}

@Composable
fun PhotoEditScreen(nav: NavController, vm: PhotoEditViewModel = hiltViewModel()) {
    val bitmap by vm.bitmap.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()
    ScreenScaffold(title = "Recadrer et corriger", onBack = { nav.popBackStack() }) { padding ->
        ScrollColumn(padding) {
            val b = bitmap
            if (b == null) { CircularProgressIndicator(); return@ScrollColumn }
            val crop = remember(b) { CropController(b.width, b.height, 0.02f) }
            val accent = MaterialTheme.colorScheme.secondary
            ImageWorkArea(
                b,
                onDragStart = { crop.start(it, maxOf(b.width, b.height) * 0.07f) },
                onDrag = { _, delta -> crop.drag(delta) },
            ) { frame -> drawCrop(frame, crop.rect, accent) }
            Text("Faites glisser les coins pour recadrer sur le radiateur, ou le cadre pour le déplacer.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.rotate() }) { Icon(Icons.Filled.Rotate90DegreesCw, null); Text(" Pivoter") }
                Button(enabled = !saving, onClick = { vm.save(crop.rect) { nav.popBackStack() } }) { Text("Enregistrer") }
            }
            InfoBanner("L'image d'origine est remplacée par la version recadrée (stockage privé du téléphone).")
        }
    }
}

// =====================================================================================
// Comptage assisté des éléments
// =====================================================================================

@HiltViewModel
class ElementCountViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: ProjectRepository,
    private val storage: PhotoStorage,
) : ViewModel() {
    private val route = savedStateHandle.toRoute<ElementCountRoute>()
    val bitmap = MutableStateFlow<Bitmap?>(null)
    val result = MutableStateFlow<ElementCountResult?>(null)
    val analyzedRect = MutableStateFlow<androidx.compose.ui.geometry.Rect?>(null)

    init {
        viewModelScope.launch {
            val p = repository.getPhoto(route.photoId) ?: return@launch
            bitmap.value = storage.loadBitmap(p.fileName, 1600)
        }
    }

    fun analyze(rect: androidx.compose.ui.geometry.Rect) = viewModelScope.launch {
        val b = bitmap.value ?: return@launch
        result.value = withContext(Dispatchers.Default) { ElementCounter.count(b.toGrayImage(rect)) }
        analyzedRect.value = rect
    }

    /** Reporte le nombre proposé sur la fiche (qui repasse « à valider »). */
    fun apply(count: Int, confidence: Double, onDone: () -> Unit) = viewModelScope.launch {
        val r = repository.getRadiator(route.radiatorId) ?: return@launch
        repository.updateRadiator(r.copy(sections = count, identificationConfidence = confidence, validated = false,
            updatedAt = System.currentTimeMillis()))
        onDone()
    }
}

@Composable
fun ElementCountScreen(nav: NavController, vm: ElementCountViewModel = hiltViewModel()) {
    val bitmap by vm.bitmap.collectAsStateWithLifecycle()
    val result by vm.result.collectAsStateWithLifecycle()
    val analyzedRect by vm.analyzedRect.collectAsStateWithLifecycle()
    var manual by remember { mutableStateOf<Int?>(null) }
    ScreenScaffold(title = "Compter les éléments", onBack = { nav.popBackStack() }) { padding ->
        ScrollColumn(padding) {
            val b = bitmap
            if (b == null) { CircularProgressIndicator(); return@ScrollColumn }
            InfoBanner("Utilisez une photo DE FACE. Encadrez uniquement le corps du radiateur (sans les pieds ni les robinets), puis lancez l'analyse. " +
                "La méthode recherche la répétition régulière des éléments (analyse de périodicité) : vérifiez toujours le résultat.")
            val crop = remember(b) { CropController(b.width, b.height, 0.15f) }
            val accent = MaterialTheme.colorScheme.secondary
            val lines = Color(0xFFFFC107)
            ImageWorkArea(
                b,
                onDragStart = { crop.start(it, maxOf(b.width, b.height) * 0.07f) },
                onDrag = { _, d -> crop.drag(d) },
            ) { frame ->
                drawCrop(frame, crop.rect, accent)
                val r = result; val ar = analyzedRect
                if (r != null && ar != null) {
                    r.boundariesPx.forEach { x ->
                        val top = frame.toScreen(Offset(ar.left + x.toFloat(), ar.top))
                        val bottom = frame.toScreen(Offset(ar.left + x.toFloat(), ar.bottom))
                        drawLine(lines, top, bottom, strokeWidth = 2.5f)
                    }
                }
            }
            Button(onClick = { vm.analyze(crop.rect) }) { Text("Analyser la zone encadrée") }
            result?.let { r ->
                SectionCard("Résultat de l'analyse") {
                    ValueRow("Éléments proposés", r.count?.toString() ?: "Non déterminé", emphasize = true)
                    ValueRow("Indice de confiance", Fmt.pct(r.confidence))
                    r.periodPx?.let { ValueRow("Largeur d'un élément sur l'image", "${Fmt.num(it, 1)} px") }
                    InfoBanner(r.message, kind = if (r.reliable) BannerKind.INFO else BannerKind.WARNING)
                    Text("Les traits jaunes montrent les limites d'éléments détectées : elles doivent coïncider avec les joints du radiateur.",
                        style = MaterialTheme.typography.bodySmall)
                    NumberField("Nombre retenu", (manual ?: r.count)?.toDouble(), { manual = it?.toInt() }, decimals = 0, min = 1.0, max = 200.0)
                    val retained = manual ?: r.count
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = retained != null, onClick = {
                            vm.apply(retained!!, if (manual != null && manual != r.count) 1.0 else r.confidence) { nav.popBackStack() }
                        }) { Text("Reporter sur la fiche") }
                        TextButton(onClick = { nav.popBackStack() }) { Text("Annuler") }
                    }
                }
            }
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

    init {
        viewModelScope.launch {
            val p = repository.getPhoto(route.photoId) ?: return@launch
            bitmap.value = storage.loadBitmap(p.fileName, 2048)
        }
    }

    fun apply(target: MeasureTarget, valueMm: Double, onDone: () -> Unit) = viewModelScope.launch {
        val r = repository.getRadiator(route.radiatorId) ?: return@launch
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

@Composable
fun MeasureScreen(nav: NavController, vm: MeasureViewModel = hiltViewModel()) {
    val bitmap by vm.bitmap.collectAsStateWithLifecycle()
    val points = remember { mutableStateListOf<Offset>() }
    var reference by remember { mutableStateOf(ReferenceObject.BANK_CARD_LONG) }
    var customLength by remember { mutableStateOf<Double?>(null) }
    var target by remember { mutableStateOf(MeasureTarget.HEIGHT) }
    ScreenScaffold(title = "Mesure avec référence", onBack = { nav.popBackStack() }) { padding ->
        ScrollColumn(padding) {
            val b = bitmap
            if (b == null) { CircularProgressIndicator(); return@ScrollColumn }
            InfoBanner("Posez un objet de dimension connue contre le radiateur (même plan), photo de face. " +
                "Touchez les 2 extrémités de la référence, puis les 2 extrémités de la dimension à mesurer. " +
                "Sans objet de référence, aucune dimension ne peut être déduite de la photo.")
            DropdownField("Objet de référence", ReferenceObject.entries, reference,
                { r -> r.label + (r.lengthMm?.let { " (${Fmt.num(it, 1)} mm)" } ?: "") }, { reference = it })
            if (reference.lengthMm == null) {
                NumberField("Longueur réelle de la référence", customLength, { customLength = it }, unit = "mm", decimals = 1, min = 10.0, max = 5000.0)
            }
            DropdownField("Dimension mesurée", MeasureTarget.entries, target, { it.label }, { target = it })
            val refColor = Color(0xFF00E5FF); val tgtColor = Color(0xFFFF6E40)
            ImageWorkArea(b, onTap = { p -> if (points.size >= 4) points.clear(); points.add(p) }) { frame ->
                points.forEachIndexed { i, p ->
                    val c = if (i < 2) refColor else tgtColor
                    drawCircle(c, 12f, frame.toScreen(p), style = Stroke(width = 4f))
                }
                if (points.size >= 2) drawLine(refColor, frame.toScreen(points[0]), frame.toScreen(points[1]), strokeWidth = 4f)
                if (points.size >= 4) drawLine(tgtColor, frame.toScreen(points[2]), frame.toScreen(points[3]), strokeWidth = 4f)
            }
            Text(
                when (points.size) {
                    0 -> "Touchez la 1re extrémité de la référence (bleu)."
                    1 -> "Touchez la 2e extrémité de la référence."
                    2 -> "Touchez la 1re extrémité de la ${target.label.lowercase()} (orange)."
                    3 -> "Touchez la 2e extrémité de la ${target.label.lowercase()}."
                    else -> "Mesure prête. Touchez l'image pour recommencer."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            val refLength = reference.lengthMm ?: customLength
            val result: MeasurementResult? = if (points.size == 4 && refLength != null) runCatching {
                ReferenceMeasurement.measure(
                    ImagePoint(points[0].x.toDouble(), points[0].y.toDouble()), ImagePoint(points[1].x.toDouble(), points[1].y.toDouble()), refLength,
                    ImagePoint(points[2].x.toDouble(), points[2].y.toDouble()), ImagePoint(points[3].x.toDouble(), points[3].y.toDouble()),
                )
            }.getOrNull() else null
            result?.let { m ->
                SectionCard("Résultat") {
                    ValueRow(target.label, "${Fmt.num(m.valueMm, 0)} mm ± ${Fmt.num(m.uncertaintyMm, 0)} mm", emphasize = true)
                    m.warnings.forEach { InfoBanner(it, kind = BannerKind.WARNING) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.apply(target, m.valueMm) { nav.popBackStack() } }) { Text("Reporter sur la fiche") }
                        TextButton(onClick = { points.clear() }) { Text("Recommencer") }
                    }
                }
            }
        }
    }
}
