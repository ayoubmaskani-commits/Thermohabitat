package fr.thermohabitat.thermal.library

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import fr.thermohabitat.thermal.model.RadiatorType
import org.junit.Test

class RadiatorLibraryTest {

    private val legacyCodes = listOf(
        "fonte_2col", "fonte_3col", "fonte_4col", "fonte_5_6col", "fonte_elements_plats", "fonte_plaques", "acier_colonnes",
        "panneaux_acier", "tubulaire", "aluminium", "seche_serviettes", "convecteur", "decoratif",
    )

    private fun cat(code: String) = RadiatorLibrary.find(code)!!

    @Test
    fun `les 13 codes historiques restent valides et les codes sont uniques`() {
        assertThat(legacyCodes).hasSize(13)
        legacyCodes.forEach { assertWithMessage(it).that(RadiatorLibrary.find(it)).isNotNull() }
        val codes = RadiatorLibrary.categories.map { it.code }
        assertThat(codes).containsNoDuplicates()
        assertThat(codes).containsAtLeast("fonte_din", "fonte_decoree", "fonte_moderne", "acier_lamelles")
        assertThat(RadiatorLibrary.find(null)).isNull()
        assertThat(RadiatorLibrary.find("inconnu")).isNull()
        // Valeurs reprises par l'écran de fiche (« Appliquer ») : inchangées pour les catégories historiques.
        assertThat(cat("fonte_2col").columns).isEqualTo(2)
        assertThat(cat("fonte_5_6col").columns).isEqualTo(5)
        assertThat(cat("tubulaire").columns).isEqualTo(3)
        assertThat(cat("panneaux_acier").panelType).isNull()
        assertThat(cat("fonte_2col").type).isEqualTo(RadiatorType.CAST_IRON_COLUMN)
    }

    @Test
    fun `donnees coherentes dans chaque categorie`() {
        for (c in RadiatorLibrary.categories) {
            val m = assertWithMessage(c.code)
            c.pitchRangeMm?.let { r ->
                m.that(r.start).isGreaterThan(0.0)
                m.that(r.start).isAtMost(r.endInclusive)
            }
            c.flutePitchRangeMm?.let { r -> m.that(r.start).isAtMost(r.endInclusive) }
            m.that(c.pitchRangeMm != null && c.flutePitchRangeMm != null).isFalse()
            // Pas affiché « ≈ » de l'écran bibliothèque : dans la plage documentée.
            c.sectionPitchMm?.let { p ->
                val r = c.pitchRangeMm
                m.that(r).isNotNull()
                m.that(p >= r!!.start - 0.5 && p <= r.endInclusive + 0.5).isTrue()
            }
            c.heightRangeMm?.let { r ->
                m.that(r.first).isGreaterThan(0)
                c.typicalHeightsMm.forEach { h -> m.that(h in r).isTrue() }
                c.normalizedHeightsMm.forEach { h -> m.that(h in r).isTrue() }
            }
            c.depthRangeMm?.let { r ->
                c.depthByColumnsMm.values.forEach { d -> m.that(d.first >= r.first && d.last <= r.last).isTrue() }
                c.normalizedDepthsMm.forEach { d -> m.that(d in r).isTrue() }
            }
            c.columns?.let { col -> m.that(c.columnsRange!!.contains(col)).isTrue() }
            c.depthByColumnsMm.keys.forEach { col -> m.that(c.columnsRange!!.contains(col)).isTrue() }
            c.elementPowers.forEach { e ->
                m.that(e.wattsDt50).isGreaterThan(0.0)
                m.that(e.source).startsWith("http")
                e.heightMm?.let { h -> m.that(c.heightRangeMm!!.contains(h)).isTrue() }
            }
            // Données sourcées seulement si documentées ; sans source : ordre de grandeur.
            if (c.reliability == DataReliability.DOCUMENTED) m.that(c.sources).isNotEmpty()
            if (c.sources.isEmpty()) m.that(c.reliability).isEqualTo(DataReliability.ORDER_OF_MAGNITUDE)
            m.that(c.visualCues).isNotEmpty()
            m.that(c.measureTips).isNotEmpty()
        }
    }

