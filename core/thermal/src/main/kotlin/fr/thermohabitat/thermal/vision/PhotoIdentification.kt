package fr.thermohabitat.thermal.vision

import fr.thermohabitat.thermal.model.ValueOrigin
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/** Dimensions déduites de la photo pour un nombre d'éléments donné. */
data class PhotoDimensions(
    val pitchMm: DimensionEstimate?,
    val heightMm: DimensionEstimate?,
    val lengthMm: DimensionEstimate?,
    /** MEASURED : référence métrique ; ESTIMATED : pas standard supposé ; UNKNOWN : aucune échelle. */
    val origin: ValueOrigin,
)

/**
 * Résultat de l'identification géométrique d'un radiateur à éléments sur une photo (éventuellement de biais).
 * Toutes les valeurs sont des PROPOSITIONS à valider par l'utilisateur.
 *
 * Repère « face » : image redressée de [faceWidth] × [faceHeight] px, éléments verticaux équidistants.
 * [faceToImage] reprojette un point de la face sur la photo (traits des limites d'éléments).
 */
data class PhotoIdentification(
    /** Nombre d'éléments proposé, null si le motif n'est pas exploitable. */
    val count: Int?,
    val alternatives: List<CountAlternative>,
    val plusOrMinusOne: Boolean,
    /** Confiance du comptage seul (0–1). */
    val countConfidence: Double,
    val pitchMm: DimensionEstimate?,
    val heightMm: DimensionEstimate?,
    val lengthMm: DimensionEstimate?,
    val dimensionsOrigin: ValueOrigin,
    /** Confiance globale (comptage et dimensions), 0–1. */
    val confidence: Double,
    /** Explications (méthode, échelle utilisée). */
    val messages: List<String>,
    /** Avertissements (précision réduite, référence douteuse…). */
    val warnings: List<String>,
    val aspect: AspectEstimate,
    /** Rapport largeur / hauteur utilisé pour le redressement (mesuré avec un objet à 4 coins, sinon estimé). */
    val aspectRatioUsed: Double,
    val aspectMeasured: Boolean,
    /** Pas / hauteur (sans unité), utile au rapprochement avec la bibliothèque même sans échelle. */
    val pitchToHeightRatio: Double?,
    /** Coins du radiateur ordonnés (haut-gauche, haut-droit, bas-droit, bas-gauche), px de la photo. */
    val corners: List<ImagePoint>,
    val faceWidth: Int,
    val faceHeight: Int,
    val faceToImage: Homography,
    val imageToFace: Homography,
    /** Détail du comptage (limites en px de la face : bord gauche, joints, bord droit). */
    val counting: ElementCountResult,
    /** Échelle retenue (null sans référence) : sert à recalculer les dimensions pour un autre nombre. */
    val scale: PhotoScale?,
) {
    /** Limites proposées (px de la face) : bord gauche, joints, bord droit. */
    val boundariesFacePx: List<Double> get() = counting.boundariesPx

    /** Bords du radiateur retenus dans la face (détectés, sinon ceux du contour). */
    val extentFacePx: ClosedFloatingPointRange<Double>
        get() = (counting.startPx ?: 0.0)..(counting.endPx ?: faceWidth.toDouble())

    /** Limites régulières pour un nombre choisi par l'utilisateur (boutons − / +). */
    fun boundariesFor(count: Int): List<Double> =
        ElementCounter.evenBoundaries(count, extentFacePx.start, extentFacePx.endInclusive)

    /** Segments (haut, bas) dans la photo des limites verticales données en px de la face. */
    fun boundaryLinesInImage(boundaries: List<Double> = boundariesFacePx): List<Pair<ImagePoint, ImagePoint>> =
        boundaries.map { x -> faceToImage.map(x, 0.0) to faceToImage.map(x, faceHeight.toDouble()) }

    /** Dimensions pour un nombre d'éléments choisi (le pas change, la longueur aussi avec un pas standard). */
    fun dimensionsFor(count: Int): PhotoDimensions {
        require(count >= 1) { "Nombre d'éléments invalide" }
        val s = scale ?: return PhotoDimensions(null, null, null, ValueOrigin.UNKNOWN)
        return s.dimensions(count)
    }
}

