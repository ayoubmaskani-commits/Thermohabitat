package fr.thermohabitat.thermal.vision

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Homographie plane (transformation projective) : p' ~ H·p en coordonnées homogènes.
 *
 * Relie un plan (façade du radiateur, objet de référence) et son image : une photo prise de biais
 * d'un rectangle donne un quadrilatère quelconque, l'homographie permet de « redresser » l'un vers l'autre.
 * Matrice 3×3 stockée ligne par ligne, normalisée (h33 = 1) quand c'est possible.
 */
class Homography(coefficients: DoubleArray) {

    /** Coefficients ligne par ligne (copie défensive). */
    val m: DoubleArray = coefficients.copyOf()

    init {
        require(m.size == 9) { "Une homographie a 9 coefficients" }
    }

    fun map(p: ImagePoint): ImagePoint = map(p.x, p.y)

    /** Image d'un point ; coordonnées infinies ou NaN si le point part à l'infini (ligne d'horizon). */
    fun map(x: Double, y: Double): ImagePoint {
        val w = m[6] * x + m[7] * y + m[8]
        return ImagePoint((m[0] * x + m[1] * y + m[2]) / w, (m[3] * x + m[4] * y + m[5]) / w)
    }

    /** Homographie réciproque (comatrice / déterminant), null si la matrice est singulière. */
    fun inverse(): Homography? {
        val a = m
        val c00 = a[4] * a[8] - a[5] * a[7]
        val c01 = a[5] * a[6] - a[3] * a[8]
        val c02 = a[3] * a[7] - a[4] * a[6]
        val det = a[0] * c00 + a[1] * c01 + a[2] * c02
        val scale = a.maxOf { abs(it) }
        if (scale == 0.0 || !det.isFinite() || abs(det) <= 1e-13 * scale * scale * scale) return null
        val inv = doubleArrayOf(
            c00, a[2] * a[7] - a[1] * a[8], a[1] * a[5] - a[2] * a[4],
            c01, a[0] * a[8] - a[2] * a[6], a[2] * a[3] - a[0] * a[5],
            c02, a[1] * a[6] - a[0] * a[7], a[0] * a[4] - a[1] * a[3],
        )
        for (i in 0 until 9) inv[i] /= det
        return Homography(normalized(inv))
    }

