package fr.thermohabitat.pro

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import fr.thermohabitat.pro.data.db.AppDatabase
import fr.thermohabitat.pro.data.db.PhotoOwner
import fr.thermohabitat.pro.data.files.PhotoStorage
import fr.thermohabitat.pro.data.repository.MaterialRepository
import fr.thermohabitat.pro.data.repository.ProjectRepository
import fr.thermohabitat.thermal.model.PhotoKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/** Tests sur appareil : `./gradlew connectedDebugAndroidTest` (téléphone ou émulateur). */
@RunWith(AndroidJUnit4::class)
class PhotoAndCameraTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun importDUneImageEtAssociationAUnRadiateur() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val storage = PhotoStorage(context)
        val repo = ProjectRepository(db, MaterialRepository(db.materialDao()), storage)
        // Image de 4000 × 3000 px simulant une photo de galerie.
        val source = File(context.cacheDir, "galerie.jpg")
        FileOutputStream(source).use { out ->
            Bitmap.createBitmap(4000, 3000, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
                .compress(Bitmap.CompressFormat.JPEG, 80, out)
        }
        val building = repo.createBuilding("Test photo")
        val room = repo.addRoom(building, "Salon")
        val radiator = repo.createRadiatorDraft(building, room)
        val photoId = repo.importPhoto(building, PhotoOwner.RADIATOR, radiator, Uri.fromFile(source), PhotoKind.FRONT)
        val photo = repo.getPhoto(photoId)!!
        assertThat(storage.file(photo.fileName).exists()).isTrue()
        assertThat(maxOf(photo.width, photo.height)).isAtMost(PhotoStorage.MAX_SIDE_PX)
        val listed = repo.observePhotos(PhotoOwner.RADIATOR, radiator).first()
        assertThat(listed.map { it.id }).containsExactly(photoId)
        val project = repo.loadProject(building)!!
        assertThat(project.building.radiatorsIn(room).single().photos).hasSize(1)
        db.close()
    }

    @Test
    fun cameraArriereDisponibleSurLAppareil() {
        val pm = context.packageManager
        assumeTrue("Appareil sans caméra", pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_ANY))
        val provider = ProcessCameraProvider.getInstance(context).get(10, TimeUnit.SECONDS)
        assertThat(provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) || provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)).isTrue()
    }
}

@RunWith(AndroidJUnit4::class)
class NavigationTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun ecranDAccueilEtSectionsPrincipales() {
        compose.onNodeWithText("Créer une étude thermique").assertExists()
        compose.onNodeWithText("Scanner un radiateur").assertExists()
        listOf("Maison", "Radiateurs", "Simulations", "Rapports").forEach { tab ->
            compose.onNodeWithText(tab, useUnmergedTree = true).performClick()
        }
        compose.onNodeWithText("Rapports et sauvegardes").assertExists()
    }
}
