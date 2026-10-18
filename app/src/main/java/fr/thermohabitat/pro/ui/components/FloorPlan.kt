package fr.thermohabitat.pro.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.MaterialTheme
import kotlin.math.max

/** Pièce à représenter sur le plan simplifié (coordonnées en mètres). */
data class PlanRoom(
    val id: Long,
    val name: String,
    val lengthM: Double,
    val widthM: Double,
    val x: Double?,
    val y: Double?,
    val fill: Color,
    val caption: String? = null,
)

/**
 * Disposition automatique des pièces sans position : rangement en lignes (étagères)
 * de largeur maximale [maxRowWidthM]. Les pièces déjà positionnées sont conservées.
 */
fun autoLayout(rooms: List<PlanRoom>, maxRowWidthM: Double = 12.0, gapM: Double = 0.3): List<PlanRoom> {
    val placed = rooms.filter { it.x != null && it.y != null }
    var cursorX = 0.0
    var cursorY = (placed.maxOfOrNull { it.y!! + it.widthM } ?: 0.0).let { if (placed.isEmpty()) 0.0 else it + gapM }
    var rowHeight = 0.0
    return rooms.map { r ->
        if (r.x != null && r.y != null) r else {
            if (cursorX > 0 && cursorX + r.lengthM > maxRowWidthM) {
                cursorX = 0.0; cursorY += rowHeight + gapM; rowHeight = 0.0
            }
            val out = r.copy(x = cursorX, y = cursorY)
            cursorX += r.lengthM + gapM
            rowHeight = max(rowHeight, r.widthM)
            out
        }
    }
}

/**
 * Plan 2D simplifié d'un niveau : rectangles à l'échelle colorés selon la couverture.
 * Toucher une pièce l'ouvre ; un appui long puis glisser la déplace (position enregistrée).
 */
@Composable
fun FloorPlan(
    rooms: List<PlanRoom>,
    onRoomClick: (Long) -> Unit,
    onRoomMoved: ((Long, Double, Double) -> Unit)? = null,
    modifier: Modifier = Modifier,
    height: Dp = 300.dp,
) {
    val laidOut = remember(rooms) { autoLayout(rooms) }
    val dragOffsets = remember(rooms) { mutableStateMapOf<Long, Offset>() }
    var dragging by remember(rooms) { mutableStateOf<Long?>(null) }
    val textMeasurer = rememberTextMeasurer()
    val outline = MaterialTheme.colorScheme.onSurface
    val textColor = MaterialTheme.colorScheme.onSurface
    val extentX = (laidOut.maxOfOrNull { it.x!! + it.lengthM } ?: 1.0).coerceAtLeast(1.0)
    val extentY = (laidOut.maxOfOrNull { it.y!! + it.widthM } ?: 1.0).coerceAtLeast(1.0)

    fun scaleFor(size: Size): Float = minOf(size.width / extentX.toFloat(), size.height / extentY.toFloat()) * 0.95f

    fun rectOf(r: PlanRoom, scale: Float): Rect {
        val o = dragOffsets[r.id] ?: Offset.Zero
        return Rect(Offset(r.x!!.toFloat() * scale + o.x, r.y!!.toFloat() * scale + o.y),
            Size(r.lengthM.toFloat() * scale, r.widthM.toFloat() * scale))
    }

    Canvas(
        modifier.fillMaxWidth().height(height)
            .pointerInput(laidOut) {
                detectTapGestures { pos ->
                    val scale = scaleFor(Size(size.width.toFloat(), size.height.toFloat()))
                    laidOut.lastOrNull { rectOf(it, scale).contains(pos) }?.let { onRoomClick(it.id) }
                }
            }
            .pointerInput(laidOut, onRoomMoved) {
                if (onRoomMoved == null) return@pointerInput
                detectDragGesturesAfterLongPress(
                    onDragStart = { pos ->
                        val scale = scaleFor(Size(size.width.toFloat(), size.height.toFloat()))
                        dragging = laidOut.lastOrNull { rectOf(it, scale).contains(pos) }?.id
                    },
                    onDragEnd = {
                        val id = dragging
                        val scale = scaleFor(Size(size.width.toFloat(), size.height.toFloat()))
                        if (id != null) {
                            val r = laidOut.first { it.id == id }
                            val o = dragOffsets[id] ?: Offset.Zero
                            val nx = (r.x!! + o.x / scale).coerceAtLeast(0.0)
                            val ny = (r.y!! + o.y / scale).coerceAtLeast(0.0)
                            onRoomMoved(id, Math.round(nx * 10) / 10.0, Math.round(ny * 10) / 10.0)
                        }
                        dragging = null
                    },
                    onDragCancel = { dragging = null; dragOffsets.clear() },
                ) { change, amount ->
                    val id = dragging ?: return@detectDragGesturesAfterLongPress
                    change.consume()
                    dragOffsets[id] = (dragOffsets[id] ?: Offset.Zero) + amount
                }
            },
    ) {
        val scale = scaleFor(size)
        for (r in laidOut) {
            val rect = rectOf(r, scale)
            drawRoundRect(r.fill.copy(alpha = if (dragging == r.id) 0.9f else 0.65f), rect.topLeft, rect.size, CornerRadius(6f))
            drawRoundRect(outline, rect.topLeft, rect.size, CornerRadius(6f), style = Stroke(width = 2f))
            val label = textMeasurer.measure(
                r.name, TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = textColor),
                overflow = TextOverflow.Ellipsis, maxLines = 2,
                constraints = androidx.compose.ui.unit.Constraints(maxWidth = max(1, (rect.width - 8).toInt())),
            )
            drawText(label, topLeft = Offset(rect.left + 4, rect.top + 4))
            r.caption?.let { c ->
                val cap = textMeasurer.measure(
                    c, TextStyle(fontSize = 10.sp, color = textColor), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    constraints = androidx.compose.ui.unit.Constraints(maxWidth = max(1, (rect.width - 8).toInt())),
                )
                if (rect.height > label.size.height + cap.size.height + 8) {
                    drawText(cap, topLeft = Offset(rect.left + 4, rect.top + 6 + label.size.height))
                }
            }
        }
    }
}
