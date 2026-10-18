package fr.thermohabitat.pro.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.thermohabitat.pro.ui.util.Fmt
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Palette de séries, lisible en mode clair et sombre. */
val ChartPalette = listOf(
    Color(0xFF0B8A9E), Color(0xFFE5642B), Color(0xFF6A5ACD), Color(0xFF2E7D32),
    Color(0xFFC2185B), Color(0xFF8D6E63), Color(0xFF00897B), Color(0xFFF9A825),
)

data class BarEntry(val label: String, val value: Double, val color: Color? = null, val detail: String? = null)

/** Diagramme en barres horizontales interactif (toucher une barre affiche son détail). */
@Composable
fun HorizontalBarChart(entries: List<BarEntry>, modifier: Modifier = Modifier, valueFormatter: (Double) -> String = { Fmt.watts(it) }) {
    var selected by remember(entries) { mutableStateOf<Int?>(null) }
    val maxValue = entries.maxOfOrNull { abs(it.value) }?.takeIf { it > 0 } ?: 1.0
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        entries.forEachIndexed { i, e ->
            val color = e.color ?: ChartPalette[i % ChartPalette.size]
            Column(Modifier.fillMaxWidth().clickable { selected = if (selected == i) null else i }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(e.label, Modifier.weight(0.38f), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    Box(Modifier.weight(0.42f).height(18.dp)) {
                        Box(Modifier.fillMaxWidth(fraction = (abs(e.value) / maxValue).toFloat().coerceIn(0.01f, 1f)).height(18.dp)
                            .background(if (selected == null || selected == i) color else color.copy(alpha = 0.35f), RoundedCornerShape(4.dp)))
                    }
                    Text(valueFormatter(e.value), Modifier.weight(0.2f).padding(start = 6.dp), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                }
                if (selected == i && e.detail != null) {
                    Text(e.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}

/** Barres jumelées besoin / puissance disponible par pièce. */
@Composable
fun NeedVsAvailableChart(
    rows: List<Triple<String, Double, Double>>,
    colors: List<Color>,
    modifier: Modifier = Modifier,
) {
    val maxValue = rows.maxOfOrNull { max(it.second, it.third) }?.takeIf { it > 0 } ?: 1.0
    val needColor = MaterialTheme.colorScheme.outline
    val primary = MaterialTheme.colorScheme.primary
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LegendDot(needColor, "Besoin")
            LegendDot(MaterialTheme.colorScheme.primary, "Disponible (couleur = couverture)")
        }
        rows.forEachIndexed { i, (label, need, available) ->
            Column {
                Text(label, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).height(10.dp)) {
                        Box(Modifier.fillMaxWidth((need / maxValue).toFloat().coerceIn(0.01f, 1f)).height(10.dp).background(needColor, RoundedCornerShape(3.dp)))
                    }
                    Text(Fmt.watts(need), Modifier.width(72.dp).padding(start = 6.dp), style = MaterialTheme.typography.labelSmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).height(10.dp)) {
                        Box(Modifier.fillMaxWidth((available / maxValue).toFloat().coerceIn(0.01f, 1f)).height(10.dp)
                            .background(colors.getOrElse(i) { primary }, RoundedCornerShape(3.dp)))
                    }
                    Text(Fmt.watts(available), Modifier.width(72.dp).padding(start = 6.dp), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, RoundedCornerShape(50)))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

data class DonutSlice(val label: String, val value: Double, val color: Color)

/** Diagramme en anneau avec légende interactive. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun DonutChart(slices: List<DonutSlice>, modifier: Modifier = Modifier, centerLabel: String? = null) {
    // Sélection remise à zéro quand les secteurs changent (autre projet, nombre de catégories différent).
    var selected by remember(slices) { mutableStateOf<Int?>(null) }
    val total = slices.sumOf { max(it.value, 0.0) }.takeIf { it > 0 } ?: 1.0
    val textMeasurer = rememberTextMeasurer()
    val onSurface = MaterialTheme.colorScheme.onSurface
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(180.dp)) {
            val stroke = size.minDimension * 0.18f
            val diameter = size.minDimension - stroke
            val topLeft = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)
            var start = -90f
            slices.forEachIndexed { i, s ->
                val sweep = (max(s.value, 0.0) / total * 360.0).toFloat()
                val alpha = if (selected == null || selected == i) 1f else 0.3f
                drawArc(s.color.copy(alpha = alpha), start, sweep, false, topLeft, Size(diameter, diameter), style = Stroke(stroke))
                start += sweep
            }
            val label = selected?.let { i -> slices.getOrNull(i)?.let { Fmt.pct(it.value / total) } } ?: centerLabel
            if (label != null) {
                val layout = textMeasurer.measure(label, TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, color = onSurface))
                drawText(layout, topLeft = Offset((size.width - layout.size.width) / 2, (size.height - layout.size.height) / 2))
            }
        }
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            slices.forEachIndexed { i, s ->
                Row(Modifier.clickable { selected = if (selected == i) null else i }, verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(s.color, RoundedCornerShape(50)))
                    Spacer(Modifier.width(4.dp))
                    Text("${s.label} ${Fmt.watts(s.value)}", style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (selected == i) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
    }
}

/** Série d'une courbe ; [low]/[high] dessinent une bande d'incertitude optionnelle. */
data class LineSeries(
    val name: String,
    val x: List<Double>,
    val y: List<Double>,
    val color: Color,
    val low: List<Double>? = null,
    val high: List<Double>? = null,
    val dashed: Boolean = false,
    val pointsOnly: Boolean = false,
)

/**
 * Courbes interactives : toucher ou glisser sur le graphique affiche le curseur et les valeurs
 * de chaque série à l'abscisse choisie.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun LineChart(
    series: List<LineSeries>,
    modifier: Modifier = Modifier,
    xLabel: (Double) -> String = { Fmt.num(it, 0) },
    yUnit: String = "°C",
    height: androidx.compose.ui.unit.Dp = 240.dp,
    yMinOverride: Double? = null,
    yMaxOverride: Double? = null,
) {
    val all = series.filter { it.x.isNotEmpty() }
    if (all.isEmpty()) {
        Text("Aucune donnée à afficher", style = MaterialTheme.typography.bodySmall)
        return
    }
    val xMin = all.minOf { it.x.min() }
    val xMax = all.maxOf { it.x.max() }.let { if (it - xMin < 1e-9) xMin + 1 else it }
    val ys = all.flatMap { it.y + (it.low ?: emptyList()) + (it.high ?: emptyList()) }.filter { !it.isNaN() }
    var yMin = yMinOverride ?: floor(ys.min() - 0.5)
    var yMax = yMaxOverride ?: ceil(ys.max() + 0.5)
    if (yMax - yMin < 1) { yMax += 0.5; yMin -= 0.5 }
    var cursorX by remember(series) { mutableStateOf<Double?>(null) }
    val textMeasurer = rememberTextMeasurer()
    val axisColor = MaterialTheme.colorScheme.outline
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val cursorColor = MaterialTheme.colorScheme.secondary
    val labelStyle = TextStyle(fontSize = 10.sp, color = labelColor)

    Column(modifier.fillMaxWidth()) {
        Canvas(
            Modifier.fillMaxWidth().height(height)
                .pointerInput(series) {
                    detectTapGestures { pos ->
                        val left = 44.dp.toPx(); val right = size.width - 8.dp.toPx()
                        cursorX = (xMin + ((pos.x - left) / (right - left)).coerceIn(0f, 1f) * (xMax - xMin))
                    }
                }
                .pointerInput(series) {
                    detectHorizontalDragGestures { change, _ ->
                        val left = 44.dp.toPx(); val right = size.width - 8.dp.toPx()
                        cursorX = (xMin + ((change.position.x - left) / (right - left)).coerceIn(0f, 1f) * (xMax - xMin))
                    }
                },
        ) {
            val left = 44.dp.toPx(); val right = size.width - 8.dp.toPx()
            val top = 8.dp.toPx(); val bottom = size.height - 24.dp.toPx()
            fun px(x: Double) = (left + (x - xMin) / (xMax - xMin) * (right - left)).toFloat()
            fun py(y: Double) = (bottom - (y - yMin) / (yMax - yMin) * (bottom - top)).toFloat()

            // Grille et graduations
            val ySteps = 5
            for (k in 0..ySteps) {
                val v = yMin + (yMax - yMin) * k / ySteps
                val y = py(v)
                drawLine(axisColor.copy(alpha = 0.3f), Offset(left, y), Offset(right, y), strokeWidth = 1f)
                val t = textMeasurer.measure(Fmt.num(v, if (yMax - yMin < 6) 1 else 0), labelStyle)
                drawText(t, topLeft = Offset(left - t.size.width - 4.dp.toPx(), y - t.size.height / 2))
            }
            val xSteps = 6
            for (k in 0..xSteps) {
                val v = xMin + (xMax - xMin) * k / xSteps
                val x = px(v)
                drawLine(axisColor.copy(alpha = 0.2f), Offset(x, top), Offset(x, bottom), strokeWidth = 1f)
                val t = textMeasurer.measure(xLabel(v), labelStyle)
                drawText(t, topLeft = Offset((x - t.size.width / 2).coerceIn(0f, size.width - t.size.width), bottom + 4.dp.toPx()))
            }
            drawLine(axisColor, Offset(left, bottom), Offset(right, bottom), strokeWidth = 1.5f)
            drawLine(axisColor, Offset(left, top), Offset(left, bottom), strokeWidth = 1.5f)

            for (s in all) {
                if (s.low != null && s.high != null && s.low.size == s.x.size) {
                    val band = Path()
                    s.x.indices.forEach { i -> if (i == 0) band.moveTo(px(s.x[i]), py(s.high[i])) else band.lineTo(px(s.x[i]), py(s.high[i])) }
                    s.x.indices.reversed().forEach { i -> band.lineTo(px(s.x[i]), py(s.low[i])) }
                    band.close()
                    drawPath(band, s.color.copy(alpha = 0.15f))
                }
                if (s.pointsOnly) {
                    s.x.indices.forEach { i -> drawCircle(s.color, 4.dp.toPx(), Offset(px(s.x[i]), py(s.y[i]))) }
                } else {
                    val path = Path()
                    s.x.indices.forEach { i -> if (i == 0) path.moveTo(px(s.x[i]), py(s.y[i])) else path.lineTo(px(s.x[i]), py(s.y[i])) }
                    drawPath(path, s.color, style = Stroke(width = 2.5.dp.toPx(),
                        pathEffect = if (s.dashed) PathEffect.dashPathEffect(floatArrayOf(12f, 8f)) else null))
                }
            }
            cursorX?.let { cx ->
                val x = px(cx)
                drawLine(cursorColor, Offset(x, top), Offset(x, bottom), strokeWidth = 1.5f)
                for (s in all) {
                    val v = interpolate(s.x, s.y, cx) ?: continue
                    drawCircle(s.color, 4.dp.toPx(), Offset(x, py(v)))
                }
            }
        }
        // Légende et valeurs au curseur
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            all.forEach { s ->
                val v = cursorX?.let { interpolate(s.x, s.y, it) }
                LegendDot(s.color, if (v != null) "${s.name} : ${Fmt.num(v, 1)} $yUnit" else s.name)
            }
        }
        cursorX?.let {
            Text("Curseur : ${xLabel(it)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun interpolate(xs: List<Double>, ys: List<Double>, x: Double): Double? {
    if (xs.isEmpty()) return null
    if (x <= xs.first()) return ys.first()
    if (x >= xs.last()) return ys.last()
    val i = xs.indexOfFirst { it >= x }
    if (i <= 0) return ys.first()
    val t = (x - xs[i - 1]) / (xs[i] - xs[i - 1])
    return ys[i - 1] + t * (ys[i] - ys[i - 1])
}

/** Jauge horizontale de couverture (0–100 %+). */
@Composable
fun CoverageGauge(fraction: Double, color: Color, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier.fillMaxWidth().height(12.dp)) {
        drawRoundRect(track, cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
        drawRoundRect(color, size = Size(size.width * min(1.0, max(0.0, fraction)).toFloat(), size.height),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
    }
}
