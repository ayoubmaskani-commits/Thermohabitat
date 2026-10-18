package fr.thermohabitat.thermal.sim

import fr.thermohabitat.thermal.calc.BuildingHeatLoss
import fr.thermohabitat.thermal.calc.HeatLossCalculator
import fr.thermohabitat.thermal.calc.LossCategory
import fr.thermohabitat.thermal.calc.ThermalInertia
import fr.thermohabitat.thermal.model.Boundary
import fr.thermohabitat.thermal.model.Building
import fr.thermohabitat.thermal.model.Orientation
import fr.thermohabitat.thermal.model.ValueOrigin

/** Modèle multizone prêt à simuler. */
data class ZoneModel(
    val zones: List<ThermalZone>,
    val couplings: List<ZoneCoupling>,
    /** Incertitude relative recommandée sur la capacité thermique (inertie mal connue). */
    val capacityUncertainty: Double,
    /** Incertitude relative recommandée sur les conductances (U estimés ou inconnus). */
    val conductanceUncertainty: Double,
    val notes: List<String>,
)

/**
 * Construction du modèle RC à partir de l'étude :
 *  - baies, ponts thermiques et ventilation relient directement l'air à l'extérieur ;
 *  - les parois opaques relient la masse à l'extérieur (H_em) via le couplage air-masse
 *    H_ms = 9,1 · A_m (EN ISO 13790) ;
 *  - les planchers sur terre-plein relient la masse au sol (température moyenne annuelle) ;
 *  - les parois entre pièces de l'étude deviennent des couplages entre zones.
 */
object ZoneModelBuilder {

    /** Coefficient d'échange masse-air de l'EN ISO 13790, W/(m²·K). */
    const val H_MS_PER_M2 = 9.1

