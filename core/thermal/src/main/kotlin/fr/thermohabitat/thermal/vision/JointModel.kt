package fr.thermohabitat.thermal.vision

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Loi projective des joints d'un radiateur à éléments dans la face redressée (px).
 *
 * Les éléments étant identiques, le joint d'indice k coupe la rangée y de la face en
 *   x = U(k) + (a + b·U(k))·(y − y₀),  avec U(k) = (α·k + β) / (γ·k + 1),
 * U étant la loi des joints sur la rangée de référence y₀ et a + b·U le dévers du joint (droites concourantes).
 * Ces 5 paramètres équivalent exactement à un indice k(x, y) rapport de deux fonctions affines de (x, y) : le modèle
 * absorbe la perspective résiduelle d'un redressement imparfait (coin caché mal placé) sans supposer les joints
 * équidistants ni verticaux. Indices réels ; dans un résultat de comptage, 0 est le bord gauche du radiateur et le
 * nombre d'éléments son bord droit.
 */
class JointModel internal constructor(
    private val alpha: Double,
    private val beta: Double,
    private val gamma: Double,
    private val a: Double,
    private val b: Double,
    private val y0: Double,
    private val offsetX: Double = 0.0,
    private val offsetY: Double = 0.0,
    private val offsetK: Double = 0.0,
) {
    /** Rangée de référence de la loi (px). */
    val referenceRow: Double get() = y0 + offsetY

    /** Abscisse (px) du joint d'indice [k] sur la rangée [y]. */
    fun positionAt(k: Double, y: Double): Double {
        val u = law(k + offsetK)
        return u + (a + b * u) * (y - offsetY - y0) + offsetX
    }

    /** Indice (réel) du joint passant par le point (x, y) ; NaN au point de fuite de la loi. */
    fun indexAt(x: Double, y: Double): Double {
        val t = y - offsetY - y0
        val u = (x - offsetX - a * t) / (1 + b * t)
        val den = alpha - gamma * u
        return if (abs(den) < 1e-12) Double.NaN else (u - beta) / den - offsetK
    }

    /** Largeur (px) de l'élément compris entre les joints [k] et k + 1, sur la rangée [y]. */
    fun pitchAt(k: Double, y: Double): Double = positionAt(k + 1, y) - positionAt(k, y)

    internal fun law(k: Double): Double = (alpha * k + beta) / (gamma * k + 1)

    /** Même loi exprimée dans un repère translaté de (dx, dy). */
    internal fun shifted(dx: Double, dy: Double) = JointModel(alpha, beta, gamma, a, b, y0, offsetX + dx, offsetY + dy, offsetK)

    /** Même loi, l'indice [k] devenant 0. */
    internal fun reindexed(k: Double) = JointModel(alpha, beta, gamma, a, b, y0, offsetX, offsetY, offsetK + k)

    override fun toString(): String =
        "JointModel(α=$alpha, β=$beta, γ=$gamma, a=$a, b=$b, y0=$y0, décalages=($offsetX, $offsetY, $offsetK))"
}

/** Joint candidat relevé dans une bande : abscisse, ordonnée de la bande, poids (netteté × qualité de la bande). */
internal class JointCandidate(val x: Double, val y: Double, val weight: Double, val band: Int)

/**
 * Signal « joint » d'une bande pour le score dense : positif sur les joints, négatif entre eux (moyenne nulle), réduit
 * par l'intensité typique d'un joint et plafonné ; [weight] : qualité de la bande ; [x0], [x1] : contour.
 */
internal class DenseBand(val y: Double, val weight: Double, val signal: DoubleArray, val x0: Double, val x1: Double)

/**
 * Ajustement de la loi : modèle (origine des indices arbitraire), joints retenus avec leur indice, écart moyen (pas) et
 * score dense.
 */
internal class JointFit(val model: JointModel, val inliers: List<Pair<JointCandidate, Int>>, val rms: Double, val dense: Double)

