package fr.thermohabitat.pro.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.PieChart
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
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
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
import fr.thermohabitat.pro.ui.components.ActionRow
import fr.thermohabitat.pro.ui.components.BannerKind
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
import fr.thermohabitat.pro.ui.navigation.RadiatorsRoute
import fr.thermohabitat.pro.ui.navigation.SettingsRoute
import fr.thermohabitat.pro.ui.navigation.navigateToTab
import fr.thermohabitat.pro.ui.theme.LocalStatusColors
import fr.thermohabitat.pro.ui.util.Fmt
import fr.thermohabitat.thermal.PacCompatibility
import fr.thermohabitat.thermal.StudySettings
import fr.thermohabitat.thermal.calc.BuildingCoverage
import fr.thermohabitat.thermal.calc.CoverageAnalyzer
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

            // Clé = projet : l'état local des graphiques (sélection) repart de zéro quand on change de projet.
            state.active?.let { a ->
                androidx.compose.runtime.key(a.project.id) {
                    ActiveProjectDashboard(a, onOpenHouse = { nav.navigateToTab(HouseRoute) }, onOpenRadiators = { nav.navigateToTab(RadiatorsRoute) })
                }
            }

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

/**
 * Même règle que le statut des pièces (CoverageAnalyzer) appliquée au bâtiment : vert seulement si la borne
 * basse couvre chaque pièce, rouge si moins de 90 % des besoins sont couverts, orange sinon.
 */
@Composable
private fun coverageColor(c: BuildingCoverage): androidx.compose.ui.graphics.Color {
    val s = LocalStatusColors.current
    if (c.totalNeedW <= 0.0) return s.neutral
    return when {
        c.rooms.all { it.status == CoverageStatus.SUFFICIENT || it.status == CoverageStatus.NO_NEED } -> s.sufficient
        c.coveredFraction < CoverageAnalyzer.INSUFFICIENT_THRESHOLD -> s.insufficient
        else -> s.limit
    }
}

/** Indicateur de la grille du tableau de bord. */
private data class Kpi(
    val title: String,
    val value: String,
    val icon: ImageVector,
    val detail: String? = null,
    val accent: Color? = null,
)

/**
 * Indicateurs deux par ligne (hauteurs égales) si chaque carte garde au moins 140 dp agrandis avec la police
 * système ; sinon un par ligne, pour que les valeurs ne soient jamais coupées au milieu des mots.
 */
@Composable
private fun KpiGrid(items: List<Kpi>) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = 10.dp
        val twoColumns = (maxWidth - gap) / 2 >= 140.dp * fontScale
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            if (twoColumns) {
                items.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(gap)) {
                        row.forEach { k ->
                            KpiCard(k.title, k.value, Modifier.weight(1f).fillMaxHeight(), icon = k.icon, detail = k.detail, accent = k.accent)
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            } else {
                items.forEach { k -> KpiCard(k.title, k.value, Modifier.fillMaxWidth(), icon = k.icon, detail = k.detail, accent = k.accent) }
            }
        }
    }
}

/** « 1 pièce », « 3 pièces » (singulier pour 0 et 1). */
private fun plural(n: Int, word: String) = "$n ${if (n > 1) "${word}s" else word}"

