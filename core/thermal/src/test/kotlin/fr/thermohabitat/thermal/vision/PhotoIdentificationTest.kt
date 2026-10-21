package fr.thermohabitat.thermal.vision

import com.google.common.truth.Truth.assertThat
import fr.thermohabitat.thermal.model.ValueOrigin
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random

/**
 * Identification géométrique sur photos synthétiques réalistes (voir [SyntheticRadiatorScene]) : radiateur en fonte
 * projeté par une caméra sténopé tournée, coins pointés avec une erreur de ±1,5 % de la taille du radiateur.
 */
class PhotoIdentificationTest {

    /** Pas et hauteur (mm) de radiateurs plausibles pour chaque nombre d'éléments testé. */
    private val radiators = mapOf(
        5 to (60.0 to 600.0), 8 to (60.0 to 580.0), 10 to (45.0 to 700.0),
        13 to (60.0 to 600.0), 20 to (50.0 to 500.0), 30 to (60.0 to 450.0),
    )

    private fun scene(n: Int, yaw: Double, tilt: Double, seed: Int, ff: Double = 0.72) = radiators.getValue(n).let { (p, h) ->
        SyntheticRadiatorScene(n, pitchMm = p, heightMm = h, yawDeg = yaw, tiltDeg = tilt, focalFactor = ff, seed = seed)
    }

    private fun rel(value: Double, truth: Double) = (value - truth) / truth

    @Test
    fun `nombre d'elements correct jusqu'a 35 degres de biais`() {
        val failures = ArrayList<String>()
        var case = 0
        for (n in radiators.keys) for (yaw in listOf(0.0, 20.0, -20.0, 35.0, -35.0)) {
            val tilt = listOf(0.0, 5.0, 10.0)[case % 3]
            val sc = scene(n, yaw, tilt, seed = 100 + case++)
            val r = PhotoIdentifier.identify(sc.image, sc.pointedCorners)
            if (r.count != n || r.boundariesFacePx.size != n + 1 || !r.counting.reliable) {
                failures += "N=$n lacet=$yaw tangage=$tilt → ${r.count} (confiance ${"%.2f".format(r.countConfidence)}, alternatives ${r.alternatives.map { it.count }})"
            }
        }
        assertThat(failures).isEmpty()
    }

    @Test
    fun `radiateur en fonte de 13 elements photographie de biais`() {
        // Le cas signalé : 13 éléments, photo prise en latéral, focale réelle inconnue (±15 %).
        var case = 0
        for (yaw in listOf(-35.0, -25.0, 25.0, 35.0)) for (tilt in listOf(0.0, 5.0, 10.0)) {
            val ff = listOf(0.72 * 0.85, 0.72, 0.72 * 1.15)[case % 3]
            val sc = scene(13, yaw, tilt, seed = 500 + case++, ff = ff)
            val r = PhotoIdentifier.identify(sc.image, sc.pointedCorners)
            assertThat(r.count).isEqualTo(13)
            assertThat(r.counting.reliable).isTrue()
            assertThat(r.boundariesFacePx).hasSize(14)
            assertThat(r.boundariesFacePx).isInOrder()
            // Pas / hauteur (sans échelle) : 60 / 600 à la précision du rapport d'aspect près.
            assertThat(abs(rel(r.pitchToHeightRatio!!, 0.1))).isLessThan(0.10)
            // Sans référence : aucune dimension inventée.
            assertThat(r.lengthMm).isNull()
            assertThat(r.dimensionsOrigin).isEqualTo(ValueOrigin.UNKNOWN)
            assertThat(r.warnings.joinToString()).contains("Sans référence d'échelle")
        }
    }

    @Test
    fun `a 45 degres le bon nombre est propose ou figure parmi les alternatives`() {
        var case = 0
        for (n in radiators.keys) for (yaw in listOf(45.0, -45.0)) {
            val sc = scene(n, yaw, listOf(0.0, 8.0)[case % 2], seed = 700 + case++)
            val r = PhotoIdentifier.identify(sc.image, sc.pointedCorners)
            assertThat(r.count == n || r.alternatives.any { it.count == n }).isTrue()
        }
    }

