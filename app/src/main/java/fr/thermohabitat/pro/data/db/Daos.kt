package fr.thermohabitat.pro.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Paroi avec ses couches et ses baies. */
data class SurfaceWithDetails(
    @Embedded val surface: SurfaceEntity,
    @Relation(parentColumn = "id", entityColumn = "surfaceId")
    val layers: List<LayerEntity>,
    @Relation(parentColumn = "id", entityColumn = "surfaceId")
    val openings: List<OpeningEntity>,
)

/** Pièce avec ses parois détaillées et ses ponts thermiques. */
data class RoomWithDetails(
    @Embedded val room: RoomEntity,
    @Relation(entity = SurfaceEntity::class, parentColumn = "id", entityColumn = "roomId")
    val surfaces: List<SurfaceWithDetails>,
    @Relation(parentColumn = "id", entityColumn = "roomId")
    val bridges: List<ThermalBridgeEntity>,
)

@Dao
interface BuildingDao {
    @Query("SELECT * FROM buildings ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<BuildingEntity>>

    @Query("SELECT * FROM buildings WHERE id = :id")
    fun observe(id: Long): Flow<BuildingEntity?>

    @Query("SELECT * FROM buildings WHERE id = :id")
    suspend fun get(id: Long): BuildingEntity?

    @Query("SELECT * FROM buildings ORDER BY updatedAt DESC")
    suspend fun getAll(): List<BuildingEntity>

    @Insert
    suspend fun insert(building: BuildingEntity): Long

    @Update
    suspend fun update(building: BuildingEntity)

    @Query("DELETE FROM buildings WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE buildings SET updatedAt = :time WHERE id = :id")
    suspend fun touch(id: Long, time: Long = System.currentTimeMillis())
}

@Dao
interface RoomDao {
    @Transaction
    @Query("SELECT * FROM rooms WHERE buildingId = :buildingId ORDER BY floorLevel, sortOrder, id")
    fun observeWithDetails(buildingId: Long): Flow<List<RoomWithDetails>>

    @Transaction
    @Query("SELECT * FROM rooms WHERE buildingId = :buildingId ORDER BY floorLevel, sortOrder, id")
    suspend fun getWithDetails(buildingId: Long): List<RoomWithDetails>

    @Query("SELECT * FROM rooms WHERE buildingId = :buildingId ORDER BY floorLevel, sortOrder, id")
    fun observeForBuilding(buildingId: Long): Flow<List<RoomEntity>>

    @Query("SELECT * FROM rooms WHERE id = :id")
    fun observe(id: Long): Flow<RoomEntity?>

    @Query("SELECT * FROM rooms WHERE id = :id")
    suspend fun get(id: Long): RoomEntity?

    @Query("SELECT COALESCE(MAX(sortOrder), 0) FROM rooms WHERE buildingId = :buildingId")
    suspend fun maxSortOrder(buildingId: Long): Int

    @Insert
    suspend fun insert(room: RoomEntity): Long

    @Update
    suspend fun update(room: RoomEntity)

    @Query("DELETE FROM rooms WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface SurfaceDao {
    @Transaction
    @Query("SELECT * FROM surfaces WHERE id = :id")
    fun observeWithDetails(id: Long): Flow<SurfaceWithDetails?>

    @Query("SELECT * FROM surfaces WHERE id = :id")
    suspend fun get(id: Long): SurfaceEntity?

    @Query("SELECT * FROM surfaces WHERE roomId = :roomId ORDER BY sortOrder, id")
    suspend fun forRoom(roomId: Long): List<SurfaceEntity>

    @Query("SELECT COALESCE(MAX(sortOrder), 0) FROM surfaces WHERE roomId = :roomId")
    suspend fun maxSortOrder(roomId: Long): Int

    @Insert
    suspend fun insert(surface: SurfaceEntity): Long

    @Update
    suspend fun update(surface: SurfaceEntity)

    @Query("DELETE FROM surfaces WHERE id = :id")
    suspend fun delete(id: Long)

    // --- Couches ---
    @Query("SELECT * FROM layers WHERE surfaceId = :surfaceId ORDER BY position, id")
    suspend fun layers(surfaceId: Long): List<LayerEntity>

    @Insert
    suspend fun insertLayer(layer: LayerEntity): Long

    @Insert
    suspend fun insertLayers(layers: List<LayerEntity>)

    @Update
    suspend fun updateLayer(layer: LayerEntity)

    @Update
    suspend fun updateLayers(layers: List<LayerEntity>)

    @Query("DELETE FROM layers WHERE id = :id")
    suspend fun deleteLayer(id: Long)

    @Query("DELETE FROM layers WHERE surfaceId = :surfaceId")
    suspend fun deleteLayersOf(surfaceId: Long)

    // --- Baies ---
    @Insert
    suspend fun insertOpening(opening: OpeningEntity): Long

    @Update
    suspend fun updateOpening(opening: OpeningEntity)

    @Query("DELETE FROM openings WHERE id = :id")
    suspend fun deleteOpening(id: Long)
}

@Dao
interface ThermalBridgeDao {
    @Query("SELECT * FROM thermal_bridges WHERE roomId = :roomId ORDER BY id")
    fun observeForRoom(roomId: Long): Flow<List<ThermalBridgeEntity>>

