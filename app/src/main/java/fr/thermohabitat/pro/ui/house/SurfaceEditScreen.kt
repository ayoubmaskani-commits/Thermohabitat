package fr.thermohabitat.pro.ui.house

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Window
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import fr.thermohabitat.pro.data.db.LayerEntity
import fr.thermohabitat.pro.data.db.MaterialEntity
import fr.thermohabitat.pro.data.db.OpeningEntity
import fr.thermohabitat.pro.data.db.RoomEntity
import fr.thermohabitat.pro.data.db.SurfaceEntity
import fr.thermohabitat.pro.data.db.SurfaceWithDetails
import fr.thermohabitat.pro.data.db.toModel
import fr.thermohabitat.pro.data.repository.MaterialRepository
import fr.thermohabitat.pro.data.repository.ProjectRepository
import fr.thermohabitat.pro.di.ApplicationScope
import fr.thermohabitat.pro.ui.components.ActionRow
import fr.thermohabitat.pro.ui.components.BannerKind
import fr.thermohabitat.pro.ui.components.ChoiceChips
import fr.thermohabitat.pro.ui.components.ConfirmDialog
import fr.thermohabitat.pro.ui.components.DropdownField
import fr.thermohabitat.pro.ui.components.FieldRow
import fr.thermohabitat.pro.ui.components.InfoBanner
import fr.thermohabitat.pro.ui.components.IntField
import fr.thermohabitat.pro.ui.components.NumberField
import fr.thermohabitat.pro.ui.components.OriginBadge
import fr.thermohabitat.pro.ui.components.OriginSelector
import fr.thermohabitat.pro.ui.components.ScreenScaffold
import fr.thermohabitat.pro.ui.components.ScrollColumn
import fr.thermohabitat.pro.ui.components.SectionCard
import fr.thermohabitat.pro.ui.components.SwitchRow
import fr.thermohabitat.pro.ui.components.TextInput
import fr.thermohabitat.pro.ui.components.ValueRow
import fr.thermohabitat.pro.ui.navigation.SurfaceEditRoute
import fr.thermohabitat.pro.ui.util.AutoSaver
import fr.thermohabitat.pro.ui.util.Fmt
import fr.thermohabitat.thermal.calc.HeatLossCalculator
import fr.thermohabitat.thermal.calc.SurfaceGeometry
import fr.thermohabitat.thermal.calc.ThermalResistance
import fr.thermohabitat.thermal.calc.heatFlowDirection
import fr.thermohabitat.thermal.library.CompositionTemplates
import fr.thermohabitat.thermal.library.OpeningPresets
import fr.thermohabitat.thermal.library.UnheatedSpacePresets
import fr.thermohabitat.thermal.model.Boundary
import fr.thermohabitat.thermal.model.OpeningKind
import fr.thermohabitat.thermal.model.Orientation
import fr.thermohabitat.thermal.model.SurfaceKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SurfaceScreenState(
    val details: SurfaceWithDetails? = null,
    val room: RoomEntity? = null,
    val siblings: List<RoomEntity> = emptyList(),
    val materials: List<MaterialEntity> = emptyList(),
    /** Bâtiment complet (calcul du U équivalent d'un terre-plein, qui dépend de tous les planchers). */
    val building: fr.thermohabitat.thermal.model.Building? = null,
)

