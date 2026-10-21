package fr.thermohabitat.thermal.library

import fr.thermohabitat.thermal.fr
import fr.thermohabitat.thermal.model.Radiator
import fr.thermohabitat.thermal.model.RadiatorType
import fr.thermohabitat.thermal.model.ValueOrigin
import fr.thermohabitat.thermal.vision.DimensionEstimate
import fr.thermohabitat.thermal.vision.PhotoDimensions
import fr.thermohabitat.thermal.vision.PhotoIdentification
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Critère comparé entre les mesures et une catégorie. */
enum class MatchCriterion(val label: String) {
    TYPE("Type"),
    PATTERN("Motif de face"),
    PITCH("Pas d'élément"),
    PROPORTIONS("Proportions"),
    HEIGHT("Hauteur"),
    DEPTH("Profondeur"),
    COLUMNS("Colonnes"),
}

/** Effet d'un critère sur la plausibilité d'une catégorie. */
enum class MatchEffect(val label: String) {
    SUPPORTS("compatible"),
    NEUTRAL("non vérifiable"),
    WEAKENS("à la limite"),
    CONTRADICTS("incompatible"),
}

/** Raison lisible par l'utilisateur : critère, effet, facteur appliqué au score (0–1) et texte en français. */
data class MatchReason(val criterion: MatchCriterion, val effect: MatchEffect, val factor: Double, val text: String)

/** Verdict d'une catégorie. */
enum class MatchLevel(val label: String) {
    /** Tout ce qui est mesuré concorde avec les données de la catégorie. */
    COMPATIBLE("Compatible"),
    /** Un critère est à la limite ou rien n'est vérifiable. */
    POSSIBLE("Possible"),
    /** Au moins un critère contredit nettement la catégorie. */
    UNLIKELY("Peu probable"),
    /** Aucune mesure exploitable. */
    UNDETERMINED("Non départagé"),
}

/**
 * Plausibilité d'une catégorie au vu des mesures.
 * @property score produit des facteurs des critères (1 : tout ce qui est mesuré concorde), sert au classement
 * @property suggestedColumns colonnes déduites (catégorie à colonnes fixes, mesure compatible ou profondeur), sinon null
 */
data class CategoryMatch(
    val category: RadiatorCategory,
    val score: Double,
    val level: MatchLevel,
    val reasons: List<MatchReason>,
    val suggestedColumns: Int?,
) {
    /** Verdict suivi des raisons principales (contradictions d'abord). */
    val summary: String
        get() {
            val main = reasons.filter { it.effect != MatchEffect.NEUTRAL }.sortedBy { it.effect.ordinal * -1 }.take(3)
            return if (main.isEmpty()) "${level.label} : aucune mesure à comparer"
            else "${level.label} : " + main.joinToString(" ; ") { it.text }
        }
}

/**
 * Résultat du rapprochement, catégories de la plus à la moins plausible (ordre de la bibliothèque à égalité).
 * @property informative faux si aucune mesure n'a pu être comparée (ordre de la bibliothèque, verdicts « non départagé »)
 * @property notes explications générales (données manquantes, mesures complémentaires utiles)
 * @property pitchMm pas utilisé : mesuré, ou déduit de la longueur et du nombre d'éléments ([pitchDerived])
 */
data class MatchResult(
    val matches: List<CategoryMatch>,
    val informative: Boolean,
    val notes: List<String>,
    val pitchMm: DimensionEstimate?,
    val pitchDerived: Boolean,
) {
    val best: CategoryMatch? get() = if (informative) matches.firstOrNull() else null

    /** Groupe de tête : catégories dont le score atteint 75 % du meilleur (vide sans mesure exploitable). */
    val leading: List<CategoryMatch>
        get() {
            val b = best ?: return emptyList()
            return matches.filter { it.score >= RadiatorMatcher.LEADING_FRACTION * b.score && it.level != MatchLevel.UNLIKELY }
                .ifEmpty { listOf(b) }
        }

    fun find(code: String): CategoryMatch? = matches.firstOrNull { it.category.code == code }
}

