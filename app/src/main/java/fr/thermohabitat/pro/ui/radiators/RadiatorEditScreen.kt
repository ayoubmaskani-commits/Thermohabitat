package fr.thermohabitat.pro.ui.radiators

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.thermohabitat.pro.data.db.PhotoEntity
import fr.thermohabitat.pro.data.db.PhotoOwner
import fr.thermohabitat.pro.data.db.RadiatorEntity
import fr.thermohabitat.pro.data.db.toModel
import fr.thermohabitat.pro.data.files.PhotoStorage
import fr.thermohabitat.pro.data.repository.ProjectRepository
import fr.thermohabitat.pro.di.ApplicationScope
import fr.thermohabitat.pro.domain.StudyService
import fr.thermohabitat.pro.recognition.ClassificationResult
import fr.thermohabitat.pro.recognition.OcrEngine
import fr.thermohabitat.pro.recognition.OcrResult
import fr.thermohabitat.pro.recognition.RadiatorClassifier
import fr.thermohabitat.pro.ui.components.ActionRow
import fr.thermohabitat.pro.ui.components.BannerKind
import fr.thermohabitat.pro.ui.components.ChoiceChips
import fr.thermohabitat.pro.ui.components.ConfirmDialog
import fr.thermohabitat.pro.ui.components.DropdownField
import fr.thermohabitat.pro.ui.components.FieldRow
import fr.thermohabitat.pro.ui.components.InfoBanner
import fr.thermohabitat.pro.ui.components.IntField
import fr.thermohabitat.pro.ui.components.LargeActionButton
import fr.thermohabitat.pro.ui.components.NumberField
import fr.thermohabitat.pro.ui.components.OriginSelector
import fr.thermohabitat.pro.ui.components.PhotoStrip
import fr.thermohabitat.pro.ui.components.PhotoThumbnail
import fr.thermohabitat.pro.ui.components.RadiatorIllustration
import fr.thermohabitat.pro.ui.components.ScreenScaffold
import fr.thermohabitat.pro.ui.components.ScrollColumn
import fr.thermohabitat.pro.ui.components.SectionCard
import fr.thermohabitat.pro.ui.components.StatusChip
import fr.thermohabitat.pro.ui.components.SwitchRow
import fr.thermohabitat.pro.ui.components.TextInput
import fr.thermohabitat.pro.ui.components.ValueRow
import fr.thermohabitat.pro.ui.components.galleryRequest
import fr.thermohabitat.pro.ui.components.rememberGalleryPicker
import fr.thermohabitat.pro.ui.navigation.CaptureRoute
import fr.thermohabitat.pro.ui.navigation.MeasureRoute
import fr.thermohabitat.pro.ui.navigation.PhotoEditRoute
import fr.thermohabitat.pro.ui.navigation.PhotoIdentifyRoute
import fr.thermohabitat.pro.ui.navigation.RadiatorEditRoute
import fr.thermohabitat.pro.ui.navigation.RadiatorLibraryRoute
import fr.thermohabitat.pro.ui.util.AutoSaver
import fr.thermohabitat.pro.ui.util.Fmt
import fr.thermohabitat.thermal.calc.CoverageStatus
import fr.thermohabitat.thermal.calc.PowerBasis
import fr.thermohabitat.thermal.calc.CoverageAnalyzer
import fr.thermohabitat.thermal.calc.RadiatorPower
import fr.thermohabitat.thermal.calc.Regime
import fr.thermohabitat.thermal.library.RadiatorLibrary
import fr.thermohabitat.thermal.model.IdentificationMethod
import fr.thermohabitat.thermal.model.PanelType
import fr.thermohabitat.thermal.model.PhotoKind
import fr.thermohabitat.thermal.model.RadiatorEra
import fr.thermohabitat.thermal.model.RadiatorMaterial
import fr.thermohabitat.thermal.model.RadiatorType
import fr.thermohabitat.thermal.model.RatingCondition
import fr.thermohabitat.thermal.model.RoomUsage
import fr.thermohabitat.thermal.model.ValueOrigin
import fr.thermohabitat.thermal.vision.ExtractedField
import fr.thermohabitat.thermal.vision.NameplateData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** États de la lecture de plaque (OCR). */
sealed interface OcrUiState {
    data object Idle : OcrUiState
    data object Running : OcrUiState
    data class Done(val result: OcrResult) : OcrUiState
}

sealed interface AiUiState {
    data object Idle : AiUiState
    data object Running : AiUiState
    data class Done(val result: ClassificationResult) : AiUiState
}

/** Texte source d'un régime présumé (puissance en kcal/h lue sans conditions). */
private const val PRESUMED_RATING_SOURCE = "présumé : kcal/h sans régime"