/**
 * Échelle de la face, indépendante du nombre d'éléments sauf avec un pas standard.
 * Les incertitudes relatives proviennent de la perturbation déterministe des points.
 */
class PhotoScale internal constructor(
    private val lengthMm: Double?,
    private val lengthRel: Double,
    private val heightMm: Double?,
    private val heightRel: Double,
    private val origin: ValueOrigin,
    /** Pas standard : (pas, incertitude relative, hauteur pour un élément de la face = H·pas/P) */
    private val standardPitch: Triple<Double, Double, Double>? = null,
    private val extentPx: Double = 0.0,
) {
    fun dimensions(count: Int): PhotoDimensions {
        if (standardPitch != null) {
            val (pitch, rel, heightPerPitchUnit) = standardPitch
            val length = count * pitch
            // Hauteur = (hauteur face / pas face) × pas, avec pas face = étendue / nombre.
            val height = heightPerPitchUnit * count / extentPx * pitch
            return PhotoDimensions(
                DimensionEstimate(pitch, pitch * rel),
                DimensionEstimate(height, height * sqrt(rel * rel + heightRel * heightRel)),
                DimensionEstimate(length, length * rel),
                origin,
            )
        }
        val l = lengthMm?.let { DimensionEstimate(it, it * lengthRel) }
        val h = heightMm?.let { DimensionEstimate(it, it * heightRel) }
        val p = lengthMm?.let { DimensionEstimate(it / count, it * lengthRel / count) }
        return PhotoDimensions(p, h, l, origin)
    }
}

/**
 * Identification géométrique d'un radiateur à éléments sur une photo, même prise un peu de biais :
 * redressement de la façade (homographie), comptage des éléments sur l'image redressée, puis échelle
 * (objet de référence à 4 coins, longueur connue, dimension mesurée ou pas standard).
 * Déterministe : mêmes entrées → même résultat.
 */
object PhotoIdentifier {

    /** Pas plausibles des radiateurs à éléments (mm) : acier tubulaire ≈ 45–50, fonte ≈ 50–80, aluminium ≈ 80–100. */
    val PLAUSIBLE_PITCH_MM: ClosedFloatingPointRange<Double> = 35.0..110.0

    /** Erreur de pointé simulée par point (px) pour les incertitudes. */
    const val POINTING_ERROR_PX = 3.0

    private const val SAMPLES = 48
    private const val SEED = 7_331

