package fr.thermohabitat.thermal.calc

import fr.thermohabitat.thermal.model.Boundary
import fr.thermohabitat.thermal.model.Layer
import fr.thermohabitat.thermal.model.Room
import fr.thermohabitat.thermal.model.Surface
import fr.thermohabitat.thermal.model.SurfaceKind
import fr.thermohabitat.thermal.model.ValueOrigin

/** Sens du flux de chaleur à travers une paroi (NF EN ISO 6946). */
enum class HeatFlowDirection { HORIZONTAL, UPWARD, DOWNWARD }

val SurfaceKind.heatFlowDirection: HeatFlowDirection
    get() = when (this) {
        SurfaceKind.WALL -> HeatFlowDirection.HORIZONTAL
        SurfaceKind.CEILING, SurfaceKind.ROOF -> HeatFlowDirection.UPWARD
        SurfaceKind.FLOOR -> HeatFlowDirection.DOWNWARD
    }

/** Résistances superficielles conventionnelles (NF EN ISO 6946, tableau 7). */
object SurfaceResistances {
    const val RSE_EXTERIOR = 0.04

    fun rsi(direction: HeatFlowDirection): Double = when (direction) {
        HeatFlowDirection.HORIZONTAL -> 0.13
        HeatFlowDirection.UPWARD -> 0.10
        HeatFlowDirection.DOWNWARD -> 0.17
    }

    /**
     * Résistance superficielle côté opposé : 0,04 vers l'extérieur ; pour une paroi donnant
     * sur un local (chauffé ou non), la résistance intérieure est appliquée des deux côtés.
     * Pour le terre-plein, la méthode EN ISO 13370 intègre directement Rse.
     */
    fun rse(boundary: Boundary, direction: HeatFlowDirection): Double = when (boundary) {
        Boundary.EXTERIOR -> RSE_EXTERIOR
        Boundary.GROUND -> RSE_EXTERIOR
        Boundary.UNHEATED, Boundary.HEATED_ROOM, Boundary.ADJACENT_BUILDING -> rsi(direction)
    }
}

/** Calculs de résistance thermique et de coefficient U des parois opaques. */
object ThermalResistance {

    // Résistance des lames d'air non ventilées (NF EN ISO 6946, tableau 8) : épaisseur (mm) -> R
    private val airGapThicknessMm = doubleArrayOf(0.0, 5.0, 7.0, 10.0, 15.0, 25.0, 50.0, 100.0, 300.0)
    private val airGapHorizontal = doubleArrayOf(0.0, 0.11, 0.13, 0.15, 0.17, 0.18, 0.18, 0.18, 0.18)
    private val airGapUpward = doubleArrayOf(0.0, 0.11, 0.13, 0.15, 0.16, 0.16, 0.16, 0.16, 0.16)
    private val airGapDownward = doubleArrayOf(0.0, 0.11, 0.13, 0.15, 0.17, 0.19, 0.21, 0.22, 0.23)

    /** Résistance d'une lame d'air non ventilée d'épaisseur [thicknessM]. */
    fun airGapResistance(thicknessM: Double, direction: HeatFlowDirection): Double {
        val values = when (direction) {
            HeatFlowDirection.HORIZONTAL -> airGapHorizontal
            HeatFlowDirection.UPWARD -> airGapUpward
            HeatFlowDirection.DOWNWARD -> airGapDownward
        }
        return interpolate(airGapThicknessMm, values, thicknessM * 1000.0)
    }

    /** Résistance thermique d'une couche R = e / λ (m²·K/W). */
    fun layerResistance(layer: Layer, direction: HeatFlowDirection = HeatFlowDirection.HORIZONTAL): Double {
        require(layer.thicknessM >= 0) { "Épaisseur négative" }
        return if (layer.material.isAirGap) {
            airGapResistance(layer.thicknessM, direction)
        } else {
            require(layer.lambda > 0) { "Conductivité nulle pour ${layer.material.name}" }
            layer.thicknessM / layer.lambda
        }
    }

    /** Somme des résistances des couches, hors résistances superficielles. */
    fun layersResistance(layers: List<Layer>, direction: HeatFlowDirection): Double =
        layers.sumOf { layerResistance(it, direction) }

