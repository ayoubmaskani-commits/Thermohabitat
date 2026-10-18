package fr.thermohabitat.thermal.sim

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Intégration du modèle RC multizone par un schéma d'Euler implicite (inconditionnellement stable).
 * La matrice du système est constante : elle est factorisée une seule fois (LU).
 * Le chauffage est idéal à puissance bornée : la puissance nécessaire pour atteindre la consigne
 * est calculée exactement grâce à la linéarité du système, puis limitée à la puissance des émetteurs.
 */
class RcSimulator {

    fun simulate(zones: List<ThermalZone>, couplings: List<ZoneCoupling>, config: SimulationConfig): SimulationResult {
        require(zones.isNotEmpty()) { "Aucune zone à simuler" }
        require(config.timeStepS > 0 && config.durationH > 0)
        val nZ = zones.size
        val n = 2 * nZ
        val dt = config.timeStepS
        val index = zones.mapIndexed { i, z -> z.id to i }.toMap()

        // Matrice A (implicite) : A · x(n+1) = b(n)
        val a = Array(n) { DoubleArray(n) }
        zones.forEachIndexed { k, z ->
            val ia = 2 * k; val im = 2 * k + 1
            a[ia][ia] += z.airCapacityJk / dt + z.hAirOutdoorWk + z.hAirMassWk + z.hAirFixedWk
            a[ia][im] -= z.hAirMassWk
            a[im][im] += z.massCapacityJk / dt + z.hAirMassWk + z.hMassOutdoorWk + z.hMassGroundWk
            a[im][ia] -= z.hAirMassWk
        }
        for (c in couplings) {
            val i = index[c.zoneA] ?: continue
            val j = index[c.zoneB] ?: continue
            if (i == j || c.hWk <= 0) continue
            val ia = 2 * i; val ja = 2 * j
            a[ia][ia] += c.hWk; a[ja][ja] += c.hWk
            a[ia][ja] -= c.hWk; a[ja][ia] -= c.hWk
        }
        val lu = LuDecomposition(a)
        // Réponse de chaque nœud d'air à 1 W injecté dans l'air de la zone k.
        val unitResponses = Array(nZ) { k ->
            val e = DoubleArray(n); e[2 * k] = 1.0
            lu.solve(e)
        }

        val x = DoubleArray(n)
        zones.forEachIndexed { k, z ->
            val t0 = config.initialTemps[z.id] ?: config.initialTempC
            x[2 * k] = t0; x[2 * k + 1] = t0
        }

        val steps = ceil(config.durationH * 3600.0 / dt).toInt()
        val recordEvery = (config.recordEveryS / dt).roundToInt().coerceAtLeast(1)
        val times = mutableListOf<Double>()
        val clocks = mutableListOf<Double>()
        val outdoor = mutableListOf<Double>()
        val air = Array(nZ) { mutableListOf<Double>() }
        val mass = Array(nZ) { mutableListOf<Double>() }
        val heat = Array(nZ) { mutableListOf<Double>() }

        fun record(step: Int, heating: DoubleArray) {
            val tH = step * dt / 3600.0
            val clock = (config.startClockHour + tH) % 24.0
            times += tH; clocks += clock; outdoor += config.outdoor.at(clock, tH)
            for (k in 0 until nZ) { air[k] += x[2 * k]; mass[k] += x[2 * k + 1]; heat[k] += heating[k] }
        }
        record(0, DoubleArray(nZ))

        val b = DoubleArray(n)
        val p = DoubleArray(nZ)
        for (step in 1..steps) {
            val tH = step * dt / 3600.0
            val clock = (config.startClockHour + tH) % 24.0
            val te = config.outdoor.at(clock, tH)
            val heatingOn = config.heatingPeriods.any { it.contains(clock) }
            zones.forEachIndexed { k, z ->
                val ia = 2 * k; val im = 2 * k + 1
                val internal = if (config.includeInternalGains) z.internalGainsW else 0.0
                val solar = z.solarApertures.entries.sumOf { (o, area) -> area * config.solar.irradiance(o, clock) }
                b[ia] = z.airCapacityJk / dt * x[ia] + z.hAirOutdoorWk * te + z.hAirFixedWk * z.fixedTempC +
                    0.5 * internal + 0.2 * solar
                b[im] = z.massCapacityJk / dt * x[im] + z.hMassOutdoorWk * te + z.hMassGroundWk * config.groundTempC +
                    0.5 * internal + 0.8 * solar
            }
            val free = lu.solve(b)
            p.fill(0.0)
            if (heatingOn) {
                // Ajustement itératif de la puissance de chaque zone (couplages entre zones).
                repeat(4) {
                    val current = free.copyOf()
                    for (k in 0 until nZ) if (p[k] != 0.0) addScaled(current, unitResponses[k], p[k])
                    var changed = false
                    for (k in 0 until nZ) {
                        val z = zones[k]
                        if (z.maxHeatingW <= 0) continue
                        val sens = unitResponses[k][2 * k]
                        if (sens <= 0) continue
                        val target = (p[k] + (z.setpointC - current[2 * k]) / sens).coerceIn(0.0, z.maxHeatingW)
                        if (abs(target - p[k]) > 1e-6) { p[k] = target; changed = true }
                    }
                    if (!changed) return@repeat
                }
            }
            for (i in 0 until n) x[i] = free[i]
            for (k in 0 until nZ) if (p[k] != 0.0) addScaled(x, unitResponses[k], p[k])
            if (step % recordEvery == 0 || step == steps) record(step, p.copyOf())
        }

        return SimulationResult(
            timesH = times,
            clockHours = clocks,
            outdoorC = outdoor,
            zones = zones.mapIndexed { k, z -> ZoneSeries(z.id, z.name, air[k], mass[k], heat[k]) },
        )
    }