    fun build(
        building: Building,
        heatLoss: BuildingHeatLoss = HeatLossCalculator().calculate(building),
        maxHeatingW: Map<Long, Double> = emptyMap(),
        solarShadingFactor: Double = 0.7,
        singleZone: Boolean = false,
    ): ZoneModel {
        val notes = mutableListOf<String>()
        val zones = mutableListOf<ThermalZone>()
        val couplingMap = mutableMapOf<Pair<Long, Long>, Double>()
        val roomIds = building.rooms.map { it.id }.toSet()

        for (room in building.rooms) {
            val rl = heatLoss.room(room.id) ?: continue
            var hAirOut = rl.ventilationHWk
            var hOpaque = 0.0
            var hGround = 0.0
            var hFixed = 0.0
            var fixedWeighted = 0.0
            for (e in rl.elements) {
                when {
                    e.category == LossCategory.GROUND -> hGround += e.areaM2 * e.uValue
                    e.category == LossCategory.WINDOWS || e.category == LossCategory.DOORS ||
                        e.category == LossCategory.THERMAL_BRIDGES -> hAirOut += e.hWk
                    e.category == LossCategory.ADJACENT -> {
                        val surface = room.surfaces.firstOrNull { it.id == e.surfaceId }
                        val h = e.areaM2 * e.uValue
                        val adj = surface?.adjacentRoomId
                        if (surface?.boundary == Boundary.HEATED_ROOM && adj != null && adj in roomIds && adj != room.id) {
                            val key = if (room.id < adj) room.id to adj else adj to room.id
                            couplingMap[key] = maxOf(couplingMap[key] ?: 0.0, h)
                        } else {
                            val t = surface?.adjacentTemperatureC
                                ?: if (surface?.boundary == Boundary.ADJACENT_BUILDING) HeatLossCalculator.DEFAULT_ADJACENT_BUILDING_C
                                else building.setpointC
                            hFixed += h; fixedWeighted += h * t
                        }
                    }
                    else -> hOpaque += e.hWk
                }
            }
            val cap = ThermalInertia.roomCapacity(building, room)
            val hms = H_MS_PER_M2 * cap.effectiveMassAreaM2
            val hm = hOpaque
            val hem = when {
                hm <= 0 -> 0.0
                hm < 0.95 * hms -> 1.0 / (1.0 / hm - 1.0 / hms)
                else -> hm * 20.0
            }
            val apertures = mutableMapOf<Orientation, Double>()
            room.surfaces.filter { it.boundary == Boundary.EXTERIOR }.forEach { s ->
                val o = s.orientation ?: return@forEach
                val a = s.openings.sumOf { it.glazedAreaM2 * it.gValue } * solarShadingFactor
                if (a > 0) apertures[o] = (apertures[o] ?: 0.0) + a
            }
            zones += ThermalZone(
                id = room.id,
                name = room.name,
                airCapacityJk = cap.airJk,
                massCapacityJk = cap.massJk.coerceAtLeast(1.0),
                hAirOutdoorWk = hAirOut,
                hAirMassWk = hms.coerceAtLeast(1.0),
                hMassOutdoorWk = hem,
                hMassGroundWk = hGround,
                hAirFixedWk = hFixed,
                fixedTempC = if (hFixed > 0) fixedWeighted / hFixed else building.setpointC,
                setpointC = room.setpointC,
                maxHeatingW = maxHeatingW[room.id] ?: 0.0,
                internalGainsW = room.internalGainsW,
                solarApertures = apertures,
                floorAreaM2 = room.floorAreaM2,
            )
        }
        val couplings = couplingMap.map { (k, h) -> ZoneCoupling(k.first, k.second, h) }

        // Incertitudes : inertie (capacité) et part de U estimés.
        val layers = building.rooms.flatMap { r -> r.surfaces.flatMap { it.layers } }
        val uncertainLayers = layers.count { it.origin.isUncertain || it.material.origin.isUncertain }
        val capU = when {
            layers.isEmpty() -> 0.40
            uncertainLayers.toDouble() / layers.size > 0.5 -> 0.30
            else -> 0.20
        }
        val condU = (0.10 + 0.25 * heatLoss.uncertainShare).coerceIn(0.05, 0.35) +
            if (building.ventilation.n50Origin == ValueOrigin.MEASURED) 0.0 else 0.05
        if (layers.isEmpty()) notes += "Aucune composition de paroi : inertie évaluée par défaut, incertitude élevée."
        notes += "Capacité thermique : ±${(capU * 100).toInt()} % ; conductances : ±${(condU * 100).toInt()} %."

        if (!singleZone) return ZoneModel(zones, couplings, capU, condU, notes)

        // Agrégation monozone de la maison entière.
        val hFixedTotal = zones.sumOf { it.hAirFixedWk }
        val area = zones.sumOf { it.floorAreaM2 }.coerceAtLeast(1e-9)
        val apertures = mutableMapOf<Orientation, Double>()
        zones.forEach { z -> z.solarApertures.forEach { (o, a) -> apertures[o] = (apertures[o] ?: 0.0) + a } }
        val single = ThermalZone(
            id = 0L,
            name = "Maison entière",
            airCapacityJk = zones.sumOf { it.airCapacityJk },
            massCapacityJk = zones.sumOf { it.massCapacityJk },
            hAirOutdoorWk = zones.sumOf { it.hAirOutdoorWk },
            hAirMassWk = zones.sumOf { it.hAirMassWk },
            hMassOutdoorWk = zones.sumOf { it.hMassOutdoorWk },
            hMassGroundWk = zones.sumOf { it.hMassGroundWk },
            hAirFixedWk = hFixedTotal,
            fixedTempC = if (hFixedTotal > 0) zones.sumOf { it.hAirFixedWk * it.fixedTempC } / hFixedTotal else building.setpointC,
            setpointC = zones.sumOf { it.setpointC * it.floorAreaM2 } / area,
            maxHeatingW = zones.sumOf { it.maxHeatingW },
            internalGainsW = zones.sumOf { it.internalGainsW },
            solarApertures = apertures,
            floorAreaM2 = area,
        )
        return ZoneModel(listOf(single), emptyList(), capU, condU, notes)
    }
}
