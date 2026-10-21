package fr.thermohabitat.pro.ui.photo

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.thermohabitat.pro.data.db.RadiatorEntity
import fr.thermohabitat.pro.data.db.toModel
import fr.thermohabitat.pro.data.files.PhotoStorage
import fr.thermohabitat.pro.data.repository.ProjectRepository
import fr.thermohabitat.pro.ui.components.BannerKind
import fr.thermohabitat.pro.ui.components.ChoiceChips
import fr.thermohabitat.pro.ui.components.DropdownField
import fr.thermohabitat.pro.ui.components.EmptyState
import fr.thermohabitat.pro.ui.components.FieldRow
import fr.thermohabitat.pro.ui.components.InfoBanner
import fr.thermohabitat.pro.ui.components.IntField
import fr.thermohabitat.pro.ui.components.NumberField
import fr.thermohabitat.pro.ui.components.OriginBadge
import fr.thermohabitat.pro.ui.components.ScreenScaffold
import fr.thermohabitat.pro.ui.components.ScrollColumn
import fr.thermohabitat.pro.ui.components.SectionCard
import fr.thermohabitat.pro.ui.components.SwitchRow
import fr.thermohabitat.pro.ui.components.ValueRow
import fr.thermohabitat.pro.ui.navigation.PhotoIdentifyRoute
import fr.thermohabitat.pro.ui.util.Fmt
import fr.thermohabitat.thermal.calc.PowerEstimate
import fr.thermohabitat.thermal.calc.RadiatorEstimator
import fr.thermohabitat.thermal.library.CategoryMatch
import fr.thermohabitat.thermal.library.CountPlausibility
import fr.thermohabitat.thermal.library.MatchResult
import fr.thermohabitat.thermal.library.RadiatorLibrary
import fr.thermohabitat.thermal.library.RadiatorMatcher
import fr.thermohabitat.thermal.library.RadiatorMeasurements
import fr.thermohabitat.thermal.model.IdentificationMethod
import fr.thermohabitat.thermal.model.ValueOrigin
import fr.thermohabitat.thermal.vision.DimensionEstimate
import fr.thermohabitat.thermal.vision.GrayImage
import fr.thermohabitat.thermal.vision.Homography
import fr.thermohabitat.thermal.vision.ImagePoint
import fr.thermohabitat.thermal.vision.PhotoDimensions
import fr.thermohabitat.thermal.vision.PhotoIdentification
import fr.thermohabitat.thermal.vision.PhotoIdentifier
import fr.thermohabitat.thermal.vision.RadiatorDimension
import fr.thermohabitat.thermal.vision.ScaleObject
import fr.thermohabitat.thermal.vision.ScaleObjectShape
import fr.thermohabitat.thermal.vision.ScaleReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToInt

// =====================================================================================
// Identification géométrique sur photo : contour, échelle, comptage, rapprochement avec la base
// =====================================================================================

/** Grand côté (px) de la photo chargée pour l'analyse : les coins sont exprimés dans ces pixels. */
private const val ANALYSIS_SIDE_PX = 2048

/** Bornes du nombre d'éléments réglable par −/+. */
private const val MIN_COUNT = 1
private const val MAX_COUNT = 200

/** Référence d'échelle choisie à l'écran. */
enum class ScaleMode(val label: String, val hint: String) {
    OBJECT("Objet à 4 coins", "Carte bancaire, feuille A4 ou A5, billet, plaqué à plat contre la façade."),
    KNOWN_LENGTH("Longueur connue", "Pièce de monnaie (diamètre) ou mètre ruban posé contre la façade : 2 points."),
    MEASURED("Dimension mesurée", "Hauteur ou longueur du corps relevée au mètre."),
    NONE("Sans référence", "Nombre d'éléments seul, ou dimensions ESTIMÉES d'après un pas standard supposé."),
}

/** Longueur pointée par 2 points : diamètre d'une pièce, ou longueur lue au mètre ruban. */
enum class LengthReference(val coin: ScaleObject?) {
    TAPE(null),
    COIN_1_EURO(ScaleObject.COIN_1_EURO),
    COIN_2_EURO(ScaleObject.COIN_2_EURO),
    COIN_50_CENTS(ScaleObject.COIN_50_CENTS);

    val label: String get() = coin?.let { "${it.label} (Ø ${mmText(it.longMm)} mm)" } ?: "Mètre ruban ou cote connue"
}

/** Pas standard supposé sans référence d'échelle : aucun (nombre seul), ou plage d'une catégorie de la bibliothèque. */
class PitchChoice(val label: String, val pitchMm: Double?, val uncertaintyMm: Double)

/** Objets rectangulaires proposés comme référence à 4 coins. */
private val PLANAR_OBJECTS: List<ScaleObject> = ScaleObject.entries.filter { it.shape == ScaleObjectShape.RECTANGLE }

/**
 * Pas standard : milieu de la plage documentée de la catégorie, incertitude d'une demi-plage (au moins 6 %) ;
 * c'est une hypothèse, signalée comme telle (dimensions ESTIMÉES).
 */
private val PITCH_CHOICES: List<PitchChoice> = listOf(PitchChoice("Aucun : nombre d'éléments seulement", null, 0.0)) +
    RadiatorLibrary.categories.mapNotNull { c ->
        c.pitchRangeMm?.let { r ->
            val mid = (r.start + r.endInclusive) / 2
            val range = if (r.start == r.endInclusive) "≈ ${mmText(mid)} mm" else "${mmText(r.start)} à ${mmText(r.endInclusive)} mm"
            PitchChoice("${c.title} (pas $range)", mid, maxOf((r.endInclusive - r.start) / 2, 0.06 * mid))
        }
    }

/** Millimètres : entier, sinon 1 ou 2 décimales (85,6 ; 53,98). */
private fun mmText(v: Double): String = when {
    abs(v - v.roundToInt()) < 0.005 -> Fmt.num(v, 0)
    abs(v * 10 - (v * 10).roundToInt()) < 0.05 -> Fmt.num(v, 1)
    else -> Fmt.num(v, 2)
}

/** « 60,5 ± 5,5 mm » pour un pas (moins de 100 mm), « 1 146 ± 105 mm » au-delà. */
private fun pm(d: DimensionEstimate?): String {
    if (d == null || !d.valueMm.isFinite()) return "–"
    val u = d.uncertaintyMm
    val decimals = if (d.valueMm < 100.0) 1 else 0
    return if (u.isFinite() && u > 0) "${Fmt.num(d.valueMm, decimals)} ± ${Fmt.num(u, decimals)} mm" else "${Fmt.num(d.valueMm, decimals)} mm"
}

