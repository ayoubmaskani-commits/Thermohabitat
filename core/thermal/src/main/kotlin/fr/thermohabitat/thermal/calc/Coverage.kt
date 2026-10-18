package fr.thermohabitat.thermal.calc

import fr.thermohabitat.thermal.model.Building
import fr.thermohabitat.thermal.model.Radiator
import kotlinx.serialization.Serializable

/** Code couleur de couverture des besoins. */
enum class CoverageStatus(val label: String) {
    SUFFICIENT("Puissance suffisante"),
    LIMIT("Situation limite"),
    INSUFFICIENT("Puissance insuffisante"),
    NO_EMITTER("Aucun émetteur validé"),
    NO_NEED("Pas de besoin"),
}

/** Mode hydraulique retenu pour évaluer la puissance des émetteurs. */
@Serializable
sealed interface HydraulicMode {
    /** Écart départ/retour constant sur chaque émetteur. */
    @Serializable
    data class FixedDeltaT(val deltaK: Double = 10.0) : HydraulicMode

    /** Débit imposé par émetteur (débit saisi, sinon débit nominal). */
    @Serializable
    data object FixedFlow : HydraulicMode
}

data class RoomCoverage(
    val roomId: Long,
    val roomName: String,
    val floorAreaM2: Double,
    val setpointC: Double,
    val needW: Double,
    val availableW: Double,
    val availableLowW: Double,
    val availableHighW: Double,
    val status: CoverageStatus,
    val radiators: List<RadiatorOutput>,
    /** Émetteurs présents mais non validés : exclus du bilan. */
    val pendingRadiators: Int,
) {
    val ratio: Double get() = if (needW > 0) availableW / needW else Double.POSITIVE_INFINITY
    val deficitW: Double get() = (needW - availableW).coerceAtLeast(0.0)
    val surplusW: Double get() = (availableW - needW).coerceAtLeast(0.0)
}

data class BuildingCoverage(
    val supplyC: Double,
    val returnC: Double?,
    val mode: HydraulicMode,
    val rooms: List<RoomCoverage>,
) {
    val totalNeedW: Double get() = rooms.sumOf { it.needW }
    val totalAvailableW: Double get() = rooms.sumOf { it.availableW }

    /** Fraction des besoins couverts (chaque pièce plafonnée à son besoin). */
    val coveredFraction: Double
        get() {
            val need = totalNeedW
            if (need <= 0) return 1.0
            return rooms.sumOf { minOf(it.availableW, it.needW) } / need
        }

    val insufficientRooms: List<RoomCoverage>
        get() = rooms.filter { it.status == CoverageStatus.INSUFFICIENT || it.status == CoverageStatus.NO_EMITTER }

    val limitRooms: List<RoomCoverage> get() = rooms.filter { it.status == CoverageStatus.LIMIT }
}

/**
 * Comparaison pièce par pièce des déperditions et des puissances disponibles.
 *
 * Règle de couleur (prise en compte de l'incertitude des puissances estimées) :
 *  - vert : la borne BASSE de la puissance disponible couvre le besoin ;
 *  - rouge : la puissance disponible (valeur centrale) est inférieure à 90 % du besoin ;
 *  - orange : entre les deux (couverture incertaine ou juste).
 */
object CoverageAnalyzer {

    const val INSUFFICIENT_THRESHOLD = 0.90

    fun analyze(
        building: Building,
        heatLoss: BuildingHeatLoss,
        regime: Regime,
        mode: HydraulicMode = HydraulicMode.FixedDeltaT(regime.supplyC - regime.returnC),
        radiators: List<Radiator> = building.radiators,
    ): BuildingCoverage {
        val rooms = heatLoss.rooms.map { rl ->
            val inRoom = radiators.filter { it.roomId == rl.roomId }
            val validated = inRoom.filter { RadiatorPower.countsInStudy(it) }
            val outputs = validated.map { outputFor(it, regime.supplyC, mode, rl.setpointC) }
            roomCoverage(rl, outputs, inRoom.size - validated.size)
        }
        return BuildingCoverage(regime.supplyC, if (mode is HydraulicMode.FixedDeltaT) regime.supplyC - mode.deltaK else null, mode, rooms)
    }

    /** Puissance d'un émetteur pour une température de départ selon le mode hydraulique. */
    fun outputFor(radiator: Radiator, supplyC: Double, mode: HydraulicMode, ambientC: Double): RadiatorOutput = when (mode) {
        is HydraulicMode.FixedDeltaT -> RadiatorPower.outputOf(radiator, supplyC, supplyC - mode.deltaK, ambientC)
        HydraulicMode.FixedFlow -> RadiatorPower.outputOfWithFlow(radiator, supplyC, ambientC)
    }

