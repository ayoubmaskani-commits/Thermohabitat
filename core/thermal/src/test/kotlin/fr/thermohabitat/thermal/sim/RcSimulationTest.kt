package fr.thermohabitat.thermal.sim

import com.google.common.truth.Truth.assertThat
import fr.thermohabitat.thermal.calc.ThermalInertia
import fr.thermohabitat.thermal.library.CompositionTemplates
import fr.thermohabitat.thermal.library.SampleProjects
import org.junit.Test
import kotlin.math.exp

class RcSimulationTest {

    private val simulator = RcSimulator()

    /** Zone quasi monocapacitive : décroissance exponentielle analytique T = Te + (T0 − Te)·e^(−t/τ). */
    private fun singleCapacityZone(hWk: Double = 100.0, cJk: Double = 3.6e6) = ThermalZone(
        id = 1, name = "Z", airCapacityJk = cJk, massCapacityJk = 1.0, hAirOutdoorWk = hWk,
        hAirMassWk = 1e-6, hMassOutdoorWk = 0.0,
    )

    @Test
    fun `refroidissement exponentiel conforme a la solution analytique`() {
        val config = SimulationConfig(durationH = 10.0, timeStepS = 30.0, outdoor = OutdoorProfile.Constant(0.0), initialTempC = 20.0)
        val result = simulator.simulate(listOf(singleCapacityZone()), emptyList(), config)
        val tau = 3.6e6 / 100.0 / 3600.0
        assertThat(tau).isWithin(1e-9).of(10.0)
        val final = result.zones.single().airTempC.last()
        assertThat(final).isWithin(0.05).of(20.0 * exp(-1.0))
        val mid = result.interpolate(result.zones.single().airTempC, 5.0)
        assertThat(mid).isWithin(0.05).of(20.0 * exp(-0.5))
    }

    @Test
    fun `chauffage limite - equilibre a Te + P sur H`() {
        val zone = singleCapacityZone().copy(maxHeatingW = 1000.0, setpointC = 20.0)
        val config = SimulationConfig(durationH = 200.0, timeStepS = 600.0, outdoor = OutdoorProfile.Constant(0.0),
            heatingPeriods = listOf(HeatingPeriod(0.0, 24.0)), initialTempC = 0.0)
        val result = simulator.simulate(listOf(zone), emptyList(), config)
        assertThat(result.zones.single().airTempC.last()).isWithin(0.05).of(10.0)
        assertThat(result.zones.single().heatingW.last()).isWithin(0.5).of(1000.0)
    }

    @Test
    fun `chauffage suffisant - maintien de la consigne`() {
        val zone = singleCapacityZone().copy(maxHeatingW = 5000.0, setpointC = 19.0)
        val config = SimulationConfig(durationH = 12.0, outdoor = OutdoorProfile.Constant(-5.0),
            heatingPeriods = listOf(HeatingPeriod(0.0, 24.0)), initialTempC = 19.0)
        val result = simulator.simulate(listOf(zone), emptyList(), config)
        assertThat(result.zones.single().airTempC.last()).isWithin(0.01).of(19.0)
        assertThat(result.zones.single().heatingW.last()).isWithin(1.0).of(100.0 * 24.0)
    }

    @Test
    fun `periode de chauffage traversant minuit`() {
        val p = HeatingPeriod(22.0, 6.0)
        assertThat(p.contains(23.0)).isTrue()
        assertThat(p.contains(3.0)).isTrue()
        assertThat(p.contains(12.0)).isFalse()
    }

    @Test
    fun `deux zones identiques couplees restent identiques et le couplage egalise`() {
        val a = singleCapacityZone().copy(id = 1)
        val b = singleCapacityZone().copy(id = 2)
        val config = SimulationConfig(durationH = 4.0, outdoor = OutdoorProfile.Constant(0.0), initialTemps = mapOf(1L to 20.0, 2L to 20.0))
        val same = simulator.simulate(listOf(a, b), listOf(ZoneCoupling(1, 2, 50.0)), config)
        assertThat(same.zone(1)!!.airTempC.last()).isWithin(1e-9).of(same.zone(2)!!.airTempC.last())
        val diff = simulator.simulate(listOf(a, b), listOf(ZoneCoupling(1, 2, 500.0)),
            config.copy(initialTemps = mapOf(1L to 25.0, 2L to 15.0)))
        val gapStart = 10.0
        val gapEnd = diff.zone(1)!!.airTempC.last() - diff.zone(2)!!.airTempC.last()
        assertThat(gapEnd).isLessThan(gapStart / 10)
    }

