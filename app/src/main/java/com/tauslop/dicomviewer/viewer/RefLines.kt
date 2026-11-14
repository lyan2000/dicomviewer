package com.tauslop.dicomviewer.viewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Draws reference lines over an image of [imgW]×[imgH] pixels drawn at [topLeft] with [scale]
 * screen px per image px. Lines are clipped to the image and leave a gap of [gapPx] around the cursor.
 */
fun DrawScope.drawRefLines(
    lines: List<RefLine>,
    imgW: Int,
    imgH: Int,
    topLeft: Offset,
    scale: Float,
    strokePx: Float,
    gapPx: Float,
) {
    for (l in lines) {
        val range = clip(l, imgW - 1f, imgH - 1f) ?: continue
        val gap = gapPx / scale
        val color = l.of.color().copy(alpha = 0.85f)
        fun at(t: Float) = topLeft + Offset((l.u + t * l.du) * scale, (l.v + t * l.dv) * scale)
        val (t0, t1) = range
        if (t0 < -gap) drawLine(color, at(t0), at(min(-gap, t1)), strokePx)
        if (t1 > gap) drawLine(color, at(max(gap, t0)), at(t1), strokePx)
    }
}

/** Liang–Barsky: parameter range of the line inside [0, maxU] × [0, maxV], or null. */
private fun clip(l: RefLine, maxU: Float, maxV: Float): Pair<Float, Float>? {
    var t0 = -1e9f
    var t1 = 1e9f
    for ((p, d, hi) in listOf(Triple(l.u, l.du, maxU), Triple(l.v, l.dv, maxV))) {
        if (abs(d) < 1e-6f) {
            if (p < 0 || p > hi) return null
        } else {
            val a = (0 - p) / d
            val b = (hi - p) / d
            t0 = max(t0, min(a, b))
            t1 = min(t1, max(a, b))
        }
    }
    return if (t0 <= t1) t0 to t1 else null
}
