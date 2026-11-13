package com.tauslop.dicomviewer.viewer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Vertical scrubber: touch or drag anywhere along it to jump to that slice. */
@Composable
fun SliceScrubber(index: Int, count: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val select by rememberUpdatedState(onSelect)
    val inset = 16.dp
    Canvas(
        modifier
            .fillMaxHeight()
            .width(36.dp)
            .pointerInput(count) {
                fun at(y: Float) {
                    val pad = inset.toPx()
                    val f = ((y - pad) / (size.height - 2 * pad)).coerceIn(0f, 1f)
                    select((f * (count - 1)).roundToInt())
                }
                detectTapGestures(onPress = { at(it.y) })
            }
            .pointerInput(count) {
                val pad = inset.toPx()
                detectVerticalDragGestures { change, _ ->
                    val f = ((change.position.y - pad) / (size.height - 2 * pad)).coerceIn(0f, 1f)
                    select((f * (count - 1)).roundToInt())
                    change.consume()
                }
            },
    ) {
        if (count < 2) return@Canvas
        val pad = inset.toPx()
        val x = size.width / 2
        drawLine(Color.White.copy(alpha = 0.25f), Offset(x, pad), Offset(x, size.height - pad), strokeWidth = 2.dp.toPx())
        val y = pad + (size.height - 2 * pad) * index / (count - 1)
        val tw = 16.dp.toPx()
        val th = 6.dp.toPx()
        drawRoundRect(Color(0xFF8AB4F8), Offset(x - tw / 2, y - th / 2), Size(tw, th), CornerRadius(th / 2))
    }
}
