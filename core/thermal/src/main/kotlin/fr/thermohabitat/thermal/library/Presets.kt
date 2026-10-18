package fr.thermohabitat.thermal.library

import fr.thermohabitat.thermal.model.Layer
import fr.thermohabitat.thermal.model.OpeningKind
import fr.thermohabitat.thermal.model.SurfaceKind
import fr.thermohabitat.thermal.model.ValueOrigin

/** Modèle de baie : coefficient Uw, facteur solaire et fraction de cadre indicatifs. */
data class OpeningPreset(
    val code: String,
    val label: String,
    val kind: OpeningKind,
    val uValue: Double,
    val gValue: Double,
    val frameFraction: Double,
)

/**
 * Valeurs indicatives de baies (ordre de grandeur Th-U / NF EN ISO 10077).
 * Une valeur déclarée (fiche fabricant, Acotherm) doit être préférée lorsqu'elle existe.
 */
object OpeningPresets {
    val windows: List<OpeningPreset> = listOf(
        OpeningPreset("sv_bois", "Simple vitrage – menuiserie bois", OpeningKind.WINDOW, 4.8, 0.85, 0.30),
        OpeningPreset("sv_metal", "Simple vitrage – menuiserie métallique", OpeningKind.WINDOW, 5.8, 0.85, 0.20),
        OpeningPreset("dv_ancien_bois", "Double vitrage ancien (4/6/4) – bois", OpeningKind.WINDOW, 3.0, 0.75, 0.30),
        OpeningPreset("dv_ancien_pvc", "Double vitrage ancien (4/6/4) – PVC", OpeningKind.WINDOW, 2.9, 0.75, 0.30),
        OpeningPreset("dv_ancien_alu", "Double vitrage ancien – alu sans rupture de pont thermique", OpeningKind.WINDOW, 3.9, 0.75, 0.20),
        OpeningPreset("dv_16_air", "Double vitrage 4/16/4 air – bois ou PVC", OpeningKind.WINDOW, 2.7, 0.75, 0.30),
        OpeningPreset("dv_fe_argon", "Double vitrage faiblement émissif argon – bois ou PVC", OpeningKind.WINDOW, 1.4, 0.60, 0.30),
        OpeningPreset("dv_fe_alu", "Double vitrage faiblement émissif – alu à rupture de pont thermique", OpeningKind.WINDOW, 1.7, 0.60, 0.20),
        OpeningPreset("tv", "Triple vitrage", OpeningKind.WINDOW, 0.9, 0.50, 0.30),
        OpeningPreset("toit_ancien", "Fenêtre de toit ancienne", OpeningKind.ROOF_WINDOW, 2.8, 0.70, 0.30),
        OpeningPreset("toit_recent", "Fenêtre de toit récente", OpeningKind.ROOF_WINDOW, 1.4, 0.55, 0.30),
    )

    val doors: List<OpeningPreset> = listOf(
        OpeningPreset("porte_bois_pleine", "Porte bois pleine", OpeningKind.DOOR, 3.5, 0.0, 1.0),
        OpeningPreset("porte_bois_vitree", "Porte bois avec vitrage simple", OpeningKind.DOOR, 4.5, 0.0, 1.0),
        OpeningPreset("porte_metal", "Porte métallique non isolée", OpeningKind.DOOR, 5.8, 0.0, 1.0),
        OpeningPreset("porte_isolante", "Porte isolante récente", OpeningKind.DOOR, 1.5, 0.0, 1.0),
        OpeningPreset("porte_interieure", "Porte intérieure (vers local non chauffé)", OpeningKind.DOOR, 2.0, 0.0, 1.0),
    )

    val all: List<OpeningPreset> = windows + doors

    fun find(code: String?): OpeningPreset? = all.firstOrNull { it.code == code }
}

/** Coefficient b de réduction de température d'un local non chauffé. */
data class UnheatedSpacePreset(val code: String, val label: String, val b: Double)

/**
 * Coefficients b indicatifs pour les locaux non chauffés (EN 12831:2003, tableau 6).
 * b = (θint − θu) / (θint − θe) ; b = 1 : le local est à la température extérieure.
 */
object UnheatedSpacePresets {
    val all: List<UnheatedSpacePreset> = listOf(
        UnheatedSpacePreset("une_paroi_ext", "Local avec 1 seule paroi extérieure", 0.4),
        UnheatedSpacePreset("deux_parois_sans_porte", "Local à ≥ 2 parois extérieures, sans porte extérieure", 0.5),
        UnheatedSpacePreset("deux_parois_avec_porte", "Local à ≥ 2 parois extérieures avec porte(s) extérieure(s) (garage, hall, cellier)", 0.6),
        UnheatedSpacePreset("trois_parois", "Local à 3 parois extérieures (ex. cage d'escalier extérieure)", 0.8),
        UnheatedSpacePreset("sous_sol_aveugle", "Sous-sol sans fenêtre ni porte extérieure", 0.5),
        UnheatedSpacePreset("sous_sol_ouvert", "Sous-sol avec fenêtres ou portes extérieures", 0.8),
        UnheatedSpacePreset("combles_tres_ventiles", "Combles fortement ventilés (tuiles sans écran, non isolés)", 1.0),
        UnheatedSpacePreset("combles_non_isoles", "Autres combles à toiture non isolée", 0.9),
        UnheatedSpacePreset("combles_toiture_isolee", "Combles à toiture isolée", 0.7),
        UnheatedSpacePreset("vide_sanitaire", "Vide sanitaire", 0.8),
        UnheatedSpacePreset("circulation_interieure", "Circulation intérieure sans paroi extérieure", 0.0),
        UnheatedSpacePreset("librement_ventile", "Espace librement ventilé", 1.0),
    )

