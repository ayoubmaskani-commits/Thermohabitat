package fr.thermohabitat.thermal.vision

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Haut et bas du corps détectés dans la face : droites y = a + b·x (px de la face analysée). */
internal class BodyRows(
    private val topA: Double,
    private val topB: Double,
    private val bottomA: Double,
    private val bottomB: Double,
    /** Écart quadratique moyen des points autour des droites (px). */
    val residualPx: Double,
) {
    fun top(x: Double) = topA + topB * x
    fun bottom(x: Double) = bottomA + bottomB * x

    /** Hauteur moyenne entre x0 et x1 (droites : moyenne des extrémités). */
    fun meanHeight(x0: Double, x1: Double) = ((bottom(x0) - top(x0)) + (bottom(x1) - top(x1))) / 2
}

/**
 * Haut et bas réels du corps du radiateur, près des coins pointés : transition de luminance la plus nette
 * (mur → moyeu) dans ±6 % de la hauteur, relevée sur 8 bandes verticales puis ajustée par une droite
 * (les coins pointés peuvent être un peu décalés ou inclinés). Null si les transitions ne sont pas nettes.
 */
internal object VerticalExtent {

    private const val STRIPS = 8
    private const val SEARCH = 0.06

    /**
     * @param face image redressée comportant des marges au-dessus et au-dessous du contour
     * @param columns abscisses du corps (bords détectés ou contour)
     * @param rows ordonnées du haut et du bas du contour pointé
     */
    fun detect(face: GrayImage, columns: ClosedFloatingPointRange<Double>, rows: ClosedFloatingPointRange<Double>, excluded: List<PixelRect>): BodyRows? {
        val w = face.width; val h = face.height
        val span = rows.endInclusive - rows.start
        val cw = columns.endInclusive - columns.start
        if (span < 40 || cw < 40) return null
        val sw = SEARCH * span
        val c0 = columns.start + 0.08 * cw; val c1 = columns.endInclusive - 0.08 * cw
        val tops = ArrayList<DoubleArray>(); val bottoms = ArrayList<DoubleArray>() // [x, y+, force+, y-, force-]
        for (k in 0 until STRIPS) {
            val xa = (c0 + (c1 - c0) * k / STRIPS).roundToInt().coerceIn(0, w - 1)
            val xb = (c0 + (c1 - c0) * (k + 1) / STRIPS).roundToInt().coerceIn(xa + 1, w)
            val xc = (xa + xb) / 2.0
            fun rowMean(y: Int): Double? {
                if (y < 0 || y >= h) return null
                var s = 0.0; var n = 0
                for (x in xa until xb) {
                    if (excluded.any { x + 0.5 >= it.left && x + 0.5 <= it.right && y + 0.5 >= it.top && y + 0.5 <= it.bottom }) continue
                    s += face[x, y]; n++
                }
                return if (n > 0) s / n else null
            }
            fun edge(center: Double): DoubleArray? {
                val ya = (center - sw).roundToInt() - 1; val yb = (center + sw).roundToInt() + 1
                if (ya < 1 || yb > h - 2) return null
                val l = DoubleArray(yb - ya + 3)
                for (i in l.indices) l[i] = rowMean(ya - 1 + i) ?: return null
                val g = DoubleArray(l.size) { i -> if (i == 0 || i == l.size - 1) 0.0 else (l[i + 1] - l[i - 1]) / 2 }
                // Meilleure transition de chaque signe, pondérée par la proximité du coin pointé.
                var bp = -1; var bn = -1; var vp = 0.0; var vn = 0.0
                for (i in 2 until g.size - 2) {
                    val y = ya - 1 + i
                    val wgt = exp(-((y - center) / (0.04 * span)).let { it * it } / 2)
                    if (g[i] * wgt > vp) { vp = g[i] * wgt; bp = i }
                    if (-g[i] * wgt > vn) { vn = -g[i] * wgt; bn = i }
                }
                val med = g.map { abs(it) }.sorted()[g.size / 2].coerceAtLeast(0.3)
                fun refine(i: Int): Double {
                    if (i <= 0 || i >= g.size - 1) return (ya - 1 + i).toDouble()
                    val a = abs(g[i - 1]); val b = abs(g[i]); val c = abs(g[i + 1])
                    val d = a - 2 * b + c
                    val off = if (abs(d) < 1e-9) 0.0 else (0.5 * (a - c) / d).coerceIn(-0.5, 0.5)
                    return ya - 1 + i + off
                }
                return doubleArrayOf(
                    xc,
                    if (bp >= 0) refine(bp) else Double.NaN, vp / med,
                    if (bn >= 0) refine(bn) else Double.NaN, vn / med,
                )
            }
            edge(rows.start)?.let { tops += it }
            edge(rows.endInclusive)?.let { bottoms += it }
        }
        val top = fitEdge(tops) ?: return null
        val bottom = fitEdge(bottoms) ?: return null
        val res = sqrt((top.third * top.third + bottom.third * bottom.third) / 2)
        if (res > 0.01 * span + 1.5) return null
        return BodyRows(top.first, top.second, bottom.first, bottom.second, res)
    }

    /** Signe dominant des transitions, droite robuste ; null si moins de 4 bandes nettes. Retourne (a, b, rms). */
    private fun fitEdge(pts: List<DoubleArray>): Triple<Double, Double, Double>? {
        if (pts.size < 4) return null
        val pos = pts.sumOf { if (it[2].isFinite()) it[2] else 0.0 }; val neg = pts.sumOf { if (it[4].isFinite()) it[4] else 0.0 }
        val (iy, iforce) = if (pos >= neg) 1 to 2 else 3 to 4
        var sel = pts.filter { it[iy].isFinite() && it[iforce] >= 4.0 }
        if (sel.size < 4) return null
        var fit = Triple(0.0, 0.0, 0.0)
        repeat(3) {
            val x = sel.map { it[0] }; val y = sel.map { it[iy] }
            val xm = x.average(); val ym = y.average()
            var sxy = 0.0; var sxx = 0.0
            for (i in x.indices) { sxy += (x[i] - xm) * (y[i] - ym); sxx += (x[i] - xm) * (x[i] - xm) }
            val b = if (sxx > 1e-9) sxy / sxx else 0.0
            val a = ym - b * xm
            val r = sel.map { it[iy] - (a + b * it[0]) }
            val rms = sqrt(r.sumOf { it * it } / r.size)
            fit = Triple(a, b, rms)
            val lim = max(1.5, 2.5 * rms)
            val kept = sel.filterIndexed { i, _ -> abs(r[i]) <= lim }
            if (kept.size < 4) return null
            if (kept.size == sel.size) return if (abs(fit.second) < 0.05) fit else null
            sel = kept
        }
        return if (abs(fit.second) < 0.05) fit else null
    }
}
