package fr.thermohabitat.thermal

import com.google.common.truth.Truth.assertThat
import fr.thermohabitat.thermal.calc.CoverageAnalyzer
import fr.thermohabitat.thermal.calc.CoverageStatus
import fr.thermohabitat.thermal.calc.EnergyCarrier
import fr.thermohabitat.thermal.calc.EnergyEstimator
import fr.thermohabitat.thermal.calc.EnergySettings
import fr.thermohabitat.thermal.calc.HeatLossCalculator
import fr.thermohabitat.thermal.calc.HeatPumpAnalysis
import fr.thermohabitat.thermal.calc.HeatPumpModel
import fr.thermohabitat.thermal.calc.HeatPumpPerformance
import fr.thermohabitat.thermal.calc.HeatPumpPoint
import fr.thermohabitat.thermal.calc.HeatingCurve
import fr.thermohabitat.thermal.calc.LossCategory
import fr.thermohabitat.thermal.calc.RadiatorPower
import fr.thermohabitat.thermal.calc.Regime
import fr.thermohabitat.thermal.calc.SurfaceGeometry
import fr.thermohabitat.thermal.library.CompositionTemplates
import fr.thermohabitat.thermal.library.MaterialLibrary
import fr.thermohabitat.thermal.model.Boundary
import fr.thermohabitat.thermal.model.GroundContact
import fr.thermohabitat.thermal.model.Opening
import fr.thermohabitat.thermal.model.OpeningKind
import fr.thermohabitat.thermal.model.RadiatorType
import fr.thermohabitat.thermal.model.Room
import fr.thermohabitat.thermal.model.Surface
import fr.thermohabitat.thermal.model.SurfaceKind
import fr.thermohabitat.thermal.model.ValueOrigin
import fr.thermohabitat.thermal.scenario.InsulationPosition
import fr.thermohabitat.thermal.scenario.InsulationTarget
import fr.thermohabitat.thermal.scenario.Modification
import fr.thermohabitat.thermal.scenario.Scenario
import fr.thermohabitat.thermal.scenario.ScenarioEngine
import org.junit.Test

/** Cas limites et incohérences relevés lors de la revue du moteur. */
class ReviewFixesTest {

    private val analyzer = StudyAnalyzer()

    // --- Loi d'eau et PAC ---

    @Test
    fun `loi d'eau avec bornes inversees - pas d'exception`() {
        val curve = HeatingCurve(minSupplyC = 70.0, maxSupplyC = 65.0)
        assertThat(curve.supplyAt(0.0)).isIn(com.google.common.collect.Range.closed(65.0, 70.0))
        // L'analyse complète ne doit pas planter non plus.
        val b = TestFixtures.simpleBuilding()
        analyzer.analyze(b, StudySettings(heatingCurve = HeatingCurve(maxSupplyC = 5.0)))
    }

    @Test
    fun `COP constructeur unique transpose dans le sens defavorable`() {
        val perf = HeatPumpPerformance(HeatPumpModel(points = listOf(HeatPumpPoint(7.0, 35.0, cop = 4.6))))
        val cold = perf.at(-7.0, 55.0)
        assertThat(cold.extrapolated).isTrue()
        val ratio = HeatPumpPerformance.carnotCop(-7.0, 55.0) / HeatPumpPerformance.carnotCop(7.0, 35.0)
        assertThat(cold.cop).isWithin(1e-9).of(4.6 * ratio)
        assertThat(cold.cop).isLessThan(3.0)
        // Plus favorable que la fiche : valeur bornée (prudent).
        assertThat(perf.at(12.0, 30.0).cop).isWithin(1e-9).of(4.6)
        val seasonal = HeatPumpAnalysis.seasonal(8.0, -10.0, HeatingCurve(designOutdoorC = -10.0, designSupplyC = 55.0), perf)
        assertThat(seasonal.extrapolated).isTrue()
        assertThat(seasonal.scop).isLessThan(4.0)
    }

    @Test
    fun `puissance A7 seule degradee par grand froid`() {
        val model = HeatPumpModel(points = listOf(HeatPumpPoint(7.0, 35.0, capacityKw = 8.0), HeatPumpPoint(7.0, 55.0, capacityKw = 7.5)))
        val perf = HeatPumpPerformance(model)
        val p = perf.at(-10.0, 58.9)
        assertThat(p.extrapolated).isTrue()
        assertThat(p.capacityKw!!).isWithin(1e-6).of(7.5 * (1 + 0.025 * -17.0) * (1 - 0.005 * 3.9))
        val curve = HeatingCurve(designOutdoorC = -10.0, designSupplyC = 55.0)
        val bv = HeatPumpAnalysis.bivalence(7.0, 20.0, -10.0, curve, perf)
        assertThat(bv.coversDesignLoad).isFalse()
        assertThat(bv.capacityExtrapolated).isTrue()
    }

