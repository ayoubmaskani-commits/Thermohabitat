package fr.thermohabitat.pro.ui.photo

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import fr.thermohabitat.thermal.vision.GrayImage
import kotlin.math.abs

/** Correspondance entre les pixels de l'image et l'écran (affichage « Fit » centré). */
data class ImageFrame(val left: Float, val top: Float, val scale: Float, val imageWidth: Int, val imageHeight: Int) {
    fun toScreen(p: Offset) = Offset(left + p.x * scale, top + p.y * scale)
    fun toImage(o: Offset) = Offset(((o.x - left) / scale).coerceIn(0f, imageWidth.toFloat()), ((o.y - top) / scale).coerceIn(0f, imageHeight.toFloat()))
    fun rectToScreen(r: Rect) = Rect(toScreen(r.topLeft), toScreen(r.bottomRight))

    companion object {
        fun of(view: IntSize, w: Int, h: Int): ImageFrame {
            if (view.width == 0 || view.height == 0) return ImageFrame(0f, 0f, 1f, w, h)
            val s = minOf(view.width.toFloat() / w, view.height.toFloat() / h)
            return ImageFrame((view.width - w * s) / 2f, (view.height - h * s) / 2f, s, w, h)
        }
    }
}

/**
 * Zone d'affichage d'une photo avec calque de dessin et gestes exprimés en coordonnées image.
 * La hauteur suit le format de la photo (pas de bandes vides) et reste plafonnée à [height] et à 45 % de
 * l'écran : la page garde de la place pour défiler, les glissés sur l'image servant aux outils.
 */
@Composable
fun ImageWorkArea(
    bitmap: Bitmap,
    modifier: Modifier = Modifier,
    height: Dp = 380.dp,
    onTap: ((Offset) -> Unit)? = null,
    /** Début de glissé : position en pixels image, et l'équivalent de 24 dp à l'écran en pixels image (tolérance minimale de saisie). */
    onDragStart: ((Offset, Float) -> Unit)? = null,
    onDrag: ((Offset, Offset) -> Unit)? = null,
    overlay: DrawScope.(ImageFrame) -> Unit = {},
) {
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
    val frame = ImageFrame.of(viewSize, bitmap.width, bitmap.height)
    val screenCap = LocalConfiguration.current.screenHeightDp.dp * 0.45f
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val ratio = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)
        val h = minOf(height, screenCap, maxWidth / ratio)
        Box(Modifier.fillMaxWidth().height(h).onSizeChanged { viewSize = it }) {
            Image(imageBitmap, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            Canvas(
                Modifier.fillMaxSize()
                    .pointerInput(bitmap, onTap) {
                        if (onTap != null) detectTapGestures { pos -> onTap(ImageFrame.of(IntSize(size.width, size.height), bitmap.width, bitmap.height).toImage(pos)) }
                    }
                    .pointerInput(bitmap, onDrag) {
                        if (onDrag != null) {
                            val f = { ImageFrame.of(IntSize(size.width, size.height), bitmap.width, bitmap.height) }
                            detectDragGestures(
                                onDragStart = { pos -> val fr = f(); onDragStart?.invoke(fr.toImage(pos), 24.dp.toPx() / fr.scale) },
                            ) { change, amount ->
                                change.consume()
                                val fr = f()
                                onDrag(fr.toImage(change.position), Offset(amount.x / fr.scale, amount.y / fr.scale))
                            }
                        }
                    },
            ) { overlay(frame) }
        }
    }
}

/** Poignée saisie lors du recadrage. */
enum class CropHandle { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, MOVE, NONE }

/** Cadre de sélection en pixels image, manipulable au doigt. */
class CropController(width: Int, height: Int, margin: Float = 0.1f) {
    private val w = width.toFloat()
    private val h = height.toFloat()
    var rect by mutableStateOf(Rect(w * margin, h * margin, w * (1 - margin), h * (1 - margin)))
    private var handle = CropHandle.NONE