    /** Résistance totale Rt = Rsi + ΣR + Rse. */
    fun totalResistance(layers: List<Layer>, kind: SurfaceKind, boundary: Boundary): Double {
        val dir = kind.heatFlowDirection
        return SurfaceResistances.rsi(dir) + layersResistance(layers, dir) + SurfaceResistances.rse(boundary, dir)
    }

    /** Coefficient de transmission surfacique U = 1 / Rt, W/(m²·K). */
    fun uValue(layers: List<Layer>, kind: SurfaceKind, boundary: Boundary): Double =
        1.0 / totalResistance(layers, kind, boundary)

    /**
     * U nécessaire pour atteindre une résistance d'isolant supplémentaire : utile pour les scénarios.
     * Retourne l'épaisseur (m) d'un isolant de conductivité [lambda] pour atteindre [targetU].
     */
    fun insulationThicknessFor(targetU: Double, currentU: Double, lambda: Double): Double {
        require(targetU > 0 && currentU > 0)
        if (targetU >= currentU) return 0.0
        return (1.0 / targetU - 1.0 / currentU) * lambda
    }

    internal fun interpolate(xs: DoubleArray, ys: DoubleArray, x: Double): Double {
        if (x <= xs.first()) return ys.first()
        if (x >= xs.last()) return ys.last()
        for (i in 1 until xs.size) {
            if (x <= xs[i]) {
                val t = (x - xs[i - 1]) / (xs[i] - xs[i - 1])
                return ys[i - 1] + t * (ys[i] - ys[i - 1])
            }
        }
        return ys.last()
    }
}

/** U opaque résolu d'une paroi, avec son origine. */
data class ResolvedU(val value: Double, val origin: ValueOrigin, val fromDefault: Boolean)

/**
 * Valeurs U par défaut prudentes pour une paroi sans composition ni U saisi
 * (ordre de grandeur d'une paroi ancienne non isolée).
 */
object DefaultUValues {
    fun forKind(kind: SurfaceKind): Double = when (kind) {
        SurfaceKind.WALL -> 2.0
        SurfaceKind.FLOOR -> 2.0
        SurfaceKind.CEILING -> 2.5
        SurfaceKind.ROOF -> 2.5
    }
}

object SurfaceGeometry {
    /** Surface brute d'une paroi (m²). */
    fun grossArea(surface: Surface, room: Room): Double = when {
        surface.areaOverrideM2 != null -> surface.areaOverrideM2
        surface.kind == SurfaceKind.WALL -> (surface.lengthM ?: 0.0) * (surface.heightM ?: room.heightM)
        else -> room.floorAreaM2
    }

    fun openingsArea(surface: Surface): Double = surface.openings.sumOf { it.areaM2 }

    /** Surface opaque nette (brute − baies), jamais négative. */
    fun netOpaqueArea(surface: Surface, room: Room): Double =
        (grossArea(surface, room) - openingsArea(surface)).coerceAtLeast(0.0)

    /** U opaque : valeur saisie, sinon calculée depuis les couches, sinon valeur par défaut. */
    fun resolveU(surface: Surface): ResolvedU = when {
        surface.uValueOverride != null -> ResolvedU(surface.uValueOverride, surface.uValueOrigin, false)
        surface.layers.isNotEmpty() -> {
            val origin = when {
                surface.layers.any { it.origin == ValueOrigin.UNKNOWN || it.material.origin == ValueOrigin.UNKNOWN } -> ValueOrigin.UNKNOWN
                surface.layers.any { it.origin == ValueOrigin.ESTIMATED || it.material.origin == ValueOrigin.ESTIMATED } -> ValueOrigin.ESTIMATED
                surface.layers.all { it.origin == ValueOrigin.MEASURED } -> ValueOrigin.MEASURED
                else -> ValueOrigin.DECLARED
            }
            ResolvedU(ThermalResistance.uValue(surface.layers, surface.kind, surface.boundary), origin, false)
        }
        else -> ResolvedU(DefaultUValues.forKind(surface.kind), ValueOrigin.UNKNOWN, true)
    }
}
