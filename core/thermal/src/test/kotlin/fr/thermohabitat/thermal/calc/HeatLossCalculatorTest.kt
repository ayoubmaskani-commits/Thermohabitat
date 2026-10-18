package fr.thermohabitat.thermal.calc

import com.google.common.truth.Truth.assertThat
import fr.thermohabitat.thermal.TestFixtures
import fr.thermohabitat.thermal.library.CompositionTemplates
import fr.thermohabitat.thermal.model.Boundary
import fr.thermohabitat.thermal.model.GroundContact
import fr.thermohabitat.thermal.model.LinearThermalBridge
import fr.thermohabitat.thermal.model.Surface
import fr.thermohabitat.thermal.model.SurfaceKind
import fr.thermohabitat.thermal.model.ValueOrigin
import fr.thermohabitat.thermal.model.VentilationType
import org.junit.Test
import kotlin.math.PI
import kotlin.math.ln

class HeatLossCalculatorTest {

    private val calculator = HeatLossCalculator()

    @Test
    fun `transmission et ventilation d'une piece simple calculees a la main`() {
        val result = calculator.calculate(TestFixtures.simpleBuilding())
        val room = result.rooms.single()
        // ΔT = 20 − (−10) = 30 K ; mur 11,5 m² × 1 + fenêtre 1 m² × 3 = 14,5 W/K
        assertThat(room.transmissionHWk).isWithin(1e-9).of(14.5)
        assertThat(room.transmissionW).isWithin(1e-6).of(435.0)
        // q_inf = 2 × 50 × 10 × 0,02 = 20 m³/h ; q_min = 0,5 × 50 = 25 m³/h → 25 m³/h
        assertThat(room.infiltrationFlowM3h).isWithin(1e-9).of(20.0)
        assertThat(room.minimumFlowM3h).isWithin(1e-9).of(25.0)
        assertThat(room.designFlowM3h).isWithin(1e-9).of(25.0)
        assertThat(room.ventilationW).isWithin(1e-6).of(0.34 * 25.0 * 30.0)
        assertThat(room.totalW).isWithin(1e-6).of(435.0 + 255.0)
        assertThat(room.infiltrationW + room.airRenewalW).isWithin(1e-9).of(room.ventilationW)
    }

    @Test
    fun `forfait de ponts thermiques applique aux parois de l'enveloppe`() {
        val result = calculator.calculate(TestFixtures.simpleBuilding(deltaU = 0.10))
        val bridge = result.rooms.single().elements.single { it.category == LossCategory.THERMAL_BRIDGES }
        assertThat(bridge.hWk).isWithin(1e-9).of((11.5 + 1.0) * 0.10)
        assertThat(result.byCategoryW.getValue(LossCategory.THERMAL_BRIDGES)).isWithin(1e-6).of(1.25 * 30.0)
    }

    @Test
    fun `pont thermique lineique detaille`() {
        val room = TestFixtures.simpleRoom().copy(
            thermalBridges = listOf(LinearThermalBridge(label = "Liaison plancher", lengthM = 5.0, psi = 0.5)),
        )
        val result = calculator.calculate(TestFixtures.simpleBuilding(rooms = listOf(room)))
        assertThat(result.rooms.single().transmissionHWk).isWithin(1e-9).of(14.5 + 2.5)
    }

    @Test
    fun `paroi sur local non chauffe ponderee par b`() {
        val room = TestFixtures.simpleRoom().let { r ->
            r.copy(surfaces = r.surfaces + Surface(
                id = 99, kind = SurfaceKind.CEILING, boundary = Boundary.UNHEATED, uValueOverride = 2.0,
                unheatedFactorB = 0.9,
            ))
        }
        val result = calculator.calculate(TestFixtures.simpleBuilding(rooms = listOf(room)))
        val ceiling = result.rooms.single().elements.single { it.surfaceId == 99L }
        assertThat(ceiling.factor).isEqualTo(0.9)
        assertThat(ceiling.hWk).isWithin(1e-9).of(20.0 * 2.0 * 0.9)
        assertThat(ceiling.towardUnheated).isTrue()
    }

    @Test
    fun `echange avec une piece voisine plus froide et exclusion du bilan global`() {
        val warm = TestFixtures.simpleRoom(id = 1, setpoint = 20.0).let { r ->
            r.copy(surfaces = r.surfaces + Surface(
                id = 50, kind = SurfaceKind.WALL, boundary = Boundary.HEATED_ROOM, lengthM = 4.0, heightM = 2.5,
                uValueOverride = 2.0, adjacentRoomId = 2,
            ))
        }
        val cold = TestFixtures.simpleRoom(id = 2, setpoint = 16.0)
        val result = calculator.calculate(TestFixtures.simpleBuilding(rooms = listOf(warm, cold)))
        val partition = result.room(1)!!.elements.single { it.surfaceId == 50L }
        assertThat(partition.factor).isWithin(1e-9).of(4.0 / 30.0)
        assertThat(partition.powerW).isWithin(1e-6).of(10.0 * 2.0 * 4.0)
        assertThat(partition.isEnvelope).isFalse()
        val envelope = result.rooms.sumOf { r -> r.elements.filter { it.isEnvelope }.sumOf { it.powerW } }
        assertThat(result.envelopeTransmissionW).isWithin(1e-6).of(envelope)
        assertThat(result.sumOfRoomLoadsW).isGreaterThan(result.totalW - 1e-6)
    }

