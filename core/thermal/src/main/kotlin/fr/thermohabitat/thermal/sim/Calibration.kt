package fr.thermohabitat.thermal.sim

import kotlinx.serialization.Serializable
import kotlin.math.abs
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
        val cf: Double
        val hf: Double
        if (fitConductance) {
            // Capacité et conductance sont fortement corrélées (vallée à τ = C/H ≈ constante) : une descente
            // axe par axe stagne ; un simplexe de Nelder-Mead sur (ln cf, ln hf) suit la vallée.
            val lo = ln(MIN_FACTOR); val hi = ln(MAX_FACTOR)
            val x = nelderMead(doubleArrayOf(0.0, 0.0)) { p -> rmse(run(exp(p[0].coerceIn(lo, hi)), exp(p[1].coerceIn(lo, hi)))) }
            cf = exp(x[0].coerceIn(lo, hi)); hf = exp(x[1].coerceIn(lo, hi))
        } else {
            cf = goldenSection { f -> rmse(run(f, 1.0)) }
            hf = 1.0
        }
        val best = run(cf, hf)
        val zones = model.zones.map { it.scaled(cf, hf) }
        val c = zones.sumOf { it.totalCapacityJk }
        val h = zones.sumOf { it.steadyStateHWk }
        return CalibrationResult(cf, hf, before, rmse(best), if (h > 0) c / h / 3600.0 else Double.NaN, best)
    }

    /** Nelder-Mead en dimension 2 (réflexion 1, expansion 2, contraction 0,5, rétrécissement 0,5). */
    private fun nelderMead(start: DoubleArray, step: Double = 0.2, maxIter: Int = 300, objective: (DoubleArray) -> Double): DoubleArray {
        val pts = arrayOf(start.copyOf(), doubleArrayOf(start[0] + step, start[1]), doubleArrayOf(start[0], start[1] + step))
        val fv = DoubleArray(3) { objective(pts[it]) }
        fun along(c: DoubleArray, w: DoubleArray, k: Double) = DoubleArray(2) { c[it] + k * (w[it] - c[it]) }
        repeat(maxIter) {
            val o = (0..2).sortedBy { fv[it] }
            val best = o[0]; val mid = o[1]; val worst = o[2]
            if (fv[worst] - fv[best] < 1e-9 && abs(pts[worst][0] - pts[best][0]) + abs(pts[worst][1] - pts[best][1]) < 1e-7) return pts[best]
            val c = DoubleArray(2) { (pts[best][it] + pts[mid][it]) / 2 }
            val xr = along(c, pts[worst], -1.0); val fr = objective(xr)
            when {
                fr < fv[best] -> {
                    val xe = along(c, pts[worst], -2.0); val fe = objective(xe)
                    if (fe < fr) { pts[worst] = xe; fv[worst] = fe } else { pts[worst] = xr; fv[worst] = fr }
                }
                fr < fv[mid] -> { pts[worst] = xr; fv[worst] = fr }
                else -> {
                    val xc = along(c, pts[worst], 0.5); val fc = objective(xc)
                    if (fc < fv[worst]) {
                        pts[worst] = xc; fv[worst] = fc
                    } else {
                        for (i in listOf(mid, worst)) { pts[i] = along(pts[best], pts[i], 0.5); fv[i] = objective(pts[i]) }
                    }
                }
            }
        }
        return pts[(0..2).minBy { fv[it] }]
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
