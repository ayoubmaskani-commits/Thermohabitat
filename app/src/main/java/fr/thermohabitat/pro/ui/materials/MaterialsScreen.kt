package fr.thermohabitat.pro.ui.materials

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.thermohabitat.pro.data.db.MaterialEntity
import fr.thermohabitat.pro.data.repository.MaterialRepository
import fr.thermohabitat.pro.ui.components.ChoiceChips
import fr.thermohabitat.pro.ui.components.DropdownField
import fr.thermohabitat.pro.ui.components.InfoBanner
import fr.thermohabitat.pro.ui.components.NumberField
import fr.thermohabitat.pro.ui.components.OriginBadge
import fr.thermohabitat.pro.ui.components.OriginSelector
import fr.thermohabitat.pro.ui.components.ScreenScaffold
import fr.thermohabitat.pro.ui.components.ScrollColumn
import fr.thermohabitat.pro.ui.components.SectionCard
import fr.thermohabitat.pro.ui.components.SwitchRow
import fr.thermohabitat.pro.ui.components.TextInput
import fr.thermohabitat.pro.ui.util.Fmt
import fr.thermohabitat.thermal.model.MaterialCategory
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MaterialsViewModel @Inject constructor(private val repository: MaterialRepository) : ViewModel() {
    val materials = repository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val _messages = MutableSharedFlow<String>()
    val messages = _messages.asSharedFlow()

    fun save(m: MaterialEntity) = viewModelScope.launch { repository.update(m) }
    fun reset(id: Long) = viewModelScope.launch { repository.resetBuiltIn(id) }
    suspend fun create(base: MaterialEntity?): MaterialEntity? = repository.get(repository.createCustom(base))
    fun delete(id: Long) = viewModelScope.launch {
        if (!repository.deleteIfUnused(id)) _messages.emit("Matériau intégré ou utilisé dans une paroi : suppression impossible.")
    }
}

@Composable
fun MaterialsScreen(nav: NavController, vm: MaterialsViewModel = hiltViewModel()) {
    val materials by vm.materials.collectAsStateWithLifecycle()
    var category by remember { mutableStateOf<MaterialCategory?>(null) }
    var editing by remember { mutableStateOf<MaterialEntity?>(null) }
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    ScreenScaffold(
        title = "Matériaux",
        onBack = { nav.popBackStack() },
        snackbarHostState = snackbar,
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = {
                scope.launch { editing = vm.create(null) }
            }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Matériau personnalisé") })
        },
    ) { padding ->
        ScrollColumn(padding) {
            InfoBanner("Valeurs utiles usuelles (NF EN ISO 10456, Th-U, littérature). Les matériaux anciens (pisé, pierre, terre) sont très variables : ajustez λ si vous disposez d'une mesure ou d'une donnée déclarée.")
            ChoiceChips(listOf<MaterialCategory?>(null) + MaterialCategory.entries, category, { it?.label ?: "Tous" }, { category = it })
            val list = materials.filter { category == null || it.category == category }
            list.groupBy { it.category }.forEach { (cat, items) ->
                SectionCard(cat.label) {
                    items.forEach { m ->
                        Column(Modifier.fillMaxWidth().clickable { editing = m }.padding(vertical = 6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(m.name, Modifier.weight(1f), fontWeight = FontWeight.Medium)
                                OriginBadge(m.origin)
                            }
                            Text("λ ${Fmt.num(m.lambda, 3)} W/(m·K)" + (if (m.lambdaMin != null && m.lambdaMax != null) " (${Fmt.num(m.lambdaMin, 3)}–${Fmt.num(m.lambdaMax, 3)})" else "") +
                                " · ρ ${Fmt.num(m.density, 0)} kg/m³ · c ${Fmt.num(m.specificHeat, 0)} J/(kg·K)",
                                style = MaterialTheme.typography.bodySmall)
                            Text(if (m.builtIn) m.source else "Matériau personnalisé – ${m.source}", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    editing?.let { m ->
        MaterialEditDialog(
            m,
            onDismiss = { editing = null },
            onSave = { vm.save(it); editing = null },
            onReset = { vm.reset(m.id); editing = null },
            onDuplicate = { scope.launch { editing = vm.create(m) } },
            onDelete = { vm.delete(m.id); editing = null },
        )
    }
}

@Composable
private fun MaterialEditDialog(
    initial: MaterialEntity,
    onDismiss: () -> Unit,
    onSave: (MaterialEntity) -> Unit,
    onReset: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    var m by remember(initial.id) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.builtIn) "Matériau de la bibliothèque" else "Matériau personnalisé") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextInput("Nom", m.name, { m = m.copy(name = it) })
                DropdownField("Catégorie", MaterialCategory.entries, m.category, { it.label }, { m = m.copy(category = it) })
                NumberField("Conductivité λ", m.lambda, { v -> v?.let { m = m.copy(lambda = it) } }, unit = "W/(m·K)", decimals = 3, nullable = false, min = 0.005, max = 400.0)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("λ min", m.lambdaMin, { m = m.copy(lambdaMin = it) }, Modifier.weight(1f), decimals = 3, min = 0.0)
                    NumberField("λ max", m.lambdaMax, { m = m.copy(lambdaMax = it) }, Modifier.weight(1f), decimals = 3, min = 0.0)
                }
                NumberField("Masse volumique ρ", m.density, { v -> v?.let { m = m.copy(density = it) } }, unit = "kg/m³", decimals = 0, nullable = false, min = 1.0, max = 20000.0)
                NumberField("Capacité thermique c", m.specificHeat, { v -> v?.let { m = m.copy(specificHeat = it) } }, unit = "J/(kg·K)", decimals = 0, nullable = false, min = 100.0, max = 5000.0)
                SwitchRow("Matériau isolant", m.isInsulation, { m = m.copy(isInsulation = it) }, description = "Arrête le calcul de l'inertie utile (règle des 10 cm)")
                OriginSelector(m.origin, { m = m.copy(origin = it) })
                TextInput("Source / référence", m.source, { m = m.copy(source = it) })
                TextInput("Notes", m.notes, { m = m.copy(notes = it) }, singleLine = false)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onDuplicate) { Text("Dupliquer") }
                    if (initial.builtIn) TextButton(onClick = onReset) { Text("Valeurs d'origine") }
                    else TextButton(onClick = onDelete) { Text("Supprimer", color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(m) }) { Text("Enregistrer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