    @Test
    fun `plancher sur terre-plein selon EN ISO 13370 et EN 12831`() {
        val floorLayers = CompositionTemplates.find("dalle_terre_plein")!!.toLayers()
        val room = TestFixtures.simpleRoom().let { r ->
            r.copy(surfaces = r.surfaces + Surface(
                id = 77, kind = SurfaceKind.FLOOR, boundary = Boundary.GROUND, layers = floorLayers,
                ground = GroundContact(exposedPerimeterM = 9.0, wallThicknessM = 0.4),
            ))
        }
        val result = calculator.calculate(TestFixtures.simpleBuilding(rooms = listOf(room)))
        val ground = result.rooms.single().elements.single { it.category == LossCategory.GROUND }
        val bPrime = 20.0 / (0.5 * 9.0)
        val rf = 0.01 / 1.3 + 0.05 / 1.4 + 0.12 / 2.0
        val dt = 0.4 + 2.0 * (0.17 + rf + 0.04)
        val u = 2 * 2.0 / (PI * bPrime + dt) * ln(PI * bPrime / dt + 1)
        assertThat(ground.uValue).isWithin(1e-9).of(u)
        val fg2 = (20.0 - 11.0) / 30.0
        assertThat(ground.factor).isWithin(1e-9).of(1.45 * fg2)
        assertThat(ground.powerW).isWithin(1e-6).of(20.0 * u * 1.45 * (20.0 - 11.0))
    }

    @Test
    fun `ventilation globale du batiment avec reduction des infiltrations simultanees`() {
        val rooms = listOf(TestFixtures.simpleRoom(1), TestFixtures.simpleRoom(2))
        val b = TestFixtures.simpleBuilding(rooms = rooms)
        val result = calculator.calculate(b)
        // Σ q_inf = 40, Σ q_min = 50 → q = max(20, 50) = 50 m³/h
        assertThat(result.ventilationFlowM3h).isWithin(1e-9).of(50.0)
        val leaky = calculator.calculate(b.copy(ventilation = b.ventilation.copy(n50 = 30.0)))
        // Σ q_inf = 120 → 0,5 × 120 = 60 > 50
        assertThat(leaky.ventilationFlowM3h).isWithin(1e-9).of(60.0)
        assertThat(leaky.infiltrationW).isWithin(1e-6).of(0.34 * 60.0 * 30.0)
    }

    @Test
    fun `VMC double flux reduit les deperditions par renouvellement d'air`() {
        val b = TestFixtures.simpleBuilding()
        val natural = calculator.calculate(b)
        val balanced = calculator.calculate(b.copy(ventilation = b.ventilation.copy(
            type = VentilationType.BALANCED, mechanicalFlowM3h = 25.0, heatRecoveryEfficiency = 0.8, n50 = 2.0,
        )))
        assertThat(balanced.ventilationW).isLessThan(natural.ventilationW)
        // q = 0,5·q_inf + 25 × 0,2 ; q_inf = 2 × 50 × 2 × 0,02 = 4
        assertThat(balanced.ventilationFlowM3h).isWithin(1e-9).of(2.0 + 5.0)
    }

    @Test
    fun `paroi sans composition signalee et valeur par defaut prudente`() {
        val room = TestFixtures.simpleRoom().let { r ->
            r.copy(surfaces = r.surfaces + Surface(id = 5, label = "Mur inconnu", kind = SurfaceKind.WALL, lengthM = 4.0))
        }
        val result = calculator.calculate(TestFixtures.simpleBuilding(rooms = listOf(room)))
        assertThat(result.warnings.any { it.contains("Mur inconnu") }).isTrue()
        val element = result.rooms.single().elements.single { it.surfaceId == 5L }
        assertThat(element.uOrigin).isEqualTo(ValueOrigin.UNKNOWN)
        assertThat(result.uncertainShare).isGreaterThan(0.0)
    }

    @Test
    fun `relance proportionnelle a la surface`() {
        val b = TestFixtures.simpleBuilding().copy(reheatFactorWm2 = 11.0)
        val result = calculator.calculate(b)
        assertThat(result.reheatW).isWithin(1e-9).of(220.0)
    }
}
