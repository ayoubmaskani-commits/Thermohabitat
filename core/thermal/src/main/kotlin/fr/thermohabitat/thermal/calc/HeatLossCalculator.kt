package fr.thermohabitat.thermal.calc

import fr.thermohabitat.thermal.library.UnheatedSpacePresets
import fr.thermohabitat.thermal.model.Boundary
import fr.thermohabitat.thermal.model.Building
import fr.thermohabitat.thermal.model.OpeningKind
import fr.thermohabitat.thermal.model.Room
import fr.thermohabitat.thermal.model.Surface
import fr.thermohabitat.thermal.model.SurfaceKind
import fr.thermohabitat.thermal.model.ValueOrigin
import fr.thermohabitat.thermal.model.VentilationType
import fr.thermohabitat.thermal.fr
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Postes de déperdition. */
enum class LossCategory(val label: String) {
    WALLS("Murs"),
    WINDOWS("Fenêtres"),
    DOORS("Portes"),
    FLOORS("Planchers bas"),
    GROUND("Sol (terre-plein)"),
    CEILINGS_ROOFS("Plafonds et toitures"),
    THERMAL_BRIDGES("Ponts thermiques"),
    ADJACENT("Échanges avec locaux chauffés voisins"),
    VENTILATION("Renouvellement d'air"),
    INFILTRATION("Infiltrations d'air"),
}

/** Contribution d'un élément (paroi, baie, pont thermique) à la déperdition d'une pièce. */
data class ElementLoss(
    val surfaceId: Long?,
    val openingId: Long?,
    val label: String,
    val category: LossCategory,
    val boundary: Boundary?,
    val areaM2: Double,
    /** U (W/(m²·K)) ou ψ (W/(m·K)) pour un pont thermique linéique. */
    val uValue: Double,
    /** Facteur de température (b, f_ij, f_g1·f_g2·G_w…). */
    val factor: Double,
    /** Coefficient de déperdition H = A·U·f (W/K). */
    val hWk: Double,
    /** Puissance de déperdition H·(θint − θe) (W). */
    val powerW: Double,
    val uOrigin: ValueOrigin,
    /** Élément donnant sur un local non chauffé. */
    val towardUnheated: Boolean = false,
    /** Élément de l'enveloppe du logement (exclut les échanges entre pièces chauffées de l'étude). */
    val isEnvelope: Boolean = true,
)

/** Résultat de déperdition d'une pièce (EN 12831-1, méthode pièce par pièce). */
data class RoomHeatLoss(
    val roomId: Long,
    val roomName: String,
    val floorLevel: Int,
    val setpointC: Double,
    val designOutdoorC: Double,
    val floorAreaM2: Double,
    val volumeM3: Double,
    val elements: List<ElementLoss>,
    /** H de transmission total de la pièce, y compris échanges avec pièces voisines (W/K). */
    val transmissionHWk: Double,
    /** H de transmission vers l'enveloppe uniquement (W/K). */
    val envelopeHWk: Double,
    val transmissionW: Double,
    val infiltrationFlowM3h: Double,
    val minimumFlowM3h: Double,
    /** Débit d'air retenu pour la pièce, rapporté à de l'air extérieur (m³/h). */
    val designFlowM3h: Double,
    val ventilationHWk: Double,
    val ventilationW: Double,
    val infiltrationW: Double,
    val airRenewalW: Double,
    val reheatW: Double,
    val totalW: Double,
    val warnings: List<String>,
) {
    val wattsPerM2: Double get() = if (floorAreaM2 > 0) totalW / floorAreaM2 else 0.0
    val wattsPerM3: Double get() = if (volumeM3 > 0) totalW / volumeM3 else 0.0
}

