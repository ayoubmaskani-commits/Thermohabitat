package fr.thermohabitat.thermal.library

import fr.thermohabitat.thermal.model.Boundary
import fr.thermohabitat.thermal.model.Building
import fr.thermohabitat.thermal.model.ConstructionType
import fr.thermohabitat.thermal.model.GroundContact
import fr.thermohabitat.thermal.model.HeatingSystem
import fr.thermohabitat.thermal.model.IdentificationMethod
import fr.thermohabitat.thermal.model.InsulationType
import fr.thermohabitat.thermal.model.JoineryType
import fr.thermohabitat.thermal.model.Layer
import fr.thermohabitat.thermal.model.Opening
import fr.thermohabitat.thermal.model.OpeningKind
import fr.thermohabitat.thermal.model.Orientation
import fr.thermohabitat.thermal.model.Radiator
import fr.thermohabitat.thermal.model.RadiatorEra
import fr.thermohabitat.thermal.model.RadiatorMaterial
import fr.thermohabitat.thermal.model.RadiatorType
import fr.thermohabitat.thermal.model.Room
import fr.thermohabitat.thermal.model.RoomUsage
import fr.thermohabitat.thermal.model.Surface
import fr.thermohabitat.thermal.model.SurfaceKind
import fr.thermohabitat.thermal.model.ValueOrigin
import fr.thermohabitat.thermal.model.Ventilation
import fr.thermohabitat.thermal.model.VentilationType
import fr.thermohabitat.thermal.model.WallType

/**
 * Projets d'exemple. Les dimensions, compositions et radiateurs sont FICTIFS : ils illustrent
 * le cas d'usage « maison de 1870 en pisé » et doivent être remplacés par les relevés réels.
 */
object SampleProjects {

    const val EXAMPLE_NOTE =
        "Projet d'exemple : géométrie, compositions et radiateurs fictifs à remplacer par vos relevés."

    private const val H = 2.70

    private fun t(code: String) = CompositionTemplates.find(code)!!.toLayers()

    private fun wall(id: Long, label: String, o: Orientation, length: Double, layers: List<Layer>, vararg openings: Opening) =
        Surface(id = id, label = label, kind = SurfaceKind.WALL, boundary = Boundary.EXTERIOR, orientation = o,
            lengthM = length, heightM = H, layers = layers, openings = openings.toList())

    private fun window(id: Long, w: Double, h: Double, preset: String = "sv_bois", label: String = "Fenêtre"): Opening {
        val p = OpeningPresets.find(preset)!!
        return Opening(id = id, kind = p.kind, label = label, widthM = w, heightM = h, uValue = p.uValue,
            gValue = p.gValue, frameFraction = p.frameFraction, presetCode = p.code)
    }

    private fun door(id: Long, preset: String, label: String) = OpeningPresets.find(preset)!!.let { p ->
        Opening(id = id, kind = OpeningKind.DOOR, label = label, widthM = 0.9, heightM = 2.15, uValue = p.uValue,
            gValue = 0.0, frameFraction = 1.0, presetCode = p.code)
    }

    private fun groundFloor(id: Long, perimeter: Double) = Surface(
        id = id, label = "Sol tomettes sur terre-plein", kind = SurfaceKind.FLOOR, boundary = Boundary.GROUND,
        layers = t("tomettes_terre_plein"), ground = GroundContact(exposedPerimeterM = perimeter, wallThicknessM = 0.5),
    )

    private fun ceilingToRoom(id: Long, adjacentRoomId: Long) = Surface(
        id = id, label = "Plancher intermédiaire", kind = SurfaceKind.CEILING, boundary = Boundary.HEATED_ROOM,
        layers = t("plancher_intermediaire"), adjacentRoomId = adjacentRoomId,
    )

