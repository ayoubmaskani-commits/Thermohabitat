package fr.thermohabitat.thermal.scenario

import fr.thermohabitat.thermal.calc.Regime
import fr.thermohabitat.thermal.fr
import fr.thermohabitat.thermal.model.Boundary
import fr.thermohabitat.thermal.model.Building
import fr.thermohabitat.thermal.model.Layer
import fr.thermohabitat.thermal.model.Material
import fr.thermohabitat.thermal.model.OpeningKind
import fr.thermohabitat.thermal.model.Radiator
import fr.thermohabitat.thermal.model.Surface
import fr.thermohabitat.thermal.model.SurfaceKind
import fr.thermohabitat.thermal.model.ValueOrigin
import fr.thermohabitat.thermal.model.VentilationType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Parois visées par une isolation. */
@Serializable
enum class InsulationTarget(val label: String) {
    EXTERIOR_WALLS("Murs extérieurs"),
    WALLS_TO_UNHEATED("Murs sur locaux non chauffés"),
    TOP_CEILINGS("Plafonds sous combles / toitures"),
    LOW_FLOORS("Planchers bas"),
    SELECTED("Parois sélectionnées"),
}

@Serializable
enum class InsulationPosition(val label: String) {
    INTERIOR("Par l'intérieur"),
    EXTERIOR("Par l'extérieur"),
}

/** Modification élémentaire d'un scénario d'amélioration. */
@Serializable
sealed interface Modification {
    val description: String

    @Serializable
    @SerialName("add_insulation")
    data class AddInsulation(
        val target: InsulationTarget,
        val material: Material,
        val thicknessM: Double,
        val position: InsulationPosition = InsulationPosition.INTERIOR,
        /** Parement ajouté côté intérieur (ex. plaque de plâtre) pour une isolation intérieure. */
        val finish: Material? = null,
        val finishThicknessM: Double = 0.0125,
        val surfaceIds: List<Long> = emptyList(),
    ) : Modification {
        override val description: String
            get() = "Isolation ${position.label.lowercase()} – ${target.label.lowercase()} : ${material.name} ${(thicknessM * 100).fr(0)} cm"
    }

    @Serializable
    @SerialName("replace_windows")
    data class ReplaceWindows(
        val uValue: Double,
        val gValue: Double = 0.6,
        val openingIds: List<Long> = emptyList(),
    ) : Modification {
        override val description: String get() = "Remplacement des fenêtres (Uw = ${uValue.fr(1)} W/(m²·K))"
    }

    @Serializable
    @SerialName("replace_doors")
    data class ReplaceDoors(val uValue: Double, val openingIds: List<Long> = emptyList()) : Modification {
        override val description: String get() = "Remplacement des portes (Ud = ${uValue.fr(1)} W/(m²·K))"
    }

    @Serializable
    @SerialName("airtightness")
    data class ImproveAirtightness(val n50: Double) : Modification {
        override val description: String get() = "Amélioration de l'étanchéité à l'air (n50 = ${n50.fr(1)} h⁻¹)"
    }

    @Serializable
    @SerialName("ventilation")
    data class ChangeVentilation(
        val type: VentilationType,
        val flowM3h: Double? = null,
        val heatRecoveryEfficiency: Double = 0.0,
    ) : Modification {
        override val description: String get() = "Ventilation : ${type.label}"
    }

    @Serializable
    @SerialName("setpoint")
    data class ChangeSetpoint(val setpointC: Double, val roomIds: List<Long> = emptyList()) : Modification {
        override val description: String
            get() = "Consigne de chauffage à ${setpointC.fr(1)} °C" + if (roomIds.isEmpty()) " (toutes les pièces)" else ""
    }

    @Serializable
    @SerialName("thermal_bridges")
    data class ChangeThermalBridges(val deltaU: Double) : Modification {
        override val description: String get() = "Traitement des ponts thermiques (ΔU = ${deltaU.fr(2)} W/(m²·K))"
    }

    @Serializable
    @SerialName("replace_radiator")
    data class ReplaceRadiator(val radiatorId: Long, val replacement: Radiator) : Modification {
        override val description: String get() = "Remplacement du radiateur ${replacement.code.ifBlank { "#$radiatorId" }}"
    }

    @Serializable
    @SerialName("add_radiator")
    data class AddRadiator(val radiator: Radiator) : Modification {
        override val description: String get() = "Ajout d'un radiateur ${radiator.label}".trim()
    }
}

/** Scénario d'amélioration : liste de modifications + régime d'eau étudié. */
@Serializable
data class Scenario(
    val id: Long = 0,
    val name: String,
    val description: String = "",
    val modifications: List<Modification> = emptyList(),
    /** Régime d'eau étudié dans ce scénario (passage basse température), null : régime de l'étude. */
    val regime: Regime? = null,
)

/** Application des modifications à une copie du bâtiment (l'original n'est jamais modifié). */
object ScenarioEngine {