    @Test
    fun `carte bancaire plaquee sur la facade a 35 degres - hauteur et longueur a 3 pour cent`() {
        // Photo de téléphone réduite à 2000 px : la carte mesure 70 à 110 px de large.
        for (n in listOf(8, 13, 20)) for (yaw in listOf(-35.0, 35.0)) {
            val (pitch, height) = 60.0 to 600.0
            val sc = SyntheticRadiatorScene(n, pitchMm = pitch, heightMm = height, yawDeg = yaw, tiltDeg = 5.0, seed = 31 * n + yaw.toInt(),
                cardAtMm = ImagePoint(n * pitch / 2 - 43.0, 0.62 * height), imageWidth = 2000, imageHeight = 1500)
            val r = PhotoIdentifier.identify(sc.image, sc.pointedCorners, ScaleReference.PlanarObject(ScaleObject.BANK_CARD, sc.pointedCardCorners!!))
            assertThat(r.count).isEqualTo(n)
            assertThat(r.dimensionsOrigin).isEqualTo(ValueOrigin.MEASURED)
            assertThat(r.aspectMeasured).isTrue()
            val l = r.lengthMm!!; val h = r.heightMm!!; val p = r.pitchMm!!
            assertThat(abs(rel(l.valueMm, sc.lengthMm))).isAtMost(0.03)
            assertThat(abs(rel(h.valueMm, height))).isAtMost(0.03)
            assertThat(abs(rel(p.valueMm, pitch))).isAtMost(0.03)
            // Incertitude honnête : l'écart reste dans ±2,5 u.
            assertThat(abs(l.valueMm - sc.lengthMm)).isAtMost(2.5 * l.uncertaintyMm)
            assertThat(abs(h.valueMm - height)).isAtMost(2.5 * h.uncertaintyMm)
            assertThat(abs(rel(r.bodyAspectRatio, sc.trueAspect))).isAtMost(0.04)
        }
    }

    @Test
    fun `carte trop petite dans l'image - avertissement et incertitude elargie`() {
        val sc = SyntheticRadiatorScene(20, pitchMm = 60.0, heightMm = 600.0, yawDeg = 30.0, tiltDeg = 5.0, seed = 9,
            cardAtMm = ImagePoint(557.0, 372.0))
        val r = PhotoIdentifier.identify(sc.image, sc.pointedCorners, ScaleReference.PlanarObject(ScaleObject.BANK_CARD, sc.pointedCardCorners!!))
        assertThat(r.count).isEqualTo(20)
        assertThat(r.warnings.joinToString()).contains("Référence petite")
        assertThat(r.lengthMm!!.relativeUncertainty).isGreaterThan(0.025)
        assertThat(abs(r.lengthMm!!.valueMm - sc.lengthMm)).isAtMost(2.5 * r.lengthMm.uncertaintyMm)
    }

    @Test
    fun `rapport d'aspect du corps sans reference a 8 pour cent avec coins pointes`() {
        var case = 0
        for (ff in listOf(0.72 * 0.85, 0.72 * 1.15)) for (yaw in listOf(20.0, 35.0, -35.0)) for (n in listOf(8, 13, 30)) {
            val sc = scene(n, yaw, listOf(0.0, 10.0)[case % 2], seed = 900 + case++, ff = ff)
            val r = PhotoIdentifier.identify(sc.image, sc.pointedCorners)
            assertThat(abs(rel(r.bodyAspectRatio, sc.trueAspect))).isAtMost(0.08)
            assertThat(r.aspectMeasured).isFalse()
        }
    }

