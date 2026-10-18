package fr.thermohabitat.pro

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import fr.thermohabitat.pro.data.db.AppDatabase
import fr.thermohabitat.pro.data.db.PhotoOwner
import fr.thermohabitat.pro.data.export.BackupManager
import fr.thermohabitat.pro.data.export.CsvExporter
import fr.thermohabitat.pro.data.files.PhotoStorage
import fr.thermohabitat.pro.data.repository.MaterialRepository
import fr.thermohabitat.pro.data.repository.ProjectRepository
import fr.thermohabitat.thermal.StudyAnalyzer
import fr.thermohabitat.thermal.StudySettings
import fr.thermohabitat.thermal.calc.HeatLossCalculator
import fr.thermohabitat.thermal.library.SampleProjects
import fr.thermohabitat.thermal.model.Boundary
import fr.thermohabitat.thermal.model.Orientation
import fr.thermohabitat.thermal.model.PhotoKind
import fr.thermohabitat.thermal.model.RadiatorType
import fr.thermohabitat.thermal.scenario.Modification
import fr.thermohabitat.thermal.scenario.Scenario
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Tests de la couche données sur une base Room en mémoire (Robolectric) : création de projet,
 * association radiateurs / pièces, conversions base ⇄ moteur, scénarios et sauvegardes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class RepositoryTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var materials: MaterialRepository
    private lateinit var repo: ProjectRepository
    private lateinit var photos: PhotoStorage

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        materials = MaterialRepository(db.materialDao())
        photos = PhotoStorage(context)
        repo = ProjectRepository(db, materials, photos)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `creation d'une maison, d'une piece et de ses parois par l'assistant`() = runBlocking {
        val b = repo.createBuilding("Test")
        val room = repo.addRoom(b, "Salon", 0, 5.0, 4.0, 2.7)
        repo.addStandardSurfaces(room, setOf(Orientation.S, Orientation.W), "pise_50", Boundary.GROUND, "dalle_terre_plein",
            Boundary.UNHEATED, "plafond_platre_non_isole", interiorWalls = false)
        val p = repo.loadProject(b)!!
        val r = p.building.rooms.single()
        assertThat(r.floorAreaM2).isWithin(1e-9).of(20.0)
        assertThat(r.surfaces).hasSize(4)
        val south = r.surfaces.first { it.orientation == Orientation.S }
        assertThat(south.lengthM).isEqualTo(5.0)
        assertThat(south.layers.map { it.material.code }).containsExactly("enduit_chaux", "pise", "enduit_chaux").inOrder()
        val floor = r.surfaces.first { it.boundary == Boundary.GROUND }
        assertThat(floor.ground!!.exposedPerimeterM).isWithin(1e-9).of(5.0 + 4.0)
        val result = HeatLossCalculator().calculate(p.building)
        assertThat(result.totalW).isGreaterThan(0.0)
    }

    @Test
    fun `codes radiateurs successifs et association a une piece`() = runBlocking {
        val b = repo.createBuilding("Test")
        val room = repo.addRoom(b, "Chambre", 1)
        val r1 = repo.createRadiatorDraft(b, room)
        val r2 = repo.createRadiatorDraft(b)
        assertThat(repo.getRadiator(r1)!!.code).isEqualTo("R01")
        assertThat(repo.getRadiator(r2)!!.code).isEqualTo("R02")
        repo.updateRadiator(repo.getRadiator(r2)!!.copy(roomId = room, type = RadiatorType.CAST_IRON_COLUMN))
        val p = repo.loadProject(b)!!
        assertThat(p.building.radiatorsIn(room)).hasSize(2)
        assertThat(p.building.radiators.all { !it.validated }).isTrue()
        // Suppression de la pièce : les radiateurs sont conservés sans pièce (clé étrangère SET NULL).
        repo.deleteRoom(room)
        val after = repo.loadProject(b)!!
        assertThat(after.building.radiators).hasSize(2)
        assertThat(after.building.radiators.all { it.roomId == null }).isTrue()
    }

    @Test
    fun `le projet d'exemple traverse la base sans alteration du calcul`() = runBlocking {
        val sample = SampleProjects.pise1870()
        val id = repo.insertGraph(sample, StudySettings())
        val p = repo.loadProject(id)!!
        assertThat(p.building.rooms).hasSize(sample.rooms.size)
        assertThat(p.building.radiators).hasSize(sample.radiators.size)
        val expected = HeatLossCalculator().calculate(sample)
        val actual = HeatLossCalculator().calculate(p.building)
        assertThat(actual.totalW).isWithin(1e-6).of(expected.totalW)
        // Les parois intérieures pointent vers les nouveaux identifiants de pièces.
        val adj = p.building.rooms.flatMap { it.surfaces }.mapNotNull { it.adjacentRoomId }
        assertThat(adj).isNotEmpty()
        assertThat(adj.all { a -> p.building.rooms.any { it.id == a } }).isTrue()
        val analysis = StudyAnalyzer().analyze(p.building, p.settings)
        assertThat(analysis.validatedRadiatorCount).isEqualTo(9)
    }

    @Test
    fun `duplication d'une piece avec ses parois, couches et baies`() = runBlocking {
        val id = repo.insertGraph(SampleProjects.pise1870(), StudySettings())
        val salon = repo.loadProject(id)!!.building.rooms.first { it.name == "Salon" }
        val copyId = repo.duplicateRoom(salon.id)!!
        val copy = repo.loadProject(id)!!.building.rooms.first { it.id == copyId }
        assertThat(copy.surfaces.size).isEqualTo(salon.surfaces.size)
        assertThat(copy.surfaces.sumOf { it.layers.size }).isEqualTo(salon.surfaces.sumOf { it.layers.size })
        assertThat(copy.surfaces.sumOf { it.openings.size }).isEqualTo(salon.surfaces.sumOf { it.openings.size })
    }

    @Test
    fun `scenario enregistre et relu`() = runBlocking {
        val b = repo.createBuilding("Test")
        val sc = Scenario(name = "Fenêtres", modifications = listOf(Modification.ReplaceWindows(1.3), Modification.ImproveAirtightness(4.0)))
        val id = repo.saveScenario(b, sc)
        val read = repo.observeScenario(id).first()!!.second
        assertThat(read).isEqualTo(sc)
    }

    @Test
    fun `sauvegarde ZIP avec photo puis import comme nouveau projet`() = runBlocking {
        val id = repo.insertGraph(SampleProjects.pise1870(), StudySettings())
        val p = repo.loadProject(id)!!
        val radiator = p.radiators.first()
        val photo = photos.newPhotoFile("test")
        photo.writeBytes(ByteArray(64) { it.toByte() })
        repo.addPhoto(id, PhotoOwner.RADIATOR, radiator.id, photo, PhotoKind.FRONT)

        val backup = BackupManager(context, repo, photos)
        val zip = backup.zipToCache(id, p.entity.name)
        assertThat(zip.length()).isGreaterThan(0L)
        val importedId = backup.import(android.net.Uri.fromFile(zip))
        assertThat(importedId).isNotEqualTo(id)
        val imported = repo.loadProject(importedId)!!
        assertThat(imported.entity.name).endsWith("(importé)")
        assertThat(imported.photos).hasSize(1)
        assertThat(File(photos.directory, imported.photos.single().fileName).readBytes()).isEqualTo(photo.readBytes())
        assertThat(HeatLossCalculator().calculate(imported.building).totalW)
            .isWithin(1e-6).of(HeatLossCalculator().calculate(p.building).totalW)
    }

    @Test
    fun `suppression complete d'un projet et de ses photos`() = runBlocking {
        val id = repo.insertGraph(SampleProjects.pise1870(), StudySettings())
        val rad = repo.loadProject(id)!!.radiators.first()
        val f = photos.newPhotoFile("del"); f.writeText("x")
        repo.addPhoto(id, PhotoOwner.RADIATOR, rad.id, f, PhotoKind.FRONT)
        repo.deleteBuilding(id)
        assertThat(repo.loadProject(id)).isNull()
        assertThat(f.exists()).isFalse()
    }

    @Test
    fun `exports CSV`() = runBlocking {
        val id = repo.insertGraph(SampleProjects.pise1870(), StudySettings())
        val p = repo.loadProject(id)!!
        val result = StudyAnalyzer().analyze(p.building, p.settings)
        val radiators = CsvExporter.radiators(result).trimEnd().lines()
        assertThat(radiators).hasSize(1 + 9)
        assertThat(radiators.first()).contains("Code;Libellé;Pièce")
        val rooms = CsvExporter.rooms(result).trimEnd().lines()
        assertThat(rooms).hasSize(1 + 8 + 1)
    }
}
