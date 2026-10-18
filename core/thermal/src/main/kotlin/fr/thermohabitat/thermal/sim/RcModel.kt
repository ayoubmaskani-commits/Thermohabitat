package fr.thermohabitat.thermal.sim

import fr.thermohabitat.thermal.model.Orientation
import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.cos

/**
 * Zone thermique d'un modèle RC à deux nœuds (air + masse des parois), inspiré du modèle
 * horaire simplifié de la NF EN ISO 13790 :
 *
 *   C_air  dTa/dt = H_ao (Te − Ta) + H_am (Tm − Ta) + H_af (Tf − Ta) + Σ H_ij (Tj − Ta) + Φ_chauffage + Φ_conv
 *   C_masse dTm/dt = H_am (Ta − Tm) + H_mo (Te − Tm) + H_mg (Tsol − Tm) + Φ_rad
 */
data class ThermalZone(
    val id: Long,
    val name: String,
    val airCapacityJk: Double,
    val massCapacityJk: Double,
    /** Air → extérieur : baies, ponts thermiques, renouvellement d'air (W/K). */
    val hAirOutdoorWk: Double,
    /** Air ↔ masse des parois (W/K). */
    val hAirMassWk: Double,
    /** Masse → extérieur à travers les parois opaques (W/K). */
    val hMassOutdoorWk: Double,
    /** Masse → sol à température constante (W/K). */
    val hMassGroundWk: Double = 0.0,
    /** Air → espace à température fixe (mitoyen, pièce non simulée) (W/K). */
    val hAirFixedWk: Double = 0.0,
    val fixedTempC: Double = 15.0,
    val setpointC: Double = 20.0,
    /** Puissance de chauffage maximale disponible (émetteurs au régime choisi), W. */
    val maxHeatingW: Double = 0.0,
    val internalGainsW: Double = 0.0,
    /** Surfaces solaires équivalentes A_vitrée·g·F_ombrage par orientation, m². */
    val solarApertures: Map<Orientation, Double> = emptyMap(),
    val floorAreaM2: Double = 0.0,
) {
    /** Conductance globale vers l'extérieur et le sol (régime permanent), W/K. */
    val steadyStateHWk: Double
        get() {
            val opaque = if (hMassOutdoorWk + hMassGroundWk > 0) {
                val hm = hMassOutdoorWk + hMassGroundWk
                1.0 / (1.0 / hAirMassWk + 1.0 / hm)
            } else 0.0
            return hAirOutdoorWk + hAirFixedWk + opaque
        }

    val totalCapacityJk: Double get() = airCapacityJk + massCapacityJk

    /** Constante de temps τ = C / H (h). */
    val timeConstantH: Double get() = if (steadyStateHWk > 0) totalCapacityJk / steadyStateHWk / 3600.0 else Double.POSITIVE_INFINITY

    fun scaled(capacityFactor: Double, conductanceFactor: Double): ThermalZone = copy(
        airCapacityJk = airCapacityJk * capacityFactor,
        massCapacityJk = massCapacityJk * capacityFactor,
        hAirOutdoorWk = hAirOutdoorWk * conductanceFactor,
        hMassOutdoorWk = hMassOutdoorWk * conductanceFactor,
        hMassGroundWk = hMassGroundWk * conductanceFactor,
        hAirFixedWk = hAirFixedWk * conductanceFactor,
    )
}

/** Échange entre deux zones simulées (paroi intérieure), W/K. */
data class ZoneCoupling(val zoneA: Long, val zoneB: Long, val hWk: Double)

/** Évolution de la température extérieure. */
@Serializable
sealed interface OutdoorProfile {
    fun at(clockHour: Double, elapsedH: Double): Double

    @Serializable
    data class Constant(val tempC: Double) : OutdoorProfile {
        override fun at(clockHour: Double, elapsedH: Double) = tempC
    }

    /** Variation sinusoïdale journalière : minimum à [minHour], maximum 12 h plus tard. */
    @Serializable
    data class Daily(val minC: Double, val maxC: Double, val minHour: Double = 6.0) : OutdoorProfile {
        override fun at(clockHour: Double, elapsedH: Double): Double {
            val mean = (minC + maxC) / 2.0
            val amp = (maxC - minC) / 2.0
            return mean - amp * cos(2.0 * PI * (clockHour - minHour) / 24.0)
        }
    }

    /** Valeurs horaires depuis le début de la simulation (interpolation linéaire). */
    @Serializable
    data class Hourly(val valuesC: List<Double>) : OutdoorProfile {
        override fun at(clockHour: Double, elapsedH: Double): Double {
            if (valuesC.isEmpty()) return 0.0
            if (elapsedH <= 0) return valuesC.first()
            val i = elapsedH.toInt()
            if (i >= valuesC.size - 1) return valuesC.last()
            val t = elapsedH - i
            return valuesC[i] + t * (valuesC[i + 1] - valuesC[i])
        }
    }
}

