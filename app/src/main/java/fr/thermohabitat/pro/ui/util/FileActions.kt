package fr.thermohabitat.pro.ui.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/** Ouverture, partage et copie de fichiers générés (rapports, sauvegardes), toujours à l'initiative de l'utilisateur. */
object FileActions {

    private fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    fun share(context: Context, file: File, mime: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uriFor(context, file))
            putExtra(Intent.EXTRA_SUBJECT, file.nameWithoutExtension)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Partager « ${file.name} »"))
    }

    fun open(context: Context, file: File, mime: String) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uriFor(context, file), mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "Aucune application ne peut ouvrir ce fichier : utilisez « Partager » ou « Enregistrer sous ».", Toast.LENGTH_LONG).show()
        }
    }

    /** Copie le fichier vers l'emplacement choisi par l'utilisateur (sélecteur de documents Android). */
    fun copyTo(context: Context, file: File, target: Uri): Boolean = runCatching {
        context.contentResolver.openOutputStream(target)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: error("Destination invalide")
    }.isSuccess
}