    @Test
    fun `puissance PAC inconnue signalee`() {
        val s = HeatPumpAnalysis.seasonal(8.0, -10.0, HeatingCurve(), HeatPumpPerformance(HeatPumpModel()))
        assertThat(s.capacityKnown).isFalse()
    }

    // --- Énergie et scénarios ---

    @Test
    fun `calage applique aux scenarios - les travaux reduisent le cout`() {
        val b = TestFixtures.simpleBuilding()
        val settings = StudySettings(energy = EnergySettings(currentCarrier = EnergyCarrier.FUEL_OIL, currentAnnualConsumption = 300.0, currentEfficiency = 0.8))
        val cmp = ScenarioComparison.of(analyzer, b, settings, Scenario(name = "Fenêtres", modifications = listOf(Modification.ReplaceWindows(0.8))))
        assertThat(cmp.before.energy.calibratedNeedKwh!!).isWithin(1e-6).of(300.0 * 9.96 * 0.8)
        val ratio = cmp.after.energy.annualNeedKwh / cmp.before.energy.annualNeedKwh
        assertThat(ratio).isLessThan(1.0)
        assertThat(cmp.after.energy.calibratedNeedKwh!!).isWithin(1e-6).of(cmp.before.energy.calibratedNeedKwh!! * ratio)
        fun oil(r: StudyResult) = r.energy.carriers.single { it.carrier == EnergyCarrier.FUEL_OIL }.costEur
        assertThat(oil(cmp.after)).isLessThan(oil(cmp.before))
    }

    @Test
    fun `baisse de consigne - DJU corriges et besoins reduits`() {
        val b = TestFixtures.simpleBuilding()
        val before = analyzer.analyze(b, StudySettings())
        assertThat(before.energy.degreeDays).isWithin(1e-9).of(2500.0)
        val after = analyzer.analyzeScenario(b, StudySettings(), Scenario(name = "17 °C", modifications = listOf(Modification.ChangeSetpoint(17.0))))
        val days = HeatPumpAnalysis.EN14825_AVERAGE_BINS.sumOf { it.second } / 24.0
        assertThat(after.energy.degreeDays).isWithin(1e-6).of(2500.0 - 3.0 * days)
        assertThat(after.energy.annualNeedKwh).isLessThan(before.energy.annualNeedKwh * 0.8)
    }

    @Test
    fun `regime du scenario applique a la loi d'eau de la PAC`() {
        val b = TestFixtures.simpleBuilding()
        val s = StudySettings()
        val before = analyzer.analyze(b, s)
        val low = analyzer.analyzeScenario(b, s, Scenario(name = "BT", regime = Regime(45.0, 35.0)))
        assertThat(low.seasonal!!.scop).isGreaterThan(before.seasonal!!.scop)
        val same = analyzer.analyzeScenario(b, s, Scenario(name = "Idem", regime = s.studyRegime))
        assertThat(same.seasonal!!.scop).isWithin(1e-12).of(before.seasonal!!.scop)
    }

    @Test
    fun `PAC actuelle - consommation constatee conservee`() {
        val settings = EnergySettings(currentCarrier = EnergyCarrier.HEAT_PUMP, currentAnnualConsumption = 4000.0, currentEfficiency = 2.5)
        val e = EnergyEstimator.estimate(300.0, 2500.0, 18.0, settings, heatPumpScop = 3.5)
        assertThat(e.carriers.single { it.carrier == EnergyCarrier.HEAT_PUMP }.finalEnergyKwh).isWithin(1e-6).of(4000.0)
    }

    @Test
    fun `isolation d'un mur a U impose part du U reellement utilise`() {
        val wall = Surface(
            id = 10, kind = SurfaceKind.WALL, boundary = Boundary.EXTERIOR, lengthM = 5.0, heightM = 2.5,
            layers = CompositionTemplates.find("pierre_50")!!.toLayers(), uValueOverride = 0.45, uValueOrigin = ValueOrigin.MEASURED,
        )
        val bare = Surface(id = 11, kind = SurfaceKind.WALL, boundary = Boundary.EXTERIOR, lengthM = 4.0, heightM = 2.5)
        val room = Room(1, "Pièce", 0, 5.0, 4.0, 2.5, surfaces = listOf(wall, bare))
        val b = TestFixtures.simpleBuilding(rooms = listOf(room))
        val m = Modification.AddInsulation(InsulationTarget.EXTERIOR_WALLS, MaterialLibrary.byCode("fibre_bois_rigide"), 0.04,
            InsulationPosition.INTERIOR, finish = MaterialLibrary.byCode("ba13"))
        val after = ScenarioEngine.apply(b, listOf(m)).rooms.single().surfaces
        val uWall = SurfaceGeometry.resolveU(after.single { it.id == 10L }).value
        assertThat(uWall).isWithin(0.002).of(1.0 / (1.0 / 0.45 + 0.04 / 0.043 + 0.0125 / 0.25))
        val bareAfter = SurfaceGeometry.resolveU(after.single { it.id == 11L })
        assertThat(bareAfter.value).isWithin(0.002).of(1.0 / (1.0 / 2.0 + 0.04 / 0.043 + 0.0125 / 0.25))
        assertThat(bareAfter.origin).isEqualTo(ValueOrigin.UNKNOWN)
    }