    fun start(p: Offset, tolerance: Float) {
        val r = rect
        handle = when {
            near(p, r.topLeft, tolerance) -> CropHandle.TOP_LEFT
            near(p, r.topRight, tolerance) -> CropHandle.TOP_RIGHT
            near(p, r.bottomLeft, tolerance) -> CropHandle.BOTTOM_LEFT
            near(p, r.bottomRight, tolerance) -> CropHandle.BOTTOM_RIGHT
            r.contains(p) -> CropHandle.MOVE
            else -> CropHandle.NONE
        }
    }

    fun drag(delta: Offset) {
        val r = rect
        val min = 20f
        rect = when (handle) {
            CropHandle.TOP_LEFT -> Rect((r.left + delta.x).coerceIn(0f, r.right - min), (r.top + delta.y).coerceIn(0f, r.bottom - min), r.right, r.bottom)
            CropHandle.TOP_RIGHT -> Rect(r.left, (r.top + delta.y).coerceIn(0f, r.bottom - min), (r.right + delta.x).coerceIn(r.left + min, w), r.bottom)
            CropHandle.BOTTOM_LEFT -> Rect((r.left + delta.x).coerceIn(0f, r.right - min), r.top, r.right, (r.bottom + delta.y).coerceIn(r.top + min, h))
            CropHandle.BOTTOM_RIGHT -> Rect(r.left, r.top, (r.right + delta.x).coerceIn(r.left + min, w), (r.bottom + delta.y).coerceIn(r.top + min, h))
            CropHandle.MOVE -> {
                val dx = delta.x.coerceIn(-r.left, w - r.right)
                val dy = delta.y.coerceIn(-r.top, h - r.bottom)
                r.translate(dx, dy)
            }
            CropHandle.NONE -> r
        }
    }

    private fun near(a: Offset, b: Offset, t: Float) = abs(a.x - b.x) < t && abs(a.y - b.y) < t
}

/** Dessine le cadre de sélection et assombrit l'extérieur. */
fun DrawScope.drawCrop(frame: ImageFrame, rect: Rect, accent: Color) {
    val r = frame.rectToScreen(rect)
    val shade = Color.Black.copy(alpha = 0.45f)
    drawRect(shade, Offset(0f, 0f), Size(size.width, r.top))
    drawRect(shade, Offset(0f, r.bottom), Size(size.width, size.height - r.bottom))
    drawRect(shade, Offset(0f, r.top), Size(r.left, r.height))
    drawRect(shade, Offset(r.right, r.top), Size(size.width - r.right, r.height))
    // Tailles en dp (et non en pixels bruts) : cadre et poignées bien visibles sur les écrans denses.
    drawRect(accent, r.topLeft, r.size, style = Stroke(width = 2.dp.toPx()))
    listOf(r.topLeft, r.topRight, r.bottomLeft, r.bottomRight).forEach {
        drawCircle(accent, 10.dp.toPx(), it)
        drawCircle(Color.White, 10.dp.toPx(), it, style = Stroke(width = 1.5.dp.toPx()))
    }
}

/** Conversion d'une zone de Bitmap en image de luminance pour l'analyse embarquée. */
fun Bitmap.toGrayImage(rect: Rect? = null): GrayImage {
    val left = (rect?.left ?: 0f).toInt().coerceIn(0, width - 1)
    val top = (rect?.top ?: 0f).toInt().coerceIn(0, height - 1)
    val right = (rect?.right ?: width.toFloat()).toInt().coerceIn(left + 1, width)
    val bottom = (rect?.bottom ?: height.toFloat()).toInt().coerceIn(top + 1, height)
    val w = right - left
    val h = bottom - top
    val pixels = IntArray(w * h)
    getPixels(pixels, 0, w, left, top, w, h)
    val gray = FloatArray(w * h) { i ->
        val p = pixels[i]
        0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)
    }
    return GrayImage(w, h, gray)
}