    fun apply(building: Building, modifications: List<Modification>): Building =
        modifications.fold(building) { b, m -> applyOne(b, m) }

    private fun applyOne(b: Building, m: Modification): Building = when (m) {
        is Modification.AddInsulation -> mapSurfaces(b) { s -> if (matches(s, m)) insulate(s, m) else s }
        is Modification.ReplaceWindows -> mapSurfaces(b) { s ->
            s.copy(openings = s.openings.map { o ->
                val selected = if (m.openingIds.isEmpty()) o.kind.isGlazed else o.id in m.openingIds
                if (selected && o.kind != OpeningKind.DOOR) o.copy(uValue = m.uValue, gValue = m.gValue, uOrigin = ValueOrigin.DECLARED) else o
            })
        }
        is Modification.ReplaceDoors -> mapSurfaces(b) { s ->
            s.copy(openings = s.openings.map { o ->
                val selected = if (m.openingIds.isEmpty()) o.kind == OpeningKind.DOOR && s.boundary == Boundary.EXTERIOR else o.id in m.openingIds
                if (selected) o.copy(uValue = m.uValue, uOrigin = ValueOrigin.DECLARED) else o
            })
        }
        is Modification.ImproveAirtightness -> b.copy(ventilation = b.ventilation.copy(n50 = m.n50, n50Origin = ValueOrigin.ESTIMATED))
        is Modification.ChangeVentilation -> b.copy(ventilation = b.ventilation.copy(
            type = m.type, mechanicalFlowM3h = m.flowM3h, heatRecoveryEfficiency = m.heatRecoveryEfficiency,
        ))
        is Modification.ChangeSetpoint -> b.copy(rooms = b.rooms.map { r ->
            if (m.roomIds.isEmpty() || r.id in m.roomIds) r.copy(setpointC = m.setpointC) else r
        })
        is Modification.ChangeThermalBridges -> b.copy(thermalBridgeDeltaU = m.deltaU)
        is Modification.ReplaceRadiator -> b.copy(radiators = b.radiators.map { r ->
            if (r.id == m.radiatorId) m.replacement.copy(id = r.id, roomId = m.replacement.roomId ?: r.roomId, validated = true) else r
        })
        is Modification.AddRadiator -> {
            val newId = -(b.radiators.count { it.id < 0 } + 1).toLong()
            b.copy(radiators = b.radiators + m.radiator.copy(id = newId, validated = true))
        }
    }

    private fun mapSurfaces(b: Building, f: (Surface) -> Surface): Building =
        b.copy(rooms = b.rooms.map { r -> r.copy(surfaces = r.surfaces.map(f)) })

    private fun matches(s: Surface, m: Modification.AddInsulation): Boolean {
        if (m.surfaceIds.isNotEmpty()) return s.id in m.surfaceIds
        return when (m.target) {
            InsulationTarget.EXTERIOR_WALLS -> s.kind == SurfaceKind.WALL && s.boundary == Boundary.EXTERIOR
            InsulationTarget.WALLS_TO_UNHEATED -> s.kind == SurfaceKind.WALL && s.boundary == Boundary.UNHEATED
            InsulationTarget.TOP_CEILINGS -> (s.kind == SurfaceKind.CEILING || s.kind == SurfaceKind.ROOF) &&
                (s.boundary == Boundary.EXTERIOR || s.boundary == Boundary.UNHEATED)
            InsulationTarget.LOW_FLOORS -> s.kind == SurfaceKind.FLOOR &&
                (s.boundary == Boundary.EXTERIOR || s.boundary == Boundary.UNHEATED || s.boundary == Boundary.GROUND)
            InsulationTarget.SELECTED -> false
        }
    }

    private fun insulate(s: Surface, m: Modification.AddInsulation): Surface {
        val insulation = Layer(material = m.material, thicknessM = m.thicknessM, origin = ValueOrigin.DECLARED)
        val newLayers = when (m.position) {
            InsulationPosition.INTERIOR -> buildList {
                if (m.finish != null) add(Layer(material = m.finish, thicknessM = m.finishThicknessM, origin = ValueOrigin.DECLARED))
                add(insulation)
                addAll(s.layers)
            }
            InsulationPosition.EXTERIOR -> s.layers + insulation
        }
        // Une valeur U imposée ne permet pas d'ajouter une couche : on la convertit en résistance équivalente.
        return if (s.uValueOverride != null && s.layers.isEmpty()) {
            val rAdded = m.thicknessM / m.material.lambda + (m.finish?.let { m.finishThicknessM / it.lambda } ?: 0.0)
            s.copy(uValueOverride = 1.0 / (1.0 / s.uValueOverride + rAdded), uValueOrigin = ValueOrigin.ESTIMATED)
        } else {
            s.copy(layers = newLayers, uValueOverride = null)
        }
    }
}