/**
 * Caractéristiques d'un radiateur à rapprocher de la bibliothèque. Toutes sont facultatives.
 * Incertitudes : incertitudes-types (mm) des [DimensionEstimate].
 *
 * @property pitchMm pas d'élément (d'axe en axe), MESURÉ (jamais un pas standard supposé)
 * @property sections nombre d'éléments : avec [lengthMm], donne le pas quand il n'est pas mesuré
 * @property pitchToHeight pas / hauteur sans échelle (photo sans référence), utilisé seulement si le pas est inconnu
 * @property pitchToHeightRelUncertainty incertitude relative de [pitchToHeight]
 * @property pattern motif vu de face indiqué par l'utilisateur
 * @property type type déjà connu (sinon null)
 */
data class RadiatorMeasurements(
    val pitchMm: DimensionEstimate? = null,
    val heightMm: DimensionEstimate? = null,
    val lengthMm: DimensionEstimate? = null,
    val depthMm: DimensionEstimate? = null,
    val sections: Int? = null,
    val pitchToHeight: Double? = null,
    val pitchToHeightRelUncertainty: Double = DEFAULT_RATIO_UNCERTAINTY,
    val columns: Int? = null,
    val pattern: FrontPattern? = null,
    val type: RadiatorType? = null,
) {
    /**
     * Mêmes mesures pour un autre nombre d'éléments [count] : le pas est recalculé (longueur ÷ nombre, ou pas × ancien
     * nombre ÷ nouveau), ainsi que le rapport pas / hauteur. Sert à comparer des nombres alternatifs (harmoniques).
     */
    fun forCount(count: Int): RadiatorMeasurements {
        require(count >= 1) { "Nombre d'éléments invalide" }
        val old = sections
        val pitch = when {
            lengthMm != null -> null
            pitchMm != null && old != null -> DimensionEstimate(pitchMm.valueMm * old / count, pitchMm.uncertaintyMm * old / count)
            else -> pitchMm
        }
        val ratio = if (pitchToHeight != null && old != null) pitchToHeight * old / count else pitchToHeight
        return copy(pitchMm = pitch, pitchToHeight = ratio, sections = count)
    }

    companion object {
        /** Incertitude relative par défaut du rapport pas / hauteur (rapport d'aspect estimé sur une photo de biais). */
        const val DEFAULT_RATIO_UNCERTAINTY = 0.10

        /** Part d'incertitude ajoutée au rapport pas / hauteur d'une photo : bords détectés et pointé des coins. */
        private const val PHOTO_RATIO_EXTRA = 0.05

        /**
         * Mesures tirées d'une analyse de photo pour [count] éléments (proposé ou corrigé par l'utilisateur).
         * Pas, hauteur et longueur ne sont retenus que s'ils sont MESURÉS (référence métrique) : avec un pas standard
         * supposé, ils découleraient de l'hypothèse ; seul le rapport pas / hauteur, indépendant de l'échelle, est gardé.
         * Le motif n'est pas déduit de la photo (éléments et cannelures se ressemblent) : le pas les départage.
         */
        fun fromPhoto(
            photo: PhotoIdentification,
            count: Int? = photo.count,
            depthMm: DimensionEstimate? = null,
            columns: Int? = null,
            type: RadiatorType? = null,
        ): RadiatorMeasurements {
            val n = count?.takeIf { it >= 1 }
            val dims = if (n != null) photo.dimensionsFor(n) else PhotoDimensions(null, photo.heightMm, photo.lengthMm, photo.dimensionsOrigin)
            val measured = dims.origin == ValueOrigin.MEASURED
            val ratio = n?.let { photo.bodyAspectRatio / it }?.takeIf { it.isFinite() && it > 0 }
            val ratioRel = if (photo.aspectMeasured) {
                sqrt(listOfNotNull(dims.lengthMm, dims.heightMm).sumOf { it.relativeUncertainty * it.relativeUncertainty }).coerceAtLeast(0.03)
            } else {
                sqrt(photo.aspect.relativeUncertainty * photo.aspect.relativeUncertainty + PHOTO_RATIO_EXTRA * PHOTO_RATIO_EXTRA)
            }
            return RadiatorMeasurements(
                pitchMm = dims.pitchMm.takeIf { measured },
                heightMm = dims.heightMm.takeIf { measured },
                lengthMm = dims.lengthMm.takeIf { measured },
                depthMm = depthMm,
                sections = n,
                pitchToHeight = ratio,
                pitchToHeightRelUncertainty = ratioRel,
                columns = columns,
                type = type,
            )
        }

        /**
         * Mesures d'une fiche radiateur. Incertitude selon l'origine des dimensions : mesurée ±5 mm, déclarée ±2 mm,
         * estimée ±8 %, inconnue ±15 %. Le type de la fiche n'est retenu que si [useType] (il peut venir de la bibliothèque).
         */
        fun fromRadiator(r: Radiator, useType: Boolean = false): RadiatorMeasurements {
            fun est(v: Double?) = v?.takeIf { it > 0 && it.isFinite() }?.let { DimensionEstimate(it, uncertaintyFor(r.dimensionsOrigin, it)) }
            return RadiatorMeasurements(
                heightMm = est(r.heightMm),
                lengthMm = est(r.lengthMm),
                depthMm = est(r.depthMm),
                sections = r.sections?.takeIf { it > 0 },
                columns = r.columns?.takeIf { it > 0 },
                type = if (useType) r.type.takeIf { it != RadiatorType.UNKNOWN } else null,
            )
        }

        private fun uncertaintyFor(origin: ValueOrigin, valueMm: Double): Double = when (origin) {
            ValueOrigin.MEASURED -> RadiatorLibrary.TAPE_UNCERTAINTY_MM
            ValueOrigin.DECLARED -> 2.0
            ValueOrigin.ESTIMATED -> 0.08 * valueMm
            ValueOrigin.UNKNOWN -> 0.15 * valueMm
        }
    }
}