    // --- Couverture ---

    @Test
    fun `radiateur valide sans puissance etablie - en attente, pas 0 W`() {
        val r = TestFixtures.radiator(1, 1, 1000.0).copy(type = RadiatorType.CONVECTOR, nominalPowerW = null)
        assertThat(RadiatorPower.countsInStudy(r)).isFalse()
        val b = TestFixtures.simpleBuilding(radiators = listOf(r))
        val hl = HeatLossCalculator().calculate(b)
        val room = CoverageAnalyzer.analyze(b, hl, Regime(55.0, 45.0)).rooms.single()
        assertThat(room.status).isEqualTo(CoverageStatus.NO_EMITTER)
        assertThat(room.pendingRadiators).isEqualTo(1)
        assertThat(analyzer.analyze(b, StudySettings()).compatibility).isEqualTo(PacCompatibility.UNDETERMINED)
    }

    // --- Sol ---

    private fun groundFloor(id: Long, perimeter: Double?) = Surface(
        id = id, kind = SurfaceKind.FLOOR, boundary = Boundary.GROUND,
        layers = CompositionTemplates.find("dalle_terre_plein")!!.toLayers(),
        ground = perimeter?.let { GroundContact(exposedPerimeterM = it) },
    )

    @Test
    fun `mur declare terre-plein - B' inchange et baies comptees`() {
        val floor = groundFloor(20, 18.0)
        val base = TestFixtures.simpleBuilding(rooms = listOf(Room(1, "Séjour", 0, 5.0, 4.0, 2.5, surfaces = listOf(floor))))
        val calc = HeatLossCalculator()
        val u0 = calc.groundEquivalentU(base, floor)!!
        val buried = Surface(id = 21, kind = SurfaceKind.WALL, boundary = Boundary.GROUND, lengthM = 5.0, heightM = 2.5, uValueOverride = 1.0,
            openings = listOf(Opening(id = 210, kind = OpeningKind.WINDOW, widthM = 1.0, heightM = 1.0, uValue = 3.0)))
        val withWall = base.copy(rooms = listOf(base.rooms.single().copy(surfaces = listOf(floor, buried))))
        assertThat(calc.groundEquivalentU(withWall, floor)!!).isWithin(1e-12).of(u0)
        val hl = calc.calculate(withWall)
        val window = hl.rooms.single().elements.single { it.openingId == 210L }
        assertThat(window.category).isEqualTo(LossCategory.WINDOWS)
        assertThat(window.hWk).isWithin(1e-9).of(3.0)
        assertThat(hl.warnings.any { it.contains("n'est calculé que pour les planchers") }).isTrue()
    }

    @Test
    fun `perimetre expose manquant signale`() {
        val rooms = listOf(
            Room(1, "Séjour", 0, 5.0, 4.0, 2.5, surfaces = listOf(groundFloor(20, 18.0))),
            Room(2, "Bureau", 0, 4.0, 5.0, 2.5, surfaces = listOf(groundFloor(30, null))),
        )
        val hl = HeatLossCalculator().calculate(TestFixtures.simpleBuilding(rooms = rooms))
        assertThat(hl.warnings.any { it.contains("Périmètre exposé non renseigné pour Bureau") }).isTrue()
    }

    @Test
    fun `plancher sur terre-plein sans composition - avertissement exact`() {
        val floor = Surface(id = 20, kind = SurfaceKind.FLOOR, boundary = Boundary.GROUND, ground = GroundContact(exposedPerimeterM = 18.0))
        val b = TestFixtures.simpleBuilding(rooms = listOf(Room(1, "Séjour", 0, 5.0, 4.0, 2.5, surfaces = listOf(floor))))
        val hl = HeatLossCalculator().calculate(b)
        assertThat(hl.warnings.none { it.contains("U par défaut") }).isTrue()
        assertThat(hl.warnings.any { it.contains("Rf = 0 supposé") }).isTrue()
    }
}
