package fr.thermohabitat.pro.data.export

import fr.thermohabitat.thermal.StudyResult
import fr.thermohabitat.thermal.calc.CoverageAnalyzer
import fr.thermohabitat.thermal.calc.RadiatorPower
import java.util.Locale

/**
 * Exports tabulaires (séparateur « ; » et virgule décimale, compatibles avec les tableurs français).
 */
object CsvExporter {

    private fun n(v: Double?, d: Int = 0): String = v?.let { String.format(Locale.FRANCE, "%.${d}f", it) } ?: ""

    private fun esc(s: String): String = if (s.contains(';') || s.contains('"') || s.contains('\n')) "\"" + s.replace("\"", "\"\"") + "\"" else s

    private fun line(vararg cells: String) = cells.joinToString(";") { esc(it) }

    /** Inventaire des radiateurs, avec la puissance au régime d'étude. */
    fun radiators(result: StudyResult): String {
        val b = result.building
        val regime = result.studyCoverage
        val sb = StringBuilder("﻿")
        sb.appendLine(line("Code", "Libellé", "Pièce", "Type", "Matériau", "Éléments", "Colonnes", "Hauteur (mm)", "Longueur (mm)", "Profondeur (mm)",
            "Fabricant", "Modèle", "Référence", "Puissance nominale documentée (W)", "Origine", "Conditions", "Puissance retenue (W)", "Borne basse (W)",
            "Borne haute (W)", "Base", "Exposant n", "Validé", "Puissance à ${regime.supplyC.toInt()} °C (W)"))
        b.radiators.forEach { r ->
            val nominal = RadiatorPower.resolveNominal(r)
            val room = b.room(r.roomId)
            // Même mode hydraulique que la couverture des pièces (ΔT constant ou débit nominal conservé).
            val out = CoverageAnalyzer.outputFor(r, regime.supplyC, regime.mode, room?.setpointC ?: 20.0)
            sb.appendLine(line(
                r.code, r.label, room?.name ?: "", r.type.label, r.material.label, r.sections?.toString() ?: "", r.columns?.toString() ?: "",
                n(r.heightMm), n(r.lengthMm), n(r.depthMm), r.manufacturer, r.model, r.reference, n(r.nominalPowerW), r.nominalPowerOrigin.label,
                r.rating.label, n(nominal?.nominalW), n(nominal?.lowW), n(nominal?.highW), nominal?.basis?.label ?: "Inconnue", n(r.effectiveExponent, 2),
                if (r.validated) "oui" else "non", n(out.powerW),
            ))
        }
        return sb.toString()
    }

    /** Bilan pièce par pièce. */
    fun rooms(result: StudyResult): String {
        val sb = StringBuilder("﻿")
        val cov = result.studyCoverage
        sb.appendLine(line("Pièce", "Niveau", "Surface (m²)", "Volume (m³)", "Consigne (°C)", "Transmission (W)", "Ventilation et infiltrations (W)",
            "Relance (W)", "Total (W)", "W/m²", "Disponible à ${cov.supplyC.toInt()} °C (W)", "Couverture", "Statut"))
        result.heatLoss.rooms.forEach { r ->
            val c = cov.rooms.firstOrNull { it.roomId == r.roomId }
            sb.appendLine(line(r.roomName, r.floorLevel.toString(), n(r.floorAreaM2, 1), n(r.volumeM3, 1), n(r.setpointC, 1), n(r.transmissionW),
                n(r.ventilationW), n(r.reheatW), n(r.totalW), n(r.wattsPerM2), n(c?.availableW),
                c?.let { if (it.needW > 0) n(it.availableW / it.needW * 100) + " %" else "" } ?: "", c?.status?.label ?: ""))
        }
        sb.appendLine(line("Total bâtiment", "", n(result.heatLoss.heatedAreaM2, 1), n(result.heatLoss.heatedVolumeM3, 1), "",
            n(result.heatLoss.envelopeTransmissionW), n(result.heatLoss.ventilationW), n(result.heatLoss.reheatW), n(result.heatLoss.totalW),
            n(result.heatLoss.wattsPerM2), n(cov.totalAvailableW), n(cov.coveredFraction * 100) + " %", ""))
        return sb.toString()
    }
}
