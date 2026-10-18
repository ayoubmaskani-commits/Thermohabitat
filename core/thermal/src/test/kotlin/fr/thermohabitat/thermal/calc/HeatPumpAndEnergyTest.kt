package fr.thermohabitat.thermal.calc

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HeatPumpAndEnergyTest {

    @Test
    fun `loi d'eau lineaire et bornee`() {
        val curve = HeatingCurve(designOutdoorC = -10.0, designSupplyC = 55.0, footOutdoorC = 20.0, footSupplyC = 20.0,
            minSupplyC = 25.0, maxSupplyC = 60.0)
        assertThat(curve.supplyAt(-10.0)).isWithin(1e-9).of(55.0)
        assertThat(curve.supplyAt(5.0)).isWithin(1e-9).of(37.5)
        assertThat(curve.supplyAt(18.0)).isWithin(1e-9).of(25.0)
        assertThat(curve.supplyAt(-20.0)).isWithin(1e-9).of(60.0)
    }

    @Test
    fun `COP generique de Carnot plausible et decroissant`() {
        val a7w35 = HeatPumpPerformance.genericCop(7.0, 35.0)
        assertThat(a7w35).isWithin(0.05).of(3.99)
        val am7w55 = HeatPumpPerformance.genericCop(-7.0, 55.0)
        assertThat(am7w55).isLessThan(a7w35)
        assertThat(am7w55).isGreaterThan(1.5)
    }

    @Test
    fun `interpolation des donnees constructeur`() {
        val model = HeatPumpModel(points = listOf(
            HeatPumpPoint(-7.0, 35.0, 6.0, 2.8), HeatPumpPoint(7.0, 35.0, 8.0, 4.6),
            HeatPumpPoint(-7.0, 55.0, 5.0, 2.0), HeatPumpPoint(7.0, 55.0, 7.0, 3.0),
        ))
        val perf = HeatPumpPerformance(model)
        val p = perf.at(0.0, 45.0)
        assertThat(p.generic).isFalse()
        assertThat(p.cop).isWithin(1e-9).of(((2.8 + 4.6) / 2 + (2.0 + 3.0) / 2) / 2)
        assertThat(p.capacityKw!!).isWithin(1e-9).of(((6.0 + 8.0) / 2 + (5.0 + 7.0) / 2) / 2)
        assertThat(perf.at(-15.0, 45.0).extrapolated).isTrue()
    }

    @Test
    fun `point de bivalence ou la puissance de la PAC rejoint la charge`() {
        val model = HeatPumpModel(points = listOf(HeatPumpPoint(-20.0, 45.0, 5.0, 3.0), HeatPumpPoint(20.0, 45.0, 5.0, 3.0)))
        val curve = HeatingCurve(designOutdoorC = -10.0, designSupplyC = 45.0, footSupplyC = 45.0, minSupplyC = 45.0, maxSupplyC = 45.0)
        val result = HeatPumpAnalysis.bivalence(8.0, 20.0, -10.0, curve, HeatPumpPerformance(model), stepK = 0.25)
        // charge(θ) = 8 × (20 − θ)/30 = 5 kW → θ = 1,25 °C
        assertThat(result.bivalentTempC!!).isWithin(0.05).of(1.25)
        assertThat(result.backupAtDesignKw!!).isWithin(1e-9).of(3.0)
        assertThat(result.coversDesignLoad).isFalse()
    }

    @Test
    fun `SCOP saisonnier par classes de temperature`() {
        val curve = HeatingCurve(designOutdoorC = -10.0, designSupplyC = 45.0)
        val s = HeatPumpAnalysis.seasonal(8.0, -10.0, curve, HeatPumpPerformance(HeatPumpModel()))
        assertThat(s.generic).isTrue()
        assertThat(s.scop).isGreaterThan(2.5)
        assertThat(s.scop).isLessThan(6.0)
        assertThat(s.backupHeatKwh).isEqualTo(0.0)
        val hot = HeatPumpAnalysis.seasonal(8.0, -10.0, curve.copy(designSupplyC = 65.0, maxSupplyC = 70.0), HeatPumpPerformance(HeatPumpModel()))
        assertThat(hot.scop).isLessThan(s.scop)
        assertThat(HeatPumpAnalysis.EN14825_AVERAGE_BINS.sumOf { it.second }).isEqualTo(4910)
    }

    @Test
    fun `besoins annuels par degres-jours et consommation fioul`() {
        assertThat(EnergyEstimator.annualNeedKwh(300.0, 2500.0)).isWithin(1e-9).of(18_000.0)
        val settings = EnergySettings(currentCarrier = EnergyCarrier.FUEL_OIL, currentEfficiency = 0.8)
        val e = EnergyEstimator.estimate(300.0, 2500.0, 18.0, settings, heatPumpScop = 3.0)
        val oil = e.carriers.single { it.carrier == EnergyCarrier.FUEL_OIL }
        assertThat(oil.finalEnergyKwh).isWithin(1e-6).of(22_500.0)
        assertThat(oil.quantity).isWithin(1e-6).of(22_500.0 / 9.96)
        val pac = e.carriers.single { it.carrier == EnergyCarrier.HEAT_PUMP }
        assertThat(pac.finalEnergyKwh).isWithin(1e-6).of(6_000.0)
        assertThat(pac.savingsVsCurrentEur).isGreaterThan(0.0)
    }

    @Test
    fun `calage sur la consommation reelle`() {
        val settings = EnergySettings(currentCarrier = EnergyCarrier.FUEL_OIL, currentAnnualConsumption = 2000.0, currentEfficiency = 0.8)
        val e = EnergyEstimator.estimate(300.0, 2500.0, 18.0, settings, heatPumpScop = 3.0)
        val delivered = 2000.0 * 9.96 * 0.8
        assertThat(e.calibration!!.deliveredHeatKwh).isWithin(1e-6).of(delivered)
        assertThat(e.calibration!!.ratio).isWithin(1e-9).of(delivered / 18_000.0)
        assertThat(e.carriers.single { it.carrier == EnergyCarrier.FUEL_OIL }.quantity).isWithin(1e-6).of(2000.0)
    }
}
