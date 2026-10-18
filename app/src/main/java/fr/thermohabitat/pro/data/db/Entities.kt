package fr.thermohabitat.pro.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import fr.thermohabitat.thermal.model.Boundary
import fr.thermohabitat.thermal.model.ConstructionType
import fr.thermohabitat.thermal.model.HeatingSystem
import fr.thermohabitat.thermal.model.IdentificationMethod
import fr.thermohabitat.thermal.model.InsulationType
import fr.thermohabitat.thermal.model.JoineryType
import fr.thermohabitat.thermal.model.MaterialCategory
import fr.thermohabitat.thermal.model.OpeningKind
import fr.thermohabitat.thermal.model.Orientation
import fr.thermohabitat.thermal.model.PanelType
import fr.thermohabitat.thermal.model.PhotoKind
import fr.thermohabitat.thermal.model.RadiatorEra
import fr.thermohabitat.thermal.model.RadiatorMaterial
import fr.thermohabitat.thermal.model.RadiatorType
import fr.thermohabitat.thermal.model.RoomUsage
import fr.thermohabitat.thermal.model.Shielding
import fr.thermohabitat.thermal.model.SurfaceKind
import fr.thermohabitat.thermal.model.ValueOrigin
import fr.thermohabitat.thermal.model.VentilationType
import fr.thermohabitat.thermal.model.WallType

/*
 * Schéma de la base locale Room.
 * Unités : m, m², °C, W, W/(m²·K) ; dimensions des radiateurs en mm.
 * Les énumérations sont stockées par leur nom (TEXT).
 */

