package fr.thermohabitat.thermal.calc

import fr.thermohabitat.thermal.fr
import fr.thermohabitat.thermal.model.PanelType
import fr.thermohabitat.thermal.model.Radiator
import fr.thermohabitat.thermal.model.RadiatorType
import fr.thermohabitat.thermal.model.RatingCondition
import fr.thermohabitat.thermal.model.ValueOrigin
import fr.thermohabitat.thermal.model.logMeanTemperatureDifference
import kotlinx.serialization.Serializable
import kotlin.math.pow

/** Régime d'eau départ / retour. */
@Serializable
data class Regime(val supplyC: Double, val returnC: Double) {
    val label: String get() = "${supplyC.fmt0()}/${returnC.fmt0()} °C"
    val meanC: Double get() = (supplyC + returnC) / 2.0

    companion object {
        /** Régimes proposés pour l'étude des puissances disponibles. */
        val STANDARD: List<Regime> = listOf(
            Regime(75.0, 65.0), Regime(65.0, 55.0), Regime(60.0, 50.0), Regime(55.0, 45.0),
            Regime(50.0, 40.0), Regime(45.0, 35.0), Regime(40.0, 30.0),
        )
    }
}

private fun Double.fmt0(): String = if (this % 1.0 == 0.0) toLong().toString() else fr(1)

/** Base de la puissance nominale retenue pour un émetteur. */
enum class PowerBasis(val label: String) {
    DOCUMENTED("Puissance documentée"),
    ESTIMATED("Puissance estimée"),
    NONE("Puissance inconnue"),
}

/**
 * Puissance nominale retenue d'un émetteur aux conditions [rating], avec sa plage d'incertitude.
 */
data class NominalPower(
    val nominalW: Double,
    val lowW: Double,
    val highW: Double,
    val rating: RatingCondition,
    val exponent: Double,
    val basis: PowerBasis,
    val method: String,
) {
    val relativeUncertainty: Double get() = if (nominalW > 0) (highW - nominalW) / nominalW else 0.0
}

/** Puissance d'un émetteur à un régime donné. */
data class RadiatorOutput(
    val radiatorId: Long,
    val code: String,
    val label: String,
    val powerW: Double,
    val lowW: Double,
    val highW: Double,
    val basis: PowerBasis,
    val validated: Boolean,
    val returnC: Double,
)

/**
 * Correction de puissance des émetteurs selon la NF EN 442 :
 * Φ = Φn · (ΔT / ΔTn)^n, où ΔT est l'écart logarithmique moyen eau/air (plus juste que
 * l'écart arithmétique pour les basses températures de retour).
 */
object RadiatorPower {

    /** Capacité thermique massique de l'eau (J/(kg·K)). */
    const val WATER_CP = 4186.0

    fun correctionFactor(rating: RatingCondition, exponent: Double, supplyC: Double, returnC: Double, ambientC: Double): Double {
        if (supplyC <= ambientC || returnC <= ambientC || !rating.isValid) return 0.0
        val dt = logMeanTemperatureDifference(supplyC, minOf(returnC, supplyC), ambientC)
        return (dt / rating.lmtd).pow(exponent)
    }

    fun outputAt(nominalW: Double, rating: RatingCondition, exponent: Double, supplyC: Double, returnC: Double, ambientC: Double): Double =
        nominalW * correctionFactor(rating, exponent, supplyC, returnC, ambientC)

