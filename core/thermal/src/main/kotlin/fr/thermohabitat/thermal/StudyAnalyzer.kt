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
import fr.thermohabitat.thermal.calc.Regime
import fr.thermohabitat.thermal.calc.SeasonalPerformance
import fr.thermohabitat.thermal.calc.SupplyTemperatureSolver
import fr.thermohabitat.thermal.model.Building
import fr.thermohabitat.thermal.scenario.Scenario
import fr.thermohabitat.thermal.scenario.ScenarioEngine
import kotlinx.serialization.Serializable

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
    val validatedRadiatorCount: Int get() = building.radiators.count { it.validated }
}

/** Point d'entrée du moteur : enchaîne déperditions, couverture, température de départ, PAC et énergie. */
class StudyAnalyzer(private val calculator: HeatLossCalculator = HeatLossCalculator()) {

    fun analyze(building: Building, settings: StudySettings, regimes: List<Regime> = Regime.STANDARD): StudyResult {
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

        val energy = EnergyEstimator.estimate(
            heatLossCoefficientWk = heatLoss.totalHWk,
            degreeDays = building.climate.degreeDays,
            degreeDaysBase = building.climate.degreeDaysBase,
            settings = settings.energy,
            heatPumpScop = seasonal?.scop,
        )
        return StudyResult(building, heatLoss, study, byRegime, supply, supplyCons, compat, seasonal, energy)
    }

    /** Analyse d'un scénario d'amélioration appliqué au bâtiment. */
    fun analyzeScenario(building: Building, settings: StudySettings, scenario: Scenario): StudyResult {
        val modified = ScenarioEngine.apply(building, scenario.modifications)
        val s = scenario.regime?.let { settings.copy(studyRegime = it) } ?: settings
        return analyze(modified, s)
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
