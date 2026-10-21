package fr.thermohabitat.thermal.vision

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import kotlin.math.abs

/**
 * Comptage sur photos synthétiques encombrées (voir [SyntheticRadiatorScene.Occluder]) : dossiers de chaises sur la
 * moitié basse, objets sombres, clairs ou imprimés et feuille sur la bande haute, tiges sombres verticales devant le
 * radiateur (faux joints) ; coin caché mal placé ; forte perspective ; petite taille.
 * Exigences : bon nombre, ou nombre à ±1 signalé avec la bonne valeur en alternative ; aucune alternative absurde ;
 * traits sur les vrais joints.
 */
class OcclusionCountingTest {

    /** Objets devant un radiateur de [n] éléments de [pitch] mm et [h] mm de haut (repère de la façade, mm). */
    private fun clutter(n: Int, pitch: Double, h: Double): List<SyntheticRadiatorScene.Occluder> {
        val l = n * pitch
        return listOf(
            SyntheticRadiatorScene.stem(0.69 * l + 0.37 * pitch, -0.02 * h, 0.42 * h, 0.1 * pitch),
            SyntheticRadiatorScene.stem(0.82 * l + 0.61 * pitch, -0.02 * h, 0.40 * h, 0.08 * pitch, lean = -0.02),
            SyntheticRadiatorScene.stem(0.91 * l + 0.23 * pitch, 0.0, 0.38 * h, 0.1 * pitch),
            SyntheticRadiatorScene.leaf(0.47 * l, 0.06 * h, 0.9 * pitch, 0.06 * h),
            SyntheticRadiatorScene.chairBack(0.27 * l, 1.0 * h, 0.18 * l, 0.5 * h),
            SyntheticRadiatorScene.chairBack(0.73 * l, 0.97 * h, 0.20 * l, 0.52 * h),
            SyntheticRadiatorScene.box(0.42 * l, 0.13 * h, 0.58 * l, 0.48 * h, 28.0),
            SyntheticRadiatorScene.box(0.60 * l, 0.11 * h, 0.66 * l, 0.44 * h, 150.0),
            SyntheticRadiatorScene.box(0.74 * l, 0.18 * h, 0.97 * l, 0.42 * h, 210.0, printed = true),
        )
    }

    /** Part de la moitié basse du corps masquée par les objets (échantillonnage de la façade). */
    private fun lowerHalfCovered(sc: SyntheticRadiatorScene, occ: List<SyntheticRadiatorScene.Occluder>): Double {
        var hit = 0; var all = 0
        for (i in 0 until 60) for (j in 0 until 30) {
            val x = sc.lengthMm * (i + 0.5) / 60; val y = sc.heightMm * (0.5 + 0.5 * (j + 0.5) / 30)
            all++; if (occ.any { it.contains(x, y) }) hit++
        }
        return hit.toDouble() / all
    }

    /** Écart moyen et maximal (en pas) entre les joints de la loi et les vrais joints, sur la rangée à 10 % de la hauteur. */
    private fun jointErrors(r: PhotoIdentification, sc: SyntheticRadiatorScene): Pair<Double, Double> {
        val m = r.jointModel!!
        val e = (0..sc.elements).map { k ->
            val p = r.imageToFace.map(sc.project(k * sc.pitchMm, 0.1 * sc.heightMm))
            val q = r.imageToFace.map(sc.project((k + 1) * sc.pitchMm, 0.1 * sc.heightMm))
            (0..r.count!!).minOf { abs(m.positionAt(it.toDouble(), p.y) - p.x) } / abs(q.x - p.x)
        }
        return e.average() to e.max()
    }

