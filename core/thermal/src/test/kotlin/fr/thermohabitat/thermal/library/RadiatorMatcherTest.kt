package fr.thermohabitat.thermal.library

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import fr.thermohabitat.thermal.model.RadiatorType
import fr.thermohabitat.thermal.model.ValueOrigin
import fr.thermohabitat.thermal.vision.DimensionEstimate
import fr.thermohabitat.thermal.vision.PhotoDimensions
import org.junit.Test

class RadiatorMatcherTest {

    private fun mm(v: Double, u: Double) = DimensionEstimate(v, u)

    private fun match(m: RadiatorMeasurements) = RadiatorMatcher.match(m)

    private fun MatchResult.leadingCodes() = leading.map { it.category.code }

    private fun MatchResult.level(code: String) = find(code)!!.level

    private fun MatchResult.reason(code: String, c: MatchCriterion) = find(code)!!.reasons.single { it.criterion == c }

    private val castIronColumns = listOf("fonte_2col", "fonte_3col", "fonte_4col", "fonte_5_6col", "fonte_moderne", "fonte_din")

    @Test
    fun `pas mesure de 61 mm - fontes a colonnes proposees, autres types ecartes avec raisons`() {
        val r = match(RadiatorMeasurements(pitchMm = mm(61.0, 4.0), sections = 13))
        assertThat(r.informative).isTrue()
        assertThat(r.pitchSource).isEqualTo(PitchSource.MEASURED)
        assertThat(r.leadingCodes()).containsAtLeastElementsIn(castIronColumns)
        assertThat(r.leadingCodes()).containsNoneOf("aluminium", "tubulaire", "acier_colonnes", "panneaux_acier", "acier_lamelles",
            "seche_serviettes", "convecteur", "fonte_plaques")
        castIronColumns.forEach { assertWithMessage(it).that(r.level(it)).isEqualTo(MatchLevel.COMPATIBLE) }
        assertThat(r.reason("fonte_2col", MatchCriterion.PITCH).text).isEqualTo("Pas de 61 ± 4 mm compatible (usuel 55–66 mm)")
        listOf("aluminium", "tubulaire", "acier_colonnes", "panneaux_acier", "seche_serviettes", "convecteur").forEach {
            assertWithMessage(it).that(r.level(it)).isEqualTo(MatchLevel.UNLIKELY)
        }
        assertThat(r.find("aluminium")!!.summary).isEqualTo("Peu probable : Pas de 61 ± 4 mm nettement inférieur à l'usuel (80 mm)")
        // Fonte ornée : pas un peu court, avec la réserve des modèles anciens au pas de 60 mm.
        assertThat(r.level("fonte_decoree")).isEqualTo(MatchLevel.POSSIBLE)
        assertThat(r.reason("fonte_decoree", MatchCriterion.PITCH).text).contains("≈ 60 mm")
        // Recouvrement des fontes : la profondeur départage, plages rappelées.
        val notes = r.notes.joinToString("\n")
        assertThat(notes).contains("la profondeur (du mur à l'avant de l'élément) les départage")
        assertThat(notes).contains("Fonte 2 colonnes 65–115 mm")
        assertThat(notes).doesNotContain("cannelures")
    }

    @Test
    fun `la profondeur et les colonnes departagent les fontes`() {
        val base = RadiatorMeasurements(pitchMm = mm(61.0, 4.0), sections = 13)
        val shallow = match(base.copy(depthMm = mm(75.0, 5.0)))
        assertThat(shallow.best!!.category.code).isEqualTo("fonte_2col")
        assertThat(shallow.best!!.suggestedColumns).isEqualTo(2)
        assertThat(shallow.leadingCodes()).containsNoneOf("fonte_3col", "fonte_4col", "fonte_5_6col")
        assertThat(shallow.level("fonte_4col")).isEqualTo(MatchLevel.UNLIKELY)
        assertThat(shallow.reason("fonte_moderne", MatchCriterion.DEPTH).text).contains("correspond à ≈ 2 colonnes")
        assertThat(shallow.reason("fonte_4col", MatchCriterion.DEPTH).text)
            .isEqualTo("Profondeur de 75 ± 5 mm nettement inférieure à l'usuel (135–225 mm)")

        val four = match(base.copy(depthMm = mm(145.0, 5.0), columns = 4))
        assertThat(four.best!!.category.code).isEqualTo("fonte_4col")
        assertThat(four.leadingCodes()).contains("fonte_moderne")
        assertThat(four.level("fonte_3col")).isEqualTo(MatchLevel.UNLIKELY)
        assertThat(four.reason("fonte_3col", MatchCriterion.COLUMNS).text).isEqualTo("4 colonnes : cette catégorie en compte 3")
        assertThat(four.reason("fonte_moderne", MatchCriterion.DEPTH).text).contains("pour 4 colonnes : 140–143 mm")
    }

