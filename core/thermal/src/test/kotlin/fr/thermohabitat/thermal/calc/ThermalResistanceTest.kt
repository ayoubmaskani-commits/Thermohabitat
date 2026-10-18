package fr.thermohabitat.thermal.calc

import com.google.common.truth.Truth.assertThat
import fr.thermohabitat.thermal.TestFixtures
import fr.thermohabitat.thermal.library.CompositionTemplates
import fr.thermohabitat.thermal.library.MaterialLibrary
import fr.thermohabitat.thermal.model.Boundary
import fr.thermohabitat.thermal.model.Layer
import fr.thermohabitat.thermal.model.Surface
import fr.thermohabitat.thermal.model.SurfaceKind
import fr.thermohabitat.thermal.model.ValueOrigin
import org.junit.Test

class ThermalResistanceTest {

    @Test
    fun `surface et volume de la piece`() {
        val room = TestFixtures.simpleRoom()
        assertThat(room.floorAreaM2).isWithin(1e-9).of(20.0)
        assertThat(room.volumeM3).isWithin(1e-9).of(50.0)
        val override = room.copy(floorAreaOverrideM2 = 18.5)
        assertThat(override.volumeM3).isWithin(1e-9).of(46.25)
    }

    @Test
    fun `surface nette d'un mur deduit les baies`() {
        val room = TestFixtures.simpleRoom()
        val wall = room.surfaces.first()
        assertThat(SurfaceGeometry.grossArea(wall, room)).isWithin(1e-9).of(12.5)
        assertThat(SurfaceGeometry.netOpaqueArea(wall, room)).isWithin(1e-9).of(11.5)
        val ceiling = Surface(kind = SurfaceKind.CEILING)
        assertThat(SurfaceGeometry.grossArea(ceiling, room)).isWithin(1e-9).of(20.0)
    }

    @Test
    fun `resistance d'une couche homogene`() {
        val layer = Layer(material = MaterialLibrary.byCode("pise"), thicknessM = 0.5)
        assertThat(ThermalResistance.layerResistance(layer)).isWithin(1e-9).of(0.5 / 1.1)
    }

    @Test
    fun `donnees de couche incoherentes - pas de plantage`() {
        val pise = MaterialLibrary.byCode("pise")
        assertThat(ThermalResistance.layerResistance(Layer(material = pise, thicknessM = -0.1))).isEqualTo(0.0)
        val r = ThermalResistance.layerResistance(Layer(material = pise, thicknessM = 0.1, lambdaOverride = 0.0))
        assertThat(r.isFinite()).isTrue()
        assertThat(r).isWithin(1e-9).of(100.0)
    }

    @Test
    fun `U d'un mur en pise non isole`() {
        val layers = CompositionTemplates.find("pise_50")!!.toLayers()
        // R = 0,02/0,80 + 0,50/1,10 + 0,02/0,80 = 0,50455 ; Rt = 0,13 + R + 0,04
        val rt = ThermalResistance.totalResistance(layers, SurfaceKind.WALL, Boundary.EXTERIOR)
        assertThat(rt).isWithin(1e-4).of(0.13 + 0.025 + 0.5 / 1.1 + 0.025 + 0.04)
        assertThat(ThermalResistance.uValue(layers, SurfaceKind.WALL, Boundary.EXTERIOR)).isWithin(1e-3).of(1.4826)
    }

    @Test
    fun `U d'un mur en pise double de laine de verre 16 cm`() {
        val layers = CompositionTemplates.find("pise_50_lv16")!!.toLayers()
        val expectedR = 0.0125 / 0.25 + 0.16 / 0.035 + 0.5 / 1.1 + 0.02 / 0.8
        val u = ThermalResistance.uValue(layers, SurfaceKind.WALL, Boundary.EXTERIOR)
        assertThat(u).isWithin(1e-6).of(1.0 / (0.13 + expectedR + 0.04))
        assertThat(u).isLessThan(0.20)
    }

    @Test
    fun `resistances superficielles selon le sens du flux et l'espace adjacent`() {
        val layer = listOf(Layer(material = MaterialLibrary.byCode("beton_plein"), thicknessM = 0.2))
        val r = 0.2 / 2.0
        assertThat(ThermalResistance.totalResistance(layer, SurfaceKind.CEILING, Boundary.EXTERIOR)).isWithin(1e-9).of(0.10 + r + 0.04)
        assertThat(ThermalResistance.totalResistance(layer, SurfaceKind.FLOOR, Boundary.UNHEATED)).isWithin(1e-9).of(0.17 + r + 0.17)
        assertThat(ThermalResistance.totalResistance(layer, SurfaceKind.WALL, Boundary.HEATED_ROOM)).isWithin(1e-9).of(0.13 + r + 0.13)
    }

    @Test
    fun `lame d'air non ventilee interpolee selon EN ISO 6946`() {
        assertThat(ThermalResistance.airGapResistance(0.025, HeatFlowDirection.HORIZONTAL)).isWithin(1e-9).of(0.18)
        assertThat(ThermalResistance.airGapResistance(0.020, HeatFlowDirection.HORIZONTAL)).isWithin(1e-9).of(0.175)
        assertThat(ThermalResistance.airGapResistance(0.300, HeatFlowDirection.DOWNWARD)).isWithin(1e-9).of(0.23)
        val air = Layer(material = MaterialLibrary.byCode("lame_air"), thicknessM = 0.05)
        assertThat(ThermalResistance.layerResistance(air, HeatFlowDirection.UPWARD)).isWithin(1e-9).of(0.16)
    }

    @Test
    fun `U saisi prioritaire puis composition puis valeur par defaut`() {
        val override = Surface(uValueOverride = 0.8, uValueOrigin = ValueOrigin.MEASURED)
        assertThat(SurfaceGeometry.resolveU(override).value).isEqualTo(0.8)
        assertThat(SurfaceGeometry.resolveU(override).origin).isEqualTo(ValueOrigin.MEASURED)
        val empty = Surface()
        val resolved = SurfaceGeometry.resolveU(empty)
        assertThat(resolved.fromDefault).isTrue()
        assertThat(resolved.origin).isEqualTo(ValueOrigin.UNKNOWN)
        val estimated = Surface(layers = CompositionTemplates.find("pise_50")!!.toLayers())
        assertThat(SurfaceGeometry.resolveU(estimated).origin).isEqualTo(ValueOrigin.ESTIMATED)
    }

    @Test
    fun `epaisseur d'isolant necessaire pour un U cible`() {
        val e = ThermalResistance.insulationThicknessFor(targetU = 0.25, currentU = 1.5, lambda = 0.035)
        assertThat(e).isWithin(1e-9).of((1 / 0.25 - 1 / 1.5) * 0.035)
    }
}
