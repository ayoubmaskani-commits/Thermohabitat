package fr.thermohabitat.thermal.library

import fr.thermohabitat.thermal.fr
import fr.thermohabitat.thermal.model.PanelType
import fr.thermohabitat.thermal.model.RadiatorEra
import fr.thermohabitat.thermal.model.RadiatorType
import fr.thermohabitat.thermal.vision.DimensionEstimate
import kotlin.math.abs
import kotlin.math.roundToInt

/** Millimètres à la française : entier si la valeur l'est (à 0,05 près), sinon une décimale. */
internal fun mmText(v: Double): String = if (abs(v - v.roundToInt()) < 0.05) v.roundToInt().toString() else v.fr(1)

/** « 55 à 66 mm », ou « 60 mm » pour une valeur unique. */
internal fun rangeMmText(r: ClosedFloatingPointRange<Double>): String =
    if (r.start == r.endInclusive) "${mmText(r.start)} mm" else "${mmText(r.start)} à ${mmText(r.endInclusive)} mm"

/** Motif de la façade vu de face, tel qu'on le voit sur une photo. */
enum class FrontPattern(val label: String) {
    VERTICAL_ELEMENTS("éléments verticaux juxtaposés"),
    VERTICAL_FLUTES("cannelures verticales fines"),
    HORIZONTAL_BARS("barreaux horizontaux"),
    SMOOTH("façade lisse"),
}

/** Fiabilité des données d'une catégorie. */
enum class DataReliability(val label: String) {
    DOCUMENTED("Documentée (fiches fabricants, normes)"),
    ORDER_OF_MAGNITUDE("Ordre de grandeur (à confirmer)"),
}

/**
 * Puissance d'un élément relevée dans une documentation, en W à ΔT 50 K (régime 75/65/20 °C, EN 442).
 * Sert d'ordre de grandeur de comparaison : la puissance d'un radiateur se calcule avec ses propres mesures.
 *
 * @property heightMm hauteur hors tout de l'élément
 * @property centerDistanceMm entraxe des raccordements, quand c'est la grandeur publiée
 * @property derived valeur calculée (total ÷ nombre d'éléments, hauteur ≈ entraxe + décalage…), non publiée telle quelle
 */
data class ElementPowerReference(
    val model: String,
    val wattsDt50: Double,
    val heightMm: Int? = null,
    val depthMm: Int? = null,
    val columns: Int? = null,
    val centerDistanceMm: Int? = null,
    val reliability: DataReliability = DataReliability.DOCUMENTED,
    val derived: Boolean = false,
    val note: String = "",
    val source: String,
)

/**
 * Catégorie de la bibliothèque illustrée d'identification des radiateurs.
 *
 * @property columns nombre de colonnes caractéristique (radiateurs à éléments), sinon null
 * @property depthRangeMm profondeur hors tout usuelle d'un élément
 * @property sectionPitchMm pas d'élément typique (valeur affichée « ≈ »), voir [pitchRangeMm] pour la plage
 * @property visualCues indices visuels permettant de reconnaître la catégorie sur place
 * @property measureTips mesures à relever pour l'estimation de puissance
 * @property pitchRangeMm plage de pas d'élément (mm, d'axe en axe) : un élément par période du motif vu de face
 * @property flutePitchRangeMm pas des cannelures d'un panneau (motif périodique qui n'est PAS un élément)
 * @property columnsRange colonnes possibles selon les modèles
 * @property typicalHeightsMm hauteurs hors tout relevées dans les documentations (liste non exhaustive)
 * @property heightRangeMm plage de hauteurs usuelles (par défaut : extrêmes de [typicalHeightsMm])
 * @property normalizedHeightsMm hauteurs normalisées (DIN) : une hauteur éloignée de toutes est peu probable
 * @property normalizedDepthsMm profondeurs normalisées (DIN)
 * @property depthByColumnsMm profondeur selon le nombre de colonnes, quand elle est documentée
 * @property frontPatterns motifs possibles vus de face
 * @property elementPowers puissances d'éléments documentées (ordre de grandeur de comparaison)
 * @property pitchNote précision affichée quand un pas mesuré sort de [pitchRangeMm]
 * @property heightNote réserve sur les hauteurs (valeurs extrapolées, liste non exhaustive), rappelée avec la hauteur
 */