private fun ImagePoint.toOffset() = Offset(x.toFloat(), y.toFloat())
private fun Offset.toImagePoint() = ImagePoint(x.toDouble(), y.toDouble())

/** Points déplaçables au doigt (px de l'image), bornés à l'image ; [initial] sert à « Réinitialiser ». */
class PointHandles(private val width: Int, private val height: Int, private val initial: List<Offset>) {
    var points by mutableStateOf(initial)
        private set

    val imagePoints: List<ImagePoint> get() = points.map { it.toImagePoint() }

    fun move(index: Int, delta: Offset) {
        points = points.mapIndexed { i, p -> if (i == index) clamp(p + delta) else p }
    }

    /** Déplace tous les points en gardant l'ensemble dans l'image. */
    fun moveAll(delta: Offset) {
        val dx = delta.x.coerceIn(-points.minOf { it.x }, width - points.maxOf { it.x })
        val dy = delta.y.coerceIn(-points.minOf { it.y }, height - points.maxOf { it.y })
        points = points.map { Offset(it.x + dx, it.y + dy) }
    }

    fun reset() { points = initial }

    private fun clamp(p: Offset) = Offset(p.x.coerceIn(0f, width.toFloat()), p.y.coerceIn(0f, height.toFloat()))
}

/** Point saisi pendant un glissé ([index] = −1 : objet entier déplacé). */
class ActiveHandle(val handles: PointHandles, val index: Int)

/** Photo chargée et poignées : contour du radiateur, objet de référence à 4 coins, longueur à 2 points. */
class IdentifyWorkspace(val bitmap: Bitmap) {
    private val w = bitmap.width.toFloat()
    private val h = bitmap.height.toFloat()
    val radiator = PointHandles(bitmap.width, bitmap.height,
        listOf(Offset(0.15f * w, 0.25f * h), Offset(0.85f * w, 0.25f * h), Offset(0.85f * w, 0.75f * h), Offset(0.15f * w, 0.75f * h)))
    // Objet de référence : petit rectangle au format carte bancaire, au centre de l'image.
    private val objW = 0.12f * w
    private val objH = objW / 1.586f
    val referenceObject = PointHandles(bitmap.width, bitmap.height, listOf(
        Offset(0.5f * w - objW / 2, 0.5f * h - objH / 2), Offset(0.5f * w + objW / 2, 0.5f * h - objH / 2),
        Offset(0.5f * w + objW / 2, 0.5f * h + objH / 2), Offset(0.5f * w - objW / 2, 0.5f * h + objH / 2),
    ))
    val referenceLength = PointHandles(bitmap.width, bitmap.height, listOf(Offset(0.4f * w, 0.5f * h), Offset(0.6f * w, 0.5f * h)))
}

/** Entrées d'une analyse : sert aussi à repérer un résultat périmé (coins ou échelle modifiés depuis). */
data class AnalysisInput(val corners: List<ImagePoint>, val reference: ScaleReference?)

/**
 * Aperçu couleur de la façade redressée (marges comprises) ; [toPreview] convertit un point de la face nominale
 * (px de [PhotoIdentification]) en px de l'aperçu.
 */
class FacePreview(val bitmap: Bitmap, private val marginX: Double, private val marginY: Double, private val scale: Double) {
    fun toPreview(x: Double, y: Double) = Offset(((x + marginX) * scale).toFloat(), ((y + marginY) * scale).toFloat())
    fun toPreview(p: ImagePoint) = toPreview(p.x, p.y)

