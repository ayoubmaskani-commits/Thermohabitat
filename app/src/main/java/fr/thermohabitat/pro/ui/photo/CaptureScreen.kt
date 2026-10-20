package fr.thermohabitat.pro.ui.photo

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.thermohabitat.pro.data.db.PhotoOwner
import fr.thermohabitat.pro.data.files.PhotoStorage
import fr.thermohabitat.pro.data.repository.ProjectRepository
import fr.thermohabitat.pro.data.repository.SettingsRepository
import fr.thermohabitat.pro.ui.components.ChoiceChips
import fr.thermohabitat.pro.ui.components.PhotoThumbnail
import fr.thermohabitat.pro.ui.components.ScreenScaffold
import fr.thermohabitat.pro.ui.components.galleryRequest
import fr.thermohabitat.pro.ui.components.rememberGalleryPicker
import fr.thermohabitat.pro.ui.navigation.CaptureRoute
import fr.thermohabitat.pro.ui.navigation.RadiatorEditRoute
import fr.thermohabitat.thermal.model.PhotoKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class CaptureViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: ProjectRepository,
    private val photoStorage: PhotoStorage,
    private val settings: SettingsRepository,
) : ViewModel() {
    val route = savedStateHandle.toRoute<CaptureRoute>()
    val owner = PhotoOwner.valueOf(route.ownerType)
    val photos = repository.observePhotos(owner, route.ownerId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    val showGuide = MutableStateFlow(false)

    init {
        if (owner == PhotoOwner.RADIATOR) viewModelScope.launch {
            if (!settings.preferences.first().captureGuideSeen) showGuide.value = true
        }
    }

    fun newFile(): File = photoStorage.newPhotoFile(owner.name.lowercase())

    fun onCaptured(file: File, kind: PhotoKind) = viewModelScope.launch {
        busy.value = true
        runCatching {
            photoStorage.normalizeInPlace(file)
            repository.addPhoto(route.buildingId, owner, route.ownerId, file, kind)
        }.onSuccess { message.value = "Photo « ${kind.label} » enregistrée" }
            .onFailure { message.value = "Enregistrement impossible : ${it.message}" }
        busy.value = false
    }

    fun import(uris: List<android.net.Uri>, kind: PhotoKind) = viewModelScope.launch {
        busy.value = true
        var imported = 0
        uris.forEach { uri ->
            runCatching { repository.importPhoto(route.buildingId, owner, route.ownerId, uri, kind) }
                .onSuccess { imported++ }
                .onFailure { message.value = "Import impossible : ${it.message}" }
        }
        // Confirmation seulement si tout a réussi : un message d'échec reste prioritaire.
        if (imported > 0 && imported == uris.size) {
            message.value = if (imported == 1) "Photo « ${kind.label} » importée" else "$imported photos « ${kind.label} » importées"
        }
        busy.value = false
    }

    fun closeGuide() { showGuide.value = false; viewModelScope.launch { settings.setCaptureGuideSeen() } }
}

@Composable
fun CaptureScreen(nav: NavController, vm: CaptureViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val photos by vm.photos.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val showGuide by vm.showGuide.collectAsStateWithLifecycle()
    val isRadiator = vm.owner == PhotoOwner.RADIATOR
    val kinds = if (isRadiator) listOf(PhotoKind.FRONT, PhotoKind.SIDE, PhotoKind.NAMEPLATE, PhotoKind.DETAIL) else listOf(PhotoKind.ROOM, PhotoKind.DETAIL, PhotoKind.OTHER)
    var kind by remember { mutableStateOf(kinds.first()) }
    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var guide by remember { mutableStateOf(false) }
    // Vrai entre l'appui sur le déclencheur et l'enregistrement du fichier par CameraX.
    var shooting by remember { mutableStateOf(false) }
    val working = busy || shooting
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
    val gallery = rememberGalleryPicker { vm.import(it, kind) }
    LaunchedEffect(Unit) { if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA) }
    // Message éphémère (confirmation ou erreur) : il ne reste pas affiché après les prises suivantes.
    LaunchedEffect(message) { if (message != null) { delay(4000); vm.message.value = null } }

    val imageCapture = remember {
        ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
    }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER } }

    DisposableEffect(hasPermission, lifecycleOwner) {
        var provider: ProcessCameraProvider? = null
        if (hasPermission) {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                runCatching {
                    val p = future.get()
                    provider = p
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    p.unbindAll()
                    p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
                }.onFailure { cameraError = "Appareil photo indisponible : ${it.message}. Vous pouvez importer une photo depuis la galerie." }
            }, ContextCompat.getMainExecutor(context))
        }
        onDispose { runCatching { provider?.unbindAll() } }
    }

    fun finish() {
        val fromSheet = nav.previousBackStackEntry?.destination?.hasRoute(RadiatorEditRoute::class) == true
        if (isRadiator && !fromSheet) nav.navigate(RadiatorEditRoute(vm.route.ownerId)) { popUpTo<CaptureRoute> { inclusive = true } }
        else nav.popBackStack()
    }

    ScreenScaffold(
        title = "Prise de vue",
        onBack = { nav.popBackStack() },
        actions = { if (isRadiator) IconButton(onClick = { guide = true }) { Icon(Icons.Filled.Info, "Guide de prise de vue") } },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Box(Modifier.fillMaxWidth().weight(1f).background(Color.Black)) {
                if (hasPermission && cameraError == null) {
                    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                    if (isRadiator) CaptureGuideOverlay(kind)
                } else {
                    Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(cameraError ?: "L'accès à l'appareil photo est nécessaire pour photographier. Les images restent sur le téléphone.",
                            color = Color.White)
                        if (!hasPermission) Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) { Text("Autoriser l'appareil photo") }
                    }
                }
                // Retour visible après une prise de vue ou un import, sur l'aperçu (ne masque pas les boutons).
                message?.let {
                    Surface(Modifier.align(Alignment.BottomCenter).padding(8.dp), color = MaterialTheme.colorScheme.inverseSurface,
                        shape = RoundedCornerShape(8.dp)) {
                        Text(it, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.inverseOnSurface,
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Nombre de vues déjà prises par type : évite d'étiqueter une nouvelle vue « Face » par erreur.
                ChoiceChips(kinds, kind, { k -> photos.count { it.kind == k }.let { n -> if (n > 0) "${k.label} ($n)" else k.label } }, { kind = it })
                // Deux côtés de même largeur : le déclencheur reste centré et « Terminer » n'est jamais écrasé.
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        CaptureSideAction(Icons.Filled.PhotoLibrary, "Galerie") { gallery.launch(galleryRequest()) }
                    }
                    FilledIconButton(
                        onClick = {
                            val file = vm.newFile()
                            shooting = true
                            imageCapture.takePicture(
                                ImageCapture.OutputFileOptions.Builder(file).build(),
                                ContextCompat.getMainExecutor(context),
                                object : ImageCapture.OnImageSavedCallback {
                                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                        shooting = false
                                        vm.onCaptured(file, kind)
                                    }
                                    override fun onError(exception: ImageCaptureException) {
                                        shooting = false
                                        file.delete()
                                        cameraError = null
                                        vm.message.value = "Prise de vue impossible : ${exception.message}"
                                    }
                                },
                            )
                        },
                        enabled = hasPermission && cameraError == null && !working,
                        modifier = Modifier.size(72.dp),
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(),
                    ) {
                        if (working) CircularProgressIndicator(Modifier.size(32.dp), color = LocalContentColor.current, strokeWidth = 3.dp)
                        else Icon(Icons.Filled.CameraAlt, "Prendre la photo", Modifier.size(36.dp))
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        CaptureSideAction(Icons.Filled.Check, "Terminer", primary = true) { finish() }
                    }
                }
                if (photos.isNotEmpty()) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        photos.forEach { p -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            PhotoThumbnail(p.fileName, Modifier.size(64.dp))
                            Text(p.kind.label, Modifier.width(64.dp), style = MaterialTheme.typography.labelSmall, maxLines = 2,
                                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                        } }
                    }
                }
            }
        }
    }

    if (showGuide || guide) CaptureGuideDialog(onDismiss = { guide = false; vm.closeGuide() })
}

