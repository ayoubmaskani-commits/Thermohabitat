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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import fr.thermohabitat.thermal.library.RadiatorCategory
import fr.thermohabitat.thermal.model.RadiatorType

/**
 * Illustration vectorielle d'une catégorie de la bibliothèque : vue de face (à gauche)
 * et vue de dessus (à droite) montrant le nombre de colonnes ou de panneaux.
 * Traits en dp (lisibles quelle que soit la densité de l'écran) ; légende « dessus » à la taille de police du système.
 */
@Composable
fun RadiatorIllustration(category: RadiatorCategory, modifier: Modifier = Modifier.size(150.dp, 96.dp)) {
    val metal = MaterialTheme.colorScheme.primary
    val accent = MaterialTheme.colorScheme.secondary
    val line = MaterialTheme.colorScheme.onSurface
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val hasTopView = when (category.type) {
        RadiatorType.CAST_IRON_COLUMN, RadiatorType.STEEL_COLUMN, RadiatorType.ALUMINIUM_SECTIONAL,
        RadiatorType.STEEL_TUBULAR, RadiatorType.STEEL_PANEL, RadiatorType.CAST_IRON_PLATE -> true
        else -> false
    }
    val description = if (hasTopView) "${category.title} : vue de face et vue de dessus" else "${category.title} : vue de face"
    Canvas(modifier.semantics { contentDescription = description }) {
        val h = size.height
        val stroke = Stroke(width = 1.5.dp.toPx())
        val thin = 1.dp.toPx()
        val thick = 2.dp.toPx()
        // Légende mesurée d'abord : la vue de dessus est au moins aussi large qu'elle et dessinée au-dessus.
        val legend = if (hasTopView) textMeasurer.measure("dessus", labelStyle) else null
        val topW = maxOf(size.width * 0.26f, legend?.size?.width?.toFloat() ?: 0f)
        val x0 = size.width - topW
        val frontW = if (hasTopView) x0 - size.width * 0.08f else size.width * 0.66f
        val topH = if (legend != null) (h - legend.size.height - 2.dp.toPx()).coerceAtLeast(h * 0.5f) else h
        val columns = category.columns ?: when (category.code) { "fonte_5_6col" -> 6; else -> 2 }
        when (category.type) {
            RadiatorType.CAST_IRON_COLUMN, RadiatorType.STEEL_COLUMN, RadiatorType.ALUMINIUM_SECTIONAL -> {
                val n = 7
                val secW = frontW / n
                val gap = 2.dp.toPx()
                val sw = secW - gap
                for (i in 0 until n) {
                    val x = i * secW + gap / 2
                    drawRoundRect(metal.copy(alpha = 0.25f), Offset(x, h * 0.08f), Size(sw, h * 0.78f), CornerRadius(secW / 3))
                    drawRoundRect(metal, Offset(x, h * 0.08f), Size(sw, h * 0.78f), CornerRadius(secW / 3), style = stroke)
                    if (category.code != "fonte_elements_plats" && category.type != RadiatorType.ALUMINIUM_SECTIONAL) {
                        drawLine(metal, Offset(x + sw / 2, h * 0.2f), Offset(x + sw / 2, h * 0.74f), strokeWidth = thin)
                    }
                }
                // pieds
                drawLine(line, Offset(secW * 0.5f, h * 0.86f), Offset(secW * 0.5f, h * 0.98f), strokeWidth = thick)
                drawLine(line, Offset(frontW - secW * 0.5f, h * 0.86f), Offset(frontW - secW * 0.5f, h * 0.98f), strokeWidth = thick)
                topViewColumns(this, x0, topW, topH, columns, metal, category.code == "fonte_elements_plats")
            }
            RadiatorType.STEEL_TUBULAR -> {
                val n = 10
                val secW = frontW / n
                for (i in 0 until n) {
                    drawRoundRect(metal, Offset(i * secW + secW * 0.25f, h * 0.06f), Size(secW * 0.5f, h * 0.86f), CornerRadius(secW / 4), style = stroke)
                }
                drawLine(metal, Offset(0f, h * 0.1f), Offset(frontW, h * 0.1f), strokeWidth = thick)
                drawLine(metal, Offset(0f, h * 0.88f), Offset(frontW, h * 0.88f), strokeWidth = thick)
                topViewColumns(this, x0, topW, topH, columns, metal, false)
            }
            RadiatorType.STEEL_PANEL, RadiatorType.CAST_IRON_PLATE -> {
                val inset = stroke.width / 2
                val corner = CornerRadius(4.dp.toPx())
                drawRoundRect(metal.copy(alpha = 0.2f), Offset(inset, h * 0.15f), Size(frontW - 2 * inset, h * 0.65f), corner)
                drawRoundRect(metal, Offset(inset, h * 0.15f), Size(frontW - 2 * inset, h * 0.65f), corner, style = stroke)
                val pad = 4.dp.toPx()
                var y = h * 0.22f
                while (y < h * 0.78f) { drawLine(metal.copy(alpha = 0.6f), Offset(pad, y), Offset(frontW - pad, y), strokeWidth = thin); y += h * 0.06f }
                // vue de dessus : panneaux et ailettes (type 22)
                listOf(0.25f, 0.75f).forEach { f -> drawLine(metal, Offset(x0, topH * f), Offset(x0 + topW, topH * f), strokeWidth = 3.dp.toPx()) }
                val zig = Path().apply {
                    moveTo(x0, topH * 0.42f)
                    var xx = x0; var up = true
                    while (xx < x0 + topW) { xx += topW / 8; lineTo(xx, if (up) topH * 0.32f else topH * 0.42f); up = !up }
                }
                drawPath(zig, accent, style = stroke)
            }
            RadiatorType.TOWEL_RAIL -> {
                drawLine(metal, Offset(frontW * 0.15f, h * 0.04f), Offset(frontW * 0.15f, h * 0.96f), strokeWidth = 4.dp.toPx())
                drawLine(metal, Offset(frontW * 0.85f, h * 0.04f), Offset(frontW * 0.85f, h * 0.96f), strokeWidth = 4.dp.toPx())
                var y = h * 0.1f
                while (y < h * 0.92f) { drawLine(metal, Offset(frontW * 0.15f, y), Offset(frontW * 0.85f, y), strokeWidth = thick); y += h * 0.08f }
            }
            RadiatorType.CONVECTOR -> {
                val inset = stroke.width / 2
                drawRoundRect(metal, Offset(inset, h * 0.3f), Size(frontW - 2 * inset, h * 0.55f), CornerRadius(6.dp.toPx()), style = stroke)
                var x = frontW * 0.1f
                while (x < frontW * 0.9f) { drawLine(metal, Offset(x, h * 0.34f), Offset(x, h * 0.44f), strokeWidth = thin); x += frontW * 0.05f }
                drawArrowUp(this, frontW * 0.5f, h * 0.25f, accent)
            }
            else -> {
                val wave = Path().apply {
                    moveTo(0f, h * 0.5f)
                    cubicTo(frontW * 0.25f, h * 0.05f, frontW * 0.5f, h * 0.95f, frontW, h * 0.4f)
                }
                drawPath(wave, metal, style = Stroke(width = 3.dp.toPx()))
            }
        }
        // Légende centrée sous la vue de dessus (toutes les catégories qui en ont une).
        legend?.let { drawText(it, topLeft = Offset(x0 + (topW - it.size.width) / 2f, h - it.size.height)) }
    }
}

