package fr.thermohabitat.thermal.vision

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Comptage des éléments sur une façade REDRESSÉE, robuste aux occultations (chaises, objets posés devant, tiges de
 * plantes) et aux coins mal placés, sans apprentissage :
 *
 * 1. bandes : la hauteur du contour est découpée en 6 à 18 bandes horizontales ; pour chacune, profils par colonne
 *    (luminance, énergie du gradient horizontal |∂I/∂x|) et spectre du nombre de cycles sur la largeur du contour
 *    (grille de 0,1 cycle, affinage local) ;
 * 2. pas commun : spectre combiné des bandes nettes (pic rapporté au plancher local : un gros objet devant ne donne
 *    pas de pic) ; harmoniques N, N/2, N/3, 2N départagées par leurs harmoniques et un a priori de pas ;
 * 3. sélection des zones exploitables : qualité locale de la périodicité, colonne par colonne et bande par bande
 *    (autocorrélation à un pas, moins celle à un demi-pas ; nulle sur un objet uni) ; poids de la bande = qualité
 *    moyenne ; les bandes masquées sont ignorées et les lignes retenues exposées pour l'affichage ;
 * 4. joints candidats : maxima d'un signal « joint » (gradient + luminance selon la polarité) dans chaque bande retenue ;
 * 5. loi des joints ([JointModel]) : les éléments étant identiques, x_k = (a·k + b)/(c·k + 1) sur une rangée, avec un
 *    dévers linéaire d'une rangée à l'autre ; l'ajustement robuste ([JointLawFit]) rejette les faux joints (tiges, bords
 *    d'objets), comble les joints masqués et corrige la perspective résiduelle d'un coin mal placé ; entre lois presque
 *    aussi bien notées, on garde celle dont les éléments se ressemblent le plus ;
 * 6. bords : dans chaque bande, les éléments sont comparés au gabarit des éléments confirmés (deux joints vus) ; le
 *    radiateur s'étend tant que les éléments voisins lui ressemblent (moitié intérieure pour un élément en partie
 *    masqué) ; un coin pointé au-delà, devant un objet, ajoute les éléments masqués ; nombre = écart des indices des
 *    bords ; doute ±1 si un bord est masqué, peu net ou hors du contour pointé ;
 * 7. alternatives physiquement plausibles seulement (bord douteux, harmonique étayée par les joints observés et de pas
 *    plausible) ;
 * 8. confiance : avant tout les joints observés qui suivent la loi, puis écart à la loi, bords, ambiguïtés, netteté.
 */
internal object RectifiedCounting {

    /** Rapport pas / hauteur plausible des radiateurs à éléments (a priori sans échelle métrique). */
    val DEFAULT_PITCH_TO_HEIGHT = 0.04..0.25

    private const val NU_STEP = 0.1
    /** Netteté du spectre combiné (pic / plancher local) : en dessous, motif trop faible ; au-dessus, netteté pleine. */
    private const val MIN_PROMINENCE = 2.5
    private const val FULL_PROMINENCE = 40.0
    /** Netteté (pic / plancher local du spectre) d'une bande : en dessous, bande ignorée ; au-dessus, poids plein. */
    private const val BAND_MIN_PROMINENCE = 5.0
    private const val BAND_FULL_PROMINENCE = 20.0
    /** Qualité locale moyenne minimale d'une bande retenue. */
    private const val MIN_BAND_WEIGHT = 0.08
    /** Ressemblance médiane des éléments confirmés à leur gabarit pour qu'une bande serve à situer les bords. */
    private const val MIN_TEMPLATE_QUALITY = 0.6
    /** Échantillons par élément pour la comparaison au gabarit. */
    private const val SAMPLES = 12
    /** Ressemblance minimale au gabarit : élément entier, moitié intérieure d'un élément en partie masqué. */
    private const val SIM_FULL = 0.5
    private const val SIM_HALF = 0.6
    /** Ressemblance exigée d'un élément dont le centre est hors du contour pointé (face latérale, mur, objet voisin). */
    private const val SIM_OUTSIDE = 0.65
    /** Contraste minimal d'un élément relativement au gabarit (objet uniforme devant le radiateur). */
    private const val MIN_CONTRAST = 0.35
    private val MULTIPLES = doubleArrayOf(1.0, 2.0, 0.5, 1.0 / 3.0)

    internal class Profiles(val lum: DoubleArray, val grad: DoubleArray)

    /**
     * Bande horizontale de la face (px), ses profils, son spectre, son plancher local (sur la grille de fréquences :
     * les gros objets posés devant donnent un spectre « rouge » sans pic) et son poids.
     */
    private class Band(val index: Int, val y0: Double, val y1: Double, val prof: Profiles, val spec: Spectrum?) {
        val yc: Double get() = (y0 + y1) / 2
        /** Plancher global (médiane du spectre) et plancher local (courbe sur la grille). */
        var floor = 1.0
        var localFloor = DoubleArray(0)
        var weight = 0.0
        /** Qualité locale de la périodicité (0–1) par colonne, vide si non calculée. */
        var quality = DoubleArray(0)
        fun q(x: Double): Double = if (quality.isEmpty()) 0.0 else quality[x.roundToInt().coerceIn(0, quality.size - 1)]
    }

    private class Candidate(val nu: Double, val fundamental: Double, val evidence: Double, val prior: Double) {
        val score: Double get() = evidence * prior
    }

    /** Périodicité commune retenue sur le spectre combiné. */
    private class Selection(val best: Candidate, val candidates: List<Candidate>, val prominence: Double)

    /** Étendue retenue dans une bande : premier et dernier éléments, éléments extrêmes en partie masqués, ressemblance au-delà. */
    private class BandExtent(val lo: Int, val hi: Int, val loPartial: Boolean, val hiPartial: Boolean, val loBeyond: Double, val hiBeyond: Double)

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
        val hc = rows.endInclusive - rows.start
        val x0 = (contour?.start ?: 0.0).coerceIn(0.0, w - 1.0)
        val x1 = (contour?.endInclusive ?: w.toDouble()).coerceIn(x0 + 1, w.toDouble())
        val i0 = x0.roundToInt(); val i1 = x1.roundToInt().coerceAtMost(w)
        val n = i1 - i0
        if (n < 60 || hc < 16) return empty("Image redressée trop petite pour l'analyse.")

