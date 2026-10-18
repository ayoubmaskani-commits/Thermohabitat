package fr.thermohabitat.pro.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode(val label: String) { SYSTEM("Selon le système"), LIGHT("Clair"), DARK("Sombre") }

data class AppPreferences(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    val activeBuildingId: Long? = null,
    /** Guide de prise de vue déjà consulté. */
    val captureGuideSeen: Boolean = false,
)

/** Préférences de l'application (DataStore). */
@Singleton
class SettingsRepository @Inject constructor(private val dataStore: DataStore<Preferences>) {

    private object Keys {
        val THEME = stringPreferencesKey("theme")
        val DYNAMIC = booleanPreferencesKey("couleurs_dynamiques")
        val ACTIVE = longPreferencesKey("projet_actif")
        val GUIDE = booleanPreferencesKey("guide_photo_vu")
    }

    val preferences: Flow<AppPreferences> = dataStore.data.map { p ->
        AppPreferences(
            themeMode = p[Keys.THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            dynamicColor = p[Keys.DYNAMIC] ?: false,
            activeBuildingId = p[Keys.ACTIVE]?.takeIf { it > 0 },
            captureGuideSeen = p[Keys.GUIDE] ?: false,
        )
    }

    val activeBuildingId: Flow<Long?> = preferences.map { it.activeBuildingId }

    suspend fun setTheme(mode: ThemeMode) = dataStore.edit { it[Keys.THEME] = mode.name }
    suspend fun setDynamicColor(enabled: Boolean) = dataStore.edit { it[Keys.DYNAMIC] = enabled }
    suspend fun setActiveBuilding(id: Long?) = dataStore.edit { if (id == null) it.remove(Keys.ACTIVE) else it[Keys.ACTIVE] = id }
    suspend fun setCaptureGuideSeen() = dataStore.edit { it[Keys.GUIDE] = true }
}
