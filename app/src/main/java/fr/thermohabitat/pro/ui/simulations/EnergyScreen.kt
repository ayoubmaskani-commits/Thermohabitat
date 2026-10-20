package fr.thermohabitat.pro.ui.simulations

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Euro
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.text.font.FontWeight
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
import fr.thermohabitat.pro.ui.components.BannerKind
import fr.thermohabitat.pro.ui.components.BarEntry
import fr.thermohabitat.pro.ui.components.DropdownField
import fr.thermohabitat.pro.ui.components.FieldRow
import fr.thermohabitat.pro.ui.components.HorizontalBarChart
import fr.thermohabitat.pro.ui.components.InfoBanner
import fr.thermohabitat.pro.ui.components.NumberField
import fr.thermohabitat.pro.ui.components.ScreenScaffold
import fr.thermohabitat.pro.ui.components.ScrollColumn
import fr.thermohabitat.pro.ui.components.SectionCard
import fr.thermohabitat.pro.ui.components.ValueRow
import fr.thermohabitat.pro.ui.components.readableOn
import fr.thermohabitat.pro.ui.navigation.HouseRoute
import fr.thermohabitat.pro.ui.navigation.navigateToTab
import fr.thermohabitat.pro.ui.theme.LocalStatusColors
import fr.thermohabitat.pro.ui.util.Fmt
import fr.thermohabitat.thermal.calc.EnergyCarrier
import fr.thermohabitat.thermal.calc.EnergySettings
import fr.thermohabitat.thermal.calc.HeatPumpAnalysis
import fr.thermohabitat.thermal.calc.HeatPumpPerformance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** SCOP et coût annuel PAC pour une température de départ de base donnée. */
data class WaterTemperatureImpact(val supplyC: Double, val scop: Double, val costEur: Double, val electricityKwh: Double)

data class EnergyData(val analyzed: AnalyzedProject, val impacts: List<WaterTemperatureImpact>)

