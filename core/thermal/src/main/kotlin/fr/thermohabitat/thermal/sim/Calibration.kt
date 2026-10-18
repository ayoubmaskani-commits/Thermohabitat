package fr.thermohabitat.thermal.sim

import kotlinx.serialization.Serializable
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/** Température mesurée à un instant (heures depuis le début de la simulation). */
@Serializable
data class MeasuredPoint(val timeH: Double, val tempC: Double)

data class CalibrationResult(
    /** Facteur multiplicatif appliqué aux capacités thermiques. */
    val capacityFactor: Double,
    /** Facteur multiplicatif appliqué aux conductances. */
    val conductanceFactor: Double,
    val rmseBeforeC: Double,
    val rmseAfterC: Double,
    /** Constante de temps calée, h. */
    val timeConstantH: Double,
    val calibrated: SimulationResult,
)

/**
 * Calage du modèle RC sur des températures réellement mesurées (refroidissement nocturne
 * chauffage coupé, par exemple) : recherche par section dorée des facteurs de capacité et,
 * optionnellement, de conductance minimisant l'écart quadratique moyen.
 */
object Calibrator {

    private const val MIN_FACTOR = 0.2
    private const val MAX_FACTOR = 5.0

    fun calibrate(
        model: ZoneModel,
        config: SimulationConfig,
        measurements: List<MeasuredPoint>,
        zoneId: Long? = null,
        fitConductance: Boolean = false,
    ): CalibrationResult {
        require(measurements.size >= 2) { "Au moins deux mesures sont nécessaires" }
        val simulator = RcSimulator()
        val maxT = measurements.maxOf { it.timeH }
        val cfg = config.copy(durationH = maxOf(config.durationH, maxT))

        fun run(cf: Double, hf: Double): SimulationResult =
            simulator.simulate(model.zones.map { it.scaled(cf, hf) }, model.couplings.map { it.copy(hWk = it.hWk * hf) }, cfg)

        fun rmse(result: SimulationResult): Double {
            val series = if (zoneId != null) result.zone(zoneId)!!.airTempC
            else result.averageAir(model.zones.associate { it.id to it.floorAreaM2 })
            val se = measurements.sumOf { m -> val d = result.interpolate(series, m.timeH) - m.tempC; d * d }
            return sqrt(se / measurements.size)
        }

        val before = rmse(run(1.0, 1.0))
        var cf = 1.0
        var hf = 1.0
        val rounds = if (fitConductance) 4 else 1
        repeat(rounds) {
            cf = goldenSection { f -> rmse(run(f, hf)) }
            if (fitConductance) hf = goldenSection { f -> rmse(run(cf, f)) }
        }
        val best = run(cf, hf)
        val zones = model.zones.map { it.scaled(cf, hf) }
        val c = zones.sumOf { it.totalCapacityJk }
        val h = zones.sumOf { it.steadyStateHWk }
        return CalibrationResult(cf, hf, before, rmse(best), if (h > 0) c / h / 3600.0 else Double.NaN, best)
    }

    /** Minimisation unidimensionnelle sur ln(facteur) ∈ [ln 0,2 ; ln 5]. */
    private fun goldenSection(objective: (Double) -> Double): Double {
        val gr = (sqrt(5.0) - 1) / 2
        var a = ln(MIN_FACTOR)
        var b = ln(MAX_FACTOR)
        var c = b - gr * (b - a)
        var d = a + gr * (b - a)
        var fc = objective(exp(c))
        var fd = objective(exp(d))
        repeat(40) {
            if (fc < fd) {
                b = d; d = c; fd = fc
                c = b - gr * (b - a); fc = objective(exp(c))
            } else {
                a = c; c = d; fc = fd
                d = a + gr * (b - a); fd = objective(exp(d))
            }
        }
        return exp((a + b) / 2)
    }
}
