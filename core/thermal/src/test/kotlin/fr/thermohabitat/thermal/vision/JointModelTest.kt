package fr.thermohabitat.thermal.vision

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class JointModelTest {

    /** Loi « vraie » : pas de 30 px à gauche décroissant vers la droite (perspective résiduelle), joints inclinés. */
    private val truth = JointModel(alpha = 30.0, beta = 100.0, gamma = 0.008, a = 0.02, b = -2e-5, y0 = 200.0)

    @Test
    fun `position et indice sont reciproques, translation et reindexation coherentes`() {
        for (k in listOf(-2.0, 0.0, 3.5, 17.0)) for (y in listOf(0.0, 150.0, 380.0)) {
            val x = truth.positionAt(k, y)
            assertThat(truth.indexAt(x, y)).isWithin(1e-9).of(k)
        }
        val moved = truth.shifted(-40.0, -25.0).reindexed(3.0)
        // Même joint physique : indice décalé de 3, coordonnées translatées de (−40, −25).
        assertThat(moved.positionAt(2.0, 100.0)).isWithin(1e-9).of(truth.positionAt(5.0, 125.0) - 40.0)
        assertThat(moved.referenceRow).isWithin(1e-9).of(175.0)
        // Pas décroissant de gauche à droite (perspective).
        assertThat(truth.pitchAt(0.0, 200.0)).isGreaterThan(truth.pitchAt(15.0, 200.0) * 1.15)
    }

    @Test
    fun `loi retrouvee malgre faux joints, joints masques et forte perspective`() {
        val rnd = Random(5)
        val cands = ArrayList<JointCandidate>()
        val dense = ArrayList<DenseBand>()
        val ys = (0 until 10).map { 40.0 + 36 * it }
        for ((band, y) in ys.withIndex()) {
            // Bandes basses masquées à droite (objet posé devant) ; bande haute dégagée, joint 9 caché par une feuille.
            val visible = (0..20).filter { k -> (band < 3 || k < 9) && !(band == 0 && k == 9) }
            for (k in visible) cands += JointCandidate(truth.positionAt(k.toDouble(), y) + rnd.nextDouble(-0.6, 0.6), y, 1.0, band)
            // Tiges sombres (faux joints) vues dans toutes les bandes, à 0,3 – 0,4 pas des vrais joints.
            for (kf in listOf(11.35, 14.3, 16.62)) cands += JointCandidate(truth.positionAt(kf, y), y, 1.2, band)
            // Signal dense : pics étroits aux joints visibles et aux tiges.
            val sig = DoubleArray(900)
            for (k in visible) bump(sig, truth.positionAt(k.toDouble(), y), 1.0)
            for (kf in listOf(11.35, 14.3, 16.62)) bump(sig, truth.positionAt(kf, y), 1.2)
            dense += DenseBand(y, 1.0, sig, truth.positionAt(0.0, 200.0), truth.positionAt(20.0, 200.0))
        }
        val span = truth.positionAt(0.0, 200.0)..truth.positionAt(20.0, 200.0)
        val fits = JointLawFit.fit(cands, dense, period = 25.0, yRef = 200.0, span = span)
        assertThat(fits).isNotEmpty()
        val m = fits.first().model
        // Indice d'origine arbitraire : on recale sur le joint 0 puis on compare toutes les positions (bande haute et basse).
        val k0 = m.indexAt(truth.positionAt(0.0, 200.0), 200.0)
        for (k in 0..20) for (y in listOf(40.0, 364.0)) {
            val pitch = truth.pitchAt(k.toDouble(), y)
            assertThat(abs(m.positionAt(k0 + k, y) - truth.positionAt(k.toDouble(), y)) / pitch).isLessThan(0.08)
        }
        // Aucune tige retenue comme joint.
        assertThat(fits.first().inliers.none { (c, _) -> c.weight > 1.1 }).isTrue()
    }

    private fun bump(sig: DoubleArray, x: Double, h: Double) {
        for (i in sig.indices) { val d = (i - x) / 2.0; sig[i] += h * kotlin.math.exp(-d * d) }
    }
}
