package fr.thermohabitat.pro.ui.reports

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import fr.thermohabitat.pro.ui.components.ConfirmDialog
import fr.thermohabitat.pro.ui.components.EmptyState
import fr.thermohabitat.pro.ui.components.InfoBanner
import fr.thermohabitat.pro.ui.components.ScreenScaffold
import fr.thermohabitat.pro.ui.components.ScrollColumn
import fr.thermohabitat.pro.ui.components.SectionCard
import fr.thermohabitat.pro.ui.navigation.SettingsRoute
import fr.thermohabitat.pro.ui.util.FileActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
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
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    val saveLaunchers = mapOf(
        "application/pdf" to rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri -> uri?.let { u -> generated?.let { FileActions.copyTo(context, it.file, u) } } },
        "application/zip" to rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> uri?.let { u -> generated?.let { FileActions.copyTo(context, it.file, u) } } },
        "text/csv" to rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> uri?.let { u -> generated?.let { FileActions.copyTo(context, it.file, u) } } },
        "application/json" to rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let { u -> generated?.let { FileActions.copyTo(context, it.file, u) } } },
    )
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.import(it) } }

    ScreenScaffold(
        title = "Rapports et sauvegardes",
        isTab = true,
        snackbarHostState = snackbar,
        actions = { IconButton(onClick = { nav.navigate(SettingsRoute) }) { Icon(Icons.Filled.Settings, "Réglages") } },
    ) { padding ->
        ScrollColumn(padding) {
            busy?.let { msg ->
                Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(); Text("  $msg") }
            }
            generated?.let { g ->
                SectionCard("Fichier prêt : ${g.label}", subtitle = g.file.name) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { FileActions.open(context, g.file, g.mime) }) { Icon(Icons.Filled.Visibility, null); Text(" Ouvrir") }
                        OutlinedButton(onClick = { FileActions.share(context, g.file, g.mime) }) { Icon(Icons.Filled.Share, null); Text(" Partager") }
                    }
                    OutlinedButton(onClick = { saveLaunchers[g.mime]?.launch(g.file.name) }) { Icon(Icons.Filled.Download, null); Text(" Enregistrer sous…") }
                    Text("Le partage n'a lieu que si vous choisissez une application destinataire.", style = MaterialTheme.typography.bodySmall)
                }
            }

            val a = project
            if (a == null) {
                EmptyState("Aucune étude", "Créez ou importez une étude pour générer un rapport.")
            } else {
                SectionCard("Rapport PDF – ${a.project.entity.name}", icon = Icons.Filled.PictureAsPdf) {
                    ReportSection.entries.forEach { s ->
                        Row(Modifier.fillMaxWidth().clickable { vm.toggle(s) }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = s in sections, onCheckedChange = { vm.toggle(s) })
                            Text(s.label)
                        }
                    }
                    Button(enabled = busy == null && sections.isNotEmpty(), onClick = { vm.generatePdf() }) { Text("Générer le rapport") }
                }
                SectionCard("Exports", icon = Icons.Filled.Upload) {
                    OutlinedButton(enabled = busy == null, onClick = { vm.exportCsv(rooms = false) }) { Text("Inventaire des radiateurs (CSV)") }
                    OutlinedButton(enabled = busy == null, onClick = { vm.exportCsv(rooms = true) }) { Text("Bilan des pièces (CSV)") }
                    OutlinedButton(enabled = busy == null, onClick = { vm.exportZip() }) { Text("Sauvegarde complète avec photos (ZIP)") }
                    OutlinedButton(enabled = busy == null, onClick = { vm.exportJson() }) { Text("Projet seul (JSON)") }
                }
            }
            SectionCard("Importer une sauvegarde", icon = Icons.Filled.FolderOpen) {
                Text("Archive ZIP ou fichier JSON exportés par ThermoHabitat Pro. Le projet importé est ajouté sans modifier les projets existants.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedButton(enabled = busy == null, onClick = { importLauncher.launch(arrayOf("application/zip", "application/json", "application/octet-stream")) }) {
                    Text("Choisir un fichier…")
                }
            }
            SectionCard("Confidentialité", icon = Icons.Filled.Lock) {
                InfoBanner("L'application ne dispose d'aucune autorisation d'accès à Internet : vos données et photographies restent sur ce téléphone. " +
                    "La reconnaissance de texte et les calculs sont effectués localement. La sauvegarde automatique dans le cloud est désactivée ; " +
                    "seuls les exports que vous choisissez de partager quittent l'application.")
                if (a != null) {
                    OutlinedButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error)
                        Text(" Supprimer complètement ce projet", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
    if (confirmDelete) {
        ConfirmDialog("Supprimer définitivement le projet ?",
            "Toutes ses données (pièces, parois, radiateurs, scénarios, mesures) et toutes ses photographies seront effacées du téléphone. Cette action est irréversible.",
            "Supprimer", onConfirm = { vm.deleteProject() }, onDismiss = { confirmDelete = false }, destructive = true)
    }
}
