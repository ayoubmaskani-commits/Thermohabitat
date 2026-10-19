package fr.thermohabitat.pro.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Base de données locale de l'application (aucune synchronisation distante).
 * Le schéma est exporté dans app/schemas pour préparer les migrations futures.
 */
@Database(
    entities = [
        BuildingEntity::class,
        RoomEntity::class,
        SurfaceEntity::class,
        MaterialEntity::class,
        LayerEntity::class,
        OpeningEntity::class,
        ThermalBridgeEntity::class,
        RadiatorEntity::class,
        PhotoEntity::class,
        ScenarioEntity::class,
        MeasurementSeriesEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun buildingDao(): BuildingDao
    abstract fun roomDao(): RoomDao
    abstract fun surfaceDao(): SurfaceDao
    abstract fun thermalBridgeDao(): ThermalBridgeDao
    abstract fun materialDao(): MaterialDao
    abstract fun radiatorDao(): RadiatorDao
    abstract fun photoDao(): PhotoDao
    abstract fun scenarioDao(): ScenarioDao
    abstract fun measurementDao(): MeasurementDao

    companion object {
        const val NAME = "thermohabitat.db"

        /** v2 : conditions d'une série de mesures (extérieur variable, température initiale, durée). */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                listOf("outdoorMinC", "outdoorMaxC", "initialC", "durationH")
                    .forEach { db.execSQL("ALTER TABLE measurement_series ADD COLUMN $it REAL") }
            }
        }
    }
}
