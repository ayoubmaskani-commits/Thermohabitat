package fr.thermohabitat.thermal.calc

import fr.thermohabitat.thermal.fr
import kotlinx.serialization.Serializable

/**
 * Vecteurs énergétiques. [kwhPerUnit] : énergie utile (PCI) par unité de facturation.
 * Les prix par défaut sont des EXEMPLES à remplacer par les tarifs de l'utilisateur.
 */
@Serializable
enum class EnergyCarrier(
    val label: String,
    val unit: String,
    val kwhPerUnit: Double,
    val examplePricePerUnit: Double,
    val defaultEfficiency: Double,
) {
    FUEL_OIL("Fioul domestique", "L", 9.96, 1.15, 0.85),
    NATURAL_GAS("Gaz naturel", "kWh PCS", 0.901, 0.11, 0.92),
    PROPANE("Propane", "kg", 12.8, 2.00, 0.90),
    ELECTRICITY("Électricité (effet Joule)", "kWh", 1.0, 0.21, 1.0),
    HEAT_PUMP("Pompe à chaleur (électricité)", "kWh", 1.0, 0.21, 3.0),
    WOOD_LOGS("Bois bûche", "stère", 1700.0, 90.0, 0.75),
    WOOD_PELLETS("Granulés de bois", "kg", 4.6, 0.40, 0.88),
}

@Serializable
data class EnergySettings(
    val currentCarrier: EnergyCarrier = EnergyCarrier.FUEL_OIL,
    /** Consommation annuelle de chauffage constatée (unité du vecteur), si connue. */
    val currentAnnualConsumption: Double? = null,
    /** Rendement saisonnier global de l'installation existante (production × distribution × émission × régulation). */
    val currentEfficiency: Double = 0.80,
    val prices: Map<EnergyCarrier, Double> = EnergyCarrier.entries.associateWith { it.examplePricePerUnit },
    /** Abonnements spécifiques au vecteur (€/an), facultatif. */
    val subscriptions: Map<EnergyCarrier, Double> = emptyMap(),
    val efficiencies: Map<EnergyCarrier, Double> = EnergyCarrier.entries.associateWith { it.defaultEfficiency },
    /** Facteur d'intermittence / de réduction (abaissements nocturnes), 0,7–1,0. */
    val intermittenceFactor: Double = 1.0,
    /** SCOP saisi par l'utilisateur (prioritaire sur le SCOP calculé), null sinon. */
    val heatPumpScopOverride: Double? = null,
) {
    fun priceOf(c: EnergyCarrier): Double = prices[c] ?: c.examplePricePerUnit
    fun efficiencyOf(c: EnergyCarrier): Double = efficiencies[c] ?: c.defaultEfficiency
}

data class CarrierEstimate(
    val carrier: EnergyCarrier,
    /** Rendement saisonnier ou SCOP retenu. */
    val efficiency: Double,
    val finalEnergyKwh: Double,
    val quantity: Double,
    val costEur: Double,
    val savingsVsCurrentEur: Double,
)

data class ConsumptionCalibration(
    val measuredConsumption: Double,
    val unit: String,
    /** Chaleur réellement fournie au bâtiment déduite de la consommation × rendement, kWh. */
    val deliveredHeatKwh: Double,
    val modelledNeedKwh: Double,
) {
    /** Rapport réel / modèle : > 1, le modèle sous-estime ; < 1, il surestime. */
    val ratio: Double get() = if (modelledNeedKwh > 0) deliveredHeatKwh / modelledNeedKwh else 0.0
}

data class EnergyEstimate(
    val heatLossCoefficientWk: Double,
    val degreeDays: Double,
    val annualNeedKwh: Double,
    val calibratedNeedKwh: Double?,
    val calibration: ConsumptionCalibration?,
    val carriers: List<CarrierEstimate>,
    val assumptions: List<String>,
)

/**
 * Estimation des besoins et consommations par la méthode des degrés-jours :
 * B = H · DJU · 24 / 1000 · i (kWh/an), avec H le coefficient de déperdition global (W/K).
 * Les DJU en base 18 °C intègrent conventionnellement ≈ 2 K d'apports gratuits.
 */
object EnergyEstimator {

    fun annualNeedKwh(heatLossCoefficientWk: Double, degreeDays: Double, intermittence: Double = 1.0): Double =
        heatLossCoefficientWk * degreeDays * 24.0 / 1000.0 * intermittence