/**
 * Ajustement robuste de [JointModel] sur des joints candidats (dont de faux joints : tiges, bords d'objets) :
 * dévers initial par appariement des joints d'une bande à l'autre, puis tirages aléatoires déterministes de 3
 * candidats (indices déduits des écarts et du pas spectral, tirages stratifiés sur la largeur), loi plausible (pas
 * monotone, rapport des pas extrêmes borné), score dense (signal « joint » aux joints prédits) et score des candidats
 * (au plus un joint par indice et par bande, rendement décroissant d'une bande à l'autre), enfin moindres carrés
 * alternés loi / dévers pour chacune des meilleures lois distinctes.
 */
internal object JointLawFit {

    private const val HYPOTHESES = 600
    /** Tolérance finale (fraction de pas) d'un joint autour de la loi. */
    const val TOLERANCE = 0.16

    /**
     * Lois candidates, de la meilleure à la moins bonne au sens du score dense : les meilleures hypothèses distinctes
     * (au plus [keep]) sont chacune affinées. Le choix final peut ainsi tenir compte d'autres critères (ressemblance des
     * éléments entre eux), quand des tiges ou des bords d'objets rendent deux lois presque aussi plausibles.
     *
     * @param period pas moyen attendu (px), issu de l'analyse spectrale
     * @param yRef rangée de référence de la loi
     * @param span abscisses du contour (pas moyen contrôlé sur cette largeur)
     * @param maxPitchRatio rapport maximal des pas aux deux extrémités du contour (perspective résiduelle)
     */
    fun fit(
        cands: List<JointCandidate>,
        dense: List<DenseBand>,
        period: Double,
        yRef: Double,
        span: ClosedFloatingPointRange<Double>,
        maxPitchRatio: Double = 1.8,
        seed: Int = 4_711,
        keep: Int = 5,
    ): List<JointFit> {
        if (cands.size < 4 || period <= 0) return emptyList()
        val c = cands.sortedWith(compareBy({ it.band }, { it.x }))
        val (a0, b0) = shearFromBands(c, period)
        val u = DoubleArray(c.size) { i -> val t = c[i].y - yRef; (c[i].x - a0 * t) / (1 + b0 * t) }

        // Tirages : 3 candidats pondérés, indices = écarts / pas arrondis (et l'arrondi voisin si l'écart est ambigu).
        val rnd = Random(seed)
        val cum = DoubleArray(c.size)
        var acc = 0.0
        for (i in c.indices) { acc += c[i].weight; cum[i] = acc }
        if (acc <= 0) return emptyList()
        fun pick(): Int {
            val r = rnd.nextDouble() * acc
            var lo = 0; var hi = c.size - 1
            while (lo < hi) { val mid = (lo + hi) / 2; if (cum[mid] < r) lo = mid + 1 else hi = mid }
            return lo
        }
        // Tirages stratifiés (un candidat par tiers du contour) : la courbure de la loi (perspective résiduelle) n'est
        // déterminée que par des joints éloignés, même quand un côté, en partie masqué, a peu de candidats.
        val third = (span.endInclusive - span.start) / 3
        val strata = (0 until 3).map { s ->
            val lo = span.start + s * third - (if (s == 0) period else 0.0)
            val hi = span.start + (s + 1) * third + (if (s == 2) period else 0.0)
            c.indices.filter { c[it].x >= lo && c[it].x < hi }
        }
        fun pickIn(ids: List<Int>): Int {
            val tot = ids.sumOf { c[it].weight }
            var r = rnd.nextDouble() * tot
            for (i in ids) { r -= c[i].weight; if (r <= 0) return i }
            return ids.last()
        }
        val looseTol = 1.4 * TOLERANCE
        val kBest = DoubleArray(512); val kSum = DoubleArray(512)
        // Meilleures hypothèses distinctes (positions différant d'au moins un quart de pas quelque part sur le contour).
        val top = ArrayList<Pair<Law, Double>>()
        val probes = listOf(span.start, (span.start + span.endInclusive) / 2, span.endInclusive)
        fun distinct(l1: Law, l2: Law): Boolean {
            val ref = l1.index(probes[0])
            if (!ref.isFinite()) return true
            val off = (l2.index(probes[0]) - ref).roundToInt()
            return probes.any { x -> abs(l2.index(x) - off - l1.index(x)) > 0.25 }
        }
        fun offer(law: Law, s: Double) {
            val same = top.indexOfFirst { !distinct(it.first, law) }
            if (same >= 0) { if (s > top[same].second) top[same] = law to s else return }
            else if (top.size < keep) top += law to s
            else if (s > top.last().second) top[top.size - 1] = law to s
            else return
            top.sortByDescending { it.second }
        }
        repeat(HYPOTHESES) { h ->
            val stratified = h % 2 == 1 && strata.all { it.isNotEmpty() }
            val p = (if (stratified) strata.map { pickIn(it) } else listOf(pick(), pick(), pick())).map { u[it] }.sorted()
            val g1 = p[1] - p[0]; val g2 = p[2] - p[1]
            if (g1 < 0.6 * period || g2 < 0.6 * period || p[2] - p[0] < 2.5 * period) return@repeat
            for (n1 in roundings(g1 / period)) for (n2 in roundings(g2 / period)) {
                val law = Law.through(p[0], p[1], p[2], n1, n2) ?: continue
                val ratio = pitchRatio(law, span, period) ?: continue
                if (!plausible(law, span, period, maxPitchRatio)) continue
                // Score dense (signal « joint » lu sur tous les joints prédits, vus ou non) et, à égalité, score des
                // candidats ; légère préférence pour une loi peu projective (perspective résiduelle modérée en pratique).
                val s = (denseScore(dense, law, a0, b0, yRef) + 0.25 * score(c, u, law, looseTol, kBest, kSum)) *
                    (1 - 0.05 * (ln(ratio) / ln(maxPitchRatio)).let { it * it })
                offer(law, s)
            }
        }
        return top.mapNotNull { (law, _) -> refine(c, dense, law, a0, b0, yRef, span, period, maxPitchRatio) }
            .sortedByDescending { it.dense }
    }