        // 1. Bandes horizontales : profils et spectres sur la largeur du contour (les marges servent aux bords).
        val nb = (hc / 12).toInt().coerceIn(6, 18)
        val bands = (0 until nb).map { k ->
            val f0 = k.toDouble() / nb; val f1 = (k + 1.0) / nb
            val p = profiles(face, excluded, rows, f0, f1)
            val la = detrendQuadratic(p.lum.copyOfRange(i0, i1)); val ga = detrendQuadratic(p.grad.copyOfRange(i0, i1))
            Band(k, rows.start + f0 * hc, rows.start + f1 * hc, p, if (std(la) < 0.05 && std(ga) < 0.05) null else Spectrum(la, ga))
        }
        if (bands.all { it.spec == null }) return empty("Image uniforme : aucun motif détecté.")
        val nuLo = max(1.5, minElements - 0.6)
        val nuHi = min(maxElements * 1.1 + 1.0, n / 5.0)
        if (nuHi <= nuLo + 0.5) return empty("Plage de recherche invalide.")
        val grid = ArrayList<Double>()
        run { var nu = nuLo; while (nu <= nuHi) { grid.add(nu); nu += NU_STEP } }
        val prior = pitchPriorPx ?: (DEFAULT_PITCH_TO_HEIGHT.start * hc)..(DEFAULT_PITCH_TO_HEIGHT.endInclusive * hc)
        // Netteté d'une bande : pic du spectre rapporté au plancher local, dans la plage de pas plausible (élargie).
        val plausible = grid.indices.filter { n / grid[it] in prior.start / 1.3..prior.endInclusive * 1.3 }
        val values = bands.map { b -> b.spec?.let { s -> DoubleArray(grid.size) { s.at(grid[it]) } } }
        for (b in bands) {
            val v = values[b.index] ?: continue
            b.floor = median(v.toList()).coerceAtLeast(1e-12)
            b.localFloor = localFloor(v, grid)
            val iMax = plausible.maxByOrNull { v[it] / b.localFloor[it] } ?: continue
            b.weight = bandWeight(v[iMax] / b.localFloor[iMax])
        }

        // 2. Périodicité commune sur le spectre combiné des bandes nettes (harmoniques départagées).
        val sel = selectPeriod(bands, values, grid, n, nuLo, nuHi, prior) ?: return noPattern()
        val nu1 = sel.best.nu
        // Qualité locale (colonne par colonne) de chaque bande à ce pas : une bande compte là où elle est périodique,
        // pas là où un objet la masque ; poids de la bande = qualité moyenne sur le contour.
        val pol = polarity(bands.filter { it.weight > 0 }.ifEmpty { bands }, n / nu1, x0, x1)
        // Activité locale (écart-type de la luminance sur un pas) : un objet uni devant le radiateur n'a que du bruit,
        // que le signal « joint », réduit, amplifierait ; qualité nulle sous 12 % de l'activité typique du radiateur.
        val activity = bands.map { b -> localStd(b.prof.lum, n / nu1) }
        val actRef = quantileOf((bands.indices).flatMap { bi -> (i0 until i1).map { activity[bi][it] } }, 0.9).coerceAtLeast(1e-6)
        for (b in bands) {
            val j = if (b.spec == null) null else jointSignal(b.prof, n / nu1, x0, x1, pol)
            b.quality = j?.let { localQuality(it, n / nu1) } ?: DoubleArray(0)
            for (x in b.quality.indices) b.quality[x] *= ((activity[b.index][x] / actRef - 0.08) / 0.08).coerceIn(0.0, 1.0)
            b.weight = if (j == null) 0.0 else (i0 until i1).sumOf { b.quality[it] } / n
            if (b.weight < MIN_BAND_WEIGHT) b.weight = 0.0
        }
        val used = bands.filter { it.weight > 0 }
        if (used.isEmpty()) return weak(n / nu1, nu1)
        val qSpec = ((ln(sel.prominence) - ln(MIN_PROMINENCE)) / (ln(FULL_PROMINENCE) - ln(MIN_PROMINENCE))).coerceIn(0.0, 1.0)
        // Motif trop faible : spectre sans pic net, ou aucune bande périodique sur au moins le quart de sa largeur.
        if (qSpec <= 0.0 || used.maxOf { it.weight } < 0.25) return weak(n / nu1, nu1)
        val period = n / nu1

        // 3. Joints candidats des bandes retenues (polarité commune).
        val cands = ArrayList<JointCandidate>()
        val signals = HashMap<Int, DoubleArray>()
        val dense = ArrayList<DenseBand>()
        // Poids relatif au carré : les bandes presque entièrement visibles dominent les bandes très masquées, dont le
        // signal (bord d'objet, tissu) est surtout du bruit pour la loi des joints.
        val wMax = used.maxOf { it.weight }
        fun rel(b: Band) = (b.weight / wMax) * (b.weight / wMax)
        for (b in used) {
            val j = jointSignal(b.prof, period, x0, x1, pol) ?: continue
            signals[b.index] = j
            // Score dense et candidats modulés par la qualité locale : une zone encombrée (tiges, objets) compte moins,
            // sans être ignorée (des joints y restent visibles entre les obstacles).
            denseSignal(j, period, x0, x1)?.let { sig -> dense += DenseBand(b.yc, rel(b), DoubleArray(sig.size) { sig[it] * (0.3 + 0.7 * b.q(it.toDouble())) }, x0, x1) }
            // Pic bien plus marqué que les joints typiques de la bande : souvent un bord d'objet ou une tige (plafonné).
            for ((x, s) in jointPeaks(j, period, x0, x1)) {
                cands += JointCandidate(x, b.yc, rel(b) * s.coerceIn(0.0, 1.2) * (0.3 + 0.7 * b.q(x)), b.index)
            }
        }
        val yRef = (rows.start + rows.endInclusive) / 2

        // 4. Loi des joints : la mieux notée, sauf si une loi presque aussi bien notée (score dense à 15 % près) a des
        //    éléments nettement plus semblables entre eux (une loi glissée sur des tiges coupe de vrais joints au milieu
        //    des éléments).
        val fits = JointLawFit.fit(cands, dense, period, yRef, x0..x1)
        if (fits.isEmpty()) {
            return ElementCountResult(null, period, 0.0, emptyList(),
                "Joints entre éléments introuvables : comptez les éléments manuellement.", spectralCycles = nu1)
        }
        val topDense = fits.maxOf { it.dense }
        val scored = fits.filter { it.dense >= 0.85 * topDense }.map { it to coherence(used, it.model, x0, x1, w, ::rel) }
        val first = scored.first { it.first === fits.first() }
        val bestCoh = scored.maxBy { it.second }
        val fit = if (bestCoh.second >= first.second + 0.05) bestCoh.first else first.first
        val m = fit.model