    @Test
    fun `references d'echelle a deux points, dimension mesuree et pas standard`() {
        val sc = SyntheticRadiatorScene(13, yawDeg = 30.0, tiltDeg = 5.0, seed = 77)
        val img = sc.image
        val rnd = Random(4)
        fun pointed(x: Double, y: Double) = sc.project(x, y).let { ImagePoint(it.x + rnd.nextDouble(-1.0, 1.0), it.y + rnd.nextDouble(-1.0, 1.0)) }

        // Mètre ruban tendu horizontalement sur la façade (640 mm lus).
        val tape = ScaleReference.tapeMeasure(pointed(70.0, 200.0), pointed(710.0, 200.0), 640.0)
        val t = PhotoIdentifier.identify(img, sc.pointedCorners, tape)
        assertThat(t.count).isEqualTo(13)
        assertThat(t.dimensionsOrigin).isEqualTo(ValueOrigin.MEASURED)
        assertThat(abs(rel(t.lengthMm!!.valueMm, 780.0))).isAtMost(0.03)
        assertThat(abs(rel(t.heightMm!!.valueMm, 600.0))).isAtMost(0.08)
        assertThat(abs(rel(t.pitchMm!!.valueMm, 60.0))).isAtMost(0.03)

        // Hauteur mesurée au mètre : longueur déduite du rapport d'aspect.
        val m = PhotoIdentifier.identify(img, sc.pointedCorners, ScaleReference.MeasuredDimension(RadiatorDimension.HEIGHT, 600.0, 5.0))
        assertThat(m.heightMm!!.valueMm).isEqualTo(600.0)
        assertThat(abs(rel(m.lengthMm!!.valueMm, 780.0))).isAtMost(0.08)
        assertThat(abs(rel(m.pitchMm!!.valueMm, 60.0))).isAtMost(0.08)
        val ml = PhotoIdentifier.identify(img, sc.pointedCorners, ScaleReference.MeasuredDimension(RadiatorDimension.LENGTH, 780.0, 5.0))
        assertThat(abs(rel(ml.heightMm!!.valueMm, 600.0))).isAtMost(0.08)
        assertThat(ml.pitchMm!!.valueMm).isWithin(1e-9).of(60.0)

        // Pas standard (estimation) : longueur = N × pas, origine ESTIMÉE.
        val s = PhotoIdentifier.identify(img, sc.pointedCorners, ScaleReference.StandardPitch(60.0, 5.0))
        assertThat(s.dimensionsOrigin).isEqualTo(ValueOrigin.ESTIMATED)
        assertThat(s.lengthMm!!.valueMm).isWithin(1e-9).of(780.0)
        assertThat(s.lengthMm.uncertaintyMm).isWithin(1e-9).of(65.0)
        assertThat(abs(rel(s.heightMm!!.valueMm, 600.0))).isAtMost(0.08)
        assertThat(s.warnings.joinToString()).contains("ESTIMÉES")

        // Pièce : petite référence signalée.
        val c = PhotoIdentifier.identify(img, sc.pointedCorners, ScaleReference.coin(ScaleObject.COIN_2_EURO, pointed(300.0, 300.0), pointed(325.75, 300.0)))
        assertThat(c.warnings.joinToString()).contains("petite référence")
    }

    @Test
    fun `nombre corrige par l'utilisateur - limites et dimensions recalculees`() {
        val sc = SyntheticRadiatorScene(13, yawDeg = -30.0, tiltDeg = 5.0, seed = 12)
        val r = PhotoIdentifier.identify(sc.image, sc.pointedCorners, ScaleReference.MeasuredDimension(RadiatorDimension.LENGTH, 780.0))
        assertThat(r.count).isEqualTo(13)
        val b12 = r.boundariesFor(12)
        assertThat(b12).hasSize(13)
        assertThat(b12.first()).isEqualTo(r.extentFacePx.start)
        assertThat(b12.last()).isEqualTo(r.extentFacePx.endInclusive)
        assertThat(r.dimensionsFor(12).pitchMm!!.valueMm).isWithin(1e-9).of(65.0)
        // Les traits reprojetés sur la photo passent par les joints réels (à moins de 20 % du pas apparent).
        val lines = r.boundaryLinesInImage()
        assertThat(lines).hasSize(14)
        for (k in 1 until 13) {
            val (top, bottom) = lines[k]
            val trueTop = sc.project(k * 60.0, 0.0); val trueBottom = sc.project(k * 60.0, 600.0)
            val pitchImg = hypot(sc.project(k * 60.0 + 60, 300.0).x - sc.project(k * 60.0, 300.0).x, 0.0)
            val mid = ImagePoint((top.x + bottom.x) / 2, (top.y + bottom.y) / 2)
            val trueMid = ImagePoint((trueTop.x + trueBottom.x) / 2, (trueTop.y + trueBottom.y) / 2)
            assertThat(abs(mid.x - trueMid.x)).isLessThan(0.2 * pitchImg)
        }
    }

