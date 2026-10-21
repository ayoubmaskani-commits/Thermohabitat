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
 * 1. profils par colonne sur la bande centrale (15–85 % de la hauteur du contour, sans moyeux ni pieds) :
 *    luminance et énergie du gradient horizontal |∂I/∂x| (joints, bords de colonnes) ;
 * 2. recherche spectrale du nombre de cycles N sur la largeur du contour (fréquences non entières, pas de 0,05,
 *    affinage par section dorée), tendance quadratique retirée ;
 * 3. harmoniques : candidats N, N/2, N/3, 2N notés par leur fondamental et leurs harmoniques, pondérés par un
 *    a priori de pas ; les candidats proches sont rendus comme ALTERNATIVES ;
 * 4. joints : maxima d'un signal « joint » (gradient + luminance selon la polarité) lissé sur une demi-période
 *    (phase cohérente même avec deux motifs par élément), espacés d'au moins 0,6 période, nettoyés et complétés ;
 * 5. dévers : les coins pointés avec une petite erreur font pencher les verticales ; la pente est mesurée entre
 *    les moitiés haute et basse de la bande et les profils sont relus le long des verticales inclinées ;
 * 6. bords réels : là où le profil cesse de ressembler à lui-même décalé d'une période (mur, ombre, bord de
 *    photo), cherchés à ±0,9 pas du contour pointé (d'où les marges de la face) ; nombre = étendue / pas arrondi,
 *    doute ±1 si le quotient est loin d'un entier ou si un bord est peu marqué ;
 * 7. confiance : netteté spectrale, régularité des joints, accord des estimateurs, ambiguïtés.
 */
internal object RectifiedCounting {

    /** Rapport pas / hauteur plausible des radiateurs à éléments (a priori sans échelle métrique). */
    val DEFAULT_PITCH_TO_HEIGHT = 0.04..0.25

    private const val BAND_TOP = 0.15
    private const val BAND_BOTTOM = 0.85
    private const val NU_STEP = 0.05
    private const val MIN_PROMINENCE = 15.0
    private const val FULL_PROMINENCE = 150.0
    private const val EDGE_CONTRAST = 2.0
    private val MULTIPLES = doubleArrayOf(1.0, 2.0, 0.5, 1.0 / 3.0)

    internal class Profiles(val lum: DoubleArray, val grad: DoubleArray)

    private class Candidate(val nu: Double, val fundamental: Double, val evidence: Double, val prior: Double) {
        val score: Double get() = evidence * prior
    }

    /** Limites retenues (bords + joints) et doutes sur les bords. */
    private class JointResult(
        /** Limites successives : bord gauche, joints, bord droit (px). */
        val lines: List<Double>,
        /** Nombre de joints intérieurs déduits de l'espacement (non vus). */
        val interpolated: Int,
        val gapCv: Double,
        /** Écart du nombre si le bord gauche (resp. droit) retenu est faux, null si le bord est net. */
        val leftDelta: Int?,
        val rightDelta: Int?,
    ) {
        val count: Int get() = lines.size - 1
        val start: Double get() = lines.first()
        val end: Double get() = lines.last()
        val joints: List<Double> get() = lines.subList(1, lines.size - 1)
        val period: Double get() = (end - start) / count
    }

    fun count(
        face: GrayImage,
        pitchPriorPx: ClosedFloatingPointRange<Double>?,
        minElements: Int,
        maxElements: Int,
        excluded: List<PixelRect>,
        contour: ClosedFloatingPointRange<Double>?,
        contourRows: ClosedFloatingPointRange<Double>? = null,
    ): ElementCountResult {
        val w = face.width
        val rows = contourRows ?: 0.0..face.height.toDouble()
        val h = (rows.endInclusive - rows.start).roundToInt()
        val x0 = (contour?.start ?: 0.0).coerceIn(0.0, w - 1.0)
        val x1 = (contour?.endInclusive ?: w.toDouble()).coerceIn(x0 + 1, w.toDouble())
        val i0 = x0.roundToInt(); val i1 = x1.roundToInt().coerceAtMost(w)
        val n = i1 - i0
        if (n < 60 || h < 16) return empty("Image redressée trop petite pour l'analyse.")
        val prof = profiles(face, excluded, rows)
        // Analyse spectrale sur le contour nominal seulement (les marges servent à situer les bords).
        val a = detrendQuadratic(prof.lum.copyOfRange(i0, i1)); val b = detrendQuadratic(prof.grad.copyOfRange(i0, i1))
        if (std(a) < 0.05 && std(b) < 0.05) return empty("Image uniforme : aucun motif détecté.")
        val spec = Spectrum(a, b)
        val nuLo = max(1.5, minElements - 0.6)
        val nuHi = min(maxElements * 1.1 + 1.0, n / 5.0)
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
        val harmonicMax = n / 2.5
        val candidates = ArrayList<Candidate>()
        for (i in peaks) for (mult in MULTIPLES) {
            val target = grid[i] * mult
            if (target < nuLo - 0.2 || target > nuHi + 0.2) continue
            val (nuF, f) = spec.localMax(target, 0.35, nuLo - 0.3, nuHi + 0.3)
            if (f < 4 * floor) continue
            val h2 = if (2 * nuF <= harmonicMax) spec.localMax(2 * nuF, 0.3, 0.5, n / 2.2).second else 0.0
            val h3 = if (3 * nuF <= harmonicMax) spec.localMax(3 * nuF, 0.3, 0.5, n / 2.2).second else 0.0
            val c = Candidate(nuF, f, f + 0.5 * h2 + 0.25 * h3, priorWeight(n / nuF, prior))
            val dup = candidates.indexOfFirst { abs(it.nu - nuF) < 0.6 }
            if (dup < 0) candidates.add(c) else if (c.score > candidates[dup].score) candidates[dup] = c
        }
        if (candidates.isEmpty()) return empty("Aucune périodicité nette : comptez les éléments manuellement.")
        candidates.sortByDescending { it.score }
        val best = candidates[0]
        val prominence = best.fundamental / floor
        val qSpec = ((ln(prominence) - ln(MIN_PROMINENCE)) / (ln(FULL_PROMINENCE) - ln(MIN_PROMINENCE))).coerceIn(0.0, 1.0)
        if (qSpec <= 0.0 || best.fundamental < 0.04) {
            return ElementCountResult(null, n / best.nu, 0.0, emptyList(),
                "Motif trop faible : comptez les éléments manuellement.", spectralCycles = best.nu)
        }

        val jr = analyseJoints(face, excluded, rows, prof, n / best.nu, x0, x1)
        if (jr == null || jr.count < 2) {
            return ElementCountResult(null, n / best.nu, 0.0, emptyList(),
                "Joints entre éléments introuvables : comptez les éléments manuellement.", spectralCycles = best.nu)
        }
        val count = jr.count

        // Alternatives : autres périodicités (harmoniques) puis bords douteux.
        val alternatives = ArrayList<CountAlternative>()
        for (c in candidates.drop(1)) {
            val rel = c.score / best.score
            if (rel < 0.25 || alternatives.size >= 3) break
            val alt = analyseJoints(face, excluded, rows, prof, n / c.nu, x0, x1)?.count ?: c.nu.roundToInt()
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
        listOfNotNull(jr.leftDelta?.let { it to "gauche" }, jr.rightDelta?.let { it to "droit" }).forEach { (delta, side) ->
            val alt = count + delta
            if (alt >= 2 && alternatives.none { it.count == alt }) {
                val reason = if (delta < 0) {
                    "Bord $side douteux : $alt éléments si le dernier trait de ce côté est le bord du radiateur."
                } else {
                    "Bord $side douteux : $alt éléments si un élément de plus se trouve de ce côté."
                }
                alternatives.add(CountAlternative(alt, reason, 0.5))
            }
        }

        // Confiance : netteté spectrale × régularité × accord des estimateurs × ambiguïtés.
        val qReg = (1 - (jr.gapCv - 0.05) / 0.25).coerceIn(0.2, 1.0)
        val qInterp = (1 - 1.5 * jr.interpolated / max(1, jr.joints.size).toDouble()).coerceIn(0.3, 1.0)
        val qAgree = when {
            count == best.nu.roundToInt() -> 1.0
            abs(count - best.nu) <= 1.2 -> 0.9
            else -> 0.65
        }
        val qAmb = 1 - 0.5 * ambiguity
        val doubts = listOfNotNull(jr.leftDelta, jr.rightDelta).size
        val qBorder = if (doubts == 0) 1.0 else if (doubts == 1) 0.8 else 0.65
        val qPrior = if (best.prior >= 0.5) 1.0 else 0.8
        val confidence = ((0.35 + 0.65 * qSpec) * qReg * qInterp * qAgree * qAmb * qBorder * qPrior).coerceIn(0.0, 1.0)

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
            boundariesPx = jr.lines,
            message = message,
            alternatives = alternatives.sortedByDescending { it.score },
            plusOrMinusOne = doubts > 0,
            startPx = jr.start,
            endPx = jr.end,
            spectralCycles = best.nu,
            jointsDetected = jr.joints.size - jr.interpolated,
        )
    }

    private fun empty(msg: String) = ElementCountResult(null, null, 0.0, emptyList(), msg)

    // --- Profils ---

    /** Dévers résiduel des verticales dans la face : dx/dy = a + b·x (erreur de pointé des coins). */
    internal class Shear(val a: Double, val b: Double, val yMid: Double) {
        fun dx(x: Double, y: Double) = (a + b * x) * (y - yMid)
    }

    /**
     * Moyennes par colonne (luminance, |∂I/∂x|) sur les lignes [yFrom, yTo] (fractions de la hauteur du contour
     * [rows]), hors zones exclues ; avec [shear], chaque ligne est lue le long des verticales inclinées.
     */
    internal fun profiles(
        face: GrayImage,
        excluded: List<PixelRect>,
        rows: ClosedFloatingPointRange<Double>,
        yFrom: Double = BAND_TOP,
        yTo: Double = BAND_BOTTOM,
        shear: Shear? = null,
    ): Profiles {
        val w = face.width; val h = face.height
        val span = rows.endInclusive - rows.start
        val y0 = (rows.start + span * yFrom).toInt().coerceIn(0, h - 1)
        val y1 = (rows.start + span * yTo).toInt().coerceAtLeast(y0 + 1).coerceAtMost(h)
        val lum = DoubleArray(w); val grad = DoubleArray(w); val cnt = IntArray(w)
        val px = face.pixels
        fun at(row: Int, x: Double): Double {
            val xc = x.coerceIn(0.0, w - 1.0)
            val i = xc.toInt().coerceAtMost(w - 2).coerceAtLeast(0)
            val t = xc - i
            return if (w == 1) px[row].toDouble() else px[row + i] * (1 - t) + px[row + i + 1] * t
        }
        for (y in y0 until y1) {
            val row = y * w
            val rects = excluded.filter { y + 0.5 >= it.top && y + 0.5 <= it.bottom }
            for (x in 0 until w) {
                val xs = if (shear == null) x.toDouble() else x + shear.dx(x.toDouble(), y + 0.5)
                if (rects.isNotEmpty() && rects.any { xs + 0.5 >= it.left && xs + 0.5 <= it.right }) continue
                val v: Double; val g: Double
                if (shear == null) {
                    v = px[row + x].toDouble()
                    g = when (x) {
                        0 -> abs(px[row + 1] - px[row]).toDouble()
                        w - 1 -> abs(px[row + x] - px[row + x - 1]).toDouble()
                        else -> abs(px[row + x + 1] - px[row + x - 1]) / 2.0
                    }
                } else {
                    v = at(row, xs)
                    g = abs(at(row, xs + 1) - at(row, xs - 1)) / 2
                }
                lum[x] += v; grad[x] += g; cnt[x]++
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

    // --- Détection des joints et des bords ---

    /** Signal « joint » (gradient fort + luminance extrême selon la polarité) et sa polarité. */
    private fun jointSignal(p: Profiles, period: Double, x0: Double, x1: Double, polarity: Double?): Pair<DoubleArray, Double>? {
        val w = p.lum.size
        val win = odd(period.roundToInt())
        val lumMa = movingAverage(p.lum, win); val gradMa = movingAverage(p.grad, win)
        val lumHp = DoubleArray(w) { p.lum[it] - lumMa[it] }
        val gradHp = DoubleArray(w) { p.grad[it] - gradMa[it] }
        val ws = odd(max(1, (0.1 * period).roundToInt()))
        val lumS = movingAverage(lumHp, ws); val gradS = movingAverage(gradHp, ws)
        val i0 = x0.roundToInt().coerceIn(0, w - 1); val i1 = x1.roundToInt().coerceIn(i0 + 1, w)
        val sdL = std(lumS.copyOfRange(i0, i1)); val sdG = std(gradS.copyOfRange(i0, i1))
        if (sdL < 1e-6 && sdG < 1e-6) return null
        // Polarité : joints sombres (gradient fort où la luminance est basse) ou clairs (mur visible entre éléments).
        val pol = polarity ?: run {
            val corr = pearson(lumS.copyOfRange(i0, i1), gradS.copyOfRange(i0, i1))
            when { corr < -0.1 -> -1.0; corr > 0.1 -> 1.0; else -> 0.0 }
        }
        val j = DoubleArray(w) {
            (if (sdG > 1e-9) gradS[it] / sdG else 0.0) + 0.7 * pol * (if (sdL > 1e-9) lumS[it] / sdL else 0.0)
        }
        return j to pol
    }

    /**
     * Pics du signal près du contour, positions triées et intensités. La recherche se fait sur le signal lissé sur une
     * demi-période (ce qui annule exactement la composante de période P/2 : rainure, reflet au milieu de l'élément),
     * pour garder une phase cohérente d'un élément à l'autre ; chaque pic est ensuite recalé à ±0,2 période sur le
     * maximum du signal lissé sur un quart de période (les deux bords de colonne qui encadrent un joint fusionnent,
     * une rainure au milieu de l'élément reste à part). Suppression des non-maxima sur 0,6 période.
     */
    private fun jointPeaks(j: DoubleArray, period: Double, x0: Double, x1: Double): Pair<List<Double>, Map<Double, Double>>? {
        val w = j.size
        val js = movingAverage(j, odd((0.5 * period).roundToInt()))
        val jq = movingAverage(j, odd((0.25 * period).roundToInt()))
        val lo = x0 - 1.3 * period; val hi = x1 + 1.3 * period
        val maxima = (1 until w - 1).filter { it >= lo && it <= hi && js[it] >= js[it - 1] && js[it] > js[it + 1] }
            .sortedByDescending { js[it] }
        val radius = 0.6 * period
        val kept = ArrayList<Int>()
        for (c in maxima) if (kept.none { abs(it - c) < radius }) kept.add(c)
        val span = x1 - x0
        val central = kept.filter { it >= x0 + 0.2 * span && it <= x1 - 0.2 * span }.map { js[it] }
        val ref = median(central.ifEmpty { kept.map { js[it] } })
        if (ref <= 0) return null
        val strength = HashMap<Double, Double>()
        val r = (0.2 * period).roundToInt().coerceAtLeast(1)
        val peaks = kept.filter { js[it] >= 0.3 * ref }.map { c ->
            val a = (c - r).coerceAtLeast(1); val b = (c + r).coerceAtMost(w - 2)
            val i = (a..b).maxBy { jq[it] }
            val x = refinePeak(jq, i); strength[x] = js[c]; x
        }.distinct().sorted()
        return if (peaks.size < 2) null else peaks to strength
    }

    /**
     * Dévers résiduel : position de chaque joint dans la moitié haute et la moitié basse de la bande ; la pente
     * dx/dy est ajustée par une droite en x (moindres carrés robustes). Null si négligeable ou indéterminé.
     */
    private fun estimateShear(face: GrayImage, excluded: List<PixelRect>, rows: ClosedFloatingPointRange<Double>,
                              joints: List<Double>, period: Double, x0: Double, x1: Double, polarity: Double): Shear? {
        val h = rows.endInclusive - rows.start
        val mid = (BAND_TOP + BAND_BOTTOM) / 2
        val top = profiles(face, excluded, rows, BAND_TOP, mid); val bottom = profiles(face, excluded, rows, mid, BAND_BOTTOM)
        val jT = jointSignal(top, period, x0, x1, polarity)?.first ?: return null
        val jB = jointSignal(bottom, period, x0, x1, polarity)?.first ?: return null
        val dy = (BAND_BOTTOM - BAND_TOP) / 2 * h
        fun peakNear(j: DoubleArray, x: Double): Double? {
            val a = (x - 0.3 * period).roundToInt().coerceAtLeast(1); val b = (x + 0.3 * period).roundToInt().coerceAtMost(j.size - 2)
            if (b <= a) return null
            val i = (a..b).maxBy { j[it] }
            return if (i == a || i == b) null else refinePeak(j, i)
        }
        val xs = ArrayList<Double>(); val slopes = ArrayList<Double>()
        for (x in joints) {
            if (x < x0 || x > x1) continue
            val t = peakNear(jT, x) ?: continue; val b = peakNear(jB, x) ?: continue
            xs.add(x); slopes.add((b - t) / dy)
        }
        if (xs.size < 4) return null
        var keep = xs.indices.toList()
        var fit = Pair(0.0, 0.0)
        repeat(3) {
            fit = lineFit(keep.map { xs[it] }, keep.map { slopes[it] })
            val res = keep.map { slopes[it] - (fit.first + fit.second * xs[it]) }
            val sd = sqrt(res.sumOf { it * it } / res.size).coerceAtLeast(0.02)
            keep = keep.filterIndexed { k, _ -> abs(res[k]) <= 2.5 * sd }
            if (keep.size < 4) return null
        }
        val (a, b) = fit
        val worst = max(abs(a + b * x0), abs(a + b * x1)) * (BAND_BOTTOM - BAND_TOP) * h
        return if (worst < 1.0) null else Shear(a, b, rows.start + mid * h)
    }

    private fun lineFit(x: List<Double>, y: List<Double>): Pair<Double, Double> {
        val xm = x.average(); val ym = y.average()
        var sxy = 0.0; var sxx = 0.0
        for (i in x.indices) { sxy += (x[i] - xm) * (y[i] - ym); sxx += (x[i] - xm) * (x[i] - xm) }
        val b = if (sxx > 1e-9) sxy / sxx else 0.0
        return (ym - b * xm) to b
    }

    private fun analyseJoints(face: GrayImage, excluded: List<PixelRect>, rows: ClosedFloatingPointRange<Double>,
                              prof: Profiles, period: Double, x0: Double, x1: Double): JointResult? {
        val w = prof.lum.size
        if (period < 3 || period > (x1 - x0)) return null
        var p = prof
        var (j, pol) = jointSignal(p, period, x0, x1, null) ?: return null
        var (peaks, strength) = jointPeaks(j, period, x0, x1) ?: return null
        var lines = regularize(peaks, strength, period, x0, x1)
        // Coins pointés avec une petite erreur : les verticales de la face penchent un peu ; on relit les profils
        // le long des verticales inclinées pour garder des joints et des bords nets.
        val shear = estimateShear(face, excluded, rows, lines.filter { strength.containsKey(it) }, period, x0, x1, pol)
        if (shear != null) {
            p = profiles(face, excluded, rows, shear = shear)
            j = jointSignal(p, period, x0, x1, pol)?.first ?: return null
            val pk = jointPeaks(j, period, x0, x1) ?: return null
            peaks = pk.first; strength = pk.second
            lines = regularize(peaks, strength, period, x0, x1)
        }
        if (lines.size < 2) return null
        val detected = lines.map { strength.containsKey(it) }.toMutableList()
        val pm = median(lines.zipWithNext { a, b -> b - a })

        // Prolongement virtuel de la grille au-delà des derniers traits (bords cachés ou hors contour).
        fun localGap(fromLeft: Boolean): Double {
            val g = lines.zipWithNext { a, b -> b - a }
            return median(if (fromLeft) g.take(3) else g.takeLast(3))
        }
        val pl = localGap(true); val pr = localGap(false)
        while (lines.first() - pl >= x0 - 1.25 * pm) { lines.add(0, lines.first() - pl); detected.add(0, false) }
        while (lines.last() + pr <= x1 + 1.25 * pm) { lines.add(lines.last() + pr); detected.add(false) }

        // Bords réels : rupture de la périodicité. Le profil est comparé à lui-même décalé d'une période vers
        // l'intérieur ; au-delà du bord (mur, ombre portée, bord de la photo) l'écart devient grand.
        val inside = lines.indices.filter { detected[it] && lines[it] >= x0 - 0.5 * pm && lines[it] <= x1 + 0.5 * pm }
        if (inside.size < 2) return null
        val jFirst = lines[inside.first()]; val jLast = lines[inside.last()]
        val lumF = movingAverage(p.lum, 3); val gradF = movingAverage(p.grad, 3)
        val a0 = jFirst.roundToInt().coerceIn(0, w - 2); val a1 = jLast.roundToInt().coerceIn(a0 + 1, w)
        val sL = max(std(lumF.copyOfRange(a0, a1)), 1e-3); val sG = max(std(gradF.copyOfRange(a0, a1)), 1e-3)
        // Hors de la face : aucune donnée, traité comme « pas un élément » (la face est censée englober le corps).
        fun residual(t: Double, ref: Double): Double {
            if (ref < 0 || ref > w - 1 || t < 0 || t > w - 1) return 4.0
            val dl = (interp(lumF, t) - interp(lumF, ref)) / sL
            val dg = (interp(gradF, t) - interp(gradF, ref)) / sG
            return min(4.0, (dl * dl + dg * dg) / 2)
        }
        /** Écart tolérant un léger décalage (±4 % de période) : insensible aux petites erreurs de période locale. */
        fun elastic(t: Double, ref: Double, per: Double): Double {
            val d = max(1, (0.04 * per).roundToInt())
            var best = Double.MAX_VALUE
            for (sft in -d..d) best = min(best, residual(t, ref + sft))
            return best
        }
        /**
         * Période locale affinée par auto-ressemblance sur 1,5 période, à partir d'un trait situé une période à
         * l'intérieur du contour (le dernier trait détecté peut être le bord lui-même).
         */
        fun refinePeriod(right: Boolean, per0: Double): Double {
            val anchor = if (right) {
                lines.filter { it <= x1 - pm }.maxOrNull() ?: jLast
            } else {
                lines.filter { it >= x0 + pm }.minOrNull() ?: jFirst
            }
            var bestP = per0; var bestCost = Double.MAX_VALUE
            var q = 0.88 * per0
            while (q <= 1.12 * per0) {
                var acc = 0.0; var n = 0; var u = 0.0
                while (u <= 1.5 * q) {
                    acc += if (right) residual(anchor - u, anchor - u - q) else residual(anchor + u, anchor + u + q)
                    n++; u += 1.0
                }
                if (acc / n < bestCost) { bestCost = acc / n; bestP = q }
                q += 0.25
            }
            return bestP
        }
        // Point de départ : période spectrale (moyenne robuste), les traits pouvant sauter d'un motif à l'autre.
        val perL = if (inside.size >= 3) refinePeriod(false, period) else pl
        val perR = if (inside.size >= 3) refinePeriod(true, period) else pr
        // Seuil de rupture : bien au-dessus de l'écart typique entre deux éléments voisins (période locale).
        val interior = ArrayList<Double>()
        for (k in 1 until lines.size - 1) {
            val g = lines[k] - lines[k - 1]
            if (lines[k - 1] < jFirst || lines[k + 1] > jLast) continue
            var t = lines[k]
            while (t < lines[k + 1]) { interior.add(elastic(t, t - g, g)); t += 1.0 }
        }
        val tau = max(0.3, 8 * median(interior))

        /**
         * Bord réel : premier point, en partant de l'intérieur, où l'écart à l'élément voisin dépasse le seuil et le reste
         * en moyenne sur 0,4 période au-delà ; position affinée à mi-hauteur du saut. Retourne (position, écart au-delà).
         */
        fun locateEdge(right: Boolean): Pair<Double, Double> {
            val target = if (right) x1 else x0
            val per = if (right) perR else perL
            val dir = if (right) 1.0 else -1.0
            fun r(t: Double) = (elastic(t - dir, t - dir - dir * per, per) + elastic(t, t - dir * per, per) + elastic(t + dir, t + dir - dir * per, per)) / 3
            fun beyond(t: Double): List<Double> {
                val v = ArrayList<Double>(); var u = 0.0
                while (u <= 0.4 * per) { v.add(elastic(t + dir * u, t + dir * u - dir * per, per)); u += 1.0 }
                return v
            }
            fun before(t: Double): Double {
                var acc = 0.0; var n = 0; var u = 1.0
                while (u <= 0.4 * per) { acc += elastic(t - dir * u, t - dir * u - dir * per, per); n++; u += 1.0 }
                return if (n > 0) acc / n else 0.0
            }
            var t = target - dir * 0.9 * per
            val stop = target + dir * 0.9 * per
            while (if (right) t <= stop else t >= stop) {
                if (r(t) > tau) {
                    val b = beyond(t)
                    if (b.average() > max(tau, 2.5 * before(t))) {
                        val half = 0.5 * quantileOf(b, 0.75)
                        var e = t
                        while (abs(e - t) < 0.3 * per && r(e) < half) e += dir * 0.5
                        return e to b.average()
                    }
                }
                t += dir * 0.5
            }
            return target to 0.0
        }
        val (eL, cL) = locateEdge(false)
        val (eR, cR) = locateEdge(true)
        if (eR - eL < pm) return null
        val innerIdx = lines.indices.filter { lines[it] > eL + 0.5 * pl && lines[it] < eR - 0.5 * pr }
        val pLines = if (innerIdx.size >= 2) (lines[innerIdx.last()] - lines[innerIdx.first()]) / (innerIdx.size - 1) else pm
        // Pas moyen : celui des traits s'il concorde avec la période spectrale (±3 %), sinon la période spectrale.
        val pMean = if (abs(pLines / period - 1) <= 0.03) pLines else period
        val ratio = (eR - eL) / pMean
        val count = ratio.roundToInt().coerceAtLeast(1)
        val frac = ratio - count
        // Limites : position k ↦ x lissée (quadratique, tolère la légère dérive du pas due à la perspective résiduelle)
        // à travers les bords et les joints vus, puis chaque trait recalé sur un joint détecté proche (≤ 0,3 pas).
        val seen = innerIdx.filter { detected[it] }.map { lines[it] }
        val predicted = gridPositions(count, eL, eR, seen, pMean)
        var interpolated = 0
        val chosen = predicted.mapIndexed { i, x ->
            if (i == 0 || i == count) x else {
                val near = seen.minByOrNull { abs(it - x) }
                if (near != null && abs(near - x) <= 0.3 * pMean) near else { interpolated++; x }
            }
        }
        // Doutes : nombre de périodes loin d'un entier, ou bord peu marqué.
        val sign = if (frac >= 0) 1 else -1
        var leftDelta: Int? = if (cL < EDGE_CONTRAST * tau) sign else null
        var rightDelta: Int? = if (cR < EDGE_CONTRAST * tau) sign else null
        if (abs(frac) > 0.3 && leftDelta == null && rightDelta == null) {
            if (cL <= cR) leftDelta = sign else rightDelta = sign
        }
        val gaps = innerIdx.dropLast(1).filter { detected[it] && detected[it + 1] }.map { lines[it + 1] - lines[it] }
        val cv = if (gaps.size >= 3) detrendedCv(gaps) else 0.15
        return JointResult(chosen, interpolated, cv, leftDelta, rightDelta)
    }

    /**
     * Positions des limites 0..count : indices des joints vus attribués de proche en proche, puis ajustement
     * x(k) = c0 + c1·k + c2·k² passant au mieux par les bords (k = 0 et k = count) et ces joints.
     * Grille régulière si l'attribution est incohérente.
     */
    private fun gridPositions(count: Int, eL: Double, eR: Double, seen: List<Double>, pitch: Double): List<Double> {
        val even = ElementCounter.evenBoundaries(count, eL, eR)
        if (seen.size < 3 || count < 3) return even
        val ks = ArrayList<Int>()
        var k = ((seen[0] - eL) / pitch).roundToInt().coerceAtLeast(1)
        ks.add(k)
        for (i in 1 until seen.size) { k += max(1, ((seen[i] - seen[i - 1]) / pitch).roundToInt()); ks.add(k) }
        if (ks.last() >= count) return even
        val kx = ArrayList<Pair<Double, Double>>()
        kx.add(0.0 to eL); kx.add(count.toDouble() to eR)
        for (i in seen.indices) kx.add(ks[i].toDouble() to seen[i])
        // Moindres carrés sur la base 1, k, k² (k normalisé dans [0, 1]).
        val m = Array(3) { DoubleArray(4) }
        for ((kk, x) in kx) {
            val t = kk / count
            val b = doubleArrayOf(1.0, t, t * t)
            for (r in 0 until 3) { for (c in 0 until 3) m[r][c] += b[r] * b[c]; m[r][3] += b[r] * x }
        }
        for (c in 0 until 3) {
            var piv = c
            for (r in c + 1 until 3) if (abs(m[r][c]) > abs(m[piv][c])) piv = r
            val tmp = m[c]; m[c] = m[piv]; m[piv] = tmp
            if (abs(m[c][c]) < 1e-12) return even
            for (r in c + 1 until 3) { val f = m[r][c] / m[c][c]; for (q in c until 4) m[r][q] -= f * m[c][q] }
        }
        val coef = DoubleArray(3)
        for (c in 2 downTo 0) { var v = m[c][3]; for (q in c + 1 until 3) v -= m[c][q] * coef[q]; coef[c] = v / m[c][c] }
        return List(count + 1) { i ->
            when (i) {
                0 -> eL
                count -> eR
                else -> { val t = i.toDouble() / count; coef[0] + coef[1] * t + coef[2] * t * t }
            }
        }
    }

    /**
     * Nettoyage des pics : paires trop rapprochées (on garde celui qui respecte l'espacement des voisins), traits
     * hors contour incohérents avec la grille, puis comblement des joints manquants. Positions triées.
     */
    private fun regularize(peaks: List<Double>, strength: Map<Double, Double>, period: Double, x0: Double, x1: Double): MutableList<Double> {
        val xs = peaks.toMutableList()
        fun pm(): Double {
            val g = xs.zipWithNext { a, b -> b - a }.filter { it in 0.7 * period..1.4 * period }
            return if (g.isEmpty()) period else median(g)
        }
        fun fracErr(r: Double): Double = abs(r - max(1.0, r.roundToInt().toDouble()))
        while (xs.size >= 2) {
            val p = pm()
            val i = (0 until xs.size - 1).firstOrNull { xs[it + 1] - xs[it] < 0.75 * p } ?: break
            val a = xs[i]; val b = xs[i + 1]
            val prev = xs.getOrNull(i - 1); val next = xs.getOrNull(i + 2)
            val errDropA = if (prev != null) fracErr((b - prev) / p) else 0.25
            val errDropB = if (next != null) fracErr((next - a) / p) else 0.25
            val dropA = when {
                abs(errDropA - errDropB) < 0.05 -> (strength[a] ?: 0.0) < (strength[b] ?: 0.0)
                else -> errDropA < errDropB
            }
            xs.removeAt(if (dropA) i else i + 1)
        }
        // Traits proches des bords du contour : cohérents avec la grille intérieure, sinon écartés.
        var p = pm()
        var changed = true
        while (changed && xs.size >= 3) {
            changed = false
            val first = xs[0]; val last = xs[xs.size - 1]
            if (first < x0 + 0.5 * p && fracErr((xs[1] - first) / p) > 0.25) { xs.removeAt(0); changed = true }
            if (xs.size >= 3 && last > x1 - 0.5 * p && fracErr((last - xs[xs.size - 2]) / p) > 0.25) { xs.removeAt(xs.size - 1); changed = true }
        }
        p = pm()
        val filled = ArrayList<Double>()
        for (k in xs.indices) {
            filled.add(xs[k])
            if (k < xs.size - 1) {
                val g = xs[k + 1] - xs[k]
                val n = (g / p).roundToInt()
                if (n >= 2) for (m in 1 until n) filled.add(xs[k] + g * m / n)
            }
        }
        return filled
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

    private fun quantileOf(v: List<Double>, q: Double): Double {
        if (v.isEmpty()) return 0.0
        val s = v.sorted()
        return s[((s.size - 1) * q).roundToInt().coerceIn(0, s.size - 1)]
    }
}