/** Bilan du bâtiment complet. */
data class BuildingHeatLoss(
    val designOutdoorC: Double,
    val rooms: List<RoomHeatLoss>,
    /** Coefficient de déperdition par transmission de l'enveloppe (W/K). */
    val envelopeHWk: Double,
    val envelopeTransmissionW: Double,
    val ventilationFlowM3h: Double,
    val ventilationHWk: Double,
    val ventilationW: Double,
    val infiltrationW: Double,
    val airRenewalW: Double,
    val reheatW: Double,
    /** Charge thermique de conception du bâtiment Φ_HL (W). */
    val totalW: Double,
    /** Somme des charges des pièces (dimensionnement des émetteurs), ≥ Φ_HL. */
    val sumOfRoomLoadsW: Double,
    val heatedAreaM2: Double,
    val heatedVolumeM3: Double,
    val byCategoryW: Map<LossCategory, Double>,
    /** Part de la transmission calculée à partir de valeurs estimées ou inconnues (0–1). */
    val uncertainShare: Double,
    val warnings: List<String>,
) {
    /** Coefficient de déperdition global H = H_T + H_V (W/K). */
    val totalHWk: Double get() = envelopeHWk + ventilationHWk
    val totalKw: Double get() = totalW / 1000.0
    val wattsPerM2: Double get() = if (heatedAreaM2 > 0) totalW / heatedAreaM2 else 0.0
    fun room(id: Long): RoomHeatLoss? = rooms.firstOrNull { it.roomId == id }
}

/**
 * Moteur de calcul des déperditions de conception, fondé sur les principes de la NF EN 12831-1 :
 *
 *  - transmission : Φ_T,i = Σ A·U·f·(θint,i − θe) avec f = 1 (extérieur), b (local non chauffé),
 *    f_ij (pièce ou mitoyen à une autre température), f_g1·f_g2·G_w (terre-plein, EN ISO 13370) ;
 *  - ponts thermiques : forfait ΔU_TB appliqué aux parois de l'enveloppe et/ou ψ·l détaillés ;
 *  - ventilation et infiltrations : Φ_V,i = 0,34·q_i·(θint,i − θe) avec
 *    q_inf,i = 2·V·n50·e·ε et q_min,i = n_min·V ;
 *  - surpuissance de relance optionnelle : Φ_RH,i = A_i·f_RH.
 *
 * Les apports internes et solaires ne sont pas déduits (approche de dimensionnement).
 */
class HeatLossCalculator {

    fun calculate(building: Building): BuildingHeatLoss {
        val warnings = mutableListOf<String>()
        val climate = building.climate
        val thetaE = climate.designOutdoorTempC
        val ground = groundParameters(building, warnings)
        val totalVolume = building.heatedVolumeM3

        val roomResults = building.rooms.map { room -> calculateRoom(building, room, ground, totalVolume) }
        if (building.rooms.isEmpty()) warnings += "Aucune pièce saisie : le bilan est vide."

        val envelopeH = roomResults.sumOf { it.envelopeHWk }
        val envelopeW = roomResults.sumOf { r -> r.elements.filter { it.isEnvelope }.sumOf { it.powerW } }

        // Ventilation à l'échelle du bâtiment : les infiltrations ne s'exercent pas simultanément
        // sur toutes les façades (facteur 0,5 sur Σ q_inf), cf. NF EN 12831.
        val v = building.ventilation
        val sumInf = roomResults.sumOf { it.infiltrationFlowM3h }
        val sumMin = roomResults.sumOf { it.minimumFlowM3h }
        val mech = v.mechanicalFlowM3h ?: sumMin
        val (infPart, airPart) = when (v.type) {
            VentilationType.NATURAL -> {
                val q = max(0.5 * sumInf, sumMin)
                val inf = min(0.5 * sumInf, q)
                inf to (q - inf)
            }
            VentilationType.EXTRACT -> {
                val q = max(0.5 * sumInf + mech, sumMin)
                val inf = 0.5 * sumInf
                inf to (q - inf)
            }
            VentilationType.BALANCED -> {
                0.5 * sumInf to mech * (1.0 - v.heatRecoveryEfficiency.coerceIn(0.0, 0.95))
            }
        }
        val qBuilding = infPart + airPart
        val meanSetpoint = if (totalVolume > 0) {
            building.rooms.sumOf { it.setpointC * it.volumeM3 } / totalVolume
        } else building.setpointC
        val hv = AIR_HEAT_CAPACITY * qBuilding
        val dTbuilding = meanSetpoint - thetaE
        val ventilationW = hv * dTbuilding
        val infiltrationW = AIR_HEAT_CAPACITY * infPart * dTbuilding
        val airRenewalW = AIR_HEAT_CAPACITY * airPart * dTbuilding
        val reheatW = roomResults.sumOf { it.reheatW }

        val byCategory = LinkedHashMap<LossCategory, Double>()
        LossCategory.entries.forEach { byCategory[it] = 0.0 }
        roomResults.flatMap { it.elements }.filter { it.isEnvelope }.forEach {
            byCategory[it.category] = byCategory.getValue(it.category) + it.powerW
        }
        byCategory[LossCategory.INFILTRATION] = infiltrationW
        byCategory[LossCategory.VENTILATION] = airRenewalW

        val uncertainW = roomResults.flatMap { it.elements }
            .filter { it.isEnvelope && it.uOrigin.isUncertain }
            .sumOf { it.powerW }
        val uncertainShare = if (envelopeW > 0) (uncertainW / envelopeW).coerceIn(0.0, 1.0) else 0.0

        if (v.n50Origin.isUncertain) {
            warnings += "Étanchéité à l'air estimée (n50 = ${v.n50.fr(1)} h⁻¹) : un test d'infiltrométrie préciserait les infiltrations."
        }
        if (climate.designOutdoorOrigin.isUncertain) {
            warnings += "Température extérieure de base estimée (${thetaE.fr(1)} °C) : à vérifier pour la commune."
        }
        roomResults.forEach { r -> r.warnings.forEach { warnings += "${r.roomName} : $it" } }

        return BuildingHeatLoss(
            designOutdoorC = thetaE,
            rooms = roomResults,
            envelopeHWk = envelopeH,
            envelopeTransmissionW = envelopeW,
            ventilationFlowM3h = qBuilding,
            ventilationHWk = hv,
            ventilationW = ventilationW,
            infiltrationW = infiltrationW,
            airRenewalW = airRenewalW,
            reheatW = reheatW,
            totalW = envelopeW + ventilationW + reheatW,
            sumOfRoomLoadsW = roomResults.sumOf { it.totalW },
            heatedAreaM2 = building.heatedFloorAreaM2,
            heatedVolumeM3 = totalVolume,
            byCategoryW = byCategory,
            uncertainShare = uncertainShare,
            warnings = warnings.distinct(),
        )
    }