    /**
     * @param image photo en niveaux de gris (px de la photo d'origine)
     * @param radiatorCorners 4 coins du corps du radiateur (sans pieds), ordre quelconque, px de [image]
     * @param reference référence d'échelle optionnelle (points en px de [image])
     * @param focalPx focale connue en px (EXIF), sinon estimée
     * @throws IllegalArgumentException contour ou référence invalide (message en français)
     */
    fun identify(
        image: GrayImage,
        radiatorCorners: List<ImagePoint>,
        reference: ScaleReference? = null,
        focalPx: Double? = null,
        minElements: Int = 3,
        maxElements: Int = 60,
    ): PhotoIdentification {
        require(radiatorCorners.size == 4) { "Placez les 4 coins du corps du radiateur." }
        val q = Homography.orderCorners(radiatorCorners)
        require(Homography.isConvex(q)) { "Contour invalide : les 4 coins doivent former un quadrilatère convexe." }
        val minSide = (0 until 4).minOf { PlaneRectifier.dist(q[it], q[(it + 1) % 4]) }
        require(minSide >= 20) { "Contour trop petit : rapprochez-vous ou agrandissez la photo." }
        val messages = ArrayList<String>(); val warnings = ArrayList<String>()
        val aspect = PlaneRectifier.estimateAspectRatio(q, image.width, image.height, focalPx)

        // Objet à 4 coins dans le plan : homographie métrique exacte, rapport d'aspect MESURÉ.
        var planar: PlanarSetup? = null
        if (reference is ScaleReference.PlanarObject) {
            planar = planarSetup(reference, q, aspect.ratio, warnings)
        }
        val ratioUsed = planar?.dims?.let { it.lengthMm / it.heightMm } ?: aspect.ratio
        val size = PlaneRectifier.faceSize(q, ratioUsed)
        val faceToImage = PlaneRectifier.faceToImage(q, size.width, size.height)
            ?: throw IllegalArgumentException("Contour invalide : les 4 coins doivent former un quadrilatère convexe.")
        val imageToFace = faceToImage.inverse()
            ?: throw IllegalArgumentException("Contour invalide : redressement impossible.")
        val face = PlaneRectifier.rectify(image, q, size.width, size.height)

        // Zone de l'objet de référence ignorée par le comptage.
        val excluded = planar?.let { p ->
            val pts = p.corners.map { imageToFace.map(it) }
            listOf(PixelRect(pts.minOf { it.x } - 6, pts.minOf { it.y } - 6, pts.maxOf { it.x } + 6, pts.maxOf { it.y } + 6))
        } ?: emptyList()

        // Échelle provisoire (mm par px de face) → a priori de pas en px.
        val preScale: Double? = when (reference) {
            is ScaleReference.PlanarObject -> planar!!.dims.lengthMm / size.width
            is ScaleReference.KnownLength -> {
                val d = PlaneRectifier.dist(imageToFace.map(reference.start), imageToFace.map(reference.end))
                require(d > 1.0) { "Les deux points de la référence sont confondus." }
                reference.lengthMm / d
            }
            is ScaleReference.MeasuredDimension ->
                if (reference.dimension == RadiatorDimension.HEIGHT) reference.valueMm / size.height else reference.valueMm / size.width
            else -> null
        }
        val prior = preScale?.let { (PLAUSIBLE_PITCH_MM.start / it)..(PLAUSIBLE_PITCH_MM.endInclusive / it) }
        val counting = ElementCounter.countRectified(face, prior, minElements, maxElements, excluded)
        val count = counting.count

        // Étendue et pas en fraction de la largeur de la face.
        val extentFrac = if (count != null && counting.startPx != null && counting.endPx != null) {
            (counting.endPx - counting.startPx) / size.width
        } else 1.0
        val pitchFrac = counting.periodPx?.takeIf { count != null }?.let { it / size.width }
        // Incertitude due à la détection des bords (±0,15 pas chacun) quand l'étendue vient du comptage.
        val extentRel = if (count != null && pitchFrac != null) 0.15 * sqrt(2.0) * pitchFrac / extentFrac else 0.0

        val scale: PhotoScale? = when (reference) {
            null -> null
            is ScaleReference.PlanarObject -> planarScale(planar!!, reference, q, extentFrac, extentRel)
            is ScaleReference.KnownLength -> knownLengthScale(reference, q, ratioUsed, aspect.relativeUncertainty, extentFrac, extentRel, warnings)
            is ScaleReference.MeasuredDimension -> measuredScale(reference, ratioUsed, aspect.relativeUncertainty, extentFrac, extentRel)
            is ScaleReference.StandardPitch -> if (count != null && pitchFrac != null) {
                PhotoScale(
                    lengthMm = null, lengthRel = 0.0, heightMm = null, heightRel = aspect.relativeUncertainty,
                    origin = ValueOrigin.ESTIMATED,
                    standardPitch = Triple(reference.pitchMm, reference.uncertaintyMm / reference.pitchMm, size.height.toDouble()),
                    extentPx = extentFrac * size.width,
                )
            } else null
        }
        val dims = if (count != null && scale != null) scale.dimensions(count)
        else scale?.dimensions(1)?.let { it.copy(pitchMm = null) } ?: PhotoDimensions(null, null, null, ValueOrigin.UNKNOWN)

        // Messages.
        messages += if (planar != null) {
            "Façade redressée avec ${reference!!.label.lowercase()} : rapport longueur / hauteur mesuré ${fmt(ratioUsed, 2)}."
        } else if (aspect.frontal) {
            "Photo quasi de face : rapport longueur / hauteur ${fmt(ratioUsed, 2)}."
        } else {
            "Façade redressée (vue ≈ ${aspect.viewAngleDeg.roundToInt()}° de biais), rapport longueur / hauteur estimé ${fmt(ratioUsed, 2)} " +
                "± ${(100 * aspect.relativeUncertainty).roundToInt()} % (focale ${aspect.focalSource.label})."
        }
        messages += counting.message
        if (count == null) messages += "Nombre d'éléments non déterminé : comptez-les sur l'aperçu redressé (boutons − / +)."
        when (reference) {
            null -> warnings += "Sans référence d'échelle, aucune dimension n'est mesurée : seul le nombre d'éléments est proposé. " +
                "Posez une carte bancaire ou une feuille A4 à plat contre la façade, ou notez une dimension mesurée au mètre."
            is ScaleReference.StandardPitch -> warnings += "Dimensions ESTIMÉES à partir d'un pas standard de ${fmt(reference.pitchMm, 0)} mm : à confirmer au mètre."
            is ScaleReference.MeasuredDimension -> messages += "${if (reference.dimension == RadiatorDimension.HEIGHT) "Longueur" else "Hauteur"} " +
                "déduite de la ${reference.dimension.label.lowercase()} mesurée et du rapport d'aspect" +
                (if (aspect.frontal) "." else " (± ${(100 * aspect.relativeUncertainty).roundToInt()} %).")
            else -> Unit
        }
        if (!aspect.frontal && aspect.viewAngleDeg > 50 && planar == null) {
            warnings += "Prise de vue très oblique (≈ ${aspect.viewAngleDeg.roundToInt()}°) : précision réduite, reprenez la photo plus de face si possible."
        }
        dims.pitchMm?.let { p ->
            if (p.valueMm < PLAUSIBLE_PITCH_MM.start || p.valueMm > PLAUSIBLE_PITCH_MM.endInclusive) {
                warnings += "Pas mesuré inhabituel (${fmt(p.valueMm, 0)} mm) : vérifiez le nombre d'éléments et l'échelle."
            }
        }

        val pitchToHeight = if (count != null && pitchFrac != null) pitchFrac * size.width / size.height else null
        val dimRel = listOfNotNull(dims.lengthMm, dims.heightMm).maxOfOrNull { it.relativeUncertainty }
        val dimFactor = dimRel?.let { (1 - 2 * it).coerceIn(0.5, 1.0) } ?: 1.0
        val aspectFactor = if (planar != null || aspect.frontal) 1.0 else (0.7 + 0.3 * aspect.reliability)
        return PhotoIdentification(
            count = count,
            alternatives = counting.alternatives,
            plusOrMinusOne = counting.plusOrMinusOne,
            countConfidence = counting.confidence,
            pitchMm = dims.pitchMm,
            heightMm = dims.heightMm,
            lengthMm = dims.lengthMm,
            dimensionsOrigin = dims.origin,
            confidence = (counting.confidence * dimFactor * aspectFactor).coerceIn(0.0, 1.0),
            messages = messages,
            warnings = warnings.distinct(),
            aspect = aspect,
            aspectRatioUsed = ratioUsed,
            aspectMeasured = planar != null,
            pitchToHeightRatio = pitchToHeight,
            corners = q,
            faceWidth = size.width,
            faceHeight = size.height,
            faceToImage = faceToImage,
            imageToFace = imageToFace,
            counting = counting,
            scale = scale,
        )
    }