    /**
     * Puissance émise à débit d'eau imposé : on résout Φ = ṁ·cp·(Ts − Tr) = Φn·(LMTD/LMTDn)^n
     * par dichotomie sur la température de retour.
     *
     * @return paire (puissance W, température de retour °C)
     */
    fun outputWithFlow(
        nominalW: Double,
        rating: RatingCondition,
        exponent: Double,
        supplyC: Double,
        ambientC: Double,
        flowKgH: Double,
    ): Pair<Double, Double> {
        if (supplyC <= ambientC + 0.01 || flowKgH <= 0 || nominalW <= 0) return 0.0 to supplyC
        val mCp = flowKgH / 3600.0 * WATER_CP
        var lo = ambientC + 1e-6
        var hi = supplyC - 1e-6
        repeat(80) {
            val mid = (lo + hi) / 2.0
            val water = mCp * (supplyC - mid)
            val rad = outputAt(nominalW, rating, exponent, supplyC, mid, ambientC)
            if (water > rad) lo = mid else hi = mid
        }
        val tr = (lo + hi) / 2.0
        return mCp * (supplyC - tr) to tr
    }

    /** Débit de conception correspondant aux conditions nominales de l'émetteur (kg/h). */
    fun nominalFlowKgH(nominalW: Double, rating: RatingCondition): Double {
        val dt = rating.supplyC - rating.returnC
        return if (dt <= 0) 0.0 else nominalW / (WATER_CP * dt) * 3600.0
    }

    /**
     * Puissance nominale retenue : valeur documentée si elle existe, sinon estimation géométrique.
     * Retourne null si aucune puissance ne peut être établie honnêtement.
     */
    /**
     * Émetteur retenu dans le bilan : validé ET puissance nominale établie à des conditions de référence
     * cohérentes. Une fiche validée dont la puissance est devenue indéterminable compte comme « en attente »
     * (puissance inconnue), jamais comme un émetteur de 0 W.
     */
    fun countsInStudy(radiator: Radiator): Boolean =
        radiator.validated && resolveNominal(radiator)?.let { it.nominalW > 0 && it.rating.isValid } == true

    fun resolveNominal(radiator: Radiator): NominalPower? {
        val n = radiator.effectiveExponent
        val documented = radiator.nominalPowerW
        if (documented != null && documented > 0 && radiator.nominalPowerOrigin != ValueOrigin.UNKNOWN) {
            val u = (radiator.documentedUncertaintyPct / 100.0).coerceIn(0.0, 0.9)
            val basis = if (radiator.nominalPowerOrigin == ValueOrigin.ESTIMATED) PowerBasis.ESTIMATED else PowerBasis.DOCUMENTED
            return NominalPower(
                documented, documented * (1 - u), documented * (1 + u), radiator.rating, n, basis,
                "Puissance ${radiator.nominalPowerOrigin.label.lowercase()} à ${radiator.rating.label}",
            )
        }
        val estimate = RadiatorEstimator.estimate(radiator) ?: return null
        return NominalPower(
            estimate.nominalW, estimate.lowW, estimate.highW, RatingCondition.EN442, n, PowerBasis.ESTIMATED, estimate.method,
        )
    }

    /** Puissance de l'émetteur au régime donné, en conservant la plage d'incertitude. */
    fun outputOf(radiator: Radiator, supplyC: Double, returnC: Double, ambientC: Double): RadiatorOutput {
        val nominal = resolveNominal(radiator)
        if (nominal == null || returnC <= ambientC || supplyC <= ambientC) {
            return RadiatorOutput(radiator.id, radiator.code, radiator.label, 0.0, 0.0, 0.0,
                nominal?.basis ?: PowerBasis.NONE, radiator.validated, returnC)
        }
        val k = correctionFactor(nominal.rating, nominal.exponent, supplyC, returnC, ambientC)
        return RadiatorOutput(
            radiator.id, radiator.code, radiator.label,
            nominal.nominalW * k, nominal.lowW * k, nominal.highW * k,
            nominal.basis, radiator.validated, returnC,
        )
    }

