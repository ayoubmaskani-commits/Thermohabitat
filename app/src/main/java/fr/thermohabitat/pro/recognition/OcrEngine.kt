package fr.thermohabitat.pro.recognition

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import fr.thermohabitat.thermal.vision.NameplateData
import fr.thermohabitat.thermal.vision.NameplateParser
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** Résultat de la lecture optique d'une plaque. */
sealed interface OcrResult {
    data class Success(val text: String, val data: NameplateData) : OcrResult
    data object NoText : OcrResult
    data class Failure(val message: String) : OcrResult
}

/**
 * Reconnaissance optique de caractères EMBARQUÉE (ML Kit, modèle latin inclus dans l'APK) :
 * l'image est traitée sur le téléphone, rien n'est envoyé sur Internet.
 */
@Singleton
class OcrEngine @Inject constructor() {

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    suspend fun read(bitmap: Bitmap): OcrResult = suspendCancellableCoroutine { cont ->
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { result ->
                val text = result.text.trim()
                if (cont.isActive) {
                    cont.resume(if (text.isEmpty()) OcrResult.NoText else OcrResult.Success(text, NameplateParser.parse(text)))
                }
            }
            .addOnFailureListener { e ->
                if (cont.isActive) cont.resume(OcrResult.Failure(e.message ?: "Erreur de reconnaissance"))
            }
    }
}
