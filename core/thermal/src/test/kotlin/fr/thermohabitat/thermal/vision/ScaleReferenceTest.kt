package fr.thermohabitat.thermal.vision

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class ScaleReferenceTest {

    @Test
    fun `catalogue des objets de reference`() {
        assertThat(ScaleObject.BANK_CARD.longMm).isEqualTo(85.60)
        assertThat(ScaleObject.BANK_CARD.shortMm).isEqualTo(53.98)
        assertThat(ScaleObject.A4_SHEET.longMm to ScaleObject.A4_SHEET.shortMm).isEqualTo(297.0 to 210.0)
        assertThat(ScaleObject.A5_SHEET.longMm to ScaleObject.A5_SHEET.shortMm).isEqualTo(210.0 to 148.0)
        assertThat(ScaleObject.EURO_5.longMm to ScaleObject.EURO_5.shortMm).isEqualTo(120.0 to 62.0)
        assertThat(ScaleObject.EURO_10.longMm to ScaleObject.EURO_10.shortMm).isEqualTo(127.0 to 67.0)
        assertThat(ScaleObject.EURO_20.longMm to ScaleObject.EURO_20.shortMm).isEqualTo(133.0 to 72.0)
        assertThat(ScaleObject.EURO_50.longMm to ScaleObject.EURO_50.shortMm).isEqualTo(140.0 to 77.0)
        assertThat(ScaleObject.EURO_100.longMm to ScaleObject.EURO_100.shortMm).isEqualTo(147.0 to 77.0)
        assertThat(ScaleObject.COIN_1_EURO.longMm).isEqualTo(23.25)
        assertThat(ScaleObject.COIN_2_EURO.longMm).isEqualTo(25.75)
        assertThat(ScaleObject.COIN_50_CENTS.longMm).isEqualTo(24.25)
        assertThat(ScaleObject.entries.filter { it.shape == ScaleObjectShape.DISC }.all { it.pointCount == 2 }).isTrue()
        assertThat(ScaleObject.entries.filter { it.shape == ScaleObjectShape.RECTANGLE }.all { it.pointCount == 4 }).isTrue()
    }

    @Test
    fun `homographie metrique exacte depuis un objet a 4 coins sans focale`() {
        // Carte plaquée sur la façade, photo à 35° de biais et 8° de tangage : dimensions exactes du radiateur.
        for (ff in listOf(0.55, 0.72, 1.1)) {
            val sc = SyntheticRadiatorScene(13, yawDeg = 35.0, tiltDeg = 8.0, focalFactor = ff, cardAtMm = ImagePoint(300.0, 380.0))
            val h = ScaleCalculator.metricHomography(ScaleObject.BANK_CARD, sc.trueCardCorners!!.shuffled(kotlin.random.Random(1)), landscape = true)!!
            val d = ScaleCalculator.quadDimensions(h, sc.trueCorners)
            assertThat(d.lengthMm).isWithin(1e-6 * sc.lengthMm).of(sc.lengthMm)
            assertThat(d.heightMm).isWithin(1e-6 * sc.heightMm).of(sc.heightMm)
            assertThat(d.maxAngleErrorDeg).isLessThan(1e-6)
        }
    }

    @Test
    fun `references invalides refusees`() {
        val p = ImagePoint(0.0, 0.0)
        assertThrows(IllegalArgumentException::class.java) { ScaleReference.PlanarObject(ScaleObject.COIN_1_EURO, listOf(p, p, p, p)) }
        assertThrows(IllegalArgumentException::class.java) { ScaleReference.PlanarObject(ScaleObject.BANK_CARD, listOf(p, p, p)) }
        assertThrows(IllegalArgumentException::class.java) { ScaleReference.KnownLength(p, p, 0.0) }
        assertThrows(IllegalArgumentException::class.java) { ScaleReference.MeasuredDimension(RadiatorDimension.HEIGHT, -5.0) }
        assertThrows(IllegalArgumentException::class.java) { ScaleReference.StandardPitch(0.0, 5.0) }
        assertThrows(IllegalArgumentException::class.java) { ScaleReference.coin(ScaleObject.BANK_CARD, p, p) }
        val coin = ScaleReference.coin(ScaleObject.COIN_2_EURO, p, ImagePoint(30.0, 0.0))
        assertThat(coin.lengthMm).isEqualTo(25.75)
        assertThat(coin.smallObject).isTrue()
    }
}
