package fr.thermohabitat.thermal.model

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.ln

/**
 * Conditions de référence auxquelles une puissance nominale est donnée.
 * EN 442 : 75/65/20 °C (ΔT 50 K). Anciennes documentations (DIN 4703) : 90/70/20 °C (ΔT 60 K).
 */
@Serializable
data class RatingCondition(
    val supplyC: Double = 75.0,
    val returnC: Double = 65.0,
    val ambientC: Double = 20.0,
) {
    /** Écart de température logarithmique moyen des conditions de référence (NaN si conditions incohérentes). */
    val lmtd: Double get() = if (isValid) logMeanTemperatureDifference(supplyC, returnC, ambientC) else Double.NaN

    /** Conditions physiquement cohérentes : départ ≥ retour > ambiance. */
    val isValid: Boolean get() = supplyC >= returnC && returnC > ambientC

    val label: String get() = "${supplyC.fmt()}/${returnC.fmt()}/${ambientC.fmt()} °C"

    companion object {
        val EN442 = RatingCondition(75.0, 65.0, 20.0)
        val DIN4703 = RatingCondition(90.0, 70.0, 20.0)
    }
}

/** Référence vers une photographie stockée localement (chemin relatif au dossier de l'application). */
@Serializable
data class PhotoRef(
    val id: Long = 0,
    val fileName: String,
    val kind: PhotoKind = PhotoKind.FRONT,
    val caption: String = "",
)

/** Fiche radiateur (émetteur à eau). */
@Serializable
data class Radiator(
    val id: Long = 0,
    /** Identifiant court lisible (R01, R02…). */
    val code: String = "",
    val roomId: Long? = null,
    val label: String = "",
    val type: RadiatorType = RadiatorType.UNKNOWN,
    val material: RadiatorMaterial = RadiatorMaterial.UNKNOWN,
    val era: RadiatorEra = RadiatorEra.UNKNOWN,
    /** Nombre d'éléments (radiateurs à éléments). */
    val sections: Int? = null,
    /** Nombre de colonnes par élément. */
    val columns: Int? = null,
    val panelType: PanelType? = null,
    val heightMm: Double? = null,
    val lengthMm: Double? = null,
    val depthMm: Double? = null,
    val dimensionsOrigin: ValueOrigin = ValueOrigin.UNKNOWN,
    val manufacturer: String = "",
    val model: String = "",
    val reference: String = "",
    /** Puissance nominale documentée aux conditions [rating], W. */
    val nominalPowerW: Double? = null,
    val nominalPowerOrigin: ValueOrigin = ValueOrigin.UNKNOWN,
    val rating: RatingCondition = RatingCondition.EN442,
    /** Exposant caractéristique n (fabricant). Null : valeur usuelle de la famille. */
    val exponent: Double? = null,
    val exponentOrigin: ValueOrigin = ValueOrigin.ESTIMATED,
    /** Incertitude relative admise sur une puissance documentée, %. */
    val documentedUncertaintyPct: Double = 5.0,
    val identificationMethod: IdentificationMethod = IdentificationMethod.MANUAL,
    /** Confiance de l'identification automatique (0–1), null si saisie manuelle. */
    val identificationConfidence: Double? = null,
    /** Caractéristiques confirmées par l'utilisateur : seules les fiches validées entrent dans l'étude. */
    val validated: Boolean = false,
    /** Débit d'eau de l'émetteur s'il est connu (équilibrage), kg/h. */
    val flowKgH: Double? = null,
    val photos: List<PhotoRef> = emptyList(),
    val notes: String = "",
) {
    val effectiveExponent: Double get() = exponent ?: type.defaultExponent
}

/**
 * Écart de température logarithmique moyen (LMTD) entre l'eau et l'air ambiant.
 * Bascule sur la moyenne arithmétique lorsque départ et retour sont confondus.
 */
fun logMeanTemperatureDifference(supplyC: Double, returnC: Double, ambientC: Double): Double {
    val dIn = supplyC - ambientC
    val dOut = returnC - ambientC
    require(dIn > 0 && dOut > 0) { "Les températures d'eau doivent être supérieures à l'ambiance" }
    if (abs(dIn - dOut) < 1e-6) return dIn
    return (dIn - dOut) / ln(dIn / dOut)
}

internal fun Double.fmt(): String =
    if (this % 1.0 == 0.0) this.toLong().toString() else String.format(java.util.Locale.FRANCE, "%.1f", this)