/** Plausibilité d'un nombre d'éléments au vu de la bibliothèque (aide au choix entre N, N/2, 2N…). */
data class CountPlausibility(
    val count: Int,
    /** Score de la meilleure catégorie (0 sans mesure exploitable). */
    val score: Double,
    val level: MatchLevel,
    val bestCategory: RadiatorCategory?,
    val pitchMm: DimensionEstimate?,
    val text: String,
)

/**
 * Rapprochement mesures → catégories de la bibliothèque.
 *
 * Chaque critère mesuré donne un facteur de 0 à 1 : 1 si la mesure tombe dans la plage documentée de la catégorie
 * (incertitude comprise), décroissance gaussienne au-delà, plancher qui n'exclut jamais une catégorie (documentations
 * incomplètes). Un critère que la catégorie ne documente pas compte pour [NEUTRAL_FACTOR] : une catégorie confirmée
 * par la mesure passe devant une catégorie où rien n'est vérifiable. Le score est le produit des facteurs.
 * Le résultat est une PROPOSITION motivée : l'utilisateur choisit et valide.
 */
object RadiatorMatcher {

    /** Facteur d'un critère que la catégorie ne documente pas. */
    const val NEUTRAL_FACTOR = 0.6

    /** Groupe de tête : score ≥ 75 % du meilleur. */
    const val LEADING_FRACTION = 0.75

    // Dispersion entre modèles non couverte par les plages documentées et planchers des facteurs.
    private const val PITCH_MARGIN_MM = 1.5
    private const val PITCH_FLOOR = 0.03
    private const val NO_PATTERN_FACTOR = 0.1
    /** Incertitude-type ajoutée au pas déduit d'une longueur hors tout (bouchons d'extrémité), mm. */
    private const val END_CAPS_MM = 8.0
    private const val RATIO_MARGIN = 0.05
    private const val RATIO_FLOOR = 0.1
    private const val HEIGHT_MARGIN_FRACTION = 0.05
    /** Listes de hauteurs non exhaustives : une hauteur hors plage n'exclut pas une catégorie. */
    private const val HEIGHT_FLOOR = 0.4
    private const val NEAR_HEIGHT_MM = 15.0
    private const val NOT_NORMALIZED_FACTOR = 0.5
    private const val DEPTH_MARGIN_MM = 5.0
    private const val DEPTH_FLOOR = 0.05
    private const val NEAR_DEPTH_MM = 10.0
    private const val NOT_NORMALIZED_DEPTH_FACTOR = 0.6
    private const val COLUMNS_MISMATCH = 0.15
    private const val TYPE_MISMATCH = 0.1
    private const val PATTERN_PARTIAL = 0.5
    private const val PATTERN_MISMATCH = 0.1
    private const val UNCERTAIN_PITCH_REL = 0.12

