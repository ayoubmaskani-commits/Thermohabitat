package fr.thermohabitat.thermal.calc

import kotlin.math.PI
import kotlin.math.ln

/**
 * Déperditions par le sol des planchers sur terre-plein.
 *
 * Coefficient U équivalent selon NF EN ISO 13370 (dalle sur terre-plein) puis prise en compte
 * des facteurs de la NF EN 12831-1 : H_T,ig = f_g1 · f_g2 · Σ(A · U_equiv) · G_w.
 */
object GroundHeatLoss {

    /** Facteur de correction de la variation annuelle de la température extérieure. */
    const val F_G1 = 1.45

    private const val RSI_FLOOR = 0.17
    private const val RSE = 0.04

    /** Dimension caractéristique du plancher B' = A / (0,5 · P). */
    fun characteristicDimension(areaM2: Double, exposedPerimeterM: Double): Double {
        require(areaM2 > 0) { "Surface de plancher nulle" }
        require(exposedPerimeterM > 0) { "Périmètre exposé nul" }
        return areaM2 / (0.5 * exposedPerimeterM)
    }

    /**
     * U équivalent d'une dalle sur terre-plein (EN ISO 13370, § 9.1).
     *
     * @param bPrime dimension caractéristique B' (m)
     * @param wallThicknessM épaisseur totale des murs périphériques w (m)
     * @param floorResistance résistance thermique du plancher Rf (m²·K/W), hors résistances superficielles
     * @param groundConductivity conductivité du sol λ (W/(m·K))
     */
    fun slabUValue(
        bPrime: Double,
        wallThicknessM: Double,
        floorResistance: Double,
        groundConductivity: Double = 2.0,
    ): Double {
        val dt = wallThicknessM + groundConductivity * (RSI_FLOOR + floorResistance + RSE)
        return if (dt < bPrime) {
            2.0 * groundConductivity / (PI * bPrime + dt) * ln(PI * bPrime / dt + 1.0)
        } else {
            groundConductivity / (0.457 * bPrime + dt)
        }
    }

    /** f_g2 = (θint − θm,e) / (θint − θe). */
    fun fg2(interiorC: Double, meanAnnualOutdoorC: Double, designOutdoorC: Double): Double {
        val denom = interiorC - designOutdoorC
        if (denom <= 0) return 0.0
        return ((interiorC - meanAnnualOutdoorC) / denom).coerceAtLeast(0.0)
    }

    /** Facteur de nappe phréatique G_w. */
    fun groundwaterFactor(shallow: Boolean): Double = if (shallow) 1.15 else 1.0
}
