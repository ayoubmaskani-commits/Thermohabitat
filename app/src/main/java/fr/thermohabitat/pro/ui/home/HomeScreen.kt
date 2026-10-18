package fr.thermohabitat.pro.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.House
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.thermohabitat.pro.data.db.PhotoOwner
import fr.thermohabitat.pro.data.repository.ProjectRepository
import fr.thermohabitat.pro.data.repository.SettingsRepository
import fr.thermohabitat.pro.domain.AnalyzedProject
import fr.thermohabitat.pro.domain.StudyService
import fr.thermohabitat.pro.ui.components.BarEntry
import fr.thermohabitat.pro.ui.components.ChartPalette
import fr.thermohabitat.pro.ui.components.ConfirmDialog
import fr.thermohabitat.pro.ui.components.DonutChart
import fr.thermohabitat.pro.ui.components.DonutSlice
import fr.thermohabitat.pro.ui.components.EmptyState
import fr.thermohabitat.pro.ui.components.HorizontalBarChart
import fr.thermohabitat.pro.ui.components.InfoBanner
import fr.thermohabitat.pro.ui.components.KpiCard
import fr.thermohabitat.pro.ui.components.LargeActionButton
import fr.thermohabitat.pro.ui.components.ScreenScaffold
import fr.thermohabitat.pro.ui.components.ScrollColumn
import fr.thermohabitat.pro.ui.components.SectionCard
import fr.thermohabitat.pro.ui.components.StatusChip
import fr.thermohabitat.pro.ui.navigation.BuildingEditRoute
import fr.thermohabitat.pro.ui.navigation.CaptureRoute
import fr.thermohabitat.pro.ui.navigation.HouseRoute
import fr.thermohabitat.pro.ui.navigation.SettingsRoute
import fr.thermohabitat.pro.ui.theme.LocalStatusColors
import fr.thermohabitat.pro.ui.util.Fmt
import fr.thermohabitat.thermal.PacCompatibility
import fr.thermohabitat.thermal.StudySettings
import fr.thermohabitat.thermal.calc.CoverageStatus
import fr.thermohabitat.thermal.library.SampleProjects
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeState(
    val loading: Boolean = true,
    val projects: List<AnalyzedProject> = emptyList(),
    val activeId: Long? = null,
) {
    val active: AnalyzedProject? get() = projects.firstOrNull { it.project.id == activeId } ?: projects.firstOrNull()
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    studyService: StudyService,
    private val repository: ProjectRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    val state = combine(studyService.observeAll(), settings.activeBuildingId) { projects, active ->
        HomeState(false, projects, active)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeState())

    suspend fun createStudy(): Long {
        val id = repository.createBuilding("Nouvelle étude thermique")
        settings.setActiveBuilding(id)
        return id
    }

    /** Prépare une fiche radiateur brouillon dans le projet actif et renvoie la route de capture. */
    suspend fun startScan(buildingId: Long): CaptureRoute {
        val radiatorId = repository.createRadiatorDraft(buildingId)
        return CaptureRoute(PhotoOwner.RADIATOR.name, radiatorId, buildingId)
    }

    fun select(id: Long) = viewModelScope.launch { settings.setActiveBuilding(id) }

    fun delete(id: Long) = viewModelScope.launch {
        repository.deleteBuilding(id)
        if (state.value.activeId == id) settings.setActiveBuilding(null)
    }

    fun loadSample() = viewModelScope.launch {
        val sample = SampleProjects.pise1870()
        val id = repository.insertGraph(sample, StudySettings())
        val defaults = repository.loadProject(id)?.entity?.let { repository.defaultSettings(it) } ?: StudySettings()
        repository.updateSettings(id) { defaults }
        settings.setActiveBuilding(id)
    }
}

