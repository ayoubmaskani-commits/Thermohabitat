package fr.thermohabitat.pro.data.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.thermohabitat.pro.data.db.PhotoOwner
import fr.thermohabitat.pro.data.files.PhotoStorage
import fr.thermohabitat.pro.data.repository.Project
import fr.thermohabitat.thermal.ScenarioComparison
import fr.thermohabitat.thermal.StudyAnalyzer
import fr.thermohabitat.thermal.StudyResult
import fr.thermohabitat.thermal.calc.BuildingSupplyRequirement
import fr.thermohabitat.thermal.calc.CoverageStatus
import fr.thermohabitat.thermal.calc.CoverageAnalyzer
import fr.thermohabitat.thermal.calc.HeatPumpAnalysis
import fr.thermohabitat.thermal.calc.HeatPumpPerformance
import fr.thermohabitat.thermal.calc.LossCategory
import fr.thermohabitat.thermal.calc.RadiatorPower
import fr.thermohabitat.thermal.calc.Regime
import fr.thermohabitat.thermal.calc.SurfaceGeometry
import fr.thermohabitat.thermal.scenario.Scenario
import fr.thermohabitat.thermal.sim.OutdoorProfile
import fr.thermohabitat.thermal.sim.RcSimulator
import fr.thermohabitat.thermal.sim.SimulationConfig
import fr.thermohabitat.thermal.sim.ZoneModelBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Sections incluses dans le rapport. */
enum class ReportSection(val label: String) {
    BUILDING("Présentation du bâtiment"),
    ROOMS("Caractéristiques des pièces"),
    ENVELOPE("Matériaux et isolation"),
    HEAT_LOSS("Calcul des déperditions"),
    RADIATORS("Radiateurs : photos, identification et puissances"),
    COMPARISON("Comparaison avec les besoins"),
    HEAT_PUMP("Compatibilité avec une pompe à chaleur"),
    DYNAMIC("Graphique de température (refroidissement)"),
    SCENARIOS("Scénarios d'amélioration"),
    ENERGY("Estimations énergétiques"),
    ASSUMPTIONS("Hypothèses et avertissements"),
}

/**
 * Rapport PDF généré sur le téléphone avec l'API Android [PdfDocument] (format A4, 72 points/pouce).
 */
