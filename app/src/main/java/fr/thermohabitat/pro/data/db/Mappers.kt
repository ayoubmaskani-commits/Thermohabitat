package fr.thermohabitat.pro.data.db

import fr.thermohabitat.thermal.model.Building
import fr.thermohabitat.thermal.model.Climate
import fr.thermohabitat.thermal.model.GroundContact
import fr.thermohabitat.thermal.model.Layer
import fr.thermohabitat.thermal.model.LinearThermalBridge
import fr.thermohabitat.thermal.model.Material
import fr.thermohabitat.thermal.model.Opening
import fr.thermohabitat.thermal.model.PhotoRef
import fr.thermohabitat.thermal.model.Radiator
import fr.thermohabitat.thermal.model.RatingCondition
import fr.thermohabitat.thermal.model.Room
import fr.thermohabitat.thermal.model.Surface
import fr.thermohabitat.thermal.model.Ventilation

/*
 * Conversions entre les entités Room et le modèle du moteur thermique.
 * Le moteur ne connaît pas la base de données : la couche données assemble le graphe complet.
 */

fun MaterialEntity.toModel() = Material(
    id = id, code = code, name = name, category = category, lambda = lambda, density = density,
    specificHeat = specificHeat, lambdaMin = lambdaMin, lambdaMax = lambdaMax, source = source,
    origin = origin, isInsulation = isInsulation, notes = notes, builtIn = builtIn,
)

fun Material.toEntity(id: Long = 0, builtIn: Boolean = this.builtIn) = MaterialEntity(
    id = id, code = code, name = name, category = category, lambda = lambda, density = density,
    specificHeat = specificHeat, lambdaMin = lambdaMin, lambdaMax = lambdaMax, source = source,
    origin = origin, isInsulation = isInsulation, notes = notes, builtIn = builtIn,
)

fun LayerEntity.toModel(material: Material) = Layer(
    id = id, material = material, thicknessM = thicknessM, lambdaOverride = lambdaOverride, origin = origin,
)

fun OpeningEntity.toModel() = Opening(
    id = id, kind = kind, label = label, widthM = widthM, heightM = heightM, count = count, uValue = uValue,
    uOrigin = uOrigin, gValue = gValue, frameFraction = frameFraction, presetCode = presetCode,
)

fun Opening.toEntity(surfaceId: Long) = OpeningEntity(
    surfaceId = surfaceId, kind = kind, label = label, widthM = widthM, heightM = heightM, count = count,
    uValue = uValue, uOrigin = uOrigin, gValue = gValue, frameFraction = frameFraction, presetCode = presetCode,
)

fun SurfaceWithDetails.toModel(materials: Map<Long, Material>): Surface {
    val s = surface
    val ground = if (s.groundExposedPerimeterM != null || s.groundWallThicknessM != null || s.groundConductivity != null || s.groundShallowWater) {
        GroundContact(
            exposedPerimeterM = s.groundExposedPerimeterM ?: 0.0,
            wallThicknessM = s.groundWallThicknessM ?: 0.4,
            groundConductivity = s.groundConductivity ?: 2.0,
            shallowGroundwater = s.groundShallowWater,
        )
    } else null
    return Surface(
        id = s.id, label = s.label, kind = s.kind, boundary = s.boundary, orientation = s.orientation,
        lengthM = s.lengthM, heightM = s.heightM, areaOverrideM2 = s.areaOverrideM2,
        layers = layers.sortedBy { it.position }.mapNotNull { l -> materials[l.materialId]?.let { l.toModel(it) } },
        uValueOverride = s.uValueOverride, uValueOrigin = s.uValueOrigin, unheatedFactorB = s.unheatedFactorB,
        unheatedPresetCode = s.unheatedPresetCode, adjacentTemperatureC = s.adjacentTemperatureC,
        adjacentRoomId = s.adjacentRoomId, openings = openings.sortedBy { it.id }.map { it.toModel() },
        ground = ground, notes = s.notes,
    )
}

fun Surface.toEntity(roomId: Long, sortOrder: Int, adjacentRoomIdMapped: Long?) = SurfaceEntity(
    roomId = roomId, label = label, kind = kind, boundary = boundary, orientation = orientation, lengthM = lengthM,
    heightM = heightM, areaOverrideM2 = areaOverrideM2, uValueOverride = uValueOverride, uValueOrigin = uValueOrigin,
    unheatedFactorB = unheatedFactorB, unheatedPresetCode = unheatedPresetCode, adjacentTemperatureC = adjacentTemperatureC,
    adjacentRoomId = adjacentRoomIdMapped, groundExposedPerimeterM = ground?.exposedPerimeterM,
    groundWallThicknessM = ground?.wallThicknessM, groundConductivity = ground?.groundConductivity,
    groundShallowWater = ground?.shallowGroundwater ?: false, notes = notes, sortOrder = sortOrder,
)

fun RoomWithDetails.toModel(materials: Map<Long, Material>) = Room(
    id = room.id, name = room.name, floorLevel = room.floorLevel, lengthM = room.lengthM, widthM = room.widthM,
    heightM = room.heightM, floorAreaOverrideM2 = room.floorAreaOverrideM2, setpointC = room.setpointC,
    usage = room.usage, minAirChangeOverride = room.minAirChangeOverride, internalGainsW = room.internalGainsW,
    surfaces = surfaces.sortedWith(compareBy({ it.surface.sortOrder }, { it.surface.id })).map { it.toModel(materials) },
    thermalBridges = bridges.map { LinearThermalBridge(it.id, it.label, it.lengthM, it.psi, it.origin) },
    planX = room.planX, planY = room.planY, notes = room.notes,
)

