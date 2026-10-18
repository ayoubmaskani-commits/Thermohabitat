package fr.thermohabitat.pro.ui.house

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import fr.thermohabitat.pro.data.db.PhotoOwner
import fr.thermohabitat.pro.data.db.RoomEntity
import fr.thermohabitat.pro.data.repository.ProjectRepository
import fr.thermohabitat.pro.di.ApplicationScope
import fr.thermohabitat.pro.domain.AnalyzedProject
import fr.thermohabitat.pro.domain.StudyService
import fr.thermohabitat.pro.ui.components.BannerKind
import fr.thermohabitat.pro.ui.components.ChoiceChips
import fr.thermohabitat.pro.ui.components.ConfirmDialog
import fr.thermohabitat.pro.ui.components.DropdownField
import fr.thermohabitat.pro.ui.components.InfoBanner
import fr.thermohabitat.pro.ui.components.IntField
import fr.thermohabitat.pro.ui.components.NumberField
import fr.thermohabitat.pro.ui.components.OriginBadge
import fr.thermohabitat.pro.ui.components.PhotoStrip
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
import fr.thermohabitat.pro.ui.navigation.RadiatorEditRoute
import fr.thermohabitat.pro.ui.navigation.RoomEditRoute
import fr.thermohabitat.pro.ui.navigation.SurfaceEditRoute
import fr.thermohabitat.pro.ui.util.AutoSaver
import fr.thermohabitat.pro.ui.util.Fmt
import fr.thermohabitat.thermal.calc.RadiatorPower
import fr.thermohabitat.thermal.library.CompositionTemplates
import fr.thermohabitat.thermal.model.Boundary
import fr.thermohabitat.thermal.model.Orientation
import fr.thermohabitat.thermal.model.PhotoKind
import fr.thermohabitat.thermal.model.RoomUsage
import fr.thermohabitat.thermal.model.SurfaceKind
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