/** Le terre-plein (EN ISO 13370) n'est proposé que pour les planchers. */
fun boundariesFor(kind: SurfaceKind): List<Boundary> = Boundary.entries.filter { it != Boundary.GROUND || kind == SurfaceKind.FLOOR }

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SurfaceEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: ProjectRepository,
    materials: MaterialRepository,
    @ApplicationScope appScope: CoroutineScope,
) : ViewModel() {
    val surfaceId = savedStateHandle.toRoute<SurfaceEditRoute>().surfaceId
    private val _form = MutableStateFlow<SurfaceEntity?>(null)
    val form = _form.asStateFlow()
    private val saver = AutoSaver<SurfaceEntity>(viewModelScope, appScope) { repository.updateSurface(it) }

    private val detailsFlow = repository.observeSurface(surfaceId)
    private val roomFlow = detailsFlow.filterNotNull().map { it.surface.roomId }.flatMapLatest { repository.observeRoom(it) }
    private val projectFlow = roomFlow.flatMapLatest { r -> if (r == null) flowOf(null) else repository.observeProject(r.buildingId) }

    val state = combine(detailsFlow, roomFlow, projectFlow, materials.observeAll()) { d, r, p, m ->
        SurfaceScreenState(d, r, p?.rooms?.map { it.room }.orEmpty(), m, p?.building)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SurfaceScreenState())

    init { viewModelScope.launch { _form.value = repository.getSurface(surfaceId) } }

    fun update(transform: (SurfaceEntity) -> SurfaceEntity) {
        val next = transform(_form.value ?: return)
        _form.value = next
        saver.submit(next)
    }

    fun applyTemplate(code: String) = viewModelScope.launch {
        saver.flushNow()
        repository.applyTemplate(surfaceId, code)
        _form.value = repository.getSurface(surfaceId)
    }

    fun addLayer(materialId: Long, thicknessM: Double, interior: Boolean) = viewModelScope.launch {
        repository.addLayer(surfaceId, materialId, thicknessM, interior)
        if (_form.value?.uValueOverride != null) update { it.copy(uValueOverride = null) }
    }
    fun updateLayer(layer: LayerEntity) = viewModelScope.launch { repository.updateLayer(layer) }
    fun deleteLayer(id: Long) = viewModelScope.launch { repository.deleteLayer(surfaceId, id) }
    fun moveLayer(id: Long, delta: Int) = viewModelScope.launch { repository.moveLayer(surfaceId, id, delta) }

    fun addOpening(presetCode: String?, kind: OpeningKind) = viewModelScope.launch { repository.addOpening(surfaceId, presetCode, kind) }
    fun updateOpening(o: OpeningEntity) = viewModelScope.launch { repository.updateOpening(o) }
    fun deleteOpening(id: Long) = viewModelScope.launch { repository.deleteOpening(id) }

    fun delete(onDone: () -> Unit) = viewModelScope.launch { repository.deleteSurface(surfaceId); onDone() }

    override fun onCleared() { saver.flush() }
}