/** Action latérale de la prise de vue : grande icône et libellé court dessous (largeur fixe, jamais écrasée). */
@Composable
private fun CaptureSideAction(icon: ImageVector, label: String, primary: Boolean = false, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (primary) FilledTonalIconButton(onClick = onClick, modifier = Modifier.size(56.dp)) { Icon(icon, contentDescription = label) }
        else OutlinedIconButton(onClick = onClick, modifier = Modifier.size(56.dp)) { Icon(icon, contentDescription = label) }
        // Libellé déjà annoncé par l'icône : non relu par TalkBack.
        Text(label, Modifier.clearAndSetSemantics { }, style = MaterialTheme.typography.labelMedium, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
    }
}

/** Cadre de visée et consigne adaptés au type de vue. */
@Composable
private fun CaptureGuideOverlay(kind: PhotoKind) {
    val tip = when (kind) {
        PhotoKind.FRONT -> "Radiateur entier dans le cadre, bien de face"
        PhotoKind.SIDE -> "Vue de côté ou de dessus : montrez les colonnes"
        PhotoKind.NAMEPLATE -> "Plaque ou marquage en gros plan, sans reflet"
        else -> "Détail utile (robinet, raccord, marquage)"
    }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            // Cadre calculé sur la zone réellement occupée par l'aperçu FIT_CENTER (flux CameraX 4:3, 3:4 en portrait),
            // et non sur toute la boîte : il ne déborde plus sur les bandes noires.
            val imgRatio = if (landscape) 4f / 3f else 3f / 4f
            val iw = minOf(size.width, size.height * imgRatio); val ih = iw / imgRatio
            val ox = (size.width - iw) / 2; val oy = (size.height - ih) / 2
            val w = iw * if (kind == PhotoKind.NAMEPLATE) 0.6f else 0.86f
            val h = ih * if (kind == PhotoKind.NAMEPLATE) 0.3f else 0.6f
            drawRect(Color.White, Offset(ox + (iw - w) / 2, oy + (ih - h) / 2), Size(w, h),
                style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx()))))
        }
        // Consigne compacte (2 lignes au plus) : elle ne recouvre plus l'aperçu avec une grande police.
        Text(tip, color = Color.White, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
            maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 12.dp, vertical = 6.dp))
    }
}

@Composable
fun CaptureGuideDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bien photographier un radiateur") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    "1. Vue de face : placez-vous perpendiculairement au radiateur, à hauteur de son centre, radiateur entier dans le cadre.",
                    "2. Pour mesurer sur la photo, posez une carte bancaire ou une feuille A4 contre le radiateur, dans le même plan.",
                    "3. Vue de dessus ou de côté : elle permet de compter les colonnes et d'estimer la profondeur.",
                    "4. Plaque constructeur ou marquage : gros plan net, sans reflet ni flash direct.",
                    "5. Évitez le contre-jour ; un éclairage homogène améliore le comptage automatique des éléments.",
                    "Les photographies restent dans le stockage privé de l'application et ne sont jamais envoyées sur Internet.",
                ).forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("J'ai compris") } },
    )
}
