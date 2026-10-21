package fr.thermohabitat.thermal.vision

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random

class RectifiedCountingTest {

    /** Face redressée de face : n éléments à colonne arrondie, joints sombres, bruit, bords exactement sur le contour. */
    private fun face(n: Int, width: Int, height: Int, seed: Int = 1): GrayImage {
        val rnd = Random(seed)
        val px = FloatArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            val u = (x + 0.5) * n / width % 1.0
            val v = (u - 0.5) / 0.4
            val col = if (kotlin.math.abs(v) <= 1) 70 + 130 * sqrt(max(0.0, 1 - v * v)) else 50.0
            px[y * width + x] = (col + 20.0 * x / width + rnd.nextDouble(-10.0, 10.0)).toFloat()
        }
        return GrayImage(width, height, px)
    }

    @Test
    fun `comptage sur face redressee sans marges`() {
        for (n in listOf(6, 10, 14, 22, 40)) {
            val w = 960; val h = (w / n / 0.12).toInt().coerceAtMost(900)
            val r = ElementCounter.countRectified(face(n, w, h))
            assertThat(r.count).isEqualTo(n)
            assertWithMessage("N=$n confiance ${r.confidence} : ${r.message} ${r.alternatives}").that(r.reliable).isTrue()
            assertThat(r.boundariesPx).hasSize(n + 1)
            assertThat(r.periodPx!!).isWithin(0.03 * w / n).of(w.toDouble() / n)
            assertThat(r.startPx!!).isWithin(0.15 * w / n).of(0.0)
            assertThat(r.endPx!!).isWithin(0.15 * w / n).of(w.toDouble())
        }
    }

    @Test
    fun `a priori de pas fourni par l'echelle`() {
        // Pas de 120 px pour 240 px de haut : hors de l'a priori pas / hauteur par défaut, mais conforme à l'échelle.
        val r = ElementCounter.countRectified(face(6, 720, 240), pitchPriorPx = 80.0..160.0)
        assertThat(r.count).isEqualTo(6)
    }

    @Test
    fun `bruit ou image uniforme - aucun nombre`() {
        val rnd = Random(8)
        val noise = GrayImage(800, 300, FloatArray(800 * 300) { (120 + rnd.nextDouble(-40.0, 40.0)).toFloat() })
        assertThat(ElementCounter.countRectified(noise).count).isNull()
        val flat = ElementCounter.countRectified(GrayImage(800, 300, FloatArray(800 * 300) { 90f }))
        assertThat(flat.count).isNull()
        assertThat(flat.message).contains("uniforme")
        assertThat(ElementCounter.countRectified(GrayImage(30, 20, FloatArray(600) { 1f })).count).isNull()
    }

    @Test
    fun `limites regulieres pour un nombre choisi`() {
        assertThat(ElementCounter.evenBoundaries(4, 10.0, 50.0)).containsExactly(10.0, 20.0, 30.0, 40.0, 50.0).inOrder()
    }
}