    /** Classe [categories] selon leur compatibilité avec [measurements], avec scores et raisons. */
    fun match(measurements: RadiatorMeasurements, categories: List<RadiatorCategory> = RadiatorLibrary.categories): MatchResult {
        val m = sanitized(measurements)
        val notes = ArrayList<String>()

        // Pas : mesuré, sinon déduit de la longueur et du nombre d'éléments.
        var pitch = m.pitchMm
        var derived = false
        if (pitch == null && m.lengthMm != null && m.sections != null && m.sections >= 2) {
            val n = m.sections
            pitch = DimensionEstimate(m.lengthMm.valueMm / n, sqrt(sq(m.lengthMm.uncertaintyMm) + sq(END_CAPS_MM)) / n)
            derived = true
            notes += "Pas déduit de la longueur et du nombre d'éléments (${num(m.lengthMm.valueMm, 0)} mm ÷ $n ≈ ${num(pitch.valueMm, 1)} mm) : " +
                "légèrement surestimé si la longueur inclut les bouchons d'extrémité"
        }
        // Le rapport pas / hauteur n'apporte rien de plus quand le pas est mesuré.
        val ratio = if (pitch == null) m.pitchToHeight else null

        val informative = pitch != null || ratio != null || m.heightMm != null || m.depthMm != null ||
            m.columns != null || m.pattern != null || m.type != null
        val evaluated = categories.map { evaluate(it, m, pitch, ratio, informative) }
        val sorted = evaluated.sortedByDescending { it.score }
        val result = MatchResult(sorted, informative, notes, pitch, derived)

        if (!informative) {
            notes += "Aucune mesure exploitable : catégories dans l'ordre de la bibliothèque. Mesurez la profondeur, comptez les " +
                "colonnes vues de dessus ou ajoutez une échelle sur la photo (carte bancaire, feuille A4, dimension mesurée)."
            return result
        }
        if (pitch == null && ratio != null) {
            notes += "Sans échelle, seules les proportions des éléments sont comparées : rapprochement peu discriminant. Une carte " +
                "bancaire ou une feuille A4 plaquée contre la façade, ou une dimension mesurée au mètre, permet de mesurer le pas."
        } else if (pitch == null) {
            notes += "Pas d'élément inconnu : le rapprochement repose sur les autres mesures."
        }
        if (pitch != null && pitch.relativeUncertainty > UNCERTAIN_PITCH_REL) {
            notes += "Pas incertain (± ${num(pitch.uncertaintyMm, 0)} mm) : il départage mal les catégories."
        }
        val leading = result.leading
        if (leading.size >= 2) {
            notes += if (m.depthMm == null || m.columns == null) {
                "Plusieurs catégories restent compatibles : la profondeur (du mur à l'avant de l'élément) et le nombre de colonnes " +
                    "vus de dessus permettent de les départager."
            } else {
                "Plusieurs catégories restent compatibles : comparez les indices visuels (vue de dessus, décor, matériau)."
            }
        }
        return result
    }

    /**
     * Compare des nombres d'éléments candidats (proposé et alternatives d'un comptage) : pour chacun, la meilleure
     * catégorie de la bibliothèque avec les mesures correspondantes. Ordre des candidats conservé.
     */
    fun compareCounts(
        counts: List<Int>,
        categories: List<RadiatorCategory> = RadiatorLibrary.categories,
        measurementsFor: (Int) -> RadiatorMeasurements,
    ): List<CountPlausibility> = counts.filter { it >= 1 }.distinct().map { n ->
        val m = measurementsFor(n)
        val r = match(m, categories)
        val b = r.best
        val what = when {
            r.pitchMm != null -> "pas ≈ ${pm(r.pitchMm)}"
            m.pitchToHeight != null -> "éléments ≈ ${times(1 / m.pitchToHeight)} fois plus hauts que larges"
            else -> null
        }
        val text = if (b == null) "$n éléments : aucune mesure pour départager"
        else "$n éléments" + (what?.let { " ($it)" } ?: "") + " : au mieux « ${b.category.title} » (${b.level.label.lowercase()})"
        CountPlausibility(n, b?.score ?: 0.0, b?.level ?: MatchLevel.UNDETERMINED, b?.category, r.pitchMm, text)
    }

