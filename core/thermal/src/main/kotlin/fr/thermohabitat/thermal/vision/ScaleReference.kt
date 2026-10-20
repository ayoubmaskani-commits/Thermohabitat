package fr.thermohabitat.thermal.vision

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot

/** Forme d'un objet de référence : rectangle pointé par ses 4 coins, ou disque pointé sur son diamètre. */
enum class ScaleObjectShape { RECTANGLE, DISC }

/**
 * Objets de dimensions normalisées pouvant servir d'échelle, posés à plat contre la façade du radiateur.
 * Pour un disque, [longMm] = [shortMm] = diamètre.
 */
enum class ScaleObject(val label: String, val longMm: Double, val shortMm: Double, val shape: ScaleObjectShape, val note: String) {
    BANK_CARD("Carte bancaire", 85.60, 53.98, ScaleObjectShape.RECTANGLE, "format ISO/IEC 7810 ID-1"),
    A4_SHEET("Feuille A4", 297.0, 210.0, ScaleObjectShape.RECTANGLE, "format ISO 216"),
    A5_SHEET("Feuille A5", 210.0, 148.0, ScaleObjectShape.RECTANGLE, "format ISO 216"),
    EURO_5("Billet de 5 €", 120.0, 62.0, ScaleObjectShape.RECTANGLE, "série Europe"),
    EURO_10("Billet de 10 €", 127.0, 67.0, ScaleObjectShape.RECTANGLE, "série Europe"),
    EURO_20("Billet de 20 €", 133.0, 72.0, ScaleObjectShape.RECTANGLE, "série Europe"),
    EURO_50("Billet de 50 €", 140.0, 77.0, ScaleObjectShape.RECTANGLE, "série Europe"),
    EURO_100("Billet de 100 €", 147.0, 77.0, ScaleObjectShape.RECTANGLE, "série Europe"),
    COIN_1_EURO("Pièce de 1 €", 23.25, 23.25, ScaleObjectShape.DISC, "diamètre"),
    COIN_2_EURO("Pièce de 2 €", 25.75, 25.75, ScaleObjectShape.DISC, "diamètre"),
    COIN_50_CENTS("Pièce de 50 centimes", 24.25, 24.25, ScaleObjectShape.DISC, "diamètre");

    /** Nombre de points à pointer : 4 coins ou 2 extrémités d'un diamètre. */
    val pointCount: Int get() = if (shape == ScaleObjectShape.RECTANGLE) 4 else 2

    /** Rapport grand côté / petit côté. */
    val ratio: Double get() = longMm / shortMm
}

/** Dimension du radiateur relevée au mètre. */
enum class RadiatorDimension(val label: String) { HEIGHT("Hauteur"), LENGTH("Longueur") }

/** Valeur (mm) et incertitude-type (mm). */
data class DimensionEstimate(val valueMm: Double, val uncertaintyMm: Double) {
    val relativeUncertainty: Double get() = if (valueMm != 0.0) uncertaintyMm / abs(valueMm) else Double.POSITIVE_INFINITY
}

/** Référence d'échelle choisie par l'utilisateur pour l'analyse d'une photo. */
sealed interface ScaleReference {
    val label: String

    /** Objet rectangulaire de dimensions connues plaqué contre la façade : 4 coins pointés (ordre quelconque). */
    data class PlanarObject(val obj: ScaleObject, val corners: List<ImagePoint>) : ScaleReference {
        init {
            require(obj.shape == ScaleObjectShape.RECTANGLE) { "${obj.label} : pointer le diamètre (2 points)" }
            require(corners.size == 4) { "Placez les 4 coins de l'objet de référence." }
        }
        override val label: String get() = obj.label
    }

    /** Deux points du plan de la façade séparés d'une longueur connue (mètre ruban, cote, diamètre d'une pièce). */
    data class KnownLength(
        val start: ImagePoint,
        val end: ImagePoint,
        val lengthMm: Double,
        override val label: String = "Longueur connue",
        /** Référence petite (pièce) : avertissement de précision. */
        val smallObject: Boolean = false,
    ) : ScaleReference {
        init { require(lengthMm > 0) { "Longueur de référence invalide" } }
    }

