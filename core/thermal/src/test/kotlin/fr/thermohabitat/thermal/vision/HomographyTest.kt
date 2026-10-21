package fr.thermohabitat.thermal.vision

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.hypot
import kotlin.random.Random

class HomographyTest {

    private fun quad(rnd: Random, scale: Double): List<ImagePoint> {
        // Rectangle perturbé (reste convexe) : coins d'une façade vue en perspective.
        val w = scale * rnd.nextDouble(0.5, 1.5); val h = scale * rnd.nextDouble(0.3, 1.0)
        val x0 = rnd.nextDouble(-scale, scale); val y0 = rnd.nextDouble(-scale, scale)
        fun j() = rnd.nextDouble(-0.15, 0.15) * scale * 0.5
        return listOf(
            ImagePoint(x0 + j(), y0 + j()), ImagePoint(x0 + w + j(), y0 + j()),
            ImagePoint(x0 + w + j(), y0 + h + j()), ImagePoint(x0 + j(), y0 + h + j()),
        )
    }

    private fun dist(a: ImagePoint, b: ImagePoint) = hypot(a.x - b.x, a.y - b.y)

    @Test
    fun `homographie aller-retour exacte`() {
        val rnd = Random(3)
        repeat(50) {
            val scale = listOf(1.0, 100.0, 4000.0)[it % 3]
            val src = quad(rnd, scale); val dst = quad(rnd, scale * 0.7)
            val h = Homography.fromPoints(src, dst)!!
            val inv = h.inverse()!!
            for (i in 0 until 4) {
                assertThat(dist(h.map(src[i]), dst[i])).isLessThan(1e-9 * scale)
                assertThat(dist(inv.map(dst[i]), src[i])).isLessThan(1e-9 * scale)
            }
            // Point intérieur quelconque : aller-retour et composition avec l'inverse = identité.
            val p = ImagePoint((src[0].x + src[2].x) / 2 + 0.1 * scale, (src[0].y + src[2].y) / 2)
            assertThat(dist(inv.map(h.map(p)), p)).isLessThan(1e-9 * scale)
            assertThat(dist((inv * h).map(p), p)).isLessThan(1e-9 * scale)
        }
    }

    @Test
    fun `rectangle vers quadrilatere`() {
        val q = listOf(ImagePoint(100.0, 80.0), ImagePoint(900.0, 140.0), ImagePoint(880.0, 600.0), ImagePoint(130.0, 700.0))
        val h = Homography.rectToQuad(800.0, 400.0, q)!!
        Homography.rectangle(800.0, 400.0).forEachIndexed { i, c -> assertThat(dist(h.map(c), q[i])).isLessThan(1e-9) }
        assertThat(h.m[8]).isWithin(1e-12).of(1.0)
    }

    @Test
    fun `configurations degenerees refusees`() {
        val rect = Homography.rectangle(10.0, 5.0)
        // Trois points alignés.
        assertThat(Homography.fromPoints(listOf(ImagePoint(0.0, 0.0), ImagePoint(5.0, 0.0), ImagePoint(10.0, 0.0), ImagePoint(0.0, 5.0)), rect)).isNull()
        // Quadrilatère croisé (« nœud papillon »).
        assertThat(Homography.fromPoints(listOf(ImagePoint(0.0, 0.0), ImagePoint(10.0, 5.0), ImagePoint(10.0, 0.0), ImagePoint(0.0, 5.0)), rect)).isNull()
        // Points confondus.
        assertThat(Homography.fromPoints(listOf(ImagePoint(1.0, 1.0), ImagePoint(1.0, 1.0), ImagePoint(10.0, 5.0), ImagePoint(0.0, 5.0)), rect)).isNull()
        // Quadrilatère concave.
        assertThat(Homography.fromPoints(listOf(ImagePoint(0.0, 0.0), ImagePoint(10.0, 0.0), ImagePoint(3.0, 2.0), ImagePoint(0.0, 5.0)), rect)).isNull()
        // Nombre de points incorrect.
        assertThat(Homography.fromPoints(rect.take(3), rect.take(3))).isNull()
        // Matrice singulière.
        assertThat(Homography(doubleArrayOf(1.0, 2.0, 3.0, 2.0, 4.0, 6.0, 0.0, 0.0, 1.0)).inverse()).isNull()
    }

    @Test
    fun `ordre des coins quel que soit l'ordre de pose`() {
        val expected = listOf(ImagePoint(120.0, 90.0), ImagePoint(980.0, 160.0), ImagePoint(950.0, 640.0), ImagePoint(140.0, 720.0))
        fun permutations(l: List<ImagePoint>): List<List<ImagePoint>> =
            if (l.size <= 1) listOf(l) else l.flatMap { p -> permutations(l - p).map { listOf(p) + it } }
        for (perm in permutations(expected)) assertThat(Homography.orderCorners(perm)).isEqualTo(expected)
        // Un contour croisé est remis en ordre.
        val crossed = listOf(expected[0], expected[2], expected[1], expected[3])
        assertThat(Homography.isConvex(crossed)).isFalse()
        assertThat(Homography.isConvex(Homography.orderCorners(crossed))).isTrue()
    }

    @Test
    fun `convexite dans les deux sens de parcours`() {
        val cw = Homography.rectangle(4.0, 3.0)
        assertThat(Homography.isConvex(cw)).isTrue()
        assertThat(Homography.isConvex(cw.reversed())).isTrue()
        assertThat(Homography.isConvex(listOf(ImagePoint(0.0, 0.0), ImagePoint(4.0, 0.0), ImagePoint(1.0, 1.0), ImagePoint(0.0, 3.0)))).isFalse()
        assertThat(Homography.isConvex(listOf(ImagePoint(0.0, 0.0), ImagePoint(Double.NaN, 0.0), ImagePoint(4.0, 3.0), ImagePoint(0.0, 3.0)))).isFalse()
    }
}