    /** Puissance de l'émetteur à débit imposé (débit fabricant/équilibrage, sinon débit nominal). */
    fun outputOfWithFlow(radiator: Radiator, supplyC: Double, ambientC: Double, flowKgH: Double? = null): RadiatorOutput {
        val nominal = resolveNominal(radiator)
            ?: return RadiatorOutput(radiator.id, radiator.code, radiator.label, 0.0, 0.0, 0.0, PowerBasis.NONE, radiator.validated, supplyC)
        val flow = flowKgH ?: radiator.flowKgH ?: nominalFlowKgH(nominal.nominalW, nominal.rating)
        val (p, tr) = outputWithFlow(nominal.nominalW, nominal.rating, nominal.exponent, supplyC, ambientC, flow)
        val ratio = if (nominal.nominalW > 0) p / nominal.nominalW else 0.0
        return RadiatorOutput(
            radiator.id, radiator.code, radiator.label, p,
            nominal.lowW * ratio, nominal.highW * ratio, nominal.basis, radiator.validated, tr,
        )
    }
}

/** Estimation de puissance nominale (ΔT 50 K, EN 442) avec plage d'incertitude. */
data class PowerEstimate(
    val nominalW: Double,
    val lowW: Double,
    val highW: Double,
    val relativeUncertainty: Double,
    val method: String,
)

/**
 * Estimation de la puissance nominale d'un émetteur dont la référence est inconnue,
 * à partir de sa géométrie MESURÉE (jamais à partir de la seule photographie).
 *
 * Les corrélations sont des lois de puissance ajustées sur des ordres de grandeur de catalogues
 * (DIN 4703 pour les éléments en fonte ramenés à ΔT 50 K, catalogues EN 442 pour les panneaux,
 * tubulaires et aluminium). Elles donnent un ORDRE DE GRANDEUR : la plage d'incertitude est
 * systématiquement affichée et une puissance documentée doit lui être préférée.
 */
object RadiatorEstimator {

    // Élément fonte de référence : H = 580 mm, profondeur 160 mm ≈ 78 W à ΔT 50 K.
    private const val CAST_IRON_REF_W = 78.0
    private const val CAST_IRON_REF_H = 580.0
    private const val CAST_IRON_REF_D = 160.0
    private const val HEIGHT_EXPONENT = 0.87
    private const val DEPTH_EXPONENT = 0.70

    // Élément aluminium de référence : H = 680 mm, profondeur 95 mm ≈ 130 W.
    private const val ALU_REF_W = 130.0
    private const val ALU_REF_H = 680.0
    private const val ALU_REF_D = 95.0

    // Tubulaire acier : ≈ 19 W par colonne pour H = 600 mm.
    private const val TUBULAR_W_PER_COLUMN_600 = 19.0

    /** Profondeur d'élément fonte typique selon le nombre de colonnes (mm) quand elle n'est pas mesurée. */
    fun typicalCastIronDepthMm(columns: Int): Double = when {
        columns <= 1 -> 70.0
        columns == 2 -> 100.0
        columns == 3 -> 145.0
        columns == 4 -> 195.0
        else -> 250.0
    }

    fun estimate(radiator: Radiator): PowerEstimate? {
        val h = radiator.heightMm
        return when (radiator.type) {
            RadiatorType.CAST_IRON_COLUMN, RadiatorType.STEEL_COLUMN -> sectional(radiator, h, cast = radiator.type == RadiatorType.CAST_IRON_COLUMN)
            RadiatorType.ALUMINIUM_SECTIONAL -> aluminium(radiator, h)
            RadiatorType.STEEL_TUBULAR -> tubular(radiator, h)
            RadiatorType.STEEL_PANEL -> panel(radiator, h)
            RadiatorType.CAST_IRON_PLATE -> plate(radiator, h)
            RadiatorType.TOWEL_RAIL -> towelRail(radiator, h)
            RadiatorType.DECORATIVE, RadiatorType.CONVECTOR, RadiatorType.UNKNOWN -> null
        }
    }

