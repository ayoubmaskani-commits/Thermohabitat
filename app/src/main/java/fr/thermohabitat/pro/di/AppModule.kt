package fr.thermohabitat.pro.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import fr.thermohabitat.pro.data.db.AppDatabase
import fr.thermohabitat.thermal.StudyAnalyzer
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "reglages")

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME).build()

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> = context.settingsDataStore

    @Provides
    @Singleton
    fun provideStudyAnalyzer(): StudyAnalyzer = StudyAnalyzer()

    @Provides fun buildingDao(db: AppDatabase) = db.buildingDao()
    @Provides fun roomDao(db: AppDatabase) = db.roomDao()
    @Provides fun surfaceDao(db: AppDatabase) = db.surfaceDao()
    @Provides fun thermalBridgeDao(db: AppDatabase) = db.thermalBridgeDao()
    @Provides fun materialDao(db: AppDatabase) = db.materialDao()
    @Provides fun radiatorDao(db: AppDatabase) = db.radiatorDao()
    @Provides fun photoDao(db: AppDatabase) = db.photoDao()
    @Provides fun scenarioDao(db: AppDatabase) = db.scenarioDao()
    @Provides fun measurementDao(db: AppDatabase) = db.measurementDao()
}
