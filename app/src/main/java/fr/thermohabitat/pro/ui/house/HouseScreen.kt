package fr.thermohabitat.pro.ui.house

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.House
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.thermohabitat.pro.data.repository.ProjectRepository
import fr.thermohabitat.pro.domain.AnalyzedProject
import fr.thermohabitat.pro.domain.StudyService
import fr.thermohabitat.pro.ui.components.ActionRow
import fr.thermohabitat.pro.ui.components.BannerKind
import fr.thermohabitat.pro.ui.components.ChoiceChips
import fr.thermohabitat.pro.ui.components.DropdownField
import fr.thermohabitat.pro.ui.components.EmptyState
import fr.thermohabitat.pro.ui.components.FieldRow
import fr.thermohabitat.pro.ui.components.FloorPlan
import fr.thermohabitat.pro.ui.components.InfoBanner
import fr.thermohabitat.pro.ui.components.IntField
import fr.thermohabitat.pro.ui.components.KpiCard
import fr.thermohabitat.pro.ui.components.LegendDot
import fr.thermohabitat.pro.ui.components.NumberField
import fr.thermohabitat.pro.ui.components.PlanRoom
import fr.thermohabitat.pro.ui.components.ScreenScaffold
import fr.thermohabitat.pro.ui.components.ScrollColumn
import fr.thermohabitat.pro.ui.components.SectionCard
import fr.thermohabitat.pro.ui.components.StatusChip
import fr.thermohabitat.pro.ui.components.TextInput
import fr.thermohabitat.pro.ui.components.ValueRow
import fr.thermohabitat.pro.ui.components.readableOn
import fr.thermohabitat.pro.ui.components.statusColor
import fr.thermohabitat.pro.ui.navigation.BuildingEditRoute
import fr.thermohabitat.pro.ui.navigation.HomeRoute
import fr.thermohabitat.pro.ui.navigation.MaterialsRoute
import fr.thermohabitat.pro.ui.navigation.RoomEditRoute
import fr.thermohabitat.pro.ui.navigation.navigateToTab
import fr.thermohabitat.pro.ui.theme.LocalStatusColors
import fr.thermohabitat.pro.ui.util.Fmt
import fr.thermohabitat.thermal.calc.CoverageStatus
import fr.thermohabitat.thermal.model.RoomUsage
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HouseState(val loading: Boolean = true, val project: AnalyzedProject? = null)