data class RadiatorCategory(
    val code: String,
    val title: String,
    val type: RadiatorType,
    val era: RadiatorEra,
    val columns: Int?,
    val panelType: PanelType? = null,
    val depthRangeMm: IntRange?,
    val sectionPitchMm: Int?,
    val description: String,
    val visualCues: List<String>,
    val measureTips: List<String>,
    val pitchRangeMm: ClosedFloatingPointRange<Double>? = sectionPitchMm?.let { it.toDouble()..it.toDouble() },
    val flutePitchRangeMm: ClosedFloatingPointRange<Double>? = null,
    val columnsRange: IntRange? = columns?.let { it..it },
    val typicalHeightsMm: List<Int> = emptyList(),
    val heightRangeMm: IntRange? = typicalHeightsMm.takeIf { it.isNotEmpty() }?.let { it.min()..it.max() },
    val normalizedHeightsMm: List<Int> = emptyList(),
    val normalizedDepthsMm: List<Int> = emptyList(),
    val depthByColumnsMm: Map<Int, IntRange> = emptyMap(),
    val frontPatterns: Set<FrontPattern> = emptySet(),
    val elementPowers: List<ElementPowerReference> = emptyList(),
    val pitchNote: String = "",
    val heightNote: String = "",
    /** Période de fabrication et de pose. */
    val epoch: String = "",
    val reliability: DataReliability = DataReliability.ORDER_OF_MAGNITUDE,
    /** Précisions sur les données (provenance, limites). */
    val remarks: String = "",
    /** Documents consultés (adresses web), vides pour les catégories décrites de façon générale. */
    val sources: List<String> = emptyList(),
) {
    /** Pas du motif vertical vu de face : éléments, sinon cannelures. */
    val patternPitchRangeMm: ClosedFloatingPointRange<Double>? get() = pitchRangeMm ?: flutePitchRangeMm

    /**
     * Vrai si une période du motif vu de face est un élément : le nombre compté sur la photo est alors un nombre
     * d'éléments. Faux pour les cannelures d'un panneau (le compte n'a pas de sens pour la puissance).
     */
    val countsElements: Boolean get() = pitchRangeMm != null

    /** Pas affiché dans la bibliothèque (« Pas d'élément 55 à 66 mm »), cannelures signalées ; null si sans objet. */
    val pitchText: String?
        get() = when {
            pitchRangeMm != null -> "Pas d'élément ${rangeMmText(pitchRangeMm)}"
            flutePitchRangeMm != null -> "Cannelures au pas de ${rangeMmText(flutePitchRangeMm)} (ce ne sont pas des éléments)"
            else -> null
        }

    /** Hauteurs affichées dans la bibliothèque (normalisées, sinon plage usuelle), réserve comprise ; null si inconnues. */
    val heightText: String?
        get() {
            val base = when {
                normalizedHeightsMm.isNotEmpty() -> "Hauteurs normalisées ${normalizedHeightsMm.joinToString(", ")} mm"
                heightRangeMm != null -> "Hauteurs usuelles ${rangeMmText(heightRangeMm.first.toDouble()..heightRangeMm.last.toDouble())}"
                else -> return null
            }
            return if (heightNote.isBlank()) base else "$base ; $heightNote"
        }

    /** Rapport pas / hauteur possible (pas mini / hauteur maxi … pas maxi / hauteur mini), null si non documenté. */
    val pitchToHeightRange: ClosedFloatingPointRange<Double>?
        get() {
            val p = patternPitchRangeMm ?: return null
            val h = heightRangeMm ?: return null
            if (h.first <= 0) return null
            return (p.start / h.last)..(p.endInclusive / h.first)
        }

    /** Nombre de colonnes correspondant à une profondeur (tableau [depthByColumnsMm]), null si aucun ou ambigu. */
    fun columnsForDepth(depthMm: Double, toleranceMm: Double = 8.0): Int? {
        val hits = depthByColumnsMm.filter { (_, r) -> depthMm >= r.first - toleranceMm && depthMm <= r.last + toleranceMm }.keys
        return hits.singleOrNull()
    }

    /**
     * Puissances documentées d'éléments comparables (hauteur à ±[heightTolerance] près, profondeur à ±20 %, mêmes
     * colonnes quand ces valeurs sont connues), de la plus proche à la plus éloignée en hauteur.
     */
    fun comparableElementPowers(
        heightMm: Double?,
        depthMm: Double? = null,
        columns: Int? = null,
        heightTolerance: Double = 0.12,
    ): List<ElementPowerReference> = elementPowers
        .filter { e -> heightMm == null || (e.heightMm != null && abs(e.heightMm - heightMm) <= heightTolerance * heightMm) }
        .filter { e -> depthMm == null || e.depthMm == null || abs(e.depthMm - depthMm) <= 0.20 * depthMm }
        .filter { e -> columns == null || e.columns == null || e.columns == columns }
        .sortedBy { e -> if (heightMm == null || e.heightMm == null) 0.0 else abs(e.heightMm - heightMm) }

    /** Plage des puissances documentées d'éléments comparables (W à ΔT 50 K), null si aucune. */
    fun elementPowerRangeW(heightMm: Double?, depthMm: Double? = null, columns: Int? = null): ClosedFloatingPointRange<Double>? =
        comparableElementPowers(heightMm, depthMm, columns).takeIf { it.isNotEmpty() }
            ?.let { list -> list.minOf { it.wattsDt50 }..list.maxOf { it.wattsDt50 } }
}

/**
 * Bibliothèque illustrée (les illustrations sont dessinées par l'application à partir de ces données).
 *
 * Données de rapprochement (pas, hauteurs, profondeurs, puissances) issues de fiches fabricants, de normes (DIN 4703,
 * EN 442) et de tableaux techniques ; les valeurs non publiées telles quelles sont signalées (ordre de grandeur, valeur
 * calculée). Les codes existants ne changent jamais : des fiches enregistrées y font référence.
 */
object RadiatorLibrary {

    /** Incertitude-type d'une profondeur relevée au mètre (mm). */
    const val TAPE_UNCERTAINTY_MM = 5.0

    private val sectionalTips = listOf(
        "Comptez les éléments sur le dessus du radiateur.",
        "Mesurez la hauteur hors tout (sans les pieds) et la profondeur du mur vers l'avant de l'élément.",
        "Relevez l'entraxe des raccordements si possible.",
        "Sur la photo, une carte bancaire ou une feuille A4 plaquée contre la façade donne l'échelle.",
    )

    private val vertical = setOf(FrontPattern.VERTICAL_ELEMENTS)

    // Sources communes.
    private const val CASTRADS_GRACE = "https://www.castrads.com/us/grace"
    private const val IDEAL_93_4 = "https://dpp-tool.com/fr/passport/radiateur-fonte-4-colonnes-pr63o9/"
    private const val FUTURA_FONTE = "https://forums.futura-sciences.com/habitat-bioclimatique-isolation-chauffage/849483-puissance-radiateurs-fonte.html"
    private const val FUTURA_VIEUX = "https://forums.futura-sciences.com/habitat-bioclimatique-isolation-chauffage/865977-puissance-vieux-radiateur.html"
    private const val OPENCLASSROOMS = "https://openclassrooms.com/courses/8491831-decouvrez-le-poste-de-renovation-cvc-chauffage-ventilation-climatisation/8600346-identifiez-les-differents-types-de-radiateurs"
    private const val HG_TEC_FONTE = "https://www.hg-tec.de/downloads/technischedatengussradiatoren.pdf"
    private const val WATT42 = "https://42watt.de/magazin/gliederheizkorper"
    private const val ENERGIE_EXPERTEN = "https://www.energie-experten.org/heizung/heizungstechnik/heizkoerper/gliederheizkoerper"
    private const val FONDITAL_B4 = "https://www.fondital.com/downloads/7360/3073/CTC_SCHEDA_CALIDOR_SUPER_B4_EN.pdf"
    private const val RADSON_DELTA = "https://www.radson.com/docs/DELTA_V_-_FR.pdf"
    private const val MEGABAD_50 = "https://www.megabad.com/zehnder-wohnraumheizkoerper-charleston-bauhoehe-50-cm-a-2148754.htm"
    private const val MEGABAD_60 = "https://www.megabad.com/hersteller-zehnder-wohnraumheizkoerper-charleston-bauhoehe-60-a-365245.htm"

    private fun mercury(columns: Int, heightMm: Int, depthMm: Int, watts: Double, url: String, note: String = "") =
        ElementPowerReference("Castrads Mercury $columns colonnes (réédition, pas 58,3 mm)", watts, heightMm, depthMm, columns,
            note = note, source = url)