        // 5. Bords : extension, bande par bande, tant que les éléments ressemblent au gabarit des éléments confirmés.
        val kc0 = m.indexAt(x0, yRef); val kc1 = m.indexAt(x1, yRef)
        if (!kc0.isFinite() || !kc1.isFinite() || kc1 - kc0 < 1) return noJoints(period, nu1)
        val kMin = ceil(kc0 - 1.2).toInt(); val kMax = floor(kc1 + 0.2).toInt()
        val jointsByBand = fit.inliers.groupBy({ it.first.band }, { it.second }).mapValues { it.value.toSet() }
        val extents = HashMap<Int, BandExtent>()
        val templates = HashMap<Int, Pair<DoubleArray, Double>>()
        val flat = HashSet<Int>()
        for (b in used) {
            val joints = jointsByBand[b.index] ?: continue
            val confirmed = joints.filter { it + 1 in joints && it >= kMin - 1 && it <= kMax + 1 }
            val feats = confirmed.mapNotNull { elementSamples(b, m, it, w) }
            if (feats.size < 2) continue
            // Gabarit discriminant seulement si les éléments confirmés lui ressemblent nettement (pas un motif plat :
            // moyeu uni, joint plus fin que l'échantillonnage).
            val tpl = template(feats)
            val quality = median(feats.map { similarity(it, tpl.first, 0, SAMPLES) })
            if (quality >= MIN_TEMPLATE_QUALITY) templates[b.index] = tpl else flat += b.index
        }
        // Gabarit trop peu contrasté par rapport aux autres bandes (moyeu presque uni) : bande écartée des bords.
        if (templates.isNotEmpty()) {
            val ref = median(templates.values.map { it.second })
            templates.entries.filter { it.value.second < 0.4 * ref }.map { it.key }.forEach { templates.remove(it); flat += it }
        }
        for (b in used) {
            val joints = jointsByBand[b.index] ?: continue
            if (b.index in flat) continue
            // Trop peu d'éléments confirmés : gabarit d'une bande voisine.
            val tpl = templates[b.index] ?: templates.minByOrNull { abs(it.key - b.index) }?.takeIf { abs(it.key - b.index) <= 1 }?.value ?: continue
            // Contour pointé sur la rangée de la bande : un élément dont le centre en sort doit ressembler nettement au
            // gabarit, même si ses deux joints sont vus (face latérale du radiateur, montant voisin aligné par hasard).
            val cL = m.indexAt(x0, b.yc); val cR = m.indexAt(x1, b.yc)
            val confirmed = joints.filter { k ->
                k + 1 in joints && k >= kMin && k <= kMax &&
                    (k + 0.5 in cL..cR || elementSim(b, m, w, k, tpl, 0, SAMPLES) >= SIM_OUTSIDE)
            }
            if (confirmed.isEmpty()) continue
            extents[b.index] = extend(b, m, w, confirmed.min(), confirmed.max(), kMin, kMax, tpl, cL, cR)
        }
        if (extents.isEmpty()) return noJoints(period, nu1)
        // Bords retenus : l'élément extrême le plus éloigné vu dans une bande. Hors du contour pointé (centre au-delà),
        // il doit l'être dans plusieurs bandes : la face latérale du radiateur ou un objet voisin peuvent ressembler à
        // un élément dans une ou deux bandes basses.
        val los = extents.values.map { it.lo }.sorted()
        val his = extents.values.map { it.hi }.sortedDescending()
        fun support(outside: Double): Int = when {
            outside <= 0 -> 1
            outside <= 0.25 -> 2
            else -> max(3, (0.25 * extents.size).roundToInt())
        }
        var lo = los.firstOrNull { v -> los.count { it <= v } >= support(kc0 - (v + 0.5)) } ?: los.last()
        var hi = his.firstOrNull { v -> his.count { it >= v } >= support((v + 0.5) - kc1) } ?: his.last()
        if (hi < lo) return noJoints(period, nu1)
        var loPartial = extents.values.filter { it.lo == lo }.let { e -> e.isNotEmpty() && e.all { it.loPartial } }
        var hiPartial = extents.values.filter { it.hi == hi }.let { e -> e.isNotEmpty() && e.all { it.hiPartial } }
        val loBeyond = extents.values.filter { it.lo <= lo }.maxOf { it.loBeyond }
        val hiBeyond = extents.values.filter { it.hi >= hi }.maxOf { it.hiBeyond }
        // Coins pointés : l'utilisateur voit le radiateur jusqu'à ses coins. Au-delà des éléments reconnus, la loi des
        // joints situe le coin : à plus d'un demi-élément, les éléments jusqu'au coin sont masqués (objet posé devant)
        // et comptés, avec un doute (−1).
        val cornerL = min(m.indexAt(x0, rows.start), m.indexAt(x0, rows.endInclusive))
        val cornerR = max(m.indexAt(x1, rows.start), m.indexAt(x1, rows.endInclusive))
        // Seulement si, juste au-delà du dernier élément reconnu, la majorité des bandes voit un objet contrasté (au moins
        // 60 % du contraste d'un élément) et non un mur uni ou le bord du radiateur : sinon le coin est simplement large.
        fun covered(k: Int): Boolean {
            val v = used.filter { it.index !in flat }.mapNotNull { b ->
                templates[b.index]?.let { t -> elementSamples(b, m, k, w)?.let { f -> sdRange(f, 0, SAMPLES) >= 0.6 * t.second } }
            }
            return v.isNotEmpty() && v.count { it } >= 0.5 * v.size
        }
        val hiddenL = if (lo - cornerL >= 0.5 && covered(lo - 1)) (lo - cornerL.roundToInt()).coerceAtLeast(0) else 0
        val hiddenR = if (cornerR - (hi + 1) >= 0.5 && covered(hi + 1)) (cornerR.roundToInt() - (hi + 1)).coerceAtLeast(0) else 0
        if (hiddenL > 0) { lo -= hiddenL; loPartial = true }
        if (hiddenR > 0) { hi += hiddenR; hiPartial = true }
        val count = hi - lo + 1
        if (count < 1) return noJoints(period, nu1)

        val model = m.reindexed(lo.toDouble())
        val lines = List(count + 1) { model.positionAt(it.toDouble(), yRef) }
        val pMean = (lines.last() - lines.first()) / count

        // Doutes sur les bords : élément extrême en partie masqué, déduit d'un coin ou hors du contour pointé (ombre
        // portée, face latérale qui lui ressemblent) (−1) ; élément voisin presque semblable ou coin un peu au-delà (+1).
        val loOutside = hiddenL == 0 && kc0 - (lo + 0.5) > 0.25
        val hiOutside = hiddenR == 0 && (hi + 0.5) - kc1 > 0.25
        val leftDeltas = listOfNotNull(if (loPartial || loOutside) -1 else null, if (loBeyond > 0.3 || lo - cornerL >= 0.25) 1 else null)
        val rightDeltas = listOfNotNull(if (hiPartial || hiOutside) -1 else null, if (hiBeyond > 0.3 || cornerR - (hi + 1) >= 0.25) 1 else null)

