package fr.thermohabitat.thermal.vision

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/** Origine de la focale utilisée pour estimer le rapport d'aspect. */
enum class FocalSource(val label: String) {
    DEDUCED("déduite du contour (deux points de fuite)"),
    PROVIDED("fournie (données de l'appareil photo)"),
    DEFAULT("par défaut d'un téléphone (≈ 26 mm équivalent 24×36)"),
    NOT_NEEDED("inutile : vue quasi de face"),
}

/**
 * Rapport largeur / hauteur RÉEL d'un rectangle photographié en perspective.
 *
 * @property ratio largeur / hauteur dans le plan de la façade
 * @property relativeUncertainty incertitude relative (1 σ environ) : pointé des coins et focale
 * @property viewAngleDeg angle estimé entre l'axe de visée et la normale à la façade (0 = de face)
 * @property reliability fiabilité 0–1 (1 : incertitude négligeable)
 */
data class AspectEstimate(
    val ratio: Double,
    val relativeUncertainty: Double,
    val focalPx: Double,
    val focalSource: FocalSource,
    val frontal: Boolean,
    val viewAngleDeg: Double,
    val reliability: Double,
)

/** Dimensions (px) d'une image redressée. */
data class FaceSize(val width: Int, val height: Int)

/**
 * Redressement d'une façade plane photographiée de biais.
 *
 * Le rapport d'aspect suit la méthode de Zhang & He (« Whiteboard scanning and image enhancement », 2007) :
 * point principal au centre de l'image, pixels carrés ; la focale est déduite du quadrilatère quand les deux
 * paires de côtés fuient nettement, sinon on prend celle fournie ou celle, par défaut, d'un téléphone.
 */
object PlaneRectifier {

    /** Focale par défaut / grand côté de l'image : ≈ 26 mm équivalent 24×36 (objectif principal d'un téléphone). */
    const val DEFAULT_FOCAL_FACTOR = 0.72

    /** Incertitude relative supposée de la focale par défaut (objectifs de 23 à 30 mm équivalent). */
    const val DEFAULT_FOCAL_UNCERTAINTY = 0.18

    /** Incertitude relative d'une focale fournie (EXIF). */
    const val PROVIDED_FOCAL_UNCERTAINTY = 0.05

    /** Plus grand côté de l'image redressée (calcul rapide sur téléphone). */
    const val MAX_FACE_SIDE = 1200

    /** Erreur de pointé supposée par coin (px). */
    const val POINTING_ERROR_PX = 3.0

    private const val PLAUSIBLE_FOCAL_MIN = 0.4
    private const val PLAUSIBLE_FOCAL_MAX = 2.5
    private const val SEED = 20_241_013

    fun defaultFocalPx(imageWidth: Int, imageHeight: Int): Double = DEFAULT_FOCAL_FACTOR * max(imageWidth, imageHeight)

    /**
     * Focale (px) déduite du quadrilatère, null si elle n'est pas calculable : une paire de côtés parallèles dans
     * l'image (rotation autour d'un seul axe) ne fournit qu'un point de fuite.
     */
    fun focalFromQuad(quad: List<ImagePoint>, imageWidth: Int, imageHeight: Int): Double? {
        val q = Homography.orderCorners(quad)
        val n = zhangVectors(q, imageWidth, imageHeight) ?: return null
        val den = n[2] * n[5]
        if (den == 0.0) return null
        val f2 = -(n[0] * n[3] + n[1] * n[4]) / den
        return if (f2 > 0 && f2.isFinite()) sqrt(f2) else null
    }