    /** Variante : mêmes mesures de base recalculées pour chaque nombre ([RadiatorMeasurements.forCount]). */
    fun compareCounts(counts: List<Int>, base: RadiatorMeasurements, categories: List<RadiatorCategory> = RadiatorLibrary.categories): List<CountPlausibility> =
        compareCounts(counts, categories) { base.forCount(it) }

    // --- Évaluation d'une catégorie ---

    private fun evaluate(
        cat: RadiatorCategory, m: RadiatorMeasurements, pitch: DimensionEstimate?, ratio: Double?, informative: Boolean,
    ): CategoryMatch {
        val reasons = ArrayList<MatchReason>()
        m.type?.let { reasons += typeReason(cat, it) }
        m.pattern?.let { reasons += patternReason(cat, it) }
        pitch?.let { reasons += pitchReason(cat, it) }
        ratio?.let { reasons += ratioReason(cat, it, m.pitchToHeightRelUncertainty) }
        m.heightMm?.let { reasons += heightReason(cat, it) }
        m.depthMm?.let { reasons += depthReason(cat, it, m.columns) }
        m.columns?.let { reasons += columnsReason(cat, it) }
        val score = reasons.fold(1.0) { acc, r -> acc * r.factor }
        val level = when {
            !informative -> MatchLevel.UNDETERMINED
            reasons.any { it.effect == MatchEffect.CONTRADICTS } -> MatchLevel.UNLIKELY
            reasons.any { it.effect == MatchEffect.WEAKENS } -> MatchLevel.POSSIBLE
            reasons.any { it.effect == MatchEffect.SUPPORTS } -> MatchLevel.COMPATIBLE
            else -> MatchLevel.POSSIBLE
        }
        val range = cat.columnsRange
        val suggested = when {
            range != null && range.first == range.last -> range.first
            m.columns != null && range != null && m.columns in range -> m.columns
            m.depthMm != null -> cat.columnsForDepth(m.depthMm.valueMm)
            else -> null
        }
        return CategoryMatch(cat, score, level, reasons, suggested)
    }

    private fun typeReason(cat: RadiatorCategory, type: RadiatorType): MatchReason =
        if (type == cat.type) MatchReason(MatchCriterion.TYPE, MatchEffect.SUPPORTS, 1.0, "Type indiqué : ${type.label}")
        else MatchReason(MatchCriterion.TYPE, MatchEffect.CONTRADICTS, TYPE_MISMATCH, "Type indiqué (${type.label}) différent de « ${cat.type.label} »")

    private fun patternReason(cat: RadiatorCategory, p: FrontPattern): MatchReason {
        val periodic = setOf(FrontPattern.VERTICAL_ELEMENTS, FrontPattern.VERTICAL_FLUTES)
        return when {
            cat.frontPatterns.isEmpty() -> MatchReason(MatchCriterion.PATTERN, MatchEffect.NEUTRAL, NEUTRAL_FACTOR, "Motif de face non documenté pour cette catégorie")
            p in cat.frontPatterns -> MatchReason(MatchCriterion.PATTERN, MatchEffect.SUPPORTS, 1.0, "Motif de face compatible (${p.label})")
            p in periodic && cat.frontPatterns.any { it in periodic } -> MatchReason(MatchCriterion.PATTERN, MatchEffect.WEAKENS, PATTERN_PARTIAL,
                if (p == FrontPattern.VERTICAL_ELEMENTS) "Motif vertical régulier : sur ce type, il s'agirait de cannelures plutôt que d'éléments"
                else "Cannelures fines : ce type présente plutôt des éléments juxtaposés")
            else -> MatchReason(MatchCriterion.PATTERN, MatchEffect.CONTRADICTS, PATTERN_MISMATCH,
                "Motif de face différent (attendu : ${cat.frontPatterns.joinToString(" ou ") { it.label }})")
        }
    }