    /** Paramètres de sol communs au bâtiment (B' calculé sur l'ensemble des planchers sur terre-plein). */
    internal data class GroundParameters(val bPrime: Double, val wallThicknessM: Double)

    private fun groundParameters(building: Building, warnings: MutableList<String>): GroundParameters? {
        val groundFloors = building.rooms.flatMap { room ->
            room.surfaces.filter { it.boundary == Boundary.GROUND }.map { room to it }
        }
        if (groundFloors.isEmpty()) return null
        val area = groundFloors.sumOf { (room, s) -> SurfaceGeometry.grossArea(s, room) }
        if (area <= 0) return null
        var perimeter = groundFloors.sumOf { (_, s) -> s.ground?.exposedPerimeterM ?: 0.0 }
        if (perimeter <= 0) {
            perimeter = 4.0 * sqrt(area)
            warnings += "Périmètre exposé des planchers sur terre-plein non renseigné : estimé à ${perimeter.fr(1)} m (emprise carrée)."
        }
        val thicknesses = groundFloors.mapNotNull { it.second.ground?.wallThicknessM }
        val w = if (thicknesses.isEmpty()) 0.4 else thicknesses.average()
        return GroundParameters(GroundHeatLoss.characteristicDimension(area, perimeter), w)
    }

    private fun calculateRoom(
        building: Building,
        room: Room,
        ground: GroundParameters?,
        totalVolume: Double,
    ): RoomHeatLoss {
        val warnings = mutableListOf<String>()
        val thetaI = room.setpointC
        val thetaE = building.climate.designOutdoorTempC
        val dT = thetaI - thetaE
        val elements = mutableListOf<ElementLoss>()
        val deltaUtb = building.thermalBridgeDeltaU.coerceAtLeast(0.0)
        var bridgeH = 0.0

        if (room.surfaces.isEmpty()) warnings += "aucune paroi saisie."

        for (surface in room.surfaces) {
            val gross = SurfaceGeometry.grossArea(surface, room)
            val openingsArea = SurfaceGeometry.openingsArea(surface)
            if (openingsArea > gross + 1e-6) {
                warnings += "les baies de « ${surface.label.ifBlank { surface.kind.label }} » dépassent la surface de la paroi."
            }
            val net = (gross - openingsArea).coerceAtLeast(0.0)
            val resolved = SurfaceGeometry.resolveU(surface)
            if (resolved.fromDefault) {
                warnings += "« ${surface.label.ifBlank { surface.kind.label }} » sans composition : U par défaut ${resolved.value.fr(2)} W/(m²·K)."
            }
            val label = surface.label.ifBlank { surface.kind.label }

            if (surface.boundary == Boundary.GROUND) {
                val g = surface.ground
                val uEquiv = if (surface.uValueOverride != null) {
                    surface.uValueOverride
                } else if (ground != null) {
                    val rf = ThermalResistance.layersResistance(surface.layers, HeatFlowDirection.DOWNWARD)
                    GroundHeatLoss.slabUValue(ground.bPrime, ground.wallThicknessM, rf, g?.groundConductivity ?: 2.0)
                } else resolved.value
                val f = GroundHeatLoss.F_G1 *
                    GroundHeatLoss.fg2(thetaI, building.climate.meanAnnualOutdoorTempC, thetaE) *
                    GroundHeatLoss.groundwaterFactor(g?.shallowGroundwater ?: false)
                val h = gross * uEquiv * f
                elements += ElementLoss(
                    surface.id, null, label, LossCategory.GROUND, surface.boundary, gross, uEquiv, f, h, h * dT,
                    if (surface.uValueOverride != null) surface.uValueOrigin else resolved.origin,
                )
                continue
            }

            val f = temperatureFactor(building, room, surface, warnings)
            val isEnvelope = surface.boundary != Boundary.HEATED_ROOM
            val appliesBridge = surface.boundary == Boundary.EXTERIOR || surface.boundary == Boundary.UNHEATED
            val towardUnheated = surface.boundary == Boundary.UNHEATED
            val category = when {
                surface.boundary == Boundary.HEATED_ROOM || surface.boundary == Boundary.ADJACENT_BUILDING -> LossCategory.ADJACENT
                surface.kind == SurfaceKind.WALL -> LossCategory.WALLS
                surface.kind == SurfaceKind.FLOOR -> LossCategory.FLOORS
                else -> LossCategory.CEILINGS_ROOFS
            }
            if (net > 0) {
                val h = net * resolved.value * f
                elements += ElementLoss(
                    surface.id, null, label, category, surface.boundary, net, resolved.value, f, h, h * dT,
                    resolved.origin, towardUnheated, isEnvelope,
                )
                if (appliesBridge) bridgeH += net * deltaUtb * f
            }
            for (opening in surface.openings) {
                val a = opening.areaM2
                if (a <= 0) continue
                val h = a * opening.uValue * f
                val cat = when {
                    category == LossCategory.ADJACENT -> LossCategory.ADJACENT
                    opening.kind == OpeningKind.DOOR -> LossCategory.DOORS
                    else -> LossCategory.WINDOWS
                }
                val oLabel = opening.label.ifBlank { opening.kind.label } +
                    (if (opening.count > 1) " ×${opening.count}" else "")
                elements += ElementLoss(
                    surface.id, opening.id, oLabel, cat, surface.boundary, a, opening.uValue, f, h, h * dT,
                    opening.uOrigin, towardUnheated, isEnvelope,
                )
                if (appliesBridge) bridgeH += a * deltaUtb * f
            }
        }

        if (bridgeH > 0) {
            elements += ElementLoss(
                null, null, "Ponts thermiques (forfait ΔU = ${deltaUtb.fr(2)} W/(m²·K))",
                LossCategory.THERMAL_BRIDGES, null, 0.0, deltaUtb, 1.0, bridgeH, bridgeH * dT, ValueOrigin.ESTIMATED,
            )
        }
        for (tb in room.thermalBridges) {
            val h = tb.psi * tb.lengthM
            elements += ElementLoss(
                null, null, tb.label.ifBlank { "Pont thermique linéique" }, LossCategory.THERMAL_BRIDGES, Boundary.EXTERIOR,
                tb.lengthM, tb.psi, 1.0, h, h * dT, tb.origin,
            )
        }

        val transmissionH = elements.sumOf { it.hWk }
        val envelopeH = elements.filter { it.isEnvelope }.sumOf { it.hWk }

        // --- Ventilation et infiltrations ---
        val v = building.ventilation
        val volume = room.volumeM3
        val exposedOpeningFacades = room.surfaces.count { s ->
            s.boundary == Boundary.EXTERIOR && s.openings.isNotEmpty()
        }
        val e = when (exposedOpeningFacades) {
            0 -> 0.0
            1 -> v.shielding.eOneOpening
            else -> v.shielding.eSeveralOpenings
        }
        val epsilon = when {
            v.buildingHeightM <= 10.0 -> 1.0
            v.buildingHeightM <= 30.0 -> 1.2
            else -> 1.5
        }
        val qInf = 2.0 * volume * v.n50 * e * epsilon
        val qMin = room.minAirChangePerHour * volume
        val share = if (totalVolume > 0) volume / totalVolume else 0.0
        val sumMinBuilding = building.rooms.sumOf { it.minAirChangePerHour * it.volumeM3 }
        val mechTotal = v.mechanicalFlowM3h ?: sumMinBuilding
        val qRoom: Double
        val infPart: Double
        when (v.type) {
            VentilationType.NATURAL -> {
                qRoom = max(qInf, qMin); infPart = min(qInf, qRoom)
            }
            VentilationType.EXTRACT -> {
                qRoom = max(qInf + mechTotal * share, qMin); infPart = min(qInf, qRoom)
            }
            VentilationType.BALANCED -> {
                qRoom = qInf + mechTotal * share * (1.0 - v.heatRecoveryEfficiency.coerceIn(0.0, 0.95)); infPart = qInf
            }
        }
        val hv = AIR_HEAT_CAPACITY * qRoom
        val ventW = hv * dT
        val infW = AIR_HEAT_CAPACITY * infPart * dT
        val reheat = room.floorAreaM2 * building.reheatFactorWm2.coerceAtLeast(0.0)
        val transmissionW = transmissionH * dT

        return RoomHeatLoss(
            roomId = room.id,
            roomName = room.name,
            floorLevel = room.floorLevel,
            setpointC = thetaI,
            designOutdoorC = thetaE,
            floorAreaM2 = room.floorAreaM2,
            volumeM3 = volume,
            elements = elements,
            transmissionHWk = transmissionH,
            envelopeHWk = envelopeH,
            transmissionW = transmissionW,
            infiltrationFlowM3h = qInf,
            minimumFlowM3h = qMin,
            designFlowM3h = qRoom,
            ventilationHWk = hv,
            ventilationW = ventW,
            infiltrationW = infW,
            airRenewalW = ventW - infW,
            reheatW = reheat,
            totalW = transmissionW + ventW + reheat,
            warnings = warnings,
        )
    }