    @Test
    fun `recouvrement a 50 mm - acier a elements, tubulaire et cannelures de panneau, la profondeur tranche`() {
        val base = RadiatorMeasurements(pitchMm = mm(50.0, 2.0), sections = 16)
        val r = match(base)
        assertThat(r.leadingCodes()).containsAtLeast("acier_colonnes", "tubulaire", "panneaux_acier")
        assertThat(r.leadingCodes()).containsNoneOf("fonte_2col", "aluminium", "acier_lamelles")
        val panel = r.find("panneaux_acier")!!
        assertThat(panel.countsElements).isFalse()
        assertThat(panel.reasons.single { it.criterion == MatchCriterion.PITCH }.text)
            .isEqualTo("Pas de 50 ± 2 mm : correspond aux cannelures d'un panneau (33,3–50 mm) ; le motif compté ne serait pas des éléments")
        val notes = r.notes.joinToString("\n")
        assertThat(notes).contains("peut être des cannelures et non des éléments")
        assertThat(notes).contains("ne sert pas au calcul de la puissance")
        assertThat(notes).contains("les départage")

        val thin = match(base.copy(depthMm = mm(62.0, 5.0)))
        assertThat(thin.level("acier_colonnes")).isEqualTo(MatchLevel.UNLIKELY)
        assertThat(thin.leadingCodes()).containsAtLeast("tubulaire", "panneaux_acier")
        assertThat(thin.find("tubulaire")!!.suggestedColumns).isEqualTo(2)

        val deep = match(base.copy(depthMm = mm(220.0, 5.0)))
        assertThat(deep.level("panneaux_acier")).isEqualTo(MatchLevel.UNLIKELY)
        assertThat(deep.leadingCodes()).containsAtLeast("acier_colonnes", "tubulaire")
        assertThat(deep.reason("acier_colonnes", MatchCriterion.DEPTH).text).contains("profondeur normalisée 220 mm")
        assertThat(deep.notes.joinToString()).doesNotContain("cannelures")
    }

    @Test
    fun `cannelures au pas de 33 mm - panneau en tete et garde-fou explicite`() {
        val r = match(RadiatorMeasurements(pitchMm = mm(33.3, 1.5), sections = 30))
        assertThat(r.best!!.category.code).isEqualTo("panneaux_acier")
        assertThat(r.best!!.countsElements).isFalse()
        assertThat(r.notes.joinToString()).contains("« Panneaux acier (types 10 à 33) » : un motif au pas de 33,3 à 50 mm peut être des cannelures")
        assertThat(r.level("fonte_2col")).isEqualTo(MatchLevel.UNLIKELY)
    }

