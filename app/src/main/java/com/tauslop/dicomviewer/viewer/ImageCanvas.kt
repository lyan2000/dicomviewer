package com.tauslop.dicomviewer.viewer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Draws [image] fitted to the view, then scaled by [zoom] and offset by [pan] (px).
 *
 * Gestures: one-finger vertical drag scrolls slices (finger up = next), or in [wlMode] adjusts
 * window/level; two fingers pinch-zoom and pan; double-tap resets the view.
 */
@Composable
fun ImageCanvas(
    image: ImageBitmap?,
    zoom: Float,
    pan: Offset,
    sliceCount: Int,
    onScroll: (steps: Int) -> Unit,
    onTransform: (zoom: Float, pan: Offset) -> Unit,
    onResetView: () -> Unit,
    modifier: Modifier = Modifier,
    wlMode: Boolean = false,
    onWindowDrag: (dx: Float, dy: Float, viewWidth: Float) -> Unit = { _, _, _ -> },
    lines: List<RefLine> = emptyList(),
    /** Single tap, in image pixel coordinates. Null disables tap handling. */
    onTap: ((u: Float, v: Float) -> Unit)? = null,
) {
    val currentImage by rememberUpdatedState(image)
    val tap by rememberUpdatedState(onTap)
    val wl by rememberUpdatedState(wlMode)
    val windowDrag by rememberUpdatedState(onWindowDrag)
    val currentZoom by rememberUpdatedState(zoom)
    val currentPan by rememberUpdatedState(pan)
    val scroll by rememberUpdatedState(onScroll)
    val transform by rememberUpdatedState(onTransform)

    Canvas(
        modifier
            .fillMaxSize()
            .clipToBounds()
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { onResetView() },
                    onTap = { pos ->
                        val img = currentImage ?: return@detectTapGestures
                        val cb = tap ?: return@detectTapGestures
                        val fit = min(size.width.toFloat() / img.width, size.height.toFloat() / img.height) * currentZoom
                        val topLeft = Offset(size.width / 2f, size.height / 2f) + currentPan -
                            Offset(img.width * fit / 2f, img.height * fit / 2f)
                        cb((pos.x - topLeft.x) / fit, (pos.y - topLeft.y) / fit)
                    },
                )
            }
            .pointerInput(sliceCount) {
                // Spread the stack over ~1 screen height, but never less than 5dp per slice.
                val pxPerSlice = maxOf(size.height.toFloat() / maxOf(sliceCount, 1), 5.dp.toPx())
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var mode = 0 // 0 = undecided, 1 = scroll, 2 = transform
                    var acc = 0f
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2) {
                            mode = 2
                            val f = event.calculateZoom()
                            val c = event.calculateCentroid()
                            val center = Offset(size.width / 2f, size.height / 2f)
                            val newZoom = (currentZoom * f).coerceIn(0.5f, 20f)
                            val ef = newZoom / currentZoom
                            // Keep the point under the centroid fixed while zooming, then add the pan.
                            val newPan = c + (center + currentPan - c) * ef - center + event.calculatePan()
                            transform(newZoom, newPan)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        } else if (mode != 2) {
                            val ch = pressed.first()
                            if (mode == 0 && (ch.position - down.position).getDistance() > viewConfiguration.touchSlop) mode = 1
                            if (mode == 1 && wl) {
                                val d = ch.position - ch.previousPosition
                                windowDrag(d.x, d.y, size.width.toFloat())
                                ch.consume()
                            } else if (mode == 1) {
                                acc += ch.position.y - ch.previousPosition.y
                                val steps = (-acc / pxPerSlice).toInt()
                                if (steps != 0) {
                                    acc += steps * pxPerSlice
                                    scroll(steps)
                                }
                                ch.consume()
                            }
                        }
                    }
                }
            },
    ) {
        val img = image ?: return@Canvas
        val fit = min(size.width / img.width, size.height / img.height) * zoom
        val w = img.width * fit
        val h = img.height * fit
        val topLeft = Offset(size.width / 2f, size.height / 2f) + pan - Offset(w / 2f, h / 2f)
        drawImage(
            img,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(img.width, img.height),
            dstOffset = IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()),
            dstSize = IntSize(w.roundToInt(), h.roundToInt()),
            filterQuality = FilterQuality.Low,
        )
        if (lines.isNotEmpty()) {
            drawRefLines(lines, img.width, img.height, topLeft, fit, strokePx = 1.5.dp.toPx(), gapPx = 14.dp.toPx())
        }
    }
}