@HiltViewModel
class HouseViewModel @Inject constructor(
    studyService: StudyService,
    private val repository: ProjectRepository,
) : ViewModel() {
    val state = studyService.observeActive().map { HouseState(false, it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HouseState())

    suspend fun addRoom(name: String, floor: Int, usage: RoomUsage, length: Double, width: Double): Long? {
        val p = state.value.project ?: return null
        return repository.addRoom(p.project.id, name, floor, length, width, usage = usage)
    }

    fun moveRoom(roomId: Long, x: Double, y: Double) = viewModelScope.launch { repository.moveRoomOnPlan(roomId, x, y) }
}

@Composable
fun HouseScreen(nav: NavController, vm: HouseViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var showAdd by remember { mutableStateOf(false) }
    val p = state.project

    ScreenScaffold(
        title = p?.project?.entity?.name ?: "Maison",
        isTab = true,
        actions = {
            if (p != null) IconButton(onClick = { nav.navigate(BuildingEditRoute(p.project.id)) }) { Icon(Icons.Filled.Edit, "Modifier le bâtiment") }
        },
        floatingActionButton = {
            if (p != null) ExtendedFloatingActionButton(onClick = { showAdd = true }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Ajouter une pièce") })
        },
    ) { padding ->
        if (!state.loading && p == null) {
            ScrollColumn(padding) {
                EmptyState("Aucune étude", "Créez d'abord une étude thermique depuis l'accueil.", actionLabel = "Aller à l'accueil",
                    onAction = { nav.navigateToTab(HomeRoute) })
            }
            return@ScreenScaffold
        }
        if (p == null) return@ScreenScaffold
        val hl = p.result.heatLoss
        val allRooms = p.project.building.rooms
        ScrollColumn(padding) {
            // Étude sans pièce : on guide vers la saisie au lieu d'afficher des résultats à zéro.
            if (allRooms.isEmpty()) {
                EmptyState("Aucune pièce", "Ajoutez les pièces chauffées (dimensions, parois, fenêtres). La puissance de chauffage sera calculée ensuite.",
                    actionLabel = "Ajouter une pièce", onAction = { showAdd = true })
                MaterialsLibraryButton { nav.navigate(MaterialsRoute) }
                return@ScrollColumn
            }

            // Résultat principal en tête.
            KpiCard("Puissance de chauffage nécessaire", Fmt.kw(hl.totalW), Modifier.fillMaxWidth(), icon = Icons.Filled.House,
                detail = "Par ${Fmt.temp(hl.designOutdoorC)} extérieur · ${Fmt.area(hl.heatedAreaM2)} chauffés")
            val incomplete = allRooms.count { it.surfaces.isEmpty() }
            if (incomplete > 0) {
                InfoBanner("$incomplete pièce(s) sans paroi : la puissance affichée est incomplète (air seulement pour ces pièces).",
                    kind = BannerKind.WARNING)
            }

            val floors = allRooms.map { it.floorLevel }.distinct().sorted()
            var floor by remember(floors) { mutableIntStateOf(floors.first()) }
            SectionCard("Plan simplifié", icon = Icons.Filled.Map, subtitle = "Touchez une pièce pour l'ouvrir · appui long puis glisser pour la déplacer") {
                ChoiceChips(floors, floor, { floorLabel(it) }, { floor = it })
                val coverage = p.result.studyCoverage
                val rooms = allRooms.filter { it.floorLevel == floor }.map { r ->
                    val c = coverage.rooms.firstOrNull { it.roomId == r.id }
                    PlanRoom(r.id, r.name, r.lengthM, r.widthM, r.planX, r.planY,
                        statusColor(c?.status ?: CoverageStatus.NO_NEED),
                        caption = c?.let {
                            when {
                                it.needW <= 0 -> "Pas de besoin"
                                // Radiateurs scannés mais pas encore validés : ne pas laisser croire qu'il n'y en a aucun.
                                it.status == CoverageStatus.NO_EMITTER ->
                                    "${Fmt.watts(it.needW)} · " + if (it.pendingRadiators > 0) "${it.pendingRadiators} à valider" else "sans radiateur"
                                // Légende courte : le pourcentage reste visible dans les petites pièces.
                                else -> "${Fmt.watts(it.needW)} · ${Fmt.pct(it.availableW / it.needW)}"
                            }
                        })
                }
                FloorPlan(rooms, onRoomClick = { nav.navigate(RoomEditRoute(it)) }, onRoomMoved = { id, x, y -> vm.moveRoom(id, x, y) })
                ActionRow {
                    val s = LocalStatusColors.current
                    LegendDot(s.sufficient, "Suffisant"); LegendDot(s.limit, "Limite")
                    LegendDot(s.insufficient, "Insuffisant ou sans radiateur validé"); LegendDot(s.neutral, "Pas de besoin")
                }
                Text("Couverture : part du besoin fournie par les radiateurs validés, avec une eau à ${coverage.supplyC.toInt()} °C.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            floors.forEach { level ->
                Text(floorLabel(level), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                allRooms.filter { it.floorLevel == level }.forEach { room ->
                    val rl = hl.room(room.id)
                    val cov = p.result.studyCoverage.rooms.firstOrNull { it.roomId == room.id }
                    val radiators = p.project.building.radiatorsIn(room.id)
                    Card(Modifier.fillMaxWidth().clickable { nav.navigate(RoomEditRoute(room.id)) }, shape = RoundedCornerShape(14.dp)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            // Nom seul sur sa ligne, pastille dessous : le nom n'est jamais écrasé.
                            Text(room.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            cov?.let { StatusChip(it.status) }
                            val parois = if (room.surfaces.isEmpty()) "" else " · ${room.surfaces.size} paroi(s)"
                            Text("${Fmt.area(room.floorAreaM2)} · ${Fmt.volume(room.volumeM3)} · consigne ${Fmt.temp(room.setpointC, 0)}$parois",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            ValueRow("Besoin de chauffage", rl?.let { "${Fmt.watts(it.totalW)} · ${Fmt.num(it.wattsPerM2, 0)} W/m²" } ?: "–", emphasize = true)
                            if (room.surfaces.isEmpty()) {
                                Text("Aucune paroi saisie : besoin incomplet", style = MaterialTheme.typography.bodySmall,
                                    color = readableOn(LocalStatusColors.current.limit))
                            }
                            val pending = cov?.pendingRadiators ?: 0
                            ValueRow(
                                if (radiators.isEmpty()) "Radiateurs" else "Disponible (${radiators.size} radiateur${if (radiators.size > 1) "s" else ""})",
                                when {
                                    radiators.isEmpty() -> "Aucun"
                                    pending > 0 -> "${Fmt.watts(cov?.availableW)} ($pending à valider)"
                                    else -> Fmt.watts(cov?.availableW)
                                },
                            )
                        }
                    }
                }
            }

            SectionCard("Détails du bâtiment", icon = Icons.Filled.House) {
                ValueRow("Surface chauffée (pièces)", Fmt.area(hl.heatedAreaM2))
                p.project.entity.declaredLivingAreaM2?.let { declared ->
                    ValueRow("Surface habitable déclarée", Fmt.area(declared))
                }
                ValueRow("Volume chauffé", Fmt.volume(hl.heatedVolumeM3))
                ValueRow("Température extérieure de base", Fmt.temp(hl.designOutdoorC))
                ValueRow("Déperdition par degré d'écart (H)", "${Fmt.num(hl.totalHWk, 1)} W/K")
                ValueRow("Somme des besoins pièce par pièce", Fmt.kw(hl.sumOfRoomLoadsW))
                Text("La somme par pièce sert à choisir les radiateurs ; la puissance du bâtiment sert à choisir la chaudière ou la PAC.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            MaterialsLibraryButton { nav.navigate(MaterialsRoute) }
        }
    }

    if (showAdd) {
        AddRoomDialog(
            defaultFloor = p?.project?.building?.rooms?.maxOfOrNull { it.floorLevel } ?: 0,
            onDismiss = { showAdd = false },
            onCreate = { name, floor, usage, l, w ->
                showAdd = false
                scope.launch { vm.addRoom(name, floor, usage, l, w)?.let { nav.navigate(RoomEditRoute(it)) } }
            },
        )
    }
}

/** Accès à la bibliothèque de matériaux, en bas de l'écran (action secondaire). */
@Composable
private fun MaterialsLibraryButton(onClick: () -> Unit) {
    OutlinedButton(onClick = onClick) {
        Icon(Icons.Filled.Layers, null); Spacer(Modifier.width(8.dp)); Text("Bibliothèque de matériaux")
    }
}

fun floorLabel(level: Int): String = when {
    level < 0 -> "Sous-sol ${-level}"
    level == 0 -> "Rez-de-chaussée"
    level == 1 -> "1er étage"
    else -> "${level}e étage"
}

@Composable
private fun AddRoomDialog(defaultFloor: Int, onDismiss: () -> Unit, onCreate: (String, Int, RoomUsage, Double, Double) -> Unit) {
    var name by remember { mutableStateOf("") }
    var usage by remember { mutableStateOf(RoomUsage.LIVING) }
    var floor by remember { mutableIntStateOf(defaultFloor) }
    var length by remember { mutableStateOf<Double?>(4.0) }
    var width by remember { mutableStateOf<Double?>(3.5) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nouvelle pièce") },
        text = {
            // Défilant : avec une police agrandie et le clavier ouvert, aucun champ n'est rogné.
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextInput("Nom", name, { name = it })
                DropdownField("Usage", RoomUsage.entries, usage, { it.label }, { usage = it; if (name.isBlank()) name = it.label })
                IntField("Niveau", floor, { v -> v?.let { floor = it } }, min = -3, max = 20, nullable = false,
                    supportingText = floorLabel(floor))
                FieldRow(minFieldWidth = 100.dp) {
                    NumberField("Longueur", length, { length = it }, unit = "m", min = 0.3, max = 100.0)
                    NumberField("Largeur", width, { width = it }, unit = "m", min = 0.3, max = 100.0)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = (length ?: 0.0) > 0 && (width ?: 0.0) > 0, onClick = {
                onCreate(name.ifBlank { usage.label }, floor, usage, length ?: 4.0, width ?: 3.5)
            }) { Text("Créer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