/** Vue de dessus des éléments : deux éléments côte à côte, [columns] colonnes chacun, dans la zone [x0, x0 + w] × [0, h]. */
private fun topViewColumns(scope: DrawScope, x0: Float, w: Float, h: Float, columns: Int, color: Color, flat: Boolean) = with(scope) {
    // Rayon borné pour que la dernière colonne reste au-dessus de la légende.
    val r = minOf(w / (2.4f * 2), h * 0.86f / (columns * 2.4f))
    val stroke = Stroke(1.2.dp.toPx())
    for (row in 0 until 2) {
        val cx = x0 + w * (0.3f + 0.4f * row)
        for (c in 0 until columns) {
            val cy = h * 0.12f + r + c * (2.4f * r)
            if (flat) drawRect(color, Offset(cx - r, cy - r), Size(2 * r, 2 * r), style = stroke)
            else drawCircle(color, r, Offset(cx, cy), style = stroke)
        }
    }
}

private fun drawArrowUp(scope: DrawScope, x: Float, y: Float, color: Color) = with(scope) {
    val head = 4.dp.toPx()
    val width = 2.dp.toPx()
    drawLine(color, Offset(x, y), Offset(x, y - size.height * 0.2f), strokeWidth = width)
    drawLine(color, Offset(x, y - size.height * 0.2f), Offset(x - head, y - size.height * 0.12f), strokeWidth = width)
    drawLine(color, Offset(x, y - size.height * 0.2f), Offset(x + head, y - size.height * 0.12f), strokeWidth = width)
}
