package fr.thermohabitat.thermal.library

import fr.thermohabitat.thermal.model.PanelType
import fr.thermohabitat.thermal.model.RadiatorEra
import fr.thermohabitat.thermal.model.RadiatorType

/**
 * Catégorie de la bibliothèque illustrée d'identification des radiateurs.
 *
 * @property columns nombre de colonnes caractéristique (radiateurs à éléments), sinon null
 * @property depthRangeMm profondeur hors tout usuelle d'un élément
 * @property visualCues indices visuels permettant de reconnaître la catégorie sur place
 * @property measureTips mesures à relever pour l'estimation de puissance
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
)

/** Bibliothèque illustrée (les illustrations sont dessinées par l'application à partir de ces données). */
object RadiatorLibrary {

    private val sectionalTips = listOf(
        "Comptez les éléments sur le dessus du radiateur.",
        "Mesurez la hauteur hors tout (sans les pieds) et la profondeur du mur vers l'avant de l'élément.",
        "Relevez l'entraxe des raccordements si possible.",
    )

    val categories: List<RadiatorCategory> = listOf(
        RadiatorCategory(
            code = "fonte_2col", title = "Fonte 2 colonnes", type = RadiatorType.CAST_IRON_COLUMN,
            era = RadiatorEra.OLD, columns = 2, depthRangeMm = 80..115, sectionPitchMm = 60,
            description = "Éléments étroits composés de deux tubes verticaux reliés en haut et en bas. Très répandus de 1900 à 1960, souvent décorés.",
            visualCues = listOf("Vu de dessus : 2 tubes par élément", "Profondeur faible (≈ 10 cm)", "Surface souvent ornée ou à motifs"),
            measureTips = sectionalTips,
        ),
        RadiatorCategory(
            code = "fonte_3col", title = "Fonte 3 colonnes", type = RadiatorType.CAST_IRON_COLUMN,
            era = RadiatorEra.OLD, columns = 3, depthRangeMm = 120..165, sectionPitchMm = 60,
            description = "Trois tubes par élément. Profondeur moyenne ; très courant dans les maisons des années 1920–1960.",
            visualCues = listOf("Vu de dessus : 3 tubes alignés vers l'avant", "Profondeur ≈ 13 à 16 cm"),
            measureTips = sectionalTips,
        ),
        RadiatorCategory(
            code = "fonte_4col", title = "Fonte 4 colonnes", type = RadiatorType.CAST_IRON_COLUMN,
            era = RadiatorEra.OLD, columns = 4, depthRangeMm = 170..225, sectionPitchMm = 60,
            description = "Quatre tubes par élément, radiateur profond et lourd. Forte inertie.",
            visualCues = listOf("Vu de dessus : 4 tubes", "Profondeur ≈ 18 à 22 cm"),
            measureTips = sectionalTips,
        ),
        RadiatorCategory(
            code = "fonte_5_6col", title = "Fonte 5 ou 6 colonnes", type = RadiatorType.CAST_IRON_COLUMN,
            era = RadiatorEra.OLD, columns = 5, depthRangeMm = 230..300, sectionPitchMm = 60,
            description = "Radiateurs très profonds de grands volumes (halls, salles de séjour anciennes).",
            visualCues = listOf("Vu de dessus : 5 ou 6 tubes", "Profondeur supérieure à 23 cm"),
            measureTips = sectionalTips,
        ),
        RadiatorCategory(
            code = "fonte_elements_plats", title = "Fonte à éléments multiples (face lisse)", type = RadiatorType.CAST_IRON_COLUMN,
            era = RadiatorEra.OLD, columns = null, depthRangeMm = 100..220, sectionPitchMm = 60,
            description = "Éléments assemblés à face avant plate ou ajourée (années 1950–1975). Le nombre de colonnes n'est pas visible : relevez la profondeur.",
            visualCues = listOf("Face avant plane, éléments juxtaposés", "Joints verticaux réguliers"),
            measureTips = sectionalTips,
        ),
        RadiatorCategory(
            code = "fonte_plaques", title = "Fonte à plaques", type = RadiatorType.CAST_IRON_PLATE,
            era = RadiatorEra.OLD, columns = null, depthRangeMm = 50..120, sectionPitchMm = null,
            description = "Panneaux de fonte plats, parfois à ailettes à l'arrière. Peu d'éléments visibles.",
            visualCues = listOf("Grandes plaques plates", "Faible profondeur"),
            measureTips = listOf("Mesurez longueur, hauteur et profondeur hors tout.", "Comptez le nombre de plaques en profondeur."),
        ),
        RadiatorCategory(
            code = "acier_colonnes", title = "Acier embouti à colonnes", type = RadiatorType.STEEL_COLUMN,
            era = RadiatorEra.OLD, columns = null, depthRangeMm = 100..220, sectionPitchMm = 45,
            description = "Éléments en tôle d'acier emboutie soudés, aspect proche de la fonte mais plus léger et à parois minces.",
            visualCues = listOf("Éléments plus fins que la fonte", "Sonne creux au tapotement", "Soudures visibles"),
            measureTips = sectionalTips,
        ),
        RadiatorCategory(
            code = "panneaux_acier", title = "Panneaux acier (types 10 à 33)", type = RadiatorType.STEEL_PANEL,
            // Type de panneau non présumé : il se lit vu de dessus et change la puissance du simple au double.
            era = RadiatorEra.MODERN, columns = null, panelType = null, depthRangeMm = 45..160, sectionPitchMm = null,
            description = "Un à trois panneaux d'acier, avec ou sans ailettes. Le type (11, 21, 22, 33) se lit vu de dessus.",
            visualCues = listOf("Façade lisse ou à cannelures horizontales", "Grille sur le dessus", "Ailettes visibles entre les panneaux"),
            measureTips = listOf("Comptez les panneaux et rangées d'ailettes vus de dessus.", "Mesurez longueur et hauteur hors tout."),
        ),
        RadiatorCategory(
            code = "tubulaire", title = "Tubulaire acier multicolonnes", type = RadiatorType.STEEL_TUBULAR,
            era = RadiatorEra.MODERN, columns = 3, depthRangeMm = 60..230, sectionPitchMm = 46,
            description = "Tubes ronds verticaux assemblés (type Charleston). Le nombre de tubes en profondeur donne le nombre de colonnes.",
            visualCues = listOf("Tubes ronds réguliers de ≈ 25 mm", "Pas d'élément ≈ 46 mm"),
            measureTips = sectionalTips,
        ),
        RadiatorCategory(
            code = "aluminium", title = "Aluminium à éléments", type = RadiatorType.ALUMINIUM_SECTIONAL,
            era = RadiatorEra.MODERN, columns = null, depthRangeMm = 70..100, sectionPitchMm = 80,
            description = "Éléments en aluminium injecté à façade plate et ailettes latérales. Léger et réactif.",
            visualCues = listOf("Façade plate, ailettes visibles de côté", "Pas d'élément ≈ 80 mm"),
            measureTips = sectionalTips,
        ),
        RadiatorCategory(
            code = "seche_serviettes", title = "Sèche-serviettes à eau", type = RadiatorType.TOWEL_RAIL,
            era = RadiatorEra.MODERN, columns = null, depthRangeMm = 30..120, sectionPitchMm = null,
            description = "Barreaux horizontaux entre deux collecteurs verticaux.",
            visualCues = listOf("Barreaux horizontaux", "Installé en salle de bains"),
            measureTips = listOf("Mesurez la largeur et la hauteur hors tout."),
        ),
        RadiatorCategory(
            code = "convecteur", title = "Convecteur à eau", type = RadiatorType.CONVECTOR,
            era = RadiatorEra.UNKNOWN, columns = null, depthRangeMm = null, sectionPitchMm = null,
            description = "Échangeur à ailettes dans un habillage. Puissance très dépendante du modèle : documentation indispensable.",
            visualCues = listOf("Habillage fermé avec grille de sortie d'air"),
            measureTips = listOf("Relevez la référence sur la plaque ou dans l'habillage."),
        ),
        RadiatorCategory(
            code = "decoratif", title = "Décoratif / design", type = RadiatorType.DECORATIVE,
            era = RadiatorEra.MODERN, columns = null, depthRangeMm = null, sectionPitchMm = null,
            description = "Formes spécifiques : seule la documentation du fabricant permet de connaître la puissance.",
            visualCues = listOf("Forme non standard"),
            measureTips = listOf("Recherchez la marque et la référence (plaque, notice)."),
        ),
    )