/**
 * Profils d'ensoleillement simplifiés sur façade verticale (ordre de grandeur pour un jour de
 * janvier en France métropolitaine). Les pics [peaks] sont en W/m², à l'heure [peakHour].
 */
enum class SolarProfile(val label: String, private val peaks: Map<Orientation, Double>) {
    NONE("Sans apports solaires", emptyMap()),
    CLEAR_WINTER("Ciel clair d'hiver (simplifié)", mapOf(
        Orientation.S to 650.0, Orientation.SE to 500.0, Orientation.SW to 500.0,
        Orientation.E to 330.0, Orientation.W to 330.0, Orientation.NE to 90.0, Orientation.NW to 90.0, Orientation.N to 60.0,
    )),
    OVERCAST("Ciel couvert (rayonnement diffus)", Orientation.entries.associateWith { if (it == Orientation.N) 40.0 else 55.0 });

    fun irradiance(orientation: Orientation, clockHour: Double): Double {
        val peak = peaks[orientation] ?: return 0.0
        val h = ((clockHour % 24.0) + 24.0) % 24.0
        if (h < SUNRISE || h > SUNSET) return 0.0
        val peakHour = when (orientation) {
            Orientation.E, Orientation.NE -> 9.5
            Orientation.SE -> 10.75
            Orientation.S, Orientation.N -> 12.75
            Orientation.SW -> 14.75
            Orientation.W, Orientation.NW -> 16.0
        }
        val halfWidth = if (orientation == Orientation.S || orientation == Orientation.N || this == OVERCAST) 4.25 else 3.0
        val x = (h - peakHour) / halfWidth
        if (x <= -1.0 || x >= 1.0) return 0.0
        return peak * cos(x * PI / 2.0)
    }

    companion object {
        const val SUNRISE = 8.5
        const val SUNSET = 17.0
    }
}

/** Plage de fonctionnement du chauffage en heures d'horloge (peut franchir minuit : 22 → 6). */
@Serializable
data class HeatingPeriod(val startHour: Double, val endHour: Double) {
    fun contains(clockHour: Double): Boolean {
        val h = ((clockHour % 24.0) + 24.0) % 24.0
        return if (startHour <= endHour) h >= startHour && h < endHour else h >= startHour || h < endHour
    }
}

data class SimulationConfig(
    val durationH: Double = 8.0,
    val timeStepS: Double = 120.0,
    /** Heure d'horloge au début de la simulation (ex. 22 h : coupure du chauffage). */
    val startClockHour: Double = 22.0,
    val outdoor: OutdoorProfile = OutdoorProfile.Constant(0.0),
    /** Périodes où le chauffage fonctionne. Vide : chauffage coupé pendant toute la simulation. */
    val heatingPeriods: List<HeatingPeriod> = emptyList(),
    val solar: SolarProfile = SolarProfile.NONE,
    val includeInternalGains: Boolean = false,
    val initialTempC: Double = 20.0,
    val initialTemps: Map<Long, Double> = emptyMap(),
    val groundTempC: Double = 11.0,
    val recordEveryS: Double = 600.0,
)

data class ZoneSeries(
    val zoneId: Long,
    val name: String,
    val airTempC: List<Double>,
    val massTempC: List<Double>,
    val heatingW: List<Double>,
)

data class SimulationResult(
    val timesH: List<Double>,
    val clockHours: List<Double>,
    val outdoorC: List<Double>,
    val zones: List<ZoneSeries>,
) {
    fun zone(id: Long): ZoneSeries? = zones.firstOrNull { it.zoneId == id }

    /** Température d'air moyenne pondérée par [weights] (ex. surfaces), ou moyenne simple. */
    fun averageAir(weights: Map<Long, Double> = emptyMap()): List<Double> = timesH.indices.map { i ->
        var sw = 0.0; var s = 0.0
        zones.forEach { z ->
            val w = (weights[z.zoneId] ?: 1.0).takeIf { it > 0 } ?: 1.0
            sw += w; s += w * z.airTempC[i]
        }
        if (sw > 0) s / sw else Double.NaN
    }

    /** Valeur interpolée d'une série à l'instant [timeH]. */
    fun interpolate(series: List<Double>, timeH: Double): Double {
        if (timesH.isEmpty()) return Double.NaN
        if (timeH <= timesH.first()) return series.first()
        if (timeH >= timesH.last()) return series.last()
        val i = timesH.indexOfFirst { it >= timeH }
        val t0 = timesH[i - 1]; val t1 = timesH[i]
        return series[i - 1] + (timeH - t0) / (t1 - t0) * (series[i] - series[i - 1])
    }
}

/** Résultat avec enveloppe d'incertitude (capacité et conductances ± incertitudes). */
data class UncertainSimulation(
    val nominal: SimulationResult,
    val lowAir: Map<Long, List<Double>>,
    val highAir: Map<Long, List<Double>>,
    val capacityUncertainty: Double,
    val conductanceUncertainty: Double,
)
