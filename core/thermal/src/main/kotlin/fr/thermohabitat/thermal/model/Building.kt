package fr.thermohabitat.thermal.model

import kotlinx.serialization.Serializable

/**
 * Données climatiques de conception.
 *
 * @property designOutdoorTempC température extérieure de base θe (°C)
 * @property meanAnnualOutdoorTempC température extérieure moyenne annuelle θm,e (°C), utilisée pour le sol
 * @property degreeDays degrés-jours unifiés de chauffage (base [degreeDaysBase])
 */
@Serializable
data class Climate(
    val referenceName: String = "",
    val designOutdoorTempC: Double = -7.0,
    val designOutdoorOrigin: ValueOrigin = ValueOrigin.ESTIMATED,
    val meanAnnualOutdoorTempC: Double = 11.5,
    val degreeDays: Double = 2400.0,
    val degreeDaysBase: Double = 18.0,
    val degreeDaysOrigin: ValueOrigin = ValueOrigin.ESTIMATED,
    val altitudeM: Double = 200.0,
)

/**
 * Couche homogène d'une paroi. Les couches sont ordonnées de l'INTÉRIEUR vers l'EXTÉRIEUR.
 */
@Serializable
data class Layer(
    val id: Long = 0,
    val material: Material,
    val thicknessM: Double,
    val lambdaOverride: Double? = null,
    val origin: ValueOrigin = ValueOrigin.ESTIMATED,
) {
    val lambda: Double get() = lambdaOverride ?: material.lambda
}

/** Baie (fenêtre, porte-fenêtre, porte) intégrée dans une paroi. */
@Serializable
data class Opening(
    val id: Long = 0,
    val kind: OpeningKind = OpeningKind.WINDOW,
    val label: String = "",
    val widthM: Double,
    val heightM: Double,
    val count: Int = 1,
    val uValue: Double,
    val uOrigin: ValueOrigin = ValueOrigin.ESTIMATED,
    /** Facteur solaire g du vitrage (0–1). */
    val gValue: Double = 0.6,
    /** Fraction de cadre (part opaque de la baie, 0–1). */
    val frameFraction: Double = 0.3,
    val presetCode: String? = null,
) {
    val areaM2: Double get() = widthM * heightM * count
    val glazedAreaM2: Double get() = if (kind.isGlazed) areaM2 * (1.0 - frameFraction) else 0.0
}

/** Paramètres d'un plancher sur terre-plein (EN ISO 13370). */
@Serializable
data class GroundContact(
    /** Périmètre exposé (en contact avec l'extérieur) de la partie de plancher de la pièce, m. */
    val exposedPerimeterM: Double = 0.0,
    /** Épaisseur des murs périphériques, m. */
    val wallThicknessM: Double = 0.4,
    /** Conductivité du sol λg, W/(m·K) (2,0 : argile/limon, valeur par défaut normative). */
    val groundConductivity: Double = 2.0,
    /** Nappe phréatique à moins d'un mètre sous le plancher. */
    val shallowGroundwater: Boolean = false,
)

/**
 * Paroi d'une pièce (mur, plancher, plafond, rampant).
 *
 * La surface brute est déterminée, par ordre de priorité, par [areaOverrideM2],
 * puis longueur × hauteur (murs), puis la surface au sol de la pièce (planchers, plafonds).
 */
@Serializable
data class Surface(
    val id: Long = 0,
    val label: String = "",
    val kind: SurfaceKind = SurfaceKind.WALL,
    val boundary: Boundary = Boundary.EXTERIOR,
    val orientation: Orientation? = null,
    val lengthM: Double? = null,
    val heightM: Double? = null,
    val areaOverrideM2: Double? = null,
    val layers: List<Layer> = emptyList(),
    val uValueOverride: Double? = null,
    val uValueOrigin: ValueOrigin = ValueOrigin.ESTIMATED,
    /** Coefficient de réduction de température b (local non chauffé). */
    val unheatedFactorB: Double? = null,
    val unheatedPresetCode: String? = null,
    /** Température de l'espace adjacent (pièce chauffée, mitoyen), °C. */
    val adjacentTemperatureC: Double? = null,
    /** Pièce adjacente de la même étude (paroi intérieure). */
    val adjacentRoomId: Long? = null,
    val openings: List<Opening> = emptyList(),
    val ground: GroundContact? = null,
    val notes: String = "",
)