    @Test
    fun `sans echelle - proportions comparees a la plage propre a chaque categorie`() {
        // 13 éléments de 60 mm sur un corps de 600 mm : pas / hauteur = 0,10.
        val r = match(RadiatorMeasurements(sections = 13, pitchToHeight = 0.10, pitchToHeightRelUncertainty = 0.10))
        assertThat(r.informative).isTrue()
        assertThat(r.pitchMm).isNull()
        assertThat(r.notes.joinToString()).contains("Sans échelle")
        assertThat(r.level("fonte_2col")).isEqualTo(MatchLevel.COMPATIBLE)
        assertThat(r.reason("fonte_2col", MatchCriterion.PROPORTIONS).text)
            .isEqualTo("Proportions (sans échelle) : élément ≈ 10 fois plus haut que large, compatible (usuel 7 à 17 fois)")
        listOf("seche_serviettes", "convecteur", "fonte_plaques").forEach { assertWithMessage(it).that(r.level(it)).isEqualTo(MatchLevel.UNLIKELY) }
        // Peu discriminant : les cannelures de panneau restent possibles, le garde-fou est affiché.
        assertThat(r.leadingCodes()).contains("panneaux_acier")
        assertThat(r.notes.joinToString()).contains("cannelures")
        assertThat(r.reason("aluminium", MatchCriterion.PROPORTIONS).text).contains("extrapolées")

        // Éléments très élancés (tubulaire de 2 m) : hors des fontes, compatible avec le seul tubulaire.
        val tall = match(RadiatorMeasurements(sections = 10, pitchToHeight = 0.0225))
        assertThat(tall.best!!.category.code).isEqualTo("tubulaire")
        assertThat(tall.leadingCodes()).containsExactly("tubulaire")
        assertThat(tall.level("fonte_2col")).isEqualTo(MatchLevel.UNLIKELY)
        assertThat(tall.reason("fonte_2col", MatchCriterion.PROPORTIONS).text).contains("nettement plus élancé")

        // Éléments trapus (tubulaire bas de 155 mm) : trop trapus pour une fonte.
        val squat = match(RadiatorMeasurements(sections = 20, pitchToHeight = 0.30))
        assertThat(squat.best!!.category.code).isEqualTo("tubulaire")
        assertThat(squat.level("fonte_2col")).isEqualTo(MatchLevel.UNLIKELY)
        assertThat(squat.reason("fonte_din", MatchCriterion.PROPORTIONS).text).contains("plus trapu")
    }

    @Test
    fun `pas deduit de la longueur ou des proportions et de la hauteur mesuree`() {
        val fromLength = match(RadiatorMeasurements(lengthMm = mm(793.0, 5.0), sections = 13))
        assertThat(fromLength.pitchSource).isEqualTo(PitchSource.FROM_LENGTH)
        assertThat(fromLength.pitchDerived).isTrue()
        assertThat(fromLength.pitchMm!!.valueMm).isWithin(1e-9).of(61.0)
        assertThat(fromLength.notes.first()).startsWith("Pas déduit de la longueur et du nombre d'éléments (793 mm ÷ 13 ≈ 61 mm)")
        assertThat(fromLength.reason("fonte_3col", MatchCriterion.PITCH).text).isEqualTo("Pas de 61 ± 0,7 mm compatible (usuel 55–66 mm)")

        val fromRatio = match(RadiatorMeasurements(heightMm = mm(600.0, 5.0), sections = 13, pitchToHeight = 0.10, pitchToHeightRelUncertainty = 0.08))
        assertThat(fromRatio.pitchSource).isEqualTo(PitchSource.FROM_PROPORTIONS)
        assertThat(fromRatio.pitchMm!!.valueMm).isWithin(1e-9).of(60.0)
        assertThat(fromRatio.pitchMm!!.relativeUncertainty).isWithin(0.002).of(0.0804)
        assertThat(fromRatio.level("aluminium")).isEqualTo(MatchLevel.UNLIKELY)
        assertThat(fromRatio.leadingCodes()).contains("fonte_2col")
        // Pas mesuré prioritaire sur la longueur.
        val both = match(RadiatorMeasurements(pitchMm = mm(80.0, 2.0), lengthMm = mm(793.0, 5.0), sections = 13))
        assertThat(both.pitchSource).isEqualTo(PitchSource.MEASURED)
        assertThat(both.best!!.category.code).isEqualTo("aluminium")
    }