    /** Affinage d'une loi : moindres carrés alternés (loi sur la rangée de référence, puis dévers), joints réattribués. */
    private fun refine(
        c: List<JointCandidate>, dense: List<DenseBand>, start: Law, a0: Double, b0: Double, yRef: Double,
        span: ClosedFloatingPointRange<Double>, period: Double, maxPitchRatio: Double,
    ): JointFit? {
        var law = start; var a = a0; var b = b0
        val u = DoubleArray(c.size)
        fun refresh() { for (i in c.indices) { val t = c[i].y - yRef; u[i] = (c[i].x - a * t) / (1 + b * t) } }
        refresh()
        var inl: List<Pair<Int, Int>>
        for (iter in 0 until 6) {
            inl = assign(c, u, law, if (iter < 2) 1.5 * TOLERANCE else TOLERANCE)
            if (inl.map { it.second }.distinct().size < 3) return null
            law = Law.leastSquares(inl.map { (i, k) -> Triple(k.toDouble(), u[i], c[i].weight) }, law) ?: return null
            if (!plausible(law, span, period, maxPitchRatio * 1.1)) return null
            val sh = shearLeastSquares(c, inl, law, yRef, a, b)
            a = sh.first; b = sh.second
            refresh()
        }
        inl = assign(c, u, law, TOLERANCE)
        if (inl.map { it.second }.distinct().size < 3) return null
        var sw = 0.0; var sr = 0.0
        for ((i, k) in inl) { val r = law.index(u[i]) - k; sw += c[i].weight; sr += c[i].weight * r * r }
        val model = JointModel(law.alpha, law.beta, law.gamma, a, b, yRef)
        return JointFit(model, inl.map { (i, k) -> c[i] to k }, sqrt(sr / sw.coerceAtLeast(1e-12)), denseScore(dense, law, a, b, yRef))
    }

