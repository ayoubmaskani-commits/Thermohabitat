package fr.thermohabitat.thermal.calc

import com.google.common.truth.Truth.assertThat
import fr.thermohabitat.thermal.model.PanelType
import fr.thermohabitat.thermal.model.Radiator
import fr.thermohabitat.thermal.model.RadiatorType
import fr.thermohabitat.thermal.model.RatingCondition
import fr.thermohabitat.thermal.model.ValueOrigin
import fr.thermohabitat.thermal.model.logMeanTemperatureDifference
import org.junit.Test
import kotlin.math.ln
import kotlin.math.pow

class RadiatorPowerTest {

    @Test
    fun `ecart logarithmique des conditions EN 442`() {
        assertThat(logMeanTemperatureDifference(75.0, 65.0, 20.0)).isWithin(1e-6).of(10.0 / ln(55.0 / 45.0))
        assertThat(RatingCondition.EN442.lmtd).isWithin(0.01).of(49.83)
        assertThat(logMeanTemperatureDifference(50.0, 50.0, 20.0)).isWithin(1e-9).of(30.0)
    }

    @Test
    fun `exemple du salon - radiateur fonte de 3000 W a 55-45 °C`() {
        val p = RadiatorPower.outputAt(3000.0, RatingCondition.EN442, 1.30, 55.0, 45.0, 20.0)
        val expected = 3000.0 * (logMeanTemperatureDifference(55.0, 45.0, 20.0) / RatingCondition.EN442.lmtd).pow(1.30)
        assertThat(p).isWithin(1e-9).of(expected)
        assertThat(p).isWithin(5.0).of(1532.0)
    }

    @Test
    fun `puissance decroissante avec la temperature d'eau`() {
        val values = Regime.STANDARD.map { RadiatorPower.outputAt(1000.0, RatingCondition.EN442, 1.3, it.supplyC, it.returnC, 20.0) }
        assertThat(values).isInStrictOrder(Comparator.reverseOrder<Double>())
        assertThat(values.first()).isWithin(1e-9).of(1000.0)
    }

    @Test
    fun `conversion d'une puissance documentee a 90-70-20`() {
        val r = Radiator(nominalPowerW = 1000.0, nominalPowerOrigin = ValueOrigin.DECLARED, rating = RatingCondition.DIN4703,
            exponent = 1.3, type = RadiatorType.CAST_IRON_COLUMN, documentedUncertaintyPct = 0.0, validated = true)
        val out = RadiatorPower.outputOf(r, 75.0, 65.0, 20.0)
        val expected = 1000.0 * (RatingCondition.EN442.lmtd / RatingCondition.DIN4703.lmtd).pow(1.3)
        assertThat(out.powerW).isWithin(1e-6).of(expected)
        assertThat(out.powerW).isWithin(2.0).of(795.0)
    }

    @Test
    fun `temperature d'eau inferieure a l'ambiance donne une puissance nulle`() {
        assertThat(RadiatorPower.outputAt(1000.0, RatingCondition.EN442, 1.3, 19.0, 15.0, 20.0)).isEqualTo(0.0)
    }

    @Test
    fun `debit impose - bilan energetique respecte`() {
        val nominal = 1500.0
        val flow = RadiatorPower.nominalFlowKgH(nominal, RatingCondition.EN442)
        val (pNom, trNom) = RadiatorPower.outputWithFlow(nominal, RatingCondition.EN442, 1.3, 75.0, 20.0, flow)
        assertThat(pNom).isWithin(1.0).of(nominal)
        assertThat(trNom).isWithin(0.05).of(65.0)
        val (p, tr) = RadiatorPower.outputWithFlow(nominal, RatingCondition.EN442, 1.3, 50.0, 20.0, flow)
        val water = flow / 3600.0 * RadiatorPower.WATER_CP * (50.0 - tr)
        val rad = RadiatorPower.outputAt(nominal, RatingCondition.EN442, 1.3, 50.0, tr, 20.0)
        assertThat(p).isWithin(0.5).of(water)
        assertThat(p).isWithin(0.5).of(rad)
        assertThat(tr).isLessThan(50.0)
        assertThat(tr).isGreaterThan(20.0)
    }

    @Test
    fun `estimation fonte a colonnes avec plage d'incertitude`() {
        val r = Radiator(type = RadiatorType.CAST_IRON_COLUMN, sections = 10, heightMm = 580.0, depthMm = 160.0)
        val e = RadiatorEstimator.estimate(r)!!
        assertThat(e.nominalW).isWithin(1e-6).of(780.0)
        assertThat(e.lowW).isWithin(1e-6).of(585.0)
        assertThat(e.highW).isWithin(1e-6).of(975.0)
        val taller = RadiatorEstimator.estimate(r.copy(heightMm = 980.0))!!
        assertThat(taller.nominalW).isGreaterThan(e.nominalW)
    }

    @Test
    fun `sans profondeur mesuree l'incertitude augmente`() {
        val r = Radiator(type = RadiatorType.CAST_IRON_COLUMN, sections = 10, heightMm = 600.0, columns = 3)
        val e = RadiatorEstimator.estimate(r)!!
        assertThat(e.relativeUncertainty).isWithin(1e-9).of(0.35)
    }

    @Test
    fun `aucune puissance fabriquee sans dimensions ou pour un modele decoratif`() {
        assertThat(RadiatorEstimator.estimate(Radiator(type = RadiatorType.CAST_IRON_COLUMN, sections = 10))).isNull()
        assertThat(RadiatorEstimator.estimate(Radiator(type = RadiatorType.DECORATIVE, heightMm = 1200.0, lengthMm = 500.0))).isNull()
        assertThat(RadiatorEstimator.estimate(Radiator(type = RadiatorType.UNKNOWN, heightMm = 600.0, sections = 8))).isNull()
        val out = RadiatorPower.outputOf(Radiator(type = RadiatorType.UNKNOWN), 55.0, 45.0, 20.0)
        assertThat(out.basis).isEqualTo(PowerBasis.NONE)
        assertThat(out.powerW).isEqualTo(0.0)
    }

    @Test
    fun `panneau acier type 22 600 x 1000`() {
        val r = Radiator(type = RadiatorType.STEEL_PANEL, panelType = PanelType.T22, heightMm = 600.0, lengthMm = 1000.0)
        assertThat(RadiatorEstimator.estimate(r)!!.nominalW).isWithin(1e-6).of(1650.0)
    }

    @Test
    fun `puissance documentee prioritaire sur l'estimation`() {
        val r = Radiator(type = RadiatorType.CAST_IRON_COLUMN, sections = 10, heightMm = 580.0, depthMm = 160.0,
            nominalPowerW = 900.0, nominalPowerOrigin = ValueOrigin.DECLARED, documentedUncertaintyPct = 5.0)
        val n = RadiatorPower.resolveNominal(r)!!
        assertThat(n.basis).isEqualTo(PowerBasis.DOCUMENTED)
        assertThat(n.nominalW).isEqualTo(900.0)
        assertThat(n.lowW).isWithin(1e-9).of(855.0)
    }
}