    /**
     * Rapport largeur / hauteur réel du rectangle vu en perspective.
     * @param quad 4 coins dans l'image (ordre quelconque)
     * @param focalPx focale connue en pixels (EXIF), sinon null
     */
    fun estimateAspectRatio(quad: List<ImagePoint>, imageWidth: Int, imageHeight: Int, focalPx: Double? = null): AspectEstimate {
        val q = Homography.orderCorners(quad)
        require(Homography.isConvex(q)) { "Contour invalide : les 4 coins doivent former un quadrilatère convexe." }
        val long = max(imageWidth, imageHeight).toDouble()
        val deduced = stableDeducedFocal(q, imageWidth, imageHeight)
        val (f, source, uF) = when {
            deduced != null -> Triple(deduced.first, FocalSource.DEDUCED, deduced.second)
            focalPx != null && focalPx > 0 -> Triple(focalPx, FocalSource.PROVIDED, PROVIDED_FOCAL_UNCERTAINTY)
            else -> Triple(DEFAULT_FOCAL_FACTOR * long, FocalSource.DEFAULT, DEFAULT_FOCAL_UNCERTAINTY)
        }
        val n = zhangVectors(q, imageWidth, imageHeight)
        // Vue quasi frontale (parallélogramme) : tan(angle) < 0,035 sur les deux axes, la focale n'intervient plus.
        val frontal = n == null || (tilt(n, 0, f) < 0.035 && tilt(n, 3, f) < 0.035)
        val ratio = if (frontal) sidesRatio(q) else aspectFor(n!!, f)
        // Incertitude : pointé (perturbation déterministe des coins) et focale (± uF).
        val rnd = Random(SEED)
        val samples = DoubleArray(32) {
            val qp = q.map { ImagePoint(it.x + rnd.nextDouble(-POINTING_ERROR_PX, POINTING_ERROR_PX), it.y + rnd.nextDouble(-POINTING_ERROR_PX, POINTING_ERROR_PX)) }
            val np = zhangVectors(qp, imageWidth, imageHeight)
            if (frontal || np == null) sidesRatio(qp) else aspectFor(np, f)
        }
        val uCorner = std(samples) / ratio
        val uFocal = if (frontal) 0.0 else abs(aspectFor(n!!, f * (1 + uF)) - aspectFor(n, f / (1 + uF))) / (2 * ratio)
        val uRel = sqrt(uCorner * uCorner + uFocal * uFocal)
        val angle = if (n == null) 0.0 else viewAngle(n, f)
        return AspectEstimate(
            ratio = ratio,
            relativeUncertainty = uRel,
            focalPx = f,
            focalSource = if (frontal) FocalSource.NOT_NEEDED else source,
            frontal = frontal,
            viewAngleDeg = angle,
            reliability = (1 - uRel / 0.15).coerceIn(0.0, 1.0),
        )
    }

    /**
     * Taille de l'image redressée : assez fine pour le comptage (≈ 1,4 × le plus long bord horizontal de l'image,
     * au moins 480 px de large) sans dépasser [maxSide] px.
     */
    fun faceSize(quad: List<ImagePoint>, aspectRatio: Double, maxSide: Int = MAX_FACE_SIDE): FaceSize {
        require(aspectRatio > 0 && aspectRatio.isFinite()) { "Rapport d'aspect invalide" }
        val q = Homography.orderCorners(quad)
        val top = dist(q[0], q[1]); val bottom = dist(q[3], q[2])
        var w = (1.4 * max(top, bottom)).coerceIn(480.0, maxSide.toDouble())
        var h = w / aspectRatio
        if (h > maxSide) { h = maxSide.toDouble(); w = h * aspectRatio }
        return FaceSize(w.roundToInt().coerceAtLeast(16), h.roundToInt().coerceAtLeast(16))
    }

    /** Homographie image redressée (rectangle outWidth × outHeight) → photo. */
    fun faceToImage(quad: List<ImagePoint>, outWidth: Int, outHeight: Int): Homography? =
        Homography.rectToQuad(outWidth.toDouble(), outHeight.toDouble(), Homography.orderCorners(quad))

