package fr.thermohabitat.thermal.vision

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Image en niveaux de gris (luminance 0–255) stockée ligne par ligne.
 * Indépendante d'Android : l'application convertit ses Bitmap dans ce format.
 */
class GrayImage(val width: Int, val height: Int, val pixels: FloatArray) {
    init {
        require(width > 0 && height > 0 && pixels.size == width * height) { "Dimensions d'image invalides" }
    }

    operator fun get(x: Int, y: Int): Float = pixels[y * width + x]

    /** Extrait un rectangle (coordonnées en pixels, bornées à l'image). */
    fun crop(left: Int, top: Int, right: Int, bottom: Int): GrayImage {
        val l = left.coerceIn(0, width - 1); val t = top.coerceIn(0, height - 1)
        val r = right.coerceIn(l + 1, width); val b = bottom.coerceIn(t + 1, height)
        val w = r - l; val h = b - t
        val out = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) out[y * w + x] = this[l + x, t + y]
        return GrayImage(w, h, out)
    }
}

/** Résultat du comptage assisté des éléments. */
data class ElementCountResult(
    /** Nombre d'éléments estimé, null si le signal n'est pas exploitable. */
    val count: Int?,
    /** Période détectée (largeur d'un élément) en pixels. */
    val periodPx: Double?,
    /** Indice de confiance 0–1 (netteté de la périodicité). */
    val confidence: Double,
    /** Positions (px, dans la zone analysée) des limites d'éléments estimées, pour contrôle visuel. */
    val boundariesPx: List<Double>,
    val message: String,
    /** Autres nombres plausibles (harmonique du motif, bord douteux), du plus au moins probable. */
    val alternatives: List<CountAlternative> = emptyList(),
    /** Vrai si un bord du radiateur est douteux : le nombre peut être faux d'une unité. */
    val plusOrMinusOne: Boolean = false,
    /** Bord gauche du radiateur estimé (px de la zone analysée ; négatif si le contour coupe le 1er élément). */
    val startPx: Double? = null,
    /** Bord droit du radiateur estimé (px de la zone analysée). */
    val endPx: Double? = null,
    /** Nombre de cycles du motif sur la largeur analysée (analyse spectrale), pour contrôle. */
    val spectralCycles: Double? = null,
    /** Nombre de joints détectés (dont interpolés). */
    val jointsDetected: Int = 0,
) {
    val reliable: Boolean get() = count != null && confidence >= ElementCounter.RELIABLE_CONFIDENCE
}

/** Nombre d'éléments alternatif et sa justification (score relatif 0–1 par rapport à la proposition). */
data class CountAlternative(val count: Int, val reason: String, val score: Double)

/** Rectangle (px de l'image analysée) à ignorer, p. ex. l'objet de référence posé sur la façade. */
data class PixelRect(val left: Double, val top: Double, val right: Double, val bottom: Double)

/**
 * Comptage assisté des éléments d'un radiateur à éléments.
 *
 * - [count] (outil historique) : zone rectangulaire d'une photo de FACE ; profil de luminance moyen par colonne,
 *   suppression de la tendance (éclairage), période dominante par autocorrélation, nombre = largeur / période.
 * - [countRectified] : façade redressée depuis une photo éventuellement prise de biais (voir [PhotoIdentifier]).
 *
 * Le résultat n'est qu'une proposition : il est affiché avec son indice de confiance et les
 * limites détectées superposées à la photo, et doit être confirmé par l'utilisateur.
 */
object ElementCounter {

    const val RELIABLE_CONFIDENCE = 0.45
    private const val MIN_CONFIDENCE = 0.20

    /**
     * @param image zone recadrée sur le corps du radiateur (sans pieds ni robinets)
     * @param minElements nombre minimal d'éléments attendu (borne la période maximale)
     * @param maxElements nombre maximal d'éléments attendu (borne la période minimale)
     */
    fun count(image: GrayImage, minElements: Int = 3, maxElements: Int = 60): ElementCountResult {
        val w = image.width
        if (w < 30) return ElementCountResult(null, null, 0.0, emptyList(), "Zone trop étroite pour l'analyse.")
        val y0 = (image.height * 0.2).toInt()
        val y1 = (image.height * 0.8).toInt().coerceAtLeast(y0 + 1)
        val profile = DoubleArray(w) { x ->
            var s = 0.0
            for (y in y0 until y1) s += image[x, y]
            s / (y1 - y0)
        }
        val detrended = detrend(profile, (w / 10).coerceAtLeast(3))
        val minPeriod = (w.toDouble() / maxElements).coerceAtLeast(3.0)
        val maxPeriod = (w.toDouble() / minElements).coerceAtMost(w / 2.0)
        if (maxPeriod <= minPeriod) return ElementCountResult(null, null, 0.0, emptyList(), "Plage de recherche invalide.")

        // Le pic discret peut dépasser w/minElements (arrondi du pic, recadrage un peu serré) : marge de 15 %,
        // bornée pour que round(w / période) reste ≥ minElements.
        val searchMax = minOf(maxPeriod * 1.15, w / (minElements - 0.5), w / 2.0)

        val ac = autocorrelation(detrended, searchMax.toInt() + 2)
        if (ac[0] <= 1e-9) return ElementCountResult(null, null, 0.0, emptyList(), "Image uniforme : aucun motif détecté.")

        // Pics locaux de l'autocorrélation dans la plage de périodes admissible.
        val peaks = (minPeriod.toInt().coerceAtLeast(2) until minOf(ac.size - 1, searchMax.toInt() + 1))
            .filter { l -> ac[l] > ac[l - 1] && ac[l] >= ac[l + 1] && ac[l] > 0 }
        if (peaks.isEmpty()) return ElementCountResult(null, null, 0.0, emptyList(), "Aucune périodicité nette.")
        val best = peaks.maxBy { ac[it] }
        // Fondamental : plus petit pic d'amplitude comparable (évite de retenir un multiple de la période).
        val fundamental = peaks.filter { ac[it] >= 0.85 * ac[best] }.min()
        val period = refinePeak(ac, fundamental)
        var confidence = (ac[fundamental] / ac[0]).coerceIn(0.0, 1.0)
        // Cohérence : l'harmonique 2·P doit aussi être corrélée positivement.
        val second = (2 * period).roundToInt()
        if (second < ac.size && ac[second] < 0.2 * ac[0]) confidence *= 0.7

        if (confidence < MIN_CONFIDENCE) {
            return ElementCountResult(null, period, confidence, emptyList(),
                "Motif trop faible : comptez les éléments manuellement.")
        }
        val count = (w / period).roundToInt().coerceAtLeast(1)
        val phase = bestPhase(detrended, period)
        val boundaries = generateSequence(phase) { it + period }.takeWhile { it < w }.toList()
        val msg = if (confidence >= RELIABLE_CONFIDENCE) {
            "Périodicité nette : $count éléments proposés (à vérifier visuellement)."
        } else {
            "Périodicité faible : $count éléments proposés avec prudence. Vérifiez et corrigez si nécessaire."
        }
        return ElementCountResult(count, period, confidence, boundaries, msg)
    }