    fun roomCoverage(rl: RoomHeatLoss, outputs: List<RadiatorOutput>, pending: Int): RoomCoverage {
        val available = outputs.sumOf { it.powerW }
        val low = outputs.sumOf { it.lowW }
        val high = outputs.sumOf { it.highW }
        val need = rl.totalW
        val status = statusOf(need, available, low, outputs.isNotEmpty())
        return RoomCoverage(rl.roomId, rl.roomName, rl.floorAreaM2, rl.setpointC, need, available, low, high, status, outputs, pending)
    }

    fun statusOf(needW: Double, availableW: Double, availableLowW: Double, hasEmitters: Boolean): CoverageStatus = when {
        needW <= 0.0 -> CoverageStatus.NO_NEED
        !hasEmitters -> CoverageStatus.NO_EMITTER
        availableLowW >= needW -> CoverageStatus.SUFFICIENT
        availableW < INSUFFICIENT_THRESHOLD * needW -> CoverageStatus.INSUFFICIENT
        else -> CoverageStatus.LIMIT
    }
}

/** Température de départ minimale par pièce et pour le bâtiment. */
data class RoomSupplyRequirement(
    val roomId: Long,
    val roomName: String,
    /** Température de départ minimale (°C), null si non atteignable à [SupplyTemperatureSolver.MAX_SUPPLY_C]. */
    val requiredSupplyC: Double?,
    val hasEmitters: Boolean,
)

data class BuildingSupplyRequirement(
    val rooms: List<RoomSupplyRequirement>,
    /** Température de départ nécessaire pour couvrir toutes les pièces équipées (°C). */
    val requiredSupplyC: Double?,
    val limitingRoom: RoomSupplyRequirement?,
    val uncoverableRooms: List<RoomSupplyRequirement>,
    val roomsWithoutEmitters: List<RoomSupplyRequirement>,
    val conservative: Boolean,
)

/**
 * Recherche, par dichotomie, de la température de départ minimale permettant aux émetteurs
 * validés de chaque pièce de couvrir sa déperdition de conception.
 */
object SupplyTemperatureSolver {

    const val MAX_SUPPLY_C = 90.0

    /**
     * @param loadFactor fraction de la charge de conception (1,0 à θe ; < 1 pour une température extérieure plus douce)
     * @param conservative utilise la borne basse des puissances estimées
     */
    fun solve(
        building: Building,
        heatLoss: BuildingHeatLoss,
        mode: HydraulicMode,
        loadFactor: Double = 1.0,
        conservative: Boolean = false,
        radiators: List<Radiator> = building.radiators,
    ): BuildingSupplyRequirement {
        val reqs = heatLoss.rooms.map { rl ->
            val emitters = radiators.filter { it.roomId == rl.roomId && RadiatorPower.countsInStudy(it) }
            val need = rl.totalW * loadFactor
            val t = if (emitters.isEmpty()) null else minimumSupply(emitters, need, rl.setpointC, mode, conservative)
            RoomSupplyRequirement(rl.roomId, rl.roomName, t, emitters.isNotEmpty())
        }
        val equipped = reqs.filter { it.hasEmitters }
        val uncoverable = equipped.filter { it.requiredSupplyC == null }
        val required = if (equipped.isEmpty() || uncoverable.isNotEmpty()) null else equipped.maxOf { it.requiredSupplyC!! }
        val limiting = equipped.filter { it.requiredSupplyC != null }.maxByOrNull { it.requiredSupplyC!! }
        return BuildingSupplyRequirement(
            rooms = reqs,
            requiredSupplyC = required,
            limitingRoom = if (uncoverable.isNotEmpty()) uncoverable.first() else limiting,
            uncoverableRooms = uncoverable,
            roomsWithoutEmitters = reqs.filter { !it.hasEmitters && (heatLoss.room(it.roomId)?.totalW ?: 0.0) > 0 },
            conservative = conservative,
        )
    }

    /** Température de départ minimale (°C) pour délivrer [needW], null si impossible. */
    fun minimumSupply(
        radiators: List<Radiator>,
        needW: Double,
        ambientC: Double,
        mode: HydraulicMode,
        conservative: Boolean = false,
    ): Double? {
        if (needW <= 0) return ambientC
        val lowerBound = when (mode) {
            is HydraulicMode.FixedDeltaT -> ambientC + mode.deltaK + 0.05
            HydraulicMode.FixedFlow -> ambientC + 0.05
        }
        fun power(ts: Double): Double = radiators.sumOf {
            val o = CoverageAnalyzer.outputFor(it, ts, mode, ambientC)
            if (conservative) o.lowW else o.powerW
        }
        if (power(MAX_SUPPLY_C) < needW) return null
        if (power(lowerBound) >= needW) return lowerBound
        var lo = lowerBound
        var hi = MAX_SUPPLY_C
        repeat(60) {
            val mid = (lo + hi) / 2
            if (power(mid) >= needW) hi = mid else lo = mid
        }
        return hi
    }
}