@Singleton
class PdfReportGenerator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val photoStorage: PhotoStorage,
    private val analyzer: StudyAnalyzer,
) {
    suspend fun generate(
        project: Project,
        result: StudyResult,
        scenarios: List<Scenario>,
        sections: Set<ReportSection>,
    ): File = withContext(Dispatchers.Default) {
        val dir = File(context.cacheDir, fr.thermohabitat.pro.data.files.PhotoStorage.EXPORTS_DIR).apply { mkdirs() }
        val file = File(dir, "Rapport_${BackupManager.safeName(project.entity.name)}.pdf")
        val doc = PdfDocument()
        val w = Writer(doc, project.entity.name)
        try {
            cover(w, project, result)
            if (ReportSection.BUILDING in sections) building(w, project, result)
            if (ReportSection.ROOMS in sections) rooms(w, result)
            if (ReportSection.ENVELOPE in sections) envelope(w, project, result)
            if (ReportSection.HEAT_LOSS in sections) heatLoss(w, result)
            if (ReportSection.RADIATORS in sections) radiators(w, project, result)
            if (ReportSection.COMPARISON in sections) comparison(w, result)
            if (ReportSection.HEAT_PUMP in sections) heatPump(w, project, result)
            if (ReportSection.DYNAMIC in sections) dynamic(w, project, result)
            if (ReportSection.SCENARIOS in sections) scenarios(w, project, result, scenarios)
            if (ReportSection.ENERGY in sections) energy(w, result)
            if (ReportSection.ASSUMPTIONS in sections) assumptions(w, result)
            w.finish()
            FileOutputStream(file).use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
        file
    }

    // ------------------------------------------------------------------ Sections

    private fun cover(w: Writer, p: Project, r: StudyResult) {
        w.title("Étude thermique – ${p.entity.name}")
        w.paragraph(listOfNotNull(p.entity.address.ifBlank { null }, p.entity.commune.ifBlank { null }).joinToString(" – "))
        w.paragraph("Rapport généré le ${DateFormat.getDateInstance(DateFormat.LONG, Locale.FRANCE).format(Date())} par ThermoHabitat Pro.")
        w.space(6f)
        w.keyValues(listOf(
            "Surface chauffée" to a(r.heatLoss.heatedAreaM2) ,
            "Volume chauffé" to "${n(r.heatLoss.heatedVolumeM3, 0)} m³",
            "Température extérieure de base" to "${n(r.heatLoss.designOutdoorC, 1)} °C",
            "Puissance de chauffage nécessaire" to "${n(r.heatLoss.totalW / 1000, 2)} kW (${n(r.heatLoss.wattsPerM2, 0)} W/m²)",
            "Coefficient de déperdition H" to "${n(r.heatLoss.totalHWk, 1)} W/K",
            "Radiateurs validés" to "${r.validatedRadiatorCount} / ${r.radiatorCount}",
            "Besoins couverts à ${r.studyCoverage.supplyC.toInt()} °C" to pct(r.studyCoverage.coveredFraction),
            "Température de départ nécessaire" to supplyText(r.supply),
            "Compatibilité PAC estimée" to r.compatibility.label,
        ))
        w.note("Étude indicative fondée sur les principes de la NF EN 12831-1. Les valeurs estimées ou inconnues sont signalées ; " +
            "ce document ne constitue ni une étude réglementaire ni un dimensionnement certifié.")
    }

    private fun building(w: Writer, p: Project, r: StudyResult) {
        val e = p.entity
        val b = p.building
        w.heading("1. Présentation du bâtiment")
        w.keyValues(listOf(
            "Année de construction" to (e.constructionYear?.toString() ?: "inconnue"),
            "Type de construction" to b.constructionType.label,
            "Murs" to b.wallType.label,
            "Isolation" to b.insulationType.label,
            "Menuiseries" to b.joineryType.label,
            "Chauffage existant" to b.existingHeating.label,
            "Niveaux / hauteur sous plafond" to "${b.floorsCount} / ${n(b.ceilingHeightM, 2)} m",
            "Surface habitable déclarée" to (b.declaredLivingAreaM2?.let { a(it) } ?: "–"),
            "Climat de référence" to "${b.climate.referenceName.ifBlank { "saisie manuelle" }} – altitude ${n(b.climate.altitudeM, 0)} m",
            "θe / θm,e / DJU" to "${n(b.climate.designOutdoorTempC, 1)} °C (${b.climate.designOutdoorOrigin.label.lowercase()}) / ${n(b.climate.meanAnnualOutdoorTempC, 1)} °C / ${n(b.climate.degreeDays, 0)}",
            "Ventilation" to b.ventilation.type.label,
            "Étanchéité n50" to "${n(b.ventilation.n50, 1)} h⁻¹ (${b.ventilation.n50Origin.label.lowercase()})",
            "Ponts thermiques ΔU_TB" to "${n(b.thermalBridgeDeltaU, 2)} W/(m²·K)",
        ))
        if (e.notes.isNotBlank()) w.paragraph("Notes : ${e.notes}")
    }

    private fun rooms(w: Writer, r: StudyResult) {
        w.heading("2. Caractéristiques des pièces")
        w.table(
            listOf("Pièce", "Niveau", "Surface", "Volume", "Consigne", "Parois"),
            r.building.rooms.map { room -> listOf(room.name, room.floorLevel.toString(), a(room.floorAreaM2), "${n(room.volumeM3, 1)} m³",
                "${n(room.setpointC, 1)} °C", room.surfaces.size.toString()) },
            floatArrayOf(0.30f, 0.1f, 0.15f, 0.15f, 0.15f, 0.15f),
        )
    }

    private fun envelope(w: Writer, p: Project, r: StudyResult) {
        w.heading("3. Matériaux et isolation")
        val b = p.building
        for (room in b.rooms) {
            w.subheading(room.name)
            val elements = r.heatLoss.rooms.firstOrNull { it.roomId == room.id }?.elements.orEmpty()
            val rows = room.surfaces.map { s ->
                // U réellement retenu par le bilan (U équivalent EN ISO 13370 pour un terre-plein).
                val resolved = SurfaceGeometry.resolveU(s)
                val el = elements.firstOrNull { it.surfaceId == s.id && it.openingId == null }
                val uValue = el?.uValue ?: resolved.value
                val uOrigin = el?.uOrigin ?: resolved.origin
                val equivalent = el?.category == LossCategory.GROUND
                val comp = if (s.layers.isEmpty()) (if (s.uValueOverride != null) "U saisi" else "non renseignée")
                else s.layers.joinToString(" + ") { "${it.material.name} ${n(it.thicknessM * 100, 1)} cm" }
                listOf(s.label.ifBlank { s.kind.label }, s.boundary.label, a(SurfaceGeometry.netOpaqueArea(s, room)), comp,
                    "${n(uValue, 2)}${if (equivalent) " éq." else ""} (${uOrigin.label.lowercase()})")
            }
            w.table(listOf("Paroi", "Donne sur", "Surface nette", "Composition (int. → ext.)", "U"), rows, floatArrayOf(0.17f, 0.15f, 0.12f, 0.40f, 0.16f))
            val openings = room.surfaces.flatMap { s -> s.openings.map { s to it } }
            if (openings.isNotEmpty()) {
                w.table(listOf("Baie", "Paroi", "Dimensions", "U", "Origine"),
                    openings.map { (s, o) -> listOf(o.label.ifBlank { o.kind.label }, s.label.ifBlank { s.kind.label },
                        "${n(o.widthM, 2)} × ${n(o.heightM, 2)} m ×${o.count}", n(o.uValue, 2), o.uOrigin.label) },
                    floatArrayOf(0.25f, 0.25f, 0.22f, 0.12f, 0.16f))
            }
        }
        val used = b.rooms.flatMap { r -> r.surfaces.flatMap { s -> s.layers.map { it.material } } }.distinctBy { it.code }
        if (used.isNotEmpty()) {
            w.subheading("Matériaux utilisés")
            w.table(listOf("Matériau", "λ W/(m·K)", "ρ kg/m³", "c J/(kg·K)", "Origine / source"),
                used.map { listOf(it.name, n(it.lambda, 3), n(it.density, 0), n(it.specificHeat, 0), "${it.origin.label} – ${it.source}") },
                floatArrayOf(0.28f, 0.1f, 0.1f, 0.1f, 0.42f))
        }
    }

    private fun heatLoss(w: Writer, r: StudyResult) {
        val hl = r.heatLoss
        w.heading("4. Calcul des déperditions")
        w.paragraph("Méthode : NF EN 12831-1 (transmission, terre-plein EN ISO 13370, locaux non chauffés, ponts thermiques, ventilation et infiltrations). " +
            "θe = ${n(hl.designOutdoorC, 1)} °C.")
        w.table(listOf("Poste", "Puissance", "Part"), LossCategory.entries.mapNotNull { c ->
            val v = hl.byCategoryW[c] ?: 0.0
            if (v == 0.0) null else listOf(c.label, wt(v), pct(if (hl.totalW > 0) v / hl.totalW else 0.0))
        } + listOf(listOf("Total bâtiment", wt(hl.totalW), "100 %")), floatArrayOf(0.5f, 0.25f, 0.25f))
        w.table(listOf("Pièce", "Transmission", "Ventilation", "Total", "W/m²"),
            hl.rooms.map { listOf(it.roomName, wt(it.transmissionW), wt(it.ventilationW), wt(it.totalW), n(it.wattsPerM2, 0)) },
            floatArrayOf(0.32f, 0.17f, 0.17f, 0.17f, 0.17f))
        w.barChart("Déperditions par pièce (W)", hl.rooms.map { it.roomName to it.totalW })
        if (hl.uncertainShare > 0) w.note("${pct(hl.uncertainShare)} des déperditions par les parois reposent sur des valeurs estimées ou inconnues.")
    }

    private fun radiators(w: Writer, p: Project, r: StudyResult) {
        w.heading("5. Radiateurs")
        val regime = r.studyCoverage
        val b = p.building
        if (b.radiators.isEmpty()) { w.paragraph("Aucun radiateur recensé."); return }
        for (rad in b.radiators) {
            val nominal = RadiatorPower.resolveNominal(rad)
            val room = b.room(rad.roomId)
            val out = CoverageAnalyzer.outputFor(rad, regime.supplyC, regime.mode, room?.setpointC ?: 20.0)
            val photo = p.photosOf(PhotoOwner.RADIATOR, rad.id).let { list -> list.firstOrNull { it.kind == fr.thermohabitat.thermal.model.PhotoKind.FRONT } ?: list.firstOrNull() }
            val bitmap = photo?.let { PhotoStorage.decodeOriented(photoStorage.file(it.fileName), 600) }
            w.radiatorBlock(
                title = "${rad.code} – ${rad.label.ifBlank { rad.type.label }}",
                bitmap = bitmap,
                lines = listOf(
                    "Pièce : ${room?.name ?: "non attribuée"}",
                    "Type : ${rad.type.label} – ${rad.material.label} – ${rad.era.label}",
                    "Éléments / colonnes : ${rad.sections ?: "–"} / ${rad.columns ?: "–"}",
                    "Dimensions H × L × P : ${mm(rad.heightMm)} × ${mm(rad.lengthMm)} × ${mm(rad.depthMm)} (${rad.dimensionsOrigin.label.lowercase()})",
                    listOf(rad.manufacturer, rad.model, rad.reference).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Marque / référence inconnues" },
                    "Identification : ${rad.identificationMethod.label}" + (rad.identificationConfidence?.let { " (confiance ${pct(it)})" } ?: ""),
                    when (nominal?.basis) {
                        null -> "Puissance : inconnue"
                        fr.thermohabitat.thermal.calc.PowerBasis.DOCUMENTED -> "Puissance nominale documentée : ${wt(nominal.nominalW)} à ${nominal.rating.label}"
                        else -> "Puissance estimée : ${wt(nominal.nominalW)} (${wt(nominal.lowW)} – ${wt(nominal.highW)}) à " +
                            if (nominal.rating == fr.thermohabitat.thermal.model.RatingCondition.EN442) "ΔT 50 K (${nominal.rating.label})" else nominal.rating.label
                    },
                    "Au régime ${regime.supplyC.toInt()}/${regime.returnC?.toInt() ?: n(out.returnC, 0)} °C" +
                        (if (regime.returnC == null) " (retour calculé à débit nominal)" else "") + " : ${wt(out.powerW)}",
                    when {
                        !rad.validated -> "NON VALIDÉ – exclu du bilan"
                        !RadiatorPower.countsInStudy(rad) -> "Validé, mais puissance non établie – exclu du bilan"
                        room == null -> "Validé, mais non attribué à une pièce – exclu du bilan"
                        else -> "Caractéristiques validées par l'utilisateur"
                    },
                ),
            )
            bitmap?.recycle()
        }
        w.subheading("Puissance disponible selon le régime d'eau (radiateurs validés)")
        val validated = b.radiators.filter { RadiatorPower.countsInStudy(it) }
        if (regime.mode is fr.thermohabitat.thermal.calc.HydraulicMode.FixedFlow) {
            w.note("Ce tableau suppose un écart départ/retour constant ; la couverture de l'étude est calculée à débit nominal conservé.")
        }
        w.table(listOf("Radiateur") + Regime.STANDARD.map { "${it.supplyC.toInt()}/${it.returnC.toInt()}" },
            validated.map { rad ->
                val amb = b.room(rad.roomId)?.setpointC ?: 20.0
                listOf(rad.code) + Regime.STANDARD.map { reg -> n(RadiatorPower.outputOf(rad, reg.supplyC, reg.returnC, amb).powerW, 0) }
            },
            floatArrayOf(0.16f) + FloatArray(Regime.STANDARD.size) { 0.84f / Regime.STANDARD.size })
    }

    private fun comparison(w: Writer, r: StudyResult) {
        w.heading("6. Comparaison avec les besoins")
        val c = r.studyCoverage
        w.paragraph("Régime d'étude ${c.supplyC.toInt()}/${c.returnC?.toInt() ?: "–"} °C : besoins couverts à ${pct(c.coveredFraction)}.")
        w.table(listOf("Pièce", "Besoin", "Disponible", "Couverture", "Déficit", "Statut"),
            c.rooms.map { listOf(it.roomName, wt(it.needW), wt(it.availableW), if (it.needW > 0) pct(it.availableW / it.needW) else "–", wt(it.deficitW), it.status.label) },
            floatArrayOf(0.24f, 0.14f, 0.15f, 0.13f, 0.13f, 0.21f),
            rowColors = c.rooms.map { statusColor(it.status) })
        w.subheading("Couverture selon le régime")
        w.table(listOf("Régime", "Disponible", "Couverture", "Pièces insuffisantes"),
            r.regimeCoverages.map { listOf("${it.supplyC.toInt()}/${it.returnC?.toInt() ?: "–"} °C", wt(it.totalAvailableW), pct(it.coveredFraction), it.insufficientRooms.size.toString()) },
            floatArrayOf(0.25f, 0.25f, 0.25f, 0.25f))
    }

    private fun heatPump(w: Writer, p: Project, r: StudyResult) {
        w.heading("7. Compatibilité avec une pompe à chaleur")
        val s = p.settings
        w.keyValues(listOf(
            "Température de départ nécessaire" to supplyText(r.supply),
            "Hypothèse prudente (borne basse)" to supplyText(r.supplyConservative),
            "Pièce dimensionnante" to (r.supply.limitingRoom?.roomName ?: "–"),
            "Compatibilité estimée" to "${r.compatibility.label} – ${r.compatibility.description}",
            "Loi d'eau" to "${n(s.heatingCurve.designSupplyC, 0)} °C à ${n(s.heatingCurve.designOutdoorC, 0)} °C ext., pied ${n(s.heatingCurve.footSupplyC, 0)} °C à ${n(s.heatingCurve.footOutdoorC, 0)} °C",
            "SCOP estimé" to (r.seasonal?.let {
                "${n(it.scop, 2)}" + when {
                    it.generic -> " (modèle générique)"
                    it.extrapolated -> " (données constructeur, transposées hors de leur plage)"
                    else -> " (données constructeur)"
                } + if (!it.capacityKnown) " – puissance PAC inconnue : appoint non évalué" else ""
            } ?: "–"),
        ))
        val perf = HeatPumpPerformance(s.heatPump, s.genericCarnotEfficiency)
        val bv = HeatPumpAnalysis.bivalence(r.heatLoss.totalKw, r.building.setpointC, r.building.climate.designOutdoorTempC, s.heatingCurve, perf)
        w.keyValues(listOf(
            "PAC étudiée" to listOf(s.heatPump.name, s.heatPump.manufacturer, s.heatPump.reference).filter { it.isNotBlank() }.joinToString(" · "),
            "Puissance PAC à θe" to (bv.capacityAtDesignKw?.let { "${n(it, 1)} kW" + if (bv.capacityExtrapolated) " (transposée hors plage constructeur)" else "" } ?: "inconnue"),
            "Point de bivalence" to when { bv.capacityAtDesignKw == null -> "indéterminé"; bv.bivalentTempC == null -> "PAC suffisante jusqu'à θe"; else -> "${n(bv.bivalentTempC, 1)} °C" },
        ))
        w.note("Le besoin du bâtiment à θe ne doit pas être confondu avec la puissance commerciale nominale d'une PAC (souvent annoncée à +7 °C / 35 °C).")
    }

    private fun dynamic(w: Writer, p: Project, r: StudyResult) {
        w.heading("8. Refroidissement chauffage coupé")
        val model = ZoneModelBuilder.build(p.building, r.heatLoss, singleZone = true)
        if (model.zones.isEmpty()) { w.paragraph("Aucune pièce."); return }
        val config = SimulationConfig(durationH = 8.0, startClockHour = 22.0, outdoor = OutdoorProfile.Constant(0.0), initialTempC = 20.0)
        val sim = RcSimulator().simulateWithUncertainty(model.zones, model.couplings, config, model.capacityUncertainty, model.conductanceUncertainty)
        val z = sim.nominal.zones.first()
        w.paragraph("Hypothèses : chauffage coupé à 22 h, 20 °C intérieurs au départ, 0 °C extérieurs constants, sans apports. " +
            "Température au bout de 8 h : ${n(z.airTempC.last(), 1)} °C (plage ${n(sim.lowAir.getValue(z.zoneId).last(), 1)}–${n(sim.highAir.getValue(z.zoneId).last(), 1)} °C).")
        w.lineChart("Température intérieure (°C) – maison entière", sim.nominal.timesH, z.airTempC, sim.lowAir.getValue(z.zoneId), sim.highAir.getValue(z.zoneId), 22.0)
        model.notes.forEach { w.note(it) }
    }

    private fun scenarios(w: Writer, p: Project, r: StudyResult, scenarios: List<Scenario>) {
        w.heading("9. Scénarios d'amélioration")
        if (scenarios.isEmpty()) { w.paragraph("Aucun scénario enregistré."); return }
        for (sc in scenarios) {
            val cmp = ScenarioComparison(sc, r, analyzer.analyzeScenario(p.building, p.settings, sc))
            w.subheading(sc.name)
            sc.modifications.forEach { w.bullet(it.description) }
            sc.regime?.let { w.bullet("Régime étudié : ${it.label}") }
            w.keyValues(listOf(
                "Déperditions" to "${n(cmp.before.heatLoss.totalW / 1000, 2)} → ${n(cmp.after.heatLoss.totalW / 1000, 2)} kW (−${pct(cmp.heatLossReductionPct)})",
                "Départ nécessaire" to "${cmp.before.supply.requiredSupplyC?.let { n(it, 0) } ?: "–"} → ${cmp.after.supply.requiredSupplyC?.let { n(it, 0) } ?: "–"} °C",
                "Couverture" to "${pct(cmp.before.studyCoverage.coveredFraction)} → ${pct(cmp.after.studyCoverage.coveredFraction)}",
                "Compatibilité PAC" to "${cmp.before.compatibility.label} → ${cmp.after.compatibility.label}",
                "Besoins annuels" to "${n(cmp.before.energy.annualNeedKwh, 0)} → ${n(cmp.after.energy.annualNeedKwh, 0)} kWh",
            ))
        }
    }

    private fun energy(w: Writer, r: StudyResult) {
        w.heading("10. Estimations énergétiques")
        val e = r.energy
        w.paragraph("Besoins annuels de chauffage estimés : ${n(e.annualNeedKwh, 0)} kWh" +
            (e.calibration?.let { " ; chaleur réellement fournie d'après la consommation : ${n(it.deliveredHeatKwh, 0)} kWh (rapport ${n(it.ratio, 2)})." } ?: "."))
        w.table(listOf("Énergie", "Rendement / SCOP", "Quantité", "Coût annuel", "Économie"),
            e.carriers.map { c -> listOf(c.carrier.label, if (c.carrier == fr.thermohabitat.thermal.calc.EnergyCarrier.HEAT_PUMP) n(c.efficiency, 2) else pct(c.efficiency),
                "${n(c.quantity, 0)} ${c.carrier.unit}", "${n(c.costEur, 0)} €", "${n(c.savingsVsCurrentEur, 0)} €") },
            floatArrayOf(0.3f, 0.17f, 0.2f, 0.17f, 0.16f))
        e.assumptions.forEach { w.bullet(it) }
    }

    private fun assumptions(w: Writer, r: StudyResult) {
        w.heading("11. Hypothèses et avertissements")
        w.bullet("Déperditions de conception sans déduction des apports internes et solaires (approche de dimensionnement).")
        w.bullet("Puissances des radiateurs corrigées selon la NF EN 442 (écart logarithmique, exposant n) ; les estimations géométriques sont des ordres de grandeur.")
        w.bullet("Seuls les radiateurs dont les caractéristiques ont été validées par l'utilisateur sont pris en compte.")
        w.bullet("Les températures de base, degrés-jours et propriétés des matériaux anciens sont indicatifs lorsqu'ils sont marqués « estimés ».")
        w.bullet("Aucune reconnaissance automatique du type de radiateur n'est utilisée sans modèle d'IA installé ; l'identification repose sur la bibliothèque illustrée et la saisie.")
        r.warnings.forEach { w.bullet(it) }
    }

    // ------------------------------------------------------------------ Mise en page

    private fun statusColor(s: CoverageStatus): Int = when (s) {
        CoverageStatus.SUFFICIENT -> Color.rgb(220, 240, 222)
        CoverageStatus.LIMIT -> Color.rgb(255, 236, 204)
        CoverageStatus.INSUFFICIENT, CoverageStatus.NO_EMITTER -> Color.rgb(250, 214, 214)
        CoverageStatus.NO_NEED -> Color.WHITE
    }

    private class Writer(private val doc: PdfDocument, private val projectName: String) {
        val pageW = 595f
        val pageH = 842f
        val margin = 40f
        val contentW = pageW - 2 * margin
        private var pageNumber = 0
        private var page: PdfDocument.Page? = null
        private lateinit var canvas: Canvas
        var y = 0f
        private val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9.5f; color = Color.rgb(30, 30, 30) }
        private val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8f; color = Color.rgb(90, 90, 90) }
        private val bold = TextPaint(body).apply { typeface = Typeface.DEFAULT_BOLD }
        private val h1 = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 18f; typeface = Typeface.DEFAULT_BOLD; color = Color.rgb(11, 93, 107) }
        private val h2 = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 13f; typeface = Typeface.DEFAULT_BOLD; color = Color.rgb(11, 93, 107) }
        private val h3 = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10.5f; typeface = Typeface.DEFAULT_BOLD; color = Color.rgb(40, 40, 40) }
        private val line = Paint().apply { color = Color.rgb(200, 200, 200); strokeWidth = 0.6f }
        private val fill = Paint()

        init { newPage() }

        private fun newPage() {
            page?.let { finishPage(it) }
            pageNumber++
            val p = doc.startPage(PdfDocument.PageInfo.Builder(pageW.toInt(), pageH.toInt(), pageNumber).create())
            page = p
            canvas = p.canvas
            y = margin
        }

        private fun finishPage(p: PdfDocument.Page) {
            val c = p.canvas
            c.drawLine(margin, pageH - 30f, pageW - margin, pageH - 30f, line)
            c.drawText("ThermoHabitat Pro – $projectName – étude indicative", margin, pageH - 18f, small)
            val t = "Page $pageNumber"
            c.drawText(t, pageW - margin - small.measureText(t), pageH - 18f, small)
            doc.finishPage(p)
        }

        fun finish() { page?.let { finishPage(it) }; page = null }

        private fun ensure(h: Float) { if (y + h > pageH - 45f) newPage() }

        /** Hauteur utile d'une page (entre la marge haute et le pied de page). */
        private val usableH get() = pageH - 45f - margin

        private fun layout(text: String, paint: TextPaint, width: Float = contentW): StaticLayout =
            StaticLayout.Builder.obtain(text, 0, text.length, paint, width.toInt()).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()

        private fun drawLayout(l: StaticLayout, x: Float = margin) {
            if (l.height <= usableH) {
                ensure(l.height.toFloat())
                canvas.save(); canvas.translate(x, y); l.draw(canvas); canvas.restore()
                y += l.height
                return
            }
            // Bloc plus haut qu'une page (notes longues) : découpé ligne à ligne sur plusieurs pages.
            var lineIndex = 0
            while (lineIndex < l.lineCount) {
                ensure((l.getLineBottom(lineIndex) - l.getLineTop(lineIndex)).toFloat())
                val top = l.getLineTop(lineIndex)
                var last = lineIndex
                while (last + 1 < l.lineCount && y + (l.getLineBottom(last + 1) - top) <= pageH - 45f) last++
                val bottom = l.getLineBottom(last)
                canvas.save()
                canvas.translate(x, y - top)
                canvas.clipRect(0f, top.toFloat(), l.width.toFloat(), bottom.toFloat())
                l.draw(canvas)
                canvas.restore()
                y += bottom - top
                lineIndex = last + 1
            }
        }

        fun space(h: Float) { y += h }
        fun title(text: String) { drawLayout(layout(text, h1)); space(6f) }
        fun heading(text: String) { ensure(60f); space(10f); drawLayout(layout(text, h2)); canvas.drawLine(margin, y + 2, pageW - margin, y + 2, line); space(8f) }
        fun subheading(text: String) { ensure(40f); space(6f); drawLayout(layout(text, h3)); space(3f) }
        fun paragraph(text: String) { if (text.isBlank()) return; drawLayout(layout(text, body)); space(4f) }
        fun note(text: String) { drawLayout(layout(text, small)); space(4f) }
        fun bullet(text: String) {
            val l = layout(text, body, contentW - 12f)
            // La puce reste avec sa première ligne.
            ensure(if (l.height <= usableH) l.height.toFloat() else l.getLineBottom(0).toFloat())
            canvas.drawText("•", margin + 2f, y + body.textSize, body)
            drawLayout(l, margin + 12f)
            space(2f)
        }

        fun keyValues(rows: List<Pair<String, String>>) {
            for ((k, v) in rows) {
                val lk = layout(k, bold, contentW * 0.42f)
                val lv = layout(v, body, contentW * 0.56f)
                val h = maxOf(lk.height, lv.height).toFloat()
                ensure(h + 3)
                canvas.save(); canvas.translate(margin, y); lk.draw(canvas); canvas.restore()
                canvas.save(); canvas.translate(margin + contentW * 0.44f, y); lv.draw(canvas); canvas.restore()
                y += h + 3
            }
            space(4f)
        }

        fun table(headers: List<String>, rows: List<List<String>>, widths: FloatArray, rowColors: List<Int>? = null) {
            fun drawRow(cells: List<String>, paint: TextPaint, background: Int?) {
                val layouts = cells.mapIndexed { i, c -> layout(c, paint, contentW * widths[i] - 4f) }
                val h = (layouts.maxOfOrNull { it.height } ?: 10) + 4f
                ensure(h)
                if (background != null) {
                    fill.color = background
                    canvas.drawRect(RectF(margin, y, margin + contentW, y + h), fill)
                }
                var x = margin
                layouts.forEachIndexed { i, l ->
                    canvas.save(); canvas.translate(x + 2f, y + 2f); l.draw(canvas); canvas.restore()
                    x += contentW * widths[i]
                }
                y += h
                canvas.drawLine(margin, y, margin + contentW, y, line)
            }
            ensure(30f)
            drawRow(headers, bold, Color.rgb(225, 240, 243))
            rows.forEachIndexed { i, r -> drawRow(r, body, rowColors?.getOrNull(i)) }
            space(8f)
        }

        fun barChart(title: String, data: List<Pair<String, Double>>) {
            if (data.isEmpty()) return
            val h = 18f * data.size + 24f
            ensure(if (h <= usableH) h else 16f + 18f)
            canvas.drawText(title, margin, y + 10f, h3)
            y += 16f
            val max = data.maxOf { it.second }.coerceAtLeast(1.0)
            val labelW = contentW * 0.3f
            fill.color = Color.rgb(11, 138, 158)
            data.forEach { (label, v) ->
                ensure(18f) // saut de page entre deux barres si nécessaire
                canvas.drawText(label.take(28), margin, y + 10f, body)
                val bw = ((contentW * 0.55f) * (v / max)).toFloat()
                canvas.drawRect(RectF(margin + labelW, y + 2f, margin + labelW + bw, y + 13f), fill)
                canvas.drawText(String.format(Locale.FRANCE, "%.0f W", v), margin + labelW + bw + 4f, y + 11f, small)
                y += 18f
            }
            space(8f)
        }

        fun lineChart(title: String, x: List<Double>, yv: List<Double>, low: List<Double>, high: List<Double>, startHour: Double) {
            val h = 200f
            ensure(h + 30f)
            canvas.drawText(title, margin, y + 10f, h3)
            y += 18f
            val left = margin + 30f; val right = margin + contentW - 10f; val top = y; val bottom = y + h - 20f
            val ymin = (low.minOrNull() ?: 0.0).let { kotlin.math.floor(it) - 1 }
            val ymax = (high.maxOrNull() ?: 20.0).let { kotlin.math.ceil(it) + 1 }
            val xmax = x.maxOrNull()?.coerceAtLeast(1e-6) ?: 1.0
            fun px(v: Double) = (left + (v / xmax) * (right - left)).toFloat()
            fun py(v: Double) = (bottom - (v - ymin) / (ymax - ymin) * (bottom - top)).toFloat()
            for (k in 0..4) {
                val v = ymin + (ymax - ymin) * k / 4
                canvas.drawLine(left, py(v), right, py(v), line)
                canvas.drawText(String.format(Locale.FRANCE, "%.0f", v), margin, py(v) + 3f, small)
            }
            for (k in 0..4) {
                val v = xmax * k / 4
                canvas.drawText("${((startHour + v) % 24).toInt()} h", px(v) - 8f, bottom + 12f, small)
            }
            val band = android.graphics.Path()
            x.indices.forEach { i -> if (i == 0) band.moveTo(px(x[i]), py(high[i])) else band.lineTo(px(x[i]), py(high[i])) }
            x.indices.reversed().forEach { i -> band.lineTo(px(x[i]), py(low[i])) }
            band.close()
            fill.color = Color.argb(50, 11, 138, 158)
            canvas.drawPath(band, fill)
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(11, 138, 158); strokeWidth = 1.6f; style = Paint.Style.STROKE }
            val path = android.graphics.Path()
            x.indices.forEach { i -> if (i == 0) path.moveTo(px(x[i]), py(yv[i])) else path.lineTo(px(x[i]), py(yv[i])) }
            canvas.drawPath(path, stroke)
            y = bottom + 20f
            space(6f)
        }

        fun radiatorBlock(title: String, bitmap: Bitmap?, lines: List<String>) {
            val imgW = 120f; val imgH = 90f
            val textX = margin + (if (bitmap != null) imgW + 10f else 0f)
            val textW = contentW - (textX - margin)
            val layouts = lines.map { layout(it, body, textW) }
            val titleLayout = layout(title, h3, contentW)
            val h = titleLayout.height + maxOf(if (bitmap != null) imgH else 0f, layouts.sumOf { it.height }.toFloat()) + 10f
            ensure(h)
            drawLayout(titleLayout)
            val top = y + 2f
            if (bitmap != null) {
                val scale = minOf(imgW / bitmap.width, imgH / bitmap.height)
                val dw = bitmap.width * scale; val dh = bitmap.height * scale
                canvas.drawBitmap(bitmap, null, RectF(margin, top, margin + dw, top + dh), null)
            }
            var ty = top
            layouts.forEach { l -> canvas.save(); canvas.translate(textX, ty); l.draw(canvas); canvas.restore(); ty += l.height }
            y = maxOf(ty, top + if (bitmap != null) imgH else 0f) + 8f
            canvas.drawLine(margin, y - 4f, pageW - margin, y - 4f, line)
        }
    }

    // ------------------------------------------------------------------ Format

    /** Température de départ nécessaire, en distinguant « non atteignable » de « indéterminée ». */
    private fun supplyText(s: BuildingSupplyRequirement): String = when {
        s.requiredSupplyC != null -> "${n(s.requiredSupplyC, 1)} °C"
        s.rooms.none { it.hasEmitters } -> "indéterminée (aucun radiateur validé)"
        else -> "non atteignable sous 90 °C (" + s.uncoverableRooms.joinToString { it.roomName } + ")"
    }

    private fun n(v: Double?, d: Int): String = v?.let { String.format(Locale.FRANCE, "%.${d}f", it) } ?: "–"

    private fun wt(v: Double?): String = v?.let { if (kotlin.math.abs(it) >= 10_000) "${String.format(Locale.FRANCE, "%.1f", it / 1000)} kW" else "${String.format(Locale.FRANCE, "%.0f", it)} W" } ?: "–"
    private fun a(v: Double?): String = v?.let { "${String.format(Locale.FRANCE, "%.1f", it)} m²" } ?: "–"
    private fun mm(v: Double?): String = v?.let { "${String.format(Locale.FRANCE, "%.0f", it)} mm" } ?: "–"
    private fun pct(v: Double?): String = v?.let { if (it.isNaN() || it.isInfinite()) "–" else "${String.format(Locale.FRANCE, "%.0f", it * 100)} %" } ?: "–"
}