    private fun pitchReason(cat: RadiatorCategory, p: DimensionEstimate): MatchReason {
        val what = "Pas de ${pm(p)}"
        cat.pitchRangeMm?.let { r ->
            val (f, side) = interval(p.valueMm, p.uncertaintyMm, r.start, r.endInclusive, PITCH_MARGIN_MM, PITCH_FLOOR)
            val e = effectOf(f)
            var text = if (e == MatchEffect.SUPPORTS) "$what compatible (usuel ${range(r)})" else "$what ${compared(e, side, false)} à l'usuel (${range(r)})"
            if (e != MatchEffect.SUPPORTS && cat.pitchNote.isNotBlank()) text += " ; ${cat.pitchNote}"
            return MatchReason(MatchCriterion.PITCH, e, f, text)
        }
        cat.flutePitchRangeMm?.let { r ->
            val (f, side) = interval(p.valueMm, p.uncertaintyMm, r.start, r.endInclusive, PITCH_MARGIN_MM, PITCH_FLOOR)
            val e = effectOf(f)
            val text = if (e == MatchEffect.SUPPORTS) "$what : correspond aux cannelures d'un panneau (${range(r)}), le motif compté ne serait pas des éléments"
            else "$what ${compared(e, side, false)} aux cannelures usuelles d'un panneau (${range(r)})"
            return MatchReason(MatchCriterion.PITCH, e, f, text)
        }
        return if (cat.type == RadiatorType.DECORATIVE) {
            MatchReason(MatchCriterion.PITCH, MatchEffect.NEUTRAL, NEUTRAL_FACTOR, "Pas variable selon le modèle : non vérifiable")
        } else {
            MatchReason(MatchCriterion.PITCH, MatchEffect.CONTRADICTS, NO_PATTERN_FACTOR, "Aucun motif vertical régulier attendu sur ce type d'émetteur")
        }
    }

    private fun ratioReason(cat: RadiatorCategory, ratio: Double, rel: Double): MatchReason {
        val r = cat.pitchToHeightRange
        if (r == null) {
            return when {
                cat.patternPitchRangeMm != null -> MatchReason(MatchCriterion.PROPORTIONS, MatchEffect.NEUTRAL, NEUTRAL_FACTOR,
                    "Proportions non documentées pour cette catégorie (hauteurs inconnues)")
                cat.type == RadiatorType.DECORATIVE -> MatchReason(MatchCriterion.PROPORTIONS, MatchEffect.NEUTRAL, NEUTRAL_FACTOR,
                    "Proportions variables selon le modèle : non vérifiables")
                else -> MatchReason(MatchCriterion.PROPORTIONS, MatchEffect.CONTRADICTS, NO_PATTERN_FACTOR,
                    "Aucun motif vertical régulier attendu sur ce type d'émetteur")
            }
        }
        val d = max(0.0, max(ln(r.start) - ln(ratio), ln(ratio) - ln(r.endInclusive)))
        val s = sqrt(sq(rel) + sq(RATIO_MARGIN))
        val f = max(RATIO_FLOOR, exp(-0.5 * sq(d / s)))
        val e = effectOf(f)
        val usual = "${times(1 / r.endInclusive)} à ${times(1 / r.start)} fois"
        val unit = if (cat.pitchRangeMm != null) "élément" else "motif"
        val shape = "$unit ≈ ${times(1 / ratio)} fois plus haut que large"
        val text = when {
            e == MatchEffect.SUPPORTS && cat.pitchRangeMm == null -> "Proportions (sans échelle) : $shape, compatible avec des cannelures de panneau (usuel $usual)"
            e == MatchEffect.SUPPORTS -> "Proportions (sans échelle) : $shape, compatible (usuel $usual)"
            ratio > r.endInclusive -> "Proportions (sans échelle) : $shape, ${if (e == MatchEffect.WEAKENS) "un peu" else "nettement"} plus trapu que l'usuel ($usual)"
            else -> "Proportions (sans échelle) : $shape, ${if (e == MatchEffect.WEAKENS) "un peu" else "nettement"} plus élancé que l'usuel ($usual)"
        }
        return MatchReason(MatchCriterion.PROPORTIONS, e, f, text)
    }

