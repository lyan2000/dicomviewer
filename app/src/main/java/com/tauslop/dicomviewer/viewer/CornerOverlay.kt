package com.tauslop.dicomviewer.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tauslop.dicom.DicomFormat
import kotlin.math.roundToInt

/** One overlay line; [color] null = the default overlay text colour. [wrap] lets long notes use two lines. */
data class OverlayLine(val text: String, val color: Color? = null, val wrap: Boolean = false)

/** Text for the four corners of the main view (each list top to bottom, blanks already removed). */
data class OverlayUi(
    val topLeft: List<OverlayLine> = emptyList(),
    val topRight: List<OverlayLine> = emptyList(),
    val bottomLeft: List<OverlayLine> = emptyList(),
    val bottomRight: List<OverlayLine> = emptyList(),
)

private val NoteColor = Color(0xFF9E9E9E)
private val ThreeDText = Color(0xFFE0E0E0)

/**
 * Corner text for the main view:
 * - top left, patient; top right, study (both from the series' first header);
 * - bottom left, series plus the stack slice (image n/N, location, thickness, kV/mAs), the MPR
 *   plane and depth with the cursor position, or the 3D mode and preset/window;
 * - bottom right, the window and preset (stack/MPR) or the render time (3D), and the zoom.
 * Stack mode also puts its "MPR unavailable" / skipped-files notes at the bottom left.
 */
fun overlayFor(state: ViewerState): OverlayUi {
    fun lines(xs: List<String>) = xs.map { OverlayLine(it) }
    val info = state.info
    val bl = mutableListOf<OverlayLine>()
    val br = mutableListOf<OverlayLine>()
    info?.seriesLine()?.let { bl += OverlayLine(it) }
    val m = state.mpr
    val n = state.sliceCount
    when {
        m != null && m.threeDMain -> {
            bl += OverlayLine(m.vr.title, ThreeDText)
            m.vr.millis?.let { br += OverlayLine("$it ms") }
        }
        m != null -> {
            if (n > 0) bl += OverlayLine("${m.mainLabel} ${state.sliceIndex + 1}/$n", m.main.color())
            val c = m.cursor
            bl += OverlayLine("Pos ${listOf(c.x, c.y, c.z).joinToString(", ") { DicomFormat.number(it) ?: "?" }} mm")
            br += windowLines(state)
        }
        else -> {
            if (n > 0) bl += OverlayLine("Im ${state.sliceIndex + 1}/$n")
            bl += lines(state.sliceInfo?.lines().orEmpty())
            br += windowLines(state)
            listOfNotNull(state.stackReason, state.skippedNote).forEach { bl += OverlayLine(it, NoteColor, wrap = true) }
        }
    }
    br += OverlayLine("Zoom ${(state.zoom * 100).roundToInt()}%")
    return OverlayUi(
        topLeft = lines(info?.patientLines().orEmpty()),
        topRight = lines(info?.studyLines().orEmpty()),
        bottomLeft = bl,
        bottomRight = br,
    )
}

private fun windowLines(state: ViewerState): List<OverlayLine> = listOfNotNull(
    state.window?.let { OverlayLine("W ${it.width.roundToInt()} L ${it.center.roundToInt()}") },
    state.presetName?.let { OverlayLine(it) },
)

/**
 * Draws [ui] in the corners of its box. Each corner may use up to half the width; longer lines end
 * in an ellipsis. [endInset] keeps the right corners clear of a scrubber along the right edge, and
 * [topEndInset] keeps the top-right corner below buttons in that corner. Text never takes touches.
 */
@Composable
fun CornerOverlay(ui: OverlayUi, modifier: Modifier = Modifier, endInset: Dp = 0.dp, topEndInset: Dp = 0.dp) {
    BoxWithConstraints(modifier.fillMaxSize().padding(6.dp)) {
        val half = maxWidth / 2 - 4.dp
        val rightMax = (half - endInset).coerceAtLeast(48.dp)
        Corner(ui.topLeft, Alignment.TopStart, Modifier.widthIn(max = half))
        Corner(ui.topRight, Alignment.TopEnd,
            Modifier.padding(end = endInset, top = topEndInset).widthIn(max = rightMax))
        Corner(ui.bottomLeft, Alignment.BottomStart, Modifier.widthIn(max = half))
        Corner(ui.bottomRight, Alignment.BottomEnd, Modifier.padding(end = endInset).widthIn(max = rightMax))
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.Corner(lines: List<OverlayLine>, align: Alignment, modifier: Modifier) {
    if (lines.isEmpty()) return
    val end = align == Alignment.TopEnd || align == Alignment.BottomEnd
    val style = MaterialTheme.typography.labelSmall.copy(
        shadow = Shadow(Color.Black, Offset(1f, 1f), blurRadius = 3f),
    )
    Box(Modifier.align(align).then(modifier)) {
        Column(
            horizontalAlignment = if (end) Alignment.End else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            for (l in lines) {
                Text(
                    l.text,
                    color = l.color ?: OverlayText,
                    style = style,
                    textAlign = if (end) TextAlign.End else TextAlign.Start,
                    maxLines = if (l.wrap) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private val OverlayText = Color(0xFFE8E8E8)