    private fun din(heightMm: Int, depthMm: Int, watts: Double) =
        ElementPowerReference("Fonte DIN 4703 H $heightMm × P $depthMm", watts, heightMm, depthMm,
            centerDistanceMm = heightMm - 80, source = HG_TEC_FONTE)

    private fun steelDin(heightMm: Int, depthMm: Int, watts: Double) =
        ElementPowerReference("Acier à éléments H $heightMm × P $depthMm (tableau générique)", watts, heightMm, depthMm,
            centerDistanceMm = heightMm - 100, source = ENERGIE_EXPERTEN)

    private fun calidor(centerMm: Int, watts: Double) =
        ElementPowerReference("Fondital Calidor Super B4 (entraxe $centerMm, P 97, pas 80)", watts, heightMm = centerMm + 82, depthMm = 97,
            centerDistanceMm = centerMm, derived = true, note = "Hauteur ≈ entraxe + 82 mm (ordre de grandeur)", source = FONDITAL_B4)

    private fun tube(model: String, columns: Int, heightMm: Int, watts: Double, url: String, depthMm: Int? = null, derived: Boolean = true) =
        ElementPowerReference(model, watts, heightMm, depthMm, columns, derived = derived,
            note = if (derived) "Puissance totale ÷ nombre d'éléments" else "", source = url)