@Composable
fun HomeScreen(nav: NavController, vm: HomeViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var askCreateForScan by remember { mutableStateOf(false) }
    var deleteId by remember { mutableStateOf<Long?>(null) }

    ScreenScaffold(
        title = "ThermoHabitat Pro",
        isTab = true,
        actions = { IconButton(onClick = { nav.navigate(SettingsRoute) }) { Icon(Icons.Filled.Settings, "Réglages") } },
    ) { padding ->
        ScrollColumn(padding) {
            LargeActionButton("Créer une étude thermique", Icons.Filled.Add, onClick = {
                scope.launch { val id = vm.createStudy(); nav.navigate(BuildingEditRoute(id)) }
            })
            LargeActionButton("Scanner un radiateur", Icons.Filled.CameraAlt, outlined = true, onClick = {
                val active = state.active
                if (active == null) askCreateForScan = true
                else scope.launch { nav.navigate(vm.startScan(active.project.id)) }
            })

            if (!state.loading && state.projects.isEmpty()) {
                EmptyState(
                    title = "Aucune étude enregistrée",
                    message = "Créez votre maison pièce par pièce, ou chargez le projet d'exemple « maison 1870 en pisé » pour découvrir l'application.",
                    actionLabel = "Charger le projet d'exemple",
                    onAction = { vm.loadSample() },
                )
            }

            state.active?.let { ActiveProjectDashboard(it, onOpenHouse = { nav.navigate(HouseRoute) }) }

            if (state.projects.isNotEmpty()) {
                Text("Projets enregistrés", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                state.projects.forEach { p ->
                    ProjectCard(
                        p, active = p.project.id == state.active?.project?.id,
                        onClick = { vm.select(p.project.id) },
                        onEdit = { vm.select(p.project.id); nav.navigate(BuildingEditRoute(p.project.id)) },
                        onDelete = { deleteId = p.project.id },
                    )
                }
                TextButton(onClick = { vm.loadSample() }) { Text("Ajouter le projet d'exemple (maison 1870 en pisé)") }
            }
        }
    }

    if (askCreateForScan) {
        AlertDialog(
            onDismissRequest = { askCreateForScan = false },
            title = { Text("Aucun projet") },
            text = { Text("Un radiateur est toujours rattaché à une étude. Créer une nouvelle étude pour y enregistrer ce radiateur ?") },
            confirmButton = {
                TextButton(onClick = {
                    askCreateForScan = false
                    scope.launch { val id = vm.createStudy(); nav.navigate(vm.startScan(id)) }
                }) { Text("Créer et scanner") }
            },
            dismissButton = { TextButton(onClick = { askCreateForScan = false }) { Text("Annuler") } },
        )
    }
    deleteId?.let { id ->
        ConfirmDialog(
            title = "Supprimer le projet ?",
            message = "Toutes les données du projet (pièces, radiateurs, photographies, scénarios) seront définitivement effacées du téléphone.",
            confirmLabel = "Supprimer",
            destructive = true,
            onConfirm = { vm.delete(id) },
            onDismiss = { deleteId = null },
        )
    }
}

@Composable
private fun ActiveProjectDashboard(p: AnalyzedProject, onOpenHouse: () -> Unit) {
    val r = p.result
    val hl = r.heatLoss
    val status = LocalStatusColors.current
    val coverage = r.studyCoverage
    val coverageColor = when {
        coverage.coveredFraction >= 0.999 -> status.sufficient
        coverage.coveredFraction >= 0.9 -> status.limit
        else -> status.insufficient
    }
    SectionCard(
        title = p.project.entity.name,
        subtitle = listOfNotNull(p.project.entity.commune.ifBlank { null }, p.project.entity.constructionYear?.let { "construit en $it" },
            "θe = ${Fmt.temp(hl.designOutdoorC)}").joinToString(" · "),
        icon = Icons.Filled.House,
        action = { TextButton(onClick = onOpenHouse) { Text("Ouvrir") } },
    ) {
        val cards = listOf(
            Triple("Surface habitable", Fmt.area(hl.heatedAreaM2), Icons.Filled.Straighten),
            Triple("Volume chauffé", Fmt.volume(hl.heatedVolumeM3), Icons.Filled.Layers),
            Triple("Puissance nécessaire", Fmt.kw(hl.totalW), Icons.Filled.Bolt),
            Triple("Pièces", "${p.project.building.rooms.size}", Icons.Filled.House),
            Triple("Radiateurs validés", "${r.validatedRadiatorCount} / ${r.radiatorCount}", Icons.Filled.ViewColumn),
            Triple("Besoins couverts à ${coverage.supplyC.toInt()} °C", Fmt.pct(coverage.coveredFraction), Icons.Filled.CheckCircle),
            Triple("Départ nécessaire", r.supply.requiredSupplyC?.let { Fmt.temp(it) } ?: "> 90 °C ou non couvert", Icons.Filled.Thermostat),
            Triple("Compatibilité PAC", r.compatibility.label, Icons.Filled.Science),
        )
        cards.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEachIndexed { i, (t, v, icon) ->
                    val accent = when {
                        t.startsWith("Besoins") -> coverageColor
                        t.startsWith("Compatibilité") -> compatibilityColor(r.compatibility)
                        else -> null
                    }
                    KpiCard(t, v, Modifier.weight(1f), icon = icon, accent = accent,
                        detail = if (t == "Puissance nécessaire") "${Fmt.num(hl.wattsPerM2, 0)} W/m² · H = ${Fmt.num(hl.totalHWk, 0)} W/K" else null)
                }
                if (row.size == 1) androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            }
        }
        if (hl.uncertainShare > 0.3) {
            InfoBanner("${Fmt.pct(hl.uncertainShare)} des déperditions par les parois reposent sur des valeurs estimées ou inconnues.",
                kind = fr.thermohabitat.pro.ui.components.BannerKind.WARNING)
        }
    }

    if (hl.rooms.isNotEmpty()) {
        SectionCard("Répartition des déperditions", subtitle = "Touchez un poste pour le mettre en évidence") {
            val slices = hl.byCategoryW.entries.filter { it.value > 0.5 }.mapIndexed { i, (k, v) ->
                DonutSlice(k.label, v, ChartPalette[i % ChartPalette.size])
            }
            DonutChart(slices, centerLabel = Fmt.kw(hl.totalW))
        }
        SectionCard("Déperditions par pièce", subtitle = "Touchez une barre pour le détail") {
            HorizontalBarChart(hl.rooms.sortedByDescending { it.totalW }.map { room ->
                val cov = r.studyCoverage.rooms.firstOrNull { it.roomId == room.roomId }
                BarEntry(room.roomName, room.totalW,
                    detail = "${Fmt.area(room.floorAreaM2)} · ${Fmt.num(room.wattsPerM2, 0)} W/m² · disponible à ${r.studyCoverage.supplyC.toInt()} °C : ${Fmt.watts(cov?.availableW)}")
            })
        }
        SectionCard("Couverture selon le régime d'eau", subtitle = "Part des besoins couverts par les radiateurs validés") {
            HorizontalBarChart(
                r.regimeCoverages.map { c ->
                    val color = when {
                        c.coveredFraction >= 0.999 -> status.sufficient
                        c.coveredFraction >= 0.9 -> status.limit
                        else -> status.insufficient
                    }
                    BarEntry("${c.supplyC.toInt()}/${c.returnC?.toInt() ?: "–"} °C", c.coveredFraction, color,
                        "Puissance disponible ${Fmt.watts(c.totalAvailableW)} pour ${Fmt.watts(c.totalNeedW)} de besoins")
                },
                valueFormatter = { Fmt.pct(it) },
            )
        }
    }
}

