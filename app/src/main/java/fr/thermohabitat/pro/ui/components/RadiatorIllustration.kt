package fr.thermohabitat.pro.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.thermohabitat.thermal.library.RadiatorCategory
import fr.thermohabitat.thermal.model.RadiatorType

/**
 * Illustration vectorielle d'une catégorie de la bibliothèque : vue de face (à gauche)
 * et vue de dessus (à droite) montrant le nombre de colonnes ou de panneaux.
 */
@Composable
fun RadiatorIllustration(category: RadiatorCategory, modifier: Modifier = Modifier.size(150.dp, 96.dp)) {
    val metal = MaterialTheme.colorScheme.primary
    val accent = MaterialTheme.colorScheme.secondary
    val line = MaterialTheme.colorScheme.onSurface
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Canvas(modifier) {
        val frontW = size.width * 0.66f
        val h = size.height
        val stroke = Stroke(width = 1.5f)
        val columns = category.columns ?: when (category.code) { "fonte_5_6col" -> 6; else -> 2 }
        when (category.type) {
            RadiatorType.CAST_IRON_COLUMN, RadiatorType.STEEL_COLUMN, RadiatorType.ALUMINIUM_SECTIONAL -> {
                val n = 7
                val secW = frontW / n
                for (i in 0 until n) {
                    val x = i * secW + 1
                    drawRoundRect(metal.copy(alpha = 0.25f), Offset(x, h * 0.08f), Size(secW - 2, h * 0.78f), CornerRadius(secW / 3))
                    drawRoundRect(metal, Offset(x, h * 0.08f), Size(secW - 2, h * 0.78f), CornerRadius(secW / 3), style = stroke)
                    if (category.code != "fonte_elements_plats" && category.type != RadiatorType.ALUMINIUM_SECTIONAL) {
                        drawLine(metal, Offset(x + secW / 2 - 1, h * 0.2f), Offset(x + secW / 2 - 1, h * 0.74f), strokeWidth = 1f)
                    }
                }
                // pieds
                drawLine(line, Offset(secW * 0.5f, h * 0.86f), Offset(secW * 0.5f, h * 0.98f), strokeWidth = 2f)
                drawLine(line, Offset(frontW - secW * 0.5f, h * 0.86f), Offset(frontW - secW * 0.5f, h * 0.98f), strokeWidth = 2f)
                topViewColumns(this, frontW, columns, metal, category.code == "fonte_elements_plats")
            }
            RadiatorType.STEEL_TUBULAR -> {
                val n = 10
                val secW = frontW / n
                for (i in 0 until n) {
                    drawRoundRect(metal, Offset(i * secW + secW * 0.25f, h * 0.06f), Size(secW * 0.5f, h * 0.86f), CornerRadius(secW / 4), style = stroke)
                }
                drawLine(metal, Offset(0f, h * 0.1f), Offset(frontW, h * 0.1f), strokeWidth = 2f)
                drawLine(metal, Offset(0f, h * 0.88f), Offset(frontW, h * 0.88f), strokeWidth = 2f)
                topViewColumns(this, frontW, columns, metal, false)
            }
            RadiatorType.STEEL_PANEL, RadiatorType.CAST_IRON_PLATE -> {
                drawRoundRect(metal.copy(alpha = 0.2f), Offset(0f, h * 0.15f), Size(frontW, h * 0.65f), CornerRadius(4f))
                drawRoundRect(metal, Offset(0f, h * 0.15f), Size(frontW, h * 0.65f), CornerRadius(4f), style = stroke)
                var y = h * 0.22f
                while (y < h * 0.78f) { drawLine(metal.copy(alpha = 0.6f), Offset(4f, y), Offset(frontW - 4f, y), strokeWidth = 1f); y += h * 0.06f }
                // vue de dessus : panneaux et ailettes (type 22)
                val x0 = frontW + size.width * 0.08f
                val w = size.width - x0
                listOf(0.25f, 0.75f).forEach { f -> drawLine(metal, Offset(x0, h * f), Offset(x0 + w, h * f), strokeWidth = 3f) }
                val zig = Path().apply {
                    moveTo(x0, h * 0.42f)
                    var xx = x0; var up = true
                    while (xx < x0 + w) { xx += w / 8; lineTo(xx, if (up) h * 0.32f else h * 0.42f); up = !up }
                }
                drawPath(zig, accent, style = stroke)
                drawText(textMeasurer.measure("dessus", labelStyle), topLeft = Offset(x0, h * 0.82f))
            }
            RadiatorType.TOWEL_RAIL -> {
                drawLine(metal, Offset(frontW * 0.15f, h * 0.04f), Offset(frontW * 0.15f, h * 0.96f), strokeWidth = 4f)
                drawLine(metal, Offset(frontW * 0.85f, h * 0.04f), Offset(frontW * 0.85f, h * 0.96f), strokeWidth = 4f)
                var y = h * 0.1f
                while (y < h * 0.92f) { drawLine(metal, Offset(frontW * 0.15f, y), Offset(frontW * 0.85f, y), strokeWidth = 2f); y += h * 0.08f }
            }
            RadiatorType.CONVECTOR -> {
                drawRoundRect(metal, Offset(0f, h * 0.3f), Size(frontW, h * 0.55f), CornerRadius(6f), style = stroke)
                var x = frontW * 0.1f
                while (x < frontW * 0.9f) { drawLine(metal, Offset(x, h * 0.34f), Offset(x, h * 0.44f), strokeWidth = 1.5f); x += frontW * 0.05f }
                drawArrowUp(this, frontW * 0.5f, h * 0.25f, accent)
            }
            else -> {
                val wave = Path().apply {
                    moveTo(0f, h * 0.5f)
                    cubicTo(frontW * 0.25f, h * 0.05f, frontW * 0.5f, h * 0.95f, frontW, h * 0.4f)
                }
                drawPath(wave, metal, style = Stroke(width = 6f))
            }
        }
    }
}

private fun topViewColumns(scope: DrawScope, frontW: Float, columns: Int, color: Color, flat: Boolean) = with(scope) {
    val x0 = frontW + size.width * 0.08f
    val w = size.width - x0
    val h = size.height
    val r = minOf(w / (2.4f * 2), h / (columns * 2.6f))
    for (row in 0 until 2) {
        val cx = x0 + w * (0.3f + 0.4f * row)
        for (c in 0 until columns) {
            val cy = h * 0.12f + r + c * (2.4f * r)
            if (flat) drawRect(color, Offset(cx - r, cy - r), Size(2 * r, 2 * r), style = Stroke(1.2f))
            else drawCircle(color, r, Offset(cx, cy), style = Stroke(1.2f))
        }
    }
}

private fun drawArrowUp(scope: DrawScope, x: Float, y: Float, color: Color) = with(scope) {
    drawLine(color, Offset(x, y), Offset(x, y - size.height * 0.2f), strokeWidth = 2f)
    drawLine(color, Offset(x, y - size.height * 0.2f), Offset(x - 6f, y - size.height * 0.12f), strokeWidth = 2f)
    drawLine(color, Offset(x, y - size.height * 0.2f), Offset(x + 6f, y - size.height * 0.12f), strokeWidth = 2f)
}
