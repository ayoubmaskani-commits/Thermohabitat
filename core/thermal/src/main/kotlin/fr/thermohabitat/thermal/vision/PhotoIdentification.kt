package fr.thermohabitat.thermal.vision

import fr.thermohabitat.thermal.model.ValueOrigin
import kotlin.math.abs
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
    /** Rapport largeur / hauteur utilisé pour le redressement (estimé par la perspective du contour). */
    val aspectRatioUsed: Double,
    /**
     * Rapport longueur / hauteur du corps : MESURÉ avec un objet à 4 coins, sinon estimé à partir du redressement
     * et des bords détectés.
     */
    val bodyAspectRatio: Double,
    /** Haut et bas du corps détectés au milieu de la face (px de la face), null si non détectés (coins retenus). */
    val bodyRowsFacePx: ClosedFloatingPointRange<Double>?,
    /** Vrai si proportions et échelle viennent d'un objet à 4 coins (indépendant de la focale). */
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
    /**
     * Limites proposées (px de la face) : bord gauche, joints, bord droit, lues sur la rangée [boundariesRowFacePx].
     * Elles suivent la loi des joints : pas forcément équidistantes si un coin est mal placé.
     */
    val boundariesFacePx: List<Double> get() = counting.boundariesPx

    /** Rangée de la face (px) sur laquelle [boundariesFacePx] sont lues (milieu du contour). */
    val boundariesRowFacePx: Double get() = counting.referenceRowPx ?: (faceHeight / 2.0)

    /** Loi des joints dans la face (indice 0 au bord gauche, [count] au bord droit), null sans comptage. */
    val jointModel: JointModel? get() = counting.jointModel

    /** Bandes de la face (px) retenues pour l'analyse, pour affichage (zones masquées exclues). */
    val analyzedRowsFacePx: List<ClosedFloatingPointRange<Double>> get() = counting.analyzedRowsPx

    /** Bords du radiateur retenus dans la face (détectés, sinon ceux du contour). */
    val extentFacePx: ClosedFloatingPointRange<Double>
        get() = (counting.startPx ?: 0.0)..(counting.endPx ?: faceWidth.toDouble())

    /**
     * Limites pour un nombre choisi par l'utilisateur (boutons − / +), entre les bords retenus : indices de la loi des
     * joints répartis régulièrement (la perspective résiduelle est conservée), sinon limites équidistantes. Pour le
     * nombre proposé, ce sont exactement [boundariesFacePx].
     */
    fun boundariesFor(count: Int): List<Double> {
        require(count >= 1) { "Nombre d'éléments invalide" }
        val n = this.count
        if (n != null && count == n && boundariesFacePx.size == n + 1) return boundariesFacePx
        val start = extentFacePx.start; val end = extentFacePx.endInclusive
        val m = jointModel ?: return ElementCounter.evenBoundaries(count, start, end)
        val y = boundariesRowFacePx
        val k0 = m.indexAt(start, y); val k1 = m.indexAt(end, y)
        if (!k0.isFinite() || !k1.isFinite() || k1 <= k0) return ElementCounter.evenBoundaries(count, start, end)
        return List(count + 1) { i ->
            when (i) {
                0 -> start
                count -> end
                else -> m.positionAt(k0 + (k1 - k0) * i / count, y)
            }
        }
    }

    /**
     * Segments (haut, bas) dans la photo des limites données en px de la face (rangée [boundariesRowFacePx]), sur la
     * hauteur du corps ; avec la loi des joints, chaque segment suit le joint (éventuellement incliné dans la face).
     */
    fun boundaryLinesInImage(boundaries: List<Double> = boundariesFacePx): List<Pair<ImagePoint, ImagePoint>> =
        boundaryLinesInFace(boundaries).map { (top, bottom) -> faceToImage.map(top) to faceToImage.map(bottom) }

    /** Segments (haut, bas) des limites dans la face (px), sur la hauteur du corps, pour l'aperçu redressé. */
    fun boundaryLinesInFace(boundaries: List<Double> = boundariesFacePx): List<Pair<ImagePoint, ImagePoint>> {
        val rows = bodyRowsFacePx ?: 0.0..faceHeight.toDouble()
        val m = jointModel
        val y = boundariesRowFacePx
        return boundaries.map { x ->
            val k = m?.indexAt(x, y)
            if (m == null || k == null || !k.isFinite()) {
                ImagePoint(x, rows.start) to ImagePoint(x, rows.endInclusive)
            } else {
                ImagePoint(m.positionAt(k, rows.start), rows.start) to ImagePoint(m.positionAt(k, rows.endInclusive), rows.endInclusive)
            }
        }
    }

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
    /** Pas standard : (pas mm, incertitude relative, hauteur du corps en px de face). */
    private val standardPitch: Triple<Double, Double, Double>? = null,
    /** Étendue du corps en px de face (pas standard). */
    private val extentPx: Double = 0.0,
) {
    fun dimensions(count: Int): PhotoDimensions {
        if (standardPitch != null) {
            val (pitch, rel, bodyHeightPx) = standardPitch
            val length = count * pitch
            // Hauteur = (hauteur du corps / pas dans la face) × pas, avec pas dans la face = étendue / nombre.
            val height = bodyHeightPx * count / extentPx * pitch
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
    private const val MARGIN_FRACTION = 0.15
    private const val MARGIN_Y_FRACTION = 0.08
    private const val SEED = 7_331

    /**
     * Étapes : rapport d'aspect du contour (Zhang & He) → face redressée (≤ 1200 px, avec marges) → comptage (bandes
     * exploitables malgré les objets posés devant, pas commun et harmoniques, loi projective des joints ajustée de façon
     * robuste, bords, haut et bas du corps) → échelle et incertitudes (perturbation déterministe de ±[POINTING_ERROR_PX]
     * px par point). Quelques centaines de millisecondes sur JVM de bureau.
     *
     * @param image photo en niveaux de gris (px de la photo d'origine)
     * @param radiatorCorners 4 coins du corps du radiateur (sans pieds), ordre quelconque, px de [image]
     * @param reference référence d'échelle optionnelle (points en px de [image])
     * @param focalPx focale connue en px (EXIF, voir [PlaneRectifier.focalPxFrom35mm]), sinon estimée
     * @param minElements / maxElements bornes de recherche du nombre d'éléments
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

        val ratioUsed = aspect.ratio
        // Face nominale bornée pour que la face élargie de ses marges reste ≤ 1200 px (calcul rapide sur téléphone).
        val size = PlaneRectifier.faceSize(q, ratioUsed, (PlaneRectifier.MAX_FACE_SIDE / (1 + 2 * MARGIN_FRACTION)).toInt())
        val faceToImage = PlaneRectifier.faceToImage(q, size.width, size.height)
            ?: throw IllegalArgumentException("Contour invalide : les 4 coins doivent former un quadrilatère convexe.")
        val imageToFace = faceToImage.inverse()
            ?: throw IllegalArgumentException("Contour invalide : redressement impossible.")
        // Objet à 4 coins dans le plan : échelles horizontale et verticale lues dans la face redressée.
        val planar = (reference as? ScaleReference.PlanarObject)?.let { planarSetup(it, q, imageToFace, warnings) }
        // Face élargie de marges (15 % de la largeur de chaque côté, 8 % de la hauteur en haut et en bas) : les bords
        // réels du radiateur y restent visibles même si les coins sont posés un peu à l'intérieur.
        val margin = (MARGIN_FRACTION * size.width).roundToInt()
        val marginY = (MARGIN_Y_FRACTION * size.height).roundToInt()
        val shift = Homography(doubleArrayOf(1.0, 0.0, -margin.toDouble(), 0.0, 1.0, -marginY.toDouble(), 0.0, 0.0, 1.0))
        val face = PlaneRectifier.rectify(image, faceToImage * shift, size.width + 2 * margin, size.height + 2 * marginY,
            PlaneRectifier.supersampleFor(q, size.width, size.height))

        // Zone de l'objet de référence ignorée par l'analyse (px de la face élargie).
        val excluded = planar?.let { p ->
            val pts = p.corners.map { imageToFace.map(it) }
            listOf(PixelRect(pts.minOf { it.x } - 6 + margin, pts.minOf { it.y } - 6 + marginY,
                pts.maxOf { it.x } + 6 + margin, pts.maxOf { it.y } + 6 + marginY))
        } ?: emptyList()

        // Échelle provisoire (mm par px de face) → a priori de pas en px.
        val preScale: Double? = when (reference) {
            is ScaleReference.PlanarObject -> planar!!.scaleX
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
        val counting = ElementCounter.countRectified(face, prior, minElements, maxElements, excluded,
            contour = margin.toDouble()..(margin + size.width).toDouble(),
            contourRows = marginY.toDouble()..(marginY + size.height).toDouble()).shifted(-margin.toDouble(), -marginY.toDouble())
        val count = counting.count
        // Haut et bas réels du corps (coins pointés un peu trop haut ou trop bas) entre les bords retenus.
        val cols = if (count != null && counting.startPx != null && counting.endPx != null) {
            (counting.startPx + margin)..(counting.endPx + margin)
        } else margin.toDouble()..(margin + size.width).toDouble()
        val body = VerticalExtent.detect(face, cols, marginY.toDouble()..(marginY + size.height).toDouble(), excluded)
        val heightFrac = body?.let { it.meanHeight(cols.start, cols.endInclusive) / size.height }?.takeIf { it in 0.85..1.15 } ?: 1.0
        val bodyRows = if (heightFrac != 1.0 && body != null) {
            val xm = (cols.start + cols.endInclusive) / 2
            (body.top(xm) - marginY)..(body.bottom(xm) - marginY)
        } else null

        // Étendue et pas en fraction de la largeur de la face.
        val extentFrac = if (count != null && counting.startPx != null && counting.endPx != null) {
            (counting.endPx - counting.startPx) / size.width
        } else 1.0
        val pitchFrac = counting.periodPx?.takeIf { count != null }?.let { it / size.width }
        // Incertitude due à la détection des bords (±0,15 pas chacun) quand l'étendue vient du comptage.
        val extentRel = if (count != null && pitchFrac != null) 0.15 * sqrt(2.0) * pitchFrac / extentFrac else 0.0
        // Haut et bas : écart des points aux droites détectées, sinon pointé des coins (≈ 1 % de la hauteur chacun).
        val heightDetRel = if (bodyRows != null) (body!!.residualPx + 0.5) * sqrt(2.0) / size.height else 0.0

        val scale: PhotoScale? = when (reference) {
            null -> null
            is ScaleReference.PlanarObject -> planarScale(planar!!, reference, q, size, extentFrac * size.width, extentRel,
                heightFrac * size.height, heightDetRel, warnings)
            is ScaleReference.KnownLength -> knownLengthScale(reference, q, ratioUsed, aspect.relativeUncertainty, extentFrac, extentRel,
                heightFrac, heightDetRel, warnings)
            is ScaleReference.MeasuredDimension -> measuredScale(reference, ratioUsed, aspect.relativeUncertainty, extentFrac / heightFrac,
                sqrt(sq(extentRel) + sq(heightDetRel)))
            is ScaleReference.StandardPitch -> if (count != null && pitchFrac != null) {
                PhotoScale(
                    lengthMm = null, lengthRel = 0.0, heightMm = null, heightRel = aspect.relativeUncertainty,
                    origin = ValueOrigin.ESTIMATED,
                    standardPitch = Triple(reference.pitchMm, reference.uncertaintyMm / reference.pitchMm, heightFrac * size.height),
                    extentPx = extentFrac * size.width,
                )
            } else null
        }
        val dims = if (count != null && scale != null) scale.dimensions(count)
        else scale?.dimensions(1)?.let { it.copy(pitchMm = null) } ?: PhotoDimensions(null, null, null, ValueOrigin.UNKNOWN)

        // Messages.
        val bodyAspect = if (planar != null && dims.lengthMm != null && dims.heightMm != null) {
            dims.lengthMm.valueMm / dims.heightMm.valueMm
        } else extentFrac * ratioUsed / heightFrac
        messages += if (planar != null) {
            "Échelle et proportions lues sur ${reference.label.lowercase()} : rapport longueur / hauteur mesuré ${fmt(bodyAspect, 2)}."
        } else if (aspect.frontal) {
            "Photo quasi de face : rapport longueur / hauteur ${fmt(ratioUsed, 2)}."
        } else {
            "Façade redressée (vue ≈ ${aspect.viewAngleDeg.roundToInt()}° de biais), rapport longueur / hauteur estimé ${fmt(ratioUsed, 2)} " +
                "± ${(100 * aspect.relativeUncertainty).roundToInt()} % (focale ${aspect.focalSource.label})."
        }
        messages += counting.message
        warnings += counting.warnings
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

        val pitchToHeight = if (count != null && pitchFrac != null) pitchFrac * size.width / (heightFrac * size.height) else null
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
            bodyAspectRatio = bodyAspect,
            bodyRowsFacePx = bodyRows,
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

    /**
     * La perspective vient du contour du radiateur (grand, bien déterminé) : la face redressée est exacte à une
     * dilatation horizontale près (rapport d'aspect estimé). L'objet, petit, ne donne que les ÉCHELLES : son grand
     * et son petit côté mesurés dans la face fixent les mm par px en x et en y, sans focale. Extrapoler la propre
     * homographie d'une carte de quelques dizaines de pixels au radiateur entier serait bien moins précis.
     */
    private class PlanarSetup(val corners: List<ImagePoint>, val landscape: Boolean, val scaleX: Double, val scaleY: Double)

    /** Échelles (mm par px de face) en x et en y tirées des côtés de l'objet ; null si dégénéré. */
    private fun objectScales(obj: ScaleObject, landscape: Boolean, corners: List<ImagePoint>, imageToFace: Homography): Pair<Double, Double>? {
        val p = corners.map { imageToFace.map(it) }
        val wF = (PlaneRectifier.dist(p[0], p[1]) + PlaneRectifier.dist(p[3], p[2])) / 2
        val hF = (PlaneRectifier.dist(p[0], p[3]) + PlaneRectifier.dist(p[1], p[2])) / 2
        if (!(wF > 1e-6) || !(hF > 1e-6)) return null
        val (wMm, hMm) = if (landscape) obj.longMm to obj.shortMm else obj.shortMm to obj.longMm
        return (wMm / wF) to (hMm / hF)
    }

    private fun planarSetup(ref: ScaleReference.PlanarObject, q: List<ImagePoint>, imageToFace: Homography, warnings: MutableList<String>): PlanarSetup {
        val oq = Homography.orderCorners(ref.corners)
        require(Homography.isConvex(oq)) { "Objet de référence : les 4 coins doivent former un quadrilatère convexe." }
        // Orientation (grand côté horizontal ou vertical) lue dans la face redressée.
        val pf = oq.map { imageToFace.map(it) }
        val wObs = (PlaneRectifier.dist(pf[0], pf[1]) + PlaneRectifier.dist(pf[3], pf[2])) / 2
        val hObs = (PlaneRectifier.dist(pf[0], pf[3]) + PlaneRectifier.dist(pf[1], pf[2])) / 2
        val landscape = wObs >= hObs
        val (sx, sy) = objectScales(ref.obj, landscape, oq, imageToFace)
            ?: throw IllegalArgumentException("Objet de référence : coins dégénérés.")
        val objPx = (0 until 4).sumOf { PlaneRectifier.dist(oq[it], oq[(it + 1) % 4]) } / 4
        if (objPx < 60) warnings += "La référence est petite dans l'image : rapprochez-vous pour plus de précision."
        val radiatorPx = (0 until 4).sumOf { PlaneRectifier.dist(q[it], q[(it + 1) % 4]) } / 4
        if (objPx / radiatorPx < 0.12) {
            warnings += "Référence petite devant le radiateur : l'incertitude est élargie (une feuille A4 serait plus précise)."
        }
        return PlanarSetup(oq, landscape, sx, sy)
    }

    private fun planarScale(
        p: PlanarSetup, ref: ScaleReference.PlanarObject, q: List<ImagePoint>, size: FaceSize,
        extentPx: Double, extentRel: Double, heightPx: Double, heightRel: Double, warnings: MutableList<String>,
    ): PhotoScale {
        val length = extentPx * p.scaleX
        val height = heightPx * p.scaleY
        val rnd = Random(SEED)
        val ls = ArrayList<Double>(); val hs = ArrayList<Double>()
        repeat(SAMPLES) {
            val qp = q.map { jitter(it, rnd) }; val op = p.corners.map { jitter(it, rnd) }
            val f = Homography.rectToQuad(size.width.toDouble(), size.height.toDouble(), qp)?.inverse() ?: return@repeat
            val (sx, sy) = objectScales(ref.obj, p.landscape, op, f) ?: return@repeat
            ls += extentPx * sx; hs += heightPx * sy
        }
        // Contrôle de cohérence par l'homographie propre de l'objet, seulement quand elle est bien déterminée.
        val own = ScaleCalculator.metricHomography(ref.obj, p.corners, p.landscape)?.let { ScaleCalculator.quadDimensions(it, q) }
        if (own != null && own.heightMm.isFinite()) {
            val hsOwn = ArrayList<Double>()
            repeat(SAMPLES) {
                val h = ScaleCalculator.metricHomography(ref.obj, p.corners.map { jitter(it, rnd) }, p.landscape) ?: return@repeat
                val d = ScaleCalculator.quadDimensions(h, q).heightMm
                if (d.isFinite()) hsOwn += d
            }
            if (relStd(hsOwn) < 0.03) {
                if (abs(own.heightMm - height) / height > 0.08 || own.maxAngleErrorDeg > 5) {
                    warnings += "La référence et le contour du radiateur ne concordent pas : vérifiez que la référence est " +
                        "plaquée contre la façade et que les coins sont bien placés."
                }
            }
        }
        // Terme systématique : épaisseur et planéité de la référence (≈ 0,5 %).
        val sys = 0.005
        val lRel = sqrt(sq(relStd(ls)) + sq(extentRel) + sq(sys))
        val hRel = sqrt(sq(relStd(hs)) + sq(heightRel) + sq(sys))
        return PhotoScale(length, lRel, height, hRel, ValueOrigin.MEASURED)
    }

    // --- Longueur connue (2 points) ---

    private fun knownLengthScale(
        ref: ScaleReference.KnownLength, q: List<ImagePoint>, ratio: Double, ratioRel: Double,
        extentFrac: Double, extentRel: Double, heightFrac: Double, heightRel: Double, warnings: MutableList<String>,
    ): PhotoScale? {
        fun eval(qp: List<ImagePoint>, a: ImagePoint, b: ImagePoint, r: Double): Pair<Double, Double>? {
            val f = Homography.rectToQuad(r * 1000, 1000.0, qp)?.inverse() ?: return null
            val d = PlaneRectifier.dist(f.map(a), f.map(b))
            if (!(d > 1e-6)) return null
            val s = ref.lengthMm / d
            return (extentFrac * r * 1000 * s) to (heightFrac * 1000 * s)
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
            nominal.second, sqrt(sq(relStd(hs)) + sq(heightRel) + sq(sys)),
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

    /** Résultat de comptage exprimé dans le repère de la face nominale (translation de (dx, dy)). */
    private fun ElementCountResult.shifted(dx: Double, dy: Double) = copy(
        boundariesPx = boundariesPx.map { it + dx },
        startPx = startPx?.plus(dx),
        endPx = endPx?.plus(dx),
        analyzedRowsPx = analyzedRowsPx.map { (it.start + dy)..(it.endInclusive + dy) },
        jointModel = jointModel?.shifted(dx, dy),
        referenceRowPx = referenceRowPx?.plus(dy),
    )

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
