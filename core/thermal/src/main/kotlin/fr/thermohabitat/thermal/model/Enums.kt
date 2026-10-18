package fr.thermohabitat.thermal.model

import kotlinx.serialization.Serializable

/**
 * Origine (et donc fiabilité) d'une donnée d'entrée.
 * Toutes les grandeurs saisies dans l'étude portent cette information afin que
 * les résultats puissent signaler la part d'hypothèses.
 */
@Serializable
enum class ValueOrigin(val label: String, val description: String) {
    MEASURED("Mesurée", "Valeur mesurée sur site (mètre, thermomètre, test d'étanchéité…)"),
    DECLARED("Déclarée", "Valeur issue d'un document : fiche fabricant, facture, étude, plaque"),
    ESTIMATED("Estimée", "Valeur indicative proposée par l'application ou estimée par l'utilisateur"),
    UNKNOWN("Inconnue", "Donnée inconnue : une valeur par défaut prudente est utilisée");

    /** Vrai si la valeur n'est pas issue d'une mesure ou d'un document. */
    val isUncertain: Boolean get() = this == ESTIMATED || this == UNKNOWN
}

@Serializable
enum class SurfaceKind(val label: String) {
    WALL("Mur"),
    FLOOR("Plancher bas"),
    CEILING("Plafond / plancher haut"),
    ROOF("Toiture / rampant");
}

/** Nature de l'espace situé de l'autre côté d'une paroi. */
@Serializable
enum class Boundary(val label: String) {
    EXTERIOR("Extérieur"),
    GROUND("Terre-plein (sol)"),
    UNHEATED("Local non chauffé"),
    HEATED_ROOM("Pièce chauffée voisine"),
    ADJACENT_BUILDING("Bâtiment / logement mitoyen");
}

@Serializable
enum class Orientation(val label: String, val azimuthDeg: Int) {
    N("Nord", 0), NE("Nord-Est", 45), E("Est", 90), SE("Sud-Est", 135),
    S("Sud", 180), SW("Sud-Ouest", 225), W("Ouest", 270), NW("Nord-Ouest", 315);
}

@Serializable
enum class OpeningKind(val label: String, val isGlazed: Boolean) {
    WINDOW("Fenêtre", true),
    FRENCH_WINDOW("Porte-fenêtre", true),
    ROOF_WINDOW("Fenêtre de toit", true),
    DOOR("Porte", false);
}

/**
 * Usage de la pièce : fixe le taux minimal de renouvellement d'air (EN 12831,
 * valeurs indicatives) et une consigne usuelle.
 */
@Serializable
enum class RoomUsage(val label: String, val minAirChangePerHour: Double, val usualSetpointC: Double) {
    LIVING("Séjour / salon", 0.5, 20.0),
    BEDROOM("Chambre", 0.5, 19.0),
    KITCHEN("Cuisine", 1.5, 20.0),
    BATHROOM("Salle de bains", 1.5, 22.0),
    WC("WC", 1.5, 19.0),
    OFFICE("Bureau", 0.5, 20.0),
    CIRCULATION("Entrée / couloir / escalier", 0.5, 18.0),
    OTHER("Autre", 0.5, 20.0);
}

@Serializable
enum class VentilationType(val label: String) {
    NATURAL("Naturelle (grilles, défauts d'étanchéité)"),
    EXTRACT("VMC simple flux"),
    BALANCED("VMC double flux");
}

/** Coefficient d'exposition au vent (EN 12831:2003, tableau D.8). */
@Serializable
enum class Shielding(val label: String, val eOneOpening: Double, val eSeveralOpenings: Double) {
    NONE("Site exposé (sans abri)", 0.03, 0.05),
    MODERATE("Abri modéré (campagne arborée, périurbain)", 0.02, 0.03),
    HEAVY("Site très abrité (centre-ville, forêt)", 0.01, 0.02);
}

/** Niveaux d'étanchéité à l'air indicatifs (taux de renouvellement sous 50 Pa). */
@Serializable
enum class AirtightnessPreset(val label: String, val n50: Double) {
    VERY_TIGHT("Très étanche (construction récente testée)", 1.5),
    TIGHT("Étanche (menuiseries récentes, joints soignés)", 3.0),
    MEDIUM("Moyenne", 6.0),
    LEAKY("Peu étanche (bâti ancien, menuiseries anciennes)", 10.0),
    VERY_LEAKY("Très peu étanche (simple vitrage, cheminées ouvertes)", 15.0);
}

@Serializable
enum class ConstructionType(val label: String) {
    DETACHED_HOUSE("Maison individuelle"),
    TERRACED_HOUSE("Maison mitoyenne"),
    APARTMENT("Appartement"),
    OTHER("Autre");
}

@Serializable
enum class WallType(val label: String) {
    PISE("Pisé"),
    EARTH("Terre crue (bauge, adobe)"),
    STONE("Pierre"),
    SOLID_BRICK("Brique pleine"),
    HOLLOW_BRICK("Brique creuse"),
    CONCRETE_BLOCK("Parpaing"),
    CONCRETE("Béton"),
    TIMBER_FRAME("Ossature bois"),
    MIXED("Mixte"),
    OTHER("Autre");
}