    @Test
    fun `sans aucune mesure - ordre de la bibliotheque, rien n'est propose`() {
        val r = match(RadiatorMeasurements(sections = 13))
        assertThat(r.informative).isFalse()
        assertThat(r.best).isNull()
        assertThat(r.leading).isEmpty()
        assertThat(r.matches.map { it.category }).isEqualTo(RadiatorLibrary.categories)
        assertThat(r.matches.map { it.level }.toSet()).containsExactly(MatchLevel.UNDETERMINED)
        assertThat(r.notes.single()).startsWith("Aucune mesure exploitable")
    }

    @Test
    fun `motif de face et type indiques`() {
        val bars = match(RadiatorMeasurements(pattern = FrontPattern.HORIZONTAL_BARS))
        assertThat(bars.best!!.category.code).isEqualTo("seche_serviettes")
        assertThat(bars.level("fonte_2col")).isEqualTo(MatchLevel.UNLIKELY)

        val flutes = match(RadiatorMeasurements(pattern = FrontPattern.VERTICAL_FLUTES))
        assertThat(flutes.best!!.category.code).isEqualTo("panneaux_acier")
        assertThat(flutes.level("fonte_2col")).isEqualTo(MatchLevel.POSSIBLE)
        assertThat(flutes.reason("fonte_2col", MatchCriterion.PATTERN).text).contains("plutôt des éléments juxtaposés")

        val alu = match(RadiatorMeasurements(type = RadiatorType.ALUMINIUM_SECTIONAL, pitchMm = mm(80.0, 3.0)))
        assertThat(alu.best!!.category.code).isEqualTo("aluminium")
        assertThat(alu.reason("fonte_2col", MatchCriterion.TYPE).effect).isEqualTo(MatchEffect.CONTRADICTS)
    }

    @Test
    fun `hauteurs normalisees DIN et hauteurs extrapolees de l'aluminium`() {
        val din = match(RadiatorMeasurements(pitchMm = mm(60.0, 1.5), heightMm = mm(580.0, 8.0)))
        assertThat(din.reason("fonte_din", MatchCriterion.HEIGHT).text).isEqualTo("Hauteur de 580 ± 8 mm : hauteur normalisée 580 mm")
        assertThat(din.level("fonte_din")).isEqualTo(MatchLevel.COMPATIBLE)

        val notDin = match(RadiatorMeasurements(pitchMm = mm(60.0, 1.5), heightMm = mm(750.0, 8.0)))
        val h = notDin.reason("fonte_din", MatchCriterion.HEIGHT)
        assertThat(h.effect).isEqualTo(MatchEffect.WEAKENS)
        assertThat(h.text).contains("aucune hauteur normalisée proche")
        assertThat(notDin.reason("fonte_2col", MatchCriterion.HEIGHT).text).isEqualTo("Hauteur de 750 ± 8 mm proche d'une hauteur usuelle (750 mm)")
        assertThat(notDin.best!!.score).isGreaterThan(notDin.find("fonte_din")!!.score)

        val alu = match(RadiatorMeasurements(pitchMm = mm(80.0, 2.0), heightMm = mm(582.0, 8.0)))
        assertThat(alu.best!!.category.code).isEqualTo("aluminium")
        assertThat(alu.reason("aluminium", MatchCriterion.HEIGHT).text)
            .isEqualTo("Hauteur de 582 ± 8 mm proche d'une hauteur usuelle (582 mm) ; hauteurs extrapolées (entraxe + 82 mm) d'après un seul modèle documenté")
    }

