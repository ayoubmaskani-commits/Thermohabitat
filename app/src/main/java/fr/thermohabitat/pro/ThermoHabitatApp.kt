package fr.thermohabitat.pro

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import fr.thermohabitat.pro.data.files.PhotoStorage
import java.io.File

@HiltAndroidApp
class ThermoHabitatApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Les exports d'une session précédente (copies de rapports et de sauvegardes avec photos) ne sont pas conservés.
        val exports = File(cacheDir, PhotoStorage.EXPORTS_DIR)
        Thread { exports.deleteRecursively() }.start()
    }
}