    companion object {
        private const val MAX_SIDE = 1400.0

        /**
         * Redressement par [android.graphics.Matrix.setPolyToPoly] (4 points : homographie identique à celle de
         * l'analyse, coins ordonnés haut-gauche, haut-droit, bas-droit, bas-gauche). Null si les coins sont dégénérés.
         */
        fun render(source: Bitmap, id: PhotoIdentification): FacePreview? {
            val w = id.faceWidth.toDouble(); val h = id.faceHeight.toDouble()
            val mx = 0.06 * w; val my = 0.10 * h
            val s = minOf(1.0, MAX_SIDE / maxOf(w + 2 * mx, h + 2 * my))
            val c = id.corners
            if (c.size != 4) return null
            val src = floatArrayOf(
                c[0].x.toFloat(), c[0].y.toFloat(), c[1].x.toFloat(), c[1].y.toFloat(),
                c[2].x.toFloat(), c[2].y.toFloat(), c[3].x.toFloat(), c[3].y.toFloat(),
            )
            val x0 = (mx * s).toFloat(); val y0 = (my * s).toFloat()
            val x1 = ((mx + w) * s).toFloat(); val y1 = ((my + h) * s).toFloat()
            val dst = floatArrayOf(x0, y0, x1, y0, x1, y1, x0, y1)
            val matrix = android.graphics.Matrix()
            if (!matrix.setPolyToPoly(src, 0, dst, 0, 4)) return null
            val out = Bitmap.createBitmap(((w + 2 * mx) * s).roundToInt().coerceAtLeast(1), ((h + 2 * my) * s).roundToInt().coerceAtLeast(1),
                Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(out).apply {
                drawColor(android.graphics.Color.DKGRAY) // hors de la photo
                drawBitmap(source, matrix, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
            }
            return FacePreview(out, mx, my, s)
        }
    }
}

/** Résultat d'une analyse avec ses entrées et l'aperçu redressé. */
class PhotoAnalysis(val input: AnalysisInput, val identification: PhotoIdentification, val preview: FacePreview?)

/** Propositions tirées d'une analyse pour le nombre d'éléments retenu, toutes à valider par l'utilisateur. */
class IdentifyProposal(
    val count: Int?,
    val dimensions: PhotoDimensions,
    val match: MatchResult,
    /** Plausibilité du nombre proposé et des alternatives au vu de la base (vide sans mesure exploitable). */
    val counts: List<CountPlausibility>,
    val chosen: CategoryMatch?,
    /** Confiance de l'analyse ; null si l'utilisateur a changé le nombre (elle ne s'applique plus). */
    val confidence: Double?,
) {
    /** Faux si la catégorie retenue ne compte pas d'éléments (cannelures d'un panneau, décoratif) : le nombre n'est pas reporté. */
    val writesSections: Boolean get() = count != null && chosen?.countsElements != false

    val hasDimensions: Boolean
        get() = dimensions.origin != ValueOrigin.UNKNOWN && (dimensions.heightMm != null || dimensions.lengthMm != null)

    companion object {
        fun of(a: PhotoAnalysis, count: Int?, depthMm: Double?, columns: Int?, categoryCode: String?): IdentifyProposal {
            val id = a.identification
            val dims = if (count != null) id.dimensionsFor(count) else PhotoDimensions(null, id.heightMm, id.lengthMm, id.dimensionsOrigin)
            val depth = depthMm?.takeIf { it > 0 }?.let { DimensionEstimate(it, RadiatorLibrary.TAPE_UNCERTAINTY_MM) }
            val match = RadiatorMatcher.matchPhoto(id, count, depth, columns)
            val candidates = (listOfNotNull(id.count) + id.alternatives.map { it.count } + listOfNotNull(count)).distinct()
            val counts = if (match.informative && candidates.size > 1) {
                RadiatorMatcher.compareCounts(candidates) { RadiatorMeasurements.fromPhoto(id, it, depth, columns) }
            } else emptyList()
            return IdentifyProposal(
                count, dims, match, counts, categoryCode?.let { match.find(it) },
                if (count != null && count == id.count) id.confidence else null,
            )
        }
    }
}

/**
 * Fiche complétée par l'analyse : nombre d'éléments, hauteur et longueur (si [replaceDimensions]), profondeur et colonnes
 * saisies, catégorie choisie (ses colonnes l'emportent). La fiche repasse « à valider ».
 */
fun RadiatorEntity.withPhotoProposal(
    p: IdentifyProposal,
    replaceDimensions: Boolean,
    typedDepthMm: Double?,
    typedColumns: Int? = null,
): RadiatorEntity {
    val d = p.dimensions
    val withDims = replaceDimensions && p.hasDimensions
    var x = copy(
        sections = if (p.writesSections) p.count else sections,
        heightMm = if (withDims) d.heightMm?.let { Math.round(it.valueMm).toDouble() } ?: heightMm else heightMm,
        lengthMm = if (withDims) d.lengthMm?.let { Math.round(it.valueMm).toDouble() } ?: lengthMm else lengthMm,
        depthMm = typedDepthMm?.takeIf { it > 0 }?.let { Math.round(it).toDouble() } ?: depthMm,
        columns = typedColumns?.takeIf { it > 0 } ?: columns,
        dimensionsOrigin = if (withDims) d.origin else dimensionsOrigin,
        identificationMethod = IdentificationMethod.PHOTO_GEOMETRY,
        identificationConfidence = p.confidence,
        validated = false,
        validatedAt = null,
        updatedAt = System.currentTimeMillis(),
    )
    p.chosen?.let { m ->
        val c = m.category
        x = x.copy(type = c.type, material = c.type.defaultMaterial, era = c.era, categoryCode = c.code,
            columns = m.suggestedColumns ?: c.columns ?: x.columns, panelType = c.panelType ?: x.panelType)
    }
    return x
}

@HiltViewModel
class PhotoIdentifyViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: ProjectRepository,
    private val storage: PhotoStorage,
) : ViewModel() {
    private val route = savedStateHandle.toRoute<PhotoIdentifyRoute>()
    val workspace = MutableStateFlow<IdentifyWorkspace?>(null)
    val failed = MutableStateFlow(false)
    val radiator = MutableStateFlow<RadiatorEntity?>(null)
    val analysis = MutableStateFlow<PhotoAnalysis?>(null)
    /** Analyse en cours : indicateur visible et bouton désactivé (pas de relance multiple). */
    val analyzing = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    // Réglages de l'écran, gardés par le ViewModel (rotation de l'écran sans perte des coins placés).
    var mode by mutableStateOf(ScaleMode.NONE)
    var planarObject by mutableStateOf(ScaleObject.BANK_CARD)
    var lengthReference by mutableStateOf(LengthReference.TAPE)
    var tapeLengthMm by mutableStateOf<Double?>(null)
    var measuredDimension by mutableStateOf(RadiatorDimension.HEIGHT)
    var measuredValueMm by mutableStateOf<Double?>(null)
    var pitchChoice by mutableStateOf(PITCH_CHOICES.first())
    /** Nombre d'éléments retenu : proposition, alternative ou correction −/+. */
    var count by mutableStateOf<Int?>(null)
    var depthMm by mutableStateOf<Double?>(null)
    /** Colonnes vues de dessus (reprises de la fiche), pour départager les catégories. */
    var columns by mutableStateOf<Int?>(null)
    var categoryCode by mutableStateOf<String?>(null)
    var replaceDimensions by mutableStateOf(true)
    var active by mutableStateOf<ActiveHandle?>(null)
        private set

    /** Luminance de la photo, calculée une fois (hors fil principal). */
    @Volatile private var gray: GrayImage? = null
    /** Évite un double report (double appui) qui fermerait aussi la fiche radiateur. */
    private var applying = false

    init {
        viewModelScope.launch {
            val r = repository.getRadiator(route.radiatorId)
            radiator.value = r
            r?.let { prefill(it) }
            val bmp = repository.getPhoto(route.photoId)?.let { storage.loadBitmap(it.fileName, ANALYSIS_SIDE_PX) }
            if (bmp == null) failed.value = true else workspace.value = IdentifyWorkspace(bmp)
        }
    }

    /** Profondeur de la fiche ; dimension relevée au mètre proposée comme échelle (pas celle issue d'une photo). */
    private fun prefill(r: RadiatorEntity) {
        depthMm = r.depthMm
        columns = r.columns?.takeIf { it > 0 }
        if (r.dimensionsOrigin != ValueOrigin.MEASURED || r.identificationMethod == IdentificationMethod.PHOTO_GEOMETRY) return
        val h = r.heightMm?.takeIf { it > 0 }
        val l = r.lengthMm?.takeIf { it > 0 }
        if (h != null) {
            mode = ScaleMode.MEASURED; measuredDimension = RadiatorDimension.HEIGHT; measuredValueMm = h
        } else if (l != null) {
            mode = ScaleMode.MEASURED; measuredDimension = RadiatorDimension.LENGTH; measuredValueMm = l
        }
    }

    private fun editableHandles(ws: IdentifyWorkspace): List<PointHandles> = listOfNotNull(
        ws.referenceObject.takeIf { mode == ScaleMode.OBJECT },
        ws.referenceLength.takeIf { mode == ScaleMode.KNOWN_LENGTH },
        ws.radiator,
    )

    /** Saisit le point le plus proche (tolérance ≥ 2 × 24 dp) ; à défaut, l'objet de référence entier si on le touche. */
    fun startDrag(p: Offset, minTolerance: Float) {
        val ws = workspace.value ?: return
        val tolerance = maxOf(2f * minTolerance, 0.03f * maxOf(ws.bitmap.width, ws.bitmap.height))
        var best: ActiveHandle? = null
        var bestDistance = Float.MAX_VALUE
        for (h in editableHandles(ws)) {
            h.points.forEachIndexed { i, q ->
                val d = (q - p).getDistance()
                if (d < bestDistance) { bestDistance = d; best = ActiveHandle(h, i) }
            }
        }
        active = when {
            bestDistance <= tolerance -> best
            mode == ScaleMode.OBJECT && insideQuad(ws.referenceObject.points, p) -> ActiveHandle(ws.referenceObject, -1)
            else -> null
        }
    }

    fun drag(delta: Offset) {
        val a = active ?: return
        if (a.index < 0) a.handles.moveAll(delta) else a.handles.move(a.index, delta)
    }

    fun endDrag() { active = null }

    fun resetHandles() {
        val ws = workspace.value ?: return
        ws.radiator.reset(); ws.referenceObject.reset(); ws.referenceLength.reset()
        active = null
    }

    /** Message si les 4 coins ne forment pas un quadrilatère convexe (coins croisés ou alignés). */
    fun contourIssue(): String? {
        val ws = workspace.value ?: return null
        val q = Homography.orderCorners(ws.radiator.imagePoints)
        return if (Homography.isConvex(q)) null else "Contour invalide : les 4 coins doivent former un quadrilatère (coins croisés ou alignés)."
    }

    /** Entrées de l'analyse d'après l'écran ; échec avec un message en français si une donnée manque. */
    fun currentInput(): Result<AnalysisInput> = runCatching {
        val ws = workspace.value ?: throw IllegalStateException("Photo non chargée.")
        val reference: ScaleReference? = when (mode) {
            ScaleMode.OBJECT -> ScaleReference.PlanarObject(planarObject, ws.referenceObject.imagePoints)
            ScaleMode.KNOWN_LENGTH -> {
                val (a, b) = ws.referenceLength.imagePoints
                val coin = lengthReference.coin
                if (coin != null) ScaleReference.coin(coin, a, b)
                else ScaleReference.tapeMeasure(a, b, requireNotNull(tapeLengthMm) { "Indiquez la longueur lue entre les deux points." })
            }
            ScaleMode.MEASURED -> ScaleReference.MeasuredDimension(measuredDimension,
                requireNotNull(measuredValueMm) { "Indiquez la ${measuredDimension.label.lowercase()} mesurée au mètre." })
            ScaleMode.NONE -> pitchChoice.pitchMm?.let { ScaleReference.StandardPitch(it, pitchChoice.uncertaintyMm) }
        }
        AnalysisInput(ws.radiator.imagePoints, reference)
    }

    /** Analyse complète hors du fil principal (quelques centaines de millisecondes à quelques secondes). */
    fun analyze() {
        val ws = workspace.value ?: return
        if (analyzing.value) return
        val input = currentInput().getOrElse { error.value = it.message ?: "Données incomplètes."; return }
        analyzing.value = true
        error.value = null
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.Default) {
                runCatching {
                    val g = gray ?: ws.bitmap.toGrayImage().also { gray = it }
                    val id = PhotoIdentifier.identify(g, input.corners, input.reference)
                    PhotoAnalysis(input, id, FacePreview.render(ws.bitmap, id))
                }
            }
            // L'aperçu précédent n'est pas recyclé : il peut encore être affiché jusqu'à la recomposition.
            outcome.onSuccess { a ->
                analysis.value = a
                count = a.identification.count ?: count
                replaceDimensions = defaultReplace(radiator.value)
            }.onFailure { e -> error.value = analysisErrorMessage(e) }
            analyzing.value = false
        }
    }

    /**
     * Hauteur et longueur remplacées par défaut seulement si la fiche n'en a pas de plus sûres (relevées ou documentées).
     * La méthode PHOTO_GEOMETRY ne suffit pas : un report précédent a pu laisser en place des cotes relevées au mètre.
     */
    private fun defaultReplace(r: RadiatorEntity?): Boolean {
        if (r == null || (r.heightMm == null && r.lengthMm == null)) return true
        return r.dimensionsOrigin == ValueOrigin.UNKNOWN || r.dimensionsOrigin == ValueOrigin.ESTIMATED
    }

    private fun analysisErrorMessage(e: Throwable): String = when (e) {
        is IllegalArgumentException -> e.message ?: "Contour ou référence invalide."
        is OutOfMemoryError -> "Mémoire insuffisante pour analyser cette photo : fermez d'autres applications puis réessayez."
        else -> "Analyse impossible (erreur inattendue). Replacez les coins et réessayez."
    }

    /** Reporte les propositions retenues sur la fiche, qui repasse « à valider ». */
    fun applyToRadiator(onDone: () -> Unit) {
        val a = analysis.value ?: return
        if (applying) return
        applying = true
        viewModelScope.launch {
            val r = repository.getRadiator(route.radiatorId)
            if (r == null) { applying = false; return@launch }
            val p = IdentifyProposal.of(a, count, depthMm, columns, categoryCode)
            repository.updateRadiator(r.withPhotoProposal(p, replaceDimensions, depthMm, columns))
            onDone() // applying reste vrai : l'écran se ferme
        }
    }

    private fun insideQuad(points: List<Offset>, p: Offset): Boolean {
        if (points.size != 4) return false
        val q = Homography.orderCorners(points.map { it.toImagePoint() })
        var sign = 0
        for (i in 0 until 4) {
            val a = q[i]; val b = q[(i + 1) % 4]
            val z = (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)
            val s = if (z > 0) 1 else if (z < 0) -1 else 0
            if (s != 0) {
                if (sign == 0) sign = s else if (s != sign) return false
            }
        }
        return true
    }
}

