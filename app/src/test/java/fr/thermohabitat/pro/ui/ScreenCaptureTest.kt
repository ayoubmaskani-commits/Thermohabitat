package fr.thermohabitat.pro.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import fr.thermohabitat.pro.MainActivity
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File
import java.io.FileOutputStream

/**
 * Captures d'écran de l'application réelle (Hilt, base Room, projet d'exemple) sur un téléphone de 360 dp
 * de large avec une police système agrandie à 130 % : sert à vérifier la mise en page et la lisibilité.
 * Écran très haut (1 400 dp) pour voir l'essentiel de chaque page sans défilement.
 *
 * Exécuté seulement avec `-Pcaptures=true` (étape dédiée de la CI) ; les images sont écrites dans
 * `app/build/captures`.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = HiltTestApplication::class, qualifiers = "w360dp-h1400dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED) // le test s'exécute sur le fil principal (navigation, cycle de vie)
class ScreenCaptureTest {

    /** Avant tout lancement de l'activité : le parcours ne s'exécute qu'avec -Pcaptures=true. */
    @get:Rule(order = -1)
    val enabled = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                assumeTrue("Captures désactivées (-Pcaptures=true)", System.getProperty("captures") == "true")
                base.evaluate()
            }
        }
    }

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val largeFont = object : ExternalResource() {
        override fun before() = RuntimeEnvironment.setFontScale(1.3f)
    }

    // Règle v2 : coroutines de composition mises en file (StandardTestDispatcher) et exécutées sur le fil principal.
    @get:Rule(order = 2)
    val compose = createAndroidComposeRule<MainActivity>()

    private val dir = File("build/captures").apply { mkdirs() }
    private val errors = mutableListOf<String>()

    @Test
    fun parcoursDesEcrans() {
        step("01_accueil_vide") { }
        step("02_nouvelle_etude") { click("Créer une étude thermique") }
        step("03_simulations_etude_vide") { back(); tab("Simulations") }
        step("04_energie_etude_vide") { click("Consommations et coûts") }
        step("05_accueil_exemple") {
            back(); tab("Accueil")
            click("Charger le projet d'exemple")
            waitFor("Puissance nécessaire")
        }
        step("06_maison") { tab("Maison"); waitFor("Salon") }
        step("07_piece_salon") { click("Salon") }
        step("08_radiateurs") { back(); tab("Radiateurs"); waitFor("R01") }
        step("09_fiche_radiateur") { click("R01") }
        step("10_simulations") { back(); tab("Simulations"); waitFor("Compatibilité PAC") }
        step("11_comparaison") { click("Comparer besoins et radiateurs") }
        step("12_pac") { back(); click("Pompe à chaleur et loi d'eau") }
        step("13_refroidissement") { back(); click("Refroidissement et inertie") }
        step("14_scenarios") { back(); click("Scénarios d'amélioration") }
        step("15_energie") { back(); click("Consommations et coûts") }
        step("16_rapports") { back(); tab("Rapports") }
        File(dir, "errors.txt").writeText(errors.joinToString("\n", postfix = "\n"))
    }

    /** Exécute une étape de navigation puis capture l'écran ; une étape en échec est notée sans arrêter le parcours. */
    private fun step(name: String, action: () -> Unit) {
        runCatching { action(); settle() }.onFailure { e ->
            val where = e.stackTrace.take(12).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
            errors += "$name : ${e::class.simpleName} ${e.message?.lines()?.firstOrNull()} [fil ${Thread.currentThread().name}] $where"
        }
        runCatching { capture(name) }.onFailure { errors += "$name (capture) : ${it.message?.lines()?.firstOrNull()}" }
    }

    private fun settle() {
        compose.waitForIdle()
        Thread.sleep(300) // calculs en arrière-plan (Dispatchers.Default) puis recomposition
        compose.waitForIdle()
    }

    private fun waitFor(text: String) {
        compose.waitUntil(20_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun click(text: String) {
        waitFor(text)
        val node = compose.onAllNodesWithText(text, substring = true).onFirst()
        runCatching { node.performScrollTo() }
        node.performClick()
        settle()
    }

    private fun tab(label: String) {
        compose.onNodeWithTag("tab:$label").performClick()
        settle()
    }

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        settle()
    }

    /** Rendu logiciel de la fenêtre courante (la copie de pixels de Robolectric renvoie une image figée). */
    private fun capture(name: String) {
        compose.waitForIdle()
        var bitmap: Bitmap? = null
        compose.runOnUiThread {
            val view = compose.activity.window.decorView
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { b -> view.draw(Canvas(b)) }
        }
        FileOutputStream(File(dir, "$name.png")).use { bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
