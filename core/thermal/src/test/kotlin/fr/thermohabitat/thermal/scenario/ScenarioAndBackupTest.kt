package fr.thermohabitat.thermal.scenario

import com.google.common.truth.Truth.assertThat
import fr.thermohabitat.thermal.ScenarioComparison
import fr.thermohabitat.thermal.StudyAnalyzer
import fr.thermohabitat.thermal.StudySettings
import fr.thermohabitat.thermal.backup.BackupJson
import fr.thermohabitat.thermal.backup.BackupPhoto
import fr.thermohabitat.thermal.backup.MeasurementSeries
import fr.thermohabitat.thermal.backup.ProjectBackup
import fr.thermohabitat.thermal.calc.HeatLossCalculator
import fr.thermohabitat.thermal.calc.Regime
import fr.thermohabitat.thermal.library.MaterialLibrary
import fr.thermohabitat.thermal.library.SampleProjects
import fr.thermohabitat.thermal.model.Boundary
import fr.thermohabitat.thermal.model.PhotoKind
import fr.thermohabitat.thermal.model.SurfaceKind
import fr.thermohabitat.thermal.model.VentilationType
import fr.thermohabitat.thermal.sim.MeasuredPoint
import org.junit.Test

class ScenarioAndBackupTest {

    private val building = SampleProjects.pise1870()
    private val calculator = HeatLossCalculator()

    @Test
    fun `isolation interieure des murs exterieurs ajoutee cote interieur`() {
        val m = Modification.AddInsulation(
            target = InsulationTarget.EXTERIOR_WALLS, material = MaterialLibrary.byCode("fibre_bois_rigide"),
            thicknessM = 0.12, position = InsulationPosition.INTERIOR, finish = MaterialLibrary.byCode("ba13"),
        )
        val after = ScenarioEngine.apply(building, listOf(m))
        val before = building.rooms.flatMap { it.surfaces }.filter { it.kind == SurfaceKind.WALL && it.boundary == Boundary.EXTERIOR }
        val walls = after.rooms.flatMap { it.surfaces }.filter { it.kind == SurfaceKind.WALL && it.boundary == Boundary.EXTERIOR }
        walls.zip(before).forEach { (a, b) ->
            assertThat(a.layers.size).isEqualTo(b.layers.size + 2)
            assertThat(a.layers.first().material.code).isEqualTo("ba13")
            assertThat(a.layers[1].material.code).isEqualTo("fibre_bois_rigide")
        }
        assertThat(calculator.calculate(after).totalW).isLessThan(calculator.calculate(building).totalW)
        // l'original n'est pas modifié
        assertThat(building.rooms.first().surfaces.first().layers.first().material.code).isEqualTo("ba13")
    }

    @Test
    fun `remplacement des fenetres et etancheite`() {
        val after = ScenarioEngine.apply(building, listOf(
            Modification.ReplaceWindows(uValue = 1.3, gValue = 0.6),
            Modification.ImproveAirtightness(n50 = 4.0),
        ))
        val windows = after.rooms.flatMap { r -> r.surfaces.flatMap { it.openings } }.filter { it.kind.isGlazed }
        assertThat(windows).isNotEmpty()
        assertThat(windows.all { it.uValue == 1.3 }).isTrue()
        val doors = after.rooms.flatMap { r -> r.surfaces.flatMap { it.openings } }.filter { !it.kind.isGlazed }
        assertThat(doors.none { it.uValue == 1.3 }).isTrue()
        assertThat(after.ventilation.n50).isEqualTo(4.0)
        val b = calculator.calculate(building); val a = calculator.calculate(after)
        assertThat(a.infiltrationW).isLessThan(b.infiltrationW + 1e-9)
        assertThat(a.totalW).isLessThan(b.totalW)
    }

    @Test
    fun `remplacement et ajout de radiateur`() {
        val r1 = building.radiators.first()
        val after = ScenarioEngine.apply(building, listOf(
            Modification.ReplaceRadiator(r1.id, r1.copy(sections = 20)),
            Modification.AddRadiator(r1.copy(id = 0, code = "N1", roomId = 2)),
        ))
        assertThat(after.radiators).hasSize(building.radiators.size + 1)
        assertThat(after.radiators.first { it.id == r1.id }.sections).isEqualTo(20)
        assertThat(after.radiators.last().id).isLessThan(0L)
    }

    @Test
    fun `comparateur avant apres`() {
        val scenario = Scenario(name = "Rénovation", modifications = listOf(
            Modification.ReplaceWindows(1.3),
            Modification.AddInsulation(InsulationTarget.TOP_CEILINGS, MaterialLibrary.byCode("ouate_cellulose"), 0.30,
                InsulationPosition.EXTERIOR),
        ), regime = Regime(45.0, 35.0))
        val cmp = ScenarioComparison.of(StudyAnalyzer(), building, StudySettings(), scenario)
        assertThat(cmp.heatLossReductionW).isGreaterThan(0.0)
        assertThat(cmp.heatLossReductionPct).isGreaterThan(0.0)
        assertThat(cmp.after.studyCoverage.supplyC).isEqualTo(45.0)
    }

    @Test
    fun `sauvegarde JSON aller-retour sans perte`() {
        val backup = ProjectBackup(
            exportedAt = "2026-10-18T12:00:00Z",
            building = building,
            settings = StudySettings(studyRegime = Regime(50.0, 40.0)),
            scenarios = listOf(Scenario(name = "S1", modifications = listOf(
                Modification.ReplaceWindows(1.2),
                Modification.ChangeVentilation(VentilationType.EXTRACT, 120.0),
                Modification.AddInsulation(InsulationTarget.EXTERIOR_WALLS, MaterialLibrary.byCode("pse"), 0.14, InsulationPosition.EXTERIOR),
            ))),
            measurements = listOf(MeasurementSeries("Nuit du 12 janvier", 1, 0.0, 22.0, listOf(MeasuredPoint(1.0, 19.2)))),
            photos = listOf(BackupPhoto("r01_face.jpg", "RADIATOR", 1, PhotoKind.FRONT)),
        )
        val text = BackupJson.encode(backup)
        assertThat(text).contains("\"kind_type\": \"add_insulation\"")
        val decoded = BackupJson.decode(text)
        assertThat(decoded).isEqualTo(backup)
        assertThat(calculator.calculate(decoded.building).totalW).isWithin(1e-9).of(calculator.calculate(building).totalW)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `sauvegarde d'une version future refusee`() {
        val text = BackupJson.encode(ProjectBackup(exportedAt = "x", building = building)).replace("\"formatVersion\": 1", "\"formatVersion\": 99")
        BackupJson.decode(text)
    }
}
