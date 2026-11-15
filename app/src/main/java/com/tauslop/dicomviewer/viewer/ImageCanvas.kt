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
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt

/** What a one-finger drag does. [SCROLL] is the default when no tool is toggled on. */
enum class Tool(val label: String) { SCROLL("Scroll"), PAN("Pan"), ZOOM("Zoom"), WL("W/L"), OBLIQUE("Oblique") }

/**
 * Draws [image] fitted to the view, then scaled by [zoom] and offset by [pan] (px), with
 * optional reference [lines].
 *
 * One-finger drag depends on [tool]: scroll slices (finger up = next), pan, zoom (up = in),
 * window/level, or rotate planes around the cursor (oblique). Two fingers always pinch-zoom and
 * pan; single tap reports image coordinates; double-tap resets the view.
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
    tool: Tool = Tool.SCROLL,
    onWindowDrag: (dx: Float, dy: Float, viewWidth: Float) -> Unit = { _, _, _ -> },
    lines: List<RefLine> = emptyList(),
    /** Single tap, in image pixel coordinates. Null disables tap handling. */
    onTap: ((u: Float, v: Float) -> Unit)? = null,
    /** Oblique tool: rotation in degrees (clockwise positive) swept around the cursor. */
    onTwist: (degrees: Float) -> Unit = {},
    /**
     * Camera mode (3D): [zoom]/[pan] drive gestures but the image is drawn simply fitted, because
     * the renderer already applied them; the default one-finger drag calls [onRotate] instead of
     * scrolling.
     */
    cameraMode: Boolean = false,
    onRotate: (dx: Float, dy: Float, viewPx: Float) -> Unit = { _, _, _ -> },
) {
    val camMode by rememberUpdatedState(cameraMode)
    val rotate by rememberUpdatedState(onRotate)
    val currentImage by rememberUpdatedState(image)
    val currentZoom by rememberUpdatedState(zoom)
    val currentPan by rememberUpdatedState(pan)
    val currentTool by rememberUpdatedState(tool)
    val currentLines by rememberUpdatedState(lines)
    val tap by rememberUpdatedState(onTap)
    val scroll by rememberUpdatedState(onScroll)
    val transform by rememberUpdatedState(onTransform)
    val windowDrag by rememberUpdatedState(onWindowDrag)
    val twist by rememberUpdatedState(onTwist)

    /** Fit scale × zoom, and the screen position of the image's top-left pixel. */
    fun layout(viewW: Float, viewH: Float, img: ImageBitmap): Pair<Float, Offset> {
        val fit = min(viewW / img.width, viewH / img.height) * currentZoom
        val topLeft = Offset(viewW / 2f, viewH / 2f) + currentPan - Offset(img.width * fit / 2f, img.height * fit / 2f)
        return fit to topLeft
    }

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
                        val (fit, topLeft) = if (camMode) fitted(size.width.toFloat(), size.height.toFloat(), img)
                            else layout(size.width.toFloat(), size.height.toFloat(), img)
                        cb((pos.x - topLeft.x) / fit, (pos.y - topLeft.y) / fit)
                    },
                )
            }
            .pointerInput(sliceCount) {
                // Spread the stack over ~1 screen height, but never less than 5dp per slice.
                val pxPerSlice = maxOf(size.height.toFloat() / maxOf(sliceCount, 1), 5.dp.toPx())
                val zoomPx = 200.dp.toPx() // drag this far for ×e zoom
                val pivotDeadZone = 24.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var dragging = false
                    var twoFinger = false
                    var acc = 0f
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2) {
                            // Two fingers: pinch-zoom about the centroid plus pan, in every tool.
                            twoFinger = true
                            val f = event.calculateZoom()
                            val c = event.calculateCentroid()
                            val center = Offset(size.width / 2f, size.height / 2f)
                            val newZoom = (currentZoom * f).coerceIn(0.5f, 20f)
                            val ef = newZoom / currentZoom
                            transform(newZoom, c + (center + currentPan - c) * ef - center + event.calculatePan())
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                            continue
                        }
                        if (twoFinger) continue // ignore the leftover finger after a pinch
                        val ch = pressed.first()
                        if (!dragging && (ch.position - down.position).getDistance() > viewConfiguration.touchSlop) dragging = true
                        if (!dragging) continue
                        val d = ch.position - ch.previousPosition
                        when (currentTool) {
                            Tool.SCROLL -> if (camMode) {
                                rotate(d.x, d.y, minOf(size.width, size.height).toFloat())
                            } else {
                                acc += d.y
                                val steps = (-acc / pxPerSlice).toInt()
                                if (steps != 0) {
                                    acc += steps * pxPerSlice
                                    scroll(steps)
                                }
                            }
                            Tool.PAN -> transform(currentZoom, currentPan + d)
                            Tool.ZOOM -> {
                                // Zoom about the touch-down point, keeping it fixed on screen.
                                val f = exp(-d.y / zoomPx)
                                val center = Offset(size.width / 2f, size.height / 2f)
                                val newZoom = (currentZoom * f).coerceIn(0.5f, 20f)
                                val ef = newZoom / currentZoom
                                val a = down.position
                                transform(newZoom, a + (center + currentPan - a) * ef - center)
                            }
                            Tool.WL -> windowDrag(d.x, d.y, size.width.toFloat())
                            Tool.OBLIQUE -> {
                                // Rotate by the angle the finger sweeps around the cursor on screen.
                                val img = currentImage
                                val l = currentLines.firstOrNull()
                                if (img != null && l != null) {
                                    val (fit, topLeft) = layout(size.width.toFloat(), size.height.toFloat(), img)
                                    val pivot = topLeft + Offset(l.u * fit, l.v * fit)
                                    val a = ch.previousPosition - pivot
                                    val b = ch.position - pivot
                                    if (a.getDistance() > pivotDeadZone && b.getDistance() > pivotDeadZone) {
                                        var da = atan2(b.y, b.x) - atan2(a.y, a.x)
                                        if (da > PI) da -= (2 * PI).toFloat()
                                        if (da < -PI) da += (2 * PI).toFloat()
                                        twist(Math.toDegrees(da.toDouble()).toFloat())
                                    }
                                }
                            }
                        }
                        ch.consume()
                    }
                }
            },
    ) {
        val img = image ?: return@Canvas
        // Camera mode: already zoomed/panned by the renderer, just fit it.
        val (fit, topLeft) = if (cameraMode) fitted(size.width, size.height, img) else layout(size.width, size.height, img)
        drawImage(
            img,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(img.width, img.height),
            dstOffset = IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()),
            dstSize = IntSize((img.width * fit).roundToInt(), (img.height * fit).roundToInt()),
            filterQuality = FilterQuality.Low,
        )
        if (lines.isNotEmpty()) {
            drawRefLines(lines, img.width, img.height, topLeft, fit, strokePx = 1.5.dp.toPx(), gapPx = 14.dp.toPx())
        }
    }
}

/** Scale and top-left that fit [img] in a [w]×[h] canvas, centred (no zoom/pan). */
private fun fitted(w: Float, h: Float, img: ImageBitmap): Pair<Float, Offset> {
    val f = min(w / img.width, h / img.height)
    return f to Offset((w - img.width * f) / 2f, (h - img.height * f) / 2f)
}