    private fun sectional(r: Radiator, h: Double?, cast: Boolean): PowerEstimate? {
        val sections = r.sections ?: return null
        if (h == null || h <= 0 || sections <= 0) return null
        var uncertainty = if (cast) 0.25 else 0.30
        val depth = r.depthMm ?: r.columns?.let { typicalCastIronDepthMm(it) }?.also { uncertainty += 0.10 } ?: return null
        val perSection = CAST_IRON_REF_W * (h / CAST_IRON_REF_H).pow(HEIGHT_EXPONENT) * (depth / CAST_IRON_REF_D).pow(DEPTH_EXPONENT)
        val total = perSection * sections
        val what = if (cast) "élément fonte" else "élément acier embouti"
        return build(total, uncertainty,
            "Corrélation géométrique $what (${perSection.fr(0)} W/élément × $sections, H ${h.fr(0)} mm, P ${depth.fr(0)} mm)")
    }

    private fun aluminium(r: Radiator, h: Double?): PowerEstimate? {
        val sections = r.sections ?: return null
        if (h == null || h <= 0 || sections <= 0) return null
        var uncertainty = 0.20
        val depth = r.depthMm ?: ALU_REF_D.also { uncertainty += 0.05 }
        val perSection = ALU_REF_W * (h / ALU_REF_H).pow(HEIGHT_EXPONENT) * (depth / ALU_REF_D).pow(DEPTH_EXPONENT)
        return build(perSection * sections, uncertainty,
            "Corrélation élément aluminium (${perSection.fr(0)} W/élément × $sections)")
    }

    private fun tubular(r: Radiator, h: Double?): PowerEstimate? {
        val sections = r.sections ?: return null
        val columns = r.columns ?: return null
        if (h == null || h <= 0 || sections <= 0 || columns <= 0) return null
        val perSection = TUBULAR_W_PER_COLUMN_600 * columns.toDouble().pow(0.9) * (h / 600.0).pow(0.9)
        return build(perSection * sections, 0.25,
            "Corrélation tubulaire acier $columns colonnes (${perSection.fr(0)} W/élément × $sections)")
    }

    private fun panel(r: Radiator, h: Double?): PowerEstimate? {
        val length = r.lengthMm ?: return null
        val type = r.panelType ?: return null
        if (h == null || h <= 0 || length <= 0) return null
        val p = type.outputKwPerMeterAt600 * 1000.0 * (length / 1000.0) * (h / 600.0).pow(0.86)
        val uncertainty = if (type == PanelType.T10 || type == PanelType.T20) 0.25 else 0.15
        return build(p, uncertainty, "Ordre de grandeur catalogue ${type.label}, L ${length.fr(0)} × H ${h.fr(0)} mm")
    }

    private fun plate(r: Radiator, h: Double?): PowerEstimate? {
        val length = r.lengthMm ?: return null
        if (h == null || h <= 0 || length <= 0) return null
        val plates = r.columns ?: 1
        // ≈ 1,1 kW/m² de façade par plaque, rendement décroissant pour les plaques suivantes.
        val perM2 = 1100.0 * plates.toDouble().pow(0.7)
        val p = perM2 * (length / 1000.0) * (h / 1000.0)
        return build(p, 0.30, "Estimation surfacique fonte à plaques ($plates plaque(s))")
    }

    private fun towelRail(r: Radiator, h: Double?): PowerEstimate? {
        val length = r.lengthMm ?: return null
        if (h == null || h <= 0 || length <= 0) return null
        val p = 850.0 * (length / 1000.0) * (h / 1000.0)
        return build(p, 0.30, "Estimation surfacique sèche-serviettes (≈ 850 W/m² de façade)")
    }

    private fun build(nominal: Double, uncertainty: Double, method: String) = PowerEstimate(
        nominalW = nominal,
        lowW = nominal * (1 - uncertainty),
        highW = nominal * (1 + uncertainty),
        relativeUncertainty = uncertainty,
        method = "$method – incertitude ±${(uncertainty * 100).fr(0)} %",
    )
}