@Composable
private fun ActiveProjectDashboard(p: AnalyzedProject, onOpenHouse: () -> Unit, onOpenRadiators: () -> Unit) {
    val r = p.result
    val hl = r.heatLoss
    val coverage = r.studyCoverage
    val coverageColor = coverageColor(coverage)
    SectionCard(
        title = p.project.entity.name,
        subtitle = listOfNotNull(p.project.entity.commune.ifBlank { null }, p.project.entity.constructionYear?.let { "construit en $it" },
            "θe = ${Fmt.temp(hl.designOutdoorC)}").joinToString(" · "),
        icon = Icons.Filled.House,
        action = { TextButton(onClick = onOpenHouse) { Text("Ouvrir") } },
    ) {
        if (p.project.building.rooms.isEmpty()) {
            // Étude sans pièce : les indicateurs vaudraient tous zéro ; on indique la prochaine étape.
            InfoBanner("Étape suivante : saisissez les pièces (dimensions et parois) pour obtenir la puissance de chauffage nécessaire.")
            Button(onClick = onOpenHouse) { Text("Saisir les pièces") }
            return@SectionCard
        }
        val noRadiator = r.validatedRadiatorCount == 0
        // L'essentiel d'abord, sur toute la largeur : les valeurs en toutes lettres y restent lisibles.
        KpiCard("Puissance nécessaire", Fmt.kw(hl.totalW), Modifier.fillMaxWidth(), icon = Icons.Filled.Bolt,
            detail = "${Fmt.num(hl.wattsPerM2, 0)} W/m² · H = ${Fmt.num(hl.totalHWk, 0)} W/K")
        if (noRadiator) {
            // Sans radiateur validé, couverture et compatibilité restent indéterminées : on indique quoi faire.
            InfoBanner(
                if (r.radiatorCount == 0) "Aucun radiateur saisi : scannez ou ajoutez les radiateurs pour savoir s'ils couvrent les besoins et si une PAC convient."
                else "Aucun radiateur validé : vérifiez et validez les radiateurs saisis pour savoir s'ils couvrent les besoins et si une PAC convient.",
            )
            OutlinedButton(onClick = onOpenRadiators) { Text("Voir les radiateurs") }
        }
        KpiCard("Compatibilité PAC", r.compatibility.label, Modifier.fillMaxWidth(), icon = Icons.Filled.Science,
            accent = compatibilityColor(r.compatibility), detail = r.compatibility.description)
        KpiCard("Température de départ nécessaire", Fmt.supply(r.supply), Modifier.fillMaxWidth(), icon = Icons.Filled.Thermostat)
        KpiGrid(
            listOf(
                // Espace insécable : « 55 °C » ne se coupe pas dans le titre (deux lignes au plus).
                Kpi("Couverture à ${coverage.supplyC.toInt()}\u00A0°C", if (noRadiator) "–" else Fmt.coverage(coverage), Icons.Filled.PieChart,
                    detail = if (noRadiator) "Aucun radiateur validé" else "Part des besoins couverts",
                    accent = if (noRadiator) null else coverageColor),
                Kpi("Radiateurs validés", "${r.validatedRadiatorCount} / ${r.radiatorCount}", Icons.Filled.ViewColumn),
                Kpi("Surface habitable", Fmt.area(hl.heatedAreaM2), Icons.Filled.Straighten, detail = plural(p.project.building.rooms.size, "pièce")),
                Kpi("Volume chauffé", Fmt.volume(hl.heatedVolumeM3), Icons.Filled.Layers),
            ),
        )
        if (hl.uncertainShare > 0.3) {
            InfoBanner("${Fmt.pct(hl.uncertainShare)} des déperditions par les parois reposent sur des valeurs estimées ou inconnues.",
                kind = BannerKind.WARNING)
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
        // Sans radiateur validé, toutes les barres vaudraient 0 % : la carte d'accueil explique déjà la marche à suivre.
        if (r.validatedRadiatorCount > 0) {
            SectionCard("Couverture selon le régime d'eau", subtitle = "Part des besoins couverts par les radiateurs validés") {
                HorizontalBarChart(
                    r.regimeCoverages.map { c ->
                        val color = coverageColor(c)
                        BarEntry("${c.supplyC.toInt()}/${c.returnC?.toInt() ?: "–"} °C", c.coveredFraction, color,
                            "Puissance disponible ${Fmt.watts(c.totalAvailableW)} pour ${Fmt.watts(c.totalNeedW)} de besoins")
                    },
                    maxValue = 1.0,
                    valueFormatter = { Fmt.pct(it) },
                )
            }
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
            if (p.project.building.rooms.isEmpty()) {
                // Pas de « 0,00 kW » ni de couverture sans objet : l'étude reste à compléter.
                Text("Étude à compléter : aucune pièce saisie", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("${Fmt.area(r.heatLoss.heatedAreaM2)} · ${Fmt.volume(r.heatLoss.heatedVolumeM3)} · ${plural(p.project.building.rooms.size, "pièce")} · ${plural(r.radiatorCount, "radiateur")}",
                    style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (r.validatedRadiatorCount == 0) "Besoin ${Fmt.kw(r.heatLoss.totalW)} · aucun radiateur validé"
                    else "Besoin ${Fmt.kw(r.heatLoss.totalW)} · couverture ${Fmt.coverage(r.studyCoverage)} à ${r.studyCoverage.supplyC.toInt()} °C · départ ${r.supply.requiredSupplyC?.let { Fmt.temp(it, 0) } ?: "–"}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            // Puces sur plusieurs lignes : « Projet actif » n'est plus écrasée par une longue puce PAC.
            ActionRow {
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
