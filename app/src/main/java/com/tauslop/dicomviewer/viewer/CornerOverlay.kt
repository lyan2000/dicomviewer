package com.tauslop.dicomviewer.viewer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import com.tauslop.dicom.ImagePlane
import com.tauslop.dicom.Orientation
import com.tauslop.dicom.Plane
import com.tauslop.dicom.ScaleBar
import com.tauslop.dicom.Vec3
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
 * Patient-space screen axes of a displayed image ([right], [down]: unit LPS directions) and the size
 * of one image pixel in mm ([mmPerPx]; null when unknown, e.g. no Pixel Spacing).
 */
data class ViewGeometry(val right: Vec3, val down: Vec3, val mmPerPx: Double?) {
    companion object {
        /** A resliced MPR plane. */
        fun of(p: Plane) = ViewGeometry(p.right, p.down, p.pixelMm)

        /** A stack slice: row direction is screen right, column direction screen down; horizontal spacing. */
        fun of(p: ImagePlane) = ViewGeometry(
            Vec3(p.rowDir[0], p.rowDir[1], p.rowDir[2]),
            Vec3(p.colDir[0], p.colDir[1], p.colDir[2]),
            // Pixel Spacing is (row spacing, column spacing): the second is the horizontal step.
            p.pixelSpacing?.getOrNull(1)?.takeIf { it > 0 },
        )
    }
}

/** Horizontal band kept free at the middle of the top and bottom edges for the orientation letters. */
private val LetterBand = 40.dp

/**
 * Draws [ui] in the corners of its box, plus, when [geom] is known, orientation letters at the
 * middle of each edge and a scale bar at the top of the bottom-right corner.
 * - Each corner may use up to half the width minus a central band for the top/bottom letters;
 *   longer lines end in an ellipsis.
 * - [endInset] keeps the right corners and the right letter clear of a scrubber along the right
 *   edge; [topEndInset] keeps the top-right corner (and the top letter) below buttons there.
 * - The scale bar is sized for the image as drawn: [imgW]×[imgH] pixels fitted to this box and
 *   multiplied by [zoom] (pass 1 when the renderer already applied the zoom, as in 3D).
 * Nothing here takes touches.
 */
@Composable
fun CornerOverlay(
    ui: OverlayUi,
    modifier: Modifier = Modifier,
    endInset: Dp = 0.dp,
    topEndInset: Dp = 0.dp,
    geom: ViewGeometry? = null,
    imgW: Int = 0,
    imgH: Int = 0,
    zoom: Float = 1f,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val boxW = constraints.maxWidth.toFloat()
        val boxH = constraints.maxHeight.toFloat()
        val fullWidth = maxWidth
        Box(Modifier.fillMaxSize().padding(6.dp)) {
            val half = (fullWidth - 12.dp) / 2 - LetterBand / 2
            val leftMax = half.coerceAtLeast(48.dp)
            val rightMax = (half - endInset).coerceAtLeast(48.dp)
            // Screen mm per px: image mm per px over (fit × zoom) screen px per image px.
            val bar = geom?.mmPerPx?.takeIf { imgW > 0 && imgH > 0 && boxW > 0 && boxH > 0 }?.let { mm ->
                val screenPerImg = minOf(boxW / imgW, boxH / imgH) * zoom
                val maxBarPx = with(density) { (rightMax - ScaleLabelWidth).coerceAtLeast(24.dp).toPx() }
                ScaleBar.choose(mm / screenPerImg, maxBarPx.toDouble())
            }
            Corner(ui.topLeft, Alignment.TopStart, Modifier.widthIn(max = leftMax))
            Corner(ui.topRight, Alignment.TopEnd,
                Modifier.padding(end = endInset, top = topEndInset).widthIn(max = rightMax))
            Corner(ui.bottomLeft, Alignment.BottomStart, Modifier.widthIn(max = leftMax))
            Corner(ui.bottomRight, Alignment.BottomEnd, Modifier.padding(end = endInset).widthIn(max = rightMax),
                header = bar?.let { { ScaleBarRow(it) } })
            geom?.let { g ->
                val e = Orientation.edges(g.right, g.down)
                Letter(e.left, Modifier.align(Alignment.CenterStart))
                Letter(e.right, Modifier.align(Alignment.CenterEnd).padding(end = endInset))
                Letter(e.top, Modifier.align(Alignment.TopCenter).padding(top = topEndInset))
                Letter(e.bottom, Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}

/** Width reserved next to the bar for its label ("10 cm"). */
private val ScaleLabelWidth = 40.dp

private val OverlayStyle @Composable get() = MaterialTheme.typography.labelSmall.copy(
    shadow = Shadow(Color.Black, Offset(1f, 1f), blurRadius = 3f),
)

@Composable
private fun Letter(text: String, modifier: Modifier) {
    if (text.isEmpty()) return
    Text(text, modifier = modifier, color = LetterColor, maxLines = 1,
        style = MaterialTheme.typography.labelMedium.copy(
            fontWeight = FontWeight.Bold, shadow = Shadow(Color.Black, Offset(1f, 1f), blurRadius = 3f)))
}

private val LetterColor = Color(0xFFFFE082)

/** "10 cm |———|", right-aligned, the bar exactly [bar].px screen pixels long. */
@Composable
private fun ScaleBarRow(bar: ScaleBar.Bar) {
    val barDp = with(LocalDensity.current) { bar.px.toFloat().toDp() }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(bar.label, color = OverlayText, style = OverlayStyle, maxLines = 1)
        Spacer(Modifier.width(4.dp))
        Canvas(Modifier.width(barDp).height(8.dp)) {
            val y = size.height / 2
            val w = size.width
            for ((color, stroke) in listOf(Color.Black.copy(alpha = 0.7f) to 3.dp.toPx(), OverlayText to 1.dp.toPx())) {
                drawLine(color, Offset(0f, y), Offset(w, y), stroke)
                drawLine(color, Offset(0f, 0f), Offset(0f, size.height), stroke)
                drawLine(color, Offset(w, 0f), Offset(w, size.height), stroke)
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.Corner(
    lines: List<OverlayLine>, align: Alignment, modifier: Modifier, header: (@Composable () -> Unit)? = null,
) {
    if (lines.isEmpty() && header == null) return
    val end = align == Alignment.TopEnd || align == Alignment.BottomEnd
    val style = OverlayStyle
    Box(Modifier.align(align).then(modifier)) {
        Column(
            horizontalAlignment = if (end) Alignment.End else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            header?.invoke()
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