/** Données minimales exigées avant validation. */
fun validationIssues(r: RadiatorEntity): List<String> = buildList {
    if (r.type == RadiatorType.UNKNOWN) add("Le type de radiateur n'est pas identifié.")
    if (r.nominalPowerW != null && !RatingCondition(r.ratingSupplyC, r.ratingReturnC, r.ratingAmbientC).isValid) {
        add("Conditions de la puissance documentée incohérentes (le départ doit être ≥ au retour, lui-même > à l'ambiance).")
    }
    val model = r.toModel()
    if (RadiatorPower.resolveNominal(model) == null) {
        add(when {
            r.type == RadiatorType.DECORATIVE || r.type == RadiatorType.CONVECTOR || r.type == RadiatorType.UNKNOWN ->
                "Ce type ne permet pas d'estimation : saisissez la puissance nominale documentée."
            r.type == RadiatorType.STEEL_PANEL && r.panelType == null ->
                "Précisez le type de panneau (10, 11, 20, 21, 22 ou 33, lisible vu de dessus) ou saisissez la puissance nominale documentée."
            else -> "Dimensions insuffisantes pour estimer la puissance (hauteur, profondeur ou longueur, nombre d'éléments) ou puissance nominale manquante."
        })
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class RadiatorEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: ProjectRepository,
    private val photoStorage: PhotoStorage,
    private val ocr: OcrEngine,
    private val classifier: RadiatorClassifier,
    studyService: StudyService,
    @ApplicationScope appScope: CoroutineScope,
) : ViewModel() {
    val radiatorId = savedStateHandle.toRoute<RadiatorEditRoute>().radiatorId
    private val _form = MutableStateFlow<RadiatorEntity?>(null)
    val form = _form.asStateFlow()
    private val saver = AutoSaver<RadiatorEntity>(viewModelScope, appScope) { repository.updateRadiator(it) }

    val photos = repository.observePhotos(PhotoOwner.RADIATOR, radiatorId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val project = repository.observeRadiator(radiatorId).filterNotNull().map { it.buildingId }.distinctUntilChanged()
        .flatMapLatest { studyService.observe(it) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val ocrState = MutableStateFlow<OcrUiState>(OcrUiState.Idle)
    val aiState = MutableStateFlow<AiUiState>(AiUiState.Idle)
    val classifierAvailable: Boolean get() = classifier.isAvailable
    val ambient = MutableStateFlow<Double?>(null)

    init {
        // Les outils photo (comptage, mesure) modifient la fiche en base : on recharge le formulaire à chaque retour.
        viewModelScope.launch {
            repository.observeRadiator(radiatorId).collect { db ->
                val current = _form.value
                if (current == null || (db != null && db.updatedAt > current.updatedAt)) _form.value = db
            }
        }
    }

    fun update(transform: (RadiatorEntity) -> RadiatorEntity) {
        var next = transform(_form.value ?: return).copy(updatedAt = System.currentTimeMillis())
        // Une fiche qui ne satisfait plus les conditions de validation est retirée du bilan.
        if (next.validated && validationIssues(next).isNotEmpty()) next = next.copy(validated = false, validatedAt = null)
        _form.value = next
        saver.submit(next)
    }

    fun setType(t: RadiatorType) = update {
        // Le type de panneau (10 à 33) n'est jamais présumé : il est choisi par l'utilisateur.
        it.copy(type = t, material = if (it.material == RadiatorMaterial.UNKNOWN) t.defaultMaterial else it.material)
    }

    fun setValidated(on: Boolean) = update { it.copy(validated = on, validatedAt = if (on) System.currentTimeMillis() else null) }

    fun readPlate(photo: PhotoEntity) = viewModelScope.launch {
        ocrState.value = OcrUiState.Running
        val bmp = photoStorage.loadBitmap(photo.fileName, 2048)
        ocrState.value = OcrUiState.Done(if (bmp == null) OcrResult.Failure("Photo illisible") else ocr.read(bmp))
    }

    fun dismissOcr() { ocrState.value = OcrUiState.Idle }

    /** Applique les champs OCR choisis : la fiche repasse « à valider ». */
    fun applyOcr(d: NameplateData, fields: Set<String>) {
        update { r ->
            var x = r.copy(identificationMethod = IdentificationMethod.OCR, validated = false)
            if ("manufacturer" in fields) d.manufacturer?.let { x = x.copy(manufacturer = it.value) }
            if ("model" in fields) d.model?.let { x = x.copy(model = it.value) }
            if ("reference" in fields) d.reference?.let { x = x.copy(reference = it.value) }
            if ("power" in fields) d.powerW?.let { x = x.copy(nominalPowerW = Math.round(it.value).toDouble(), nominalPowerOrigin = ValueOrigin.DECLARED) }
            if ("rating" in fields) d.rating?.let {
                x = x.copy(ratingSupplyC = it.value.supplyC, ratingReturnC = it.value.returnC, ratingAmbientC = it.value.ambientC)
                // Régime présumé (kcal/h sans mention) : incertitude élargie, visible dans la borne basse de la couverture.
                if (it.sourceText == PRESUMED_RATING_SOURCE) x = x.copy(documentedUncertaintyPct = maxOf(x.documentedUncertaintyPct, 15.0))
            }
            if ("panel" in fields) d.panelType?.let { x = x.copy(panelType = it.value, type = RadiatorType.STEEL_PANEL) }
            if ("height" in fields) d.heightMm?.let { x = x.copy(heightMm = it.value, dimensionsOrigin = ValueOrigin.DECLARED) }
            if ("length" in fields) d.lengthMm?.let { x = x.copy(lengthMm = it.value, dimensionsOrigin = ValueOrigin.DECLARED) }
            x
        }
        ocrState.value = OcrUiState.Idle
    }

    fun classify(photo: PhotoEntity) = viewModelScope.launch {
        aiState.value = AiUiState.Running
        val bmp = photoStorage.loadBitmap(photo.fileName, 1024)
        aiState.value = AiUiState.Done(if (bmp == null) ClassificationResult.Failure("Photo illisible") else classifier.classify(bmp))
    }

    fun applyAi(categoryCode: String, confidence: Float, model: String) {
        val cat = RadiatorLibrary.find(categoryCode) ?: return
        update {
            it.copy(type = cat.type, material = cat.type.defaultMaterial, era = cat.era, columns = cat.columns ?: it.columns,
                categoryCode = cat.code, identificationMethod = IdentificationMethod.AI_MODEL,
                identificationConfidence = confidence.toDouble(), aiSuggestion = "$model : ${cat.title}", validated = false)
        }
        aiState.value = AiUiState.Idle
    }

    fun dismissAi() { aiState.value = AiUiState.Idle }

    fun importPhotos(uris: List<android.net.Uri>) = viewModelScope.launch {
        val b = _form.value?.buildingId ?: return@launch
        uris.forEach { runCatching { repository.importPhoto(b, PhotoOwner.RADIATOR, radiatorId, it, PhotoKind.FRONT) } }
    }

    fun setPhotoKind(p: PhotoEntity, kind: PhotoKind) = viewModelScope.launch { repository.updatePhoto(p.copy(kind = kind)) }
    fun deletePhoto(id: Long) = viewModelScope.launch { repository.deletePhoto(id) }

    suspend fun createRoom(name: String, floor: Int): Long? {
        val b = _form.value?.buildingId ?: return null
        val id = repository.addRoom(b, name, floor, usage = RoomUsage.entries.firstOrNull { it.label.equals(name, true) } ?: RoomUsage.OTHER)
        update { it.copy(roomId = id) }
        return id
    }

    fun delete(onDone: () -> Unit) = viewModelScope.launch { repository.deleteRadiator(radiatorId); onDone() }

    override fun onCleared() { saver.flush() }
}

@Composable
fun RadiatorEditScreen(nav: NavController, vm: RadiatorEditViewModel = hiltViewModel()) {
    val form by vm.form.collectAsStateWithLifecycle()
    val photos by vm.photos.collectAsStateWithLifecycle()
    val project by vm.project.collectAsStateWithLifecycle()
    val ocrState by vm.ocrState.collectAsStateWithLifecycle()
    val aiState by vm.aiState.collectAsStateWithLifecycle()
    val ambientOverride by vm.ambient.collectAsStateWithLifecycle()
    val gallery = rememberGalleryPicker { vm.importPhotos(it) }
    var selectedPhoto by remember { mutableStateOf<PhotoEntity?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var newRoom by remember { mutableStateOf(false) }
    var showIssues by remember { mutableStateOf<List<String>>(emptyList()) }
    var deletePhotoId by remember { mutableStateOf<Long?>(null) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    // Retour visible quand la fiche repasse « à valider » (modification, plaque, bibliothèque) : elle sort du bilan.
    // Sauvegardé pour couvrir aussi le retour depuis la bibliothèque ou les outils photo.
    var wasValidated by rememberSaveable { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(form?.validated) {
        val v = form?.validated ?: return@LaunchedEffect
        val before = wasValidated
        wasValidated = v
        if (before == true && !v) snackbar.showSnackbar("Fiche repassée « à valider » : ce radiateur est exclu du bilan jusqu'à sa validation.")
    }
    // Validation : contrôle des données minimales, puis confirmation de la prise en compte.
    val validate: () -> Unit = {
        val cur = form
        if (cur != null) {
            val issues = validationIssues(cur)
            if (issues.isEmpty()) {
                vm.setValidated(true)
                scope.launch {
                    snackbar.showSnackbar(
                        if (cur.roomId == null) "Fiche validée : attribuez-la à une pièce pour qu'elle compte dans le bilan."
                        else "Radiateur validé : pris en compte dans l'étude.",
                    )
                }
            } else {
                showIssues = issues
            }
        }
    }

    ScreenScaffold(
        title = form?.let { "Radiateur ${it.code}" } ?: "Radiateur",
        onBack = { nav.popBackStack() },
        actions = { IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Supprimer") } },
        snackbarHostState = snackbar,
    ) { padding ->
        val r = form ?: return@ScreenScaffold
        val model = r.toModel()
        val nominal = RadiatorPower.resolveNominal(model)
        val rooms = project?.project?.rooms?.map { it.room }.orEmpty()
        val room = rooms.firstOrNull { it.id == r.roomId }
        val mainPhoto = photos.firstOrNull { it.kind == PhotoKind.FRONT } ?: photos.firstOrNull()
        val settings = project?.project?.settings
        val study = settings?.studyRegime
        val ambient = ambientOverride ?: room?.setpointC ?: 20.0
        ScrollColumn(padding) {
            // --- Fiche de synthèse « Radiateur identifié » ---
            SectionCard("Radiateur identifié", icon = Icons.Filled.Thermostat) {
                mainPhoto?.let { PhotoThumbnail(it.fileName, Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(12.dp))) }
                // Statut et validation en premier : seul un radiateur validé et attribué à une pièce entre dans le bilan.
                // La clé étrangère garantit que roomId désigne une pièce existante (ou null).
                val inStudy = r.validated && r.roomId != null
                StatusChip(if (inStudy) CoverageStatus.SUFFICIENT else CoverageStatus.LIMIT,
                    text = when {
                        !r.validated -> "À valider : non pris en compte"
                        r.roomId == null -> "Validé, mais non attribué à une pièce : exclu du bilan"
                        else -> "Validé : pris en compte dans l'étude"
                    })
                if (!r.validated) {
                    Button(onClick = validate) { Icon(Icons.Filled.CheckCircle, null); Spacer(Modifier.width(8.dp)); Text("Valider la fiche") }
                }
                // Puissance utile au régime de l'étude, ou ce qu'il manque pour l'établir.
                if (nominal == null) {
                    InfoBanner("Puissance inconnue : identifiez le type et relevez les dimensions, ou saisissez la puissance documentée, " +
                        "dans les sections ci-dessous.", kind = BannerKind.WARNING)
                } else if (study != null && settings != null) {
                    // Même calcul que le bilan : mode hydraulique de l'étude et consigne de la pièce (pas l'ambiance d'essai).
                    val out = CoverageAnalyzer.outputFor(model, study.supplyC, settings.modeFor(study), room?.setpointC ?: settings.radiatorSheetAmbientC)
                    ValueRow("Puissance à ${study.label} (régime de l'étude)", if (out.powerW > 0) Fmt.watts(out.powerW) else "Non calculable", emphasize = true)
                }
                ValueRow("Type", r.type.label)
                // Dimensions, éléments, colonnes ou type de panneau en une ligne (« Dimensions à relever » si rien n'est saisi).
                ValueRow("Dimensions", dimensionsText(model))
                if (r.material != RadiatorMaterial.UNKNOWN) ValueRow("Matériau", r.material.label)
                if (r.manufacturer.isNotBlank() || r.reference.isNotBlank() || r.model.isNotBlank()) {
                    ValueRow("Marque / modèle / référence", listOf(r.manufacturer, r.model, r.reference).filter { it.isNotBlank() }.joinToString(" · "))
                }
                ValueRow("Puissance nominale documentée", r.nominalPowerW?.takeIf { r.nominalPowerOrigin != ValueOrigin.UNKNOWN }
                    ?.let { "${Fmt.watts(it)} à ${model.rating.label}" } ?: "Non documentée")
                if (nominal?.basis == PowerBasis.ESTIMATED && r.nominalPowerW == null) {
                    ValueRow("Puissance estimée (ΔT 50 K)", "${Fmt.watts(nominal.nominalW)} (${Fmt.watts(nominal.lowW)} – ${Fmt.watts(nominal.highW)})")
                }
                ValueRow("Fiabilité", reliabilityLabel(r, nominal?.basis, nominal?.relativeUncertainty), emphasize = true)
                ValueRow("Méthode d'identification", r.identificationMethod.label +
                    (r.identificationConfidence?.let { " (confiance ${Fmt.pct(it)})" } ?: ""))
            }

            // --- Photographies et outils ---
            SectionCard("Photographies", subtitle = "Face, côté, plaque constructeur. Touchez une photo pour les outils.") {
                PhotoStrip(
                    photos, selectedId = selectedPhoto?.id,
                    onCamera = { nav.navigate(CaptureRoute(PhotoOwner.RADIATOR.name, r.id, r.buildingId)) },
                    onGallery = { gallery.launch(galleryRequest()) },
                    onOpen = { selectedPhoto = if (selectedPhoto?.id == it.id) null else it },
                )
                selectedPhoto?.let { ph ->
                    val current = photos.firstOrNull { it.id == ph.id } ?: ph
                    Text("Type de vue", style = MaterialTheme.typography.labelLarge)
                    ChoiceChips(listOf(PhotoKind.FRONT, PhotoKind.SIDE, PhotoKind.NAMEPLATE, PhotoKind.DETAIL), current.kind, { it.label }, { vm.setPhotoKind(current, it) })
                    // Tous les outils restent visibles : ils passent à la ligne au lieu de défiler hors écran.
                    ActionRow {
                        // Identification (éléments, échelle, type) : remplace l'ancien comptage sur zone rectangulaire de face.
                        Button(onClick = { nav.navigate(PhotoIdentifyRoute(current.id, r.id)) }) {
                            Icon(Icons.Filled.ViewColumn, null); Spacer(Modifier.width(6.dp)); Text("Identifier sur la photo")
                        }
                        OutlinedButton(onClick = { nav.navigate(PhotoEditRoute(current.id)) }) { Icon(Icons.Filled.Crop, null); Spacer(Modifier.width(6.dp)); Text("Recadrer") }
                        OutlinedButton(onClick = { nav.navigate(MeasureRoute(current.id, r.id)) }) { Icon(Icons.Filled.Straighten, null); Spacer(Modifier.width(6.dp)); Text("Mesurer") }
                        OutlinedButton(onClick = { vm.readPlate(current) }) { Icon(Icons.Filled.DocumentScanner, null); Spacer(Modifier.width(6.dp)); Text("Lire la plaque") }
                        TextButton(onClick = { deletePhotoId = current.id }) { Text("Supprimer la photo", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }

            // --- Identification ---
            SectionCard("Identification", icon = Icons.Filled.AutoAwesome) {
                // Outil principal : photo de face sélectionnée, sinon la première photo de face (ou la première photo).
                val identifyPhoto = selectedPhoto?.let { s -> photos.firstOrNull { it.id == s.id } }?.takeIf { it.kind == PhotoKind.FRONT } ?: mainPhoto
                if (identifyPhoto != null) {
                    LargeActionButton("Identifier sur la photo", Icons.Filled.ViewColumn,
                        onClick = { nav.navigate(PhotoIdentifyRoute(identifyPhoto.id, r.id)) })
                    Text("Nombre d'éléments, dimensions avec une échelle (carte bancaire, feuille A4, dimension mesurée) et types compatibles " +
                        "de la bibliothèque. La photo peut être prise un peu de biais ; chaque proposition est à vérifier.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    InfoBanner("Ajoutez une photo de face (un peu de biais convient) dans la section Photographies pour identifier le " +
                        "radiateur : nombre d'éléments, dimensions et types compatibles.")
                }
                if (vm.classifierAvailable) {
                    InfoBanner("Un modèle d'IA embarqué est installé. Ses propositions s'accompagnent d'un indice de confiance et doivent être confirmées.")
                    val target = selectedPhoto ?: mainPhoto
                    OutlinedButton(enabled = target != null && aiState !is AiUiState.Running, onClick = { target?.let { vm.classify(it) } }) {
                        Text("Proposer un type à partir de la photo")
                    }
                } else {
                    InfoBanner("Aucun modèle d'IA entraîné et validé n'est installé dans cette version : le type n'est pas reconnu automatiquement. " +
                        "Identifiez le radiateur sur la photo (analyse géométrique) ou avec la bibliothèque illustrée ; la lecture de plaque reste disponible.",
                        kind = BannerKind.WARNING)
                }
                Button(onClick = { nav.navigate(RadiatorLibraryRoute(r.id)) }) {
                    Icon(Icons.AutoMirrored.Filled.MenuBook, null); Spacer(Modifier.width(8.dp)); Text("Bibliothèque illustrée")
                }
                RadiatorLibrary.find(r.categoryCode)?.let { cat ->
                    // Texte sur toute la largeur, illustration dessous (plus de colonne étroite à côté du dessin).
                    Text("Catégorie retenue : ${cat.title}", style = MaterialTheme.typography.bodyMedium)
                    RadiatorIllustration(cat)
                }
                TextInput("Libellé", r.label, { v -> vm.update { it.copy(label = v) } })
                DropdownField("Type", RadiatorType.entries, r.type, { it.label }, { vm.setType(it) })
                DropdownField("Matériau", RadiatorMaterial.entries, r.material, { it.label }, { v -> vm.update { it.copy(material = v) } })
                DropdownField("Époque", RadiatorEra.entries, r.era, { it.label }, { v -> vm.update { it.copy(era = v) } })
                if (r.type.isSectional || r.type == RadiatorType.CAST_IRON_PLATE) {
                    FieldRow {
                        IntField("Éléments", r.sections, { v -> vm.update { it.copy(sections = v) } }, min = 1, max = 200)
                        IntField(if (r.type == RadiatorType.CAST_IRON_PLATE) "Plaques" else "Colonnes", r.columns, { v -> vm.update { it.copy(columns = v) } },
                            min = 1, max = 10)
                    }
                }
                if (r.type == RadiatorType.STEEL_PANEL) {
                    DropdownField("Type de panneau", PanelType.entries, r.panelType, { it.label }, { v -> vm.update { it.copy(panelType = v) } })
                }
                TextInput("Fabricant", r.manufacturer, { v -> vm.update { it.copy(manufacturer = v) } })
                FieldRow {
                    TextInput("Modèle", r.model, { v -> vm.update { it.copy(model = v) } })
                    TextInput("Référence", r.reference, { v -> vm.update { it.copy(reference = v) } })
                }
            }

            // --- Dimensions ---
            SectionCard("Dimensions", icon = Icons.Filled.Straighten,
                // Sans échelle, la photo ne donne au mieux qu'une estimation (pas standard supposé) : dit explicitement.
                subtitle = "Mesurez au mètre, ou utilisez « Identifier sur la photo » avec une échelle (carte bancaire, feuille A4, " +
                    "dimension mesurée). Sans échelle, une photo ne donne au mieux qu'une estimation.") {
                // Côte à côte seulement s'il y a la place ; sinon empilés (petit écran, police agrandie).
                FieldRow {
                    NumberField("Hauteur", r.heightMm, { v -> vm.update { it.copy(heightMm = v) } }, unit = "mm", decimals = 0, min = 50.0, max = 3000.0)
                    NumberField("Longueur", r.lengthMm, { v -> vm.update { it.copy(lengthMm = v) } }, unit = "mm", decimals = 0, min = 50.0, max = 6000.0)
                    NumberField("Profondeur", r.depthMm, { v -> vm.update { it.copy(depthMm = v) } }, unit = "mm", decimals = 0, min = 10.0, max = 500.0)
                }
                Text("Origine des dimensions", style = MaterialTheme.typography.labelLarge)
                OriginSelector(r.dimensionsOrigin, { o -> vm.update { it.copy(dimensionsOrigin = o) } })
            }

            // --- Puissance ---
            SectionCard("Puissance") {
                NumberField("Puissance nominale", r.nominalPowerW, { v -> vm.update { it.copy(nominalPowerW = v,
                    nominalPowerOrigin = if (v == null) ValueOrigin.UNKNOWN else if (it.nominalPowerOrigin == ValueOrigin.UNKNOWN) ValueOrigin.DECLARED else it.nominalPowerOrigin) } },
                    unit = "W", decimals = 0, min = 10.0, max = 20000.0, supportingText = "Documentée (fiche fabricant, plaque, catalogue). Vide : estimation géométrique.")
                if (r.nominalPowerW != null) {
                    Text("Conditions de la puissance documentée", style = MaterialTheme.typography.labelLarge)
                    val ratings = listOf(RatingCondition.EN442, RatingCondition.DIN4703)
                    ChoiceChips(ratings + listOf(RatingCondition(r.ratingSupplyC, r.ratingReturnC, r.ratingAmbientC)).filter { it !in ratings }, RatingCondition(r.ratingSupplyC, r.ratingReturnC, r.ratingAmbientC),
                        { if (it == RatingCondition.EN442) "EN 442 ${it.label}" else if (it == RatingCondition.DIN4703) "Ancienne norme ${it.label}" else it.label },
                        { c -> vm.update { it.copy(ratingSupplyC = c.supplyC, ratingReturnC = c.returnC, ratingAmbientC = c.ambientC) } })
                    FieldRow {
                        NumberField("Départ", r.ratingSupplyC, { v -> v?.let { vm.update { x -> x.copy(ratingSupplyC = it) } } }, unit = "°C", decimals = 0, nullable = false)
                        NumberField("Retour", r.ratingReturnC, { v -> v?.let { vm.update { x -> x.copy(ratingReturnC = it) } } }, unit = "°C", decimals = 0, nullable = false)
                        NumberField("Ambiance", r.ratingAmbientC, { v -> v?.let { vm.update { x -> x.copy(ratingAmbientC = it) } } }, unit = "°C", decimals = 0, nullable = false)
                    }
                    Text("Origine de la puissance", style = MaterialTheme.typography.labelLarge)
                    OriginSelector(r.nominalPowerOrigin, { o -> vm.update { it.copy(nominalPowerOrigin = o) } })
                }
                // Réglages experts repliés ; le bouton signale s'ils s'écartent des valeurs par défaut.
                val customized = r.exponent != null || r.flowKgH != null || (r.nominalPowerW != null && r.documentedUncertaintyPct != 5.0)
                val advancedLabel = when {
                    advanced -> "Masquer les réglages avancés"
                    r.nominalPowerW != null -> "Réglages avancés : exposant n, débit, incertitude"
                    else -> "Réglages avancés : exposant n, débit"
                }
                TextButton(onClick = { advanced = !advanced }) { Text(if (!advanced && customized) "$advancedLabel (modifiés)" else advancedLabel) }
                if (advanced) {
                    if (r.nominalPowerW != null) {
                        NumberField("Incertitude admise", r.documentedUncertaintyPct, { v -> vm.update { it.copy(documentedUncertaintyPct = v ?: 5.0) } }, unit = "%", decimals = 0,
                            nullable = false, min = 0.0, max = 50.0, supportingText = "Sur la puissance documentée")
                    }
                    NumberField("Exposant n", r.exponent, { v -> vm.update { it.copy(exponent = v, exponentOrigin = if (v == null) ValueOrigin.ESTIMATED else ValueOrigin.DECLARED) } },
                        decimals = 2, min = 1.0, max = 2.0,
                        supportingText = "Sensibilité de la puissance à la température d'eau. Vide : valeur usuelle ${Fmt.num(r.type.defaultExponent, 2)} pour ce type")
                    NumberField("Débit d'eau", r.flowKgH, { v -> vm.update { it.copy(flowKgH = v) } }, unit = "kg/h", decimals = 0, min = 1.0,
                        supportingText = "Facultatif : débit connu après équilibrage")
                }
                HorizontalDivider()
                when {
                    nominal == null -> InfoBanner("Puissance non déterminable : complétez les dimensions et le nombre d'éléments, ou saisissez la puissance documentée. " +
                        "Aucune puissance n'est inventée à partir de la seule photographie.", kind = BannerKind.WARNING)
                    else -> {
                        Text(nominal.method, style = MaterialTheme.typography.bodySmall)
                        NumberField("Température ambiante", ambientOverride, { vm.ambient.value = it }, unit = "°C", decimals = 1, min = 5.0, max = 30.0,
                            supportingText = "Vide : consigne de la pièce (${Fmt.temp(room?.setpointC ?: 20.0, 0)})")
                        Text("Puissance disponible selon le régime d'eau (ambiance ${Fmt.temp(ambient)})", style = MaterialTheme.typography.labelLarge)
                        // Une valeur par ligne, régime de l'étude mis en avant ; « – » si le régime ne chauffe pas à cette ambiance.
                        Regime.STANDARD.forEach { reg ->
                            val out = RadiatorPower.outputOf(model, reg.supplyC, reg.returnC, ambient)
                            // « (étude) » seulement si ce tableau calcule comme le bilan (écart constant, consigne de la pièce).
                            val isStudy = reg == study && settings?.fixedFlow != true && ambientOverride == null
                            ValueRow(if (isStudy) "${reg.label} (étude)" else reg.label, if (out.powerW > 0) Fmt.watts(out.powerW) else "–", emphasize = isStudy)
                        }
                        // Même facteur de correction pour la valeur et ses bornes : la plage relative, identique à tous les régimes, est donnée une fois.
                        if (nominal.nominalW > 0 && nominal.highW > nominal.nominalW) {
                            Text("Incertitude sur chaque valeur : −${Fmt.num((1 - nominal.lowW / nominal.nominalW) * 100, 0)} % / " +
                                "+${Fmt.num((nominal.highW / nominal.nominalW - 1) * 100, 0)} %",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("Correction EN 442 : Φ = Φn × (ΔT / ΔTn)^n avec ΔT écart logarithmique moyen, n = ${Fmt.num(model.effectiveExponent, 2)}.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // --- Pièce ---
            SectionCard("Dans quelle pièce se trouve ce radiateur ?") {
                if (project != null && rooms.isEmpty()) {
                    InfoBanner("Aucune pièce dans l'étude : créez-en une pour que ce radiateur compte dans le bilan.", kind = BannerKind.WARNING)
                } else {
                    DropdownField("Pièce", rooms, room, { "${it.name} (${fr.thermohabitat.pro.ui.house.floorLabel(it.floorLevel)})" }, { v -> vm.update { it.copy(roomId = v.id) } },
                        placeholder = "Non attribué")
                }
                OutlinedButton(onClick = { newRoom = true }) { Text("Créer une nouvelle pièce") }
                if (room != null) TextButton(onClick = { vm.update { it.copy(roomId = null) } }) { Text("Retirer de la pièce") }
            }

            // --- Validation obligatoire ---
            SectionCard("Validation", icon = Icons.Filled.CheckCircle) {
                Text("Les caractéristiques proposées (bibliothèque, analyse de la photo, lecture de plaque, IA) doivent être vérifiées. " +
                    "Seuls les radiateurs validés entrent dans le bilan de chauffage.", style = MaterialTheme.typography.bodyMedium)
                SwitchRow("Je confirme les caractéristiques de ce radiateur", r.validated, { on -> if (on) validate() else vm.setValidated(false) })
            }

            TextInput("Notes", r.notes, { v -> vm.update { it.copy(notes = v) } }, singleLine = false)
        }
    }

    // --- Dialogues ---
    when (val s = ocrState) {
        OcrUiState.Running -> AlertDialog(onDismissRequest = {}, confirmButton = {}, title = { Text("Lecture de la plaque…") },
            text = { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(); Spacer(Modifier.width(12.dp)); Text("Reconnaissance de texte sur le téléphone") } })
        is OcrUiState.Done -> OcrResultDialog(s.result, onDismiss = { vm.dismissOcr() }, onApply = { d, f -> vm.applyOcr(d, f) })
        OcrUiState.Idle -> Unit
    }
    when (val s = aiState) {
        AiUiState.Running -> AlertDialog(onDismissRequest = {}, confirmButton = {}, title = { Text("Analyse de la photo…") }, text = { CircularProgressIndicator() })
        is AiUiState.Done -> AiResultDialog(s.result, onDismiss = { vm.dismissAi() }, onApply = { c, conf, m -> vm.applyAi(c, conf, m) })
        AiUiState.Idle -> Unit
    }
    if (showIssues.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showIssues = emptyList() },
            title = { Text("Validation impossible") },
            text = { Column { showIssues.forEach { Text("• $it") } } },
            confirmButton = { TextButton(onClick = { showIssues = emptyList() }) { Text("Compris") } },
        )
    }
    if (newRoom) NewRoomDialog(onDismiss = { newRoom = false }) { name, floor ->
        newRoom = false
        scope.launch { if (vm.createRoom(name, floor) != null) snackbar.showSnackbar("Pièce « $name » créée et attribuée à ce radiateur.") }
    }
    deletePhotoId?.let { id ->
        ConfirmDialog("Supprimer cette photo ?", "Elle sera effacée du téléphone. La fiche du radiateur est conservée.", "Supprimer",
            onConfirm = { vm.deletePhoto(id); selectedPhoto = null },
            onDismiss = { deletePhotoId = null }, destructive = true)
    }
    if (confirmDelete) {
        ConfirmDialog("Supprimer ce radiateur ?", "La fiche et ses photos seront effacées du téléphone.", "Supprimer",
            onConfirm = { vm.delete { nav.popBackStack() } }, onDismiss = { confirmDelete = false }, destructive = true)
    }
}

private fun reliabilityLabel(r: RadiatorEntity, basis: PowerBasis?, uncertainty: Double?): String = when (basis) {
    PowerBasis.DOCUMENTED -> "Puissance documentée (${r.nominalPowerOrigin.label.lowercase()}, ±${Fmt.num(r.documentedUncertaintyPct, 0)} %)"
    PowerBasis.ESTIMATED -> "Estimation géométrique ±${Fmt.num((uncertainty ?: 0.0) * 100, 0)} %"
    else -> "Inconnue"
}

@Composable
private fun OcrResultDialog(result: OcrResult, onDismiss: () -> Unit, onApply: (NameplateData, Set<String>) -> Unit) {
    when (result) {
        is OcrResult.Failure -> AlertDialog(onDismissRequest = onDismiss, title = { Text("Lecture impossible") }, text = { Text(result.message) },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } })
        OcrResult.NoText -> AlertDialog(onDismissRequest = onDismiss, title = { Text("Aucun texte détecté") },
            text = { Text("Rapprochez-vous de la plaque, évitez les reflets et photographiez-la bien de face.") },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } })
        is OcrResult.Success -> {
            val raw = result.data
            // Puissance en kcal/h sans régime lu : documentation antérieure à l'EN 442, en général donnée à ΔT 60 K (90/70/20).
            val presumedOld = raw.rating == null && raw.powerW?.sourceText?.contains("kcal", ignoreCase = true) == true
            val d = if (presumedOld) raw.copy(rating = ExtractedField(RatingCondition.DIN4703, PRESUMED_RATING_SOURCE, 0.3)) else raw
            val candidates = listOfNotNull(
                d.manufacturer?.let { Triple("manufacturer", "Fabricant", "${it.value} (confiance ${Fmt.pct(it.confidence)})") },
                d.model?.let { Triple("model", "Modèle", it.value) },
                d.reference?.let { Triple("reference", "Référence", it.value) },
                d.powerW?.let { Triple("power", "Puissance", "${Fmt.watts(it.value)} ← « ${it.sourceText} »") },
                d.rating?.let {
                    if (presumedOld) Triple("rating", "Conditions présumées", "${it.value.label} (kcal/h : ancienne norme ΔT 60 K, régime non lu, à vérifier)")
                    else Triple("rating", "Conditions", it.value.label)
                },
                d.panelType?.let { Triple("panel", "Type de panneau", it.value.label) },
                d.heightMm?.let { Triple("height", "Hauteur", Fmt.mm(it.value)) },
                d.lengthMm?.let { Triple("length", "Longueur", Fmt.mm(it.value)) },
            )
            var chosen by remember { mutableStateOf(candidates.map { it.first }.toSet()) }
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Texte reconnu sur la plaque") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(result.text, style = MaterialTheme.typography.bodySmall)
                        HorizontalDivider()
                        if (candidates.isEmpty()) Text("Aucune information exploitable reconnue.")
                        candidates.forEach { (key, label, value) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = key in chosen, onCheckedChange = { chosen = if (it) chosen + key else chosen - key })
                                Column { Text(label, fontWeight = FontWeight.Medium); Text(value, style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                        if (d.powerW != null && d.rating == null) {
                            Text("Régime de la puissance non lu : elle sera rattachée aux conditions actuelles de la fiche (par défaut EN 442 75/65/20 °C). " +
                                "Vérifiez-les dans la section Puissance.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        }
                        if (presumedOld && "rating" !in chosen && "power" in chosen) {
                            Text("Puissance en kcal/h sans régime : la rattacher à 75/65/20 °C la surestime d'environ 26 % si elle est donnée à 90/70/20 °C.",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        }
                        Text("Vérifiez chaque valeur : la lecture automatique peut se tromper.", style = MaterialTheme.typography.labelSmall)
                    }
                },
                confirmButton = { TextButton(enabled = chosen.isNotEmpty(), onClick = { onApply(d, chosen) }) { Text("Appliquer") } },
                dismissButton = { TextButton(onClick = onDismiss) { Text("Ignorer") } },
            )
        }
    }
}

@Composable
private fun AiResultDialog(result: ClassificationResult, onDismiss: () -> Unit, onApply: (String, Float, String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Proposition du modèle d'IA") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when (result) {
                    ClassificationResult.ModelUnavailable -> Text("Aucun modèle n'est installé.")
                    is ClassificationResult.Failure -> Text("Échec de l'analyse : ${result.message}")
                    is ClassificationResult.Suggestions -> {
                        if (result.items.isEmpty()) Text("Aucune catégorie reconnue.")
                        result.items.forEach { s ->
                            val cat = RadiatorLibrary.find(s.categoryCode)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(cat?.title ?: s.categoryCode, fontWeight = FontWeight.Medium)
                                    Text("Confiance ${Fmt.pct(s.confidence.toDouble())}" + if (s.confidence < 0.6f) " – incertain" else "", style = MaterialTheme.typography.bodySmall)
                                }
                                if (cat != null) TextButton(onClick = { onApply(s.categoryCode, s.confidence, result.modelName) }) { Text("Appliquer") }
                            }
                        }
                        Text("Une proposition n'est jamais certaine : validez ou corrigez la fiche.", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
    )
}

@Composable
private fun NewRoomDialog(onDismiss: () -> Unit, onCreate: (String, Int) -> Unit) {
    var name by remember { mutableStateOf("") }
    var floor by remember { mutableStateOf<Int?>(0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nouvelle pièce") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextInput("Nom de la pièce", name, { name = it })
                IntField("Niveau", floor, { floor = it }, min = -3, max = 20, supportingText = "0 = rez-de-chaussée, −1 = sous-sol")
                Text("Dimensions et parois à compléter ensuite dans l'onglet Maison.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onCreate(name, floor ?: 0) }) { Text("Créer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