    /** Hauteur ou longueur du corps du radiateur mesurée au mètre. */
    data class MeasuredDimension(
        val dimension: RadiatorDimension,
        val valueMm: Double,
        val uncertaintyMm: Double = 5.0,
    ) : ScaleReference {
        init { require(valueMm > 0 && uncertaintyMm >= 0) { "Dimension mesurée invalide" } }
        override val label: String get() = "${dimension.label} mesurée au mètre"
    }

    /** Pas d'élément standard supposé (type connu) : dimensions ESTIMÉES, incertitude large. */
    data class StandardPitch(
        val pitchMm: Double,
        val uncertaintyMm: Double,
        override val label: String = "Pas d'élément standard",
    ) : ScaleReference {
        init { require(pitchMm > 0 && uncertaintyMm >= 0) { "Pas standard invalide" } }
    }

    companion object {
        /** Pièce pointée aux deux extrémités d'un diamètre. */
        fun coin(obj: ScaleObject, start: ImagePoint, end: ImagePoint): KnownLength {
            require(obj.shape == ScaleObjectShape.DISC) { "${obj.label} : pointer les 4 coins" }
            return KnownLength(start, end, obj.longMm, obj.label, smallObject = true)
        }

        /** Mètre ruban (ou toute longueur lue) entre deux points de la façade. */
        fun tapeMeasure(start: ImagePoint, end: ImagePoint, lengthMm: Double): KnownLength =
            KnownLength(start, end, lengthMm, "Mètre ruban")
    }
}

/** Côtés (mm) d'un quadrilatère reporté dans le plan métrique de la façade. */
data class PlaneQuadDimensions(
    val topMm: Double,
    val bottomMm: Double,
    val leftMm: Double,
    val rightMm: Double,
    /** Plus grand écart des angles à 90° (degrés) : contour incohérent ou référence hors du plan si élevé. */
    val maxAngleErrorDeg: Double,
) {
    val lengthMm: Double get() = (topMm + bottomMm) / 2
    val heightMm: Double get() = (leftMm + rightMm) / 2
}

/** Calculs d'échelle dans le plan de la façade. */
object ScaleCalculator {

    /**
     * Homographie image → plan de la façade en mm, EXACTE (sans focale) : les 4 coins d'un objet rectangulaire de
     * dimensions connues, dans le plan de la façade, sont envoyés sur son rectangle réel.
     * @param landscape grand côté de l'objet horizontal dans le plan de la façade
     */
    fun metricHomography(obj: ScaleObject, corners: List<ImagePoint>, landscape: Boolean): Homography? {
        require(obj.shape == ScaleObjectShape.RECTANGLE) { "Objet à 4 coins requis" }
        val q = Homography.orderCorners(corners)
        val (w, h) = if (landscape) obj.longMm to obj.shortMm else obj.shortMm to obj.longMm
        return Homography.fromPoints(q, Homography.rectangle(w, h))
    }

    /** Côtés et angles du quadrilatère image [quad] (ordre quelconque) reporté en mm par [imageToMm]. */
    fun quadDimensions(imageToMm: Homography, quad: List<ImagePoint>): PlaneQuadDimensions {
        val p = Homography.orderCorners(quad).map { imageToMm.map(it) }
        fun d(a: ImagePoint, b: ImagePoint) = hypot(b.x - a.x, b.y - a.y)
        var worst = 0.0
        for (i in 0 until 4) {
            val a = p[(i + 3) % 4]; val o = p[i]; val b = p[(i + 1) % 4]
            val ux = a.x - o.x; val uy = a.y - o.y; val vx = b.x - o.x; val vy = b.y - o.y
            val c = (ux * vx + uy * vy) / (hypot(ux, uy) * hypot(vx, vy))
            worst = maxOf(worst, abs(Math.toDegrees(acos(c.coerceIn(-1.0, 1.0))) - 90))
        }
        return PlaneQuadDimensions(d(p[0], p[1]), d(p[3], p[2]), d(p[0], p[3]), d(p[1], p[2]), worst)
    }
}
