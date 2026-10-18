package fr.thermohabitat.thermal

import com.google.common.truth.Truth.assertThat
import fr.thermohabitat.thermal.calc.CoverageStatus
import fr.thermohabitat.thermal.calc.Regime
import fr.thermohabitat.thermal.library.MaterialLibrary
import fr.thermohabitat.thermal.library.SampleProjects
import fr.thermohabitat.thermal.scenario.InsulationPosition
import fr.thermohabitat.thermal.scenario.InsulationTarget
import fr.thermohabitat.thermal.scenario.Modification
import fr.thermohabitat.thermal.scenario.Scenario
import fr.thermohabitat.thermal.sim.OutdoorProfile
import fr.thermohabitat.thermal.sim.RcSimulator
import fr.thermohabitat.thermal.sim.SimulationConfig
import fr.thermohabitat.thermal.sim.ZoneModelBuilder
import org.junit.Test

/**
 * Cas d'usage réel du cahier des charges : maison de 1870 en pisé, 110 m², 2,70 m,
 * isolation intérieure partielle, 9 radiateurs fonte, projet de PAC air/eau.
 * (Géométrie et radiateurs d'exemple fictifs : le test vérifie l'enchaînement et la cohérence.)
 */
class CaseStudy1870Test {

    private val building = SampleProjects.pise1870()
    private val analyzer = StudyAnalyzer()
    private val result = analyzer.analyze(building, StudySettings())

    @Test
    fun `donnees du cas d'usage`() {
        assertThat(building.heatedFloorAreaM2).isWithin(1e-9).of(110.0)
        assertThat(building.heatedVolumeM3).isWithin(1e-9).of(110.0 * 2.70)
        assertThat(building.rooms).hasSize(8)
        assertThat(building.radiators).hasSize(9)
        assertThat(building.radiators.all { it.validated }).isTrue()
    }

    @Test
    fun `deperditions coherentes pour une maison ancienne partiellement isolee`() {
        val hl = result.heatLoss
        // Ordre de grandeur attendu pour 110 m² ancien partiellement isolé : 60 à 160 W/m².
        assertThat(hl.wattsPerM2).isGreaterThan(60.0)
        assertThat(hl.wattsPerM2).isLessThan(160.0)
        assertThat(hl.rooms.all { it.totalW > 0 }).isTrue()
        assertThat(hl.byCategoryW.values.sum()).isWithin(1.0).of(hl.envelopeTransmissionW + hl.ventilationW)
    }

    @Test
    fun `puissance des radiateurs decroissante de 60 a 45 °C et pieces sous-chauffees identifiees`() {
        val at = { s: Double -> result.regimeCoverages.first { it.supplyC == s } }
        val p60 = at(60.0).totalAvailableW; val p55 = at(55.0).totalAvailableW
        val p50 = at(50.0).totalAvailableW; val p45 = at(45.0).totalAvailableW
        assertThat(listOf(p60, p55, p50, p45)).isInStrictOrder(Comparator.reverseOrder<Double>())
        assertThat(at(45.0).coveredFraction).isAtMost(at(60.0).coveredFraction)
        val insufficient45 = at(45.0).rooms.filter { it.status != CoverageStatus.SUFFICIENT }
        assertThat(insufficient45).isNotEmpty()
    }

    @Test
    fun `temperature de depart minimale et compatibilite PAC`() {
        val t = result.supply.requiredSupplyC
        assertThat(t).isNotNull()
        assertThat(t!!).isGreaterThan(30.0)
        assertThat(t).isAtMost(90.0)
        assertThat(result.supplyConservative.requiredSupplyC ?: 99.0).isAtLeast(t)
        assertThat(result.compatibility).isNotEqualTo(PacCompatibility.UNDETERMINED)
    }

