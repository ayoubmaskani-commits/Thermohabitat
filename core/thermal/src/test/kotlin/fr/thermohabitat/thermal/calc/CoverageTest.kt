package fr.thermohabitat.thermal.calc

import com.google.common.truth.Truth.assertThat
import fr.thermohabitat.thermal.TestFixtures
import fr.thermohabitat.thermal.model.RatingCondition
import org.junit.Test

class CoverageTest {

    private val calculator = HeatLossCalculator()

    @Test
    fun `code couleur selon la couverture`() {
        assertThat(CoverageAnalyzer.statusOf(1000.0, 1200.0, 1050.0, true)).isEqualTo(CoverageStatus.SUFFICIENT)
        assertThat(CoverageAnalyzer.statusOf(1000.0, 1100.0, 900.0, true)).isEqualTo(CoverageStatus.LIMIT)
        assertThat(CoverageAnalyzer.statusOf(1000.0, 950.0, 950.0, true)).isEqualTo(CoverageStatus.LIMIT)
        assertThat(CoverageAnalyzer.statusOf(1000.0, 850.0, 800.0, true)).isEqualTo(CoverageStatus.INSUFFICIENT)
        assertThat(CoverageAnalyzer.statusOf(1000.0, 0.0, 0.0, false)).isEqualTo(CoverageStatus.NO_EMITTER)
        assertThat(CoverageAnalyzer.statusOf(0.0, 0.0, 0.0, false)).isEqualTo(CoverageStatus.NO_NEED)
    }

    @Test
    fun `comparaison besoin et puissance disponible avec deficit`() {
        val b = TestFixtures.simpleBuilding(radiators = listOf(TestFixtures.radiator(1, 1, 1000.0)))
        val loss = calculator.calculate(b)
        val coverage = CoverageAnalyzer.analyze(b, loss, Regime(55.0, 45.0))
        val room = coverage.rooms.single()
        val expected = RadiatorPower.outputAt(1000.0, RatingCondition.EN442, 1.3, 55.0, 45.0, 20.0)
        assertThat(room.availableW).isWithin(1e-6).of(expected)
        assertThat(room.needW).isWithin(1e-6).of(690.0)
        assertThat(room.status).isEqualTo(CoverageStatus.INSUFFICIENT)
        assertThat(room.deficitW).isWithin(1e-6).of(690.0 - expected)
        assertThat(coverage.coveredFraction).isWithin(1e-9).of(expected / 690.0)
    }

    @Test
    fun `les radiateurs non valides sont exclus du bilan`() {
        val b = TestFixtures.simpleBuilding(radiators = listOf(TestFixtures.radiator(1, 1, 3000.0, validated = false)))
        val coverage = CoverageAnalyzer.analyze(b, calculator.calculate(b), Regime(75.0, 65.0))
        val room = coverage.rooms.single()
        assertThat(room.status).isEqualTo(CoverageStatus.NO_EMITTER)
        assertThat(room.pendingRadiators).isEqualTo(1)
        assertThat(coverage.insufficientRooms).hasSize(1)
    }

    @Test
    fun `plusieurs radiateurs dans une meme piece s'additionnent`() {
        val b = TestFixtures.simpleBuilding(radiators = listOf(TestFixtures.radiator(1, 1, 600.0), TestFixtures.radiator(2, 1, 600.0)))
        val coverage = CoverageAnalyzer.analyze(b, calculator.calculate(b), Regime(75.0, 65.0))
        assertThat(coverage.rooms.single().availableW).isWithin(1e-6).of(1200.0)
        assertThat(coverage.rooms.single().status).isEqualTo(CoverageStatus.SUFFICIENT)
    }

    @Test
    fun `temperature de depart minimale atteint exactement le besoin`() {
        val b = TestFixtures.simpleBuilding(radiators = listOf(TestFixtures.radiator(1, 1, 1500.0)))
        val loss = calculator.calculate(b)
        val req = SupplyTemperatureSolver.solve(b, loss, HydraulicMode.FixedDeltaT(10.0))
        val ts = req.requiredSupplyC!!
        val p = RadiatorPower.outputAt(1500.0, RatingCondition.EN442, 1.3, ts, ts - 10.0, 20.0)
        assertThat(p).isWithin(0.5).of(690.0)
        assertThat(ts).isGreaterThan(40.0)
        assertThat(ts).isLessThan(60.0)
        // Une charge partielle (température extérieure plus douce) demande une eau moins chaude.
        val partial = SupplyTemperatureSolver.solve(b, loss, HydraulicMode.FixedDeltaT(10.0), loadFactor = 0.5)
        assertThat(partial.requiredSupplyC!!).isLessThan(ts)
    }

    @Test
    fun `besoin impossible a couvrir sous 90 °C`() {
        val b = TestFixtures.simpleBuilding(radiators = listOf(TestFixtures.radiator(1, 1, 200.0)))
        val req = SupplyTemperatureSolver.solve(b, calculator.calculate(b), HydraulicMode.FixedDeltaT(10.0))
        assertThat(req.requiredSupplyC).isNull()
        assertThat(req.uncoverableRooms).hasSize(1)
    }

    @Test
    fun `mode debit impose - l'ecart depart retour se reduit a charge partielle`() {
        val b = TestFixtures.simpleBuilding(radiators = listOf(TestFixtures.radiator(1, 1, 1500.0)))
        val loss = calculator.calculate(b)
        val flow = SupplyTemperatureSolver.solve(b, loss, HydraulicMode.FixedFlow).requiredSupplyC!!
        val dt5 = SupplyTemperatureSolver.solve(b, loss, HydraulicMode.FixedDeltaT(5.0)).requiredSupplyC!!
        val dt10 = SupplyTemperatureSolver.solve(b, loss, HydraulicMode.FixedDeltaT(10.0)).requiredSupplyC!!
        // Débit nominal (ΔT 10 K à 1500 W) : à 690 W l'eau ne se refroidit que de ≈ 4,6 K.
        assertThat(flow).isLessThan(dt10)
        assertThat(flow).isWithin(0.5).of(dt5)
        val radiator = b.radiators.single()
        val out = CoverageAnalyzer.outputFor(radiator, flow, HydraulicMode.FixedFlow, 20.0)
        assertThat(flow - out.returnC).isWithin(0.3).of(4.6)
    }
}
