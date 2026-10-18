package fr.thermohabitat.pro.ui.components

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import fr.thermohabitat.pro.data.db.PhotoEntity
import fr.thermohabitat.pro.data.files.PhotoStorage
import java.io.File

/** Fichier d'une photographie du stockage privé. */
fun photoFile(context: Context, fileName: String): File = File(File(context.filesDir, PhotoStorage.DIR), fileName)

@Composable
fun PhotoThumbnail(fileName: String, modifier: Modifier = Modifier.size(96.dp), contentDescription: String? = null) {
    val context = LocalContext.current
    AsyncImage(
        model = photoFile(context, fileName),
        contentDescription = contentDescription,
        contentScale = ContentScale.Crop,
        modifier = modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

/** Sélecteur de photos de la galerie (sélecteur système : aucune permission de stockage requise). */
@Composable
fun rememberGalleryPicker(onPicked: (List<android.net.Uri>) -> Unit) =
    rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(maxItems = 10)) { uris -> if (uris.isNotEmpty()) onPicked(uris) }

fun galleryRequest() = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)

/** Bandeau de photos avec boutons d'ajout (appareil photo / galerie). */
@Composable
fun PhotoStrip(
    photos: List<PhotoEntity>,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
    onOpen: (PhotoEntity) -> Unit,
    modifier: Modifier = Modifier,
    selectedId: Long? = null,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (photos.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                photos.forEach { p ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.size(96.dp).clip(RoundedCornerShape(10.dp)).clickable { onOpen(p) }
                                .then(if (p.id == selectedId) Modifier.border(3.dp, MaterialTheme.colorScheme.secondary, RoundedCornerShape(10.dp)) else Modifier),
                        ) { PhotoThumbnail(p.fileName, Modifier.fillMaxSize()) }
                        Text(p.kind.label, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        } else {
            Text("Aucune photo pour le moment.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCamera) { Icon(Icons.Filled.CameraAlt, null); Text("  Appareil photo") }
            OutlinedButton(onClick = onGallery) { Icon(Icons.Filled.PhotoLibrary, null); Text("  Galerie") }
        }
        Text("Les photos restent dans le stockage privé de l'application, sur ce téléphone.", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
    }
}

/** Visionneuse plein écran d'une photo avec suppression. */
@Composable
fun PhotoViewerDialog(photo: PhotoEntity, onDismiss: () -> Unit, onDelete: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${photo.kind.label} – ${fr.thermohabitat.pro.ui.util.Fmt.dateTime(photo.createdAt)}") },
        text = {
            val context = LocalContext.current
            AsyncImage(
                model = photoFile(context, photo.fileName),
                contentDescription = photo.caption.ifBlank { photo.kind.label },
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().height(320.dp).clip(RoundedCornerShape(10.dp)),
            )
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Fermer") } },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDelete) { Text("Supprimer", color = MaterialTheme.colorScheme.error) }
        },
    )
}