        // Joints observés (indices distincts) et leur part parmi les joints intérieurs.
        val observed = fit.inliers.map { it.second - lo }.filter { it in 0..count }.toSet()
        val interiorSeen = observed.count { it in 1 until count }
        val obsFrac = if (count > 1) interiorSeen.toDouble() / (count - 1) else 1.0
        val bandsPerJoint = if (observed.isEmpty()) 0.0 else fit.inliers.count { it.second - lo in observed }.toDouble() / observed.size

        // 6. Alternatives : bords douteux, harmoniques étayées par les joints observés et de pas plausible.
        val alternatives = ArrayList<CountAlternative>()
        val plausiblePitch = (prior.start * 0.8)..(prior.endInclusive * 1.25)
        fun addAlt(alt: Int, reason: String, score: Double) {
            if (alt >= max(2, minElements) && alt <= maxElements && alt != count && alternatives.none { it.count == alt }) {
                alternatives += CountAlternative(alt, reason, score.coerceIn(0.0, 1.0))
            }
        }
        val half = halfIndexEvidence(used, signals, model, count)
        if (half >= 0.55 && pMean / 2 in plausiblePitch) {
            addAlt(2 * count, "Motif deux fois plus fin également présent : ${2 * count} éléments si chaque bande étroite est un élément.", half)
        }
        val parity = parityContrast(fit.inliers.map { (c, k) -> (k - lo) to c.weight })
        if (parity >= 0.4 && count >= 4 && pMean * 2 in plausiblePitch) {
            val alt = (count / 2.0).roundToInt()
            addAlt(alt, "Joints alternativement marqués : $alt éléments si deux bandes forment un seul élément.", parity)
        }
        val ambiguity = alternatives.maxOfOrNull { it.score } ?: 0.0
        (leftDeltas.map { it to "gauche" } + rightDeltas.map { it to "droit" }).forEach { (delta, side) ->
            val alt = count + delta
            val hidden = if (side == "gauche") hiddenL else hiddenR
            val outside = if (side == "gauche") loOutside else hiOutside
            val reason = if (delta < 0 && hidden > 0) {
                "Élément masqué au bord $side, déduit du coin pointé : $alt éléments si le coin est placé trop loin."
            } else if (delta < 0 && outside) {
                "Élément extrême hors du contour pointé au bord $side : $alt éléments s'il n'appartient pas au radiateur (ombre, face latérale)."
            } else if (delta < 0) {
                "Élément en partie masqué au bord $side : $alt éléments s'il n'appartient pas au radiateur."
            } else {
                "Bord $side peu net ou masqué : $alt éléments si un élément de plus se trouve de ce côté."
            }
            addAlt(alt, reason, 0.5)
        }

        // 7. Confiance : avant tout les joints observés qui suivent la loi (part des joints intérieurs, nombre, vus dans
        // plusieurs bandes, écart à la loi), même si une grande partie du radiateur est masquée ; puis bords,
        // ambiguïtés, a priori de pas, et netteté spectrale (modérément : l'occultation l'abaisse sans fausser la loi).
        val qObs = (obsFrac / 0.7).coerceIn(0.3, 1.0) * (interiorSeen / min(6.0, count - 1.0).coerceAtLeast(1.0)).coerceIn(0.4, 1.0) *
            (if (bandsPerJoint >= 1.5) 1.0 else 0.85)
        val qFit = (1 - (fit.rms - 0.05) / 0.15).coerceIn(0.4, 1.0)
        val doubts = (if (leftDeltas.isEmpty()) 0 else 1) + (if (rightDeltas.isEmpty()) 0 else 1)
        val qBorder = if (doubts == 0) 1.0 else if (doubts == 1) 0.85 else 0.7
        val qAmb = 1 - 0.5 * ambiguity
        val qPrior = if (sel.best.prior >= 0.5) 1.0 else 0.8
        // Plusieurs éléments déduits des seuls coins pointés : nombre moins sûr.
        val qHidden = (1 - 0.06 * (hiddenL + hiddenR - 1).coerceAtLeast(0)).coerceAtLeast(0.5)
        val confidence = ((0.75 + 0.25 * qSpec) * qObs * qFit * qBorder * qAmb * qPrior * qHidden).coerceIn(0.0, 1.0)

        // Perspective résiduelle : pas inégaux ou joints inclinés dans la face → coins probablement mal placés.
        val warnings = ArrayList<String>()
        val pitches = (0 until count).map { model.pitchAt(it.toDouble(), yRef) }
        val spread = pitches.max() / pitches.min().coerceAtLeast(1e-6) - 1
        val tilt = (0..count).maxOf { abs(model.positionAt(it.toDouble(), rows.endInclusive) - model.positionAt(it.toDouble(), rows.start)) } / pMean
        if (spread > 0.15 || tilt > 0.35) {
            warnings += "Les joints ne sont ni équidistants ni verticaux sur la face redressée (écart de pas ${(100 * spread).roundToInt()} %) : " +
                "un coin est probablement mal placé, en particulier un coin masqué. Le comptage en tient compte, mais " +
                "vérifiez les coins : les proportions et les dimensions en dépendent."
        }