    /**
     * Image redressée de la façade délimitée par [quad] : échantillonnage bilinéaire de la photo à travers
     * l'homographie rectangle de sortie → quadrilatère (sur-échantillonnage 2×2 si la sortie réduit l'image).
     * Dans le résultat, des éléments de même largeur réelle sont équidistants.
     */
    fun rectify(image: GrayImage, quad: List<ImagePoint>, outWidth: Int, outHeight: Int): GrayImage {
        require(outWidth > 0 && outHeight > 0) { "Taille de sortie invalide" }
        val q = Homography.orderCorners(quad)
        val h = faceToImage(q, outWidth, outHeight)
            ?: throw IllegalArgumentException("Contour invalide : les 4 coins doivent former un quadrilatère convexe.")
        val m = h.m
        val shrink = max(max(dist(q[0], q[1]), dist(q[3], q[2])) / outWidth, max(dist(q[0], q[3]), dist(q[1], q[2])) / outHeight)
        val ss = if (shrink > 1.5) 2 else 1
        val inv = 1.0 / (ss * ss)
        val out = FloatArray(outWidth * outHeight)
        val src = image.pixels; val iw = image.width; val ih = image.height
        for (j in 0 until outHeight) {
            for (sy in 0 until ss) {
                val y = j + (sy + 0.5) / ss
                // Numérateurs et dénominateur affines en x le long de la ligne.
                val bx = m[1] * y + m[2]; val by = m[4] * y + m[5]; val bw = m[7] * y + m[8]
                for (i in 0 until outWidth) {
                    var acc = 0.0
                    for (sx in 0 until ss) {
                        val x = i + (sx + 0.5) / ss
                        val w = m[6] * x + bw
                        val fx = (m[0] * x + bx) / w - 0.5
                        val fy = (m[3] * x + by) / w - 0.5
                        acc += bilinear(src, iw, ih, fx, fy)
                    }
                    out[j * outWidth + i] += (acc * inv).toFloat()
                }
            }
        }
        return GrayImage(outWidth, outHeight, out)
    }

    // --- Calculs internes ---

    /** Échantillonnage bilinéaire (indices de pixels, bords répliqués). */
    internal fun bilinear(src: FloatArray, w: Int, h: Int, x: Double, y: Double): Double {
        if (!x.isFinite() || !y.isFinite()) return 0.0
        val xc = x.coerceIn(0.0, (w - 1).toDouble()); val yc = y.coerceIn(0.0, (h - 1).toDouble())
        val x0 = floor(xc).toInt().coerceAtMost(w - 2).coerceAtLeast(0)
        val y0 = floor(yc).toInt().coerceAtMost(h - 2).coerceAtLeast(0)
        val x1 = min(x0 + 1, w - 1); val y1 = min(y0 + 1, h - 1)
        val ax = xc - x0; val ay = yc - y0
        val p00 = src[y0 * w + x0]; val p10 = src[y0 * w + x1]; val p01 = src[y1 * w + x0]; val p11 = src[y1 * w + x1]
        return (p00 * (1 - ax) + p10 * ax) * (1 - ay) + (p01 * (1 - ax) + p11 * ax) * ay
    }

    /**
     * Vecteurs n2 = k2·m2 − m1 et n3 = k3·m3 − m1 de Zhang & He (coordonnées centrées sur l'image), concaténés
     * [n2x, n2y, n2z, n3x, n3y, n3z] ; m1 haut-gauche, m2 haut-droit, m3 bas-gauche, m4 bas-droit.
     */
    private fun zhangVectors(q: List<ImagePoint>, imageWidth: Int, imageHeight: Int): DoubleArray? {
        val cx = imageWidth / 2.0; val cy = imageHeight / 2.0
        val m1 = doubleArrayOf(q[0].x - cx, q[0].y - cy, 1.0)
        val m2 = doubleArrayOf(q[1].x - cx, q[1].y - cy, 1.0)
        val m4 = doubleArrayOf(q[2].x - cx, q[2].y - cy, 1.0)
        val m3 = doubleArrayOf(q[3].x - cx, q[3].y - cy, 1.0)
        val c14 = cross(m1, m4)
        val d2 = dot(cross(m2, m4), m3); val d3 = dot(cross(m3, m4), m2)
        if (abs(d2) < 1e-12 || abs(d3) < 1e-12) return null
        val k2 = dot(c14, m3) / d2
        val k3 = dot(c14, m2) / d3
        if (!k2.isFinite() || !k3.isFinite()) return null
        return doubleArrayOf(
            k2 * m2[0] - m1[0], k2 * m2[1] - m1[1], k2 * m2[2] - m1[2],
            k3 * m3[0] - m1[0], k3 * m3[1] - m1[1], k3 * m3[2] - m1[2],
        )
    }

