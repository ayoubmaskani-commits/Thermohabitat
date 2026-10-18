package fr.thermohabitat.thermal.model

import kotlinx.serialization.Serializable

@Serializable
enum class MaterialCategory(val label: String) {
    EARTH("Terre crue"),
    STONE("Pierre"),
    MASONRY("Maçonnerie (brique, parpaing)"),
    CONCRETE("Béton"),
    WOOD("Bois"),
    INSULATION("Isolants"),
    PLASTER_RENDER("Enduits et plâtres"),
    BOARD("Plaques et panneaux"),
    FLOOR_FINISH("Revêtements et chapes"),
    AIR("Lame d'air"),
    OTHER("Autre");
}

/**
 * Matériau de construction et ses propriétés thermophysiques.
 *
 * @property lambda conductivité thermique utile λ en W/(m·K)
 * @property density masse volumique ρ en kg/m³
 * @property specificHeat capacité thermique massique c en J/(kg·K)
 * @property lambdaMin borne basse de la plage usuelle de λ (dispersion des matériaux anciens)
 * @property lambdaMax borne haute de la plage usuelle de λ
 * @property source référence documentaire de la valeur
 */
@Serializable
data class Material(
    val id: Long = 0,
    val code: String,
    val name: String,
    val category: MaterialCategory,
    val lambda: Double,
    val density: Double,
    val specificHeat: Double,
    val lambdaMin: Double? = null,
    val lambdaMax: Double? = null,
    val source: String = "",
    val origin: ValueOrigin = ValueOrigin.DECLARED,
    val isInsulation: Boolean = false,
    val notes: String = "",
    val builtIn: Boolean = true,
) {
    /** Capacité thermique volumique ρ·c en J/(m³·K). */
    val volumetricHeatCapacity: Double get() = density * specificHeat

    val isAirGap: Boolean get() = category == MaterialCategory.AIR
}