/** Préréglages indicatifs de ponts thermiques linéiques ψ (ordre de grandeur, liaisons béton). */
private val bridgePresets = listOf(
    "Plancher intermédiaire béton / mur (isolation intérieure)" to 0.80,
    "Plancher bas béton / mur (isolation intérieure)" to 0.70,
    "Refend / mur extérieur (isolation intérieure)" to 0.70,
    "Plancher bois / mur" to 0.10,
    "Pourtour de baie" to 0.10,
    "Liaison avec isolation par l'extérieur" to 0.10,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class RoomEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: ProjectRepository,
    studyService: StudyService,
    @ApplicationScope appScope: CoroutineScope,
) : ViewModel() {
    val roomId = savedStateHandle.toRoute<RoomEditRoute>().roomId
    private val _form = MutableStateFlow<RoomEntity?>(null)
    val form = _form.asStateFlow()
    private val saver = AutoSaver<RoomEntity>(viewModelScope, appScope) { repository.updateRoom(it) }

    val analyzed = repository.observeRoom(roomId).filterNotNull().map { it.buildingId }.distinctUntilChanged()
        .flatMapLatest { studyService.observe(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val photos = repository.observePhotos(PhotoOwner.ROOM, roomId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init { viewModelScope.launch { _form.value = repository.getRoom(roomId) } }

    fun update(transform: (RoomEntity) -> RoomEntity) {
        val next = transform(_form.value ?: return)
        _form.value = next
        saver.submit(next)
    }

    fun addStandardSurfaces(
        exterior: Set<Orientation>, wallTemplate: String?, floor: Boundary?, floorTemplate: String?,
        ceiling: Boundary?, ceilingTemplate: String?, interior: Boolean,
    ) = viewModelScope.launch {
        saver.flush()
        repository.addStandardSurfaces(roomId, exterior, wallTemplate, floor, floorTemplate, ceiling, ceilingTemplate, interior)
    }

    suspend fun addSurface(kind: SurfaceKind, boundary: Boundary): Long { saver.flush(); return repository.addSurface(roomId, kind, boundary) }

    fun addBridge(label: String, length: Double, psi: Double) = viewModelScope.launch { repository.addBridge(roomId, label, length, psi) }
    fun deleteBridge(id: Long) = viewModelScope.launch { repository.deleteBridge(id) }

    suspend fun addRadiator(): Long? {
        val b = _form.value?.buildingId ?: return null
        return repository.createRadiatorDraft(b, roomId)
    }

    fun importPhotos(uris: List<android.net.Uri>) = viewModelScope.launch {
        val b = _form.value?.buildingId ?: return@launch
        uris.forEach { runCatching { repository.importPhoto(b, PhotoOwner.ROOM, roomId, it, PhotoKind.ROOM) } }
    }

    fun deletePhoto(id: Long) = viewModelScope.launch { repository.deletePhoto(id) }

    fun delete(onDone: () -> Unit) = viewModelScope.launch { repository.deleteRoom(roomId); onDone() }
    suspend fun duplicate(): Long? { saver.flush(); return repository.duplicateRoom(roomId) }

    override fun onCleared() { saver.flush() }
}

@Composable
fun RoomEditScreen(nav: NavController, vm: RoomEditViewModel = hiltViewModel()) {
    val form by vm.form.collectAsStateWithLifecycle()
    val analyzed by vm.analyzed.collectAsStateWithLifecycle()
    val photos by vm.photos.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var showAssistant by remember { mutableStateOf(false) }
    var showAddSurface by remember { mutableStateOf(false) }
    var showAddBridge by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var photoToDelete by remember { mutableStateOf<Long?>(null) }
    var viewing by remember { mutableStateOf<fr.thermohabitat.pro.data.db.PhotoEntity?>(null) }
    val gallery = rememberGalleryPicker { vm.importPhotos(it) }

    ScreenScaffold(
        title = form?.name ?: "Pièce",
        onBack = { nav.popBackStack() },
        actions = {
            IconButton(onClick = { scope.launch { vm.duplicate()?.let { nav.navigate(RoomEditRoute(it)) } } }) { Icon(Icons.Filled.ContentCopy, "Dupliquer") }
            IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Supprimer") }
        },
    ) { padding ->
        val r = form ?: return@ScreenScaffold
        val p: AnalyzedProject? = analyzed
        val room = p?.project?.building?.room(r.id)
        val rl = p?.result?.heatLoss?.room(r.id)
        val cov = p?.result?.studyCoverage?.rooms?.firstOrNull { it.roomId == r.id }
        ScrollColumn(padding) {
            SectionCard("Identification", icon = Icons.Filled.Thermostat) {
                TextInput("Nom de la pièce", r.name, { v -> vm.update { it.copy(name = v) } })
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    IntField("Niveau", r.floorLevel, { v -> vm.update { it.copy(floorLevel = v ?: 0) } }, Modifier.weight(1f), min = -3, max = 20,
                        supportingText = floorLabel(r.floorLevel))
                    NumberField("Consigne", r.setpointC, { v -> vm.update { it.copy(setpointC = v ?: 20.0) } }, Modifier.weight(1f), unit = "°C",
                        decimals = 1, nullable = false, min = 5.0, max = 30.0, supportingText = "Usuel : ${Fmt.temp(r.usage.usualSetpointC, 0)}")
                }
                DropdownField("Usage", RoomUsage.entries, r.usage, { it.label }, { v -> vm.update { it.copy(usage = v) } },
                    supportingText = "Renouvellement d'air minimal : ${Fmt.num(r.minAirChangeOverride ?: r.usage.minAirChangePerHour, 1)} vol/h")
            }

            SectionCard("Dimensions", icon = Icons.Filled.Straighten) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("Longueur", r.lengthM, { v -> vm.update { it.copy(lengthM = v ?: it.lengthM) } }, Modifier.weight(1f), unit = "m", nullable = false, min = 0.1, max = 100.0)
                    NumberField("Largeur", r.widthM, { v -> vm.update { it.copy(widthM = v ?: it.widthM) } }, Modifier.weight(1f), unit = "m", nullable = false, min = 0.1, max = 100.0)
                    NumberField("Hauteur", r.heightM, { v -> vm.update { it.copy(heightM = v ?: it.heightM) } }, Modifier.weight(1f), unit = "m", nullable = false, min = 1.0, max = 20.0)
                }
                NumberField("Surface au sol imposée (pièce non rectangulaire)", r.floorAreaOverrideM2, { v -> vm.update { it.copy(floorAreaOverrideM2 = v) } },
                    unit = "m²", decimals = 1, min = 0.1)
                val area = r.floorAreaOverrideM2 ?: (r.lengthM * r.widthM)
                ValueRow("Surface au sol", Fmt.area(area), emphasize = true)
                ValueRow("Volume", Fmt.volume(area * r.heightM), emphasize = true)
            }

            SectionCard("Ventilation et apports", icon = Icons.Filled.AutoAwesome) {
                NumberField("Renouvellement minimal (vide = selon l'usage)", r.minAirChangeOverride, { v -> vm.update { it.copy(minAirChangeOverride = v) } },
                    unit = "vol/h", decimals = 2, min = 0.0, max = 10.0)
                NumberField("Apports internes moyens", r.internalGainsW, { v -> vm.update { it.copy(internalGainsW = v ?: 0.0) } }, unit = "W", decimals = 0,
                    nullable = false, min = 0.0, supportingText = "Utilisés seulement dans la simulation dynamique (non déduits du dimensionnement)")
            }

            SectionCard(
                "Parois", icon = Icons.Filled.Layers,
                subtitle = "Murs, planchers, plafonds avec leurs couches et leurs baies",
                action = { IconButton(onClick = { showAddSurface = true }) { Icon(Icons.Filled.Add, "Ajouter une paroi") } },
            ) {
                if (room == null || room.surfaces.isEmpty()) {
                    InfoBanner("Aucune paroi : utilisez l'assistant pour créer les murs, le plancher et le plafond à partir des dimensions.")
                }
                Button(onClick = { showAssistant = true }) { Text("Assistant de création des parois") }
                room?.surfaces?.forEach { s ->
                    val elements = rl?.elements?.filter { it.surfaceId == s.id }.orEmpty()
                    val opaque = elements.firstOrNull { it.openingId == null }
                    Column(Modifier.fillMaxWidth().clickable { nav.navigate(SurfaceEditRoute(s.id)) }.padding(vertical = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(s.label.ifBlank { s.kind.label }, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            opaque?.let { OriginBadge(it.uOrigin) }
                        }
                        Text(
                            "${s.kind.label} · ${s.boundary.label}${s.orientation?.let { " · " + it.label } ?: ""} · " +
                                "${Fmt.area(opaque?.areaM2)} · U ${Fmt.num(opaque?.uValue, 2)} · ${s.openings.size} baie(s)",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text("Déperdition : ${Fmt.watts(elements.sumOf { it.powerW })}", style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider()
                }
            }

            SectionCard(
                "Ponts thermiques linéiques", subtitle = "Facultatif : en complément ou à la place du forfait ΔU du bâtiment",
                action = { IconButton(onClick = { showAddBridge = true }) { Icon(Icons.Filled.Add, "Ajouter") } },
            ) {
                if (room?.thermalBridges.isNullOrEmpty()) Text("Aucun pont thermique détaillé.", style = MaterialTheme.typography.bodySmall)
                room?.thermalBridges?.forEach { b ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${b.label} · ${Fmt.num(b.lengthM, 1)} m × ψ ${Fmt.num(b.psi, 2)} = ${Fmt.num(b.psi * b.lengthM, 2)} W/K", Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall)
                        IconButton(onClick = { vm.deleteBridge(b.id) }) { Icon(Icons.Filled.Delete, "Supprimer") }
                    }
                }
            }

            SectionCard("Radiateurs de la pièce", icon = Icons.Filled.ViewColumn) {
                val regime = p?.result?.studyCoverage
                val radiators = p?.project?.building?.radiatorsIn(r.id).orEmpty()
                if (radiators.isEmpty()) Text("Aucun radiateur associé.", style = MaterialTheme.typography.bodySmall)
                radiators.forEach { rad ->
                    val out = regime?.let { RadiatorPower.outputOf(rad, it.supplyC, it.returnC ?: (it.supplyC - 10), r.setpointC) }
                    Row(Modifier.fillMaxWidth().clickable { nav.navigate(RadiatorEditRoute(rad.id)) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${rad.code} · ${rad.label.ifBlank { rad.type.label }}", style = MaterialTheme.typography.bodyMedium)
                            Text(if (rad.validated) "À ${regime?.supplyC?.toInt()} °C : ${Fmt.watts(out?.powerW)}" else "En attente de validation – non compté",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { scope.launch { vm.addRadiator()?.let { nav.navigate(RadiatorEditRoute(it)) } } }) { Text("Saisir") }
                    OutlinedButton(onClick = {
                        scope.launch { vm.addRadiator()?.let { nav.navigate(CaptureRoute(PhotoOwner.RADIATOR.name, it, r.buildingId)) } }
                    }) { Icon(Icons.Filled.CameraAlt, null); Text("  Scanner") }
                }
            }

            if (rl != null) {
                SectionCard("Déperditions de la pièce", subtitle = "θint ${Fmt.temp(rl.setpointC)} · θe ${Fmt.temp(rl.designOutdoorC)}") {
                    cov?.let { StatusChip(it.status, text = "${it.status.label} – ${Fmt.watts(it.availableW)} disponibles pour ${Fmt.watts(it.needW)}") }
                    rl.elements.forEach { e ->
                        ValueRow("${e.label} (${e.category.label.lowercase()})", Fmt.watts(e.powerW))
                    }
                    ValueRow("Infiltrations (${Fmt.num(rl.infiltrationFlowM3h, 0)} m³/h)", Fmt.watts(rl.infiltrationW))
                    ValueRow("Renouvellement d'air (débit retenu ${Fmt.num(rl.designFlowM3h, 0)} m³/h)", Fmt.watts(rl.airRenewalW))
                    if (rl.reheatW > 0) ValueRow("Surpuissance de relance", Fmt.watts(rl.reheatW))
                    HorizontalDivider()
                    ValueRow("Total", "${Fmt.watts(rl.totalW)} · ${Fmt.num(rl.wattsPerM2, 0)} W/m²", emphasize = true)
                    rl.warnings.forEach { InfoBanner(it, kind = BannerKind.WARNING) }
                }
            }

            SectionCard("Photos et notes") {
                PhotoStrip(
                    photos,
                    onCamera = { nav.navigate(CaptureRoute(PhotoOwner.ROOM.name, r.id, r.buildingId)) },
                    onGallery = { gallery.launch(galleryRequest()) },
                    onOpen = { viewing = it },
                )
                TextInput("Notes", r.notes, { v -> vm.update { it.copy(notes = v) } }, singleLine = false)
            }
        }
    }

    if (showAssistant) {
        SurfaceAssistantDialog(onDismiss = { showAssistant = false }) { ext, wall, floor, floorT, ceiling, ceilT, interior ->
            showAssistant = false
            vm.addStandardSurfaces(ext, wall, floor, floorT, ceiling, ceilT, interior)
        }
    }
    if (showAddSurface) {
        AddSurfaceDialog(onDismiss = { showAddSurface = false }) { kind, boundary ->
            showAddSurface = false
            scope.launch { nav.navigate(SurfaceEditRoute(vm.addSurface(kind, boundary))) }
        }
    }
    if (showAddBridge) {
        AddBridgeDialog(onDismiss = { showAddBridge = false }) { label, length, psi -> showAddBridge = false; vm.addBridge(label, length, psi) }
    }
    if (confirmDelete) {
        ConfirmDialog("Supprimer la pièce ?", "Ses parois et photos seront supprimées. Les radiateurs associés seront conservés sans pièce.",
            "Supprimer", onConfirm = { vm.delete { nav.popBackStack() } }, onDismiss = { confirmDelete = false }, destructive = true)
    }
    viewing?.let { ph ->
        fr.thermohabitat.pro.ui.components.PhotoViewerDialog(ph, onDismiss = { viewing = null }, onDelete = { viewing = null; photoToDelete = ph.id })
    }
    photoToDelete?.let { id ->
        ConfirmDialog("Supprimer la photo ?", "Le fichier sera effacé du téléphone.", "Supprimer",
            onConfirm = { vm.deletePhoto(id) }, onDismiss = { photoToDelete = null }, destructive = true)
    }
}

@Composable
private fun SurfaceAssistantDialog(
    onDismiss: () -> Unit,
    onCreate: (Set<Orientation>, String?, Boundary?, String?, Boundary?, String?, Boolean) -> Unit,
) {
    var exterior by remember { mutableStateOf(setOf(Orientation.S)) }
    val wallTemplates = CompositionTemplates.all.filter { it.kind == SurfaceKind.WALL }
    var wall by remember { mutableStateOf(wallTemplates.first().code) }
    val floorOptions = listOf<Boundary?>(Boundary.GROUND, Boundary.UNHEATED, Boundary.HEATED_ROOM, Boundary.EXTERIOR, null)
    var floor by remember { mutableStateOf<Boundary?>(Boundary.GROUND) }
    val floorTemplates = CompositionTemplates.all.filter { it.kind == SurfaceKind.FLOOR }
    var floorT by remember { mutableStateOf(floorTemplates.first().code) }
    val ceilingOptions = listOf<Boundary?>(Boundary.UNHEATED, Boundary.HEATED_ROOM, Boundary.EXTERIOR, null)
    var ceiling by remember { mutableStateOf<Boundary?>(Boundary.HEATED_ROOM) }
    val ceilingTemplates = CompositionTemplates.all.filter { it.kind == SurfaceKind.CEILING || it.kind == SurfaceKind.ROOF }
    var ceilingT by remember { mutableStateOf("plancher_intermediaire") }
    var interior by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Assistant parois") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Murs donnant sur l'extérieur", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(Orientation.N, Orientation.E, Orientation.S, Orientation.W).forEach { o ->
                        androidx.compose.material3.FilterChip(
                            selected = o in exterior,
                            onClick = { exterior = if (o in exterior) exterior - o else exterior + o },
                            label = { Text(o.label) },
                        )
                    }
                }
                Text("Murs N et S = longueur, E et O = largeur de la pièce.", style = MaterialTheme.typography.bodySmall)
                DropdownField("Composition des murs extérieurs", wallTemplates.map { it.code }, wall, { c -> CompositionTemplates.find(c)!!.label }, { wall = it })
                SwitchRow("Créer aussi les cloisons intérieures", interior, { interior = it }, description = "Vers des pièces chauffées (utile pour l'inertie)")
                DropdownField("Plancher bas", floorOptions, floor, { it?.label ?: "Aucun" }, { floor = it })
                if (floor != null) DropdownField("Composition du plancher", floorTemplates.map { it.code }, floorT, { c -> CompositionTemplates.find(c)!!.label }, { floorT = it })
                DropdownField("Plafond", ceilingOptions, ceiling, { it?.label ?: "Aucun" }, { ceiling = it })
                if (ceiling != null) DropdownField("Composition du plafond", ceilingTemplates.map { it.code }, ceilingT, { c -> CompositionTemplates.find(c)!!.label }, { ceilingT = it })
                Text("Compositions indicatives modifiables ensuite paroi par paroi (épaisseur d'isolant, matériaux).", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(exterior, wall, floor, floorT, ceiling, ceilingT, interior) }) { Text("Créer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@Composable
private fun AddSurfaceDialog(onDismiss: () -> Unit, onCreate: (SurfaceKind, Boundary) -> Unit) {
    var kind by remember { mutableStateOf(SurfaceKind.WALL) }
    var boundary by remember { mutableStateOf(Boundary.EXTERIOR) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nouvelle paroi") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceChips(SurfaceKind.entries, kind, { it.label }, { kind = it })
                DropdownField("Donne sur", Boundary.entries, boundary, { it.label }, { boundary = it })
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(kind, boundary) }) { Text("Créer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@Composable
private fun AddBridgeDialog(onDismiss: () -> Unit, onCreate: (String, Double, Double) -> Unit) {
    var label by remember { mutableStateOf(bridgePresets.first().first) }
    var psi by remember { mutableStateOf<Double?>(bridgePresets.first().second) }
    var length by remember { mutableStateOf<Double?>(4.0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pont thermique linéique") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DropdownField("Type (valeur indicative)", bridgePresets, bridgePresets.firstOrNull { it.first == label },
                    { "${it.first} – ψ ≈ ${Fmt.num(it.second, 2)}" }, { label = it.first; psi = it.second })
                TextInput("Libellé", label, { label = it })
                NumberField("Longueur", length, { length = it }, unit = "m", min = 0.0)
                NumberField("ψ", psi, { psi = it }, unit = "W/(m·K)", min = 0.0, max = 3.0)
            }
        },
        confirmButton = { TextButton(enabled = length != null && psi != null, onClick = { onCreate(label, length!!, psi!!) }) { Text("Ajouter") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