    fun estimate(
        heatLossCoefficientWk: Double,
        degreeDays: Double,
        degreeDaysBase: Double,
        settings: EnergySettings,
        heatPumpScop: Double?,
        carriers: List<EnergyCarrier> = EnergyCarrier.entries,
        /**
         * Besoin modélisé (kWh/an) du bâtiment sur lequel la consommation a été constatée (état actuel).
         * Null : le bâtiment estimé est l'état actuel. Pour un scénario, le rapport de calage réel/modèle
         * de l'état actuel est appliqué au besoin modélisé du bâtiment modifié.
         */
        calibrationReferenceNeedKwh: Double? = null,
    ): EnergyEstimate {
        val need = annualNeedKwh(heatLossCoefficientWk, degreeDays, settings.intermittenceFactor)
        val referenceNeed = calibrationReferenceNeedKwh ?: need
        val calibration = settings.currentAnnualConsumption?.takeIf { it > 0 }?.let { c ->
            val delivered = c * settings.currentCarrier.kwhPerUnit * settings.currentEfficiency
            ConsumptionCalibration(c, settings.currentCarrier.unit, delivered, referenceNeed)
        }
        val basis = when {
            calibration == null -> need
            calibration.modelledNeedKwh > 0 -> need * calibration.ratio
            else -> calibration.deliveredHeatKwh
        }
        val scop = settings.heatPumpScopOverride ?: heatPumpScop ?: EnergyCarrier.HEAT_PUMP.defaultEfficiency

        fun estimateFor(c: EnergyCarrier, eff: Double): CarrierEstimate {
            val finalKwh = basis / eff
            val qty = finalKwh / c.kwhPerUnit
            val cost = qty * settings.priceOf(c) + (settings.subscriptions[c] ?: 0.0)
            return CarrierEstimate(c, eff, finalKwh, qty, cost, 0.0)
        }

        val currentEff = settings.currentEfficiency
        val current = estimateFor(settings.currentCarrier, currentEff)
        val list = carriers.map { c ->
            val eff = when (c) {
                settings.currentCarrier -> currentEff // installation actuelle (PAC comprise) : rendement constaté
                EnergyCarrier.HEAT_PUMP -> scop
                else -> settings.efficiencyOf(c)
            }
            estimateFor(c, eff).let { it.copy(savingsVsCurrentEur = current.costEur - it.costEur) }
        }

        val assumptions = buildList {
            add("Besoins = H × DJU × 24 / 1000 × i avec H = ${heatLossCoefficientWk.fr(0)} W/K, DJU = ${degreeDays.fr(0)} (base ${degreeDaysBase.fr(0)} °C), i = ${settings.intermittenceFactor.fr(2)}.")
            add("Les DJU base 18 °C supposent ≈ 2 K d'apports gratuits pour une consigne de 20 °C.")
            add("Chauffage seul : l'eau chaude sanitaire n'est pas incluse.")
            if (calibration != null) {
                add("Calage sur la consommation réelle : ${calibration.measuredConsumption.fr(0)} ${calibration.unit}/an × rendement ${currentEff.fr(2)} = ${calibration.deliveredHeatKwh.fr(0)} kWh utiles (modèle de l'état actuel : ${referenceNeed.fr(0)} kWh, rapport ${calibration.ratio.fr(2)}).")
                if (calibrationReferenceNeedKwh != null) {
                    add("Rapport de calage appliqué au besoin modélisé après travaux : ${need.fr(0)} × ${calibration.ratio.fr(2)} = ${basis.fr(0)} kWh.")
                }
            }
            if (settings.currentCarrier == EnergyCarrier.HEAT_PUMP) {
                add("PAC actuelle : SCOP constaté ${currentEff.fr(2)} (le SCOP calculé ne sert qu'à la PAC étudiée).")
            } else {
                add("SCOP retenu pour la PAC : ${scop.fr(2)}" + if (settings.heatPumpScopOverride != null) " (saisi)." else if (heatPumpScop != null) " (calculé)." else " (valeur par défaut).")
            }
            add("Prix de l'énergie : valeurs saisies ou exemples à mettre à jour.")
        }
        return EnergyEstimate(heatLossCoefficientWk, degreeDays, need, calibration?.let { basis }, calibration, list, assumptions)
    }
}