    @Test
    fun `fontes a colonnes au pas de 55 a 66 mm, fonte ornee plus large, DIN a 60 mm`() {
        listOf("fonte_2col", "fonte_3col", "fonte_4col", "fonte_5_6col", "fonte_elements_plats").forEach {
            assertWithMessage(it).that(cat(it).pitchRangeMm).isEqualTo(55.0..66.0)
        }
        assertThat(cat("fonte_din").pitchRangeMm).isEqualTo(60.0..60.0)
        assertThat(cat("fonte_din").normalizedHeightsMm).containsExactly(280, 430, 580, 680, 980).inOrder()
        assertThat(cat("fonte_decoree").pitchRangeMm).isEqualTo(66.5..78.3)
        assertThat(cat("fonte_moderne").pitchRangeMm).isEqualTo(55.0..61.0)
        assertThat(cat("aluminium").pitchRangeMm).isEqualTo(80.0..80.0)
        assertThat(cat("acier_lamelles").pitchRangeMm).isEqualTo(30.6..31.0)
    }

    @Test
    fun `rapport pas sur hauteur propre a chaque categorie`() {
        fun ratio(code: String) = cat(code).pitchToHeightRange!!
        // Valeurs calculées (pas mini / hauteur maxi … pas maxi / hauteur mini) des données de référence.
        val expected = mapOf(
            "fonte_2col" to (0.057 to 0.143), "fonte_3col" to (0.064 to 0.118), "fonte_4col" to (0.057 to 0.183),
            "fonte_5_6col" to (0.057 to 0.183), "fonte_din" to (0.061 to 0.214), "fonte_decoree" to (0.070 to 0.224),
            "fonte_moderne" to (0.057 to 0.169), "acier_lamelles" to (0.031 to 0.124), "tubulaire" to (0.015 to 0.323),
            "aluminium" to (0.091 to 0.185), "panneaux_acier" to (0.035 to 0.250),
        )
        expected.forEach { (code, lohi) ->
            val r = ratio(code)
            assertWithMessage(code).that(r.start).isWithin(0.0015).of(lohi.first)
            assertWithMessage(code).that(r.endInclusive).isWithin(0.0015).of(lohi.second)
        }
        // Pas de plage globale : le tubulaire déborde largement 0,04–0,25, les fontes 2 colonnes s'arrêtent bien avant.
        assertThat(ratio("tubulaire").start).isLessThan(0.04)
        assertThat(ratio("tubulaire").endInclusive).isGreaterThan(0.25)
        assertThat(ratio("fonte_2col").endInclusive).isLessThan(0.15)
        // Sans pas ou sans hauteurs documentés : non calculable.
        listOf("fonte_plaques", "convecteur", "decoratif", "seche_serviettes", "fonte_elements_plats").forEach {
            assertWithMessage(it).that(cat(it).pitchToHeightRange).isNull()
        }
    }

    @Test
    fun `cannelures de panneau distinguees des elements`() {
        val panel = cat("panneaux_acier")
        assertThat(panel.pitchRangeMm).isNull()
        assertThat(panel.sectionPitchMm).isNull()
        assertThat(panel.flutePitchRangeMm).isEqualTo(33.3..50.0)
        assertThat(panel.countsElements).isFalse()
        assertThat(panel.patternPitchRangeMm).isEqualTo(33.3..50.0)
        assertThat(panel.pitchText).isEqualTo("Cannelures au pas de 33,3 à 50 mm (ce ne sont pas des éléments)")
        assertThat(panel.frontPatterns).contains(FrontPattern.VERTICAL_FLUTES)
        assertThat(cat("fonte_2col").countsElements).isTrue()
        assertThat(cat("fonte_2col").pitchText).isEqualTo("Pas d'élément 55 à 66 mm")
        assertThat(cat("convecteur").pitchText).isNull()
    }

