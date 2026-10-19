package fr.thermohabitat.thermal.backup

import fr.thermohabitat.thermal.StudySettings
import fr.thermohabitat.thermal.model.Building
import fr.thermohabitat.thermal.model.Material
import fr.thermohabitat.thermal.model.PhotoKind
import fr.thermohabitat.thermal.scenario.Scenario
import fr.thermohabitat.thermal.sim.MeasuredPoint
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Photographie référencée dans une sauvegarde (le fichier est stocké dans l'archive sous photos/). */
@Serializable
data class BackupPhoto(
    val fileName: String,
    val ownerType: String,
    val ownerId: Long,
    val kind: PhotoKind = PhotoKind.OTHER,
    val caption: String = "",
)

/** Série de températures mesurées servant au calage du modèle dynamique. */
@Serializable
data class MeasurementSeries(
    val name: String,
    val roomId: Long? = null,
    val outdoorC: Double? = null,
    val startClockHour: Double = 22.0,
    val points: List<MeasuredPoint> = emptyList(),
    /** Conditions de la mesure (absentes des sauvegardes antérieures). */
    val outdoorMinC: Double? = null,
    val outdoorMaxC: Double? = null,
    val initialC: Double? = null,
    val durationH: Double? = null,
)

/**
 * Format de sauvegarde complet d'un projet (JSON). Les identifiants sont ceux de l'appareil
 * d'origine ; ils sont réattribués à l'import.
 */
@Serializable
data class ProjectBackup(
    val formatVersion: Int = CURRENT_VERSION,
    val application: String = "ThermoHabitat Pro",
    val exportedAt: String,
    val building: Building,
    val settings: StudySettings = StudySettings(),
    val scenarios: List<Scenario> = emptyList(),
    val measurements: List<MeasurementSeries> = emptyList(),
    val customMaterials: List<Material> = emptyList(),
    val photos: List<BackupPhoto> = emptyList(),
) {
    companion object {
        const val CURRENT_VERSION = 1
        const val JSON_ENTRY = "projet.json"
        const val PHOTOS_DIR = "photos/"
    }
}

object BackupJson {
    val json: Json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        classDiscriminator = "kind_type"
    }

    fun encode(backup: ProjectBackup): String = json.encodeToString(ProjectBackup.serializer(), backup)

    fun decode(text: String): ProjectBackup {
        val backup = json.decodeFromString(ProjectBackup.serializer(), text)
        require(backup.formatVersion <= ProjectBackup.CURRENT_VERSION) {
            "Sauvegarde créée par une version plus récente de l'application (format ${backup.formatVersion})."
        }
        return backup
    }
}