@HiltViewModel
class EnergyViewModel @Inject constructor(
    studyService: StudyService,
    private val repository: ProjectRepository,
) : ViewModel() {
    val data = studyService.observeActive().map { p ->
        p?.let { a ->
            val s = a.project.settings
            val hl = a.result.heatLoss
            val perf = HeatPumpPerformance(s.heatPump, s.genericCarnotEfficiency)
            // Même assiette que l'étude (DJU corrigés, calage éventuel) ; seule la loi d'eau varie.
            val basisKwh = a.result.energy.calibratedNeedKwh ?: a.result.energy.annualNeedKwh
            val hp = EnergyCarrier.HEAT_PUMP
            val impacts = if (hl.totalW <= 0) emptyList() else listOf(35.0, 45.0, 55.0, 65.0).mapNotNull { t ->
                val curve = s.heatingCurve.copy(designSupplyC = t, maxSupplyC = maxOf(t, s.heatingCurve.maxSupplyC))
                val scop = HeatPumpAnalysis.seasonal(hl.totalKw, a.project.building.climate.designOutdoorTempC, curve, perf).scop
                if (scop <= 0) return@mapNotNull null
                val kwh = basisKwh / scop
                WaterTemperatureImpact(t, scop, kwh / hp.kwhPerUnit * s.energy.priceOf(hp) + (s.energy.subscriptions[hp] ?: 0.0), kwh)
            }
            EnergyData(a, impacts)
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun edit(t: (EnergySettings) -> EnergySettings) = viewModelScope.launch {
        val id = data.value?.analyzed?.project?.id ?: return@launch
        repository.updateSettings(id) { it.copy(energy = t(it.energy)) }
    }
}

@Composable
fun EnergyScreen(nav: NavController, vm: EnergyViewModel = hiltViewModel()) {
    val data by vm.data.collectAsStateWithLifecycle()
    ScreenScaffold(title = "Consommations et coûts", onBack = { nav.popBackStack() }) { padding ->
        ScrollColumn(padding) {
            val d = data ?: return@ScrollColumn
            val e = d.analyzed.project.settings.energy
            val est = d.analyzed.result.energy
            val climate = d.analyzed.project.building.climate
            // Sans besoin modélisé (aucune pièce), les coûts ne reposent que sur une consommation constatée, si elle est saisie.
            val noModel = est.annualNeedKwh <= 0.0
            val hasCosts = (est.calibratedNeedKwh ?: est.annualNeedKwh) > 0.0

            if (noModel) {
                // Texte fixe : sa hauteur ne change pas pendant la saisie de la consommation, plus bas.
                InfoBanner(
                    "Aucun besoin calculé (pièces non saisies) : saisissez les pièces pour estimer besoins et coûts. " +
                        "Les coûts peuvent déjà reposer sur la consommation constatée, si vous la renseignez.",
                    kind = BannerKind.WARNING,
                )
                Button(onClick = { nav.navigateToTab(HouseRoute) }) { Text("Saisir les pièces") }
            }

            // L'essentiel (dépense annuelle) en tête, toujours avec les mêmes lignes (« – » sans données) :
            // la saisie de la consommation ou d'un rendement, plus bas, ne fait pas sauter la page.
            val current = est.carriers.firstOrNull { it.carrier == e.currentCarrier }?.takeIf { hasCosts }
            val best = est.carriers.minByOrNull { it.costEur }?.takeIf { hasCosts }
            val bestIsCurrent = best == null || best.carrier == e.currentCarrier || best.savingsVsCurrentEur < 0.5
            SectionCard("En résumé", icon = Icons.Filled.Euro, subtitle = "Selon les prix et rendements saisis plus bas") {
                ValueRow("Besoins annuels", if (!hasCosts) "–"
                    else "${Fmt.num(est.calibratedNeedKwh ?: est.annualNeedKwh, 0)} kWh" + if (est.calibratedNeedKwh != null) " (calés sur la consommation)" else "")
                ValueRow("Dépense actuelle estimée", current?.let { "${Fmt.eur(it.costEur)}/an" } ?: "–", emphasize = true)
                ValueRow("Énergie la moins chère", when {
                    best == null -> "–"
                    bestIsCurrent -> "Énergie actuelle"
                    else -> "${best.carrier.label} · ${Fmt.eur(best.costEur)}/an"
                })
                ValueRow("Économie possible", if (best == null || bestIsCurrent) "–" else "${Fmt.eur(best.savingsVsCurrentEur)}/an")
            }

            SectionCard("Situation actuelle", icon = Icons.Filled.Calculate) {
                DropdownField("Énergie actuelle", EnergyCarrier.entries.filter { it != EnergyCarrier.HEAT_PUMP } + EnergyCarrier.HEAT_PUMP,
                    e.currentCarrier, { it.label }, { c -> vm.edit { it.copy(currentCarrier = c, currentEfficiency = it.efficiencyOf(c).coerceAtMost(if (c == EnergyCarrier.HEAT_PUMP) 6.0 else 1.1)) } })
                NumberField("Consommation annuelle", e.currentAnnualConsumption, { v -> vm.edit { it.copy(currentAnnualConsumption = v) } },
                    unit = e.currentCarrier.unit, decimals = 0, min = 0.0,
                    supportingText = "Facultatif : consommation de chauffage constatée, pour caler l'estimation (moyenne de plusieurs hivers de préférence)")
                NumberField(if (e.currentCarrier == EnergyCarrier.HEAT_PUMP) "SCOP constaté" else "Rendement saisonnier",
                    if (e.currentCarrier == EnergyCarrier.HEAT_PUMP) e.currentEfficiency else e.currentEfficiency * 100,
                    { v -> v?.let { vm.edit { x -> x.copy(currentEfficiency = if (x.currentCarrier == EnergyCarrier.HEAT_PUMP) it else it / 100) } } },
                    unit = if (e.currentCarrier == EnergyCarrier.HEAT_PUMP) null else "%", decimals = if (e.currentCarrier == EnergyCarrier.HEAT_PUMP) 2 else 0, nullable = false,
                    supportingText = "Production × distribution × émission × régulation (chaudière fioul ancienne : 65 à 80 %)")
                NumberField("Facteur d'intermittence", e.intermittenceFactor, { v -> v?.let { vm.edit { x -> x.copy(intermittenceFactor = it) } } }, decimals = 2,
                    nullable = false, min = 0.5, max = 1.2, supportingText = "1,0 : chauffage continu ; 0,85–0,95 : réduits nocturnes")
                ValueRow("Degrés-jours utilisés", "${Fmt.num(est.degreeDays, 0)} DJU (climat : ${Fmt.num(climate.degreeDays, 0)} en base ${Fmt.num(climate.degreeDaysBase, 0)} °C, ${climate.degreeDaysOrigin.label.lowercase()})")
            }

            if (!noModel) {
                SectionCard("Besoins de chauffage") {
                    ValueRow("Besoins annuels estimés (modèle)", "${Fmt.num(est.annualNeedKwh, 0)} kWh", emphasize = true)
                    // Le rapport réel / modèle n'a de sens que si le modèle donne un besoin.
                    est.calibration?.takeIf { it.modelledNeedKwh > 0 }?.let { c ->
                        ValueRow("Chaleur réellement fournie (consommation × rendement)", "${Fmt.num(c.deliveredHeatKwh, 0)} kWh")
                        ValueRow("Rapport réel / modèle", Fmt.num(c.ratio, 2))
                        InfoBanner(when {
                            c.ratio > 1.25 -> "La consommation réelle dépasse nettement le modèle : rendement surestimé, consigne plus élevée, ou déperditions sous-estimées (U, infiltrations)."
                            c.ratio < 0.75 -> "La consommation réelle est nettement inférieure au modèle : chauffage partiel, apports gratuits importants, ou déperditions surestimées."
                            else -> "Le modèle est cohérent avec la consommation réelle (écart inférieur à 25 %). Les coûts ci-dessous sont calés sur la consommation réelle."
                        })
                    }
                }
            }

            if (hasCosts) {
                SectionCard("Comparaison des énergies", subtitle = "Dépense annuelle et écart avec l'énergie actuelle") {
                    est.carriers.forEach { c ->
                        val isCurrent = c.carrier == e.currentCarrier
                        val delta = c.savingsVsCurrentEur
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(c.carrier.label + if (isCurrent) " (actuel)" else "", fontWeight = FontWeight.SemiBold)
                            // Dépense d'abord, puis l'écart signé en clair (jamais « économie −412 € »).
                            Text("${Fmt.eur(c.costEur)}/an", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            if (!isCurrent) {
                                when {
                                    delta >= 0.5 -> Text("Économie de ${Fmt.eur(delta)}/an", style = MaterialTheme.typography.bodyMedium,
                                        color = readableOn(LocalStatusColors.current.sufficient))
                                    delta <= -0.5 -> Text("Surcoût de ${Fmt.eur(-delta)}/an", style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.error)
                                    else -> Text("Coût équivalent", style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Text("${if (c.carrier == EnergyCarrier.HEAT_PUMP) "SCOP" else "Rendement"} ${if (c.carrier == EnergyCarrier.HEAT_PUMP) Fmt.num(c.efficiency, 2) else Fmt.pct(c.efficiency)} · " +
                                "${Fmt.num(c.quantity, 0)} ${c.carrier.unit}/an · ${Fmt.num(c.finalEnergyKwh, 0)} kWh",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        HorizontalDivider()
                    }
                    HorizontalBarChart(est.carriers.map { BarEntry(it.carrier.label, it.costEur, detail = "${Fmt.num(it.quantity, 0)} ${it.carrier.unit}/an") },
                        valueFormatter = { Fmt.eur(it) })
                }
            }

            if (d.impacts.isNotEmpty()) {
                SectionCard("Incidence de la température d'eau (PAC)", subtitle = "Départ de la loi d'eau à la température de base") {
                    d.impacts.forEach { i ->
                        ValueRow("Départ ${Fmt.temp(i.supplyC, 0)}", "SCOP ${Fmt.num(i.scop, 2)} · ${Fmt.num(i.electricityKwh, 0)} kWh · ${Fmt.eur(i.costEur)}/an")
                    }
                    Text("Une température d'eau plus basse améliore le rendement de la PAC, à condition que les radiateurs couvrent encore les besoins.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }

            // Saisie longue (sept énergies) placée après les résultats ; nom de l'énergie en titre, champs courts.
            SectionCard("Prix et rendements", subtitle = "Prix : exemples à remplacer par vos tarifs actuels") {
                EnergyCarrier.entries.forEachIndexed { index, c ->
                    if (index > 0) HorizontalDivider()
                    // Libellés courts à l'écran ; le nom de l'énergie est un titre et accompagne chaque champ pour TalkBack.
                    Text(c.label, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall)
                    FieldRow {
                        NumberField("Prix", e.priceOf(c), { v -> v?.let { vm.edit { x -> x.copy(prices = x.prices + (c to it)) } } },
                            Modifier.semantics { contentDescription = "Prix, ${c.label}" },
                            unit = "€/${c.unit}", decimals = 3, nullable = false, min = 0.0)
                        if (c == EnergyCarrier.HEAT_PUMP) {
                            NumberField("SCOP imposé", e.heatPumpScopOverride, { v -> vm.edit { x -> x.copy(heatPumpScopOverride = v) } },
                                Modifier.semantics { contentDescription = "SCOP imposé, ${c.label}" },
                                decimals = 2, min = 1.0, max = 7.0, supportingText = "Facultatif. Vide : SCOP calculé (${Fmt.num(d.analyzed.result.seasonal?.scop, 2)})")
                        } else {
                            NumberField("Rendement", e.efficiencyOf(c) * 100, { v -> v?.let { vm.edit { x -> x.copy(efficiencies = x.efficiencies + (c to it / 100)) } } },
                                Modifier.semantics { contentDescription = "Rendement, ${c.label}" },
                                unit = "%", decimals = 0, nullable = false, min = 10.0, max = 120.0)
                        }
                    }
                }
            }

            SectionCard("Hypothèses") { est.assumptions.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) } }
        }
    }
}
