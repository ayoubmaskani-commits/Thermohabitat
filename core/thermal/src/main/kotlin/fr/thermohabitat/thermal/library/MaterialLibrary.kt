package fr.thermohabitat.thermal.library

import fr.thermohabitat.thermal.model.Material
import fr.thermohabitat.thermal.model.MaterialCategory
import fr.thermohabitat.thermal.model.MaterialCategory.AIR
import fr.thermohabitat.thermal.model.MaterialCategory.BOARD
import fr.thermohabitat.thermal.model.MaterialCategory.CONCRETE
import fr.thermohabitat.thermal.model.MaterialCategory.EARTH
import fr.thermohabitat.thermal.model.MaterialCategory.FLOOR_FINISH
import fr.thermohabitat.thermal.model.MaterialCategory.INSULATION
import fr.thermohabitat.thermal.model.MaterialCategory.MASONRY
import fr.thermohabitat.thermal.model.MaterialCategory.OTHER
import fr.thermohabitat.thermal.model.MaterialCategory.PLASTER_RENDER
import fr.thermohabitat.thermal.model.MaterialCategory.STONE
import fr.thermohabitat.thermal.model.MaterialCategory.WOOD
import fr.thermohabitat.thermal.model.ValueOrigin

/**
 * Bibliothèque de matériaux intégrée.
 *
 * Les valeurs sont des valeurs utiles usuelles issues de la NF EN ISO 10456 (tableau 3),
 * des Règles Th-U (fascicule Matériaux) et, pour les matériaux en terre crue, de la littérature
 * technique. Les matériaux anciens présentent une forte dispersion (teneur en eau, densité) :
 * la plage [Material.lambdaMin]–[Material.lambdaMax] est indiquée et toutes les valeurs restent
 * modifiables. Aucune de ces valeurs ne constitue une mesure certifiée.
 */
object MaterialLibrary {

    private const val ISO_10456 = "NF EN ISO 10456 (valeurs tabulées)"
    private const val TH_U = "Règles Th-U, fascicule Matériaux (ordre de grandeur)"
    private const val DECLARED_TYPICAL = "Valeur déclarée typique des fabricants (certificats ACERMI)"
    private const val EARTH_LIT = "Littérature technique terre crue (CRAterre, ENTPE) – forte dépendance à l'humidité"
    private const val EQUIVALENT = "Conductivité équivalente d'un bloc creux (inclut les alvéoles)"