@Composable
private fun compatibilityColor(c: PacCompatibility) = when (c) {
    PacCompatibility.VERY_FAVORABLE, PacCompatibility.FAVORABLE -> LocalStatusColors.current.sufficient
    PacCompatibility.POSSIBLE -> LocalStatusColors.current.limit
    PacCompatibility.UNFAVORABLE -> LocalStatusColors.current.insufficient
    PacCompatibility.UNDETERMINED -> LocalStatusColors.current.neutral
}

@Composable
private fun ProjectCard(p: AnalyzedProject, active: Boolean, onClick: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val r = p.result
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = if (active) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(p.project.entity.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(listOfNotNull(p.project.entity.commune.ifBlank { null }, "modifié le ${Fmt.date(p.project.entity.updatedAt)}").joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Actions") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Modifier le bâtiment") }, leadingIcon = { Icon(Icons.Filled.Edit, null) }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text("Supprimer le projet") }, leadingIcon = { Icon(Icons.Filled.Delete, null) }, onClick = { menu = false; onDelete() })
                }
            }
            Text("${Fmt.area(r.heatLoss.heatedAreaM2)} · ${Fmt.volume(r.heatLoss.heatedVolumeM3)} · ${p.project.building.rooms.size} pièces · ${r.radiatorCount} radiateurs",
                style = MaterialTheme.typography.bodyMedium)
            Text("Besoin ${Fmt.kw(r.heatLoss.totalW)} · couverture ${Fmt.pct(r.studyCoverage.coveredFraction)} à ${r.studyCoverage.supplyC.toInt()} °C · départ ${r.supply.requiredSupplyC?.let { Fmt.temp(it, 0) } ?: "–"}",
                style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusChip(
                    when (r.compatibility) {
                        PacCompatibility.VERY_FAVORABLE, PacCompatibility.FAVORABLE -> CoverageStatus.SUFFICIENT
                        PacCompatibility.POSSIBLE -> CoverageStatus.LIMIT
                        PacCompatibility.UNFAVORABLE -> CoverageStatus.INSUFFICIENT
                        PacCompatibility.UNDETERMINED -> CoverageStatus.NO_NEED
                    },
                    text = "PAC : ${r.compatibility.label}",
                )
                if (active) StatusChip(CoverageStatus.NO_NEED, text = "Projet actif")
            }
        }
    }
}