    @Test
    fun `hauteurs affichees avec leurs reserves`() {
        assertThat(cat("fonte_din").heightText).isEqualTo("Hauteurs normalisées 280, 430, 580, 680, 980 mm")
        assertThat(cat("fonte_2col").heightText).isEqualTo("Hauteurs usuelles 460 à 960 mm")
        assertThat(cat("aluminium").heightText).contains("extrapolées")
        assertThat(cat("aluminium").remarks).contains("un seul modèle")
        assertThat(cat("convecteur").heightText).isNull()
    }

    @Test
    fun `puissances de fonte en fourchette d'elements comparables`() {
        val c = cat("fonte_4col")
        val refs = c.comparableElementPowers(860.0)
        assertThat(refs.map { it.wattsDt50 }).containsAtLeast(109.0, 171.0)
        // La plus proche en hauteur d'abord.
        assertThat(refs.first().heightMm).isEqualTo(860)
        assertThat(c.elementPowerRangeW(860.0)).isEqualTo(96.0..171.0)
        // Profondeur et colonnes filtrent.
        assertThat(c.comparableElementPowers(860.0, depthMm = 140.0).map { it.depthMm }).doesNotContain(175)
        assertThat(cat("tubulaire").comparableElementPowers(600.0, columns = 3).map { it.wattsDt50 }).containsExactly(60.9)
        assertThat(cat("convecteur").elementPowerRangeW(600.0)).isNull()
    }

    @Test
    fun `colonnes deduites de la profondeur quand elle est documentee`() {
        assertThat(cat("tubulaire").columnsForDepth(140.0)).isEqualTo(4)
        assertThat(cat("tubulaire").columnsForDepth(101.0)).isEqualTo(3)
        assertThat(cat("fonte_moderne").columnsForDepth(143.0)).isEqualTo(4)
        assertThat(cat("fonte_moderne").columnsForDepth(120.0)).isNull()
        assertThat(cat("fonte_2col").columnsForDepth(70.0)).isNull()
    }

    @Test
    fun `categories plausibles compatibles avec l'ecran bibliotheque`() {
        val all = RadiatorLibrary.categories
        // Sans donnée : ordre de la bibliothèque, aucune catégorie retirée.
        assertThat(RadiatorLibrary.plausibleCategories(null, null, null)).isEqualTo(all)
        assertThat(RadiatorLibrary.plausibleCategories(0.0, 0, RadiatorType.UNKNOWN)).isEqualTo(all)
        assertThat(RadiatorLibrary.plausibleCategories(Double.NaN, null, null)).isEqualTo(all)
        // Toujours toutes les catégories (l'écran affiche le reste sous « Autres »).
        assertThat(RadiatorLibrary.plausibleCategories(150.0, 3, null)).containsExactlyElementsIn(all)
        assertThat(RadiatorLibrary.plausibleCategories(75.0, 2, null).first().code).isEqualTo("fonte_2col")
        assertThat(RadiatorLibrary.plausibleCategories(200.0, 4, null).first().code).isEqualTo("fonte_4col")
        assertThat(RadiatorLibrary.plausibleCategories(230.0, 6, null).first().code).isEqualTo("fonte_5_6col")
        assertThat(RadiatorLibrary.plausibleCategories(null, null, RadiatorType.STEEL_PANEL).first().code).isEqualTo("panneaux_acier")
        assertThat(RadiatorLibrary.plausibleCategories(null, null, RadiatorType.ALUMINIUM_SECTIONAL).first().code).isEqualTo("aluminium")
        // Une profondeur de panneau relègue la fonte 4 colonnes derrière les catégories compatibles.
        val shallow = RadiatorLibrary.plausibleCategories(60.0, null, null).map { it.code }
        assertThat(shallow.indexOf("panneaux_acier")).isLessThan(shallow.indexOf("fonte_4col"))
    }
}
