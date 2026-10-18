package fr.thermohabitat.thermal.calc

import fr.thermohabitat.thermal.model.Boundary
import fr.thermohabitat.thermal.model.Building
import fr.thermohabitat.thermal.model.Layer
import fr.thermohabitat.thermal.model.Room

/** Classes d'inertie de la NF EN ISO 13790 (capacité effective par m² de plancher). */
enum class InertiaClass(val label: String, val jPerM2K: Double) {
    VERY_LIGHT("Très légère", 80_000.0),
    LIGHT("Légère", 110_000.0),
    MEDIUM("Moyenne", 165_000.0),
    HEAVY("Lourde", 260_000.0),
    VERY_HEAVY("Très lourde", 370_000.0);

    companion object {
        fun classify(jPerM2K: Double): InertiaClass =
            entries.minBy { kotlin.math.abs(it.jPerM2K - jPerM2K) }
    }
}

data class RoomCapacity(
    /** Capacité du nœud « air » (air + mobilier), J/K. */
    val airJk: Double,
    /** Capacité effective des parois (nœud « masse »), J/K. */
    val massJk: Double,
    /** Surface effective de masse A_m (EN ISO 13790), m². */
    val effectiveMassAreaM2: Double,
) {
    val totalJk: Double get() = airJk + massJk
}

/**
 * Capacité thermique effective selon la méthode simplifiée de la NF EN ISO 13786 :
 * on cumule ρ·c·e des couches depuis la face intérieure jusqu'à la première couche isolante,
 * dans la limite de 10 cm (ou de la mi-épaisseur pour une paroi intérieure).
 */
object ThermalInertia {

    const val MAX_EFFECTIVE_DEPTH_M = 0.10
    const val AIR_VOLUMETRIC_HEAT_CAPACITY = 1.2 * 1005.0
    /** Mobilier rattaché au nœud air, J/(m²·K) de plancher. */
    const val FURNITURE_JM2K = 10_000.0

    /** Capacité surfacique effective κ (J/(m²·K)) d'une paroi, couches de l'intérieur vers l'extérieur. */
    fun arealHeatCapacity(layers: List<Layer>, internalBothSides: Boolean = false): Double {
        val total = layers.sumOf { it.thicknessM }
        val limit = if (internalBothSides) minOf(MAX_EFFECTIVE_DEPTH_M, total / 2.0) else MAX_EFFECTIVE_DEPTH_M
        var depth = 0.0
        var kappa = 0.0
        for (layer in layers) {
            if (layer.material.isInsulation || layer.lambda < 0.08) break
            val take = minOf(layer.thicknessM, limit - depth)
            if (take <= 0) break
            kappa += layer.material.volumetricHeatCapacity * take
            depth += take
        }
        return kappa
    }

    fun roomCapacity(building: Building, room: Room): RoomCapacity {
        var mass = 0.0
        var sumAk2 = 0.0
        for (s in room.surfaces) {
            val area = SurfaceGeometry.netOpaqueArea(s, room)
            if (area <= 0) continue
            val kappa = arealHeatCapacity(s.layers, internalBothSides = s.boundary == Boundary.HEATED_ROOM)
            mass += kappa * area
            sumAk2 += area * kappa * kappa
        }
        val floor = room.floorAreaM2
        val extra = (building.additionalHeatCapacityJm2K - FURNITURE_JM2K).coerceAtLeast(0.0)
        if (extra > 0 && floor > 0) {
            mass += extra * floor
            sumAk2 += floor * extra * extra
        }
        val am = if (sumAk2 > 0) mass * mass / sumAk2 else floor
        val air = AIR_VOLUMETRIC_HEAT_CAPACITY * room.volumeM3 + FURNITURE_JM2K * floor
        return RoomCapacity(air, mass, am)
    }

    /** Capacité totale du bâtiment par m² de plancher, J/(m²·K). */
    fun buildingCapacityPerM2(building: Building): Double {
        val area = building.heatedFloorAreaM2
        if (area <= 0) return 0.0
        return building.rooms.sumOf { roomCapacity(building, it).totalJk } / area
    }
}
