package fr.thermohabitat.pro.data.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * Stockage des photographies dans le dossier PRIVÉ de l'application (inaccessible aux autres
 * applications, jamais téléversé). Les images importées sont redressées (EXIF) et limitées
 * à [MAX_SIDE_PX] pixels pour économiser l'espace.
 */
@Singleton
class PhotoStorage @Inject constructor(@ApplicationContext private val context: Context) {

    val directory: File get() = File(context.filesDir, DIR).apply { mkdirs() }

    fun file(fileName: String): File = File(directory, fileName)

    /** Nouveau fichier vide destiné à recevoir une capture CameraX. */
    fun newPhotoFile(prefix: String = "photo"): File = File(directory, "${prefix}_${UUID.randomUUID()}.jpg")

    /** Copie une image choisie dans la galerie vers le stockage privé. */
    suspend fun importFromUri(uri: Uri, prefix: String = "import"): File = withContext(Dispatchers.IO) {
        val tmp = File(context.cacheDir, "import_${UUID.randomUUID()}.tmp")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Image illisible" }
            FileOutputStream(tmp).use { input.copyTo(it) }
        }
        val out = newPhotoFile(prefix)
        normalize(tmp, out)
        tmp.delete()
        out
    }

    /** Redresse selon l'orientation EXIF, réduit si nécessaire et enregistre en JPEG. */
    suspend fun normalizeInPlace(file: File) = withContext(Dispatchers.IO) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        normalize(file, tmp)
        if (tmp.exists()) { file.delete(); tmp.renameTo(file) }
    }

    private fun normalize(source: File, target: File) {
        val bitmap = decodeOriented(source, MAX_SIDE_PX) ?: error("Format d'image non pris en charge")
        FileOutputStream(target).use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        bitmap.recycle()
    }

    /** Charge une image redressée, sous-échantillonnée pour tenir dans [maxSide] pixels. */
    suspend fun loadBitmap(fileName: String, maxSide: Int = 1600): Bitmap? = withContext(Dispatchers.IO) {
        decodeOriented(file(fileName), maxSide)
    }

    suspend fun saveBitmap(bitmap: Bitmap, fileName: String) = withContext(Dispatchers.IO) {
        FileOutputStream(file(fileName)).use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
    }

    fun delete(fileName: String) {
        file(fileName).delete()
    }

    fun dimensions(fileName: String): Pair<Int, Int> {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file(fileName).absolutePath, opts)
        return opts.outWidth to opts.outHeight
    }

    /** Efface les copies générées (rapports PDF, sauvegardes ZIP/JSON, CSV) partagées via le FileProvider : régénérables. */
    fun clearExports() {
        File(context.cacheDir, EXPORTS_DIR).deleteRecursively()
    }

    companion object {
        const val DIR = "photos"
        /** Dossier du cache où sont écrits les fichiers exportés (voir res/xml/file_paths.xml). */
        const val EXPORTS_DIR = "exports"
        const val MAX_SIDE_PX = 2560
        const val JPEG_QUALITY = 90

        fun decodeOriented(file: File, maxSide: Int): Bitmap? {
            if (!file.exists()) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
            val decoded = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return null
            val rotation = runCatching {
                when (ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            }.getOrDefault(0f)
            val scale = minOf(1f, maxSide.toFloat() / max(decoded.width, decoded.height))
            if (rotation == 0f && scale >= 1f) return decoded
            val m = Matrix().apply { if (scale < 1f) postScale(scale, scale); postRotate(rotation) }
            val result = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
            if (result !== decoded) decoded.recycle()
            return result
        }
    }
}