    // --- Objet à 4 coins ---

    private class PlanarSetup(val corners: List<ImagePoint>, val landscape: Boolean, val dims: PlaneQuadDimensions)

    private fun planarSetup(ref: ScaleReference.PlanarObject, q: List<ImagePoint>, aspectRatio: Double, warnings: MutableList<String>): PlanarSetup {
        val oq = Homography.orderCorners(ref.corners)
        require(Homography.isConvex(oq)) { "Objet de référence : les 4 coins doivent former un quadrilatère convexe." }
        // Orientation de l'objet (grand côté horizontal ou vertical) lue dans la face redressée par le rapport estimé.
        val frame = Homography.rectToQuad(aspectRatio * 1000, 1000.0, q)?.inverse()
        val (wObs, hObs) = (frame?.let { f -> oq.map { f.map(it) } } ?: oq).let { p ->
            (PlaneRectifier.dist(p[0], p[1]) + PlaneRectifier.dist(p[3], p[2])) / 2 to (PlaneRectifier.dist(p[0], p[3]) + PlaneRectifier.dist(p[1], p[2])) / 2
        }
        val rObs = wObs / hObs
        val landscape = rObs >= 1.0
        val deviation = abs(ln(if (landscape) rObs / ref.obj.ratio else rObs * ref.obj.ratio))
        val h = ScaleCalculator.metricHomography(ref.obj, oq, landscape)
            ?: throw IllegalArgumentException("Objet de référence : coins dégénérés.")
        val dims = ScaleCalculator.quadDimensions(h, q)
        require(dims.lengthMm.isFinite() && dims.heightMm.isFinite() && dims.lengthMm > 0 && dims.heightMm > 0) {
            "Référence incohérente avec le contour du radiateur : vérifiez les coins."
        }
        val objPx = (0 until 4).sumOf { PlaneRectifier.dist(oq[it], oq[(it + 1) % 4]) } / 4
        if (objPx < 60) warnings += "La référence est petite dans l'image : rapprochez-vous pour plus de précision."
        if (ref.obj.longMm / max(dims.lengthMm, dims.heightMm) < 0.08) {
            warnings += "Référence petite devant le radiateur : l'incertitude est élargie (une feuille A4 serait plus précise)."
        }
        if (deviation > 0.15) {
            warnings += "La forme de la référence ne correspond pas à celle attendue : elle semble hors du plan de la façade ou ses coins sont mal placés."
        }
        if (dims.maxAngleErrorDeg > 5) {
            warnings += "Le contour du radiateur n'est pas rectangulaire dans le plan de la référence : vérifiez les coins et que la référence est plaquée contre la façade."
        }
        return PlanarSetup(oq, landscape, dims)
    }