    @Insert
    suspend fun insert(bridge: ThermalBridgeEntity): Long

    @Update
    suspend fun update(bridge: ThermalBridgeEntity)

    @Query("DELETE FROM thermal_bridges WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface MaterialDao {
    @Query("SELECT * FROM materials ORDER BY category, name")
    fun observeAll(): Flow<List<MaterialEntity>>

    @Query("SELECT * FROM materials ORDER BY category, name")
    suspend fun getAll(): List<MaterialEntity>

    @Query("SELECT * FROM materials WHERE id = :id")
    suspend fun get(id: Long): MaterialEntity?

    @Query("SELECT * FROM materials WHERE code = :code")
    suspend fun byCode(code: String): MaterialEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnore(materials: List<MaterialEntity>)

    @Insert
    suspend fun insert(material: MaterialEntity): Long

    @Update
    suspend fun update(material: MaterialEntity)

    @Query("DELETE FROM materials WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM layers WHERE materialId = :id")
    suspend fun usageCount(id: Long): Int
}

@Dao
interface RadiatorDao {
    @Query("SELECT * FROM radiators WHERE buildingId = :buildingId ORDER BY code, id")
    fun observeForBuilding(buildingId: Long): Flow<List<RadiatorEntity>>

    @Query("SELECT * FROM radiators WHERE buildingId = :buildingId ORDER BY code, id")
    suspend fun getForBuilding(buildingId: Long): List<RadiatorEntity>

    @Query("SELECT * FROM radiators WHERE id = :id")
    fun observe(id: Long): Flow<RadiatorEntity?>

    @Query("SELECT * FROM radiators WHERE id = :id")
    suspend fun get(id: Long): RadiatorEntity?

    @Query("SELECT code FROM radiators WHERE buildingId = :buildingId")
    suspend fun codes(buildingId: Long): List<String>

    @Insert
    suspend fun insert(radiator: RadiatorEntity): Long

    @Update
    suspend fun update(radiator: RadiatorEntity)

    @Query("DELETE FROM radiators WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface PhotoDao {
    @Query("SELECT * FROM photos WHERE ownerType = :ownerType AND ownerId = :ownerId ORDER BY createdAt")
    fun observeFor(ownerType: PhotoOwner, ownerId: Long): Flow<List<PhotoEntity>>

    @Query("SELECT * FROM photos WHERE ownerType = :ownerType AND ownerId = :ownerId ORDER BY createdAt")
    suspend fun getFor(ownerType: PhotoOwner, ownerId: Long): List<PhotoEntity>

    @Query("SELECT * FROM photos WHERE buildingId = :buildingId ORDER BY createdAt")
    fun observeForBuilding(buildingId: Long): Flow<List<PhotoEntity>>

    @Query("SELECT * FROM photos WHERE buildingId = :buildingId ORDER BY createdAt")
    suspend fun getForBuilding(buildingId: Long): List<PhotoEntity>

    @Query("SELECT * FROM photos WHERE id = :id")
    suspend fun get(id: Long): PhotoEntity?

    @Query("SELECT * FROM photos WHERE id = :id")
    fun observe(id: Long): Flow<PhotoEntity?>

    @Insert
    suspend fun insert(photo: PhotoEntity): Long

    @Update
    suspend fun update(photo: PhotoEntity)

    @Query("DELETE FROM photos WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface ScenarioDao {
    @Query("SELECT * FROM scenarios WHERE buildingId = :buildingId ORDER BY createdAt")
    fun observeForBuilding(buildingId: Long): Flow<List<ScenarioEntity>>

    @Query("SELECT * FROM scenarios WHERE buildingId = :buildingId ORDER BY createdAt")
    suspend fun getForBuilding(buildingId: Long): List<ScenarioEntity>

    @Query("SELECT * FROM scenarios WHERE id = :id")
    fun observe(id: Long): Flow<ScenarioEntity?>

    @Insert
    suspend fun insert(scenario: ScenarioEntity): Long

    @Update
    suspend fun update(scenario: ScenarioEntity)

    @Query("DELETE FROM scenarios WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface MeasurementDao {
    @Query("SELECT * FROM measurement_series WHERE buildingId = :buildingId ORDER BY createdAt")
    fun observeForBuilding(buildingId: Long): Flow<List<MeasurementSeriesEntity>>

    @Query("SELECT * FROM measurement_series WHERE buildingId = :buildingId ORDER BY createdAt")
    suspend fun getForBuilding(buildingId: Long): List<MeasurementSeriesEntity>

    @Insert
    suspend fun insert(series: MeasurementSeriesEntity): Long

    @Update
    suspend fun update(series: MeasurementSeriesEntity)

    @Query("DELETE FROM measurement_series WHERE id = :id")
    suspend fun delete(id: Long)
}