    @Test
    fun `scenario de renovation - baisse des besoins et de la temperature de depart`() {
        val scenario = Scenario(name = "Fenêtres + combles + murs non isolés", modifications = listOf(
            Modification.ReplaceWindows(1.3, 0.6),
            Modification.AddInsulation(InsulationTarget.TOP_CEILINGS, MaterialLibrary.byCode("ouate_cellulose"), 0.30, InsulationPosition.EXTERIOR),
            Modification.AddInsulation(InsulationTarget.EXTERIOR_WALLS, MaterialLibrary.byCode("fibre_bois_rigide"), 0.10,
                InsulationPosition.INTERIOR, finish = MaterialLibrary.byCode("enduit_terre"), finishThicknessM = 0.015),
        ))
        val cmp = ScenarioComparison.of(analyzer, building, StudySettings(), scenario)
        assertThat(cmp.heatLossReductionPct).isGreaterThan(0.2)
        val before = cmp.before.supply.requiredSupplyC!!
        val after = cmp.after.supply.requiredSupplyC!!
        assertThat(after).isLessThan(before)
        assertThat(cmp.after.energy.annualNeedKwh).isLessThan(cmp.before.energy.annualNeedKwh)
    }

    @Test
    fun `simulation du refroidissement nocturne avant et apres isolation`() {
        val config = SimulationConfig(durationH = 8.0, startClockHour = 22.0, outdoor = OutdoorProfile.Constant(0.0), initialTempC = 20.0)
        val sim = RcSimulator()
        val before = sim.simulate(ZoneModelBuilder.build(building, singleZone = true).zones, emptyList(), config)
        val improved = fr.thermohabitat.thermal.scenario.ScenarioEngine.apply(building, listOf(
            Modification.ReplaceWindows(1.3), Modification.ImproveAirtightness(4.0),
            Modification.AddInsulation(InsulationTarget.TOP_CEILINGS, MaterialLibrary.byCode("ouate_cellulose"), 0.30, InsulationPosition.EXTERIOR),
        ))
        val after = sim.simulate(ZoneModelBuilder.build(improved, singleZone = true).zones, emptyList(), config)
        val tBefore = before.zones.single().airTempC.last()
        val tAfter = after.zones.single().airTempC.last()
        assertThat(tAfter).isGreaterThan(tBefore)
        assertThat(tBefore).isLessThan(20.0)
    }

    @Test
    fun `rapport textuel du cas d'usage`() {
        val hl = result.heatLoss
        val sb = StringBuilder()
        sb.appendLine("=== Maison 1870 pisé (exemple) – θe = ${building.climate.designOutdoorTempC.fr(1)} °C ===")
        sb.appendLine("Déperditions : ${hl.totalW.fr(0)} W (${hl.wattsPerM2.fr(0)} W/m²), H = ${hl.totalHWk.fr(1)} W/K")
        hl.byCategoryW.forEach { (k, v) -> if (v != 0.0) sb.appendLine("  ${k.label} : ${v.fr(0)} W") }
        for (r in Regime.STANDARD.filter { it.supplyC in listOf(60.0, 55.0, 50.0, 45.0) }) {
            val c = result.regimeCoverages.first { it.supplyC == r.supplyC }
            sb.appendLine("Régime ${r.label} : ${c.totalAvailableW.fr(0)} W dispo, couverture ${(c.coveredFraction * 100).fr(0)} %")
            c.rooms.forEach { rc -> sb.appendLine("   ${rc.roomName.padEnd(16)} besoin ${rc.needW.fr(0).padStart(5)} W  dispo ${rc.availableW.fr(0).padStart(5)} W  ${rc.status.label}") }
        }
        sb.appendLine("Départ minimal : ${result.supply.requiredSupplyC?.fr(1)} °C (prudent : ${result.supplyConservative.requiredSupplyC?.fr(1)} °C) – ${result.compatibility.label}")
        sb.appendLine("SCOP estimé : ${result.seasonal?.scop?.fr(2)} ; besoins annuels : ${result.energy.annualNeedKwh.fr(0)} kWh")
        println(sb)
        assertThat(sb.toString()).contains("Départ minimal")
    }
}