fun Room.toEntity(buildingId: Long, sortOrder: Int) = RoomEntity(
    buildingId = buildingId, name = name, floorLevel = floorLevel, lengthM = lengthM, widthM = widthM, heightM = heightM,
    floorAreaOverrideM2 = floorAreaOverrideM2, setpointC = setpointC, usage = usage,
    minAirChangeOverride = minAirChangeOverride, internalGainsW = internalGainsW, planX = planX, planY = planY,
    notes = notes, sortOrder = sortOrder,
)

fun RadiatorEntity.toModel(photos: List<PhotoEntity> = emptyList()) = Radiator(
    id = id, code = code, roomId = roomId, label = label, type = type, material = material, era = era,
    sections = sections, columns = columns, panelType = panelType, heightMm = heightMm, lengthMm = lengthMm,
    depthMm = depthMm, dimensionsOrigin = dimensionsOrigin, manufacturer = manufacturer, model = model,
    reference = reference, nominalPowerW = nominalPowerW, nominalPowerOrigin = nominalPowerOrigin,
    rating = RatingCondition(ratingSupplyC, ratingReturnC, ratingAmbientC), exponent = exponent,
    exponentOrigin = exponentOrigin, documentedUncertaintyPct = documentedUncertaintyPct,
    identificationMethod = identificationMethod, identificationConfidence = identificationConfidence,
    validated = validated, flowKgH = flowKgH,
    photos = photos.map { PhotoRef(it.id, it.fileName, it.kind, it.caption) }, notes = notes,
)

fun Radiator.toEntity(buildingId: Long, roomIdMapped: Long?) = RadiatorEntity(
    buildingId = buildingId, roomId = roomIdMapped, code = code, label = label, type = type, material = material,
    era = era, sections = sections, columns = columns, panelType = panelType, heightMm = heightMm, lengthMm = lengthMm,
    depthMm = depthMm, dimensionsOrigin = dimensionsOrigin, manufacturer = manufacturer, model = model,
    reference = reference, nominalPowerW = nominalPowerW, nominalPowerOrigin = nominalPowerOrigin,
    ratingSupplyC = rating.supplyC, ratingReturnC = rating.returnC, ratingAmbientC = rating.ambientC,
    exponent = exponent, exponentOrigin = exponentOrigin, documentedUncertaintyPct = documentedUncertaintyPct,
    identificationMethod = identificationMethod, identificationConfidence = identificationConfidence,
    validated = validated, validatedAt = if (validated) System.currentTimeMillis() else null, flowKgH = flowKgH,
    notes = notes,
)

fun BuildingEntity.climate() = Climate(
    referenceName = climateReference, designOutdoorTempC = designOutdoorTempC, designOutdoorOrigin = designOutdoorOrigin,
    meanAnnualOutdoorTempC = meanAnnualOutdoorTempC, degreeDays = degreeDays, degreeDaysBase = degreeDaysBase,
    degreeDaysOrigin = degreeDaysOrigin, altitudeM = altitudeM,
)

fun BuildingEntity.ventilation() = Ventilation(
    type = ventilationType, mechanicalFlowM3h = mechanicalFlowM3h, heatRecoveryEfficiency = heatRecoveryEfficiency,
    n50 = n50, n50Origin = n50Origin, shielding = shielding, buildingHeightM = buildingHeightM,
)

fun BuildingEntity.toModel(rooms: List<Room>, radiators: List<Radiator>) = Building(
    id = id, name = name, address = address, commune = commune, constructionYear = constructionYear,
    declaredLivingAreaM2 = declaredLivingAreaM2, floorsCount = floorsCount, ceilingHeightM = ceilingHeightM,
    constructionType = constructionType, wallType = wallType, insulationType = insulationType,
    joineryType = joineryType, existingHeating = existingHeating, setpointC = setpointC,
    climate = climate(), ventilation = ventilation(), thermalBridgeDeltaU = thermalBridgeDeltaU,
    reheatFactorWm2 = reheatFactorWm2, additionalHeatCapacityJm2K = additionalHeatCapacityJm2K,
    rooms = rooms, radiators = radiators, notes = notes,
)

fun Building.toEntity(settingsJson: String) = BuildingEntity(
    name = name, address = address, commune = commune, altitudeM = climate.altitudeM, constructionYear = constructionYear,
    declaredLivingAreaM2 = declaredLivingAreaM2, floorsCount = floorsCount, ceilingHeightM = ceilingHeightM,
    constructionType = constructionType, wallType = wallType, insulationType = insulationType, joineryType = joineryType,
    existingHeating = existingHeating, setpointC = setpointC, climateReference = climate.referenceName,
    designOutdoorTempC = climate.designOutdoorTempC, designOutdoorOrigin = climate.designOutdoorOrigin,
    meanAnnualOutdoorTempC = climate.meanAnnualOutdoorTempC, degreeDays = climate.degreeDays,
    degreeDaysBase = climate.degreeDaysBase, degreeDaysOrigin = climate.degreeDaysOrigin,
    ventilationType = ventilation.type, mechanicalFlowM3h = ventilation.mechanicalFlowM3h,
    heatRecoveryEfficiency = ventilation.heatRecoveryEfficiency, n50 = ventilation.n50, n50Origin = ventilation.n50Origin,
    shielding = ventilation.shielding, buildingHeightM = ventilation.buildingHeightM,
    thermalBridgeDeltaU = thermalBridgeDeltaU, reheatFactorWm2 = reheatFactorWm2,
    additionalHeatCapacityJm2K = additionalHeatCapacityJm2K, settingsJson = settingsJson, notes = notes,
)