    @Test
    fun `puissance par element - fourchette pour la fonte, valeur tabulee pour une cote DIN`() {
        val iron = match(RadiatorMeasurements(pitchMm = mm(60.0, 2.0), heightMm = mm(860.0, 10.0)))
        val p4 = iron.find("fonte_4col")!!.power!!
        assertThat(p4.indicative).isTrue()
        assertThat(p4.rangeW).isEqualTo(96.0..171.0)
        assertThat(p4.text).contains("96 à 171 W par élément à ΔT 50 K")
        assertThat(p4.text).contains("ordre de grandeur")
        // Colonnes fixes de la catégorie : seules les références 2 colonnes.
        assertThat(iron.find("fonte_2col")!!.power!!.references.all { it.columns == 2 }).isTrue()

        val din = match(RadiatorMeasurements(pitchMm = mm(60.0, 1.5), heightMm = mm(580.0, 8.0), depthMm = mm(160.0, 5.0)))
        val pd = din.find("fonte_din")!!.power!!
        assertThat(pd.indicative).isFalse()
        assertThat(pd.rangeW).isEqualTo(96.0..96.0)
        assertThat(pd.text).isEqualTo("Valeur tabulée H 580 × P 160 : 96 W par élément à ΔT 50 K, si le radiateur est bien de ce type")
        // Hors cote normalisée : simple ordre de grandeur.
        val off = match(RadiatorMeasurements(pitchMm = mm(60.0, 1.5), heightMm = mm(640.0, 8.0), depthMm = mm(160.0, 5.0)))
        assertThat(off.find("fonte_din")!!.power!!.indicative).isTrue()
        // Sans hauteur : aucune puissance proposée.
        assertThat(match(RadiatorMeasurements(pitchMm = mm(60.0, 2.0))).find("fonte_4col")!!.power).isNull()
    }

    @Test
    fun `nombres alternatifs compares - le bon nombre ressort, les harmoniques sont signalees`() {
        val base = RadiatorMeasurements(pitchMm = mm(61.0, 3.0), lengthMm = mm(793.0, 20.0), heightMm = mm(600.0, 15.0),
            depthMm = mm(150.0, 5.0), sections = 13)
        val c = RadiatorMatcher.compareCounts(listOf(13, 26, 7, 13), base)
        assertThat(c.map { it.count }).containsExactly(13, 26, 7).inOrder()
        val (n13, n26, n7) = c
        assertThat(n13.level).isEqualTo(MatchLevel.COMPATIBLE)
        assertThat(n13.bestCategory!!.type).isEqualTo(RadiatorType.CAST_IRON_COLUMN)
        assertThat(n13.text).startsWith("13 éléments (pas ≈ 61 ± 3 mm) : compatible — « ")
        assertThat(n26.pitchMm!!.valueMm).isWithin(1e-9).of(30.5)
        assertThat(n26.score).isLessThan(n13.score)
        assertThat(n7.score).isLessThan(n13.score)
        assertThat(n7.result.level("fonte_2col")).isEqualTo(MatchLevel.UNLIKELY)
    }

    @Test
    fun `changement du nombre d'elements - pas recalcule`() {
        val photo = RadiatorMeasurements(pitchMm = mm(61.0, 3.0), lengthMm = mm(793.0, 20.0), sections = 13, pitchToHeight = 0.10)
        val doubled = photo.forCount(26)
        assertThat(doubled.sections).isEqualTo(26)
        assertThat(doubled.pitchMm!!.valueMm).isWithin(1e-9).of(30.5)
        assertThat(doubled.pitchMm!!.uncertaintyMm).isWithin(1e-9).of(1.5)
        assertThat(doubled.pitchToHeight!!).isWithin(1e-12).of(0.05)
        assertThat(doubled.lengthMm).isEqualTo(photo.lengthMm)
        // Fiche radiateur (longueur sans pas) : le pas sera déduit de la longueur pour le nouveau nombre.
        val record = RadiatorMeasurements(lengthMm = mm(793.0, 5.0), sections = 13).forCount(26)
        assertThat(record.pitchMm).isNull()
        assertThat(match(record).pitchMm!!.valueMm).isWithin(1e-9).of(30.5)
        // Pas mesuré sur un élément, sans nombre de départ : inchangé.
        assertThat(RadiatorMeasurements(pitchMm = mm(61.0, 3.0)).forCount(10).pitchMm).isEqualTo(mm(61.0, 3.0))
    }