    private fun heightReason(cat: RadiatorCategory, h: DimensionEstimate): MatchReason {
        val what = "Hauteur de ${pm(h)}"
        val r = cat.heightRangeMm
            ?: return MatchReason(MatchCriterion.HEIGHT, MatchEffect.NEUTRAL, NEUTRAL_FACTOR, "Hauteurs non documentées pour cette catégorie")
        val (fRange, _) = interval(h.valueMm, h.uncertaintyMm, r.first.toDouble(), r.last.toDouble(), HEIGHT_MARGIN_FRACTION * h.valueMm, HEIGHT_FLOOR)
        val tol = max(2 * h.uncertaintyMm, NEAR_HEIGHT_MM)
        if (cat.normalizedHeightsMm.isNotEmpty()) {
            val nearest = cat.normalizedHeightsMm.minBy { abs(it - h.valueMm) }
            return if (abs(nearest - h.valueMm) <= tol) {
                MatchReason(MatchCriterion.HEIGHT, MatchEffect.SUPPORTS, 1.0, "$what : hauteur normalisée $nearest mm")
            } else {
                val f = minOf(fRange, NOT_NORMALIZED_FACTOR)
                MatchReason(MatchCriterion.HEIGHT, effectOf(f), f,
                    "$what : aucune hauteur normalisée proche (${cat.normalizedHeightsMm.joinToString(", ")} mm)")
            }
        }
        val e = effectOf(fRange)
        val usual = cat.typicalHeightsMm.minByOrNull { abs(it - h.valueMm) }?.takeIf { abs(it - h.valueMm) <= tol }
        val text = when {
            e == MatchEffect.SUPPORTS && usual != null -> "$what proche d'une hauteur usuelle ($usual mm)"
            e == MatchEffect.SUPPORTS -> "$what dans la plage usuelle (${rangeInt(r)})"
            else -> "$what en dehors des hauteurs documentées (${rangeInt(r)}, liste non exhaustive)"
        }
        return MatchReason(MatchCriterion.HEIGHT, e, fRange, text)
    }

    private fun depthReason(cat: RadiatorCategory, d: DimensionEstimate, columns: Int?): MatchReason {
        val what = "Profondeur de ${pm(d)}"
        val byColumns = columns?.let { cat.depthByColumnsMm[it] }
        val r = byColumns ?: cat.depthRangeMm
            ?: return MatchReason(MatchCriterion.DEPTH, MatchEffect.NEUTRAL, NEUTRAL_FACTOR, "Profondeur non documentée pour cette catégorie")
        var (f, side) = interval(d.valueMm, d.uncertaintyMm, r.first.toDouble(), r.last.toDouble(), DEPTH_MARGIN_MM, DEPTH_FLOOR)
        val ref = if (byColumns != null) "pour $columns colonnes : ${rangeInt(r)}" else "usuel ${rangeInt(r)}"
        var e = effectOf(f)
        var text = if (e == MatchEffect.SUPPORTS) "$what compatible ($ref)" else "$what ${compared(e, side, true)} à l'usuel ($ref)"
        if (e == MatchEffect.SUPPORTS && cat.normalizedDepthsMm.isNotEmpty()) {
            val nearest = cat.normalizedDepthsMm.minBy { abs(it - d.valueMm) }
            if (abs(nearest - d.valueMm) <= max(2 * d.uncertaintyMm, NEAR_DEPTH_MM)) {
                text += " ; profondeur normalisée $nearest mm"
            } else {
                f = minOf(f, NOT_NORMALIZED_DEPTH_FACTOR)
                e = effectOf(f)
                text += ", mais aucune profondeur normalisée proche (${cat.normalizedDepthsMm.joinToString(", ")} mm)"
            }
        }
        if (columns == null && e != MatchEffect.CONTRADICTS) {
            cat.columnsForDepth(d.valueMm)?.let { text += " ; correspond à ≈ ${columnsText(it)}" }
        }
        return MatchReason(MatchCriterion.DEPTH, e, f, text)
    }

    private fun columnsReason(cat: RadiatorCategory, c: Int): MatchReason {
        val r = cat.columnsRange
            ?: return MatchReason(MatchCriterion.COLUMNS, MatchEffect.NEUTRAL, NEUTRAL_FACTOR, "Nombre de colonnes non caractéristique de cette catégorie")
        val what = columnsText(c).replaceFirstChar { it.uppercase() }
        return if (c in r) {
            MatchReason(MatchCriterion.COLUMNS, MatchEffect.SUPPORTS, 1.0,
                if (r.first == r.last) "$what : compatible" else "$what : compatible (${rangeColumns(r)} selon les modèles)")
        } else {
            MatchReason(MatchCriterion.COLUMNS, MatchEffect.CONTRADICTS, COLUMNS_MISMATCH, "$what : cette catégorie en compte ${rangeColumns(r)}")
        }
    }