    /** Loi U(k) = (α·k + β) / (γ·k + 1) sur la rangée de référence. */
    private class Law(val alpha: Double, val beta: Double, val gamma: Double) {
        fun index(u: Double): Double { val d = alpha - gamma * u; return if (abs(d) < 1e-12) Double.NaN else (u - beta) / d }
        fun pos(k: Double) = (alpha * k + beta) / (gamma * k + 1)

        /** Même loi quand l'indice 0 de celle-ci doit devenir l'indice [k0] : U(k) = loi(k − k0). */
        fun shifted(k0: Int): Law? {
            val d = 1 - gamma * k0
            if (abs(d) < 1e-12) return null
            return Law(alpha / d, (beta - alpha * k0) / d, gamma / d)
        }

        companion object {
            /** Loi passant par 3 joints d'indices 0, n1 et n1 + n2. */
            fun through(u0: Double, u1: Double, u2: Double, n1: Int, n2: Int): Law? {
                val k1 = n1.toDouble(); val k2 = (n1 + n2).toDouble()
                // α·k − γ·k·u = u − u0 (β = u0) pour k1 et k2.
                val s1 = (u1 - u0) / k1; val s2 = (u2 - u0) / k2
                if (abs(u2 - u1) < 1e-9) return null
                val gamma = (s1 - s2) / (u2 - u1)
                val alpha = s1 + gamma * u1
                return if (alpha.isFinite() && gamma.isFinite()) Law(alpha, u0, gamma) else null
            }

            /**
             * Moindres carrés pondérés sur (k, u, poids), coordonnées normalisées, écart pondéré par 1 / (γ·k + 1)
             * (loi précédente) pour approcher l'écart géométrique.
             */
            fun leastSquares(pts: List<Triple<Double, Double, Double>>, prev: Law): Law? {
                if (pts.size < 3) return null
                val sw = pts.sumOf { it.third }
                if (sw <= 0) return null
                val km = pts.sumOf { it.first * it.third } / sw
                val um = pts.sumOf { it.second * it.third } / sw
                val ks = sqrt(pts.sumOf { (it.first - km) * (it.first - km) * it.third } / sw).coerceAtLeast(1e-6)
                val us = sqrt(pts.sumOf { (it.second - um) * (it.second - um) * it.third } / sw).coerceAtLeast(1e-6)
                // α̃·k̃ + β̃ − γ̃·k̃·ũ = ũ
                val m = Array(3) { DoubleArray(4) }
                for ((k, uu, wt) in pts) {
                    val d = prev.gamma * k + 1
                    val ww = wt / (d * d).coerceAtLeast(1e-6)
                    val kt = (k - km) / ks; val ut = (uu - um) / us
                    val row = doubleArrayOf(kt, 1.0, -kt * ut)
                    for (r in 0 until 3) { for (q in 0 until 3) m[r][q] += ww * row[r] * row[q]; m[r][3] += ww * row[r] * ut }
                }
                val s = solve3(m) ?: return null
                val (at, bt, gt) = Triple(s[0], s[1], s[2])
                // Retour aux coordonnées d'origine.
                val d0 = 1 - gt * km / ks
                if (abs(d0) < 1e-9) return null
                val gamma = gt / (ks * d0)
                val alpha = (um * gt + us * at) / (ks * d0)
                val beta = (um * d0 + us * (bt - at * km / ks)) / d0
                return if (alpha.isFinite() && beta.isFinite() && gamma.isFinite()) Law(alpha, beta, gamma) else null
            }
        }
    }

    private fun roundings(r: Double): List<Int> {
        val n = r.roundToInt()
        val out = ArrayList<Int>(2)
        if (n >= 1) out += n
        if (abs(r - n) > 0.25) { val m = if (r > n) n + 1 else n - 1; if (m >= 1) out += m }
        return out
    }

