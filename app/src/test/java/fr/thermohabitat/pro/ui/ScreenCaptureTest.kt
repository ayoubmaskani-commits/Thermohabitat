package fr.thermohabitat.pro.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
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

    /** Taille de police système (1,3 par défaut ; 1,5 pour le cas le plus contraignant). */
    private val fontScale = System.getProperty("capturesFontScale")?.toFloatOrNull() ?: 1.3f

    /** Préfixe des fichiers pour une police autre que 130 % (ex. « x150_ »). */
    private val prefix = if (fontScale == 1.3f) "" else "x${(fontScale * 100).toInt()}_"

    @get:Rule(order = 1)
    val largeFont = object : ExternalResource() {
        override fun before() = RuntimeEnvironment.setFontScale(fontScale)
    }

    // Règle v2 : coroutines de composition mises en file (StandardTestDispatcher) et exécutées sur le fil principal.
    @get:Rule(order = 2)
    val compose = createAndroidComposeRule<MainActivity>()

    private val dir = File("build/captures").apply { mkdirs() }
    private val errors = mutableListOf<String>()

    @Test
    fun parcoursDesEcrans() {
        step("01_accueil_vide") { }
        step("02_nouvelle_etude") { click("Créer une étude thermique"); waitFor("Nom du projet") }
        step("03_nouvelle_etude_bas") { scrollToEnd() }
        step("04_accueil_etude_vide") { back(); tab("Accueil") }
        step("05_maison_etude_vide") { tab("Maison") }
        step("06_simulations_etude_vide") { tab("Simulations") }
        step("07_pac_etude_vide") { click("Pompe à chaleur et loi d'eau") }
        step("08_energie_etude_vide") { back(); click("Consommations et coûts") }
        step("09_accueil_exemple") {
            back(); tab("Accueil")
            click("projet d'exemple")
            waitFor("Puissance nécessaire")
        }
        step("10_maison") { tab("Maison"); waitFor("Salon") }
        step("11_piece_salon") { click("Salon") }
        step("12_radiateurs") { back(); tab("Radiateurs"); waitFor("R01") }
        step("13_fiche_radiateur") { click("R01") }
        step("14_simulations") { back(); tab("Simulations"); waitFor("Comparer besoins et radiateurs") }
        step("15_comparaison") { click("Comparer besoins et radiateurs") }
        step("16_pac") { back(); click("Pompe à chaleur et loi d'eau") }
        step("17_pac_bas") { scrollToEnd() }
        step("18_refroidissement") { back(); click("Refroidissement et inertie") }
        step("19_scenarios") { back(); click("Scénarios d'amélioration") }
        step("20_energie") { back(); click("Consommations et coûts") }
        step("21_energie_bas") { scrollToEnd() }
        step("22_rapports") { back(); tab("Rapports") }
        step("23_reglages") { compose.onNodeWithContentDescription("Réglages").performClick(); settle() }
        File(dir, "errors$prefix.txt").writeText(errors.joinToString("\n", postfix = "\n") { prefix + it })
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

    /** Fait défiler la page jusqu'en bas (capture de la fin des longs formulaires). */
    private fun scrollToEnd() {
        compose.onAllNodes(hasScrollAction()).onFirst().performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
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
        FileOutputStream(File(dir, "$prefix$name.png")).use { bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