    val categories: List<RadiatorCategory> = listOf(
        RadiatorCategory(
            code = "fonte_2col", title = "Fonte 2 colonnes", type = RadiatorType.CAST_IRON_COLUMN,
            era = RadiatorEra.OLD, columns = 2, depthRangeMm = 65..115, sectionPitchMm = 60,
            description = "Éléments étroits composés de deux tubes verticaux reliés en haut et en bas. Posés en masse de 1900 à 1970 ; rééditions actuelles.",
            visualCues = listOf(
                "Vu de dessus : 2 tubes par élément", "Éléments étroits et réguliers, joints verticaux sombres",
                "Profondeur faible (≈ 7 à 11 cm)", "Moyeux de raccordement en haut et en bas de chaque élément",
            ),
            measureTips = sectionalTips,
            pitchRangeMm = 55.0..66.0, typicalHeightsMm = listOf(460, 615, 750, 760, 960), frontPatterns = vertical,
            elementPowers = listOf(
                mercury(2, 460, 70, 35.3, "https://www.castrads.com/en/product/mercury-2-column-460mm"),
                mercury(2, 760, 70, 58.6, "https://www.castrads.com/en/product/mercury-2-column-760mm/"),
                mercury(2, 960, 70, 73.6, "https://www.castrads.com/en/product/mercury-2-column-960mm/"),
                ElementPowerReference("Fonte 2 colonnes (témoignage)", 58.0, 750, columns = 2,
                    reliability = DataReliability.ORDER_OF_MAGNITUDE, source = FUTURA_FONTE),
            ),
            epoch = "1900–1970 (pose massive à partir des années 1930) ; rééditions actuelles",
            reliability = DataReliability.DOCUMENTED,
            remarks = "Pas documentés : 55 mm (Ideal Standard), 58,3 (Mercury), 60 (DIN 4703), ≈ 62–64 (Carron, longueur ÷ nombre), " +
                "63,5 = 2½ po (radiateurs anciens). Profondeur 70 mm documentée ; 115 mm : borne haute (ordre de grandeur) pour les " +
                "modèles anciens. Puissances documentées sur des rééditions ; modèles anciens : ordre de grandeur.",
            sources = listOf(
                "https://www.castrads.com/en/product/mercury-2-column-460mm", "https://www.castrads.com/en/product/mercury-2-column-760mm/",
                "https://www.castrads.com/en/product/mercury-2-column-960mm/", HG_TEC_FONTE,
                "https://ukaa.com/carron-victorian-460mm-tall-cast-iron-radiator-12-sections-long-21150",
                "https://forum.heatinghelp.com/discussion/comment/1311043/", FUTURA_FONTE, OPENCLASSROOMS, WATT42,
            ),
        ),
        RadiatorCategory(
            code = "fonte_3col", title = "Fonte 3 colonnes", type = RadiatorType.CAST_IRON_COLUMN,
            era = RadiatorEra.OLD, columns = 3, depthRangeMm = 95..165, sectionPitchMm = 60,
            description = "Trois tubes par élément. Profondeur moyenne ; très courant dans les maisons des années 1920–1960.",
            visualCues = listOf("Vu de dessus : 3 tubes alignés vers l'avant", "Profondeur ≈ 10 à 16 cm", "Joints verticaux réguliers au pas ≈ 6 cm"),
            measureTips = sectionalTips,
            pitchRangeMm = 55.0..66.0, typicalHeightsMm = listOf(560, 660, 750, 760, 860), frontPatterns = vertical,
            elementPowers = listOf(
                mercury(3, 560, 101, 53.8, "https://www.castrads.com/en/product/mercury-3-column-560mm/"),
                mercury(3, 660, 101, 66.4, "https://www.castrads.com/en/product/mercury-3-column-660mm"),
                mercury(3, 760, 101, 77.5, "https://www.castrads.com/en/product/mercury-3-column-760mm"),
                mercury(3, 860, 101, 87.4, "https://www.castrads.com/en/product/mercury-3-column-860mm"),
                ElementPowerReference("Fonte 3 colonnes (témoignage)", 84.0, 750, columns = 3,
                    reliability = DataReliability.ORDER_OF_MAGNITUDE, source = FUTURA_FONTE),
            ),
            epoch = "1900–1970, très courant 1920–1960 ; rééditions actuelles",
            reliability = DataReliability.DOCUMENTED,
            remarks = "Profondeur 101 mm documentée (réédition) ; plage 95–165 mm : ordre de grandeur incluant les modèles anciens plus profonds.",
            sources = listOf(
                "https://www.castrads.com/en/product/mercury-3-column-560mm/", "https://www.castrads.com/en/product/mercury-3-column-660mm",
                "https://www.castrads.com/en/product/mercury-3-column-760mm", "https://www.castrads.com/en/product/mercury-3-column-860mm",
                FUTURA_FONTE, OPENCLASSROOMS,
            ),
        ),
        RadiatorCategory(
            code = "fonte_4col", title = "Fonte 4 colonnes", type = RadiatorType.CAST_IRON_COLUMN,
            era = RadiatorEra.OLD, columns = 4, depthRangeMm = 135..225, sectionPitchMm = 60,
            description = "Quatre tubes par élément, radiateur profond et lourd. Forte inertie.",
            visualCues = listOf("Vu de dessus : 4 tubes", "Profondeur ≈ 14 à 22 cm", "Radiateur lourd, souvent sur pieds"),
            measureTips = sectionalTips,
            pitchRangeMm = 55.0..66.0, typicalHeightsMm = listOf(360, 460, 500, 560, 660, 760, 860, 871, 960), frontPatterns = vertical,
            elementPowers = listOf(
                mercury(4, 460, 140, 68.0, "https://www.castrads.com/en/product/mercury-4-column-460mm/", "58 W sur une autre page du fabricant"),
                mercury(4, 560, 140, 71.0, "https://www.castrads.com/en/product/mercury-4-column-560mm/"),
                mercury(4, 760, 140, 96.0, "https://www.castrads.com/en/product/mercury-4-column-760mm/"),
                mercury(4, 860, 140, 109.0, "https://clone.castrads.com/en/product/mercury-4-column-860mm"),
                mercury(4, 960, 140, 122.0, "https://www.castrads.com/en/product/mercury-4-column-960mm/"),
                ElementPowerReference("Castrads Grace 4 colonnes (pas 60,5 mm)", 86.0, 660, 175, 4, note = "85,3 ou 87 W selon la page",
                    source = CASTRADS_GRACE),
                ElementPowerReference("Ideal Standard fonte 4 colonnes 93-4 IS (pas 55 mm, NF EN 442-1)", 171.0, 871, 143, 4,
                    centerDistanceMm = 800, source = IDEAL_93_4),
                ElementPowerReference("Fonte 4 colonnes (témoignage)", 135.0, 930, columns = 4,
                    reliability = DataReliability.ORDER_OF_MAGNITUDE, source = FUTURA_FONTE),
                ElementPowerReference("Fonte 4 colonnes neuve (témoignage)", 165.0, 900, columns = 4,
                    reliability = DataReliability.ORDER_OF_MAGNITUDE, source = FUTURA_VIEUX),
            ),
            epoch = "1900–1970 ; rééditions et fabrication actuelle",
            reliability = DataReliability.DOCUMENTED,
            remarks = "Forte dispersion des puissances à hauteur voisine (122 W à H 960, 171 W à H 871 selon le profil) : " +
                "la puissance dépend du profil, pas seulement de la hauteur. Profondeurs documentées 140–175 mm ; 225 mm : borne " +
                "haute (ordre de grandeur).",
            sources = listOf(
                "https://www.castrads.com/en/product/mercury-4-column-460mm/", "https://www.castrads.com/en/product/mercury-4-column-560mm/",
                "https://www.castrads.com/en/product/mercury-4-column-760mm/", "https://clone.castrads.com/en/product/mercury-4-column-860mm",
                "https://www.castrads.com/en/product/mercury-4-column-960mm/", CASTRADS_GRACE, IDEAL_93_4, FUTURA_FONTE, FUTURA_VIEUX,
            ),
        ),
        RadiatorCategory(
            code = "fonte_5_6col", title = "Fonte 5 ou 6 colonnes", type = RadiatorType.CAST_IRON_COLUMN,
            era = RadiatorEra.OLD, columns = 5, depthRangeMm = 200..260, sectionPitchMm = 60,
            description = "Radiateurs très profonds de grands volumes (halls, salles de séjour anciennes), souvent bas sous les fenêtres.",
            visualCues = listOf("Vu de dessus : 5 ou 6 tubes", "Profondeur supérieure à 20 cm", "Radiateur bas et profond fréquent (sous fenêtre)"),
            measureTips = sectionalTips,
            pitchRangeMm = 55.0..66.0, columnsRange = 5..6, typicalHeightsMm = listOf(360, 485, 625, 660, 960), frontPatterns = vertical,
            elementPowers = listOf(
                mercury(6, 360, 215, 63.1, "https://www.castrads.com/en/product/mercury-6-column-360mm/"),
                mercury(6, 960, 215, 170.7, "https://www.castrads.com/en/product/mercury-6-column-960mm/"),
                ElementPowerReference("Carron Victorian (pas ≈ 62,4 mm)", 120.1, 740, 220, derived = true,
                    note = "Colonnes non précisées ; valeur calculée (total ÷ nombre d'éléments)",
                    source = "https://www.contractsfinder.service.gov.uk/Notice/Attachment/61a6a6b9-543d-4fc3-8a95-c25e76ffc8b8"),
            ),
            epoch = "1900–1970 (halls, grands volumes) ; rééditions actuelles",
            reliability = DataReliability.DOCUMENTED,
            remarks = "Profondeurs documentées : 215 mm (6 colonnes), 220 mm, 250 mm (fonte DIN). Plage 200–260 mm : marge prudente.",
            sources = listOf(
                "https://www.castrads.com/en/product/mercury-6-column-360mm/", "https://www.castrads.com/en/product/mercury-6-column-960mm/",
                "https://ukaa.com/victorian-6-column-cast-iron-radiator-18-sections-long-6788",
                "https://www.contractsfinder.service.gov.uk/Notice/Attachment/61a6a6b9-543d-4fc3-8a95-c25e76ffc8b8", HG_TEC_FONTE,
            ),
        ),
        RadiatorCategory(
            code = "fonte_moderne", title = "Fonte moderne à colonnes fines", type = RadiatorType.CAST_IRON_COLUMN,
            era = RadiatorEra.MODERN, columns = null, depthRangeMm = 70..215, sectionPitchMm = 58,
            description = "Fonte d'aspect sobre, à colonnes fines et lisses (production française des années 1980 à nos jours et rééditions). " +
                "Recoupe les fontes à 2 à 6 colonnes : la profondeur (≈ 3,5 cm par colonne) aide à les distinguer.",
            visualCues = listOf(
                "Colonnes fines et lisses, profil régulier", "Profondeur ≈ 3,5 cm par colonne (4 colonnes ≈ 14 cm)",
                "Peinture d'usine uniforme, bouchons modernes", "Éléments étroits (≈ 5,5 à 6 cm)",
            ),
            measureTips = sectionalTips,
            pitchRangeMm = 55.0..61.0, columnsRange = 2..6, typicalHeightsMm = listOf(360, 460, 560, 660, 760, 871, 960),
            depthByColumnsMm = mapOf(2 to 70..70, 3 to 101..101, 4 to 140..143, 6 to 215..215), frontPatterns = vertical,
            elementPowers = listOf(
                ElementPowerReference("Ideal Standard fonte 4 colonnes 93-4 IS (pas 55 mm)", 171.0, 871, 143, 4, centerDistanceMm = 800,
                    source = IDEAL_93_4),
            ),
            epoch = "Production française et rééditions, années 1980 à nos jours",
            reliability = DataReliability.ORDER_OF_MAGNITUDE,
            remarks = "Un seul modèle documenté en détail (55 mm de pas, 143 mm de profondeur, H 871, 171 W) ; profondeurs par colonne " +
                "relevées sur des rééditions (70, 101, 140–143, 215 mm). Autres modèles : ordre de grandeur.",
            sources = listOf(
                IDEAL_93_4, "https://www.espace-aubade.fr/chauffage/radiateur-chauffage-central/dune.html",
                "https://www.cherchons.com/maison/chauffages/radiateur/chappee-radiateur.html",
                "https://www.castrads.com/en/product/mercury-4-column-760mm/", CASTRADS_GRACE,
                "https://forums.futura-sciences.com/habitat-bioclimatique-isolation-chauffage/496590-marque-type-de-radiateur-ancien-fonte.html",
            ),
        ),
        RadiatorCategory(
            code = "fonte_din", title = "Fonte normalisée DIN 4703", type = RadiatorType.CAST_IRON_COLUMN,
            era = RadiatorEra.OLD, columns = null, depthRangeMm = 70..250, sectionPitchMm = 60,
            description = "Éléments en fonte lisses aux cotes normalisées (pas de 60 mm, hauteurs et profondeurs fixes), posés en Europe " +
                "de 1950 à 1980 et toujours fabriqués.",
            visualCues = listOf("Éléments lisses sans décor, pas régulier de 6 cm", "Hauteurs normalisées (28, 43, 58, 68, 98 cm)", "Souvent fixé au mur sans pieds"),
            measureTips = sectionalTips,
            pitchRangeMm = 60.0..60.0, typicalHeightsMm = listOf(280, 430, 580, 680, 980), normalizedHeightsMm = listOf(280, 430, 580, 680, 980),
            normalizedDepthsMm = listOf(70, 110, 160, 220, 250), frontPatterns = vertical,
            elementPowers = listOf(
                din(280, 250, 75.0), din(430, 110, 55.0), din(430, 160, 75.0), din(430, 220, 97.0),
                din(580, 110, 72.0), din(580, 160, 96.0), din(580, 220, 122.0),
                din(680, 110, 81.0), din(680, 160, 111.0), din(980, 160, 163.0), din(980, 220, 205.0),
            ),
            epoch = "Europe (surtout Allemagne, Benelux) 1950–1980 ; toujours fabriqué",
            reliability = DataReliability.DOCUMENTED,
            remarks = "Hauteur hors tout = entraxe des moyeux + 80 mm. Puissances à 75/65/20 °C (DIN 4703 / EN 442, fiches techniques). " +
                "Livré en blocs : 20 éléments au plus (H 280 à 680), 15 (H 980). La correspondance profondeur → nombre de colonnes " +
                "dépend du fabricant.",
            sources = listOf(
                HG_TEC_FONTE, "https://www.gussradiatoren.info/downloads/guradiatoren-technische-daten.pdf",
                "https://www.gussradiatoren.info/technik/technische-beschreibung/index.html", WATT42,
            ),
        ),
        RadiatorCategory(
            code = "fonte_decoree", title = "Fonte ornée à motifs", type = RadiatorType.CAST_IRON_COLUMN,
            era = RadiatorEra.OLD, columns = null, depthRangeMm = 140..340, sectionPitchMm = 76,
            description = "Éléments en fonte décorés de reliefs floraux ou géométriques (style 1890–1930 et rééditions), plus larges que la fonte à colonnes lisse.",
            visualCues = listOf(
                "Reliefs floraux ou géométriques sur chaque élément", "Éléments plus larges que la fonte à colonnes lisse (≈ 6,5 à 8 cm)",
                "Souvent sur pieds moulés", "Haut d'élément arrondi ou ajouré",
            ),
            measureTips = sectionalTips,
            pitchRangeMm = 66.5..78.3, pitchNote = "des fontes ornées anciennes au pas ≈ 60 mm existent aussi (non documentées)",
            columnsRange = 1..7, typicalHeightsMm = listOf(350, 470, 510, 660, 750, 768, 870, 950), frontPatterns = vertical,
            elementPowers = listOf(
                ElementPowerReference("Castrads Rococo III (pas 76,5 mm)", 139.6, 768, 256, source = "https://www.castrads.com/en/product/rococo-iii-768mm/"),
                ElementPowerReference("Castrads Emmeline III (pas 66,5 mm)", 148.0, 870, 230, source = "https://www.castrads.com/en/product/emmeline-iii-870mm/"),
                ElementPowerReference("Castrads Emmeline I (pas 66,5 mm)", 62.0, 665, source = "https://www.castrads.com/en/product/emmeline-i-665mm"),
                ElementPowerReference("Fonte 7 colonnes de style (pas 73 mm)", 117.0, 350, 340, 7, reliability = DataReliability.ORDER_OF_MAGNITUDE,
                    note = "Écart de température non indiqué",
                    source = "https://www.batiproduits.com/fiche/produits/radiateur-en-fonte-de-3-a-30-elements-de-117-wa-p69325458.html"),
                ElementPowerReference("Fonte rétro (pas ≈ 78 mm)", 144.8, 750, 250, reliability = DataReliability.ORDER_OF_MAGNITUDE, derived = true,
                    note = "Déduit des longueurs et puissances du catalogue ; écart de température non indiqué",
                    source = "https://www.batiproduits.com/fiche/produits/radiateurs-en-fonte-de-style-retro-p68894453.html"),
            ),
            epoch = "Modèles anciens 1890–1930 ; rééditions actuelles",
            reliability = DataReliability.DOCUMENTED,
            remarks = "Pas documentés sur des rééditions : 66,5 ; 73 ; 75,6 à 78,3 mm ; 78 mm déduit. Profondeur jusqu'à 340 mm pour un modèle " +
                "bas à 7 colonnes. Des fontes anciennes ornées au pas ≈ 60 mm existent aussi (non documentées ici).",
            sources = listOf(
                "https://www.castrads.com/en/product/rococo-iii-768mm/", "https://www.castrads.com/en/product/rococo-i-950mm",
                "https://www.castrads.com/en/product/rococo-i-660mm", "https://www.castrads.com/en/product/rococo-ii-510mm/",
                "https://www.castrads.com/en/product/rococo-ii-950mm/", "https://www.ukaa.com/cast-iron-radiators/rococo",
                "https://www.castrads.com/en/product/emmeline-iii-870mm/", "https://www.castrads.com/en/product/emmeline-i-665mm",
                "https://www.batiproduits.com/fiche/produits/radiateur-en-fonte-de-3-a-30-elements-de-117-wa-p69325458.html",
                "https://product-explorer.specifiedby.com/castrads/churchill-7-column-350mm?sm=1+",
                "https://www.batiproduits.com/fiche/produits/radiateurs-en-fonte-de-style-retro-p68894453.html",
                "https://www.cherchons.com/maison/chauffages/radiateur/chappee-radiateur.html",
            ),
        ),
        RadiatorCategory(
            code = "fonte_elements_plats", title = "Fonte à éléments multiples (face lisse)", type = RadiatorType.CAST_IRON_COLUMN,
            era = RadiatorEra.OLD, columns = null, depthRangeMm = 100..220, sectionPitchMm = 60,
            description = "Éléments assemblés à face avant plate ou ajourée (années 1950–1975). Le nombre de colonnes n'est pas visible : relevez la profondeur.",
            visualCues = listOf("Face avant plane ou ajourée, éléments juxtaposés", "Joints verticaux réguliers", "Nombre de colonnes non visible : relevez la profondeur"),
            measureTips = sectionalTips,
            pitchRangeMm = 55.0..66.0, frontPatterns = vertical,
            epoch = "1950–1975 (ordre de grandeur)",
            reliability = DataReliability.ORDER_OF_MAGNITUDE,
            remarks = "Aucune fiche technique trouvée : plages reprises des fontes à colonnes. Puissance : se ramener à la fonte DIN de même hauteur et profondeur.",
        ),
        RadiatorCategory(
            code = "fonte_plaques", title = "Fonte à plaques", type = RadiatorType.CAST_IRON_PLATE,
            era = RadiatorEra.OLD, columns = null, depthRangeMm = 50..120, sectionPitchMm = null,
            description = "Panneaux de fonte plats, parfois à ailettes à l'arrière. Peu d'éléments visibles.",
            visualCues = listOf("Grandes plaques plates", "Faible profondeur", "Peu ou pas de joints verticaux visibles de face"),
            measureTips = listOf("Mesurez longueur, hauteur et profondeur hors tout.", "Comptez le nombre de plaques en profondeur."),
            frontPatterns = setOf(FrontPattern.SMOOTH),
            epoch = "Ancien (ordre de grandeur)",
            reliability = DataReliability.ORDER_OF_MAGNITUDE,
            remarks = "Aucune donnée fabricant trouvée. Pas de comptage d'éléments possible de face.",
        ),
        RadiatorCategory(
            code = "acier_colonnes", title = "Acier embouti à colonnes", type = RadiatorType.STEEL_COLUMN,
            era = RadiatorEra.OLD, columns = null, depthRangeMm = 110..250, sectionPitchMm = 50,
            description = "Éléments en tôle d'acier emboutie soudés en blocs, aspect proche de la fonte mais plus léger et à parois minces.",
            visualCues = listOf(
                "Éléments en tôle emboutie soudés en blocs (pas de raccords vissés visibles)", "Sonne creux au tapotement, parois minces",
                "Pas d'élément 5 cm, plus fin que la fonte", "Soudures visibles sur les collecteurs",
            ),
            measureTips = sectionalTips,
            pitchRangeMm = 45.0..50.0, pitchNote = "pas documenté : 50 mm ; modèles anciens non documentés",
            typicalHeightsMm = listOf(300, 450, 600, 1000), normalizedHeightsMm = listOf(300, 450, 600, 1000),
            normalizedDepthsMm = listOf(110, 160, 220, 250), frontPatterns = vertical,
            elementPowers = listOf(
                steelDin(300, 250, 62.0), steelDin(450, 160, 59.0), steelDin(450, 220, 76.0),
                steelDin(600, 110, 54.0), steelDin(600, 160, 73.0), steelDin(600, 220, 97.0),
                steelDin(1000, 110, 86.0), steelDin(1000, 160, 115.0), steelDin(1000, 220, 150.0),
            ),
            epoch = "1950–1990 ; toujours fabriqué",
            reliability = DataReliability.DOCUMENTED,
            remarks = "Pas de 50 mm documenté (3 éléments = 15 cm) ; 45 mm : ordre de grandeur pour des modèles anciens non documentés. " +
                "Hauteur hors tout = entraxe + 100 mm.",
            sources = listOf(
                "https://www.stahlradiatoren.de/downloads/technischebescheibungstahlradiatoren.pdf",
                "https://www.stahlradiatoren.de/printable/technik/technische-beschreibung/index.html",
                "https://gerabad.de/ASW/ASW-Stahlradiator-grundiert-3-Glieder-Nabenabstand-500-mm-HG-TEC-15-60-11-cm/AW113499",
                "https://gerabad.de/ASW/ASW-Stahlradiator-pulverbeschichtet-4-Glieder-Nabenabstand-500-mm-HG-TEC-20-60-16-cm/AW153358",
                ENERGIE_EXPERTEN, "http://www.kolboske.de/joomla/files/mat/hz/auslegung_glieder_und_platten_heizkoerper_txt.pdf",
                WATT42, OPENCLASSROOMS,
            ),
        ),
        RadiatorCategory(
            code = "acier_lamelles", title = "Acier à lamelles plissées", type = RadiatorType.STEEL_COLUMN,
            era = RadiatorEra.UNKNOWN, columns = null, depthRangeMm = 65..120, sectionPitchMm = 31,
            description = "Façade en tôle d'acier plissée en plis verticaux serrés (≈ 3 cm), vendue au nombre d'éléments (type « Lamella »).",
            visualCues = listOf("Façade en tôle plissée : plis verticaux serrés (≈ 3 cm)", "Vendu au nombre d'éléments (6 à 70)", "Faible profondeur (6,5 / 9,5 / 12 cm)"),
            measureTips = sectionalTips,
            pitchRangeMm = 30.6..31.0, heightRangeMm = 250..1000, heightNote = "plage relevée, sans hauteurs fixes documentées",
            normalizedDepthsMm = listOf(65, 95, 120), frontPatterns = vertical,
            epoch = "Années 1960 à nos jours",
            reliability = DataReliability.ORDER_OF_MAGNITUDE,
            remarks = "Pas déduit d'exemples d'utilisateurs (550 mm pour 18 éléments ≈ 30,6 ; 310 mm pour 10 ≈ 31,0), non publié par le " +
                "fabricant. Hauteurs de 250 à 1000 mm. Aucune puissance par élément retenue.",
            sources = listOf(
                "https://www.richardson.fr/files/richardson/technical_document/1f0/7018892_t238m8.pdf",
                "https://www.richardson.fr/files/richardson/technical_document/6cc/603863_t5323a.pdf",
                "https://forums.futura-sciences.com/bricolage-decoration/950617-plomberie-remplacer-radiateur-lamella-ancien-entraxe.html",
            ),
        ),
        RadiatorCategory(
            code = "panneaux_acier", title = "Panneaux acier (types 10 à 33)", type = RadiatorType.STEEL_PANEL,
            // Type de panneau non présumé : il se lit vu de dessus et change la puissance du simple au double.
            era = RadiatorEra.MODERN, columns = null, panelType = null, depthRangeMm = 42..160, sectionPitchMm = null,
            description = "Un à trois panneaux d'acier, avec ou sans ailettes. Le type (10, 11, 20, 21, 22, 33) se lit vu de dessus.",
            visualCues = listOf(
                "Façade à cannelures verticales fines (pas ≈ 33 mm) ou façade lisse (modèles « plan »)",
                "Grille sur le dessus (types à ailettes 11, 21, 22, 33)", "Ailettes visibles entre les panneaux vu de dessus",
                "Type lisible vu de dessus : nombre de panneaux et de rangées d'ailettes",
            ),
            measureTips = listOf("Comptez les panneaux et rangées d'ailettes vus de dessus.", "Mesurez longueur et hauteur hors tout."),
            flutePitchRangeMm = 33.3..50.0, typicalHeightsMm = listOf(200, 300, 400, 450, 500, 550, 600, 900, 950),
            frontPatterns = setOf(FrontPattern.VERTICAL_FLUTES, FrontPattern.SMOOTH),
            epoch = "Années 1960 à nos jours",
            reliability = DataReliability.DOCUMENTED,
            remarks = "Les cannelures (33 à 50 mm) ressemblent à des éléments : un « comptage » sur un panneau donne le nombre de cannelures, " +
                "sans valeur pour la puissance. Puissance par mètre à H 600 et ΔT 50 K (catalogues) : type 10 ≈ 600 W, 11 ≈ 1020, " +
                "20 ≈ 1030, 21 ≈ 1340, 22 ≈ 1710, 33 ≈ 2360. Profondeurs : type 10 ≈ 42–61 mm, 11 ≈ 60–63, 21 ≈ 70–80, 22 ≈ 100–110, 33 ≈ 150–160.",
            sources = listOf(
                "https://onninen.fi/en/purmo-radiator-purmo-compact-c11-600-1200/p/AAO786",
                "https://onnshop.onninen.fi/en/purmo-radiator-purmo-compact-c21-600-1000/p/AAO819",
                "https://onninen.fi/en/purmo-radiator-purmo-compact-c22-600-1000/p/AAO853",
                "https://www.termix.ro/en/cumpara/radiator-compact-c33-600-700-5076",
                "https://assets.heizung-billiger.de/documents/kermi-profil-k-v-vplus-hygiene-technik.pdf",
                "https://www.heizungsdiscount24.de/marken/kermi-kompaktheizkoerper-heizkoerper-profil-k-typ-10-600x1000-mm-h-x-l.html",
                "https://www.purmo.com/au/products/panels/purmo-plan-compact.htm",
                "https://demirdokum.com.tr/products/demirdokum-panel-radiator-33-mm-14784.html",
                "https://www.bimobject.com/en/purmo/product/radson-integra-parada-panel-radiators",
            ),
        ),
        RadiatorCategory(
            code = "tubulaire", title = "Tubulaire acier multicolonnes", type = RadiatorType.STEEL_TUBULAR,
            era = RadiatorEra.MODERN, columns = 3, depthRangeMm = 62..225, sectionPitchMm = 46,
            description = "Tubes ronds verticaux assemblés (type Charleston), de 2 à 6 colonnes. Le nombre de tubes en profondeur donne le nombre de colonnes.",
            visualCues = listOf(
                "Tubes ronds verticaux réguliers (Ø ≈ 25 mm)", "Pas d'élément ≈ 45 à 50 mm", "Nombre de tubes en profondeur = nombre de colonnes",
                "Existe en très grande hauteur (jusqu'à 3 m), posé verticalement",
            ),
            measureTips = sectionalTips,
            pitchRangeMm = 45.0..50.0, columnsRange = 2..6, typicalHeightsMm = listOf(155, 500, 600, 656, 756, 1200, 1800, 2000, 3000),
            depthByColumnsMm = mapOf(2 to 62..63, 3 to 100..105, 4 to 136..145, 5 to 173..185, 6 to 210..225), frontPatterns = vertical,
            elementPowers = listOf(
                tube("Zehnder Charleston 2050", 2, 500, 38.4, MEGABAD_50),
                tube("Zehnder Charleston 2060", 2, 600, 45.4, MEGABAD_60, depthMm = 62),
                tube("Zehnder Charleston 3050", 3, 500, 51.6, MEGABAD_50),
                tube("Zehnder Charleston 3060", 3, 600, 60.9, MEGABAD_60, depthMm = 100),
                tube("Zehnder Charleston 4060", 4, 600, 79.8, "https://produkte.zehnder-systems.de/de/produkt/zehnder-charleston", depthMm = 136, derived = false),
                tube("Zehnder Charleston 2180", 2, 1800, 124.0, "https://www.megabad.com/en/zehnder-wohnraumheizkoerper-charleston-bauhoehe-180-cm-a-1171155.htm", depthMm = 62),
                tube("Radson Delta V 2180", 2, 1800, 132.2, RADSON_DELTA, depthMm = 63),
                tube("Radson Delta V 2200", 2, 2000, 146.8, RADSON_DELTA, depthMm = 63),
                tube("Radson Delta V 3180", 3, 1800, 178.0, RADSON_DELTA, depthMm = 101),
                tube("Radson Delta V 3200", 3, 2000, 197.1, RADSON_DELTA, depthMm = 101),
            ),
            epoch = "Années 1930 à nos jours ; très répandu en rénovation",
            reliability = DataReliability.DOCUMENTED,
            remarks = "Pas documentés : 45, 46 et 50 mm selon les fabricants. Puissances par élément le plus souvent calculées (puissance " +
                "totale ÷ nombre d'éléments). Rapport pas / hauteur très variable (modèles de 155 mm à 3 m de haut).",
            sources = listOf(
                "https://officinaidraulica.it/documenti/schede/ETV3204352.pdf",
                "https://www.international.zehnder-systems.com/products-and-systems/decorative-radiators/zehnder-charleston",
                "https://produkte.zehnder-systems.de/de/produkt/zehnder-charleston", MEGABAD_50, MEGABAD_60,
                "https://www.megabad.com/en/zehnder-wohnraumheizkoerper-charleston-bauhoehe-180-cm-a-1171155.htm",
                "https://www.vogelundnoot.com/static_files/lv/media/downloads/standard_laserline_tube_german_version_technikal_information.pdf",
                "https://www.c-o-k.ru/library/instructions/arbonia/radiatory-konvektory/28947/107241.pdf",
                "https://www.purmo.com/da-dk/produkter/14358/delta", RADSON_DELTA,
            ),
        ),
        RadiatorCategory(
            code = "aluminium", title = "Aluminium à éléments", type = RadiatorType.ALUMINIUM_SECTIONAL,
            era = RadiatorEra.MODERN, columns = null, depthRangeMm = 80..100, sectionPitchMm = 80,
            description = "Éléments en aluminium injecté à façade plate et ailettes latérales. Léger et réactif.",
            visualCues = listOf("Façade plate par élément avec fente centrale ou ailettes", "Ailettes verticales visibles de côté", "Pas d'élément 8 cm", "Léger, réactif"),
            measureTips = sectionalTips,
            pitchRangeMm = 80.0..80.0, typicalHeightsMm = listOf(432, 582, 682, 782, 882),
            heightNote = "hauteurs extrapolées (entraxe + 82 mm) d'après un seul modèle documenté", frontPatterns = vertical,
            elementPowers = listOf(calidor(350, 89.9), calidor(500, 123.8), calidor(600, 143.6), calidor(700, 161.3), calidor(800, 177.7)),
            epoch = "Années 1980 à nos jours",
            reliability = DataReliability.DOCUMENTED,
            remarks = "Pas de 80 mm documenté. Hauteurs ≈ entraxe + 82 mm, déduites d'un seul modèle (ordre de grandeur).",
            sources = listOf(
                FONDITAL_B4, "https://vencon.ua/ua/products/global-radiatori-iseo-50080r", "https://pdf.archiexpo.com/pdf/global-radiatori/iseo/519-393004.html",
                "https://www.manomano.fr/p/radiateur-aluminium-tropical-h-1600-58966733",
                "https://media.castorama.fr/is/content/Castorama/8013428316815_prd_FR_CFpdf", OPENCLASSROOMS,
            ),
        ),
        RadiatorCategory(
            code = "seche_serviettes", title = "Sèche-serviettes à eau", type = RadiatorType.TOWEL_RAIL,
            era = RadiatorEra.MODERN, columns = null, depthRangeMm = 83..150, sectionPitchMm = null,
            description = "Barreaux horizontaux entre deux collecteurs verticaux.",
            visualCues = listOf(
                "Barreaux horizontaux ronds (Ø 22 à 25 mm) entre deux collecteurs verticaux (Ø ≈ 38 mm)", "Installé en salle de bains",
                "Groupes de barreaux séparés par des espaces pour serviettes",
            ),
            measureTips = listOf("Mesurez la largeur et la hauteur hors tout."),
            typicalHeightsMm = listOf(450, 1000, 1146), heightNote = "hauteurs relevées sur quelques modèles",
            frontPatterns = setOf(FrontPattern.HORIZONTAL_BARS),
            epoch = "Années 1990 à nos jours",
            reliability = DataReliability.ORDER_OF_MAGNITUDE,
            remarks = "Espacement des barreaux non normalisé. Hauteurs et profondeurs relevées sur quelques modèles (liste non exhaustive).",
            sources = listOf(
                "https://www.batiproduits.com/amp/fiche/produits/seche-serviettes-vertical-a-eau-chaude-p69065173.html",
                "https://www.heatandplumb.com/media/heatwave/datasheets/file-517230.pdf",
            ),
        ),
        RadiatorCategory(
            code = "convecteur", title = "Convecteur à eau", type = RadiatorType.CONVECTOR,
            era = RadiatorEra.UNKNOWN, columns = null, depthRangeMm = null, sectionPitchMm = null,
            description = "Échangeur à ailettes dans un habillage. Puissance très dépendante du modèle : documentation indispensable.",
            visualCues = listOf("Habillage fermé avec grille de sortie d'air", "Échangeur à ailettes non visible de face"),
            measureTips = listOf("Relevez la référence sur la plaque ou dans l'habillage."),
            frontPatterns = setOf(FrontPattern.SMOOTH),
            reliability = DataReliability.ORDER_OF_MAGNITUDE,
            remarks = "Aucune donnée générique retenue.",
        ),
        RadiatorCategory(
            code = "decoratif", title = "Décoratif / design", type = RadiatorType.DECORATIVE,
            era = RadiatorEra.MODERN, columns = null, depthRangeMm = null, sectionPitchMm = null,
            description = "Formes spécifiques : seule la documentation du fabricant permet de connaître la puissance.",
            visualCues = listOf("Forme non standard", "Tubes minces très serrés (≈ 19 mm) ou plaques design"),
            measureTips = listOf("Recherchez la marque et la référence (plaque, notice)."),
            frontPatterns = FrontPattern.entries.toSet(),
            epoch = "Années 1990 à nos jours",
            reliability = DataReliability.ORDER_OF_MAGNITUDE,
            remarks = "Motif et dimensions propres à chaque modèle (exemple : tubes de Ø 12 mm espacés de 19 mm).",
            sources = listOf("https://www.batiproduits.com/amp/fiche/produits/radiateur-a-tubes-minces-jusqu-a-3-metres-de-lo-p69065113.html"),
        ),
    )

    fun find(code: String?): RadiatorCategory? = categories.firstOrNull { it.code == code }

    /**
     * Catégories plausibles à partir des seules dimensions mesurées (aide à l'identification).
     * Retourne toutes les catégories, triées par cohérence décroissante ([RadiatorMatcher]) ; l'utilisateur confirme.
     * Sans aucune donnée, l'ordre de la bibliothèque est conservé.
     */
    fun plausibleCategories(depthMm: Double?, columns: Int?, type: RadiatorType?): List<RadiatorCategory> =
        RadiatorMatcher.match(
            RadiatorMeasurements(
                depthMm = depthMm?.takeIf { it > 0 && it.isFinite() }?.let { DimensionEstimate(it, TAPE_UNCERTAINTY_MM) },
                columns = columns?.takeIf { it > 0 },
                type = type?.takeIf { it != RadiatorType.UNKNOWN },
            ),
            categories,
        ).matches.map { it.category }
}
