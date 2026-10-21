package fr.thermohabitat.pro.ui.navigation

import kotlinx.serialization.Serializable

// --- Sections principales (barre de navigation) ---
@Serializable data object HomeRoute
@Serializable data object HouseRoute
@Serializable data object RadiatorsRoute
@Serializable data object SimulationsRoute
@Serializable data object ReportsRoute

// --- Bâtiment, pièces, parois ---
@Serializable data class BuildingEditRoute(val buildingId: Long)
@Serializable data class RoomEditRoute(val roomId: Long)
@Serializable data class SurfaceEditRoute(val surfaceId: Long)
@Serializable data object MaterialsRoute

// --- Radiateurs et photographies ---
@Serializable data class RadiatorEditRoute(val radiatorId: Long)
/** Prise de vue CameraX ; [ownerType] = nom de [fr.thermohabitat.pro.data.db.PhotoOwner]. */
@Serializable data class CaptureRoute(val ownerType: String, val ownerId: Long, val buildingId: Long)
@Serializable data class PhotoEditRoute(val photoId: Long)
@Serializable data class ElementCountRoute(val photoId: Long, val radiatorId: Long)
@Serializable data class MeasureRoute(val photoId: Long, val radiatorId: Long)
/** Identification géométrique sur photo : contour, échelle, comptage des éléments et rapprochement avec la base. */
@Serializable data class PhotoIdentifyRoute(val photoId: Long, val radiatorId: Long)
@Serializable data class RadiatorLibraryRoute(val radiatorId: Long)

// --- Simulations ---
@Serializable data object ComparisonRoute
@Serializable data object HeatPumpRoute
@Serializable data object CoolingRoute
@Serializable data object ScenariosRoute
@Serializable data class ScenarioEditRoute(val scenarioId: Long)
@Serializable data object EnergyRoute

// --- Réglages ---
@Serializable data object SettingsRoute