    private fun planarScale(p: PlanarSetup, ref: ScaleReference.PlanarObject, q: List<ImagePoint>, extentFrac: Double, extentRel: Double): PhotoScale {
        val rnd = Random(SEED)
        val ls = ArrayList<Double>(); val hs = ArrayList<Double>()
        repeat(SAMPLES) {
            val qp = q.map { jitter(it, rnd) }; val op = p.corners.map { jitter(it, rnd) }
            val h = ScaleCalculator.metricHomography(ref.obj, op, p.landscape) ?: return@repeat
            val d = ScaleCalculator.quadDimensions(h, qp)
            if (d.lengthMm.isFinite() && d.heightMm.isFinite()) { ls += d.lengthMm; hs += d.heightMm }
        }
        // Terme systématique : épaisseur et planéité de la référence (≈ 0,5 %).
        val sys = 0.005
        val lRel = sqrt(sq(relStd(ls)) + sq(extentRel) + sq(sys))
        val hRel = sqrt(sq(relStd(hs)) + sq(sys))
        return PhotoScale(extentFrac * p.dims.lengthMm, lRel, p.dims.heightMm, hRel, ValueOrigin.MEASURED)
    }

    // --- Longueur connue (2 points) ---

    private fun knownLengthScale(
        ref: ScaleReference.KnownLength, q: List<ImagePoint>, ratio: Double, ratioRel: Double,
        extentFrac: Double, extentRel: Double, warnings: MutableList<String>,
    ): PhotoScale? {
        fun eval(qp: List<ImagePoint>, a: ImagePoint, b: ImagePoint, r: Double): Pair<Double, Double>? {
            val f = Homography.rectToQuad(r * 1000, 1000.0, qp)?.inverse() ?: return null
            val d = PlaneRectifier.dist(f.map(a), f.map(b))
            if (!(d > 1e-6)) return null
            val s = ref.lengthMm / d
            return (extentFrac * r * 1000 * s) to (1000 * s)
        }
        val nominal = eval(q, ref.start, ref.end, ratio) ?: return null
        val rnd = Random(SEED)
        val ls = ArrayList<Double>(); val hs = ArrayList<Double>()
        repeat(SAMPLES) {
            val rp = ratio * (1 + ratioRel * sqrt(3.0) * rnd.nextDouble(-1.0, 1.0))
            val v = eval(q.map { jitter(it, rnd) }, jitter(ref.start, rnd), jitter(ref.end, rnd), rp) ?: return@repeat
            ls += v.first; hs += v.second
        }
        val refPx = PlaneRectifier.dist(ref.start, ref.end)
        if (refPx < 60) warnings += "La référence est petite dans l'image : rapprochez-vous pour plus de précision."
        if (ref.smallObject) warnings += "${ref.label} : petite référence, précision limitée ; préférez une carte bancaire ou une feuille A4."
        warnings += "La longueur de référence doit être dans le plan de la façade (pas sur le mur derrière le radiateur)."
        val sys = 0.01
        return PhotoScale(
            nominal.first, sqrt(sq(relStd(ls)) + sq(extentRel) + sq(sys)),
            nominal.second, sqrt(sq(relStd(hs)) + sq(sys)),
            ValueOrigin.MEASURED,
        )
    }