    /** Facteur de température f appliqué à une paroi selon la nature de l'espace adjacent. */
    private fun temperatureFactor(building: Building, room: Room, surface: Surface, warnings: MutableList<String>): Double {
        val thetaI = room.setpointC
        val thetaE = building.climate.designOutdoorTempC
        val dT = thetaI - thetaE
        return when (surface.boundary) {
            Boundary.EXTERIOR -> 1.0
            Boundary.GROUND -> 1.0
            Boundary.UNHEATED -> {
                surface.unheatedFactorB
                    ?: UnheatedSpacePresets.find(surface.unheatedPresetCode)?.b
                    ?: UnheatedSpacePresets.DEFAULT_B.also {
                        warnings += "local non chauffé non qualifié derrière « ${surface.label.ifBlank { surface.kind.label }} » : b = $it supposé."
                    }
            }
            Boundary.HEATED_ROOM -> {
                val thetaAdj = building.room(surface.adjacentRoomId)?.setpointC
                    ?: surface.adjacentTemperatureC
                    ?: building.setpointC
                if (dT <= 0) 0.0 else (thetaI - thetaAdj) / dT
            }
            Boundary.ADJACENT_BUILDING -> {
                val thetaAdj = surface.adjacentTemperatureC ?: DEFAULT_ADJACENT_BUILDING_C.also {
                    warnings += "température du mitoyen non renseignée : $it °C supposés."
                }
                if (dT <= 0) 0.0 else (thetaI - thetaAdj) / dT
            }
        }
    }

    companion object {
        /** ρ·cp de l'air ramené aux débits en m³/h : 1,2 kg/m³ × 1005 J/(kg·K) / 3600 ≈ 0,34 Wh/(m³·K). */
        const val AIR_HEAT_CAPACITY = 0.34

        /** Température supposée d'un logement mitoyen non renseigné (°C). */
        const val DEFAULT_ADJACENT_BUILDING_C = 15.0
    }
}