    /**
     * Score dense : somme pondérée, sur les bandes, du signal « joint » aux positions prédites de tous les joints du
     * contour, diminué du signal à mi-chemin (milieu des éléments, pénalité plafonnée). Une loi décalée d'un demi-pas
     * (sur des rainures ou des reflets) est défavorisée ; une loi de pas moitié alterne joints et creux (≈ 0). Le score
     * départage les lois que les seuls pics détectés (rares sous les objets) laissent ambiguës.
     */
    private fun denseScore(dense: List<DenseBand>, law: Law, a: Double, b: Double, yRef: Double): Double {
        // Par indice de joint : meilleure bande + 10 % des autres (une tige haute ou un bord d'objet vu dans beaucoup de
        // bandes ne l'emporte pas sur des joints vus dans peu de bandes, comme sous un objet posé devant).
        val best = HashMap<Int, Double>(); val sum = HashMap<Int, Double>()
        for (d in dense) {
            val t = d.y - yRef
            val k0 = law.index((d.x0 - a * t) / (1 + b * t)); val k1 = law.index((d.x1 - a * t) / (1 + b * t))
            if (!k0.isFinite() || !k1.isFinite() || k1 <= k0) continue
            val n = d.signal.size
            fun at(kk: Double): Double {
                val uk = law.pos(kk)
                val x = uk + (a + b * uk) * t
                if (!(x >= 0 && x <= n - 1)) return 0.0
                val i = x.toInt().coerceAtMost(n - 2); val f = x - i
                return d.signal[i] * (1 - f) + d.signal[i + 1] * f
            }
            val last = floor(k1).toInt()
            for (k in ceil(k0).toInt()..last) {
                // Pénalité plafonnée : une tige ou un bord d'objet tombant à mi-chemin ne doit pas exclure la bonne loi.
                val v = d.weight * (at(k.toDouble()) - if (k < last) min(at(k + 0.5), 0.5) else 0.0)
                sum[k] = (sum[k] ?: 0.0) + v
                if (v > (best[k] ?: Double.NEGATIVE_INFINITY)) best[k] = v
            }
        }
        var total = 0.0
        for ((k, bk) in best) total += bk + 0.1 * (sum.getValue(k) - bk)
        return total
    }

    /** Rapport des pas extrêmes (≥ 1) sur le contour élargi d'un pas, null si la loi n'y est pas définie et croissante. */
    private fun pitchRatio(law: Law, span: ClosedFloatingPointRange<Double>, period: Double): Double? {
        val kA = law.index(span.start - period); val kB = law.index(span.endInclusive + period)
        if (!kA.isFinite() || !kB.isFinite() || kB <= kA) return null
        val dA = law.gamma * kA + 1; val dB = law.gamma * kB + 1
        if (dA <= 0 || dB <= 0) return null
        val r = max(dA, dB) / min(dA, dB)
        return r * r
    }

    /** Loi croissante sur le contour élargi d'un pas, pas moyen proche du pas spectral, perspective résiduelle bornée. */
    private fun plausible(law: Law, span: ClosedFloatingPointRange<Double>, period: Double, maxRatio: Double): Boolean {
        val kA = law.index(span.start - period); val kB = law.index(span.endInclusive + period)
        if (!kA.isFinite() || !kB.isFinite() || kB <= kA) return false
        val dA = law.gamma * kA + 1; val dB = law.gamma * kB + 1
        if (dA <= 0 || dB <= 0) return false
        val slope = law.alpha - law.beta * law.gamma
        if (slope <= 0) return false
        val ratio = max(dA, dB) / min(dA, dB)
        if (ratio * ratio > maxRatio) return false
        val k0 = law.index(span.start); val k1 = law.index(span.endInclusive)
        val mean = (span.endInclusive - span.start) / (k1 - k0)
        return mean in 0.7 * period..1.45 * period
    }

