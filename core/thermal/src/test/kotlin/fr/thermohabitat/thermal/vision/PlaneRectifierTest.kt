package fr.thermohabitat.thermal.vision

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class PlaneRectifierTest {

    @Test
    fun `vue de face - rapport des cotes`() {
        val q = listOf(ImagePoint(300.0, 300.0), ImagePoint(900.0, 300.0), ImagePoint(900.0, 600.0), ImagePoint(300.0, 600.0))
        val a = PlaneRectifier.estimateAspectRatio(q, 1200, 900)
        assertThat(a.ratio).isWithin(1e-9).of(2.0)
        assertThat(a.frontal).isTrue()
        assertThat(a.focalSource).isEqualTo(FocalSource.NOT_NEEDED)
        assertThat(a.viewAngleDeg).isLessThan(2.0)
    }

    @Test
    fun `rapport d'aspect sans reference a 8 pour cent jusqu'a 35 degres avec focale fausse de 15 pour cent`() {
        // Coins exacts : seule l'hypothèse de focale (≈ 0,72 × grand côté) est en défaut.
        val worst = ArrayList<String>()
        for (ff in listOf(0.72 * 0.85, 0.72, 0.72 * 1.15)) for (yaw in listOf(0.0, 20.0, 35.0, -35.0)) for (tilt in listOf(0.0, 5.0, 10.0)) {
            for (n in listOf(8, 13, 30)) {
                val sc = SyntheticRadiatorScene(n, yawDeg = yaw, tiltDeg = tilt, focalFactor = ff)
                val a = PlaneRectifier.estimateAspectRatio(sc.trueCorners, sc.imageWidth, sc.imageHeight)
                val err = (a.ratio - sc.trueAspect) / sc.trueAspect
                if (abs(err) > 0.08) worst += "ff=$ff lacet=$yaw tangage=$tilt N=$n : ${"%.1f".format(100 * err)} %"
                // L'incertitude annoncée couvre l'écart (focale par défaut ±18 %).
                assertThat(abs(err)).isAtMost(3 * a.relativeUncertainty + 0.01)
            }
        }
        assertThat(worst).isEmpty()
    }

    @Test
    fun `focale deduite d'une perspective a deux points de fuite`() {
        val sc = SyntheticRadiatorScene(13, yawDeg = 35.0, tiltDeg = 20.0, focalFactor = 0.8)
        val f = PlaneRectifier.focalFromQuad(sc.trueCorners, sc.imageWidth, sc.imageHeight)!!
        assertThat(f).isWithin(0.01 * sc.focalPx).of(sc.focalPx)
        val a = PlaneRectifier.estimateAspectRatio(sc.trueCorners, sc.imageWidth, sc.imageHeight)
        assertThat(a.focalSource).isEqualTo(FocalSource.DEDUCED)
        assertThat(a.ratio).isWithin(0.01 * sc.trueAspect).of(sc.trueAspect)
        assertThat(a.viewAngleDeg).isWithin(3.0).of(40.0)
        // Un seul point de fuite (lacet pur) : focale indéterminée, on se rabat sur celle fournie.
        val yawOnly = SyntheticRadiatorScene(13, yawDeg = 30.0, tiltDeg = 0.0)
        val provided = PlaneRectifier.estimateAspectRatio(yawOnly.trueCorners, 1200, 900, focalPx = yawOnly.focalPx)
        assertThat(provided.focalSource).isEqualTo(FocalSource.PROVIDED)
        assertThat(provided.ratio).isWithin(0.01 * yawOnly.trueAspect).of(yawOnly.trueAspect)
    }

    @Test
    fun `focale equivalente 24x36`() {
        // 26 mm équivalent sur une photo 4:3 : ≈ 0,75 × grand côté.
        assertThat(PlaneRectifier.focalPxFrom35mm(26.0, 4000, 3000) / 4000).isWithin(0.01).of(0.751)
        assertThat(PlaneRectifier.defaultFocalPx(4000, 3000)).isWithin(1e-9).of(2880.0)
    }

    @Test
    fun `redressement bilineaire d'un motif plan vu de biais`() {
        // Motif sinusoïdal plan (période 50 mm) photographié de biais ; la face redressée doit le restituer.
        val sc = SyntheticRadiatorScene(10, pitchMm = 60.0, heightMm = 400.0, yawDeg = 35.0, tiltDeg = 8.0, noise = 0.0)
        val planeToImage = Homography.fromPoints(Homography.rectangle(sc.lengthMm, sc.heightMm), sc.trueCorners)!!
        val imageToPlane = planeToImage.inverse()!!
        val w = sc.imageWidth; val h = sc.imageHeight
        fun pattern(x: Double) = 128 + 80 * sin(2 * PI * x / 50.0)
        val img = GrayImage(w, h, FloatArray(w * h) { i ->
            val p = imageToPlane.map(i % w + 0.5, i / w + 0.5)
            pattern(p.x).toFloat()
        })
        val fw = 600; val fh = 400
        val face = PlaneRectifier.rectify(img, sc.trueCorners, fw, fh)
        var err = 0.0
        for (y in 0 until fh) for (x in 0 until fw) err += abs(face[x, y] - pattern((x + 0.5) * sc.lengthMm / fw))
        assertThat(err / (fw * fh)).isLessThan(2.0)
        // Identité : un contour égal à l'image restitue l'image.
        val ramp = GrayImage(100, 50, FloatArray(5000) { (it % 100 + 2 * (it / 100)).toFloat() })
        val same = PlaneRectifier.rectify(ramp, Homography.rectangle(100.0, 50.0), 100, 50)
        for (i in 0 until 5000) assertThat(same.pixels[i]).isWithin(1e-3f).of(ramp.pixels[i])
    }

    @Test
    fun `taille de la face redressee bornee`() {
        val q = listOf(ImagePoint(0.0, 0.0), ImagePoint(3000.0, 0.0), ImagePoint(3000.0, 1000.0), ImagePoint(0.0, 1000.0))
        val s = PlaneRectifier.faceSize(q, 3.0)
        assertThat(s.width).isAtMost(PlaneRectifier.MAX_FACE_SIDE)
        assertThat(s.width.toDouble() / s.height).isWithin(0.02).of(3.0)
        val tall = PlaneRectifier.faceSize(q, 0.2)
        assertThat(tall.height).isAtMost(PlaneRectifier.MAX_FACE_SIDE)
    }
}