    /**
     * Simulation nominale et enveloppe d'incertitude obtenue en faisant varier la capacité
     * (± [capacityUncertainty]) et les conductances (± [conductanceUncertainty]).
     */
    fun simulateWithUncertainty(
        zones: List<ThermalZone>,
        couplings: List<ZoneCoupling>,
        config: SimulationConfig,
        capacityUncertainty: Double,
        conductanceUncertainty: Double,
    ): UncertainSimulation {
        val nominal = simulate(zones, couplings, config)
        val variants = buildList {
            for (cf in listOf(1 - capacityUncertainty, 1 + capacityUncertainty)) {
                for (hf in listOf(1 - conductanceUncertainty, 1 + conductanceUncertainty)) {
                    add(simulate(zones.map { it.scaled(cf, hf) }, couplings.map { it.copy(hWk = it.hWk * hf) }, config))
                }
            }
        }
        val low = mutableMapOf<Long, List<Double>>()
        val high = mutableMapOf<Long, List<Double>>()
        for (z in nominal.zones) {
            val series = variants.map { v -> v.zone(z.zoneId)!!.airTempC } + listOf(z.airTempC)
            low[z.zoneId] = z.airTempC.indices.map { i -> series.minOf { it[i] } }
            high[z.zoneId] = z.airTempC.indices.map { i -> series.maxOf { it[i] } }
        }
        return UncertainSimulation(nominal, low, high, capacityUncertainty, conductanceUncertainty)
    }

    private fun addScaled(target: DoubleArray, v: DoubleArray, s: Double) {
        for (i in target.indices) target[i] += s * v[i]
    }
}

/** Décomposition LU avec pivot partiel (matrices denses de petite taille). */
internal class LuDecomposition(matrix: Array<DoubleArray>) {
    private val n = matrix.size
    private val lu = Array(n) { matrix[it].copyOf() }
    private val perm = IntArray(n) { it }

    init {
        for (k in 0 until n) {
            var p = k
            var max = abs(lu[k][k])
            for (i in k + 1 until n) if (abs(lu[i][k]) > max) { max = abs(lu[i][k]); p = i }
            require(max > 1e-14) { "Système thermique singulier" }
            if (p != k) {
                val tmp = lu[p]; lu[p] = lu[k]; lu[k] = tmp
                val tp = perm[p]; perm[p] = perm[k]; perm[k] = tp
            }
            for (i in k + 1 until n) {
                lu[i][k] /= lu[k][k]
                val f = lu[i][k]
                if (f != 0.0) for (j in k + 1 until n) lu[i][j] -= f * lu[k][j]
            }
        }
    }

    fun solve(b: DoubleArray): DoubleArray {
        val y = DoubleArray(n) { b[perm[it]] }
        for (i in 0 until n) for (j in 0 until i) y[i] -= lu[i][j] * y[j]
        for (i in n - 1 downTo 0) {
            for (j in i + 1 until n) y[i] -= lu[i][j] * y[j]
            y[i] /= lu[i][i]
        }
        return y
    }
}