@Entity(tableName = "buildings")
data class BuildingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val address: String = "",
    val commune: String = "",
    val altitudeM: Double = 200.0,
    val constructionYear: Int? = null,
    val declaredLivingAreaM2: Double? = null,
    val floorsCount: Int = 1,
    val ceilingHeightM: Double = 2.5,
    val constructionType: ConstructionType = ConstructionType.DETACHED_HOUSE,
    val wallType: WallType = WallType.OTHER,
    val insulationType: InsulationType = InsulationType.UNKNOWN,
    val joineryType: JoineryType = JoineryType.MIXED,
    val existingHeating: HeatingSystem = HeatingSystem.OTHER,
    val setpointC: Double = 20.0,
    // Climat
    val climateReference: String = "",
    val designOutdoorTempC: Double = -7.0,
    val designOutdoorOrigin: ValueOrigin = ValueOrigin.ESTIMATED,
    val meanAnnualOutdoorTempC: Double = 11.5,
    val degreeDays: Double = 2400.0,
    val degreeDaysBase: Double = 18.0,
    val degreeDaysOrigin: ValueOrigin = ValueOrigin.ESTIMATED,
    // Ventilation et étanchéité
    val ventilationType: VentilationType = VentilationType.NATURAL,
    val mechanicalFlowM3h: Double? = null,
    val heatRecoveryEfficiency: Double = 0.0,
    val n50: Double = 10.0,
    val n50Origin: ValueOrigin = ValueOrigin.ESTIMATED,
    val shielding: Shielding = Shielding.MODERATE,
    val buildingHeightM: Double = 6.0,
    // Hypothèses de calcul
    val thermalBridgeDeltaU: Double = 0.10,
    val reheatFactorWm2: Double = 0.0,
    val additionalHeatCapacityJm2K: Double = 20_000.0,
    /** Paramètres d'étude (régimes, PAC, énergie) sérialisés en JSON ([fr.thermohabitat.thermal.StudySettings]). */
    val settingsJson: String = "",
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "rooms",
    foreignKeys = [ForeignKey(BuildingEntity::class, ["id"], ["buildingId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("buildingId")],
)
data class RoomEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val buildingId: Long,
    val name: String,
    val floorLevel: Int = 0,
    val lengthM: Double = 4.0,
    val widthM: Double = 3.0,
    val heightM: Double = 2.5,
    val floorAreaOverrideM2: Double? = null,
    val setpointC: Double = 20.0,
    val usage: RoomUsage = RoomUsage.LIVING,
    val minAirChangeOverride: Double? = null,
    val internalGainsW: Double = 0.0,
    val planX: Double? = null,
    val planY: Double? = null,
    val notes: String = "",
    val sortOrder: Int = 0,
)

@Entity(
    tableName = "surfaces",
    foreignKeys = [ForeignKey(RoomEntity::class, ["id"], ["roomId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("roomId")],
)
data class SurfaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val roomId: Long,
    val label: String = "",
    val kind: SurfaceKind = SurfaceKind.WALL,
    val boundary: Boundary = Boundary.EXTERIOR,
    val orientation: Orientation? = null,
    val lengthM: Double? = null,
    val heightM: Double? = null,
    val areaOverrideM2: Double? = null,
    val uValueOverride: Double? = null,
    val uValueOrigin: ValueOrigin = ValueOrigin.ESTIMATED,
    val unheatedFactorB: Double? = null,
    val unheatedPresetCode: String? = null,
    val adjacentTemperatureC: Double? = null,
    val adjacentRoomId: Long? = null,
    val groundExposedPerimeterM: Double? = null,
    val groundWallThicknessM: Double? = null,
    val groundConductivity: Double? = null,
    val groundShallowWater: Boolean = false,
    val notes: String = "",
    val sortOrder: Int = 0,
)

@Entity(tableName = "materials", indices = [Index(value = ["code"], unique = true)])
data class MaterialEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val code: String,
    val name: String,
    val category: MaterialCategory,
    val lambda: Double,
    val density: Double,
    val specificHeat: Double,
    val lambdaMin: Double? = null,
    val lambdaMax: Double? = null,
    val source: String = "",
    val origin: ValueOrigin = ValueOrigin.DECLARED,
    val isInsulation: Boolean = false,
    val notes: String = "",
    val builtIn: Boolean = false,
)

@Entity(
    tableName = "layers",
    foreignKeys = [
        ForeignKey(SurfaceEntity::class, ["id"], ["surfaceId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(MaterialEntity::class, ["id"], ["materialId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index("surfaceId"), Index("materialId")],
)
data class LayerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val surfaceId: Long,
    /** Ordre de la couche, 0 = face intérieure. */
    val position: Int,
    val materialId: Long,
    val thicknessM: Double,
    val lambdaOverride: Double? = null,
    val origin: ValueOrigin = ValueOrigin.ESTIMATED,
)

@Entity(
    tableName = "openings",
    foreignKeys = [ForeignKey(SurfaceEntity::class, ["id"], ["surfaceId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("surfaceId")],
)
data class OpeningEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val surfaceId: Long,
    val kind: OpeningKind = OpeningKind.WINDOW,
    val label: String = "",
    val widthM: Double = 1.0,
    val heightM: Double = 1.2,
    val count: Int = 1,
    val uValue: Double = 3.0,
    val uOrigin: ValueOrigin = ValueOrigin.ESTIMATED,
    val gValue: Double = 0.6,
    val frameFraction: Double = 0.3,
    val presetCode: String? = null,
)

@Entity(
    tableName = "thermal_bridges",
    foreignKeys = [ForeignKey(RoomEntity::class, ["id"], ["roomId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("roomId")],
)
data class ThermalBridgeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val roomId: Long,
    val label: String,
    val lengthM: Double,
    val psi: Double,
    val origin: ValueOrigin = ValueOrigin.ESTIMATED,
)

@Entity(
    tableName = "radiators",
    foreignKeys = [
        ForeignKey(BuildingEntity::class, ["id"], ["buildingId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(RoomEntity::class, ["id"], ["roomId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index("buildingId"), Index("roomId")],
)
data class RadiatorEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val buildingId: Long,
    val roomId: Long? = null,
    val code: String = "",
    val label: String = "",
    val type: RadiatorType = RadiatorType.UNKNOWN,
    val material: RadiatorMaterial = RadiatorMaterial.UNKNOWN,
    val era: RadiatorEra = RadiatorEra.UNKNOWN,
    val sections: Int? = null,
    val columns: Int? = null,
    val panelType: PanelType? = null,
    val heightMm: Double? = null,
    val lengthMm: Double? = null,
    val depthMm: Double? = null,
    val dimensionsOrigin: ValueOrigin = ValueOrigin.UNKNOWN,
    val manufacturer: String = "",
    val model: String = "",
    val reference: String = "",
    val nominalPowerW: Double? = null,
    val nominalPowerOrigin: ValueOrigin = ValueOrigin.UNKNOWN,
    val ratingSupplyC: Double = 75.0,
    val ratingReturnC: Double = 65.0,
    val ratingAmbientC: Double = 20.0,
    val exponent: Double? = null,
    val exponentOrigin: ValueOrigin = ValueOrigin.ESTIMATED,
    val documentedUncertaintyPct: Double = 5.0,
    val identificationMethod: IdentificationMethod = IdentificationMethod.MANUAL,
    val identificationConfidence: Double? = null,
    /** Catégorie de la bibliothèque illustrée retenue. */
    val categoryCode: String? = null,
    /** Libellé proposé par un modèle d'IA (si installé), pour traçabilité. */
    val aiSuggestion: String? = null,
    val validated: Boolean = false,
    val validatedAt: Long? = null,
    val flowKgH: Double? = null,
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

enum class PhotoOwner { BUILDING, ROOM, RADIATOR }

@Entity(
    tableName = "photos",
    foreignKeys = [ForeignKey(BuildingEntity::class, ["id"], ["buildingId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("buildingId"), Index(value = ["ownerType", "ownerId"])],
)
data class PhotoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val buildingId: Long,
    val ownerType: PhotoOwner,
    val ownerId: Long,
    /** Nom du fichier dans le dossier privé des photos de l'application. */
    val fileName: String,
    val kind: PhotoKind = PhotoKind.FRONT,
    val caption: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "scenarios",
    foreignKeys = [ForeignKey(BuildingEntity::class, ["id"], ["buildingId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("buildingId")],
)
data class ScenarioEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val buildingId: Long,
    val name: String,
    /** Scénario complet sérialisé ([fr.thermohabitat.thermal.scenario.Scenario]). */
    val json: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "measurement_series",
    foreignKeys = [ForeignKey(BuildingEntity::class, ["id"], ["buildingId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("buildingId")],
)
data class MeasurementSeriesEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val buildingId: Long,
    val name: String,
    val roomId: Long? = null,
    val outdoorC: Double? = null,
    val startClockHour: Double = 22.0,
    /** Points mesurés sérialisés (liste de [fr.thermohabitat.thermal.sim.MeasuredPoint]). */
    val pointsJson: String = "[]",
    val createdAt: Long = System.currentTimeMillis(),
)