    fun find(code: String?): RadiatorCategory? = categories.firstOrNull { it.code == code }

    /**
     * Catégories plausibles à partir des seules dimensions mesurées (aide à l'identification).
     * Retourne les catégories triées par cohérence décroissante ; l'utilisateur confirme.
     */
    fun plausibleCategories(depthMm: Double?, columns: Int?, type: RadiatorType?): List<RadiatorCategory> {
        return categories
            .map { cat -> cat to score(cat, depthMm, columns, type) }
            .filter { it.second > 0.0 }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    private fun score(cat: RadiatorCategory, depthMm: Double?, columns: Int?, type: RadiatorType?): Double {
        var s = 1.0
        if (type != null && type != RadiatorType.UNKNOWN) s *= if (cat.type == type) 3.0 else 0.2
        if (columns != null && cat.columns != null) {
            s *= when {
                cat.columns == columns -> 3.0
                cat.code == "fonte_5_6col" && columns >= 5 -> 3.0
                else -> 0.3
            }
        }
        if (depthMm != null && cat.depthRangeMm != null) {
            val r = cat.depthRangeMm
            s *= when {
                depthMm >= r.first && depthMm <= r.last -> 2.0
                depthMm >= r.first - 20 && depthMm <= r.last + 20 -> 1.0
                else -> 0.2
            }
        }
        return s
    }
}