    private fun check(label: String, r: PhotoIdentification, sc: SyntheticRadiatorScene, failures: MutableList<String>) {
        val n = sc.elements
        val count = r.count
        val ok = count == n || (count != null && r.plusOrMinusOne && abs(count - n) == 1 && r.alternatives.any { it.count == n })
        // Aucune alternative absurde : sans motif double, seulement un élément de plus ou de moins.
        val absurd = r.alternatives.filter { abs(it.count - (count ?: n)) > 1 }
        val err = if (count != null && r.jointModel != null) jointErrors(r, sc) else 9.0 to 9.0
        if (!ok || absurd.isNotEmpty() || err.first > 0.15 || r.countConfidence < ElementCounter.RELIABLE_CONFIDENCE) {
            failures += "$label N=$n → $count ±1=${r.plusOrMinusOne} confiance ${"%.2f".format(r.countConfidence)} " +
                "alternatives ${r.alternatives.map { it.count }} écart des joints ${"%.2f".format(err.first)} pas"
        }
    }

    @Test
    fun `objets devant le radiateur - chaises, pot, boite et tiges`() {
        val failures = ArrayList<String>()
        var seed = 40
        for (n in listOf(13, 20)) for (yaw in listOf(-30.0, 30.0)) {
            val occ = clutter(n, 60.0, 600.0)
            val sc = SyntheticRadiatorScene(n, pitchMm = 60.0, heightMm = 600.0, yawDeg = yaw, tiltDeg = 5.0, seed = seed++, occluders = occ)
            val covered = lowerHalfCovered(sc, occ)
            assertThat(covered).isAtLeast(0.5); assertThat(covered).isAtMost(0.75)
            val r = PhotoIdentifier.identify(sc.image, sc.pointedCorners)
            check("lacet $yaw", r, sc, failures)
            assertThat(r.analyzedRowsFacePx).isNotEmpty()
        }
        assertWithMessage(failures.joinToString("\n")).that(failures).isEmpty()
    }

    @Test
    fun `bandes entierement masquees ignorees et exposees pour l'affichage`() {
        // Dossier de canapé uni sur toute la largeur, bas du radiateur (40 %) : ces rangées ne sont pas analysées.
        val h = 600.0; val l = 16 * 60.0
        val occ = listOf(SyntheticRadiatorScene.box(-100.0, 0.6 * h, l + 100, 1.2 * h, 120.0), SyntheticRadiatorScene.stem(0.4 * l + 21, 0.0, 0.6 * h, 6.0))
        val sc = SyntheticRadiatorScene(16, pitchMm = 60.0, heightMm = h, yawDeg = 25.0, tiltDeg = 5.0, seed = 95, occluders = occ)
        val r = PhotoIdentifier.identify(sc.image, sc.pointedCorners)
        assertThat(r.count).isEqualTo(16)
        assertThat(r.counting.reliable).isTrue()
        val rows = r.analyzedRowsFacePx
        assertThat(rows).isNotEmpty()
        // Rangée de la face correspondant au haut du canapé (milieu du radiateur).
        val sofaTop = r.imageToFace.map(sc.project(0.5 * l, 0.62 * h)).y
        assertThat(rows.maxOf { it.endInclusive }).isLessThan(sofaTop + 0.06 * r.faceHeight)
    }

    @Test
    fun `coin cache derriere une chaise place 12 a 14 pour cent trop haut ou trop bas`() {
        val failures = ArrayList<String>()
        var seed = 60
        for (n in listOf(13, 20)) for (yaw in listOf(-30.0, 30.0)) for (dy in listOf(-0.12, 0.14)) {
            val sc = SyntheticRadiatorScene(n, pitchMm = 60.0, heightMm = 600.0, yawDeg = yaw, tiltDeg = 5.0, seed = seed++, occluders = clutter(n, 60.0, 600.0))
            val corners = sc.pointedCorners.toMutableList()
            val hImg = sc.trueCorners[2].y - sc.trueCorners[1].y
            corners[2] = ImagePoint(corners[2].x, corners[2].y + dy * hImg)
            val r = PhotoIdentifier.identify(sc.image, corners)
            check("lacet $yaw coin bas droit ${(100 * dy).toInt()} %", r, sc, failures)
            if (r.count != null) {
                // Traits cohérents : limites du nombre proposé = celles de la loi ; autre nombre : bornes conservées.
                assertThat(r.boundariesFor(r.count!!)).isEqualTo(r.boundariesFacePx)
                val other = r.boundariesFor(r.count!! + 1)
                assertThat(other).hasSize(r.count!! + 2)
                assertThat(other.first()).isEqualTo(r.extentFacePx.start)
                assertThat(other.last()).isEqualTo(r.extentFacePx.endInclusive)
                assertThat(other).isInOrder()
            }
        }
        assertWithMessage(failures.joinToString("\n")).that(failures).isEmpty()
    }