@Composable
fun SurfaceEditScreen(nav: NavController, vm: SurfaceEditViewModel = hiltViewModel()) {
    val form by vm.form.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    var addLayerInterior by remember { mutableStateOf<Boolean?>(null) }

    ScreenScaffold(
        title = form?.label?.ifBlank { null } ?: "Paroi",
        onBack = { nav.popBackStack() },
        actions = { IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Supprimer la paroi") } },
    ) { padding ->
        val s = form ?: return@ScreenScaffold
        val details = state.details
        val room = state.room
        val materialMap = state.materials.associate { it.id to it.toModel() }
        // Paroi courante (formulaire + couches/baies en base) convertie pour le moteur → calculs en direct.
        val model = details?.copy(surface = s)?.toModel(materialMap)
        val isGroundFloor = s.boundary == Boundary.GROUND && s.kind == SurfaceKind.FLOOR
        val roomModel = room?.let {
            fr.thermohabitat.thermal.model.Room(id = it.id, name = it.name, lengthM = it.lengthM, widthM = it.widthM, heightM = it.heightM,
                floorAreaOverrideM2 = it.floorAreaOverrideM2)
        }
        ScrollColumn(padding) {
            SectionCard("Paroi") {
                TextInput("Libellé", s.label, { v -> vm.update { it.copy(label = v) } })
                Text("Type", style = MaterialTheme.typography.labelLarge)
                ChoiceChips(SurfaceKind.entries, s.kind, { it.label }, { v ->
                    vm.update { it.copy(kind = v, boundary = if (v != SurfaceKind.FLOOR && it.boundary == Boundary.GROUND) Boundary.EXTERIOR else it.boundary) }
                })
                DropdownField("Donne sur", boundariesFor(s.kind), s.boundary, { it.label }, { v -> vm.update { it.copy(boundary = v) } })
                if (s.kind == SurfaceKind.WALL || s.kind == SurfaceKind.ROOF) {
                    DropdownField("Orientation", listOf<Orientation?>(null) + Orientation.entries, s.orientation, { it?.label ?: "Non précisée" },
                        { v -> vm.update { it.copy(orientation = v) } })
                }
            }

            SectionCard("Surface") {
                if (s.kind == SurfaceKind.WALL) {
                    FieldRow {
                        NumberField("Longueur", s.lengthM, { v -> vm.update { it.copy(lengthM = v) } }, unit = "m", min = 0.0)
                        NumberField("Hauteur", s.heightM, { v -> vm.update { it.copy(heightM = v) } }, unit = "m", min = 0.0,
                            supportingText = "Vide : hauteur de la pièce")
                    }
                }
                NumberField("Surface brute imposée", s.areaOverrideM2, { v -> vm.update { it.copy(areaOverrideM2 = v) } }, unit = "m²", decimals = 2, min = 0.0,
                    supportingText = if (s.kind == SurfaceKind.WALL) "Prioritaire sur longueur × hauteur" else "Vide : surface au sol de la pièce")
                if (model != null && roomModel != null) {
                    ValueRow("Surface brute", Fmt.area(SurfaceGeometry.grossArea(model, roomModel)))
                    ValueRow("Baies", Fmt.area(SurfaceGeometry.openingsArea(model)))
                    ValueRow("Surface opaque nette", Fmt.area(SurfaceGeometry.netOpaqueArea(model, roomModel)), emphasize = true)
                }
            }

            BoundarySection(s, state.siblings.filter { it.id != s.roomId }, vm)

            SectionCard("Composition", icon = Icons.Filled.Layers, subtitle = "Couches de l'intérieur vers l'extérieur") {
                val templates = CompositionTemplates.all.filter { it.kind == s.kind || (s.kind == SurfaceKind.ROOF && it.kind == SurfaceKind.CEILING) }
                DropdownField("Composition type", templates.map { it.code }, null, { c -> CompositionTemplates.find(c)!!.label },
                    { vm.applyTemplate(it) }, placeholder = "Choisir un modèle…", supportingText = "Remplace les couches actuelles")
                val layers = details?.layers?.sortedBy { it.position }.orEmpty()
                if (layers.isEmpty()) InfoBanner("Aucune couche : ajoutez les matériaux ou saisissez directement un coefficient U ci-dessous.")
                layers.forEachIndexed { index, l ->
                    LayerRow(l, state.materials, s.kind, index == 0, index == layers.lastIndex, vm)
                }
                ActionRow {
                    OutlinedButton(onClick = { addLayerInterior = true }) {
                        Icon(Icons.Filled.Add, null); Spacer(Modifier.width(8.dp)); Text("Couche côté intérieur")
                    }
                    OutlinedButton(onClick = { addLayerInterior = false }) {
                        Icon(Icons.Filled.Add, null); Spacer(Modifier.width(8.dp)); Text("Couche côté extérieur")
                    }
                }
                // U équivalent EN ISO 13370 tel que le bilan le calcule (hors U imposé).
                val groundU = model?.takeIf { isGroundFloor }?.let { m ->
                    state.building?.let { b -> HeatLossCalculator().groundEquivalentU(b, m.copy(uValueOverride = null)) }
                }
                if (model != null) {
                    val dir = s.kind.heatFlowDirection
                    HorizontalDivider()
                    if (isGroundFloor) {
                        ValueRow("Résistance du plancher Rf", "${Fmt.num(ThermalResistance.layersResistance(model.layers, dir), 2)} m²·K/W")
                        groundU?.let { ValueRow("U équivalent calculé (EN ISO 13370)", "${Fmt.num(it, 3)} W/(m²·K)", emphasize = true) }
                        Text("Le U équivalent du terre-plein dépend de l'ensemble des planchers du bâtiment (B').",
                            style = MaterialTheme.typography.bodySmall)
                    } else if (model.layers.isNotEmpty()) {
                        val rt = ThermalResistance.totalResistance(model.layers, s.kind, s.boundary)
                        ValueRow("Résistance totale Rt (avec Rsi + Rse)", "${Fmt.num(rt, 2)} m²·K/W")
                        ValueRow("U calculé", "${Fmt.num(1.0 / rt, 3)} W/(m²·K)", emphasize = true)
                    }
                }
                SwitchRow("Imposer un coefficient U", s.uValueOverride != null, { on ->
                    // Pré-remplissage avec la valeur réellement utilisée par le bilan : imposer sans modifier ne change rien.
                    val prefill = if (isGroundFloor) groundU else model?.let { m -> SurfaceGeometry.resolveU(m.copy(uValueOverride = null)).value }
                    vm.update { it.copy(uValueOverride = if (on) (prefill ?: 1.0) else null) }
                }, description = "Valeur connue (étude, fiche technique, mesure) prioritaire sur la composition")
                if (s.uValueOverride != null) {
                    NumberField(if (isGroundFloor) "U équivalent" else "U", s.uValueOverride, { v -> vm.update { it.copy(uValueOverride = v) } },
                        unit = "W/(m²·K)", decimals = 3, min = 0.01, max = 10.0)
                    OriginSelector(s.uValueOrigin, { o -> vm.update { it.copy(uValueOrigin = o) } })
                }
            }

            if (s.kind == SurfaceKind.WALL || s.kind == SurfaceKind.ROOF) {
                SectionCard("Fenêtres et portes", icon = Icons.Filled.Window) {
                    details?.openings?.sortedBy { it.id }?.forEach { o -> OpeningEditor(o, vm) }
                    ActionRow {
                        OutlinedButton(onClick = { vm.addOpening(if (s.kind == SurfaceKind.ROOF) "toit_ancien" else "sv_bois", OpeningKind.WINDOW) }) {
                            Icon(Icons.Filled.Add, null); Spacer(Modifier.width(8.dp)); Text("Fenêtre")
                        }
                        OutlinedButton(onClick = { vm.addOpening("porte_bois_pleine", OpeningKind.DOOR) }) {
                            Icon(Icons.Filled.Add, null); Spacer(Modifier.width(8.dp)); Text("Porte")
                        }
                    }
                }
            }
            TextInput("Notes", s.notes, { v -> vm.update { it.copy(notes = v) } }, singleLine = false)
        }
    }

    addLayerInterior?.let { interior ->
        AddLayerDialog(state.materials, onDismiss = { addLayerInterior = null }) { materialId, thickness ->
            addLayerInterior = null
            vm.addLayer(materialId, thickness, interior)
        }
    }
    if (confirmDelete) {
        ConfirmDialog("Supprimer la paroi ?", "Ses couches et ses baies seront supprimées.", "Supprimer",
            onConfirm = { vm.delete { nav.popBackStack() } }, onDismiss = { confirmDelete = false }, destructive = true)
    }
}

@Composable
private fun BoundarySection(s: SurfaceEntity, siblings: List<RoomEntity>, vm: SurfaceEditViewModel) {
    when (s.boundary) {
        Boundary.UNHEATED -> SectionCard("Local non chauffé adjacent", subtitle = "Coefficient b = (θint − θu) / (θint − θe)") {
            DropdownField("Type de local", UnheatedSpacePresets.all, UnheatedSpacePresets.find(s.unheatedPresetCode), { "${it.label} – b = ${Fmt.num(it.b, 1)}" },
                { p -> vm.update { it.copy(unheatedPresetCode = p.code, unheatedFactorB = null) } })
            NumberField("Coefficient b", s.unheatedFactorB, { v -> vm.update { it.copy(unheatedFactorB = v) } }, decimals = 2, min = 0.0, max = 1.0,
                supportingText = "Saisie libre, prioritaire sur le type de local")
        }
        Boundary.HEATED_ROOM -> SectionCard("Pièce chauffée voisine") {
            DropdownField("Pièce de l'étude", listOf<RoomEntity?>(null) + siblings, siblings.firstOrNull { it.id == s.adjacentRoomId },
                { it?.let { r -> "${r.name} (${Fmt.temp(r.setpointC, 0)})" } ?: "Autre / non précisée" },
                { r -> vm.update { it.copy(adjacentRoomId = r?.id) } })
            if (s.adjacentRoomId == null) {
                NumberField("Température voisine", s.adjacentTemperatureC, { v -> vm.update { it.copy(adjacentTemperatureC = v) } }, unit = "°C",
                    decimals = 1, supportingText = "Espace voisin. Vide : consigne du bâtiment (échange nul)")
            }
        }
        Boundary.ADJACENT_BUILDING -> SectionCard("Logement ou bâtiment mitoyen") {
            NumberField("Température du mitoyen", s.adjacentTemperatureC, { v -> vm.update { it.copy(adjacentTemperatureC = v) } }, unit = "°C",
                decimals = 1, supportingText = "Valeur supposée. Vide : 15 °C")
        }
        Boundary.GROUND -> if (s.kind != SurfaceKind.FLOOR) {
            InfoBanner("Le contact avec le sol n'est calculé que pour les planchers : cette paroi est traitée comme donnant sur l'extérieur. " +
                "Choisissez « Extérieur » ou corrigez le type de paroi.", kind = BannerKind.WARNING)
        } else SectionCard("Contact avec le sol (EN ISO 13370)") {
            NumberField("Périmètre exposé", s.groundExposedPerimeterM, { v -> vm.update { it.copy(groundExposedPerimeterM = v) } }, unit = "m",
                decimals = 2, min = 0.0, supportingText = "Longueur des murs extérieurs bordant ce plancher")
            NumberField("Épaisseur des murs", s.groundWallThicknessM, { v -> vm.update { it.copy(groundWallThicknessM = v) } }, unit = "m", decimals = 2, min = 0.0,
                supportingText = "Murs périphériques du plancher")
            NumberField("Conductivité du sol", s.groundConductivity, { v -> vm.update { it.copy(groundConductivity = v) } }, unit = "W/(m·K)", decimals = 1,
                min = 0.5, max = 4.0, supportingText = "2,0 par défaut (argile, limon) ; 1,5 sable sec ; 3,5 roche")
            SwitchRow("Nappe phréatique à moins d'1 m", s.groundShallowWater, { v -> vm.update { it.copy(groundShallowWater = v) } })
        }
        Boundary.EXTERIOR -> Unit
    }
}

@Composable
private fun LayerRow(l: LayerEntity, materials: List<MaterialEntity>, kind: SurfaceKind, first: Boolean, last: Boolean, vm: SurfaceEditViewModel) {
    val material = materials.firstOrNull { it.id == l.materialId }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // Le nom du matériau garde toute la largeur ; les actions sont sur leur propre ligne.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(material?.name ?: "Matériau supprimé", Modifier.weight(1f), fontWeight = FontWeight.Medium)
                material?.let { Spacer(Modifier.width(8.dp)); OriginBadge(it.origin) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = { vm.moveLayer(l.id, -1) }, enabled = !first) { Icon(Icons.Filled.ArrowUpward, "Vers l'intérieur") }
                IconButton(onClick = { vm.moveLayer(l.id, 1) }, enabled = !last) { Icon(Icons.Filled.ArrowDownward, "Vers l'extérieur") }
                IconButton(onClick = { vm.deleteLayer(l.id) }) { Icon(Icons.Filled.Delete, "Supprimer la couche") }
            }
            FieldRow {
                NumberField("Épaisseur", l.thicknessM * 100, { v -> v?.let { vm.updateLayer(l.copy(thicknessM = it / 100)) } },
                    unit = "cm", decimals = 1, nullable = false, min = 0.1, max = 200.0)
                NumberField("λ spécifique", l.lambdaOverride, { v -> vm.updateLayer(l.copy(lambdaOverride = v)) },
                    unit = "W/(m·K)", decimals = 3, min = 0.005, max = 10.0, supportingText = "Vide : λ ${Fmt.num(material?.lambda, 3)}")
            }
            if (material != null) {
                val model = l.toModel(material.toModel())
                val r = ThermalResistance.layerResistance(model, kind.heatFlowDirection)
                Text("R = ${Fmt.num(r, 3)} m²·K/W" + (material.lambdaMin?.let { lo -> material.lambdaMax?.let { hi -> " · plage usuelle λ ${Fmt.num(lo, 3)}–${Fmt.num(hi, 3)}" } } ?: ""),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun AddLayerDialog(materials: List<MaterialEntity>, onDismiss: () -> Unit, onAdd: (Long, Double) -> Unit) {
    var selected by remember { mutableStateOf<MaterialEntity?>(null) }
    var thicknessCm by remember { mutableStateOf<Double?>(10.0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ajouter une couche") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DropdownField("Matériau", materials, selected, { "${it.category.label} – ${it.name} (λ ${Fmt.num(it.lambda, 3)})" }, { selected = it })
                NumberField("Épaisseur", thicknessCm, { thicknessCm = it }, unit = "cm", decimals = 1, min = 0.1, max = 200.0)
                selected?.let { Text("Source : ${it.source}", style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = selected != null && (thicknessCm ?: 0.0) > 0, onClick = { onAdd(selected!!.id, thicknessCm!! / 100) }) { Text("Ajouter") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@Composable
private fun OpeningEditor(o: OpeningEntity, vm: SurfaceEditViewModel) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${o.kind.label} – ${Fmt.area(o.widthM * o.heightM * o.count)}", Modifier.weight(1f), fontWeight = FontWeight.Medium)
                IconButton(onClick = { vm.deleteOpening(o.id) }) { Icon(Icons.Filled.Delete, "Supprimer") }
            }
            ChoiceChips(OpeningKind.entries, o.kind, { it.label }, { k -> vm.updateOpening(o.copy(kind = k)) })
            TextInput("Libellé", o.label, { v -> vm.updateOpening(o.copy(label = v)) })
            FieldRow(minFieldWidth = 100.dp) {
                NumberField("Largeur", o.widthM, { v -> v?.let { vm.updateOpening(o.copy(widthM = it)) } }, unit = "m", nullable = false, min = 0.05, max = 20.0)
                NumberField("Hauteur", o.heightM, { v -> v?.let { vm.updateOpening(o.copy(heightM = it)) } }, unit = "m", nullable = false, min = 0.05, max = 10.0)
            }
            IntField("Nombre", o.count, { v -> v?.let { vm.updateOpening(o.copy(count = it.coerceAtLeast(1))) } }, min = 1, max = 50, nullable = false)
            val presets = if (o.kind == OpeningKind.DOOR) OpeningPresets.doors else OpeningPresets.windows
            DropdownField("Type (valeur indicative)", presets, OpeningPresets.find(o.presetCode), { "${it.label} – U ≈ ${Fmt.num(it.uValue, 1)}" },
                { p -> vm.updateOpening(o.copy(presetCode = p.code, uValue = p.uValue, gValue = p.gValue, frameFraction = p.frameFraction,
                    uOrigin = fr.thermohabitat.thermal.model.ValueOrigin.ESTIMATED)) })
            // Unité longue : U et g en pleine largeur pour que la valeur saisie reste visible.
            NumberField("U", o.uValue, { v -> v?.let { vm.updateOpening(o.copy(uValue = it, presetCode = null)) } },
                unit = "W/(m²·K)", decimals = 2, nullable = false, min = 0.3, max = 7.0)
            if (o.kind != OpeningKind.DOOR) {
                NumberField("Facteur solaire g", o.gValue, { v -> v?.let { vm.updateOpening(o.copy(gValue = it)) } }, decimals = 2, nullable = false, min = 0.0, max = 1.0)
            }
            OriginSelector(o.uOrigin, { org -> vm.updateOpening(o.copy(uOrigin = org)) })
        }
    }
}
