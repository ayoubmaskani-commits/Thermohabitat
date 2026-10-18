package fr.thermohabitat.pro.ui.building

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.House
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.thermohabitat.pro.data.db.BuildingEntity
import fr.thermohabitat.pro.data.repository.ProjectRepository
import fr.thermohabitat.pro.di.ApplicationScope
import fr.thermohabitat.pro.ui.components.BannerKind
import fr.thermohabitat.pro.ui.components.ChoiceChips
import fr.thermohabitat.pro.ui.components.DropdownField
import fr.thermohabitat.pro.ui.components.InfoBanner
import fr.thermohabitat.pro.ui.components.IntField
import fr.thermohabitat.pro.ui.components.NumberField
import fr.thermohabitat.pro.ui.components.OriginSelector
import fr.thermohabitat.pro.ui.components.ScreenScaffold
import fr.thermohabitat.pro.ui.components.ScrollColumn
import fr.thermohabitat.pro.ui.components.SectionCard
import fr.thermohabitat.pro.ui.components.TextInput
import fr.thermohabitat.pro.ui.navigation.BuildingEditRoute
import fr.thermohabitat.pro.ui.navigation.HouseRoute
import fr.thermohabitat.pro.ui.navigation.navigateToTab
import fr.thermohabitat.pro.ui.util.AutoSaver
import fr.thermohabitat.pro.ui.util.Fmt
import fr.thermohabitat.thermal.library.ClimateReferences
import fr.thermohabitat.thermal.model.AirtightnessPreset
import fr.thermohabitat.thermal.model.ConstructionType
import fr.thermohabitat.thermal.model.HeatingSystem
import fr.thermohabitat.thermal.model.InsulationType
import fr.thermohabitat.thermal.model.JoineryType
import fr.thermohabitat.thermal.model.Shielding
import fr.thermohabitat.thermal.model.ValueOrigin
import fr.thermohabitat.thermal.model.VentilationType
import fr.thermohabitat.thermal.model.WallType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Valeurs indicatives proposées selon l'époque et le type de bâti (jamais des mesures). */
object IndicativeDefaults {
    fun n50For(year: Int?, joinery: JoineryType): Double = when {
        joinery == JoineryType.SINGLE_GLAZING -> AirtightnessPreset.VERY_LEAKY.n50
        year == null -> AirtightnessPreset.LEAKY.n50
        year < 1975 -> AirtightnessPreset.LEAKY.n50
        year < 2000 -> AirtightnessPreset.MEDIUM.n50
        year < 2012 -> 4.0
        else -> AirtightnessPreset.TIGHT.n50
    }

    fun thermalBridgesFor(year: Int?, insulation: InsulationType): Double = when {
        insulation == InsulationType.EXTERIOR -> 0.05
        insulation == InsulationType.INTERIOR || insulation == InsulationType.INTERIOR_PARTIAL -> 0.15
        year != null && year >= 2012 -> 0.05
        else -> 0.10
    }

    fun ceilingHeightFor(year: Int?): Double = when {
        year == null -> 2.5
        year < 1950 -> 2.8
        else -> 2.5
    }
}

@HiltViewModel
class BuildingEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: ProjectRepository,
    @ApplicationScope appScope: CoroutineScope,
) : ViewModel() {
    val buildingId = savedStateHandle.toRoute<BuildingEditRoute>().buildingId
    private val _form = MutableStateFlow<BuildingEntity?>(null)
    val form = _form.asStateFlow()
    private val saver = AutoSaver<BuildingEntity>(viewModelScope, appScope) { repository.updateBuilding(it) }

    init {
        viewModelScope.launch { _form.value = repository.loadProject(buildingId)?.entity }
    }

    fun update(transform: (BuildingEntity) -> BuildingEntity) {
        val current = _form.value ?: return
        val next = transform(current)
        _form.value = next
        saver.submit(next)
    }

    /** Applique une station climatique de référence corrigée de l'altitude (valeurs estimées). */
    fun applyClimate(city: String) = update { b ->
        val ref = ClimateReferences.find(city) ?: return@update b
        val c = ClimateReferences.climateFor(ref, b.altitudeM)
        b.copy(
            climateReference = c.referenceName, designOutdoorTempC = c.designOutdoorTempC, designOutdoorOrigin = ValueOrigin.ESTIMATED,
            meanAnnualOutdoorTempC = c.meanAnnualOutdoorTempC, degreeDays = c.degreeDays, degreeDaysOrigin = ValueOrigin.ESTIMATED,
        )
    }

    fun applyIndicativeDefaults() = update { b ->
        b.copy(
            n50 = IndicativeDefaults.n50For(b.constructionYear, b.joineryType), n50Origin = ValueOrigin.ESTIMATED,
            thermalBridgeDeltaU = IndicativeDefaults.thermalBridgesFor(b.constructionYear, b.insulationType),
            ceilingHeightM = if (b.ceilingHeightM == 2.5) IndicativeDefaults.ceilingHeightFor(b.constructionYear) else b.ceilingHeightM,
        )
    }

    override fun onCleared() { saver.flush() }
}

