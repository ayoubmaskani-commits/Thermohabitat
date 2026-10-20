package fr.thermohabitat.pro.ui.reports

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.thermohabitat.pro.data.export.BackupManager
import fr.thermohabitat.pro.data.export.CsvExporter
import fr.thermohabitat.pro.data.export.PdfReportGenerator
import fr.thermohabitat.pro.data.export.ReportSection
import fr.thermohabitat.pro.data.repository.ProjectRepository
import fr.thermohabitat.pro.data.repository.SettingsRepository
import fr.thermohabitat.pro.domain.StudyService
import fr.thermohabitat.pro.ui.components.BannerKind
import fr.thermohabitat.pro.ui.components.ConfirmDialog
import fr.thermohabitat.pro.ui.components.EmptyState
import fr.thermohabitat.pro.ui.components.InfoBanner
import fr.thermohabitat.pro.ui.components.ScreenScaffold
import fr.thermohabitat.pro.ui.components.SectionCard
import fr.thermohabitat.pro.ui.navigation.SettingsRoute
import fr.thermohabitat.pro.ui.util.FileActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/** Fichier produit, prêt à être ouvert, partagé ou enregistré. */
data class GeneratedFile(val file: File, val mime: String, val label: String)

@HiltViewModel
class ReportsViewModel @Inject constructor(
    studyService: StudyService,
    private val repository: ProjectRepository,
    private val backup: BackupManager,
    private val pdf: PdfReportGenerator,
    private val settings: SettingsRepository,
) : ViewModel() {
    val project = studyService.observeActive().stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val sections = MutableStateFlow(ReportSection.entries.toSet())
    val busy = MutableStateFlow<String?>(null)
    val generated = MutableStateFlow<GeneratedFile?>(null)
    private val _messages = MutableSharedFlow<String>()
    val messages = _messages.asSharedFlow()

    private fun run(label: String, block: suspend () -> GeneratedFile?) = viewModelScope.launch {
        busy.value = label
        runCatching { block() }
            .onSuccess { generated.value = it }
            .onFailure { _messages.emit("Échec : ${it.message ?: it.javaClass.simpleName}") }
        busy.value = null
    }

    fun toggle(section: ReportSection) {
        sections.value = if (section in sections.value) sections.value - section else sections.value + section
    }

    fun generatePdf() = run("Génération du rapport PDF…") {
        val a = project.value ?: return@run null
        val scenarios = repository.scenariosOf(a.project.id)
        GeneratedFile(pdf.generate(a.project, a.result, scenarios, sections.value), "application/pdf", "Rapport PDF")
    }

    fun exportCsv(rooms: Boolean) = run("Export CSV…") {
        val a = project.value ?: return@run null
        val text = if (rooms) CsvExporter.rooms(a.result) else CsvExporter.radiators(a.result)
        val f = File(backup.exportDir, "${BackupManager.safeName(a.project.entity.name)}_${if (rooms) "pieces" else "radiateurs"}.csv")
        withContext(Dispatchers.IO) { f.writeText(text, Charsets.UTF_8) }
        GeneratedFile(f, "text/csv", if (rooms) "Bilan des pièces (CSV)" else "Inventaire des radiateurs (CSV)")
    }

    fun exportZip() = run("Préparation de l'archive…") {
        val a = project.value ?: return@run null
        GeneratedFile(backup.zipToCache(a.project.id, a.project.entity.name), "application/zip", "Sauvegarde complète avec photos (ZIP)")
    }

    fun exportJson() = run("Export JSON…") {
        val a = project.value ?: return@run null
        GeneratedFile(backup.jsonToCache(a.project.id, a.project.entity.name), "application/json", "Projet (JSON, sans photos)")
    }

    fun import(uri: Uri) = viewModelScope.launch {
        busy.value = "Import de la sauvegarde…"
        runCatching { backup.import(uri) }
            .onSuccess { id -> settings.setActiveBuilding(id); _messages.emit("Projet importé et sélectionné.") }
            .onFailure { _messages.emit("Import impossible : ${it.message}") }
        busy.value = null
    }

    fun deleteProject() = viewModelScope.launch {
        val a = project.value ?: return@launch
        repository.deleteBuilding(a.project.id)
        settings.setActiveBuilding(null)
        generated.value = null
        _messages.emit("Projet et photographies supprimés du téléphone.")
    }
}

