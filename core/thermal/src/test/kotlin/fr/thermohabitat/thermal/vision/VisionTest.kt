package fr.thermohabitat.thermal.vision

import com.google.common.truth.Truth.assertThat
import fr.thermohabitat.thermal.model.PanelType
import fr.thermohabitat.thermal.model.RatingCondition
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class VisionTest {

    /** Image synthétique d'un radiateur de [n] éléments : bandes claires (éléments) et sombres (joints). */
    private fun syntheticRadiator(n: Int, width: Int = 720, height: Int = 240, noise: Double = 12.0, gradient: Double = 40.0): GrayImage {
        val rnd = Random(42)
        val px = FloatArray(width * height)
        val period = width.toDouble() / n
        for (y in 0 until height) for (x in 0 until width) {
            val phase = (x % period) / period
            val base = if (phase < 0.75) 190.0 else 80.0
            val light = gradient * x / width
            px[y * width + x] = (base + light + rnd.nextDouble(-noise, noise)).toFloat()
        }
        return GrayImage(width, height, px)
    }

    @Test
    fun `comptage des elements sur image synthetique`() {
        for (n in listOf(6, 10, 14, 22)) {
            val r = ElementCounter.count(syntheticRadiator(n))
            assertThat(r.count).isEqualTo(n)
            assertThat(r.reliable).isTrue()
            assertThat(r.boundariesPx.size).isAtLeast(n - 1)
        }
    }

    @Test
    fun `motif sinusoidal faiblement contraste`() {
        val w = 600; val h = 120; val n = 12
        val px = FloatArray(w * h) { i -> val x = i % w; (128 + 20 * sin(2 * PI * x * n / w)).toFloat() }
        assertThat(ElementCounter.count(GrayImage(w, h, px)).count).isEqualTo(n)
    }

    @Test
    fun `image sans motif - pas de comptage invente`() {
        val rnd = Random(1)
        val w = 400; val h = 100
        val uniform = GrayImage(w, h, FloatArray(w * h) { 120f })
        assertThat(ElementCounter.count(uniform).count).isNull()
        val noise = GrayImage(w, h, FloatArray(w * h) { (120 + rnd.nextDouble(-40.0, 40.0)).toFloat() })
        val r = ElementCounter.count(noise)
        assertThat(r.reliable).isFalse()
    }

    @Test
    fun `recadrage de l'image`() {
        val img = syntheticRadiator(10)
        val c = img.crop(10, 20, 110, 70)
        assertThat(c.width).isEqualTo(100)
        assertThat(c.height).isEqualTo(50)
        assertThat(c[0, 0]).isEqualTo(img[10, 20])
    }

    @Test
    fun `mesure avec objet de reference`() {
        // carte bancaire de 85,6 mm représentée par 100 px ; radiateur de 700 px
        val m = ReferenceMeasurement.measure(ImagePoint(0.0, 0.0), ImagePoint(100.0, 0.0), 85.6,
            ImagePoint(0.0, 10.0), ImagePoint(0.0, 710.0))
        assertThat(m.valueMm).isWithin(1e-9).of(599.2)
        assertThat(m.uncertaintyMm).isGreaterThan(0.04 * 599.2)
        assertThat(m.warnings).isNotEmpty()
    }

    @Test
    fun `lecture d'une plaque constructeur`() {
        val text = """
            CHAPPEE
            Modèle : Néo Classic 600
            Réf. NC-600-12
            Type 22  600 x 1000
            1650 W  75/65/20
        """.trimIndent()
        val d = NameplateParser.parse(text)
        assertThat(d.manufacturer!!.value).isEqualTo("Chappée")
        assertThat(d.powerW!!.value).isWithin(1e-9).of(1650.0)
        assertThat(d.rating!!.value).isEqualTo(RatingCondition.EN442)
        assertThat(d.panelType!!.value).isEqualTo(PanelType.T22)
        assertThat(d.heightMm!!.value).isWithin(1e-9).of(600.0)
        assertThat(d.lengthMm!!.value).isWithin(1e-9).of(1000.0)
        assertThat(d.reference!!.value).isEqualTo("NC-600-12")
    }

    @Test
    fun `conversion kcal par heure et marque approchante`() {
        val d = NameplateParser.parse("Ideal Standrd\n1200 kcal/h  ΔT 60")
        assertThat(d.powerW!!.value).isWithin(1e-6).of(1200 * 1.163)
        assertThat(d.rating!!.value).isEqualTo(RatingCondition.DIN4703)
        val fuzzy = NameplateParser.parse("ZEHNDR charleston")
        assertThat(fuzzy.manufacturer!!.value).isEqualTo("Zehnder")
        assertThat(fuzzy.manufacturer!!.confidence).isLessThan(0.85)
    }

    @Test
    fun `texte illisible - aucune proposition`() {
        assertThat(NameplateParser.parse("~~ ## ..").isEmpty).isTrue()
    }

    @Test
    fun `trois elements - periode en limite de recherche`() {
        for (w in listOf(200, 500, 1000)) {
            assertThat(ElementCounter.count(syntheticRadiator(3, width = w)).count).isEqualTo(3)
        }
    }

    @Test
    fun `puissance avec separateur de milliers et kW decimaux`() {
        assertThat(NameplateParser.parse("Puissance nominale 1 500 W").powerW!!.value).isWithin(1e-9).of(1500.0)
        assertThat(NameplateParser.parse("1.500 W").powerW!!.value).isWithin(1e-9).of(1500.0)
        assertThat(NameplateParser.parse("1\u00A0500 W").powerW!!.value).isWithin(1e-9).of(1500.0)
        assertThat(NameplateParser.parse("1 290 kcal/h").powerW!!.value).isWithin(1e-6).of(1290 * 1.163)
        assertThat(NameplateParser.parse("Puissance : 1,5 kW").powerW!!.value).isWithin(1e-9).of(1500.0)
        assertThat(NameplateParser.parse("1,12 kW").powerW!!.value).isWithin(1e-9).of(1120.0)
        assertThat(NameplateParser.parse("Type 22 600 W").powerW!!.value).isWithin(1e-9).of(600.0)
        assertThat(NameplateParser.parse("1650.5 W").powerW!!.value).isWithin(1e-9).of(1650.5)
    }

    @Test
    fun `reference - mot-cle entier uniquement`() {
        assertThat(NameplateParser.parse("Puissance nominale 1500 W\nRéf. 22-600-1000").reference!!.value).isEqualTo("22-600-1000")
        assertThat(NameplateParser.parse("Norme EN 442\n1500 W").reference).isNull()
        assertThat(NameplateParser.parse("N°AB123").reference!!.value).isEqualTo("AB123")
        assertThat(NameplateParser.parse("Refroidissement 12").reference).isNull()
    }
}