    @Test
    fun `apports solaires uniquement de jour`() {
        assertThat(SolarProfile.CLEAR_WINTER.irradiance(fr.thermohabitat.thermal.model.Orientation.S, 2.0)).isEqualTo(0.0)
        assertThat(SolarProfile.CLEAR_WINTER.irradiance(fr.thermohabitat.thermal.model.Orientation.S, 12.75)).isWithin(1e-9).of(650.0)
        assertThat(SolarProfile.NONE.irradiance(fr.thermohabitat.thermal.model.Orientation.S, 12.0)).isEqualTo(0.0)
    }

    @Test
    fun `capacite effective selon la regle des 10 cm`() {
        val bare = CompositionTemplates.find("pise_50")!!.toLayers()
        // enduit chaux 2 cm (1600 × 1000) + 8 cm de pisé (1900 × 1000)
        assertThat(ThermalInertia.arealHeatCapacity(bare)).isWithin(1.0).of(0.02 * 1.6e6 + 0.08 * 1.9e6)
        val insulated = CompositionTemplates.find("pise_50_lv16")!!.toLayers()
        // seule la plaque de plâtre compte : l'isolant intérieur masque l'inertie du pisé
        assertThat(ThermalInertia.arealHeatCapacity(insulated)).isWithin(1.0).of(0.0125 * 850.0 * 1000.0)
    }

    @Test
    fun `l'isolation interieure accelere le refroidissement relatif`() {
        val building = SampleProjects.pise1870()
        val model = ZoneModelBuilder.build(building, singleZone = true)
        val zone = model.zones.single()
        assertThat(zone.timeConstantH).isGreaterThan(5.0)
        assertThat(zone.timeConstantH).isLessThan(500.0)
        val config = SimulationConfig(durationH = 8.0, startClockHour = 22.0, outdoor = OutdoorProfile.Constant(0.0), initialTempC = 20.0)
        val result = simulator.simulate(model.zones, model.couplings, config)
        val end = result.zones.single().airTempC.last()
        assertThat(end).isLessThan(20.0)
        assertThat(end).isGreaterThan(0.0)
        val withUncertainty = simulator.simulateWithUncertainty(model.zones, model.couplings, config,
            model.capacityUncertainty, model.conductanceUncertainty)
        val low = withUncertainty.lowAir.getValue(0L).last()
        val high = withUncertainty.highAir.getValue(0L).last()
        assertThat(low).isAtMost(end)
        assertThat(high).isAtLeast(end)
    }

    @Test
    fun `calage retrouve la capacite d'un jeu de mesures synthetiques`() {
        val zone = ThermalZone(id = 1, name = "Z", airCapacityJk = 5e5, massCapacityJk = 2e7, hAirOutdoorWk = 60.0,
            hAirMassWk = 900.0, hMassOutdoorWk = 80.0)
        val model = ZoneModel(listOf(zone), emptyList(), 0.3, 0.2, emptyList())
        val config = SimulationConfig(durationH = 10.0, outdoor = OutdoorProfile.Constant(2.0), initialTempC = 20.0)
        val truth = simulator.simulate(listOf(zone.scaled(1.8, 1.0)), emptyList(), config)
        val measures = (1..10).map { h -> MeasuredPoint(h.toDouble(), truth.interpolate(truth.zones.single().airTempC, h.toDouble())) }
        val cal = Calibrator.calibrate(model, config, measures)
        assertThat(cal.capacityFactor).isWithin(0.05).of(1.8)
        assertThat(cal.rmseAfterC).isLessThan(cal.rmseBeforeC)
        assertThat(cal.rmseAfterC).isLessThan(0.02)
    }

    @Test
    fun `calage capacite et deperditions - les deux facteurs sont retrouves`() {
        val model = ZoneModelBuilder.build(SampleProjects.pise1870(), singleZone = true)
        val config = SimulationConfig(durationH = 10.0, outdoor = OutdoorProfile.Constant(2.0), initialTempC = 20.0)
        val truth = simulator.simulate(model.zones.map { it.scaled(1.0, 1.5) }, emptyList(), config)
        val measures = (1..10).map { h -> MeasuredPoint(h.toDouble(), truth.interpolate(truth.zones.single().airTempC, h.toDouble())) }
        val cal = Calibrator.calibrate(model, config, measures, fitConductance = true)
        assertThat(cal.capacityFactor).isWithin(0.02).of(1.0)
        assertThat(cal.conductanceFactor).isWithin(0.03).of(1.5)
        assertThat(cal.rmseAfterC).isLessThan(0.01)
    }