    /**
     * Score MSAC : poids × (1 − (écart / tolérance)²), au plus un joint par indice et par bande ; par indice, les
     * bandes s'ajoutent avec un rendement décroissant (meilleure bande + 30 % des autres) : une seule ligne vue dans
     * beaucoup de bandes (tige, bord d'objet) pèse moins que des joints distincts.
     */
    private fun score(c: List<JointCandidate>, u: DoubleArray, law: Law, tol: Double, kBest: DoubleArray, kSum: DoubleArray): Double {
        kBest.fill(0.0); kSum.fill(0.0)
        val off = kBest.size / 2
        var band = -1; var kCur = Int.MIN_VALUE; var cur = 0.0
        fun flush() {
            if (kCur == Int.MIN_VALUE) return
            val j = kCur + off
            if (j < 0 || j >= kBest.size) return
            kSum[j] += cur; if (cur > kBest[j]) kBest[j] = cur
        }
        for (i in c.indices) {
            val kh = law.index(u[i])
            if (!kh.isFinite()) continue
            val kr = kh.roundToInt(); val r = abs(kh - kr)
            if (r >= tol) continue
            val v = c[i].weight * (1 - (r / tol) * (r / tol))
            if (c[i].band == band && kr == kCur) {
                if (v > cur) cur = v
            } else { flush(); band = c[i].band; kCur = kr; cur = v }
        }
        flush()
        var total = 0.0
        for (j in kBest.indices) total += kBest[j] + 0.3 * (kSum[j] - kBest[j])
        return total
    }