    /** Composition : (this × other)(p) = this(other(p)). */
    operator fun times(other: Homography): Homography {
        val a = m; val b = other.m
        val r = DoubleArray(9)
        for (i in 0 until 3) for (j in 0 until 3) {
            r[i * 3 + j] = a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j]
        }
        return Homography(normalized(r))
    }

    override fun toString(): String = "Homography(${m.joinToString()})"

    companion object {
        val IDENTITY = Homography(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))

        /**
         * Homographie envoyant src[i] sur dst[i] (4 correspondances), par la méthode DLT avec h33 = 1
         * (points normalisés à la Hartley, élimination de Gauss à pivot partiel).
         * Null si la configuration est dégénérée : 3 points alignés, quadrilatère croisé ou non convexe.
         */
        fun fromPoints(src: List<ImagePoint>, dst: List<ImagePoint>): Homography? {
            if (src.size != 4 || dst.size != 4) return null
            if (!isConvex(src) || !isConvex(dst)) return null
            val ts = normalization(src) ?: return null
            val td = normalization(dst) ?: return null
            val s = src.map { ts.map(it) }
            val d = dst.map { td.map(it) }
            val a = Array(8) { DoubleArray(9) }
            for (i in 0 until 4) {
                val x = s[i].x; val y = s[i].y; val u = d[i].x; val v = d[i].y
                a[2 * i] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -x * u, -y * u, u)
                a[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -x * v, -y * v, v)
            }
            val h = solve(a) ?: return null
            val hn = Homography(doubleArrayOf(h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7], 1.0))
            val tdInv = td.inverse() ?: return null
            val r = tdInv * hn * ts
            return if (r.m.all { it.isFinite() }) r else null
        }

        /** Homographie du rectangle [0, width] × [0, height] vers le quadrilatère (ordre haut-gauche, haut-droit, bas-droit, bas-gauche). */
        fun rectToQuad(width: Double, height: Double, quad: List<ImagePoint>): Homography? =
            fromPoints(rectangle(width, height), quad)

        /** Coins d'un rectangle dans l'ordre haut-gauche, haut-droit, bas-droit, bas-gauche. */
        fun rectangle(width: Double, height: Double): List<ImagePoint> =
            listOf(ImagePoint(0.0, 0.0), ImagePoint(width, 0.0), ImagePoint(width, height), ImagePoint(0.0, height))

        /**
         * Remet 4 points dans l'ordre [haut-gauche, haut-droit, bas-droit, bas-gauche] (sens horaire à l'écran,
         * y vers le bas), quel que soit l'ordre de pose. Le côté « haut » est celui dont le milieu est le plus haut.
         */
        fun orderCorners(points: List<ImagePoint>): List<ImagePoint> {
            require(points.size == 4) { "Il faut exactement 4 coins" }
            val cx = points.sumOf { it.x } / 4; val cy = points.sumOf { it.y } / 4
            val sorted = points.sortedBy { atan2(it.y - cy, it.x - cx) }
            val start = (0 until 4).minBy { i -> sorted[i].y + sorted[(i + 1) % 4].y }
            return List(4) { sorted[(start + it) % 4] }
        }

        /** Vrai si les 4 points, dans l'ordre donné, forment un quadrilatère strictement convexe (sens indifférent). */
        fun isConvex(quad: List<ImagePoint>): Boolean {
            if (quad.size != 4 || quad.any { !it.x.isFinite() || !it.y.isFinite() }) return false
            var scale = 0.0
            for (i in 0 until 4) {
                val p = quad[i]; val q = quad[(i + 1) % 4]
                scale = maxOf(scale, hypot(q.x - p.x, q.y - p.y))
            }
            if (scale == 0.0) return false
            val eps = 1e-9 * scale * scale
            var pos = 0; var neg = 0
            for (i in 0 until 4) {
                val p0 = quad[i]; val p1 = quad[(i + 1) % 4]; val p2 = quad[(i + 2) % 4]
                val z = (p1.x - p0.x) * (p2.y - p1.y) - (p1.y - p0.y) * (p2.x - p1.x)
                when {
                    z > eps -> pos++
                    z < -eps -> neg++
                    else -> return false
                }
            }
            return pos == 4 || neg == 4
        }

        /** Normalisation de Hartley : centroïde à l'origine, distance moyenne √2. */
        private fun normalization(pts: List<ImagePoint>): Homography? {
            val cx = pts.sumOf { it.x } / pts.size; val cy = pts.sumOf { it.y } / pts.size
            val md = pts.sumOf { hypot(it.x - cx, it.y - cy) } / pts.size
            if (!(md > 0.0) || !md.isFinite()) return null
            val s = sqrt(2.0) / md
            return Homography(doubleArrayOf(s, 0.0, -s * cx, 0.0, s, -s * cy, 0.0, 0.0, 1.0))
        }

        /** Résolution d'un système 8×8 (matrice augmentée 8×9), Gauss à pivot partiel ; null si singulier. */
        private fun solve(a: Array<DoubleArray>): DoubleArray? {
            val n = 8
            for (col in 0 until n) {
                var piv = col
                for (r in col + 1 until n) if (abs(a[r][col]) > abs(a[piv][col])) piv = r
                if (abs(a[piv][col]) < 1e-12) return null
                if (piv != col) { val t = a[piv]; a[piv] = a[col]; a[col] = t }
                for (r in col + 1 until n) {
                    val f = a[r][col] / a[col][col]
                    if (f == 0.0) continue
                    for (c in col until n + 1) a[r][c] -= f * a[col][c]
                }
            }
            val x = DoubleArray(n)
            for (r in n - 1 downTo 0) {
                var s = a[r][n]
                for (c in r + 1 until n) s -= a[r][c] * x[c]
                x[r] = s / a[r][r]
            }
            return if (x.all { it.isFinite() }) x else null
        }

        /** Ramène h33 à 1 quand il n'est pas quasi nul, sinon à une norme unitaire. */
        private fun normalized(h: DoubleArray): DoubleArray {
            val scale = h.maxOf { abs(it) }
            val d = if (abs(h[8]) > 1e-12 * scale) h[8] else scale
            return if (d == 0.0) h else DoubleArray(9) { h[it] / d }
        }
    }
}