    // --- Dimension mesurée au mètre ---

    private fun measuredScale(ref: ScaleReference.MeasuredDimension, ratio: Double, ratioRel: Double, extentFrac: Double, extentRel: Double): PhotoScale {
        val vRel = ref.uncertaintyMm / ref.valueMm
        return if (ref.dimension == RadiatorDimension.HEIGHT) {
            val length = extentFrac * ratio * ref.valueMm
            PhotoScale(length, sqrt(sq(vRel) + sq(ratioRel) + sq(extentRel)), ref.valueMm, vRel, ValueOrigin.MEASURED)
        } else {
            val height = ref.valueMm / (extentFrac * ratio)
            PhotoScale(ref.valueMm, vRel, height, sqrt(sq(vRel) + sq(ratioRel) + sq(extentRel)), ValueOrigin.MEASURED)
        }
    }

    // --- Outils ---

    private fun jitter(p: ImagePoint, rnd: Random) = ImagePoint(
        p.x + rnd.nextDouble(-POINTING_ERROR_PX, POINTING_ERROR_PX), p.y + rnd.nextDouble(-POINTING_ERROR_PX, POINTING_ERROR_PX),
    )

    private fun relStd(v: List<Double>): Double {
        if (v.size < 2) return 0.1
        val m = v.average()
        return sqrt(v.sumOf { (it - m) * (it - m) } / (v.size - 1)) / m
    }

    private fun sq(x: Double) = x * x

    private fun fmt(v: Double, decimals: Int): String {
        val p = Math.pow(10.0, decimals.toDouble())
        val r = (v * p).roundToInt() / p
        return if (decimals == 0) r.roundToInt().toString() else String.format(java.util.Locale.FRANCE, "%.${decimals}f", r)
    }
}