    @Test
    fun `maison entiere - meme H permanent que le modele multizone`() {
        val building = SampleProjects.pise1870()
        val multi = ZoneModelBuilder.build(building)
        val single = ZoneModelBuilder.build(building, singleZone = true).zones.single()
        assertThat(single.steadyStateHWk).isWithin(1e-6).of(multi.zones.sumOf { it.steadyStateHWk })
    }

    @Test
    fun `petite piece tres deperditive - H opaque conserve`() {
        // Pièce sans composition (A_m = surface au sol) : H opaque supérieur à 0,95·H_ms.
        val wc = fr.thermohabitat.thermal.model.Room(1, "WC", 0, 1.2, 2.0, 2.5, surfaces = listOf(
            fr.thermohabitat.thermal.model.Surface(id = 10, kind = fr.thermohabitat.thermal.model.SurfaceKind.WALL,
                boundary = fr.thermohabitat.thermal.model.Boundary.EXTERIOR, lengthM = 2.0, uValueOverride = 2.5),
            fr.thermohabitat.thermal.model.Surface(id = 11, kind = fr.thermohabitat.thermal.model.SurfaceKind.WALL,
                boundary = fr.thermohabitat.thermal.model.Boundary.EXTERIOR, lengthM = 1.2, uValueOverride = 2.5),
            fr.thermohabitat.thermal.model.Surface(id = 12, kind = fr.thermohabitat.thermal.model.SurfaceKind.CEILING,
                boundary = fr.thermohabitat.thermal.model.Boundary.EXTERIOR, uValueOverride = 3.0),
        ))
        val building = fr.thermohabitat.thermal.TestFixtures.simpleBuilding(rooms = listOf(wc))
        val hl = fr.thermohabitat.thermal.calc.HeatLossCalculator().calculate(building)
        val zone = ZoneModelBuilder.build(building, hl).zones.single()
        val opaque = hl.rooms.single().elements.sumOf { it.hWk }
        assertThat(opaque).isGreaterThan(0.95 * zone.hAirMassWk)
        assertThat(zone.steadyStateHWk).isWithin(1e-6).of(opaque + hl.ventilationHWk)
    }

    @Test
    fun `couplage entre pieces - mur et porte additionnes, paroi saisie des deux cotes comptee une fois`() {
        fun wall(id: Long, adj: Long) = fr.thermohabitat.thermal.model.Surface(
            id = id, kind = fr.thermohabitat.thermal.model.SurfaceKind.WALL, boundary = fr.thermohabitat.thermal.model.Boundary.HEATED_ROOM,
            lengthM = 4.0, heightM = 2.5, adjacentRoomId = adj, uValueOverride = 2.0,
            openings = listOf(fr.thermohabitat.thermal.model.Opening(id = id * 10, kind = fr.thermohabitat.thermal.model.OpeningKind.DOOR,
                widthM = 0.83, heightM = 2.04, uValue = 3.5)),
        )
        val a = fr.thermohabitat.thermal.model.Room(1, "A", 0, 4.0, 4.0, 2.5, setpointC = 20.0, surfaces = listOf(wall(10, 2)))
        val b = fr.thermohabitat.thermal.model.Room(2, "B", 0, 4.0, 4.0, 2.5, setpointC = 16.0)
        val expected = (10.0 - 0.83 * 2.04) * 2.0 + 0.83 * 2.04 * 3.5
        val one = ZoneModelBuilder.build(fr.thermohabitat.thermal.TestFixtures.simpleBuilding(rooms = listOf(a, b))).couplings.single()
        assertThat(one.hWk).isWithin(1e-6).of(expected)
        val mirrored = ZoneModelBuilder.build(fr.thermohabitat.thermal.TestFixtures.simpleBuilding(
            rooms = listOf(a, b.copy(surfaces = listOf(wall(20, 1)))))).couplings.single()
        assertThat(mirrored.hWk).isWithin(1e-6).of(expected)
    }
}