@Serializable
enum class InsulationType(val label: String) {
    NONE("Aucune isolation"),
    INTERIOR_PARTIAL("Isolation intérieure partielle"),
    INTERIOR("Isolation intérieure"),
    EXTERIOR("Isolation extérieure (ITE)"),
    DISTRIBUTED("Isolation répartie (monomur, béton cellulaire…)"),
    UNKNOWN("Inconnue");
}

@Serializable
enum class JoineryType(val label: String) {
    SINGLE_GLAZING("Simple vitrage"),
    OLD_DOUBLE_GLAZING("Double vitrage ancien (avant ~2000)"),
    RECENT_DOUBLE_GLAZING("Double vitrage récent faiblement émissif"),
    TRIPLE_GLAZING("Triple vitrage"),
    MIXED("Mixte");
}

@Serializable
enum class HeatingSystem(val label: String) {
    OIL_BOILER("Chaudière fioul"),
    GAS_BOILER("Chaudière gaz standard"),
    CONDENSING_GAS_BOILER("Chaudière gaz à condensation"),
    WOOD_BOILER("Chaudière / poêle bois"),
    ELECTRIC("Chauffage électrique direct"),
    HEAT_PUMP("Pompe à chaleur"),
    OTHER("Autre"),
    NONE("Aucun");
}

@Serializable
enum class RadiatorMaterial(val label: String) {
    CAST_IRON("Fonte"),
    STEEL("Acier"),
    ALUMINIUM("Aluminium"),
    OTHER("Autre"),
    UNKNOWN("Inconnu");
}

@Serializable
enum class RadiatorEra(val label: String) {
    OLD("Ancien (avant ~1975)"),
    MODERN("Moderne"),
    UNKNOWN("Inconnu");
}

/**
 * Familles d'émetteurs. L'exposant n par défaut correspond aux valeurs usuelles
 * relevées dans les essais EN 442 de chaque famille (il doit être remplacé par
 * la valeur fabricant lorsqu'elle est connue).
 */
@Serializable
enum class RadiatorType(
    val label: String,
    val defaultMaterial: RadiatorMaterial,
    val defaultExponent: Double,
    val isSectional: Boolean,
) {
    CAST_IRON_COLUMN("Fonte à colonnes (à éléments)", RadiatorMaterial.CAST_IRON, 1.30, true),
    CAST_IRON_PLATE("Fonte à plaques / panneau fonte", RadiatorMaterial.CAST_IRON, 1.30, false),
    STEEL_PANEL("Panneaux acier (types 10 à 33)", RadiatorMaterial.STEEL, 1.33, false),
    STEEL_COLUMN("Acier embouti à colonnes", RadiatorMaterial.STEEL, 1.30, true),
    STEEL_TUBULAR("Tubulaire acier multicolonnes", RadiatorMaterial.STEEL, 1.30, true),
    ALUMINIUM_SECTIONAL("Aluminium à éléments", RadiatorMaterial.ALUMINIUM, 1.32, true),
    TOWEL_RAIL("Sèche-serviettes à eau", RadiatorMaterial.STEEL, 1.22, false),
    DECORATIVE("Décoratif / design", RadiatorMaterial.OTHER, 1.30, false),
    CONVECTOR("Convecteur à eau", RadiatorMaterial.OTHER, 1.40, false),
    UNKNOWN("Non identifié", RadiatorMaterial.UNKNOWN, 1.30, false);
}

/**
 * Types normalisés de radiateurs à panneaux acier.
 * [outputKwPerMeterAt600] : puissance indicative par mètre de longueur pour une
 * hauteur de 600 mm à ΔT 50 K (ordre de grandeur des catalogues EN 442).
 */
@Serializable
enum class PanelType(val label: String, val outputKwPerMeterAt600: Double) {
    T10("Type 10 (1 panneau, sans ailettes)", 0.66),
    T11("Type 11 (1 panneau, 1 rangée d'ailettes)", 1.00),
    T20("Type 20 (2 panneaux, sans ailettes)", 0.95),
    T21("Type 21 (2 panneaux, 1 rangée d'ailettes)", 1.33),
    T22("Type 22 (2 panneaux, 2 rangées d'ailettes)", 1.65),
    T33("Type 33 (3 panneaux, 3 rangées d'ailettes)", 2.36);
}

@Serializable
enum class IdentificationMethod(val label: String) {
    MANUAL("Saisie manuelle"),
    LIBRARY("Bibliothèque illustrée"),
    OCR("Lecture de la plaque (OCR)"),
    AI_MODEL("Modèle d'IA embarqué"),
    DOCUMENTATION("Documentation fabricant");
}

@Serializable
enum class PhotoKind(val label: String) {
    FRONT("Face"),
    SIDE("Côté"),
    NAMEPLATE("Plaque / marquage"),
    DETAIL("Détail"),
    ROOM("Pièce"),
    OTHER("Autre");
}