    private fun floorToRoom(id: Long, adjacentRoomId: Long) = Surface(
        id = id, label = "Plancher sur pièce chauffée", kind = SurfaceKind.FLOOR, boundary = Boundary.HEATED_ROOM,
        layers = t("plancher_intermediaire").reversed(), adjacentRoomId = adjacentRoomId,
    )

    private fun atticCeiling(id: Long, insulated: Boolean) = Surface(
        id = id, label = if (insulated) "Plafond sous combles (laine ancienne 10 cm)" else "Plafond sous combles non isolé",
        kind = SurfaceKind.CEILING, boundary = Boundary.UNHEATED, unheatedPresetCode = "combles_non_isoles",
        layers = if (insulated) {
            t("plafond_platre_non_isole") + Layer(material = MaterialLibrary.byCode("laine_minerale_ancienne"), thicknessM = 0.10)
        } else t("plafond_platre_non_isole"),
    )

    /** Maison individuelle de 1870 en pisé, 110 m², 2,70 m sous plafond, 9 radiateurs fonte. */
    fun pise1870(): Building {
        val pise = t("pise_50")
        val pise16 = t("pise_50_lv16")
        val pise18 = CompositionTemplates.find("pise_50_lv16")!!.toLayers().map {
            if (it.material.code == "laine_verre") it.copy(thicknessM = 0.18) else it
        }
        val pise15 = CompositionTemplates.find("pise_50_lv16")!!.toLayers().map {
            if (it.material.code == "laine_verre") it.copy(thicknessM = 0.15) else it
        }
        val rooms = listOf(
            Room(1, "Salon", 0, 6.0, 5.0, H, setpointC = 20.0, usage = RoomUsage.LIVING, planX = 0.0, planY = 0.0, surfaces = listOf(
                wall(101, "Mur sud (isolé 16 cm)", Orientation.S, 6.0, pise16, window(1011, 1.2, 1.45), window(1012, 1.2, 1.45)),
                wall(102, "Mur ouest (non isolé)", Orientation.W, 5.0, pise, window(1021, 1.0, 1.25)),
                groundFloor(103, 11.0),
                ceilingToRoom(104, 5),
            )),
            Room(2, "Salle à manger", 0, 4.0, 4.0, H, setpointC = 20.0, usage = RoomUsage.LIVING, planX = 0.0, planY = 5.0, surfaces = listOf(
                wall(201, "Mur nord (non isolé)", Orientation.N, 4.0, pise, window(2011, 1.0, 1.25)),
                groundFloor(202, 4.0),
                ceilingToRoom(203, 6),
            )),
            Room(3, "Cuisine", 0, 3.5, 4.0, H, setpointC = 20.0, usage = RoomUsage.KITCHEN, planX = 4.0, planY = 5.0, surfaces = listOf(
                wall(301, "Mur nord (non isolé)", Orientation.N, 3.5, pise, window(3011, 1.0, 1.0)),
                wall(302, "Mur est (isolé 18 cm)", Orientation.E, 4.0, pise18, door(3021, "porte_bois_pleine", "Porte de service")),
                groundFloor(303, 7.5),
                ceilingToRoom(304, 7),
            )),
            Room(4, "Entrée", 0, 2.0, 4.0, H, setpointC = 18.0, usage = RoomUsage.CIRCULATION, planX = 6.0, planY = 0.0, surfaces = listOf(
                wall(401, "Mur sud", Orientation.S, 2.0, pise, door(4011, "porte_bois_vitree", "Porte d'entrée")),
                groundFloor(402, 2.0),
                ceilingToRoom(403, 8),
            )),
            Room(5, "Chambre 1", 1, 5.0, 2.8, H, setpointC = 19.0, usage = RoomUsage.BEDROOM, planX = 0.0, planY = 0.0, surfaces = listOf(
                wall(501, "Mur sud (isolé 16 cm)", Orientation.S, 5.0, pise16, window(5011, 1.0, 1.25)),
                floorToRoom(502, 1),
                atticCeiling(503, insulated = true),
            )),
            Room(6, "Chambre 2", 1, 4.0, 3.0, H, setpointC = 19.0, usage = RoomUsage.BEDROOM, planX = 0.0, planY = 2.8, surfaces = listOf(
                wall(601, "Mur nord (non isolé)", Orientation.N, 4.0, pise, window(6011, 1.0, 1.25)),
                wall(602, "Mur ouest (non isolé)", Orientation.W, 3.0, pise),
                floorToRoom(603, 2),
                atticCeiling(604, insulated = true),
            )),
            Room(7, "Salle de bains", 1, 2.5, 3.2, H, setpointC = 22.0, usage = RoomUsage.BATHROOM, planX = 4.0, planY = 2.8, surfaces = listOf(
                wall(701, "Mur est (isolé 15 cm)", Orientation.E, 3.2, pise15, window(7011, 0.6, 0.8)),
                floorToRoom(702, 3),
                atticCeiling(703, insulated = false),
            )),
            Room(8, "Bureau", 1, 2.5, 3.2, H, setpointC = 19.0, usage = RoomUsage.OFFICE, planX = 5.0, planY = 0.0, surfaces = listOf(
                wall(801, "Mur sud (non isolé)", Orientation.S, 2.5, pise, window(8011, 0.8, 1.0)),
                wall(802, "Mur est (non isolé)", Orientation.E, 3.2, pise),
                floorToRoom(803, 4),
                atticCeiling(804, insulated = false),
            )),
        )
        fun castIron(id: Long, code: String, room: Long, columns: Int, h: Double, d: Double, sections: Int) = Radiator(
            id = id, code = code, roomId = room, label = "Fonte $columns colonnes",
            type = RadiatorType.CAST_IRON_COLUMN, material = RadiatorMaterial.CAST_IRON, era = RadiatorEra.OLD,
            sections = sections, columns = columns, heightMm = h, depthMm = d, lengthMm = sections * 60.0,
            dimensionsOrigin = ValueOrigin.ESTIMATED, identificationMethod = IdentificationMethod.LIBRARY,
            validated = true, notes = EXAMPLE_NOTE,
        )
        // Radiateurs d'origine dimensionnés pour une eau à 75–90 °C (installation fioul existante).
        val radiators = listOf(
            castIron(1, "R01", 1, 4, 800.0, 220.0, 14),
            castIron(2, "R02", 1, 4, 800.0, 220.0, 12),
            castIron(3, "R03", 2, 3, 800.0, 150.0, 12),
            castIron(4, "R04", 3, 4, 600.0, 200.0, 16),
            castIron(5, "R05", 4, 2, 800.0, 100.0, 10),
            castIron(6, "R06", 5, 3, 600.0, 150.0, 10),
            castIron(7, "R07", 6, 4, 600.0, 200.0, 14),
            castIron(8, "R08", 7, 4, 800.0, 200.0, 10),
            castIron(9, "R09", 8, 3, 800.0, 150.0, 14),
        )
        return Building(
            id = 0,
            name = "Maison 1870 en pisé (exemple)",
            commune = "Exemple – région lyonnaise",
            constructionYear = 1870,
            declaredLivingAreaM2 = 110.0,
            floorsCount = 2,
            ceilingHeightM = H,
            constructionType = ConstructionType.DETACHED_HOUSE,
            wallType = WallType.PISE,
            insulationType = InsulationType.INTERIOR_PARTIAL,
            joineryType = JoineryType.SINGLE_GLAZING,
            existingHeating = HeatingSystem.OIL_BOILER,
            setpointC = 20.0,
            climate = ClimateReferences.climateFor(ClimateReferences.find("Lyon")!!, 250.0),
            ventilation = Ventilation(type = VentilationType.NATURAL, n50 = 10.0, n50Origin = ValueOrigin.ESTIMATED),
            thermalBridgeDeltaU = 0.10,
            rooms = rooms,
            radiators = radiators,
            notes = EXAMPLE_NOTE,
        )
    }
}
