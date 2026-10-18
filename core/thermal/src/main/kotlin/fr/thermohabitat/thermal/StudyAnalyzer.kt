package fr.thermohabitat.thermal

import fr.thermohabitat.thermal.calc.BuildingCoverage
import fr.thermohabitat.thermal.calc.BuildingHeatLoss
import fr.thermohabitat.thermal.calc.BuildingSupplyRequirement
import fr.thermohabitat.thermal.calc.CoverageAnalyzer
import fr.thermohabitat.thermal.calc.EnergyEstimate
import fr.thermohabitat.thermal.calc.EnergyEstimator
import fr.thermohabitat.thermal.calc.EnergySettings
import fr.thermohabitat.thermal.calc.HeatLossCalculator
import fr.thermohabitat.thermal.calc.HeatPumpAnalysis
import fr.thermohabitat.thermal.calc.HeatPumpModel
import fr.thermohabitat.thermal.calc.HeatPumpPerformance
import fr.thermohabitat.thermal.calc.HeatingCurve
import fr.thermohabitat.thermal.calc.HydraulicMode
import fr.thermohabitat.thermal.calc.RadiatorPower
import fr.thermohabitat.thermal.calc.Regime
import fr.thermohabitat.thermal.calc.SeasonalPerformance
import fr.thermohabitat.thermal.calc.SupplyTemperatureSolver
import fr.thermohabitat.thermal.model.Building
import fr.thermohabitat.thermal.scenario.Scenario
import fr.thermohabitat.thermal.scenario.ScenarioEngine
import kotlinx.serialization.Serializable
import kotlin.math.abs

/** Paramètres de l'étude (régimes, hydraulique, PAC, énergie). */
@Serializable
data class StudySettings(
    /** Régime d'eau de référence de l'étude (ex. PAC 55/45 °C). */
    val studyRegime: Regime = Regime(55.0, 45.0),
    /** Débit imposé (true) ou écart départ/retour constant (false). */
    val fixedFlow: Boolean = false,
    /** Écart départ/retour utilisé pour la recherche de la température de départ minimale, K. */
    val systemDeltaK: Double = 10.0,
    val heatingCurve: HeatingCurve = HeatingCurve(),
    val heatPump: HeatPumpModel = HeatPumpModel(),
    /** Fraction du COP de Carnot du modèle générique. */
    val genericCarnotEfficiency: Double = 0.50,
    val energy: EnergySettings = EnergySettings(),
    /** Température ambiante de référence pour les fiches radiateur, °C. */
    val radiatorSheetAmbientC: Double = 20.0,
) {
    fun modeFor(regime: Regime): HydraulicMode =
        if (fixedFlow) HydraulicMode.FixedFlow else HydraulicMode.FixedDeltaT(regime.supplyC - regime.returnC)

    val solverMode: HydraulicMode get() = if (fixedFlow) HydraulicMode.FixedFlow else HydraulicMode.FixedDeltaT(systemDeltaK)
}

/** Compatibilité estimée avec une pompe à chaleur air/eau. */
enum class PacCompatibility(val label: String, val description: String) {
    VERY_FAVORABLE("Très favorable", "Température de départ ≤ 45 °C : PAC basse température adaptée."),
    FAVORABLE("Favorable", "Température de départ ≤ 55 °C : PAC moyenne température."),
    POSSIBLE("Possible sous conditions", "Température de départ ≤ 65 °C : PAC haute température, isolation ou remplacement d'émetteurs."),
    UNFAVORABLE("Défavorable en l'état", "Départ > 65 °C ou pièces non couvertes : travaux préalables nécessaires."),
    UNDETERMINED("Indéterminée", "Aucun radiateur validé ou aucune pièce saisie.");

    companion object {
        fun from(supply: BuildingSupplyRequirement): PacCompatibility {
            val t = supply.requiredSupplyC
            return when {
                supply.rooms.isEmpty() || supply.rooms.none { it.hasEmitters } -> UNDETERMINED
                supply.uncoverableRooms.isNotEmpty() || t == null -> UNFAVORABLE
                supply.roomsWithoutEmitters.isNotEmpty() && t > 55.0 -> UNFAVORABLE
                t <= 45.0 -> VERY_FAVORABLE
                t <= 55.0 -> FAVORABLE
                t <= 65.0 -> POSSIBLE
                else -> UNFAVORABLE
            }
        }
    }
}

data class StudyResult(
    val building: Building,
    val heatLoss: BuildingHeatLoss,
    val studyCoverage: BuildingCoverage,
    val regimeCoverages: List<BuildingCoverage>,
    val supply: BuildingSupplyRequirement,
    val supplyConservative: BuildingSupplyRequirement,
    val compatibility: PacCompatibility,
    val seasonal: SeasonalPerformance?,
    val energy: EnergyEstimate,
) {
    val warnings: List<String> get() = heatLoss.warnings
    val radiatorCount: Int get() = building.radiators.size
    /** Radiateurs effectivement pris en compte : validés, avec une puissance établie. */
    val validatedRadiatorCount: Int get() = building.radiators.count { RadiatorPower.countsInStudy(it) }
}

/** Point d'entrée du moteur : enchaîne déperditions, couverture, température de départ, PAC et énergie. */
class StudyAnalyzer(private val calculator: HeatLossCalculator = HeatLossCalculator()) {