    // --- Outils ---

    private fun sanitized(m: RadiatorMeasurements): RadiatorMeasurements {
        fun clean(d: DimensionEstimate?) = d?.takeIf { it.valueMm > 0 && it.valueMm.isFinite() }?.let {
            if (it.uncertaintyMm.isFinite() && it.uncertaintyMm >= 0) it else DimensionEstimate(it.valueMm, 0.2 * it.valueMm)
        }
        return m.copy(
            pitchMm = clean(m.pitchMm), heightMm = clean(m.heightMm), lengthMm = clean(m.lengthMm), depthMm = clean(m.depthMm),
            sections = m.sections?.takeIf { it >= 1 },
            pitchToHeight = m.pitchToHeight?.takeIf { it > 0 && it.isFinite() },
            pitchToHeightRelUncertainty = m.pitchToHeightRelUncertainty.takeIf { it >= 0 && it.isFinite() } ?: RadiatorMeasurements.DEFAULT_RATIO_UNCERTAINTY,
            columns = m.columns?.takeIf { it >= 1 },
            type = m.type?.takeIf { it != RadiatorType.UNKNOWN },
        )
    }

    /**
     * Facteur de compatibilité d'une mesure x ± u avec la plage [lo, hi] : 1 dedans, décroissance gaussienne au-delà
     * (écart-type combinant l'incertitude et la dispersion [margin]), borné par [floor]. Côté : −1 en dessous, +1 au-dessus.
     */
    private fun interval(x: Double, u: Double, lo: Double, hi: Double, margin: Double, floor: Double): Pair<Double, Int> {
        val d = max(0.0, max(lo - x, x - hi))
        val s = sqrt(u * u + margin * margin).coerceAtLeast(1e-9)
        val side = when { x < lo -> -1; x > hi -> 1; else -> 0 }
        return max(floor, exp(-0.5 * sq(d / s))) to side
    }

    private fun effectOf(f: Double) = when {
        f >= 0.8 -> MatchEffect.SUPPORTS
        f >= 0.25 -> MatchEffect.WEAKENS
        else -> MatchEffect.CONTRADICTS
    }

    /** « un peu inférieur », « nettement supérieure »… */
    private fun compared(e: MatchEffect, side: Int, feminine: Boolean): String {
        val degree = if (e == MatchEffect.CONTRADICTS) "nettement" else "un peu"
        val adj = (if (side < 0) "inférieur" else "supérieur") + if (feminine) "e" else ""
        return "$degree $adj"
    }

    private fun columnsText(c: Int) = if (c == 1) "1 colonne" else "$c colonnes"

    private fun rangeColumns(r: IntRange) = when {
        r.first == r.last -> "${r.first}"
        r.last == r.first + 1 -> "${r.first} ou ${r.last}"
        else -> "de ${r.first} à ${r.last}"
    }

    private fun sq(x: Double) = x * x

    /** Nombre à la française : entier si [decimals] = 0 ou valeur entière, sinon une décimale. */
    private fun num(v: Double, decimals: Int): String =
        if (decimals == 0 || abs(v - v.roundToInt()) < 0.05) v.roundToInt().toString() else v.fr(decimals)

    /** « 61 ± 3 mm » ; une décimale quand l'incertitude est inférieure à 2 mm. */
    private fun pm(d: DimensionEstimate): String {
        val fine = d.uncertaintyMm < 2.0
        return "${num(d.valueMm, if (fine) 1 else 0)} ± ${num(d.uncertaintyMm, if (fine) 1 else 0)} mm"
    }

    private fun range(r: ClosedFloatingPointRange<Double>) =
        if (r.start == r.endInclusive) "${num(r.start, 1)} mm" else "${num(r.start, 1)}–${num(r.endInclusive, 1)} mm"

    private fun rangeInt(r: IntRange) = if (r.first == r.last) "${r.first} mm" else "${r.first}–${r.last} mm"

    /** « 9,5 » sous 10, entier au-delà. */
    private fun times(x: Double) = if (x < 10) num(x, 1) else x.roundToInt().toString()
}
