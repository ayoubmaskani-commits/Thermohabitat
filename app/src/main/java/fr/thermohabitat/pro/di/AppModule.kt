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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/** Portée de coroutines de l'application (survit aux écrans, ex. sauvegarde finale d'un formulaire). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "reglages")

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME).addMigrations(AppDatabase.MIGRATION_1_2).build()

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> = context.settingsDataStore

    @Provides
    @Singleton
    fun provideStudyAnalyzer(): StudyAnalyzer = StudyAnalyzer()

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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

/** Liaison de l'interface de reconnaissance à l'implémentation TensorFlow Lite (inactive sans modèle installé). */
@Module
@InstallIn(SingletonComponent::class)
abstract class RecognitionModule {
    @dagger.Binds
    @Singleton
    abstract fun bindClassifier(impl: fr.thermohabitat.pro.recognition.TfLiteRadiatorClassifier): fr.thermohabitat.pro.recognition.RadiatorClassifier
}