    /** Rapport w/h pour une focale donnée : √((n2x² + n2y² + f²·n2z²) / (n3x² + n3y² + f²·n3z²)). */
    private fun aspectFor(n: DoubleArray, f: Double): Double {
        val a = n[0] * n[0] + n[1] * n[1] + f * f * n[2] * n[2]
        val b = n[3] * n[3] + n[4] * n[4] + f * f * n[5] * n[5]
        return sqrt(a / b)
    }

    /** Tangente de l'inclinaison de la direction n (offset 0 : horizontale, 3 : verticale) sur le plan image. */
    private fun tilt(n: DoubleArray, o: Int, f: Double): Double = abs(n[o + 2]) * f / hypot(n[o], n[o + 1]).coerceAtLeast(1e-9)

    /** Angle (degrés) entre l'axe optique et la normale à la façade. */
    private fun viewAngle(n: DoubleArray, f: Double): Double {
        val r1 = unit(doubleArrayOf(n[0] / f, n[1] / f, n[2]))
        val r2 = unit(doubleArrayOf(n[3] / f, n[4] / f, n[5]))
        val r3 = unit(cross(r1, r2))
        return Math.toDegrees(acos(abs(r3[2]).coerceIn(0.0, 1.0)))
    }

    /** Moyenne des côtés opposés (vue de face). */
    private fun sidesRatio(q: List<ImagePoint>): Double =
        (dist(q[0], q[1]) + dist(q[3], q[2])) / (dist(q[0], q[3]) + dist(q[1], q[2]))

    /**
     * Focale déduite seulement si elle est plausible (0,4 à 2,5 × grand côté) et stable : des perturbations
     * des coins de 1,5 % de leur écart moyen ne la font pas varier de plus de 15 %. Retourne (focale, incertitude).
     */
    private fun stableDeducedFocal(q: List<ImagePoint>, w: Int, h: Int): Pair<Double, Double>? {
        val long = max(w, h).toDouble()
        val f = focalFromQuad(q, w, h) ?: return null
        if (f < PLAUSIBLE_FOCAL_MIN * long || f > PLAUSIBLE_FOCAL_MAX * long) return null
        val side = (dist(q[0], q[1]) + dist(q[1], q[2]) + dist(q[2], q[3]) + dist(q[3], q[0])) / 4
        val delta = max(2.0, 0.015 * side)
        val rnd = Random(SEED + 1)
        var lo = f; var hi = f
        repeat(16) {
            val qp = q.map { ImagePoint(it.x + rnd.nextDouble(-delta, delta), it.y + rnd.nextDouble(-delta, delta)) }
            val fp = focalFromQuad(qp, w, h) ?: return null
            if (fp < PLAUSIBLE_FOCAL_MIN * long || fp > PLAUSIBLE_FOCAL_MAX * long) return null
            lo = min(lo, fp); hi = max(hi, fp)
        }
        val spread = (hi - lo) / (2 * f)
        return if (spread <= 0.15) f to max(PROVIDED_FOCAL_UNCERTAINTY, spread) else null
    }

    private fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
        a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0],
    )

    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    private fun unit(a: DoubleArray): DoubleArray {
        val n = sqrt(dot(a, a)).coerceAtLeast(1e-300)
        return doubleArrayOf(a[0] / n, a[1] / n, a[2] / n)
    }

    internal fun dist(a: ImagePoint, b: ImagePoint) = hypot(b.x - a.x, b.y - a.y)

    private fun std(x: DoubleArray): Double {
        val m = x.average()
        return sqrt(x.sumOf { (it - m) * (it - m) } / x.size)
    }
}