    /** Joints retenus (indice du candidat, indice du joint) : le meilleur par indice et par bande. */
    private fun assign(c: List<JointCandidate>, u: DoubleArray, law: Law, tol: Double): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        var band = -1; var kCur = Int.MIN_VALUE; var cur = 0.0
        for (i in c.indices) {
            val kh = law.index(u[i])
            if (!kh.isFinite()) continue
            val kr = kh.roundToInt(); val r = abs(kh - kr)
            if (r >= tol) continue
            val v = c[i].weight * (1 - (r / tol) * (r / tol))
            if (c[i].band == band && kr == kCur) {
                if (v > cur) { out[out.size - 1] = i to kr; cur = v }
            } else { band = c[i].band; kCur = kr; cur = v; out += i to kr }
        }
        return out
    }

    /**
     * Dévers x − U(k) = (y − y₀)·(a + b·U(k)) par moindres carrés pondérés, rappelé vers la valeur précédente
     * (régularisation) quand les joints retenus couvrent peu de rangées.
     */
    private fun shearLeastSquares(c: List<JointCandidate>, inl: List<Pair<Int, Int>>, law: Law, yRef: Double, a0: Double, b0: Double): Pair<Double, Double> {
        if (inl.isEmpty()) return a0 to b0
        val um = inl.sumOf { law.pos(it.second.toDouble()) } / inl.size
        val us = sqrt(inl.sumOf { val d = law.pos(it.second.toDouble()) - um; d * d } / inl.size).coerceAtLeast(1.0)
        // Paramètres p = a + b·um, q = b·us : x − U = t·(p + q·Ũ).
        var s00 = 0.0; var s01 = 0.0; var s11 = 0.0; var r0 = 0.0; var r1 = 0.0
        for ((i, k) in inl) {
            val uk = law.pos(k.toDouble()); val t = c[i].y - yRef; val ut = (uk - um) / us
            val wt = c[i].weight
            val e = c[i].x - uk
            s00 += wt * t * t; s01 += wt * t * t * ut; s11 += wt * t * t * ut * ut
            r0 += wt * t * e; r1 += wt * t * ut * e
        }
        val p0 = a0 + b0 * um; val q0 = b0 * us
        val lambda = 1e-3 * (s00 + s11) + 1e-9
        val m00 = s00 + lambda; val m11 = s11 + lambda
        val det = m00 * m11 - s01 * s01
        if (abs(det) < 1e-12) return a0 to b0
        val rr0 = r0 + lambda * p0; val rr1 = r1 + lambda * q0
        val p = (rr0 * m11 - s01 * rr1) / det
        val q = (m00 * rr1 - s01 * rr0) / det
        val b = q / us
        val a = p - b * um
        return if (a.isFinite() && b.isFinite() && abs(a) < 0.5 && abs(b) < 1e-2) a to b else a0 to b0
    }

    /**
     * Dévers initial dx/dy = a + b·x : joints appariés d'une bande à l'autre (un seul candidat dans la tolérance),
     * droite robuste ; d'abord entre bandes proches, puis entre toutes les bandes avec la prédiction.
     */
    private fun shearFromBands(c: List<JointCandidate>, period: Double): Pair<Double, Double> {
        val byBand = c.groupBy { it.band }
        val keys = byBand.keys.sorted()
        if (keys.size < 2) return 0.0 to 0.0
        val ys = keys.map { k -> byBand.getValue(k).first().y }
        val gaps = ys.zipWithNext { p, q -> q - p }.sorted()
        val bandH = gaps[gaps.size / 2]
        var a = 0.0; var b = 0.0
        for (iter in 0 until 3) {
            val maxDv = if (iter == 0) 2.5 * bandH else Double.MAX_VALUE
            val tol = (if (iter == 0) 0.3 else 0.2) * period
            val xs = ArrayList<Double>(); val ss = ArrayList<Double>(); val ws = ArrayList<Double>()
            for (i in keys.indices) for (j in i + 1 until keys.size) {
                val dv = ys[j] - ys[i]
                if (dv > maxDv || dv <= 0) continue
                val lower = byBand.getValue(keys[j])
                for (p in byBand.getValue(keys[i])) {
                    val pred = p.x + (a + b * p.x) * dv
                    var match: JointCandidate? = null; var cnt = 0
                    for (q in lower) if (abs(q.x - pred) < tol) { match = q; cnt++ }
                    if (cnt != 1) continue
                    xs += (p.x + match!!.x) / 2; ss += (match.x - p.x) / dv; ws += min(p.weight, match.weight) * dv
                }
            }
            if (xs.size < 8) return if (iter == 0) 0.0 to 0.0 else a to b
            val fit = robustLine(xs, ss, ws) ?: return a to b
            a = fit.first; b = fit.second
        }
        return a to b
    }

    /** Droite pondérée y = a + b·x avec rejet itératif des écarts > 2,5 σ (σ robuste). */
    private fun robustLine(x: List<Double>, y: List<Double>, w: List<Double>): Pair<Double, Double>? {
        var keep = x.indices.toList()
        var fit: Pair<Double, Double>? = null
        repeat(4) {
            val sw = keep.sumOf { w[it] }
            if (sw <= 0 || keep.size < 4) return fit
            val xm = keep.sumOf { w[it] * x[it] } / sw; val ym = keep.sumOf { w[it] * y[it] } / sw
            var sxy = 0.0; var sxx = 0.0
            for (i in keep) { sxy += w[i] * (x[i] - xm) * (y[i] - ym); sxx += w[i] * (x[i] - xm) * (x[i] - xm) }
            val b = if (sxx > 1e-9) sxy / sxx else 0.0
            val a = ym - b * xm
            fit = a to b
            val res = keep.map { abs(y[it] - (a + b * x[it])) }
            val sigma = (1.4826 * res.sorted()[res.size / 2]).coerceAtLeast(0.004)
            keep = keep.filterIndexed { idx, _ -> res[idx] <= 2.5 * sigma }
        }
        return fit
    }

    /** Système 3×3 (matrice augmentée), Gauss à pivot partiel. */
    private fun solve3(m: Array<DoubleArray>): DoubleArray? {
        for (col in 0 until 3) {
            var piv = col
            for (r in col + 1 until 3) if (abs(m[r][col]) > abs(m[piv][col])) piv = r
            if (abs(m[piv][col]) < 1e-12) return null
            val t = m[piv]; m[piv] = m[col]; m[col] = t
            for (r in col + 1 until 3) { val f = m[r][col] / m[col][col]; for (q in col until 4) m[r][q] -= f * m[col][q] }
        }
        val x = DoubleArray(3)
        for (r in 2 downTo 0) { var s = m[r][3]; for (q in r + 1 until 3) s -= m[r][q] * x[q]; x[r] = s / m[r][r] }
        return if (x.all { it.isFinite() }) x else null
    }
}