    @Test
    fun `images uniformes ou bruit pur - pas de faux comptage`() {
        val quad = listOf(ImagePoint(200.0, 200.0), ImagePoint(1000.0, 240.0), ImagePoint(990.0, 700.0), ImagePoint(210.0, 680.0))
        val uniform = PhotoIdentifier.identify(GrayImage(1200, 900, FloatArray(1200 * 900) { 150f }), quad, ScaleReference.StandardPitch(60.0, 5.0))
        assertThat(uniform.count).isNull()
        assertThat(uniform.lengthMm).isNull()
        assertThat(uniform.dimensionsOrigin).isEqualTo(ValueOrigin.UNKNOWN)
        for (seed in 1..5) {
            val rnd = Random(seed)
            val noise = GrayImage(1200, 900, FloatArray(1200 * 900) { (128 + rnd.nextDouble(-50.0, 50.0)).toFloat() })
            val r = PhotoIdentifier.identify(noise, quad)
            assertThat(r.count).isNull()
            assertThat(r.alternatives).isEmpty()
            assertThat(r.messages.joinToString()).contains("comptez")
        }
    }

    @Test
    fun `radiateur sombre a joints clairs et motif double par element`() {
        // Mur clair visible entre les éléments : polarité inversée.
        for (yaw in listOf(0.0, 35.0)) {
            val sc = SyntheticRadiatorScene(13, yawDeg = yaw, darkRadiator = true, seed = 21)
            assertThat(PhotoIdentifier.identify(sc.image, sc.pointedCorners).count).isEqualTo(13)
        }
        // Rainure au milieu de chaque colonne (deux motifs par élément) : jamais 26 au lieu de 13.
        for (groove in listOf(0.3, 0.6)) for (yaw in listOf(0.0, 35.0)) {
            val sc = SyntheticRadiatorScene(13, yawDeg = yaw, grooveDepth = groove, seed = 22)
            val r = PhotoIdentifier.identify(sc.image, sc.pointedCorners)
            assertThat(r.count).isEqualTo(13)
        }
    }

    @Test
    fun `resultat deterministe`() {
        val sc = SyntheticRadiatorScene(10, yawDeg = 25.0, tiltDeg = 6.0, seed = 3, cardAtMm = ImagePoint(257.0, 360.0))
        val ref = ScaleReference.PlanarObject(ScaleObject.BANK_CARD, sc.pointedCardCorners!!)
        val a = PhotoIdentifier.identify(sc.image, sc.pointedCorners, ref)
        val b = PhotoIdentifier.identify(sc.image, sc.pointedCorners.reversed(), ref)
        assertThat(b.count).isEqualTo(a.count)
        assertThat(b.boundariesFacePx).isEqualTo(a.boundariesFacePx)
        assertThat(b.lengthMm).isEqualTo(a.lengthMm)
        assertThat(b.heightMm).isEqualTo(a.heightMm)
        assertThat(b.warnings).isEqualTo(a.warnings)
    }

    @Test
    fun `contour invalide refuse avec un message en francais`() {
        val img = GrayImage(400, 300, FloatArray(400 * 300) { 100f })
        val e1 = assertThrows(IllegalArgumentException::class.java) {
            PhotoIdentifier.identify(img, listOf(ImagePoint(0.0, 0.0), ImagePoint(100.0, 0.0), ImagePoint(100.0, 100.0)))
        }
        assertThat(e1.message).contains("4 coins")
        assertThrows(IllegalArgumentException::class.java) {
            PhotoIdentifier.identify(img, listOf(ImagePoint(10.0, 10.0), ImagePoint(15.0, 10.0), ImagePoint(15.0, 14.0), ImagePoint(10.0, 14.0)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PhotoIdentifier.identify(img, listOf(ImagePoint(10.0, 10.0), ImagePoint(200.0, 10.0), ImagePoint(200.0, 200.0), ImagePoint(10.0, 200.0)),
                ScaleReference.KnownLength(ImagePoint(50.0, 50.0), ImagePoint(50.0, 50.0), 100.0))
        }
    }

    @Test
    fun `analyse rapide`() {
        val sc = SyntheticRadiatorScene(20, yawDeg = 30.0, seed = 5)
        val img = sc.image
        PhotoIdentifier.identify(img, sc.pointedCorners) // préchauffage JIT
        val t0 = System.nanoTime()
        repeat(3) { PhotoIdentifier.identify(img, sc.pointedCorners) }
        val ms = (System.nanoTime() - t0) / 3e6
        assertThat(ms).isLessThan(1500.0)
    }
}
