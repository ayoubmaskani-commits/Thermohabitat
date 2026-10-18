package fr.thermohabitat.pro.recognition

import android.content.Context
import android.graphics.Bitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import javax.inject.Inject
import javax.inject.Singleton

/** Proposition de catégorie issue d'un modèle d'IA. */
data class ClassifierSuggestion(
    /** Code de catégorie de la bibliothèque illustrée (ex. « fonte_4col »). */
    val categoryCode: String,
    val confidence: Float,
)

sealed interface ClassificationResult {
    /** Aucun modèle entraîné et validé n'est installé : la reconnaissance automatique est indisponible. */
    data object ModelUnavailable : ClassificationResult
    data class Suggestions(val items: List<ClassifierSuggestion>, val modelName: String) : ClassificationResult
    data class Failure(val message: String) : ClassificationResult
}

/**
 * Interface de reconnaissance du type de radiateur.
 *
 * Cette version de l'application ne contient AUCUN modèle entraîné : [ClassificationResult.ModelUnavailable]
 * est renvoyé et l'identification se fait avec la bibliothèque illustrée. Pour activer l'IA,
 * déposer dans `app/src/main/assets/models/` :
 *  - `radiator_classifier.tflite` : classifieur d'images (entrée RGB, sortie probabilités) ;
 *  - `radiator_labels.txt` : un code de catégorie de [fr.thermohabitat.thermal.library.RadiatorLibrary] par ligne.
 */
interface RadiatorClassifier {
    val isAvailable: Boolean
    suspend fun classify(bitmap: Bitmap): ClassificationResult
}

@Singleton
class TfLiteRadiatorClassifier @Inject constructor(
    @ApplicationContext private val context: Context,
) : RadiatorClassifier {

    private val interpreter: Interpreter? by lazy { loadModel()?.let { runCatching { Interpreter(it) }.getOrNull() } }
    private val labels: List<String> by lazy {
        runCatching {
            context.assets.open(LABELS_ASSET).bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() }
        }.getOrDefault(emptyList())
    }

    override val isAvailable: Boolean get() = interpreter != null && labels.isNotEmpty()

    override suspend fun classify(bitmap: Bitmap): ClassificationResult = withContext(Dispatchers.Default) {
        val tflite = interpreter ?: return@withContext ClassificationResult.ModelUnavailable
        if (labels.isEmpty()) return@withContext ClassificationResult.ModelUnavailable
        runCatching {
            val input = tflite.getInputTensor(0)
            val shape = input.shape() // [1, h, w, 3]
            val h = shape[1]; val w = shape[2]
            val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
            val isFloat = input.dataType() == DataType.FLOAT32
            val buffer = ByteBuffer.allocateDirect(w * h * 3 * if (isFloat) 4 else 1).order(ByteOrder.nativeOrder())
            val pixels = IntArray(w * h)
            scaled.getPixels(pixels, 0, w, 0, 0, w, h)
            for (p in pixels) {
                val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
                if (isFloat) {
                    buffer.putFloat(r / 255f); buffer.putFloat(g / 255f); buffer.putFloat(b / 255f)
                } else {
                    buffer.put(r.toByte()); buffer.put(g.toByte()); buffer.put(b.toByte())
                }
            }
            buffer.rewind()
            val outTensor = tflite.getOutputTensor(0)
            val n = outTensor.shape().last()
            val scores = FloatArray(n)
            if (outTensor.dataType() == DataType.FLOAT32) {
                val out = Array(1) { FloatArray(n) }
                tflite.run(buffer, out)
                out[0].copyInto(scores)
            } else {
                val out = Array(1) { ByteArray(n) }
                tflite.run(buffer, out)
                for (i in 0 until n) scores[i] = (out[0][i].toInt() and 0xFF) / 255f
            }
            val items = scores.withIndex()
                .filter { it.index < labels.size }
                .sortedByDescending { it.value }
                .take(3)
                .map { ClassifierSuggestion(labels[it.index], it.value) }
            ClassificationResult.Suggestions(items, MODEL_ASSET.substringAfterLast('/'))
        }.getOrElse { ClassificationResult.Failure(it.message ?: "Erreur du modèle") }
    }

    private fun loadModel(): MappedByteBuffer? = try {
        context.assets.openFd(MODEL_ASSET).use { fd ->
            java.io.FileInputStream(fd.fileDescriptor).channel.use { ch ->
                ch.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        }
    } catch (_: FileNotFoundException) {
        null
    } catch (_: Exception) {
        null
    }

    companion object {
        const val MODEL_ASSET = "models/radiator_classifier.tflite"
        const val LABELS_ASSET = "models/radiator_labels.txt"
    }
}