    @Test
    fun `petite taille et forte perspective`() {
        val failures = ArrayList<String>()
        var seed = 80
        for ((n, fill) in listOf(13 to 0.27, 20 to 0.38)) for (yaw in listOf(-38.0, 38.0)) {
            val sc = SyntheticRadiatorScene(n, pitchMm = 60.0, heightMm = 500.0, yawDeg = yaw, tiltDeg = 4.0, fill = fill, seed = seed++,
                occluders = clutter(n, 60.0, 500.0))
            // Pas apparent sur la photo : 12 à 30 px, variant d'au moins 25 % d'un bout à l'autre.
            val pa = sc.project(sc.pitchMm, 0.5 * sc.heightMm).x - sc.project(0.0, 0.5 * sc.heightMm).x
            val pb = sc.project(sc.lengthMm, 0.5 * sc.heightMm).x - sc.project(sc.lengthMm - sc.pitchMm, 0.5 * sc.heightMm).x
            assertThat(maxOf(pa, pb) / minOf(pa, pb)).isAtLeast(1.25)
            assertThat((pa + pb) / 2).isAtLeast(15.0); assertThat((pa + pb) / 2).isAtMost(25.0)
            check("lacet $yaw", PhotoIdentifier.identify(sc.image, sc.pointedCorners), sc, failures)
        }
        assertWithMessage(failures.joinToString("\n")).that(failures).isEmpty()
    }

    @Test
    fun `traits reprojetes sur la photo le long des joints inclines`() {
        // Coin caché très mal placé : joints inclinés et inégalement espacés dans la face ; les traits suivent la loi.
        val sc = SyntheticRadiatorScene(20, pitchMm = 60.0, heightMm = 600.0, yawDeg = 30.0, tiltDeg = 5.0, seed = 91, occluders = clutter(20, 60.0, 600.0))
        val corners = sc.pointedCorners.toMutableList()
        corners[2] = ImagePoint(corners[2].x + 0.04 * (sc.trueCorners[2].x - sc.trueCorners[3].x), corners[2].y + 0.15 * (sc.trueCorners[2].y - sc.trueCorners[1].y))
        val r = PhotoIdentifier.identify(sc.image, corners)
        assertThat(r.count).isEqualTo(20)
        assertThat(r.warnings.joinToString()).contains("coin")
        val lines = r.boundaryLinesInImage()
        assertThat(lines).hasSize(21)
        for (k in 1 until 20) {
            val (top, bottom) = lines[k]
            val pitchImg = abs(sc.project(k * 60.0 + 60, 300.0).x - sc.project(k * 60.0, 300.0).x)
            // Le trait passe par le vrai joint en haut et en bas du corps (à 20 % du pas apparent près).
            for ((pt, yMm) in listOf(top to 0.0, bottom to 600.0)) {
                val trueX = sc.project(k * 60.0, yMm).x
                val trueY = sc.project(k * 60.0, yMm).y
                // Point du vrai joint à la même hauteur image que l'extrémité du trait (droite du joint).
                val other = sc.project(k * 60.0, 300.0)
                val xAt = trueX + (pt.y - trueY) * (other.x - trueX) / (other.y - trueY)
                assertThat(abs(pt.x - xAt)).isLessThan(0.2 * pitchImg)
            }
        }
    }
}
