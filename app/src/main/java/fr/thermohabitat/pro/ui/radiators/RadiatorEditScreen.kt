package fr.thermohabitat.pro.ui.radiators

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import fr.thermohabitat.pro.ui.components.BannerKind
import fr.thermohabitat.pro.ui.components.ChoiceChips
import fr.thermohabitat.pro.ui.components.ConfirmDialog
import fr.thermohabitat.pro.ui.components.DropdownField
import fr.thermohabitat.pro.ui.components.InfoBanner
import fr.thermohabitat.pro.ui.components.IntField
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
import fr.thermohabitat.pro.ui.navigation.ElementCountRoute
import fr.thermohabitat.pro.ui.navigation.MeasureRoute
import fr.thermohabitat.pro.ui.navigation.PhotoEditRoute
import fr.thermohabitat.pro.ui.navigation.RadiatorEditRoute
import fr.thermohabitat.pro.ui.navigation.RadiatorLibraryRoute
import fr.thermohabitat.pro.ui.util.AutoSaver
import fr.thermohabitat.pro.ui.util.Fmt
import fr.thermohabitat.thermal.calc.CoverageStatus
import fr.thermohabitat.thermal.calc.PowerBasis
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

/** Données minimales exigées avant validation. */
fun validationIssues(r: RadiatorEntity): List<String> = buildList {
    if (r.type == RadiatorType.UNKNOWN) add("Le type de radiateur n'est pas identifié.")
    if (r.nominalPowerW != null && !RatingCondition(r.ratingSupplyC, r.ratingReturnC, r.ratingAmbientC).isValid) {
        add("Conditions de la puissance documentée incohérentes (le départ doit être ≥ au retour, lui-même > à l'ambiance).")
    }
    val model = r.toModel()
    if (RadiatorPower.resolveNominal(model) == null) {
        add(
            if (r.type == RadiatorType.DECORATIVE || r.type == RadiatorType.CONVECTOR || r.type == RadiatorType.UNKNOWN)
                "Ce type ne permet pas d'estimation : saisissez la puissance nominale documentée."
            else "Dimensions insuffisantes pour estimer la puissance (hauteur, profondeur ou longueur, nombre d'éléments) ou puissance nominale manquante."
        )
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
        it.copy(type = t, material = if (it.material == RadiatorMaterial.UNKNOWN) t.defaultMaterial else it.material,
            panelType = if (t == RadiatorType.STEEL_PANEL) it.panelType ?: PanelType.T22 else it.panelType)
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
            if ("rating" in fields) d.rating?.let { x = x.copy(ratingSupplyC = it.value.supplyC, ratingReturnC = it.value.returnC, ratingAmbientC = it.value.ambientC) }
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
    val scope = rememberCoroutineScope()

    ScreenScaffold(
        title = form?.let { "Radiateur ${it.code}" } ?: "Radiateur",
        onBack = { nav.popBackStack() },
        actions = { IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Supprimer") } },
    ) { padding ->
        val r = form ?: return@ScreenScaffold
        val model = r.toModel()
        val nominal = RadiatorPower.resolveNominal(model)
        val rooms = project?.project?.rooms?.map { it.room }.orEmpty()
        val room = rooms.firstOrNull { it.id == r.roomId }
        val mainPhoto = photos.firstOrNull { it.kind == PhotoKind.FRONT } ?: photos.firstOrNull()
        ScrollColumn(padding) {
            // --- Fiche de synthèse « Radiateur identifié » ---
            SectionCard("Radiateur identifié", icon = Icons.Filled.Thermostat) {
                mainPhoto?.let { PhotoThumbnail(it.fileName, Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(12.dp))) }
                ValueRow("Type", r.type.label)
                ValueRow("Matériau", r.material.label)
                ValueRow("Éléments / colonnes", "${r.sections ?: "–"} / ${r.columns ?: "–"}")
                ValueRow("Hauteur × longueur × profondeur", "${Fmt.mm(r.heightMm)} × ${Fmt.mm(r.lengthMm)} × ${Fmt.mm(r.depthMm)}")
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
                StatusChip(if (r.validated) CoverageStatus.SUFFICIENT else CoverageStatus.LIMIT,
                    text = if (r.validated) "Validé : pris en compte dans l'étude" else "À valider : non pris en compte")
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
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { nav.navigate(PhotoEditRoute(current.id)) }) { Icon(Icons.Filled.Crop, null); Text(" Recadrer") }
                        OutlinedButton(onClick = { nav.navigate(ElementCountRoute(current.id, r.id)) }) { Icon(Icons.Filled.ViewColumn, null); Text(" Compter les éléments") }
                        OutlinedButton(onClick = { nav.navigate(MeasureRoute(current.id, r.id)) }) { Icon(Icons.Filled.Straighten, null); Text(" Mesurer") }
                        OutlinedButton(onClick = { vm.readPlate(current) }) { Icon(Icons.Filled.DocumentScanner, null); Text(" Lire la plaque") }
                        TextButton(onClick = { vm.deletePhoto(current.id); selectedPhoto = null }) { Text("Supprimer", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }

            // --- Identification ---
            SectionCard("Identification", icon = Icons.Filled.AutoAwesome) {
                if (vm.classifierAvailable) {
                    InfoBanner("Un modèle d'IA embarqué est installé. Ses propositions s'accompagnent d'un indice de confiance et doivent être confirmées.")
                    val target = selectedPhoto ?: mainPhoto
                    OutlinedButton(enabled = target != null && aiState !is AiUiState.Running, onClick = { target?.let { vm.classify(it) } }) {
                        Text("Proposer un type à partir de la photo")
                    }
                } else {
                    InfoBanner("Aucun modèle d'IA entraîné et validé n'est installé dans cette version : le type n'est pas reconnu automatiquement. " +
                        "Identifiez le radiateur avec la bibliothèque illustrée ; le comptage des éléments et la lecture de plaque restent disponibles.",
                        kind = BannerKind.WARNING)
                }
                Button(onClick = { nav.navigate(RadiatorLibraryRoute(r.id)) }) { Icon(Icons.AutoMirrored.Filled.MenuBook, null); Text("  Bibliothèque illustrée") }
                RadiatorLibrary.find(r.categoryCode)?.let { cat ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        RadiatorIllustration(cat)
                        Text("Catégorie retenue : ${cat.title}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                TextInput("Libellé", r.label, { v -> vm.update { it.copy(label = v) } })
                DropdownField("Type", RadiatorType.entries, r.type, { it.label }, { vm.setType(it) })
                DropdownField("Matériau", RadiatorMaterial.entries, r.material, { it.label }, { v -> vm.update { it.copy(material = v) } })
                DropdownField("Époque", RadiatorEra.entries, r.era, { it.label }, { v -> vm.update { it.copy(era = v) } })
                if (r.type.isSectional || r.type == RadiatorType.CAST_IRON_PLATE) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IntField("Nombre d'éléments", r.sections, { v -> vm.update { it.copy(sections = v) } }, Modifier.weight(1f), min = 1, max = 200)
                        IntField(if (r.type == RadiatorType.CAST_IRON_PLATE) "Plaques" else "Colonnes", r.columns, { v -> vm.update { it.copy(columns = v) } },
                            Modifier.weight(1f), min = 1, max = 10)
                    }
                }
                if (r.type == RadiatorType.STEEL_PANEL) {
                    DropdownField("Type de panneau", PanelType.entries, r.panelType, { it.label }, { v -> vm.update { it.copy(panelType = v) } })
                }
                TextInput("Fabricant", r.manufacturer, { v -> vm.update { it.copy(manufacturer = v) } })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextInput("Modèle", r.model, { v -> vm.update { it.copy(model = v) } }, Modifier.weight(1f))
                    TextInput("Référence", r.reference, { v -> vm.update { it.copy(reference = v) } }, Modifier.weight(1f))
                }
            }

            // --- Dimensions ---
            SectionCard("Dimensions", icon = Icons.Filled.Straighten,
                subtitle = "Aucune dimension n'est déduite d'une photo sans objet de référence. Mesurez au mètre ou utilisez l'outil de mesure avec référence.") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("Hauteur", r.heightMm, { v -> vm.update { it.copy(heightMm = v) } }, Modifier.weight(1f), unit = "mm", decimals = 0, min = 50.0, max = 3000.0)
                    NumberField("Longueur", r.lengthMm, { v -> vm.update { it.copy(lengthMm = v) } }, Modifier.weight(1f), unit = "mm", decimals = 0, min = 50.0, max = 6000.0)
                    NumberField("Profondeur", r.depthMm, { v -> vm.update { it.copy(depthMm = v) } }, Modifier.weight(1f), unit = "mm", decimals = 0, min = 10.0, max = 500.0)
                }
                OriginSelector(r.dimensionsOrigin, { o -> vm.update { it.copy(dimensionsOrigin = o) } })
            }

            // --- Puissance ---
            SectionCard("Puissance") {
                NumberField("Puissance nominale documentée", r.nominalPowerW, { v -> vm.update { it.copy(nominalPowerW = v,
                    nominalPowerOrigin = if (v == null) ValueOrigin.UNKNOWN else if (it.nominalPowerOrigin == ValueOrigin.UNKNOWN) ValueOrigin.DECLARED else it.nominalPowerOrigin) } },
                    unit = "W", decimals = 0, min = 10.0, max = 20000.0, supportingText = "Fiche fabricant, plaque, catalogue. Vide : estimation géométrique.")
                if (r.nominalPowerW != null) {
                    Text("Conditions de la puissance documentée", style = MaterialTheme.typography.labelLarge)
                    val ratings = listOf(RatingCondition.EN442, RatingCondition.DIN4703)
                    ChoiceChips(ratings + listOf(RatingCondition(r.ratingSupplyC, r.ratingReturnC, r.ratingAmbientC)).filter { it !in ratings }, RatingCondition(r.ratingSupplyC, r.ratingReturnC, r.ratingAmbientC),
                        { if (it == RatingCondition.EN442) "EN 442 ${it.label}" else if (it == RatingCondition.DIN4703) "Ancienne norme ${it.label}" else it.label },
                        { c -> vm.update { it.copy(ratingSupplyC = c.supplyC, ratingReturnC = c.returnC, ratingAmbientC = c.ambientC) } })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumberField("Départ", r.ratingSupplyC, { v -> v?.let { vm.update { x -> x.copy(ratingSupplyC = it) } } }, Modifier.weight(1f), unit = "°C", decimals = 0, nullable = false)
                        NumberField("Retour", r.ratingReturnC, { v -> v?.let { vm.update { x -> x.copy(ratingReturnC = it) } } }, Modifier.weight(1f), unit = "°C", decimals = 0, nullable = false)
                        NumberField("Ambiance", r.ratingAmbientC, { v -> v?.let { vm.update { x -> x.copy(ratingAmbientC = it) } } }, Modifier.weight(1f), unit = "°C", decimals = 0, nullable = false)
                    }
                    OriginSelector(r.nominalPowerOrigin, { o -> vm.update { it.copy(nominalPowerOrigin = o) } })
                    NumberField("Incertitude admise", r.documentedUncertaintyPct, { v -> vm.update { it.copy(documentedUncertaintyPct = v ?: 5.0) } }, unit = "%", decimals = 0, nullable = false, min = 0.0, max = 50.0)
                }
                NumberField("Exposant n", r.exponent, { v -> vm.update { it.copy(exponent = v, exponentOrigin = if (v == null) ValueOrigin.ESTIMATED else ValueOrigin.DECLARED) } },
                    decimals = 2, min = 1.0, max = 2.0, supportingText = "Vide : valeur usuelle ${Fmt.num(r.type.defaultExponent, 2)} pour ce type")
                NumberField("Débit d'eau connu (équilibrage)", r.flowKgH, { v -> vm.update { it.copy(flowKgH = v) } }, unit = "kg/h", decimals = 0, min = 1.0)
                HorizontalDivider()
                when {
                    nominal == null -> InfoBanner("Puissance non déterminable : complétez les dimensions et le nombre d'éléments, ou saisissez la puissance documentée. " +
                        "Aucune puissance n'est inventée à partir de la seule photographie.", kind = BannerKind.WARNING)
                    else -> {
                        Text(nominal.method, style = MaterialTheme.typography.bodySmall)
                        val ambient = ambientOverride ?: room?.setpointC ?: 20.0
                        NumberField("Température ambiante", ambientOverride, { vm.ambient.value = it }, unit = "°C", decimals = 1, min = 5.0, max = 30.0,
                            supportingText = "Vide : consigne de la pièce (${Fmt.temp(room?.setpointC ?: 20.0, 0)})")
                        Text("Puissance disponible selon le régime d'eau (ambiance ${Fmt.temp(ambient)})", style = MaterialTheme.typography.labelLarge)
                        Regime.STANDARD.forEach { reg ->
                            val out = RadiatorPower.outputOf(model, reg.supplyC, reg.returnC, ambient)
                            ValueRow(reg.label, if (out.lowW != out.highW) "${Fmt.watts(out.powerW)}  (${Fmt.watts(out.lowW)} – ${Fmt.watts(out.highW)})" else Fmt.watts(out.powerW))
                        }
                        Text("Correction EN 442 : Φ = Φn × (ΔT / ΔTn)^n avec ΔT écart logarithmique moyen, n = ${Fmt.num(model.effectiveExponent, 2)}.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // --- Pièce ---
            SectionCard("Dans quelle pièce se trouve ce radiateur ?") {
                DropdownField("Pièce", rooms, room, { "${it.name} (${fr.thermohabitat.pro.ui.house.floorLabel(it.floorLevel)})" }, { v -> vm.update { it.copy(roomId = v.id) } },
                    placeholder = "Non attribué")
                OutlinedButton(onClick = { newRoom = true }) { Text("Créer une nouvelle pièce") }
                if (room != null) TextButton(onClick = { vm.update { it.copy(roomId = null) } }) { Text("Retirer de la pièce") }
            }

            // --- Validation obligatoire ---
            SectionCard("Validation", icon = Icons.Filled.CheckCircle) {
                Text("Les caractéristiques proposées (bibliothèque, comptage, lecture de plaque, IA) doivent être vérifiées. " +
                    "Seuls les radiateurs validés entrent dans le bilan de chauffage.", style = MaterialTheme.typography.bodyMedium)
                SwitchRow("Je confirme les caractéristiques de ce radiateur", r.validated, { on ->
                    if (on) {
                        val issues = validationIssues(r)
                        if (issues.isEmpty()) vm.setValidated(true) else showIssues = issues
                    } else vm.setValidated(false)
                })
            }

            TextInput("Notes", r.notes, { v -> vm.update { it.copy(notes = v) } }, singleLine = false)
        }
    }

    // --- Dialogues ---
    when (val s = ocrState) {
        OcrUiState.Running -> AlertDialog(onDismissRequest = {}, confirmButton = {}, title = { Text("Lecture de la plaque…") },
            text = { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(); Text("  Reconnaissance de texte sur le téléphone") } })
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
    if (newRoom) NewRoomDialog(onDismiss = { newRoom = false }) { name, floor -> newRoom = false; scope.launch { vm.createRoom(name, floor) } }
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
            val d = result.data
            val candidates = listOfNotNull(
                d.manufacturer?.let { Triple("manufacturer", "Fabricant", "${it.value} (confiance ${Fmt.pct(it.confidence)})") },
                d.model?.let { Triple("model", "Modèle", it.value) },
                d.reference?.let { Triple("reference", "Référence", it.value) },
                d.powerW?.let { Triple("power", "Puissance", "${Fmt.watts(it.value)} ← « ${it.sourceText} »") },
                d.rating?.let { Triple("rating", "Conditions", it.value.label) },
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
                IntField("Niveau (0 = rez-de-chaussée)", floor, { floor = it }, min = -3, max = 20)
                Text("Dimensions et parois à compléter ensuite dans l'onglet Maison.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onCreate(name, floor ?: 0) }) { Text("Créer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