/** Pont thermique linéique saisi de manière détaillée. */
@Serializable
data class LinearThermalBridge(
    val id: Long = 0,
    val label: String,
    val lengthM: Double,
    /** Coefficient linéique ψ en W/(m·K). */
    val psi: Double,
    val origin: ValueOrigin = ValueOrigin.ESTIMATED,
)

@Serializable
data class Room(
    val id: Long = 0,
    val name: String,
    val floorLevel: Int = 0,
    val lengthM: Double,
    val widthM: Double,
    val heightM: Double,
    val floorAreaOverrideM2: Double? = null,
    val setpointC: Double = 20.0,
    val usage: RoomUsage = RoomUsage.LIVING,
    val minAirChangeOverride: Double? = null,
    /** Apports internes moyens (occupants, appareils), W. Non déduits du dimensionnement. */
    val internalGainsW: Double = 0.0,
    val surfaces: List<Surface> = emptyList(),
    val thermalBridges: List<LinearThermalBridge> = emptyList(),
    val planX: Double? = null,
    val planY: Double? = null,
    val notes: String = "",
) {
    val floorAreaM2: Double get() = floorAreaOverrideM2 ?: (lengthM * widthM)
    val volumeM3: Double get() = floorAreaM2 * heightM
    val minAirChangePerHour: Double get() = minAirChangeOverride ?: usage.minAirChangePerHour
}

@Serializable
data class Ventilation(
    val type: VentilationType = VentilationType.NATURAL,
    /** Débit mécanique total extrait (simple flux) ou soufflé (double flux), m³/h. Null : Σ débits minimaux. */
    val mechanicalFlowM3h: Double? = null,
    /** Efficacité de récupération de l'échangeur double flux (0–1). */
    val heatRecoveryEfficiency: Double = 0.0,
    /** Taux de renouvellement d'air sous 50 Pa, h⁻¹. */
    val n50: Double = 10.0,
    val n50Origin: ValueOrigin = ValueOrigin.ESTIMATED,
    val shielding: Shielding = Shielding.MODERATE,
    /** Hauteur du bâtiment au-dessus du sol, m (correction ε). */
    val buildingHeightM: Double = 6.0,
)

/** Projet / bâtiment étudié. */
@Serializable
data class Building(
    val id: Long = 0,
    val name: String,
    val address: String = "",
    val commune: String = "",
    val constructionYear: Int? = null,
    val declaredLivingAreaM2: Double? = null,
    val floorsCount: Int = 1,
    val ceilingHeightM: Double = 2.5,
    val constructionType: ConstructionType = ConstructionType.DETACHED_HOUSE,
    val wallType: WallType = WallType.OTHER,
    val insulationType: InsulationType = InsulationType.UNKNOWN,
    val joineryType: JoineryType = JoineryType.MIXED,
    val existingHeating: HeatingSystem = HeatingSystem.OTHER,
    val setpointC: Double = 20.0,
    val climate: Climate = Climate(),
    val ventilation: Ventilation = Ventilation(),
    /** Majoration forfaitaire des ponts thermiques ΔU_TB, W/(m²·K). */
    val thermalBridgeDeltaU: Double = 0.10,
    /** Surpuissance de relance Φ_RH par m² de plancher (0 = chauffage continu), W/m². */
    val reheatFactorWm2: Double = 0.0,
    /** Capacité thermique complémentaire (cloisons, mobilier) par m² de plancher, J/(m²·K). */
    val additionalHeatCapacityJm2K: Double = 20_000.0,
    val rooms: List<Room> = emptyList(),
    val radiators: List<Radiator> = emptyList(),
    val notes: String = "",
) {
    val heatedFloorAreaM2: Double get() = rooms.sumOf { it.floorAreaM2 }
    val heatedVolumeM3: Double get() = rooms.sumOf { it.volumeM3 }
    fun room(id: Long?): Room? = if (id == null) null else rooms.firstOrNull { it.id == id }
    fun radiatorsIn(roomId: Long): List<Radiator> = radiators.filter { it.roomId == roomId }
}
