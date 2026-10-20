package fr.thermohabitat.thermal.vision

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Comptage des éléments sur une façade REDRESSÉE (éléments équidistants), sans apprentissage :
 *
 * 1. profils par colonne sur la bande centrale (15–85 % de la hauteur, sans moyeux ni pieds) : luminance et
 *    énergie du gradient horizontal |∂I/∂x| (joints, bords de colonnes), tendance quadratique retirée ;
 * 2. recherche spectrale du nombre de cycles N sur la largeur (fréquences non entières, pas de 0,05, affinage) ;
 * 3. harmoniques : candidats N, N/2, N/3, 2N notés par leur fondamental et leurs harmoniques, pondérés par un
 *    a priori de pas ; les candidats proches sont rendus comme ALTERNATIVES ;
 * 4. second estimateur : détection des joints (maxima espacés d'au moins 0,6 période), comblement des joints
 *    manquants, examen des bords (mur ou élément entamé ?) → nombre = joints + 1 ;
 * 5. confiance : netteté spectrale, régularité des joints, accord des deux estimateurs, ambiguïtés.
 */
internal object RectifiedCounting {

    /** Rapport pas / hauteur plausible des radiateurs à éléments (a priori sans échelle métrique). */
    val DEFAULT_PITCH_TO_HEIGHT = 0.04..0.25

    private const val BAND_TOP = 0.15
    private const val BAND_BOTTOM = 0.85
    private const val NU_STEP = 0.05
    private const val MIN_PROMINENCE = 15.0
    private const val FULL_PROMINENCE = 150.0
    private const val TEMPLATE_SAMPLES = 24
    private const val JOINT_MARGIN = 0.12
    private val MULTIPLES = doubleArrayOf(1.0, 2.0, 0.5, 1.0 / 3.0)

    internal class Profiles(val lum: DoubleArray, val grad: DoubleArray)

    private class Candidate(val nu: Double, val fundamental: Double, val evidence: Double, val prior: Double) {
        val score: Double get() = evidence * prior
    }

    private enum class Verdict { ELEMENT, WALL, UNSURE }

    /** Doute sur un bord : trait conservé qui est peut-être le bord (−1) ou trait écarté qui est peut-être un joint (+1). */
    private enum class BorderDoubt(val delta: Int) { MAYBE_EDGE(-1), MAYBE_ELEMENT(+1) }

    private class JointResult(
        val joints: List<Double>,
        val interpolated: Int,
        val start: Double,
        val end: Double,
        val gapCv: Double,
        val left: BorderDoubt?,
        val right: BorderDoubt?,
    ) {
        val count: Int get() = joints.size + 1
        val period: Double get() = (end - start) / count
    }

    fun count(
        face: GrayImage,
        pitchPriorPx: ClosedFloatingPointRange<Double>?,
        minElements: Int,
        maxElements: Int,
        excluded: List<PixelRect>,
    ): ElementCountResult {
        val w = face.width; val h = face.height
        if (w < 60 || h < 16) return empty("Image redressée trop petite pour l'analyse.")
        val prof = profiles(face, excluded)
        val a = detrendQuadratic(prof.lum); val b = detrendQuadratic(prof.grad)
        if (std(a) < 0.05 && std(b) < 0.05) return empty("Image uniforme : aucun motif détecté.")
        val spec = Spectrum(a, b)
        val nuLo = max(1.5, minElements - 0.6)
        val nuHi = min(maxElements * 1.1 + 1.0, w / 5.0)
        if (nuHi <= nuLo + 0.5) return empty("Plage de recherche invalide.")
        val grid = ArrayList<Double>()
        run { var nu = nuLo; while (nu <= nuHi) { grid.add(nu); nu += NU_STEP } }
        val values = DoubleArray(grid.size) { spec.at(grid[it]) }
        val floor = median(values.toList()).coerceAtLeast(1e-12)
        val peaks = grid.indices.filter { i ->
            (i == 0 || values[i] > values[i - 1]) && (i == grid.lastIndex || values[i] >= values[i + 1]) && values[i] > 3 * floor
        }.sortedByDescending { values[it] }.take(6)
        if (peaks.isEmpty()) return empty("Aucune périodicité nette : comptez les éléments manuellement.")

        val prior = pitchPriorPx ?: (DEFAULT_PITCH_TO_HEIGHT.start * h)..(DEFAULT_PITCH_TO_HEIGHT.endInclusive * h)
        val harmonicMax = w / 2.5
        val candidates = ArrayList<Candidate>()
        for (i in peaks) for (mult in MULTIPLES) {
            val target = grid[i] * mult
            if (target < nuLo - 0.2 || target > nuHi + 0.2) continue
            val (nuF, f) = spec.localMax(target, 0.35, nuLo - 0.3, nuHi + 0.3)
            if (f < 4 * floor) continue
            val h2 = if (2 * nuF <= harmonicMax) spec.localMax(2 * nuF, 0.3, 0.5, w / 2.2).second else 0.0
            val h3 = if (3 * nuF <= harmonicMax) spec.localMax(3 * nuF, 0.3, 0.5, w / 2.2).second else 0.0
            val c = Candidate(nuF, f, f + 0.5 * h2 + 0.25 * h3, priorWeight(w / nuF, prior))
            val dup = candidates.indexOfFirst { abs(it.nu - nuF) < 0.6 }
            if (dup < 0) candidates.add(c) else if (c.score > candidates[dup].score) candidates[dup] = c
        }
        if (candidates.isEmpty()) return empty("Aucune périodicité nette : comptez les éléments manuellement.")
        candidates.sortByDescending { it.score }
        val best = candidates[0]
        val prominence = best.fundamental / floor
        val qSpec = ((ln(prominence) - ln(MIN_PROMINENCE)) / (ln(FULL_PROMINENCE) - ln(MIN_PROMINENCE))).coerceIn(0.0, 1.0)
        if (qSpec <= 0.0 || best.fundamental < 0.04) {
            return ElementCountResult(null, w / best.nu, 0.0, emptyList(),
                "Motif trop faible : comptez les éléments manuellement.", spectralCycles = best.nu)
        }

        val jr = analyseJoints(prof, w / best.nu)
        if (jr == null || jr.count < 2) {
            return ElementCountResult(null, w / best.nu, 0.0, emptyList(),
                "Joints entre éléments introuvables : comptez les éléments manuellement.", spectralCycles = best.nu)
        }
        val count = jr.count

        // Alternatives : autres périodicités (harmoniques) puis bords douteux.
        val alternatives = ArrayList<CountAlternative>()
        for (c in candidates.drop(1)) {
            val rel = c.score / best.score
            if (rel < 0.25 || alternatives.size >= 3) break
            val alt = analyseJoints(prof, w / c.nu)?.count ?: c.nu.roundToInt()
            if (alt == count || alt < 2 || alternatives.any { it.count == alt }) continue
            val ratio = c.nu / best.nu
            val reason = when {
                abs(ratio - 2) < 0.25 -> "Motif deux fois plus fin également présent : $alt éléments si chaque bande étroite est un élément."
                abs(ratio - 0.5) < 0.08 -> "Motif deux fois plus large également présent : $alt éléments si deux bandes forment un seul élément."
                abs(ratio - 3) < 0.35 || abs(ratio - 1.0 / 3) < 0.05 -> "Harmonique du motif : $alt éléments possibles."
                else -> "Autre périodicité détectée : $alt éléments."
            }
            alternatives.add(CountAlternative(alt, reason, rel.coerceIn(0.0, 1.0)))
        }
        val ambiguity = alternatives.maxOfOrNull { it.score } ?: 0.0
        listOfNotNull(jr.left?.let { it to "gauche" }, jr.right?.let { it to "droit" }).forEach { (doubt, side) ->
            val alt = count + doubt.delta
            if (alt >= 2 && alternatives.none { it.count == alt }) {
                val reason = if (doubt == BorderDoubt.MAYBE_EDGE) {
                    "Bord $side douteux : $alt éléments si le dernier trait de ce côté est le bord du radiateur."
                } else {
                    "Bord $side douteux : $alt éléments si un élément est en partie hors du contour."
                }
                alternatives.add(CountAlternative(alt, reason, 0.5))
            }
        }

        // Confiance : netteté spectrale × régularité × accord des estimateurs × ambiguïtés.
        val qReg = (1 - (jr.gapCv - 0.05) / 0.25).coerceIn(0.2, 1.0)
        val qInterp = (1 - 1.5 * jr.interpolated / jr.joints.size.toDouble()).coerceIn(0.3, 1.0)
        val qAgree = when {
            count == best.nu.roundToInt() -> 1.0
            abs(count - best.nu) <= 1.2 -> 0.9
            else -> 0.65
        }
        val qAmb = 1 - 0.5 * ambiguity
        val doubts = listOfNotNull(jr.left, jr.right).size
        val qBorder = if (doubts == 0) 1.0 else if (doubts == 1) 0.8 else 0.65
        val qPrior = if (best.prior >= 0.5) 1.0 else 0.8
        val confidence = ((0.35 + 0.65 * qSpec) * qReg * qInterp * qAgree * qAmb * qBorder * qPrior).coerceIn(0.0, 1.0)

        val boundaries = listOf(jr.start) + jr.joints + listOf(jr.end)
        val message = buildString {
            if (confidence >= ElementCounter.RELIABLE_CONFIDENCE) {
                append("Périodicité nette : $count éléments proposés (à vérifier sur l'aperçu).")
            } else {
                append("Périodicité faible : $count éléments proposés avec prudence. Vérifiez et corrigez si nécessaire.")
            }
            if (doubts > 0) append(" Un bord est douteux : ±1 élément possible.")
            if (ambiguity >= 0.5) append(" Autre nombre plausible : ${alternatives.first().count}. Faites coïncider les traits avec les joints.")
            if (jr.interpolated > 0) append(" ${jr.interpolated} joint(s) peu visible(s) déduit(s) de l'espacement.")
        }
        return ElementCountResult(
            count = count,
            periodPx = jr.period,
            confidence = confidence,
            boundariesPx = boundaries,
            message = message,
            alternatives = alternatives.sortedByDescending { it.score },
            plusOrMinusOne = doubts > 0,
            startPx = jr.start,
            endPx = jr.end,
            spectralCycles = best.nu,
            jointsDetected = jr.joints.size,
        )
    }

    private fun empty(msg: String) = ElementCountResult(null, null, 0.0, emptyList(), msg)

    // --- Profils ---

    /** Moyennes par colonne sur la bande centrale (luminance, |∂I/∂x|), hors zones exclues. */
    internal fun profiles(face: GrayImage, excluded: List<PixelRect>): Profiles {
        val w = face.width; val h = face.height
        val y0 = (h * BAND_TOP).toInt(); val y1 = (h * BAND_BOTTOM).toInt().coerceAtLeast(y0 + 1).coerceAtMost(h)
        val lum = DoubleArray(w); val grad = DoubleArray(w); val cnt = IntArray(w)
        val px = face.pixels
        for (y in y0 until y1) {
            val row = y * w
            val rects = excluded.filter { y + 0.5 >= it.top && y + 0.5 <= it.bottom }
            for (x in 0 until w) {
                if (rects.isNotEmpty() && rects.any { x + 0.5 >= it.left && x + 0.5 <= it.right }) continue
                val g = when (x) {
                    0 -> abs(px[row + 1] - px[row]).toDouble()
                    w - 1 -> abs(px[row + x] - px[row + x - 1]).toDouble()
                    else -> abs(px[row + x + 1] - px[row + x - 1]) / 2.0
                }
                lum[x] += px[row + x]; grad[x] += g; cnt[x]++
            }
        }
        // Colonnes entièrement masquées : interpolation linéaire entre colonnes valides.
        val valid = (0 until w).filter { cnt[it] > 0 }
        for (x in valid) { lum[x] /= cnt[x]; grad[x] /= cnt[x] }
        if (valid.size in 1 until w) {
            for (x in 0 until w) if (cnt[x] == 0) {
                val l = valid.lastOrNull { it < x }; val r = valid.firstOrNull { it > x }
                val (vl, vg) = when {
                    l == null -> lum[r!!] to grad[r]
                    r == null -> lum[l] to grad[l]
                    else -> {
                        val t = (x - l).toDouble() / (r - l)
                        (lum[l] * (1 - t) + lum[r] * t) to (grad[l] * (1 - t) + grad[r] * t)
                    }
                }
                lum[x] = vl; grad[x] = vg
            }
        }
        return Profiles(lum, grad)
    }

    // --- Analyse spectrale ---

    /** Spectre combiné : part de l'énergie de chaque profil (fenêtré, normé) à la fréquence ν (cycles sur la largeur). */
    private class Spectrum(a: DoubleArray, b: DoubleArray) {
        private val n = a.size
        private val signals: List<DoubleArray>

        init {
            val win = tukey(n, 0.2)
            signals = listOf(a, b).mapNotNull { s ->
                val x = DoubleArray(n) { s[it] * win[it] }
                val e = x.sumOf { it * it }
                if (e < 1e-9 * n) null else { val k = 1 / sqrt(e); for (i in 0 until n) x[i] *= k; x }
            }
        }

        fun at(nu: Double): Double {
            if (signals.isEmpty()) return 0.0
            val om = 2 * PI * nu / n
            val cw = cos(om); val sw = sin(om)
            var total = 0.0
            for (x in signals) {
                var re = 0.0; var im = 0.0; var c = 1.0; var s = 0.0
                for (k in 0 until n) {
                    re += x[k] * c; im -= x[k] * s
                    val c2 = c * cw - s * sw; s = s * cw + c * sw; c = c2
                }
                total += 2 * (re * re + im * im) / n
            }
            return total / signals.size
        }

        /** Maximum local de at() dans [center ± halfWidth] ∩ [lo, hi] : grille fine puis section dorée. */
        fun localMax(center: Double, halfWidth: Double, lo: Double, hi: Double): Pair<Double, Double> {
            val a = max(lo, center - halfWidth); val b = min(hi, center + halfWidth)
            if (b <= a) return center to at(center)
            var bestNu = a; var bestV = -1.0
            var nu = a
            while (nu <= b + 1e-9) {
                val v = at(nu)
                if (v > bestV) { bestV = v; bestNu = nu }
                nu += 0.02
            }
            var l = max(a, bestNu - 0.02); var r = min(b, bestNu + 0.02)
            val g = (sqrt(5.0) - 1) / 2
            var x1 = r - g * (r - l); var x2 = l + g * (r - l)
            var f1 = at(x1); var f2 = at(x2)
            repeat(14) {
                if (f1 < f2) { l = x1; x1 = x2; f1 = f2; x2 = l + g * (r - l); f2 = at(x2) } else { r = x2; x2 = x1; f2 = f1; x1 = r - g * (r - l); f1 = at(x1) }
            }
            val xm = (l + r) / 2; val fm = at(xm)
            return if (fm >= bestV) xm to fm else bestNu to bestV
        }
    }

    /** A priori de pas : 1 dans la plage, décroissance gaussienne en log au-delà. */
    private fun priorWeight(pitch: Double, prior: ClosedFloatingPointRange<Double>): Double {
        val d = when {
            pitch < prior.start -> ln(prior.start / pitch)
            pitch > prior.endInclusive -> ln(pitch / prior.endInclusive)
            else -> 0.0
        }
        return exp(-(d / 0.35) * (d / 0.35))
    }

    // --- Détection des joints ---

    private fun analyseJoints(p: Profiles, period: Double): JointResult? {
        val w = p.lum.size
        if (period < 3 || period > w) return null
        val win = odd(period.roundToInt())
        val lumHp = DoubleArray(w); val gradHp = DoubleArray(w)
        val lumMa = movingAverage(p.lum, win); val gradMa = movingAverage(p.grad, win)
        for (i in 0 until w) { lumHp[i] = p.lum[i] - lumMa[i]; gradHp[i] = p.grad[i] - gradMa[i] }
        val ws = odd(max(1, (0.1 * period).roundToInt()))
        val lumS = movingAverage(lumHp, ws); val gradS = movingAverage(gradHp, ws)
        val sdL = std(lumS); val sdG = std(gradS)
        if (sdL < 1e-6 && sdG < 1e-6) return null
        // Polarité : joints sombres (gradient fort où la luminance est basse) ou clairs (mur visible entre éléments).
        val corr = pearson(lumS, gradS)
        val pol = when { corr < -0.1 -> -1.0; corr > 0.1 -> 1.0; else -> 0.0 }
        val j = DoubleArray(w) {
            (if (sdG > 1e-9) gradS[it] / sdG else 0.0) + 0.7 * pol * (if (sdL > 1e-9) lumS[it] / sdL else 0.0)
        }
        val maxima = (1 until w - 1).filter { j[it] >= j[it - 1] && j[it] > j[it + 1] }.sortedByDescending { j[it] }
        val radius = 0.6 * period
        val kept = ArrayList<Int>()
        for (c in maxima) if (kept.none { abs(it - c) < radius }) kept.add(c)
        if (kept.isEmpty()) return null
        val central = kept.filter { it >= 0.2 * w && it <= 0.8 * w }.map { j[it] }
        val ref = median(central.ifEmpty { kept.map { j[it] } })
        if (ref <= 0) return null
        val strength = HashMap<Double, Double>()
        val joints = kept.filter { j[it] >= 0.3 * ref }.map { i ->
            val x = refinePeak(j, i); strength[x] = j[i]; x
        }.sorted().toMutableList()
        if (joints.isEmpty()) return null

        // Régularisation : joints trop proches (garder le plus net) puis joints manquants (interpolés).
        if (joints.size >= 3) {
            var pm = median(joints.zipWithNext { x, y -> y - x })
            var i = 0
            while (i < joints.size - 1) {
                if (joints[i + 1] - joints[i] < 0.6 * pm) {
                    val drop = if ((strength[joints[i]] ?: 0.0) >= (strength[joints[i + 1]] ?: 0.0)) i + 1 else i
                    joints.removeAt(drop)
                } else i++
            }
            pm = median(joints.zipWithNext { x, y -> y - x }.ifEmpty { listOf(period) })
            val filled = ArrayList<Double>()
            for (k in joints.indices) {
                filled.add(joints[k])
                if (k < joints.size - 1) {
                    val g = joints[k + 1] - joints[k]
                    val n = (g / pm).roundToInt()
                    if (n >= 2) for (m in 1 until n) filled.add(joints[k] + g * m / n)
                }
            }
            joints.clear(); joints.addAll(filled)
        }

        // Gabarit d'élément (luminance, gradient) moyen entre joints consécutifs réguliers.
        val pAll = if (joints.size >= 2) median(joints.zipWithNext { x, y -> y - x }) else period
        val cells = joints.zipWithNext().filter { (x, y) -> (y - x) in 0.75 * pAll..1.3 * pAll }
        val tLum = DoubleArray(TEMPLATE_SAMPLES); val tGrad = DoubleArray(TEMPLATE_SAMPLES)
        if (cells.isNotEmpty()) {
            for ((x, y) in cells) for (s in 0 until TEMPLATE_SAMPLES) {
                val pos = x + (s + 0.5) / TEMPLATE_SAMPLES * (y - x)
                tLum[s] += interp(p.lum, pos); tGrad[s] += interp(p.grad, pos)
            }
            for (s in 0 until TEMPLATE_SAMPLES) { tLum[s] /= cells.size; tGrad[s] /= cells.size }
        }
        val g0 = quantile(p.grad, 0.05)

        /** Ressemblance d'une portion [ta, tb] de cellule (origine x0, longueur len) au gabarit d'élément. */
        fun judge(ta: Double, tb: Double, x0: Double, len: Double): Verdict {
            if (cells.isEmpty()) return Verdict.UNSURE
            val idx = (0 until TEMPLATE_SAMPLES).filter { val t = (it + 0.5) / TEMPLATE_SAMPLES; t >= ta && t <= tb }
            if (idx.size < 3) return Verdict.UNSURE
            val oL = idx.map { interp(p.lum, x0 + (it + 0.5) / TEMPLATE_SAMPLES * len) }
            val oG = idx.map { interp(p.grad, x0 + (it + 0.5) / TEMPLATE_SAMPLES * len) }
            val mL = idx.map { tLum[it] }; val mG = idx.map { tGrad[it] }
            val sdT = std(mL.toDoubleArray())
            val rs = if (sdT > 1e-6) std(oL.toDoubleArray()) / sdT else 1.0
            val denG = mG.average() - g0
            val rg = if (denG > 1e-6) (oG.average() - g0) / denG else 1.0
            val ncc = pearson(oL.toDoubleArray(), mL.toDoubleArray())
            val e = (rg.coerceIn(0.0, 1.2) + rs.coerceIn(0.0, 1.2) + ncc.coerceIn(0.0, 1.0)) / 3
            return when {
                e >= 0.55 -> Verdict.ELEMENT
                e <= 0.35 -> Verdict.WALL
                else -> Verdict.UNSURE
            }
        }

        fun localPeriod(fromLeft: Boolean): Double {
            if (joints.size < 2) return pAll
            val gaps = joints.zipWithNext { x, y -> y - x }
            return median(if (fromLeft) gaps.take(3) else gaps.takeLast(3))
        }

        // Bord gauche : le trait le plus à gauche est-il un joint (élément au-delà) ou le bord (mur au-delà) ?
        var left: BorderDoubt? = null
        while (joints.isNotEmpty()) {
            val x1 = joints.first(); val pl = localPeriod(true)
            if (x1 < 0.3 * pl) {
                if (x1 >= 0.18 * pl) left = BorderDoubt.MAYBE_ELEMENT
                joints.removeAt(0); continue
            }
            val verdict = judge(max(JOINT_MARGIN, 1 - x1 / pl), 1 - JOINT_MARGIN, x1 - pl, pl)
            if (verdict == Verdict.WALL) { joints.removeAt(0); left = null; continue }
            if (verdict == Verdict.UNSURE) left = BorderDoubt.MAYBE_EDGE
            // Joint manqué au-delà ? (assez de place pour un élément complet supplémentaire)
            if (x1 >= 1.35 * pl && left == null) {
                val extra = judge(max(JOINT_MARGIN, 1 - (x1 - pl) / pl), 1 - JOINT_MARGIN, x1 - 2 * pl, pl)
                if (extra == Verdict.ELEMENT) {
                    joints.add(0, x1 - pl); continue
                } else if (extra == Verdict.UNSURE) left = BorderDoubt.MAYBE_ELEMENT
            }
            break
        }
        var right: BorderDoubt? = null
        while (joints.isNotEmpty()) {
            val xn = joints.last(); val pr = localPeriod(false)
            val room = w - xn
            if (room < 0.3 * pr) {
                if (room >= 0.18 * pr) right = BorderDoubt.MAYBE_ELEMENT
                joints.removeAt(joints.lastIndex); continue
            }
            val verdict = judge(JOINT_MARGIN, min(1 - JOINT_MARGIN, room / pr), xn, pr)
            if (verdict == Verdict.WALL) { joints.removeAt(joints.lastIndex); right = null; continue }
            if (verdict == Verdict.UNSURE) right = BorderDoubt.MAYBE_EDGE
            if (room >= 1.35 * pr && right == null) {
                val extra = judge(JOINT_MARGIN, min(1 - JOINT_MARGIN, (room - pr) / pr), xn + pr, pr)
                if (extra == Verdict.ELEMENT) {
                    joints.add(xn + pr); continue
                } else if (extra == Verdict.UNSURE) right = BorderDoubt.MAYBE_ELEMENT
            }
            break
        }
        if (joints.isEmpty()) return null
        val pl = localPeriod(true); val pr = localPeriod(false)
        val gaps = joints.zipWithNext { x, y -> y - x }
        val cv = if (gaps.size >= 3) detrendedCv(gaps) else 0.15
        return JointResult(
            joints = joints.toList(),
            interpolated = joints.count { strength[it] == null },
            start = joints.first() - pl,
            end = joints.last() + pr,
            gapCv = cv,
            left = left,
            right = right,
        )
    }

    // --- Outils numériques ---

    /** Écart-type relatif des intervalles après retrait d'une tendance linéaire (dérive projective tolérée). */
    private fun detrendedCv(g: List<Double>): Double {
        val n = g.size
        val xm = (n - 1) / 2.0; val ym = g.average()
        var sxy = 0.0; var sxx = 0.0
        for (i in 0 until n) { sxy += (i - xm) * (g[i] - ym); sxx += (i - xm) * (i - xm) }
        val slope = if (sxx > 0) sxy / sxx else 0.0
        val res = DoubleArray(n) { g[it] - (ym + slope * (it - xm)) }
        return sqrt(res.sumOf { it * it } / n) / ym
    }

    private fun detrendQuadratic(p: DoubleArray): DoubleArray {
        val n = p.size
        val half = (n - 1) / 2.0
        // Moindres carrés sur la base 1, t, t² (t ∈ [−1, 1]).
        val s = DoubleArray(5); val r = DoubleArray(3)
        for (i in 0 until n) {
            val t = if (half > 0) (i - half) / half else 0.0
            var tp = 1.0
            for (k in 0 until 5) { s[k] += tp; if (k < 3) r[k] += tp * p[i]; tp *= t }
        }
        val m = arrayOf(doubleArrayOf(s[0], s[1], s[2], r[0]), doubleArrayOf(s[1], s[2], s[3], r[1]), doubleArrayOf(s[2], s[3], s[4], r[2]))
        for (c in 0 until 3) {
            var piv = c
            for (k in c + 1 until 3) if (abs(m[k][c]) > abs(m[piv][c])) piv = k
            val t = m[c]; m[c] = m[piv]; m[piv] = t
            if (abs(m[c][c]) < 1e-12) return DoubleArray(n) { p[it] - p.average() }
            for (k in c + 1 until 3) { val f = m[k][c] / m[c][c]; for (q in c until 4) m[k][q] -= f * m[c][q] }
        }
        val coef = DoubleArray(3)
        for (c in 2 downTo 0) {
            var v = m[c][3]
            for (q in c + 1 until 3) v -= m[c][q] * coef[q]
            coef[c] = v / m[c][c]
        }
        return DoubleArray(n) { i ->
            val t = if (half > 0) (i - half) / half else 0.0
            p[i] - (coef[0] + coef[1] * t + coef[2] * t * t)
        }
    }

    private fun tukey(n: Int, alpha: Double): DoubleArray {
        val edge = alpha * (n - 1) / 2
        return DoubleArray(n) { i ->
            val d = min(i.toDouble(), (n - 1 - i).toDouble())
            if (d >= edge || edge <= 0) 1.0 else 0.5 * (1 - cos(PI * d / edge))
        }
    }

    internal fun movingAverage(p: DoubleArray, window: Int): DoubleArray {
        val n = p.size
        val half = window / 2
        val prefix = DoubleArray(n + 1)
        for (i in 0 until n) prefix[i + 1] = prefix[i] + p[i]
        return DoubleArray(n) { i ->
            val a = (i - half).coerceAtLeast(0); val b = (i + half + 1).coerceAtMost(n)
            (prefix[b] - prefix[a]) / (b - a)
        }
    }

    private fun odd(k: Int): Int { val v = k.coerceAtLeast(1); return if (v % 2 == 0) v + 1 else v }

    private fun interp(p: DoubleArray, x: Double): Double {
        val xc = x.coerceIn(0.0, (p.size - 1).toDouble())
        val i = xc.toInt().coerceAtMost(p.size - 2).coerceAtLeast(0)
        val t = xc - i
        return if (p.size == 1) p[0] else p[i] * (1 - t) + p[i + 1] * t
    }

    private fun refinePeak(y: DoubleArray, i: Int): Double {
        if (i <= 0 || i >= y.size - 1) return i.toDouble()
        val a = y[i - 1]; val b = y[i]; val c = y[i + 1]
        val d = a - 2 * b + c
        if (abs(d) < 1e-12) return i.toDouble()
        return i + (0.5 * (a - c) / d).coerceIn(-0.5, 0.5)
    }

    internal fun std(x: DoubleArray): Double {
        if (x.isEmpty()) return 0.0
        val m = x.average()
        return sqrt(x.sumOf { (it - m) * (it - m) } / x.size)
    }

    private fun pearson(a: DoubleArray, b: DoubleArray): Double {
        val ma = a.average(); val mb = b.average()
        var sab = 0.0; var saa = 0.0; var sbb = 0.0
        for (i in a.indices) { val da = a[i] - ma; val db = b[i] - mb; sab += da * db; saa += da * da; sbb += db * db }
        return if (saa <= 1e-12 || sbb <= 1e-12) 0.0 else sab / sqrt(saa * sbb)
    }

    private fun median(v: List<Double>): Double {
        if (v.isEmpty()) return 0.0
        val s = v.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
    }

    private fun quantile(v: DoubleArray, q: Double): Double {
        val s = v.sorted()
        return s[((s.size - 1) * q).roundToInt().coerceIn(0, s.size - 1)]
    }
}