    val all: List<Material> = listOf(
        // --- Terre crue ---
        m("pise", "Pisé (terre compactée)", EARTH, 1.10, 1900.0, 1000.0, 0.60, 1.50, EARTH_LIT, ValueOrigin.ESTIMATED,
            notes = "Murs anciens de 40 à 70 cm. λ augmente fortement avec l'humidité du mur."),
        m("terre_crue_bloc", "Terre crue – adobe / BTC", EARTH, 0.90, 1800.0, 1000.0, 0.60, 1.20, EARTH_LIT, ValueOrigin.ESTIMATED),
        m("bauge", "Bauge", EARTH, 0.80, 1600.0, 1000.0, 0.50, 1.10, EARTH_LIT, ValueOrigin.ESTIMATED),
        m("torchis", "Torchis (terre-paille)", EARTH, 0.45, 1200.0, 1000.0, 0.25, 0.60, EARTH_LIT, ValueOrigin.ESTIMATED),
        // --- Pierre ---
        m("pierre_moyenne", "Pierre – mur de moellons (valeur moyenne)", STONE, 1.70, 2000.0, 1000.0, 1.00, 2.80, TH_U, ValueOrigin.ESTIMATED,
            notes = "À préciser selon la nature de la pierre lorsqu'elle est connue."),
        m("calcaire_tendre", "Pierre calcaire tendre", STONE, 1.10, 1700.0, 1000.0, 0.85, 1.40, TH_U),
        m("calcaire_ferme", "Pierre calcaire ferme", STONE, 1.40, 1900.0, 1000.0, 1.10, 1.70, TH_U),
        m("calcaire_dur", "Pierre calcaire dure", STONE, 1.70, 2100.0, 1000.0, 1.40, 2.30, TH_U),
        m("calcaire_tres_dur", "Pierre calcaire très dure / marbre", STONE, 2.30, 2400.0, 1000.0, 1.70, 2.90, TH_U),
        m("granite", "Granite", STONE, 2.80, 2600.0, 1000.0, 2.50, 3.50, ISO_10456),
        m("gres", "Grès", STONE, 2.30, 2200.0, 1000.0, 1.60, 2.80, ISO_10456),
        // --- Maçonnerie ---
        m("brique_pleine", "Brique pleine (terre cuite)", MASONRY, 0.84, 1800.0, 1000.0, 0.60, 1.10, TH_U),
        m("brique_creuse", "Brique creuse classique (λ équivalent)", MASONRY, 0.45, 750.0, 1000.0, 0.35, 0.60, EQUIVALENT, ValueOrigin.ESTIMATED),
        m("brique_monomur", "Brique monomur / alvéolaire (λ équivalent)", MASONRY, 0.12, 750.0, 1000.0, 0.09, 0.15, EQUIVALENT, ValueOrigin.ESTIMATED),
        m("parpaing_creux", "Parpaing creux béton (λ équivalent, R ≈ 0,23 pour 20 cm)", MASONRY, 0.87, 1300.0, 1000.0, 0.70, 1.10, EQUIVALENT, ValueOrigin.ESTIMATED),
        m("parpaing_plein", "Bloc béton plein", MASONRY, 1.40, 2100.0, 1000.0, 1.10, 1.75, TH_U),
        m("beton_cellulaire", "Béton cellulaire", MASONRY, 0.12, 450.0, 1000.0, 0.09, 0.16, DECLARED_TYPICAL),
        // --- Béton ---
        m("beton_arme", "Béton armé", CONCRETE, 2.30, 2400.0, 1000.0, 2.00, 2.50, ISO_10456),
        m("beton_plein", "Béton plein (haute densité)", CONCRETE, 2.00, 2400.0, 1000.0, 1.65, 2.30, ISO_10456),
        m("beton_moyen", "Béton de densité moyenne", CONCRETE, 1.15, 1800.0, 1000.0, 0.90, 1.35, ISO_10456),
        // --- Bois ---
        m("bois_resineux", "Bois résineux (sapin, pin)", WOOD, 0.13, 500.0, 1600.0, 0.12, 0.15, ISO_10456),
        m("bois_feuillus", "Bois feuillus (chêne, hêtre) / parquet", WOOD, 0.18, 700.0, 1600.0, 0.15, 0.20, ISO_10456),
        m("osb", "Panneau OSB", WOOD, 0.13, 650.0, 1700.0, 0.12, 0.14, ISO_10456),
        m("contreplaque", "Contreplaqué", WOOD, 0.13, 500.0, 1600.0, 0.11, 0.15, ISO_10456),
        m("agglomere", "Panneau de particules", WOOD, 0.14, 600.0, 1700.0, 0.12, 0.18, ISO_10456),
        // --- Isolants ---
        m("laine_verre", "Laine de verre", INSULATION, 0.035, 15.0, 1030.0, 0.030, 0.040, DECLARED_TYPICAL, insulation = true),
        m("laine_minerale_ancienne", "Laine minérale ancienne (tassée, avant ~2000)", INSULATION, 0.045, 15.0, 1030.0, 0.040, 0.060,
            "Estimation : vieillissement, tassement et mise en œuvre imparfaite", ValueOrigin.ESTIMATED, insulation = true),
        m("laine_roche", "Laine de roche", INSULATION, 0.036, 40.0, 1030.0, 0.033, 0.040, DECLARED_TYPICAL, insulation = true),
        m("fibre_bois_souple", "Fibre de bois souple", INSULATION, 0.038, 50.0, 2100.0, 0.036, 0.040, DECLARED_TYPICAL, insulation = true),
        m("fibre_bois_rigide", "Fibre de bois rigide (panneau)", INSULATION, 0.043, 160.0, 2100.0, 0.038, 0.050, DECLARED_TYPICAL, insulation = true),
        m("ouate_cellulose", "Ouate de cellulose", INSULATION, 0.040, 45.0, 2000.0, 0.038, 0.045, DECLARED_TYPICAL, insulation = true),
        m("laine_chanvre", "Laine de chanvre", INSULATION, 0.040, 35.0, 1700.0, 0.038, 0.045, DECLARED_TYPICAL, insulation = true),
        m("liege_expanse", "Liège expansé", INSULATION, 0.040, 110.0, 1560.0, 0.037, 0.045, DECLARED_TYPICAL, insulation = true),
        m("pse", "Polystyrène expansé (PSE)", INSULATION, 0.035, 20.0, 1450.0, 0.030, 0.038, DECLARED_TYPICAL, insulation = true),
        m("xps", "Polystyrène extrudé (XPS)", INSULATION, 0.032, 35.0, 1450.0, 0.029, 0.035, DECLARED_TYPICAL, insulation = true),
        m("polyurethane", "Polyuréthane (PUR / PIR)", INSULATION, 0.023, 32.0, 1400.0, 0.022, 0.026, DECLARED_TYPICAL, insulation = true),
        m("chaux_chanvre", "Béton / enduit chaux-chanvre", INSULATION, 0.10, 450.0, 1500.0, 0.06, 0.14,
            "Ordre de grandeur des règles professionnelles chanvre", ValueOrigin.ESTIMATED),
        // --- Enduits et plâtres ---
        m("enduit_chaux", "Enduit à la chaux", PLASTER_RENDER, 0.80, 1600.0, 1000.0, 0.70, 1.00, TH_U),
        m("enduit_ciment", "Enduit ciment", PLASTER_RENDER, 1.15, 1900.0, 1000.0, 1.00, 1.40, TH_U),
        m("enduit_platre", "Enduit plâtre", PLASTER_RENDER, 0.57, 1300.0, 1000.0, 0.43, 0.57, ISO_10456),
        m("enduit_terre", "Enduit terre", PLASTER_RENDER, 0.60, 1500.0, 1000.0, 0.40, 0.90, EARTH_LIT, ValueOrigin.ESTIMATED),
        // --- Plaques ---
        m("ba13", "Plaque de plâtre (BA13)", BOARD, 0.25, 850.0, 1000.0, 0.21, 0.25, ISO_10456),
        // --- Revêtements / chapes ---
        m("carrelage", "Carrelage céramique", FLOOR_FINISH, 1.30, 2300.0, 840.0, 1.00, 1.30, ISO_10456),
        m("tomettes", "Terre cuite (tomettes)", FLOOR_FINISH, 1.00, 2000.0, 1000.0, 0.80, 1.15, TH_U, ValueOrigin.ESTIMATED),
        m("chape_ciment", "Chape / mortier de ciment", FLOOR_FINISH, 1.40, 2000.0, 1000.0, 1.00, 1.60, ISO_10456),
        m("moquette", "Moquette", FLOOR_FINISH, 0.06, 200.0, 1300.0, 0.05, 0.07, ISO_10456),
        // --- Divers ---
        m("lame_air", "Lame d'air non ventilée", AIR, 0.139, 1.2, 1005.0, null, null,
            "NF EN ISO 6946 : résistance tabulée selon l'épaisseur et le sens du flux"),
        m("remblai", "Hérisson / remblai (sable, graviers)", OTHER, 2.00, 1800.0, 910.0, 1.50, 2.00, ISO_10456),
    )

    private val byCode: Map<String, Material> = all.associateBy { it.code }

    fun byCode(code: String): Material = byCode[code] ?: error("Matériau inconnu : $code")

    fun find(code: String): Material? = byCode[code]

    fun byCategory(category: MaterialCategory): List<Material> = all.filter { it.category == category }

    private fun m(
        code: String,
        name: String,
        category: MaterialCategory,
        lambda: Double,
        density: Double,
        specificHeat: Double,
        lambdaMin: Double?,
        lambdaMax: Double?,
        source: String,
        origin: ValueOrigin = ValueOrigin.DECLARED,
        insulation: Boolean = false,
        notes: String = "",
    ) = Material(
        code = code,
        name = name,
        category = category,
        lambda = lambda,
        density = density,
        specificHeat = specificHeat,
        lambdaMin = lambdaMin,
        lambdaMax = lambdaMax,
        source = source,
        origin = origin,
        isInsulation = insulation,
        notes = notes,
        builtIn = true,
    )
}
