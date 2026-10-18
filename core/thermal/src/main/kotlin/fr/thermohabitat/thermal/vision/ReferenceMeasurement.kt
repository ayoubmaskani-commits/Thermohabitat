package fr.thermohabitat.thermal.vision

import kotlin.math.hypot
import kotlin.math.sqrt

/** Point dans l'image, en pixels de l'image d'origine. */
data class ImagePoint(val x: Double, val y: Double)

/** Objet de référence de dimensions connues posé dans le plan du radiateur. */
enum class ReferenceObject(val label: String, val lengthMm: Double?) {
    BANK_CARD_LONG("Carte bancaire – grand côté", 85.6),
    BANK_CARD_SHORT("Carte bancaire – petit côté", 53.98),
    A4_LONG("Feuille A4 – grand côté", 297.0),
    A4_SHORT("Feuille A4 – petit côté", 210.0),
    TAPE_MEASURE("Mètre ruban (longueur lue)", null),
    CUSTOM("Autre longueur connue", null),
}

data class MeasurementResult(
    val valueMm: Double,
    /** Incertitude absolue estimée (mm). */
    val uncertaintyMm: Double,
    val scaleMmPerPx: Double,
    val warnings: List<String>,
)

/**
 * Mesure assistée par photographie avec objet de référence.
 *
 * Hypothèses : photo prise de face, objet de référence posé contre le radiateur (même plan),
 * distance suffisante pour limiter la perspective. Sans référence d'échelle, AUCUNE dimension
 * n'est déduite d'une photographie.
 */
object ReferenceMeasurement {

    /** Erreur de pointé supposée par extrémité (px). */
    const val POINTING_ERROR_PX = 3.0

    /** Erreur relative forfaitaire liée à la perspective et à la profondeur de champ. */
    const val PERSPECTIVE_ERROR = 0.04

    fun measure(
        referenceStart: ImagePoint,
        referenceEnd: ImagePoint,
        referenceLengthMm: Double,
        targetStart: ImagePoint,
        targetEnd: ImagePoint,
    ): MeasurementResult {
        require(referenceLengthMm > 0) { "Longueur de référence invalide" }
        val refPx = distance(referenceStart, referenceEnd)
        val targetPx = distance(targetStart, targetEnd)
        require(refPx > 1.0) { "Les points de référence sont confondus" }
        require(targetPx > 1.0) { "Les points à mesurer sont confondus" }
        val scale = referenceLengthMm / refPx
        val value = targetPx * scale
        val pointingRef = sqrt(2.0) * POINTING_ERROR_PX / refPx
        val pointingTarget = sqrt(2.0) * POINTING_ERROR_PX / targetPx
        val relative = sqrt(pointingRef * pointingRef + pointingTarget * pointingTarget + PERSPECTIVE_ERROR * PERSPECTIVE_ERROR)
        val warnings = buildList {
            if (refPx < 80) add("La référence est petite dans l'image : rapprochez-vous ou utilisez une référence plus grande.")
            if (targetPx / refPx > 12) add("La dimension mesurée est très grande devant la référence : précision réduite.")
            add("Mesure valable uniquement si la référence est dans le plan du radiateur et la photo prise de face.")
        }
        return MeasurementResult(value, value * relative, scale, warnings)
    }

    private fun distance(a: ImagePoint, b: ImagePoint) = hypot(b.x - a.x, b.y - a.y)
}