    @Test
    fun `mesures de photo - seules les dimensions mesurees sont retenues`() {
        val dims = PhotoDimensions(mm(61.0, 2.0), mm(600.0, 12.0), mm(793.0, 25.0), ValueOrigin.MEASURED)
        val measured = RadiatorMeasurements.fromPhotoDimensions(13, dims, bodyAspectRatio = 793.0 / 600.0, aspectRelUncertainty = 0.04,
            depthMm = mm(150.0, 5.0))
        assertThat(measured.pitchMm).isEqualTo(dims.pitchMm)
        assertThat(measured.heightMm).isEqualTo(dims.heightMm)
        assertThat(measured.lengthMm).isEqualTo(dims.lengthMm)
        assertThat(measured.depthMm).isEqualTo(mm(150.0, 5.0))
        assertThat(measured.pitchToHeight!!).isWithin(1e-9).of(793.0 / 600.0 / 13)
        assertThat(match(measured).pitchSource).isEqualTo(PitchSource.MEASURED)

        // Pas standard supposé (ESTIMÉ) : les dimensions découlent de l'hypothèse, seules les proportions sont gardées.
        val assumed = RadiatorMeasurements.fromPhotoDimensions(13, dims.copy(origin = ValueOrigin.ESTIMATED), 1.32, 0.1)
        assertThat(assumed.pitchMm).isNull()
        assertThat(assumed.heightMm).isNull()
        assertThat(assumed.lengthMm).isNull()
        assertThat(assumed.pitchToHeight!!).isWithin(1e-9).of(1.32 / 13)
        assertThat(assumed.pitchToHeightRelUncertainty).isEqualTo(0.1)
        val r = match(assumed)
        assertThat(r.pitchMm).isNull()
        assertThat(r.notes.joinToString()).contains("Sans échelle")

        // Sans nombre d'éléments : aucune proportion par élément.
        val noCount = RadiatorMeasurements.fromPhotoDimensions(null, PhotoDimensions(null, null, null, ValueOrigin.UNKNOWN), 1.32)
        assertThat(noCount.pitchToHeight).isNull()
        assertThat(match(noCount).informative).isFalse()
    }

    @Test
    fun `valeurs invalides ignorees, incertitude inconnue a 10 pourcent`() {
        val r = match(
            RadiatorMeasurements(
                pitchMm = mm(Double.NaN, 1.0), heightMm = mm(-5.0, 1.0), depthMm = mm(150.0, Double.NaN),
                pitchToHeight = Double.POSITIVE_INFINITY, pitchToHeightRelUncertainty = -1.0, columns = 0, sections = -3,
            ),
        )
        assertThat(r.informative).isTrue()
        assertThat(r.pitchMm).isNull()
        assertThat(r.find("fonte_4col")!!.reasons.map { it.criterion }).containsExactly(MatchCriterion.DEPTH)
        assertThat(r.reason("fonte_4col", MatchCriterion.DEPTH).text).isEqualTo("Profondeur de 150 ± 15 mm compatible (usuel 135–225 mm)")
    }

    @Test
    fun `raisons en francais, resultat deterministe`() {
        val m = RadiatorMeasurements(pitchMm = mm(58.3, 1.2), heightMm = mm(660.0, 10.0), depthMm = mm(101.0, 5.0), sections = 12, columns = 3)
        val a = match(m)
        assertThat(match(m)).isEqualTo(a)
        assertThat(a.best!!.category.code).isEqualTo("fonte_3col")
        for (cm in a.matches) {
            assertWithMessage(cm.category.code).that(cm.reasons).isNotEmpty()
            cm.reasons.forEach { reason ->
                assertWithMessage(cm.category.code).that(reason.text).isNotEmpty()
                assertWithMessage(reason.text).that(reason.factor).isIn(com.google.common.collect.Range.closed(0.0, 1.0))
                // Décimales à la française.
                assertWithMessage(reason.text).that(reason.text).doesNotContainMatch("[0-9]\\.[0-9]")
            }
        }
        assertThat(a.reason("fonte_3col", MatchCriterion.PITCH).text).isEqualTo("Pas de 58,3 ± 1,2 mm compatible (usuel 55–66 mm)")
        // Classement : scores décroissants.
        assertThat(a.matches.map { it.score }).isInOrder(Comparator.reverseOrder<Double>())
    }
}