        if (hiddenL + hiddenR >= 2) {
            warnings += "${hiddenL + hiddenR} éléments masqués près des bords sont comptés d'après les coins pointés et la loi des joints : " +
                "vérifiez que les coins sont bien aux extrémités du radiateur."
        }
        val unseen = (count - 1 - interiorSeen).coerceAtLeast(0)
        val message = buildString {
            if (confidence >= ElementCounter.RELIABLE_CONFIDENCE) {
                append("Périodicité nette : $count éléments proposés (à vérifier sur l'aperçu).")
            } else {
                append("Périodicité faible : $count éléments proposés avec prudence. Vérifiez et corrigez si nécessaire.")
            }
            append(" $interiorSeen joint(s) sur ${max(0, count - 1)} observé(s) suivant la loi des joints")
            append(if (used.size < nb) " (${used.size} bande(s) exploitable(s) sur $nb)." else ".")
            if (doubts > 0) append(" Un bord est douteux ou masqué : ±1 élément possible.")
            if (ambiguity >= 0.5) append(" Autre nombre plausible : ${alternatives.first().count}. Faites coïncider les traits avec les joints.")
            if (unseen > 0) append(" $unseen joint(s) masqué(s) ou peu visible(s) placé(s) d'après la loi des joints.")
        }
        return ElementCountResult(
            count = count,
            periodPx = pMean,
            confidence = confidence,
            boundariesPx = lines,
            message = message,
            alternatives = alternatives.sortedByDescending { it.score },
            plusOrMinusOne = doubts > 0,
            startPx = lines.first(),
            endPx = lines.last(),
            spectralCycles = nu1,
            jointsDetected = observed.size,
            analyzedRowsPx = mergedRows(used),
            jointModel = model,
            referenceRowPx = yRef,
            warnings = warnings,
        )
    }

    private fun empty(msg: String) = ElementCountResult(null, null, 0.0, emptyList(), msg)

    private fun noPattern() = empty("Aucune périodicité nette : comptez les éléments manuellement.")

    private fun weak(period: Double, nu: Double) = ElementCountResult(null, period, 0.0, emptyList(),
        "Motif trop faible : comptez les éléments manuellement.", spectralCycles = nu)

    private fun noJoints(period: Double, nu: Double) = ElementCountResult(null, period, 0.0, emptyList(),
        "Joints entre éléments introuvables : comptez les éléments manuellement.", spectralCycles = nu)

    /** Poids d'une bande selon la netteté de sa périodicité (0 : bande ignorée). */
    private fun bandWeight(prominence: Double): Double =
        ((ln(prominence) - ln(BAND_MIN_PROMINENCE)) / (ln(BAND_FULL_PROMINENCE) - ln(BAND_MIN_PROMINENCE))).coerceIn(0.0, 1.0)

    /** Bandes retenues fusionnées en plages contiguës de rangées. */
    private fun mergedRows(used: List<Band>): List<ClosedFloatingPointRange<Double>> {
        val out = ArrayList<ClosedFloatingPointRange<Double>>()
        for (b in used.sortedBy { it.y0 }) {
            val last = out.lastOrNull()
            if (last != null && b.y0 <= last.endInclusive + 1e-6) out[out.size - 1] = last.start..b.y1 else out += b.y0..b.y1
        }
        return out
    }

    // --- Profils ---

    /** Moyennes par colonne (luminance, |∂I/∂x|) sur les lignes [yFrom, yTo] (fractions de la hauteur du contour [rows]), hors zones exclues. */
    internal fun profiles(face: GrayImage, excluded: List<PixelRect>, rows: ClosedFloatingPointRange<Double>, yFrom: Double, yTo: Double): Profiles {
        val w = face.width; val h = face.height
        val span = rows.endInclusive - rows.start
        val y0 = (rows.start + span * yFrom).toInt().coerceIn(0, h - 1)
        val y1 = (rows.start + span * yTo).toInt().coerceAtLeast(y0 + 1).coerceAtMost(h)
        val lum = DoubleArray(w); val grad = DoubleArray(w); val cnt = IntArray(w)
        val px = face.pixels
        for (y in y0 until y1) {
            val row = y * w
            val rects = excluded.filter { y + 0.5 >= it.top && y + 0.5 <= it.bottom }
            for (x in 0 until w) {
                if (rects.isNotEmpty() && rects.any { x + 0.5 >= it.left && x + 0.5 <= it.right }) continue
                val g = when (x) {
                    0 -> if (w > 1) abs(px[row + 1] - px[row]).toDouble() else 0.0
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
    }

    /** Maximum local de [f] dans [center ± halfWidth] ∩ [lo, hi] : grille de 0,1 puis section dorée. */
    private fun localMax(f: (Double) -> Double, center: Double, halfWidth: Double, lo: Double, hi: Double): Pair<Double, Double> {
        val a = max(lo, center - halfWidth); val b = min(hi, center + halfWidth)
        if (b <= a) return center to f(center)
        var bestNu = a; var bestV = -1.0
        var nu = a
        while (nu <= b + 1e-9) {
            val v = f(nu)
            if (v > bestV) { bestV = v; bestNu = nu }
            nu += 0.1
        }
        var l = max(a, bestNu - 0.1); var r = min(b, bestNu + 0.1)
        val g = (sqrt(5.0) - 1) / 2
        var x1 = r - g * (r - l); var x2 = l + g * (r - l)
        var f1 = f(x1); var f2 = f(x2)
        repeat(10) {
            if (f1 < f2) { l = x1; x1 = x2; f1 = f2; x2 = l + g * (r - l); f2 = f(x2) } else { r = x2; x2 = x1; f2 = f1; x1 = r - g * (r - l); f1 = f(x1) }
        }
        val xm = (l + r) / 2; val fm = f(xm)
        return if (fm >= bestV) xm to fm else bestNu to bestV
    }

    /**
     * Périodicité commune : spectre moyen des bandes pondérées (chacun rapporté à sa médiane, sans blanchiment pour ne
     * pas favoriser les harmoniques), pics, candidats harmoniques notés par leur fondamental et leurs harmoniques,
     * pondérés par l'a priori de pas.
     */
    private fun selectPeriod(
        bands: List<Band>, values: List<DoubleArray?>, grid: List<Double>, n: Int,
        nuLo: Double, nuHi: Double, prior: ClosedFloatingPointRange<Double>,
    ): Selection? {
        val used = bands.filter { it.weight > 0 && it.spec != null }
        if (used.isEmpty()) return null
        val tw = used.sumOf { it.weight }
        val comb: (Double) -> Double = { nu -> used.sumOf { b -> b.weight * b.spec!!.at(nu) / b.floor } / tw }
        val v = DoubleArray(grid.size) { i -> used.sumOf { b -> values[b.index]!![i] * b.weight / b.floor } / tw }
        val floor = median(v.toList()).coerceAtLeast(1e-12)
        // Vrai pic seulement (au-dessus du plancher local) : pas une bosse basse fréquence due aux objets posés devant.
        val local = localFloor(v, grid)
        fun peakiness(nu: Double) = comb(nu) / gridInterp(local, grid, nu)
        val peaks = grid.indices.filter { i ->
            (i == 0 || v[i] > v[i - 1]) && (i == grid.lastIndex || v[i] >= v[i + 1]) && v[i] > 3 * floor && v[i] > 3 * local[i]
        }.sortedByDescending { v[it] }.take(6)
        if (peaks.isEmpty()) return null
        val harmonicMax = n / 2.5
        val cands = ArrayList<Candidate>()
        for (i in peaks) for (mult in MULTIPLES) {
            val target = grid[i] * mult
            if (target < nuLo - 0.2 || target > nuHi + 0.2) continue
            val (nuF, f) = localMax(comb, target, 0.35, nuLo - 0.3, nuHi + 0.3)
            if (f < 4 * floor || peakiness(nuF) < 2.5) continue
            val h2 = if (2 * nuF <= harmonicMax) localMax(comb, 2 * nuF, 0.3, 0.5, n / 2.2).second else 0.0
            val h3 = if (3 * nuF <= harmonicMax) localMax(comb, 3 * nuF, 0.3, 0.5, n / 2.2).second else 0.0
            val c = Candidate(nuF, f, f + 0.5 * h2 + 0.25 * h3, priorWeight(n / nuF, prior))
            val dup = cands.indexOfFirst { abs(it.nu - nuF) < 0.6 }
            if (dup < 0) cands.add(c) else if (c.score > cands[dup].score) cands[dup] = c
        }
        if (cands.isEmpty()) return null
        cands.sortByDescending { it.score }
        return Selection(cands[0], cands, peakiness(cands[0].nu))
    }

    /**
     * Plancher local du spectre : médiane sur [ν / 1,4 ; ν × 1,4] (un spectre sans pic, celui d'un objet uniforme ou
     * d'un bord, a une netteté proche de 1), calculée de 0,5 en 0,5 cycle puis interpolée sur la grille.
     */
    private fun localFloor(v: DoubleArray, grid: List<Double>): DoubleArray {
        val g0 = grid.first()
        val centers = ArrayList<Double>()
        run { var c = g0; while (c <= grid.last() + 0.5) { centers += c; c += 0.5 } }
        val fc = centers.map { c ->
            val a = ((c / 1.4 - g0) / NU_STEP).roundToInt().coerceIn(0, v.size - 1)
            val b = ((c * 1.4 - g0) / NU_STEP).roundToInt().coerceIn(a, v.size - 1)
            median((a..b step 2).map { v[it] }).coerceAtLeast(1e-12)
        }
        return DoubleArray(v.size) { i ->
            val t = (grid[i] - g0) / 0.5
            val k = t.toInt().coerceIn(0, fc.size - 2)
            val f = (t - k).coerceIn(0.0, 1.0)
            fc[k] * (1 - f) + fc[k + 1] * f
        }
    }

    /** Valeur interpolée d'une courbe définie sur la grille de fréquences. */
    private fun gridInterp(c: DoubleArray, grid: List<Double>, nu: Double): Double {
        val t = ((nu - grid.first()) / NU_STEP).coerceIn(0.0, (c.size - 1).toDouble())
        val k = t.toInt().coerceAtMost(c.size - 2).coerceAtLeast(0)
        val f = t - k
        return if (c.size == 1) c[0] else c[k] * (1 - f) + c[k + 1] * f
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

    // --- Joints candidats ---

    /** Composantes lissées du profil (luminance, gradient), passe-haut sur une période. */
    private fun smoothed(p: Profiles, period: Double): Pair<DoubleArray, DoubleArray> {
        val w = p.lum.size
        val win = odd(period.roundToInt())
        val lumMa = movingAverage(p.lum, win); val gradMa = movingAverage(p.grad, win)
        val lumHp = DoubleArray(w) { p.lum[it] - lumMa[it] }
        val gradHp = DoubleArray(w) { p.grad[it] - gradMa[it] }
        val ws = odd(max(1, (0.1 * period).roundToInt()))
        return movingAverage(lumHp, ws) to movingAverage(gradHp, ws)
    }

    /**
     * Polarité commune des joints : clairs (mur visible entre les éléments d'un radiateur sombre : gradient fort où la
     * luminance est haute, corrélation nettement positive) ou, sinon, sombres ; moyenne pondérée des bandes retenues.
     */
    private fun polarity(used: List<Band>, period: Double, x0: Double, x1: Double): Double {
        var acc = 0.0; var tw = 0.0
        for (b in used) {
            val (l, g) = smoothed(b.prof, period)
            val i0 = x0.roundToInt().coerceIn(0, l.size - 1); val i1 = x1.roundToInt().coerceIn(i0 + 1, l.size)
            acc += b.weight * pearson(l.copyOfRange(i0, i1), g.copyOfRange(i0, i1)); tw += b.weight
        }
        val corr = if (tw > 0) acc / tw else 0.0
        // Indécis (rainures aussi marquées que les joints, par exemple) : joints sombres, cas le plus courant.
        return if (corr > 0.1) 1.0 else -1.0
    }

    /** Signal « joint » : gradient fort + luminance extrême selon la polarité, chacun réduit sur le contour. */
    private fun jointSignal(p: Profiles, period: Double, x0: Double, x1: Double, pol: Double): DoubleArray? {
        val (lumS, gradS) = smoothed(p, period)
        val w = lumS.size
        val i0 = x0.roundToInt().coerceIn(0, w - 1); val i1 = x1.roundToInt().coerceIn(i0 + 1, w)
        val sdL = std(lumS.copyOfRange(i0, i1)); val sdG = std(gradS.copyOfRange(i0, i1))
        if (sdL < 1e-6 && sdG < 1e-6) return null
        return DoubleArray(w) {
            (if (sdG > 1e-9) gradS[it] / sdG else 0.0) + 0.7 * pol * (if (sdL > 1e-9) lumS[it] / sdL else 0.0)
        }
    }

    /**
     * Pics du signal près du contour : positions et intensités relatives (médiane des pics centraux = 1). Le signal est
     * lissé sur un quart de période et les pics ne s'excluent que sur 0,3 période : un joint voisin d'une tige ou d'un
     * bord d'objet reste candidat (la loi des joints départage). Position affinée par interpolation parabolique.
     */
    private fun jointPeaks(j: DoubleArray, period: Double, x0: Double, x1: Double): List<Pair<Double, Double>> {
        val w = j.size
        val jq = movingAverage(j, odd((0.25 * period).roundToInt()))
        val lo = x0 - 1.3 * period; val hi = x1 + 1.3 * period
        val maxima = (1 until w - 1).filter { it >= lo && it <= hi && jq[it] >= jq[it - 1] && jq[it] > jq[it + 1] }
            .sortedByDescending { jq[it] }
        val radius = 0.3 * period
        val kept = ArrayList<Int>()
        for (c in maxima) if (kept.none { abs(it - c) < radius }) kept.add(c)
        val span = x1 - x0
        val central = kept.filter { it >= x0 + 0.2 * span && it <= x1 - 0.2 * span }.map { jq[it] }
        val ref = median(central.ifEmpty { kept.map { jq[it] } })
        if (ref <= 0) return emptyList()
        return kept.filter { jq[it] >= 0.3 * ref }.map { refinePeak(jq, it) to jq[it] / ref }.sortedBy { it.first }
    }

    /**
     * Qualité locale de la périodicité (0–1) colonne par colonne, sur une fenêtre de ±1,5 pas : meilleure corrélation
     * du signal « joint » avec lui-même décalé de 0,8 à 1,25 pas (perspective résiduelle), diminuée de la corrélation
     * à un demi-pas (un signal lent, dossier de chaise ou ombre, est corrélé à tout petit décalage ; un motif périodique
     * ne l'est pas à un demi-pas). Zone masquée, uniforme ou désordonnée ≈ 0.
     */
    private fun localQuality(j: DoubleArray, period: Double): DoubleArray {
        val n = j.size
        val half = max(3, (1.5 * period).roundToInt())
        val p = DoubleArray(n + 1); val p2 = DoubleArray(n + 1)
        for (i in 0 until n) { p[i + 1] = p[i] + j[i]; p2[i + 1] = p2[i] + j[i] * j[i] }
        val c = DoubleArray(n + 1)
        /** Corrélation maximale sur les décalages [lo, hi], colonne par colonne. */
        fun bestCorr(lo: Int, hi: Int): DoubleArray {
            val best = DoubleArray(n) { -1.0 }
            var lag = max(1, lo)
            while (lag <= hi && lag < n) {
                // Sommes cumulées de j[i]·j[i + lag] ; fenêtre des i : [x − half, x + half − lag].
                for (i in 0 until n - lag) c[i + 1] = c[i] + j[i] * j[i + lag]
                for (x in 0 until n) {
                    val a = (x - half).coerceAtLeast(0); val b = (x + half - lag).coerceAtMost(n - lag)
                    val m = b - a
                    if (m < period) continue
                    val sx = p[b] - p[a]; val sxx = p2[b] - p2[a]
                    val sy = p[b + lag] - p[a + lag]; val syy = p2[b + lag] - p2[a + lag]
                    val vx = sxx - sx * sx / m; val vy = syy - sy * sy / m
                    if (vx <= 1e-9 || vy <= 1e-9) continue
                    val r = (c[b] - c[a] - sx * sy / m) / sqrt(vx * vy)
                    if (r > best[x]) best[x] = r
                }
                lag += max(1, (hi - lo) / 8)
            }
            return best
        }
        val full = bestCorr((0.8 * period).roundToInt(), (1.25 * period).roundToInt())
        val halfLag = bestCorr((0.4 * period).roundToInt(), (0.625 * period).roundToInt())
        return DoubleArray(n) { ((full[it] - max(halfLag[it], 0.0) - 0.25) / 0.45).coerceIn(0.0, 1.0) }
    }

    /** Écart-type local de [p] sur une fenêtre d'un pas centrée sur chaque colonne. */
    private fun localStd(p: DoubleArray, period: Double): DoubleArray {
        val n = p.size
        val half = max(1, (0.5 * period).roundToInt())
        val s1 = DoubleArray(n + 1); val s2 = DoubleArray(n + 1)
        for (i in 0 until n) { s1[i + 1] = s1[i] + p[i]; s2[i + 1] = s2[i] + p[i] * p[i] }
        return DoubleArray(n) { x ->
            val a = (x - half).coerceAtLeast(0); val b = (x + half + 1).coerceAtMost(n)
            val m = (s1[b] - s1[a]) / (b - a)
            sqrt(((s2[b] - s2[a]) / (b - a) - m * m).coerceAtLeast(0.0))
        }
    }

    /**
     * Signal du score dense : signal « joint » lissé sur un quart de période (peu décalé par l'ombrage dissymétrique
     * des colonnes), centré, réduit par la médiane des pics du contour et plafonné à ±1,2 (une tige ou un objet très
     * contrasté ne pèse pas plus qu'un joint). Il sert à choisir entre les lois tirées ; la position fine vient des joints vus.
     */
    private fun denseSignal(j: DoubleArray, period: Double, x0: Double, x1: Double): DoubleArray? {
        val js = movingAverage(j, odd((0.25 * period).roundToInt()))
        val i0 = x0.roundToInt().coerceIn(0, js.size - 1); val i1 = x1.roundToInt().coerceIn(i0 + 1, js.size)
        val mean = js.copyOfRange(i0, i1).average()
        val peaks = (i0 + 1 until i1 - 1).filter { js[it] >= js[it - 1] && js[it] > js[it + 1] && js[it] > mean }.map { js[it] - mean }
        val ref = median(peaks)
        if (ref <= 1e-9) return null
        return DoubleArray(js.size) { ((js[it] - mean) / ref).coerceIn(-1.2, 1.2) }
    }

    // --- Bords : comparaison des éléments au gabarit de leur bande ---

    /**
     * Échantillons de l'intérieur de l'élément [k] (10 à 90 % du pas entre les joints k et k + 1, sans les joints
     * qu'il partage avec ses voisins) au centre de la bande : luminance puis gradient ; null hors de la face.
     */
    private fun elementSamples(b: Band, m: JointModel, k: Int, w: Int): DoubleArray? {
        val f = DoubleArray(2 * SAMPLES)
        for (s in 0 until SAMPLES) {
            val x = m.positionAt(k + 0.1 + 0.8 * (s + 0.5) / SAMPLES, b.yc)
            if (!x.isFinite() || x < 0 || x > w - 1) return null
            f[s] = interp(b.prof.lum, x); f[SAMPLES + s] = interp(b.prof.grad, x)
        }
        return f
    }

    /** Gabarit : médiane, échantillon par échantillon, des éléments centrés-réduits ; contraste médian (écart-type de luminance). */
    private fun template(feats: List<DoubleArray>): Pair<DoubleArray, Double> {
        val z = feats.map { normalizeParts(it) }
        val t = DoubleArray(2 * SAMPLES) { s -> median(z.map { it[s] }) }
        return t to median(feats.map { sdRange(it, 0, SAMPLES) })
    }

    /**
     * Ressemblance (corrélations moyennes luminance et gradient) sur les échantillons [s0, s1). Élément entier : pente
     * retirée (l'éclairage, commun au mur et au radiateur, ne doit pas faire ressembler un pan de mur à un élément).
     */
    private fun similarity(f: DoubleArray, t: DoubleArray, s0: Int, s1: Int): Double {
        val detrend = s1 - s0 == SAMPLES
        return (pearsonRange(f, t, s0, s1, detrend) + pearsonRange(f, t, SAMPLES + s0, SAMPLES + s1, detrend)) / 2
    }

    /**
     * Étendue de la bande : à partir des éléments confirmés extrêmes, ajout des voisins qui ressemblent au gabarit
     * (contraste suffisant), puis d'un élément dont seule la moitié intérieure ressemble (en partie masqué).
     */
    private fun extend(
        b: Band, m: JointModel, w: Int, first: Int, last: Int, kMin: Int, kMax: Int, tpl: Pair<DoubleArray, Double>,
        cL: Double, cR: Double,
    ): BandExtent {
        fun sim(k: Int, s0: Int, s1: Int) = elementSim(b, m, w, k, tpl, s0, s1)
        fun need(k: Int, base: Double) = if (k + 0.5 in cL..cR) base else max(base, SIM_OUTSIDE)
        var lo = first; var hi = last
        while (lo - 1 >= kMin && sim(lo - 1, 0, SAMPLES) >= need(lo - 1, SIM_FULL)) lo--
        while (hi + 1 <= kMax && sim(hi + 1, 0, SAMPLES) >= need(hi + 1, SIM_FULL)) hi++
        var loPartial = false; var hiPartial = false
        if (lo - 1 >= kMin && sim(lo - 1, SAMPLES / 2, SAMPLES) >= need(lo - 1, SIM_HALF)) { lo--; loPartial = true }
        if (hi + 1 <= kMax && sim(hi + 1, 0, SAMPLES / 2) >= need(hi + 1, SIM_HALF)) { hi++; hiPartial = true }
        val loBeyond = if (lo - 1 >= kMin) sim(lo - 1, 0, SAMPLES) else -1.0
        val hiBeyond = if (hi + 1 <= kMax) sim(hi + 1, 0, SAMPLES) else -1.0
        return BandExtent(lo, hi, loPartial, hiPartial, loBeyond, hiBeyond)
    }

    /**
     * Cohérence d'une loi : ressemblance moyenne des éléments du contour au gabarit de leur bande (médiane des éléments),
     * pondérée par la bande et la qualité locale. Les éléments d'une loi juste se ressemblent ; une loi décalée (sur des
     * tiges, des bords d'objets) place de vrais joints à l'intérieur de certains éléments.
     */
    private fun coherence(used: List<Band>, m: JointModel, x0: Double, x1: Double, w: Int, rel: (Band) -> Double): Double {
        var acc = 0.0; var tw = 0.0
        for (b in used) {
            val ka = m.indexAt(x0, b.yc); val kb = m.indexAt(x1, b.yc)
            if (!ka.isFinite() || !kb.isFinite()) continue
            val feats = (ceil(ka).toInt() until floor(kb).toInt()).mapNotNull { k -> elementSamples(b, m, k, w)?.let { k to it } }
            if (feats.size < 3) continue
            val tpl = template(feats.map { it.second }).first
            for ((k, f) in feats) {
                val wq = rel(b) * (0.2 + 0.8 * b.q(m.positionAt(k + 0.5, b.yc)))
                acc += wq * similarity(f, tpl, 0, SAMPLES); tw += wq
            }
        }
        return if (tw > 0) acc / tw else 0.0
    }

    /** Ressemblance de l'élément [k] au gabarit sur les échantillons [s0, s1) ; −1 hors de la face ou sans contraste. */
    private fun elementSim(b: Band, m: JointModel, w: Int, k: Int, tpl: Pair<DoubleArray, Double>, s0: Int, s1: Int): Double {
        val f = elementSamples(b, m, k, w) ?: return -1.0
        if (sdRange(f, s0, s1) < MIN_CONTRAST * tpl.second) return -1.0
        return similarity(f, tpl.first, s0, s1)
    }

    /**
     * Motif deux fois plus fin : signal « joint » brut aux demi-indices rapporté à celui aux indices entiers, sur les
     * éléments du radiateur (bandes retenues). Proche de 1 : rainure aussi marquée qu'un joint.
     */
    private fun halfIndexEvidence(used: List<Band>, signals: Map<Int, DoubleArray>, model: JointModel, count: Int): Double {
        var atJ = 0.0; var atH = 0.0; var nJ = 0; var nH = 0
        for (b in used) {
            val j = signals[b.index] ?: continue
            for (k in 0..count) {
                val x = model.positionAt(k.toDouble(), b.yc)
                if (x >= 0 && x <= j.size - 1) { atJ += b.weight * interp(j, x); nJ++ }
                if (k < count) {
                    val xh = model.positionAt(k + 0.5, b.yc)
                    if (xh >= 0 && xh <= j.size - 1) { atH += b.weight * interp(j, xh); nH++ }
                }
            }
        }
        if (nJ == 0 || nH == 0 || atJ <= 0) return 0.0
        return ((atH / nH) / (atJ / nJ)).coerceIn(0.0, 1.0)
    }

    /** Contraste de parité des joints observés (indices pairs / impairs) : 0 si égaux, 1 si une parité manque. */
    private fun parityContrast(joints: List<Pair<Int, Double>>): Double {
        val even = joints.filter { it.first % 2 == 0 }; val oddJ = joints.filter { it.first % 2 != 0 }
        if (even.size < 2 || oddJ.size < 2) return 0.0
        // Poids moyen par joint attendu de chaque parité (un joint absent compte pour 0).
        val ks = joints.map { it.first }
        val span = (ks.min()..ks.max())
        val nEven = span.count { it % 2 == 0 }.coerceAtLeast(1); val nOdd = span.count { it % 2 != 0 }.coerceAtLeast(1)
        val e = even.sumOf { it.second } / nEven; val o = oddJ.sumOf { it.second } / nOdd
        val hi = max(e, o)
        return if (hi <= 0) 0.0 else (1 - min(e, o) / hi).coerceIn(0.0, 1.0)
    }

    // --- Outils numériques ---

    private fun normalizeParts(f: DoubleArray): DoubleArray {
        val out = f.copyOf()
        for (part in 0 until 2) {
            val a = part * SAMPLES; val b = a + SAMPLES
            var m = 0.0; for (i in a until b) m += f[i]; m /= SAMPLES
            var v = 0.0; for (i in a until b) v += (f[i] - m) * (f[i] - m)
            val sd = sqrt(v / SAMPLES).coerceAtLeast(1e-9)
            for (i in a until b) out[i] = (f[i] - m) / sd
        }
        return out
    }

    private fun sdRange(f: DoubleArray, a: Int, b: Int): Double {
        var m = 0.0; for (i in a until b) m += f[i]; m /= (b - a)
        var v = 0.0; for (i in a until b) v += (f[i] - m) * (f[i] - m)
        return sqrt(v / (b - a))
    }

    /** Corrélation de x et y sur [a, b), éventuellement après retrait de la pente de chacun. */
    private fun pearsonRange(x: DoubleArray, y: DoubleArray, a: Int, b: Int, detrend: Boolean = false): Double {
        val n = b - a
        val dx = DoubleArray(n) { x[a + it] }; val dy = DoubleArray(n) { y[a + it] }
        for (v in listOf(dx, dy)) {
            val m = v.average()
            for (i in 0 until n) v[i] -= m
            if (detrend && n > 2) {
                val c = (n - 1) / 2.0
                var sxy = 0.0; var sxx = 0.0
                for (i in 0 until n) { sxy += (i - c) * v[i]; sxx += (i - c) * (i - c) }
                val slope = sxy / sxx
                for (i in 0 until n) v[i] -= slope * (i - c)
            }
        }
        var sxy = 0.0; var sxx = 0.0; var syy = 0.0
        for (i in 0 until n) { sxy += dx[i] * dy[i]; sxx += dx[i] * dx[i]; syy += dy[i] * dy[i] }
        return if (sxx <= 1e-12 || syy <= 1e-12) 0.0 else sxy / sqrt(sxx * syy)
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

    private fun quantileOf(v: List<Double>, q: Double): Double {
        if (v.isEmpty()) return 0.0
        val s = v.sorted()
        return s[((s.size - 1) * q).roundToInt().coerceIn(0, s.size - 1)]
    }

    private fun median(v: List<Double>): Double {
        if (v.isEmpty()) return 0.0
        val s = v.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
    }
}