@Composable
fun ReportsScreen(nav: NavController, vm: ReportsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val project by vm.project.collectAsStateWithLifecycle()
    val sections by vm.sections.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val generated by vm.generated.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    var confirmDelete by remember { mutableStateOf(false) }
    var chooseSections by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    // Progression et fichier prêt s'affichent en haut de l'écran : on y remonte pour que l'action ait un effet visible.
    // L'état courant est ignoré (retour sur l'onglet, rotation) : seules les nouvelles actions font remonter.
    LaunchedEffect(Unit) {
        merge(vm.busy.drop(1), vm.generated.drop(1)).collect { if (it != null) scroll.animateScrollTo(0) }
    }

    // « Enregistrer sous » : le succès ou l'échec de la copie est signalé.
    val onSaveTarget: (Uri?) -> Unit = { uri ->
        val g = generated
        if (uri != null && g != null) {
            val ok = FileActions.copyTo(context, g.file, uri)
            scope.launch { snackbar.showSnackbar(if (ok) "Fichier enregistré : ${g.file.name}" else "Échec de l'enregistrement du fichier") }
        }
    }
    val saveLaunchers = mapOf(
        "application/pdf" to rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf"), onSaveTarget),
        "application/zip" to rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip"), onSaveTarget),
        "text/csv" to rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv"), onSaveTarget),
        "application/json" to rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json"), onSaveTarget),
    )
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.import(it) } }

    ScreenScaffold(
        title = "Rapports",
        isTab = true,
        snackbarHostState = snackbar,
        actions = { IconButton(onClick = { nav.navigate(SettingsRoute) }) { Icon(Icons.Filled.Settings, "Réglages") } },
    ) { padding ->
        // Mêmes marges que ScrollColumn, avec un état de défilement accessible (retour en haut après une action).
        Column(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()
                .verticalScroll(scroll).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            busy?.let { msg ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(msg, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                }
            }
            generated?.let { g ->
                SectionCard("Fichier prêt", subtitle = "${g.label} · ${g.file.name}", icon = Icons.Filled.CheckCircle) {
                    // Boutons pleine largeur, l'un sous l'autre : aucun libellé écrasé avec une police agrandie.
                    Button(onClick = { FileActions.share(context, g.file, g.mime) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Share, null, Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text("Partager")
                    }
                    OutlinedButton(onClick = { FileActions.open(context, g.file, g.mime) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Visibility, null, Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text("Ouvrir")
                    }
                    OutlinedButton(onClick = { saveLaunchers[g.mime]?.launch(g.file.name) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Download, null, Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text("Enregistrer sous…")
                    }
                    Text("Le partage n'a lieu que si vous choisissez une application destinataire.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            val a = project
            if (a == null) {
                EmptyState("Aucune étude", "Créez ou importez une étude pour générer un rapport.")
            } else {
                SectionCard("Rapport PDF", subtitle = a.project.entity.name, icon = Icons.Filled.PictureAsPdf) {
                    if (a.project.building.rooms.isEmpty()) {
                        InfoBanner("Ce projet ne contient encore aucune pièce : le rapport serait presque vide. Saisissez les pièces dans l'onglet Maison avant de le générer.",
                            kind = BannerKind.WARNING)
                    }
                    // L'action principale d'abord ; le choix des sections est replié.
                    Button(
                        enabled = busy == null && sections.isNotEmpty(),
                        onClick = { vm.generatePdf() },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    ) {
                        Icon(Icons.Filled.PictureAsPdf, null, Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text("Générer le rapport PDF")
                    }
                    if (sections.isEmpty()) {
                        Text("Sélectionnez au moins une section.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { chooseSections = !chooseSections }) {
                        Text(
                            if (chooseSections) "Masquer le choix des sections"
                            else "Choisir les sections (${sections.size} sur ${ReportSection.entries.size})",
                            Modifier.weight(1f, fill = false),
                        )
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Icon(if (chooseSections) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, Modifier.size(ButtonDefaults.IconSize))
                    }
                    if (chooseSections) {
                        ReportSection.entries.forEach { s ->
                            // Ligne entière cliquable, hauteur tactile minimale de 48 dp.
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .toggleable(value = s in sections, role = Role.Checkbox, onValueChange = { vm.toggle(s) }),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = s in sections, onCheckedChange = null)
                                Spacer(Modifier.width(12.dp))
                                Text(s.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
                SectionCard("Exports", icon = Icons.Filled.Upload) {
                    OutlinedButton(enabled = busy == null, onClick = { vm.exportCsv(rooms = false) }, modifier = Modifier.fillMaxWidth()) { Text("Inventaire des radiateurs (CSV)") }
                    OutlinedButton(enabled = busy == null, onClick = { vm.exportCsv(rooms = true) }, modifier = Modifier.fillMaxWidth()) { Text("Bilan des pièces (CSV)") }
                    OutlinedButton(enabled = busy == null, onClick = { vm.exportZip() }, modifier = Modifier.fillMaxWidth()) { Text("Sauvegarde complète avec photos (ZIP)") }
                    OutlinedButton(enabled = busy == null, onClick = { vm.exportJson() }, modifier = Modifier.fillMaxWidth()) { Text("Projet seul (JSON)") }
                }
            }
            SectionCard("Importer une sauvegarde", icon = Icons.Filled.FolderOpen) {
                Text("Archive ZIP ou fichier JSON exportés par ThermoHabitat Pro. Le projet importé est ajouté sans modifier les projets existants.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedButton(
                    enabled = busy == null,
                    onClick = { importLauncher.launch(arrayOf("application/zip", "application/json", "application/octet-stream")) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Choisir un fichier…")
                }
            }
            SectionCard("Confidentialité", icon = Icons.Filled.Lock) {
                InfoBanner("L'application ne dispose d'aucune autorisation d'accès à Internet : vos données et photographies restent sur ce téléphone. " +
                    "La reconnaissance de texte et les calculs sont effectués localement. La sauvegarde automatique dans le cloud est désactivée ; " +
                    "seuls les exports que vous choisissez de partager quittent l'application.")
                if (a != null) {
                    OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Delete, null, Modifier.size(ButtonDefaults.IconSize), tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text("Supprimer complètement ce projet", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            Spacer(Modifier.height(80.dp))
        }
    }
    if (confirmDelete) {
        ConfirmDialog("Supprimer définitivement le projet ?",
            "Toutes ses données (pièces, parois, radiateurs, scénarios, mesures) et toutes ses photographies seront effacées du téléphone. Cette action est irréversible.",
            "Supprimer", onConfirm = { vm.deleteProject() }, onDismiss = { confirmDelete = false }, destructive = true)
    }
}
