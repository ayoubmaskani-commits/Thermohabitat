package fr.thermohabitat.thermal

import fr.thermohabitat.thermal.model.Boundary
import fr.thermohabitat.thermal.model.Building
import fr.thermohabitat.thermal.model.Climate
import fr.thermohabitat.thermal.model.Opening
import fr.thermohabitat.thermal.model.OpeningKind
import fr.thermohabitat.thermal.model.Orientation
import fr.thermohabitat.thermal.model.Radiator
import fr.thermohabitat.thermal.model.RadiatorType
import fr.thermohabitat.thermal.model.Room
import fr.thermohabitat.thermal.model.Surface
import fr.thermohabitat.thermal.model.SurfaceKind
import fr.thermohabitat.thermal.model.ValueOrigin
import fr.thermohabitat.thermal.model.Ventilation

/** Données de test simples, vérifiables à la main. */
object TestFixtures {

    val climate = Climate(designOutdoorTempC = -10.0, meanAnnualOutdoorTempC = 11.0, degreeDays = 2500.0)

    /** Pièce 5 × 4 × 2,5 m, un mur extérieur de 5 m (U = 1) avec une fenêtre 1 × 1 m (U = 3). */
    fun simpleRoom(id: Long = 1, setpoint: Double = 20.0) = Room(
        id = id, name = "Pièce $id", lengthM = 5.0, widthM = 4.0, heightM = 2.5, setpointC = setpoint,
        surfaces = listOf(
            Surface(
                id = id * 10, label = "Mur", kind = SurfaceKind.WALL, boundary = Boundary.EXTERIOR,
                orientation = Orientation.S, lengthM = 5.0, heightM = 2.5,
                uValueOverride = 1.0, uValueOrigin = ValueOrigin.DECLARED,
                openings = listOf(Opening(id = id * 100, kind = OpeningKind.WINDOW, widthM = 1.0, heightM = 1.0, uValue = 3.0)),
            ),
        ),
    )

    fun simpleBuilding(deltaU: Double = 0.0, rooms: List<Room> = listOf(simpleRoom()), radiators: List<Radiator> = emptyList()) =
        Building(
            name = "Test", climate = climate, thermalBridgeDeltaU = deltaU, rooms = rooms, radiators = radiators,
            ventilation = Ventilation(n50 = 10.0),
        )

    fun radiator(id: Long, roomId: Long?, nominal: Double, validated: Boolean = true) = Radiator(
        id = id, code = "R%02d".format(id), roomId = roomId, type = RadiatorType.CAST_IRON_COLUMN,
        nominalPowerW = nominal, nominalPowerOrigin = ValueOrigin.DECLARED, documentedUncertaintyPct = 0.0,
        exponent = 1.3, validated = validated,
    )
}
