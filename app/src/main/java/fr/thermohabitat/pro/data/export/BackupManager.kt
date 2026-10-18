package fr.thermohabitat.pro.data.export

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.thermohabitat.pro.data.db.PhotoOwner
import fr.thermohabitat.pro.data.files.PhotoStorage
import fr.thermohabitat.pro.data.repository.ProjectRepository
import fr.thermohabitat.thermal.backup.BackupJson
import fr.thermohabitat.thermal.backup.BackupPhoto
import fr.thermohabitat.thermal.backup.ProjectBackup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sauvegarde complète d'un projet : fichier JSON + photographies, dans une archive ZIP
 * que l'utilisateur enregistre ou partage lui-même (aucun envoi automatique).
 */
@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: ProjectRepository,
    private val photoStorage: PhotoStorage,
) {
    val exportDir: File get() = File(context.cacheDir, PhotoStorage.EXPORTS_DIR).apply { mkdirs() }

    suspend fun buildBackup(buildingId: Long): ProjectBackup {
        val p = repository.loadProject(buildingId) ?: error("Projet introuvable")
        val custom = p.building.rooms.flatMap { r -> r.surfaces.flatMap { s -> s.layers.map { it.material } } }
            .filter { !it.builtIn }.distinctBy { it.code }
        return ProjectBackup(
            exportedAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT).format(Date()),
            building = p.building,
            settings = p.settings,
            scenarios = repository.scenariosOf(buildingId),
            measurements = repository.measurementsOf(buildingId),
            customMaterials = custom,
            photos = p.photos.map { BackupPhoto(it.fileName, it.ownerType.name, it.ownerId, it.kind, it.caption) },
        )
    }

    /** Archive ZIP : projet.json + photos/… */
    suspend fun writeZip(buildingId: Long, out: OutputStream) = withContext(Dispatchers.IO) {
        val backup = buildBackup(buildingId)
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(ProjectBackup.JSON_ENTRY))
            zip.write(BackupJson.encode(backup).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            backup.photos.map { it.fileName }.distinct().forEach { name ->
                val f = photoStorage.file(name)
                if (f.exists()) {
                    zip.putNextEntry(ZipEntry(ProjectBackup.PHOTOS_DIR + name))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }

    suspend fun writeJson(buildingId: Long, out: OutputStream) = withContext(Dispatchers.IO) {
        out.use { it.write(BackupJson.encode(buildBackup(buildingId)).toByteArray(Charsets.UTF_8)) }
    }

    suspend fun zipToCache(buildingId: Long, name: String): File = withContext(Dispatchers.IO) {
        val f = File(exportDir, "${safeName(name)}_sauvegarde.zip")
        FileOutputStream(f).use { writeZip(buildingId, it) }
        f
    }

    suspend fun jsonToCache(buildingId: Long, name: String): File = withContext(Dispatchers.IO) {
        val f = File(exportDir, "${safeName(name)}.json")
        FileOutputStream(f).use { writeJson(buildingId, it) }
        f
    }

    /**
     * Importe une sauvegarde (ZIP avec photos, ou JSON seul). Les identifiants sont réattribués :
     * le projet importé est un nouveau projet, l'existant n'est jamais écrasé.
     */
    suspend fun import(uri: Uri): Long = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val head = ByteArray(2)
        resolver.openInputStream(uri)?.use { it.read(head) } ?: error("Fichier illisible")
        val isZip = head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
        var json: String? = null
        val photoFiles = mutableMapOf<String, File>()
        // Toutes les copies écrites dans le stockage privé (doublons compris) : rien ne doit rester en cas d'échec.
        val copied = mutableListOf<File>()
        val id = try {
            if (isZip) {
                resolver.openInputStream(uri)?.use { raw ->
                    ZipInputStream(BufferedInputStream(raw)).use { zip ->
                        var entry = zip.nextEntry
                        while (entry != null) {
                            val name = entry.name
                            when {
                                entry.isDirectory -> Unit
                                name == ProjectBackup.JSON_ENTRY -> json = zip.readBytes().toString(Charsets.UTF_8)
                                name.startsWith(ProjectBackup.PHOTOS_DIR) -> {
                                    // Protection « zip slip » : on ne garde que le nom de fichier, copié sous un nouveau nom.
                                    val original = name.removePrefix(ProjectBackup.PHOTOS_DIR)
                                    if (original.isNotEmpty() && !original.contains('/') && !original.contains('\\') && !original.contains("..")) {
                                        val target = photoStorage.newPhotoFile("import")
                                        copied += target
                                        FileOutputStream(target).use { zip.copyTo(it) }
                                        photoFiles[original] = target
                                    }
                                }
                            }
                            zip.closeEntry()
                            entry = zip.nextEntry
                        }
                    }
                }
            } else {
                json = resolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            }
            val backup = BackupJson.decode(json ?: error("Archive sans fichier ${ProjectBackup.JSON_ENTRY}"))
            repository.insertGraph(
                building = backup.building,
                settings = backup.settings,
                scenarios = backup.scenarios,
                measurements = backup.measurements,
                photos = backup.photos.map { Triple(it.fileName, it.ownerId, it.kind) },
                photoOwnerTypes = backup.photos.associate { it.fileName to (runCatching { PhotoOwner.valueOf(it.ownerType) }.getOrDefault(PhotoOwner.RADIATOR)) },
                photoFiles = photoFiles,
                nameSuffix = " (importé)",
                fromBackup = true,
            )
        } catch (t: Throwable) {
            // Échec (version, JSON, lecture, annulation) : aucune copie de photo ne reste sur le téléphone.
            copied.forEach { it.delete() }
            throw t
        }
        // Copies non rattachées au projet (propriétaire introuvable, entrée absente du JSON) : supprimées.
        val used = repository.loadProject(id)?.photos?.map { it.fileName }?.toSet().orEmpty()
        copied.filter { it.name !in used }.forEach { it.delete() }
        id
    }

    companion object {
        fun safeName(s: String): String = s.replace(Regex("[^A-Za-z0-9À-ÿ_-]+"), "_").trim('_').take(60).ifBlank { "projet" }
    }
}