    /** Valeur par défaut prudente lorsque le local n'est pas qualifié. */
    const val DEFAULT_B = 0.8

    fun find(code: String?): UnheatedSpacePreset? = all.firstOrNull { it.code == code }
}

/** Composition type de paroi (couches de l'intérieur vers l'extérieur). */
data class CompositionTemplate(
    val code: String,
    val label: String,
    val kind: SurfaceKind,
    val layers: List<Pair<String, Double>>,
) {
    fun toLayers(origin: ValueOrigin = ValueOrigin.ESTIMATED): List<Layer> =
        layers.map { (materialCode, thicknessM) ->
            Layer(material = MaterialLibrary.byCode(materialCode), thicknessM = thicknessM, origin = origin)
        }
}

/** Compositions usuelles proposées comme point de départ modifiable. */
object CompositionTemplates {
    val all: List<CompositionTemplate> = listOf(
        // Murs
        CompositionTemplate("pise_50", "Pisé 50 cm enduit chaux (non isolé)", SurfaceKind.WALL,
            listOf("enduit_chaux" to 0.02, "pise" to 0.50, "enduit_chaux" to 0.02)),
        CompositionTemplate("pise_50_lv16", "Pisé 50 cm + doublage laine de verre 16 cm + BA13", SurfaceKind.WALL,
            listOf("ba13" to 0.0125, "laine_verre" to 0.16, "pise" to 0.50, "enduit_chaux" to 0.02)),
        CompositionTemplate("pise_50_fdb10", "Pisé 50 cm + correction fibre de bois 10 cm + enduit terre", SurfaceKind.WALL,
            listOf("enduit_terre" to 0.015, "fibre_bois_rigide" to 0.10, "pise" to 0.50, "enduit_chaux" to 0.02)),
        CompositionTemplate("pierre_50", "Pierre 50 cm enduite (non isolée)", SurfaceKind.WALL,
            listOf("enduit_platre" to 0.015, "pierre_moyenne" to 0.50, "enduit_chaux" to 0.02)),
        CompositionTemplate("brique_pleine_22", "Brique pleine 22 cm (non isolée)", SurfaceKind.WALL,
            listOf("enduit_platre" to 0.015, "brique_pleine" to 0.22, "enduit_ciment" to 0.015)),
        CompositionTemplate("parpaing_20", "Parpaing 20 cm (non isolé)", SurfaceKind.WALL,
            listOf("enduit_platre" to 0.01, "parpaing_creux" to 0.20, "enduit_ciment" to 0.015)),
        CompositionTemplate("parpaing_20_pse8", "Parpaing 20 cm + doublage PSE 8 cm", SurfaceKind.WALL,
            listOf("ba13" to 0.01, "pse" to 0.08, "parpaing_creux" to 0.20, "enduit_ciment" to 0.015)),
        CompositionTemplate("cloison_ba13", "Cloison plaques de plâtre 72 mm", SurfaceKind.WALL,
            listOf("ba13" to 0.0125, "lame_air" to 0.047, "ba13" to 0.0125)),
        CompositionTemplate("cloison_brique", "Cloison brique plâtrière 5 cm enduite", SurfaceKind.WALL,
            listOf("enduit_platre" to 0.01, "brique_creuse" to 0.05, "enduit_platre" to 0.01)),
        // Planchers bas
        CompositionTemplate("dalle_terre_plein", "Dalle béton sur terre-plein non isolée", SurfaceKind.FLOOR,
            listOf("carrelage" to 0.01, "chape_ciment" to 0.05, "beton_plein" to 0.12)),
        CompositionTemplate("plancher_bois_vs", "Plancher bois sur solives (vide sanitaire / cave)", SurfaceKind.FLOOR,
            listOf("bois_feuillus" to 0.022, "lame_air" to 0.15, "bois_resineux" to 0.02)),
        CompositionTemplate("tomettes_terre_plein", "Tomettes sur terre battue / remblai", SurfaceKind.FLOOR,
            listOf("tomettes" to 0.02, "chape_ciment" to 0.03, "remblai" to 0.20)),
        // Plafonds / toitures
        CompositionTemplate("plafond_platre_non_isole", "Plafond plâtre sous combles (non isolé)", SurfaceKind.CEILING,
            listOf("enduit_platre" to 0.02, "bois_resineux" to 0.022)),
        CompositionTemplate("plafond_lv30", "Plafond BA13 + laine de verre 30 cm (combles perdus)", SurfaceKind.CEILING,
            listOf("ba13" to 0.0125, "laine_verre" to 0.30)),
        CompositionTemplate("plancher_intermediaire", "Plancher intermédiaire bois", SurfaceKind.CEILING,
            listOf("enduit_platre" to 0.02, "lame_air" to 0.15, "bois_feuillus" to 0.022)),
        CompositionTemplate("rampant_lv20", "Rampant BA13 + laine de verre 20 cm", SurfaceKind.ROOF,
            listOf("ba13" to 0.0125, "laine_verre" to 0.20)),
    )

    fun find(code: String): CompositionTemplate? = all.firstOrNull { it.code == code }
}