// --- Écran ---

// Couleurs fixes (pas celles du thème, variables avec les couleurs dynamiques) : la légende reste exacte.
private val CONTOUR_COLOR = Color(0xFFFF7043)
private val REFERENCE_COLOR = Color(0xFF00E5FF)
private val BOUNDARY_COLOR = Color(0xFFFFC107)
private val BAND_COLOR = Color(0xFF29B6F6)
private const val LOUPE_ZOOM = 3f

@Composable
private fun IdentifyPhotoLoading(failed: Boolean, onBack: () -> Unit) {
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhotoIdentifyScreen(nav: NavController, vm: PhotoIdentifyViewModel = hiltViewModel()) {
    val workspace by vm.workspace.collectAsStateWithLifecycle()
    val failed by vm.failed.collectAsStateWithLifecycle()
    val radiator by vm.radiator.collectAsStateWithLifecycle()
    val analysis by vm.analysis.collectAsStateWithLifecycle()
    val analyzing by vm.analyzing.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    // Le résultat s'affiche sous l'image : il est amené à l'écran dès qu'il est prêt.
    val resultBring = remember { BringIntoViewRequester() }
    LaunchedEffect(analysis) { if (analysis != null) { withFrameNanos { }; resultBring.bringIntoView() } }
    // Gestes : lambdas stables (le détecteur de glissé n'est pas relancé pendant un geste).
    val onDragStart = remember(vm) { { p: Offset, tolerance: Float -> vm.startDrag(p, tolerance) } }
    val onDrag = remember(vm) { { _: Offset, delta: Offset -> vm.drag(delta) } }
    val onDragEnd = remember(vm) { { vm.endDrag() } }
    ScreenScaffold(title = "Identifier sur la photo", onBack = { nav.popBackStack() }) { padding ->
        ScrollColumn(padding) {
            val ws = workspace
            if (ws == null) { IdentifyPhotoLoading(failed) { nav.popBackStack() }; return@ScrollColumn }
            val a = analysis
            // Résultat périmé : coins ou échelle modifiés depuis l'analyse (recalculé seulement quand l'état bascule).
            val stale by remember(a) { derivedStateOf { a != null && vm.currentInput().getOrNull() != a.input } }
            val contourIssue by remember(ws) { derivedStateOf { vm.contourIssue() } }
            val n = vm.count
            val boundaries = remember(a, n) { if (a != null && n != null) a.identification.boundariesFor(n) else null }
            val photoLines = remember(a, boundaries) { boundaries?.let { a?.identification?.boundaryLinesInImage(it) } }

            InfoBanner("Placez les 4 coins du corps du radiateur (sans pieds). La photo peut être prise de biais. Un coin caché " +
                "(meuble, rideau) peut être placé approximativement : l'analyse le corrige à partir des joints.")
            val image = remember(ws.bitmap) { ws.bitmap.asImageBitmap() }
            val accent = CONTOUR_COLOR
            ImageWorkArea(ws.bitmap, onDragStart = onDragStart, onDrag = onDrag, onDragEnd = onDragEnd) { frame ->
                val radiatorPts = ws.radiator.points
                val refShape: List<Offset>? = when (vm.mode) {
                    ScaleMode.OBJECT -> ws.referenceObject.points
                    ScaleMode.KNOWN_LENGTH -> ws.referenceLength.points
                    else -> null
                }
                // Limites reprojetées sur la photo (masquées si le résultat ne correspond plus au contour).
                if (!stale) photoLines?.let { l -> drawBoundaries(l.map { (top, bottom) -> frame.toScreen(top.toOffset()) to frame.toScreen(bottom.toOffset()) }) }
                drawHandles(frame, radiatorPts, accent)
                refShape?.let { drawHandles(frame, it, REFERENCE_COLOR) }
                vm.active?.let { h ->
                    if (h.index >= 0) {
                        drawLoupe(frame, image, h.handles.points[h.index],
                            listOfNotNull(radiatorPts to accent, refShape?.let { it to REFERENCE_COLOR }))
                    }
                }
            }
            Text("Contour du radiateur : orange ; référence d'échelle : cyan ; limites d'éléments : jaune. Une loupe s'affiche " +
                "pendant le glissé.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            contourIssue?.let { InfoBanner(it, kind = BannerKind.WARNING) }
            OutlinedButton(onClick = { vm.resetHandles() }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Refresh, null); Spacer(Modifier.width(8.dp)); Text("Réinitialiser les coins")
            }

            ScaleSection(vm)

            Button(enabled = !analyzing, onClick = { vm.analyze() }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                if (analyzing) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = LocalContentColor.current, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(when { analyzing -> "Analyse en cours…"; a == null -> "Analyser"; else -> "Relancer l'analyse" })
            }
            error?.let { InfoBanner(it, kind = BannerKind.WARNING) }
            if (a != null) {
                if (stale) InfoBanner("Contour ou échelle modifiés depuis l'analyse : relancez-la pour mettre le résultat à jour.", kind = BannerKind.WARNING)
                IdentifyResult(vm, a, radiator, boundaries, stale, Modifier.bringIntoViewRequester(resultBring),
                    onApply = { vm.applyToRadiator { nav.popBackStack() } }, onCancel = { nav.popBackStack() })
            }
        }
    }
}

/** Choix de la référence d'échelle et saisies associées. */
@Composable
private fun ScaleSection(vm: PhotoIdentifyViewModel) {
    SectionCard("Échelle", subtitle = "Elle permet de mesurer le pas des éléments, la hauteur et la longueur.") {
        ChoiceChips(ScaleMode.entries, vm.mode, { it.label }, { vm.mode = it })
        Text(vm.mode.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        when (vm.mode) {
            ScaleMode.OBJECT -> {
                DropdownField("Objet de référence", PLANAR_OBJECTS, vm.planarObject,
                    { "${it.label} (${mmText(it.longMm)} × ${mmText(it.shortMm)} mm)" }, { vm.planarObject = it })
                Text("Faites glisser les 4 coins cyan sur les coins de l'objet (touchez son centre pour le déplacer en entier). " +
                    "Il doit être à plat contre la façade du radiateur, pas sur le mur derrière.", style = MaterialTheme.typography.bodySmall)
            }
            ScaleMode.KNOWN_LENGTH -> {
                DropdownField("Longueur de référence", LengthReference.entries, vm.lengthReference, { it.label }, { vm.lengthReference = it })
                if (vm.lengthReference.coin == null) {
                    NumberField("Longueur entre les 2 points", vm.tapeLengthMm, { vm.tapeLengthMm = it }, unit = "mm", decimals = 0,
                        min = 10.0, max = 5000.0, supportingText = "Lue sur le mètre posé contre la façade")
                }
                Text("Placez les 2 points cyan aux extrémités de la longueur connue (diamètre de la pièce, graduations du mètre).",
                    style = MaterialTheme.typography.bodySmall)
            }
            ScaleMode.MEASURED -> {
                ChoiceChips(RadiatorDimension.entries, vm.measuredDimension, { it.label }, { vm.measuredDimension = it })
                NumberField("${vm.measuredDimension.label} mesurée", vm.measuredValueMm, { vm.measuredValueMm = it }, unit = "mm", decimals = 0,
                    min = 50.0, max = 6000.0, supportingText = "Corps du radiateur, sans pieds ni raccords")
            }
            ScaleMode.NONE -> {
                DropdownField("Pas standard supposé", PITCH_CHOICES, vm.pitchChoice, { it.label }, { vm.pitchChoice = it },
                    supportingText = "Facultatif : type présumé du radiateur")
                InfoBanner(
                    if (vm.pitchChoice.pitchMm == null) "Sans référence, seul le nombre d'éléments est proposé : aucune dimension n'est mesurée."
                    else "Dimensions ESTIMÉES à partir d'un pas supposé : elles devront être confirmées au mètre.",
                    kind = BannerKind.WARNING,
                )
            }
        }
    }
}

/** Résultat : aperçu redressé, nombre réglable, dimensions, types proposés, puissance, report sur la fiche. */
@Composable
private fun IdentifyResult(
    vm: PhotoIdentifyViewModel,
    a: PhotoAnalysis,
    radiator: RadiatorEntity?,
    boundaries: List<Double>?,
    stale: Boolean,
    modifier: Modifier,
    onApply: () -> Unit,
    onCancel: () -> Unit,
) {
    val id = a.identification
    val n = vm.count
    val depth = vm.depthMm
    val columns = vm.columns
    val proposal = remember(a, n, depth, columns, vm.categoryCode) { IdentifyProposal.of(a, n, depth, columns, vm.categoryCode) }
    val dims = proposal.dimensions
    val replace = vm.replaceDimensions
    val updated = remember(proposal, radiator, replace, depth, columns) { radiator?.withPhotoProposal(proposal, replace, depth, columns) }
    val power: PowerEstimate? = remember(updated) { updated?.let { RadiatorEstimator.estimate(it.toModel()) } }

    // --- Nombre d'éléments ---
    SectionCard("Nombre d'éléments", modifier) {
        a.preview?.let { preview ->
            Text("Aperçu redressé", style = MaterialTheme.typography.labelLarge)
            ImageWorkArea(preview.bitmap) { frame -> drawFaceOverlay(frame, preview, id, if (stale) null else boundaries) }
            Text("Traits jaunes : limites d'éléments, à faire coïncider avec les joints (boutons − / +). Bandes bleues : rangées " +
                "analysées ; les zones masquées par un objet sont écartées.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("Nombre retenu", style = MaterialTheme.typography.labelLarge)
        CountStepper(n) { vm.count = it }
        if (id.count == null) {
            IntField("Nombre compté sur l'aperçu", n, { v -> if (v != null && v >= MIN_COUNT) vm.count = v }, min = MIN_COUNT, max = MAX_COUNT,
                supportingText = "Comptez les éléments sur l'aperçu redressé")
        }
        ValueRow("Proposition de l'analyse", id.count?.let { "$it éléments" + if (id.plusOrMinusOne) " (± 1)" else "" } ?: "Non déterminé",
            emphasize = true)
        ValueRow("Confiance du comptage", Fmt.pct(id.countConfidence))
        val candidates = remember(id) { (listOfNotNull(id.count) + id.alternatives.map { it.count }).distinct() }
        if (candidates.size > 1) {
            Text("Nombres possibles", style = MaterialTheme.typography.labelLarge)
            ChoiceChips(candidates, n, { c -> if (c == id.count) "$c (proposé)" else "$c" }, { vm.count = it })
            id.alternatives.forEach { alt -> Text("${alt.count} : ${alt.reason}", style = MaterialTheme.typography.bodySmall) }
        }
        if (proposal.counts.size > 1) {
            Text("Comparaison avec la base", style = MaterialTheme.typography.labelLarge)
            proposal.counts.forEach { Text(it.text, style = MaterialTheme.typography.bodySmall) }
        }
        Text(id.counting.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        id.counting.warnings.forEach { InfoBanner(it, kind = BannerKind.WARNING) }
    }

    // --- Dimensions ---
    // Avertissements d'échelle et de prise de vue (ceux du comptage sont dans la carte précédente).
    val scaleWarnings = id.warnings.filter { it !in id.counting.warnings }
    SectionCard("Dimensions", subtitle = "Valeurs à ± 1 écart-type, pour le nombre retenu.") {
        if (dims.origin == ValueOrigin.UNKNOWN) {
            if (scaleWarnings.isEmpty()) {
                Text("Aucune échelle : le pas, la hauteur et la longueur ne sont pas mesurés. Choisissez une référence ci-dessus puis " +
                    "relancez l'analyse.", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            val badge: @Composable () -> Unit = { OriginBadge(dims.origin) }
            dims.pitchMm?.let { ValueRow("Pas d'élément", pm(it), trailing = badge) }
            dims.heightMm?.let { ValueRow("Hauteur", pm(it), trailing = badge) }
            dims.lengthMm?.let { ValueRow("Longueur", pm(it), trailing = badge) }
        }
        scaleWarnings.forEach { InfoBanner(it, kind = BannerKind.WARNING) }
        val slender = if (n != null && id.bodyAspectRatio > 0 && id.bodyAspectRatio.isFinite()) n / id.bodyAspectRatio else null
        slender?.let {
            ValueRow("Proportions d'un élément", "≈ ${Fmt.num(it, if (it < 10) 1 else 0)} fois plus haut que large" +
                if (id.aspectMeasured) "" else " (estimé)")
        }
        ValueRow("Prise de vue", if (id.aspect.frontal) "Quasi de face" else "≈ ${id.aspect.viewAngleDeg.roundToInt()}° de biais")
        ValueRow("Indice de confiance de l'analyse", Fmt.pct(id.confidence))
        // Redressement et échelle (le message du comptage, et celui d'un nombre non déterminé, sont dans la carte précédente).
        id.messages.filter { it != id.counting.message && (id.count != null || !it.startsWith("Nombre d'éléments")) }
            .forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }

    // --- Types proposés par la base ---
    TypeSection(vm, proposal)

    // --- Puissance ---
    SectionCard("Puissance estimée", subtitle = "Ordre de grandeur à ΔT 50 K (EN 442), d'après les dimensions et le type.") {
        if (power != null) {
            ValueRow("Puissance estimée", "${Fmt.watts(power.nominalW)} (${Fmt.watts(power.lowW)} – ${Fmt.watts(power.highW)})", emphasize = true)
            Text(power.method, style = MaterialTheme.typography.bodySmall)
            if (updated?.dimensionsOrigin == ValueOrigin.ESTIMATED) {
                InfoBanner("Calculée avec des dimensions estimées : simple ordre de grandeur.", kind = BannerKind.WARNING)
            }
        } else {
            Text("Non estimable pour l'instant : il faut un type à éléments, le nombre d'éléments, la hauteur (échelle) et la profondeur " +
                "ou le nombre de colonnes.", style = MaterialTheme.typography.bodyMedium)
        }
        proposal.chosen?.power?.let { Text(it.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (radiator?.nominalPowerW != null) {
            Text("La puissance documentée de la fiche reste prioritaire sur cette estimation.", style = MaterialTheme.typography.bodySmall)
        }
    }

    // --- Report sur la fiche ---
    SectionCard("Reporter sur la fiche", icon = Icons.Filled.Check) {
        if (proposal.hasDimensions) {
            val current = radiator?.takeIf { it.heightMm != null || it.lengthMm != null }
                ?.let { "Fiche actuelle : H ${Fmt.mm(it.heightMm)} × L ${Fmt.mm(it.lengthMm)} (${it.dimensionsOrigin.label.lowercase()})" }
            SwitchRow("Remplacer la hauteur et la longueur", replace, { vm.replaceDimensions = it }, description = current)
        }
        // Ce qui sera écrit sur la fiche (comparé à son état actuel).
        val lines = buildList {
            if (proposal.writesSections) add("Nombre d'éléments : ${proposal.count}")
            if (replace && proposal.hasDimensions) {
                add("Hauteur ${Fmt.mm(updated?.heightMm)}, longueur ${Fmt.mm(updated?.lengthMm)} (${dims.origin.label.lowercase()})")
            }
            if (updated != null && updated.depthMm != radiator?.depthMm) add("Profondeur : ${Fmt.mm(updated.depthMm)}")
            val chosen = proposal.chosen
            if (chosen != null) add("Type : ${chosen.category.title}")
            if (updated?.columns != null && updated.columns != radiator?.columns) add("Colonnes : ${updated.columns}")
            add("Méthode : ${IdentificationMethod.PHOTO_GEOMETRY.label}")
        }
        lines.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
        if (proposal.chosen?.countsElements == false) {
            InfoBanner("Catégorie sans éléments à compter (panneau à cannelures, décoratif) : le nombre compté n'est pas reporté sur la fiche.",
                kind = BannerKind.WARNING)
        }
        // Le report remet la fiche « à valider » (hors étude) : l'utilisateur en est averti avant d'appuyer.
        Text("La fiche repassera « à valider » : vérifiez les valeurs puis validez-la pour l'inclure dans l'étude.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val canApply = !stale && radiator != null && (proposal.writesSections || (replace && proposal.hasDimensions) || proposal.chosen != null)
        Button(enabled = canApply, onClick = onApply, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Reporter sur la fiche") }
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Annuler") }
    }
}

/** Catégories de la bibliothèque compatibles avec les mesures, au choix de l'utilisateur. */
@Composable
private fun TypeSection(vm: PhotoIdentifyViewModel, proposal: IdentifyProposal) {
    val match = proposal.match
    SectionCard("Type proposé par la base", subtitle = "Rapprochement des mesures avec la bibliothèque : une proposition, à vous de choisir.") {
        // Mesures complémentaires (reprises de la fiche) : elles départagent les catégories et sont reportées.
        FieldRow {
            NumberField("Profondeur", vm.depthMm, { vm.depthMm = it }, unit = "mm", decimals = 0, min = 10.0, max = 500.0,
                supportingText = "Du mur à l'avant de l'élément")
            IntField("Colonnes", vm.columns, { vm.columns = it }, min = 1, max = 10, supportingText = "Tubes vus de dessus")
        }
        match.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (!match.informative) {
            Text("Rien à comparer : choisissez le type avec la bibliothèque illustrée depuis la fiche.", style = MaterialTheme.typography.bodyMedium)
            return@SectionCard
        }
        val options = match.leading.take(6)
        CategoryOption("Ne pas changer le type de la fiche", null, vm.categoryCode == null) { vm.categoryCode = null }
        options.forEach { m ->
            val selected = vm.categoryCode == m.category.code
            CategoryOption(m.category.title, if (selected) reasonsText(m) else m.summary, selected) { vm.categoryCode = m.category.code }
        }
        if (match.leading.size > options.size) {
            Text("… et ${match.leading.size - options.size} autre(s) catégorie(s) compatible(s) : la profondeur ou une échelle les départage.",
                style = MaterialTheme.typography.bodySmall)
        }
        // Catégorie retenue sortie du groupe de tête (mesures modifiées depuis) : toujours affichée.
        proposal.chosen?.takeIf { c -> options.none { it.category.code == c.category.code } }?.let { m ->
            CategoryOption(m.category.title, reasonsText(m), true) { vm.categoryCode = m.category.code }
        }
    }
}

/** Verdict et toutes les raisons du rapprochement (catégorie retenue). */
private fun reasonsText(m: CategoryMatch): String =
    (listOf(m.level.label) + m.reasons.map { "• ${it.text}" }).joinToString("\n")

@Composable
private fun CategoryOption(title: String, detail: String?, selected: Boolean, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = if (selected) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else CardDefaults.cardColors(),
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
            RadioButton(selected = selected, onClick = null)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

/** Nombre réglable par − / + (traits recalculés à chaque appui). */
@Composable
private fun CountStepper(value: Int?, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedIconButton(onClick = { value?.let { onChange(it - 1) } }, modifier = Modifier.size(48.dp), enabled = value != null && value > MIN_COUNT) {
            Icon(Icons.Filled.Remove, contentDescription = "Un élément de moins")
        }
        Text(value?.toString() ?: "–", Modifier.widthIn(min = 56.dp), style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        OutlinedIconButton(onClick = { onChange((value ?: (MIN_COUNT - 1)) + 1) }, modifier = Modifier.size(48.dp), enabled = (value ?: 0) < MAX_COUNT) {
            Icon(Icons.Filled.Add, contentDescription = "Un élément de plus")
        }
    }
}

// --- Dessin ---

/**
 * Traits des limites d'éléments (écran) : épaisseur adaptée à leur écartement pour laisser voir les joints quand les
 * éléments sont petits à l'écran ; liseré sombre seulement quand la place le permet.
 */
private fun DrawScope.drawBoundaries(lines: List<Pair<Offset, Offset>>) {
    if (lines.isEmpty()) return
    val gaps = lines.zipWithNext { a, b -> ((b.first + b.second) / 2f - (a.first + a.second) / 2f).getDistance() }.sorted()
    val gap = gaps.getOrNull(gaps.size / 2) ?: Float.MAX_VALUE
    val width = (0.1f * gap).coerceIn(1f, 1.5.dp.toPx())
    val halo = gap > 10.dp.toPx()
    lines.forEach { (start, end) ->
        if (halo) drawLine(Color.Black.copy(alpha = 0.45f), start, end, strokeWidth = width + 1.dp.toPx())
        drawLine(BOUNDARY_COLOR, start, end, strokeWidth = width)
    }
}

/** Quadrilatère (coins remis dans l'ordre) ou segment, avec des ronds aux points. */
private fun DrawScope.drawHandles(frame: ImageFrame, points: List<Offset>, color: Color) {
    val screen = drawOrder(points).map { frame.toScreen(it) }
    drawPolyline(screen, color, 2.dp.toPx())
    points.forEach {
        val c = frame.toScreen(it)
        // Anneau fin (le coin reste visible) et point central : position exacte du coin.
        drawCircle(color, 10.dp.toPx(), c, style = Stroke(width = 2.dp.toPx()))
        drawCircle(Color.White, 2.5.dp.toPx(), c)
    }
}

private fun DrawScope.drawPolyline(points: List<Offset>, color: Color, width: Float) {
    if (points.size < 2) return
    val closed = points.size > 2
    val count = if (closed) points.size else 1
    for (i in 0 until count) drawLine(color, points[i], points[(i + 1) % points.size], strokeWidth = width)
}

/** 4 coins dans l'ordre haut-gauche, haut-droit, bas-droit, bas-gauche (tracé sans croisement), sinon tels quels. */
private fun drawOrder(points: List<Offset>): List<Offset> =
    if (points.size == 4) Homography.orderCorners(points.map { it.toImagePoint() }).map { it.toOffset() } else points

/**
 * Loupe (×[LOUPE_ZOOM]) centrée sur le point saisi, dans un coin de la zone opposé au doigt, avec les contours et
 * une croix de visée.
 */
private fun DrawScope.drawLoupe(frame: ImageFrame, image: ImageBitmap, point: Offset, shapes: List<Pair<List<Offset>, Color>>) {
    val margin = 8.dp.toPx()
    val radius = minOf(56.dp.toPx(), size.minDimension / 2 - margin)
    if (radius <= 0f || frame.scale <= 0f) return
    val zoom = frame.scale * LOUPE_ZOOM
    val finger = frame.toScreen(point)
    val left = !(finger.x < size.width / 2 && finger.y < size.height / 2)
    val center = Offset(if (left) margin + radius else size.width - margin - radius, margin + radius)
    fun toLoupe(p: Offset) = Offset(center.x + (p.x - point.x) * zoom, center.y + (p.y - point.y) * zoom)
    // Zone source bornée à l'image ; la destination suit exactement la même correspondance.
    val half = radius / zoom
    val l = (point.x - half).toInt().coerceIn(0, image.width - 1)
    val t = (point.y - half).toInt().coerceIn(0, image.height - 1)
    val r = (point.x + half).toInt().coerceIn(l + 1, image.width)
    val b = (point.y + half).toInt().coerceIn(t + 1, image.height)
    val d0 = toLoupe(Offset(l.toFloat(), t.toFloat()))
    val d1 = toLoupe(Offset(r.toFloat(), b.toFloat()))
    val circle = Path().apply { addOval(Rect(center, radius)) }
    clipPath(circle) {
        drawRect(Color.Black, Offset(center.x - radius, center.y - radius), Size(2 * radius, 2 * radius))
        drawImage(
            image,
            srcOffset = IntOffset(l, t),
            srcSize = IntSize(r - l, b - t),
            dstOffset = IntOffset(d0.x.roundToInt(), d0.y.roundToInt()),
            dstSize = IntSize((d1.x - d0.x).roundToInt().coerceAtLeast(1), (d1.y - d0.y).roundToInt().coerceAtLeast(1)),
        )
        shapes.forEach { (pts, color) -> drawPolyline(drawOrder(pts).map { toLoupe(it) }, color, 1.5.dp.toPx()) }
        val arm = 10.dp.toPx()
        drawLine(Color.White, Offset(center.x - arm, center.y), Offset(center.x + arm, center.y), strokeWidth = 1.dp.toPx())
        drawLine(Color.White, Offset(center.x, center.y - arm), Offset(center.x, center.y + arm), strokeWidth = 1.dp.toPx())
    }
    drawCircle(Color.White, radius, center, style = Stroke(width = 2.dp.toPx()))
}

/** Aperçu redressé : contour pointé, bandes analysées et limites d'éléments (qui suivent les joints, éventuellement inclinés). */
private fun DrawScope.drawFaceOverlay(frame: ImageFrame, preview: FacePreview, id: PhotoIdentification, boundaries: List<Double>?) {
    fun screen(x: Double, y: Double) = frame.toScreen(preview.toPreview(x, y))
    // Contour pointé (face nominale).
    val contour = listOf(screen(0.0, 0.0), screen(id.faceWidth.toDouble(), 0.0),
        screen(id.faceWidth.toDouble(), id.faceHeight.toDouble()), screen(0.0, id.faceHeight.toDouble()))
    drawPolyline(contour, Color.White.copy(alpha = 0.7f), 1.dp.toPx())
    // Bandes retenues pour l'analyse : voile léger sur l'étendue du radiateur et repère dans la marge gauche.
    val extent = id.extentFacePx
    id.analyzedRowsFacePx.forEach { rows ->
        val p0 = screen(extent.start, rows.start)
        val p1 = screen(extent.endInclusive, rows.endInclusive)
        if (p1.x > p0.x && p1.y > p0.y) {
            drawRect(BAND_COLOR.copy(alpha = 0.16f), p0, Size(p1.x - p0.x, p1.y - p0.y))
            drawRect(BAND_COLOR, Offset(p0.x - 6.dp.toPx(), p0.y), Size(4.dp.toPx(), p1.y - p0.y))
        }
    }
    boundaries?.let { b -> drawBoundaries(id.boundaryLinesInFace(b).map { (top, bottom) -> screen(top.x, top.y) to screen(bottom.x, bottom.y) }) }
}