    /** Rapport pas / hauteur plausible des radiateurs à éléments (a priori de pas sans échelle métrique). */
    val DEFAULT_PITCH_TO_HEIGHT: ClosedFloatingPointRange<Double> get() = RectifiedCounting.DEFAULT_PITCH_TO_HEIGHT

    /**
     * Comptage sur une façade REDRESSÉE (voir [PlaneRectifier.rectify]) : profils gradient + luminance, recherche
     * spectrale fine, gestion des harmoniques par un a priori de pas, comptage des joints et examen des bords.
     *
     * @param face image redressée du corps du radiateur (éléments verticaux équidistants)
     * @param pitchPriorPx plage de pas plausible en px redressés (déduite de l'échelle), sinon
     *   [DEFAULT_PITCH_TO_HEIGHT] × hauteur de l'image
     * @param excluded zones à ignorer (objet de référence posé sur la façade)
     * @return nombre proposé, alternatives, limites (bords + joints, px redressés) et confiance
     */
    fun countRectified(
        face: GrayImage,
        pitchPriorPx: ClosedFloatingPointRange<Double>? = null,
        minElements: Int = 3,
        maxElements: Int = 60,
        excluded: List<PixelRect> = emptyList(),
    ): ElementCountResult = RectifiedCounting.count(face, pitchPriorPx, minElements, maxElements, excluded)

    /** Limites régulières de [count] éléments entre [startPx] et [endPx] (nombre corrigé par l'utilisateur). */
    fun evenBoundaries(count: Int, startPx: Double, endPx: Double): List<Double> {
        require(count >= 1) { "Nombre d'éléments invalide" }
        return List(count + 1) { startPx + (endPx - startPx) * it / count }
    }

    private fun detrend(p: DoubleArray, window: Int): DoubleArray {
        val n = p.size
        val prefix = DoubleArray(n + 1)
        for (i in 0 until n) prefix[i + 1] = prefix[i] + p[i]
        return DoubleArray(n) { i ->
            val a = (i - window).coerceAtLeast(0); val b = (i + window + 1).coerceAtMost(n)
            p[i] - (prefix[b] - prefix[a]) / (b - a)
        }
    }

    private fun autocorrelation(x: DoubleArray, maxLag: Int): DoubleArray {
        val n = x.size
        val lags = minOf(maxLag, n - 1)
        return DoubleArray(lags + 1) { lag ->
            var s = 0.0
            for (i in 0 until n - lag) s += x[i] * x[i + lag]
            s / (n - lag)
        }
    }

    /** Interpolation parabolique de la position du pic. */
    private fun refinePeak(ac: DoubleArray, i: Int): Double {
        if (i <= 0 || i >= ac.size - 1) return i.toDouble()
        val a = ac[i - 1]; val b = ac[i]; val c = ac[i + 1]
        val denom = a - 2 * b + c
        if (abs(denom) < 1e-12) return i.toDouble()
        return i + 0.5 * (a - c) / denom
    }

    /** Décalage plaçant les limites d'éléments sur les minima de luminance (joints entre éléments). */
    private fun bestPhase(x: DoubleArray, period: Double): Double {
        var bestPhase = 0.0
        var bestScore = Double.POSITIVE_INFINITY
        val steps = period.toInt().coerceAtLeast(1)
        for (s in 0 until steps) {
            var pos = s.toDouble(); var sum = 0.0; var k = 0
            while (pos < x.size) { sum += x[pos.toInt()]; k++; pos += period }
            val score = if (k > 0) sum / k else Double.POSITIVE_INFINITY
            if (score < bestScore) { bestScore = score; bestPhase = s.toDouble() }
        }
        return bestPhase
    }

    /** Écart-type d'un profil (outil de diagnostic). */
    internal fun std(x: DoubleArray): Double {
        val m = x.average()
        return sqrt(x.sumOf { (it - m) * (it - m) } / x.size)
    }
}