@Composable
fun BuildingEditScreen(nav: NavController, vm: BuildingEditViewModel = hiltViewModel()) {
    val form by vm.form.collectAsStateWithLifecycle()
    ScreenScaffold(title = "Bâtiment", onBack = { nav.popBackStack() }) { padding ->
        val b = form ?: return@ScreenScaffold
        ScrollColumn(padding) {
            InfoBanner("Les modifications sont enregistrées automatiquement. Les valeurs proposées par l'application sont indicatives : elles ne remplacent pas des mesures.")

            SectionCard("Identification du projet", icon = Icons.Filled.House) {
                TextInput("Nom du projet", b.name, { v -> vm.update { it.copy(name = v) } })
                TextInput("Adresse (facultative)", b.address, { v -> vm.update { it.copy(address = v) } })
                TextInput("Commune", b.commune, { v -> vm.update { it.copy(commune = v) } })
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    IntField("Année de construction", b.constructionYear, { v -> vm.update { it.copy(constructionYear = v) } }, Modifier.weight(1f), min = 1000, max = 2100)
                    IntField("Nombre d'étages", b.floorsCount, { v -> v?.let { n -> vm.update { it.copy(floorsCount = n) } } }, Modifier.weight(1f), min = 1, max = 20, nullable = false)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumberField("Surface habitable déclarée", b.declaredLivingAreaM2, { v -> vm.update { it.copy(declaredLivingAreaM2 = v) } },
                        Modifier.weight(1f), unit = "m²", decimals = 1, min = 0.0)
                    NumberField("Hauteur sous plafond", b.ceilingHeightM, { v -> vm.update { it.copy(ceilingHeightM = v ?: 2.5) } },
                        Modifier.weight(1f), unit = "m", nullable = false, min = 1.5, max = 10.0)
                }
            }

            SectionCard("Construction", icon = Icons.Filled.Tune) {
                DropdownField("Type de construction", ConstructionType.entries, b.constructionType, { it.label }, { v -> vm.update { it.copy(constructionType = v) } })
                DropdownField("Type de murs", WallType.entries, b.wallType, { it.label }, { v -> vm.update { it.copy(wallType = v) } })
                DropdownField("Isolation", InsulationType.entries, b.insulationType, { it.label }, { v -> vm.update { it.copy(insulationType = v) } })
                DropdownField("Menuiseries", JoineryType.entries, b.joineryType, { it.label }, { v -> vm.update { it.copy(joineryType = v) } })
                DropdownField("Chauffage existant", HeatingSystem.entries, b.existingHeating, { it.label }, { v -> vm.update { it.copy(existingHeating = v) } })
                OutlinedButton(onClick = { vm.applyIndicativeDefaults() }) { Text("Proposer des valeurs indicatives (étanchéité, ponts thermiques)") }
            }

            SectionCard("Températures et climat", icon = Icons.Filled.WbSunny) {
                NumberField("Température intérieure souhaitée", b.setpointC, { v -> vm.update { it.copy(setpointC = v ?: 20.0) } },
                    unit = "°C", decimals = 1, nullable = false, min = 10.0, max = 30.0,
                    supportingText = "Valeur par défaut des nouvelles pièces")
                NumberField("Altitude de la commune", b.altitudeM, { v -> vm.update { it.copy(altitudeM = v ?: 0.0) } }, unit = "m", decimals = 0, nullable = false, min = -10.0, max = 3000.0)
                var city by remember { mutableStateOf<String?>(null) }
                DropdownField(
                    "Station climatique de référence", ClimateReferences.all.map { it.city }, city ?: b.climateReference.substringBefore(" (").ifBlank { null },
                    { c -> ClimateReferences.find(c)?.let { "${it.city} (${it.department}) – θb ${Fmt.num(it.designTempC, 0)} °C" } ?: c },
                    { c -> city = c; vm.applyClimate(c) },
                    supportingText = "Valeurs indicatives corrigées de l'altitude – à vérifier (NF EN 12831-1/CN, Météo-France)",
                )
                NumberField("Température extérieure de base θe", b.designOutdoorTempC, { v -> vm.update { it.copy(designOutdoorTempC = v ?: -7.0) } },
                    unit = "°C", decimals = 1, nullable = false, min = -40.0, max = 15.0)
                OriginSelector(b.designOutdoorOrigin, { o -> vm.update { it.copy(designOutdoorOrigin = o) } })
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumberField("T. moyenne annuelle", b.meanAnnualOutdoorTempC, { v -> vm.update { it.copy(meanAnnualOutdoorTempC = v ?: 11.0) } },
                        Modifier.weight(1f), unit = "°C", nullable = false, min = -10.0, max = 30.0)
                    NumberField("Degrés-jours (DJU)", b.degreeDays, { v -> vm.update { it.copy(degreeDays = v ?: 2400.0) } },
                        Modifier.weight(1f), decimals = 0, nullable = false, min = 0.0, max = 8000.0)
                }
                Text("DJU en base ${Fmt.num(b.degreeDaysBase, 0)} °C · origine : ${b.degreeDaysOrigin.label}", style = MaterialTheme.typography.bodySmall)
            }

            SectionCard("Ventilation et étanchéité à l'air", icon = Icons.Filled.Air) {
                DropdownField("Système de ventilation", VentilationType.entries, b.ventilationType, { it.label }, { v -> vm.update { it.copy(ventilationType = v) } })
                if (b.ventilationType != VentilationType.NATURAL) {
                    NumberField("Débit mécanique total", b.mechanicalFlowM3h, { v -> vm.update { it.copy(mechanicalFlowM3h = v) } }, unit = "m³/h", decimals = 0,
                        supportingText = "Vide : somme des débits minimaux des pièces", min = 0.0)
                }
                if (b.ventilationType == VentilationType.BALANCED) {
                    NumberField("Efficacité de l'échangeur", b.heatRecoveryEfficiency * 100, { v -> vm.update { it.copy(heatRecoveryEfficiency = (v ?: 0.0) / 100) } },
                        unit = "%", decimals = 0, nullable = false, min = 0.0, max = 95.0)
                }
                DropdownField("Niveau d'étanchéité (indicatif)", AirtightnessPreset.entries,
                    AirtightnessPreset.entries.firstOrNull { it.n50 == b.n50 }, { "${it.label} – n50 ≈ ${Fmt.num(it.n50, 1)} h⁻¹" },
                    { p -> vm.update { it.copy(n50 = p.n50, n50Origin = ValueOrigin.ESTIMATED) } })
                NumberField("n50 (renouvellement sous 50 Pa)", b.n50, { v -> vm.update { it.copy(n50 = v ?: 10.0) } }, unit = "h⁻¹", decimals = 1, nullable = false, min = 0.1, max = 50.0,
                    supportingText = "Valeur mesurée si un test d'infiltrométrie a été réalisé")
                OriginSelector(b.n50Origin, { o -> vm.update { it.copy(n50Origin = o) } })
                DropdownField("Exposition au vent", Shielding.entries, b.shielding, { it.label }, { v -> vm.update { it.copy(shielding = v) } })
                NumberField("Hauteur du bâtiment", b.buildingHeightM, { v -> vm.update { it.copy(buildingHeightM = v ?: 6.0) } }, unit = "m", decimals = 1, nullable = false, min = 1.0, max = 100.0)
            }

            SectionCard("Hypothèses de calcul", icon = Icons.Filled.Thermostat) {
                Text("Ponts thermiques (forfait ΔU_TB appliqué aux parois de l'enveloppe)", style = MaterialTheme.typography.bodyMedium)
                ChoiceChips(listOf(0.0, 0.05, 0.10, 0.15), b.thermalBridgeDeltaU, { if (it == 0.0) "Aucun (ψ détaillés)" else "${Fmt.num(it, 2)}" },
                    { v -> vm.update { it.copy(thermalBridgeDeltaU = v) } })
                NumberField("ΔU_TB", b.thermalBridgeDeltaU, { v -> vm.update { it.copy(thermalBridgeDeltaU = v ?: 0.1) } }, unit = "W/(m²·K)", nullable = false, min = 0.0, max = 0.5)
                if (b.thermalBridgeDeltaU > 0) {
                    InfoBanner("Si vous saisissez des ponts thermiques linéiques détaillés dans les pièces, réduisez ce forfait pour éviter un double comptage.", kind = BannerKind.WARNING)
                }
                NumberField("Surpuissance de relance", b.reheatFactorWm2, { v -> vm.update { it.copy(reheatFactorWm2 = v ?: 0.0) } }, unit = "W/m²", decimals = 0,
                    nullable = false, min = 0.0, max = 60.0, supportingText = "0 pour un chauffage continu ; ≈ 10 à 20 W/m² en cas de réduits nocturnes")
                NumberField("Inertie complémentaire (cloisons, mobilier)", b.additionalHeatCapacityJm2K / 1000, { v -> vm.update { it.copy(additionalHeatCapacityJm2K = (v ?: 20.0) * 1000) } },
                    unit = "kJ/(m²·K)", decimals = 0, nullable = false, min = 0.0, max = 400.0, supportingText = "Utilisée par la simulation dynamique")
            }

            TextInput("Notes", b.notes, { v -> vm.update { it.copy(notes = v) } }, singleLine = false)

            Button(onClick = {
                // Ouvert depuis l'onglet Maison : un simple retour suffit. Ouvert depuis l'Accueil : l'écran est retiré
                // de la pile SANS être sauvegardé (sinon un formulaire périmé réapparaîtrait dans l'onglet Accueil).
                if (!nav.popBackStack<HouseRoute>(inclusive = false)) {
                    nav.popBackStack()
                    nav.navigateToTab(HouseRoute)
                }
            }) {
                Text("Continuer : saisir les pièces")
            }
        }
    }
}