    fun analyze(
        building: Building,
        settings: StudySettings,
        regimes: List<Regime> = Regime.STANDARD,
        calibrationReferenceNeedKwh: Double? = null,
    ): StudyResult {
        val heatLoss = calculator.calculate(building)
        val study = CoverageAnalyzer.analyze(building, heatLoss, settings.studyRegime, settings.modeFor(settings.studyRegime))
        val byRegime = regimes.map { r -> CoverageAnalyzer.analyze(building, heatLoss, r, settings.modeFor(r)) }
        val supply = SupplyTemperatureSolver.solve(building, heatLoss, settings.solverMode)
        val supplyCons = SupplyTemperatureSolver.solve(building, heatLoss, settings.solverMode, conservative = true)
        val compat = PacCompatibility.from(supply)

        val seasonal = if (heatLoss.totalW > 0) {
            HeatPumpAnalysis.seasonal(
                designLoadKw = heatLoss.totalKw,
                designOutdoorC = building.climate.designOutdoorTempC,
                curve = settings.heatingCurve,
                performance = HeatPumpPerformance(settings.heatPump, settings.genericCarnotEfficiency),
            )
        } else null

        val dd = degreeDaysFor(building, heatLoss)
        val energy = EnergyEstimator.estimate(
            heatLossCoefficientWk = heatLoss.totalHWk,
            degreeDays = dd.degreeDays,
            degreeDaysBase = dd.base,
            settings = settings.energy,
            heatPumpScop = seasonal?.scop,
            calibrationReferenceNeedKwh = calibrationReferenceNeedKwh,
        ).let { e -> if (dd.note == null) e else e.copy(assumptions = listOf(dd.note) + e.assumptions) }
        return StudyResult(building, heatLoss, study, byRegime, supply, supplyCons, compat, seasonal, energy)
    }

    /** Analyse d'un scénario d'amélioration appliqué au bâtiment. */
    fun analyzeScenario(building: Building, settings: StudySettings, scenario: Scenario): StudyResult {
        val modified = ScenarioEngine.apply(building, scenario.modifications)
        val s = scenario.regime?.let { r ->
            val c = settings.heatingCurve
            settings.copy(
                studyRegime = r,
                // Le régime du scénario fixe aussi le point de base de la loi d'eau de la PAC (SCOP, coût PAC).
                heatingCurve = if (r == settings.studyRegime) c else c.copy(
                    designSupplyC = r.supplyC,
                    designDeltaK = r.supplyC - r.returnC,
                    maxSupplyC = maxOf(c.maxSupplyC, r.supplyC),
                ),
            )
        } ?: settings
        // Calage sur la consommation constatée : la référence est le besoin modélisé de l'état actuel.
        val referenceNeed = settings.energy.currentAnnualConsumption?.takeIf { it > 0 }?.let {
            val hl = calculator.calculate(building)
            EnergyEstimator.annualNeedKwh(hl.totalHWk, degreeDaysFor(building, hl).degreeDays, settings.energy.intermittenceFactor)
        }
        return analyze(modified, s, calibrationReferenceNeedKwh = referenceNeed)
    }

    private data class DegreeDays(val degreeDays: Double, val base: Double, val note: String?)

    /**
     * DJU corrigés pour la consigne moyenne (pondérée par le H de chaque pièce) : les DJU climatiques en
     * base θb correspondent conventionnellement à une consigne θb + 2 K (apports gratuits) ; chaque kelvin
     * d'écart ajoute ou retire environ un DJU par jour de la saison de chauffage.
     */
    private fun degreeDaysFor(building: Building, heatLoss: BuildingHeatLoss): DegreeDays {
        val climate = building.climate
        val weights = heatLoss.rooms.map { it.setpointC to (it.envelopeHWk + it.ventilationHWk) }
        val totalWeight = weights.sumOf { it.second }
        val meanSetpoint = if (totalWeight > 0) weights.sumOf { it.first * it.second } / totalWeight
            else climate.degreeDaysBase + FREE_GAINS_K
        val delta = meanSetpoint - (climate.degreeDaysBase + FREE_GAINS_K)
        if (abs(delta) < 0.05) return DegreeDays(climate.degreeDays, climate.degreeDaysBase, null)
        val dju = (climate.degreeDays + delta * HEATING_DAYS).coerceAtLeast(0.0)
        val note = "DJU corrigés pour la consigne moyenne ${meanSetpoint.fr(1)} °C : ${climate.degreeDays.fr(0)} " +
            "${if (delta < 0) "−" else "+"} ${abs(delta).fr(1)} K × ${HEATING_DAYS.fr(0)} j = ${dju.fr(0)} DJU."
        return DegreeDays(dju, climate.degreeDaysBase + delta, note)
    }

    private companion object {
        /** Apports gratuits conventionnellement inclus dans l'écart consigne / base des DJU, K. */
        const val FREE_GAINS_K = 2.0
        /** Durée de la saison de chauffage du climat moyen EN 14825 (4 910 h), jours. */
        val HEATING_DAYS = HeatPumpAnalysis.EN14825_AVERAGE_BINS.sumOf { it.second } / 24.0
    }
}

/** Comparaison avant / après d'un scénario. */
data class ScenarioComparison(val scenario: Scenario, val before: StudyResult, val after: StudyResult) {
    val heatLossReductionW: Double get() = before.heatLoss.totalW - after.heatLoss.totalW
    val heatLossReductionPct: Double
        get() = if (before.heatLoss.totalW > 0) heatLossReductionW / before.heatLoss.totalW else 0.0
    val supplyTempChangeK: Double?
        get() {
            val b = before.supply.requiredSupplyC; val a = after.supply.requiredSupplyC
            return if (a != null && b != null) a - b else null
        }

    companion object {
        fun of(analyzer: StudyAnalyzer, building: Building, settings: StudySettings, scenario: Scenario): ScenarioComparison {
            val before = analyzer.analyze(building, settings)
            val after = analyzer.analyzeScenario(building, settings, scenario)
            return ScenarioComparison(scenario, before, after)
        }
    }
}
