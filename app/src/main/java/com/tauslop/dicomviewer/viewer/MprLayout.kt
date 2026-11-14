package com.tauslop.dicomviewer.viewer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.tauslop.dicom.MprView
import kotlin.math.min
import kotlin.math.roundToInt

/** Plane colours used for borders (and reference lines in step 3). */
fun MprView.color(): Color = when (this) {
    MprView.AXIAL -> Color(0xFF4FA3FF)
    MprView.CORONAL -> Color(0xFF5BD46B)
    MprView.SAGITTAL -> Color(0xFFFFA040)
}

/**
 * Square main view plus small views. Portrait: small views in a row below the square.
 * Landscape: stacked to the right. Tapping a small view swaps it into the main slot.
 */
@Composable
fun MprLayout(state: ViewerState, mpr: MprUi, actions: ViewerActions) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val portrait = maxHeight >= maxWidth
        if (portrait) {
            val main = min(maxWidth.value, (maxHeight - maxWidth / 3.2f).value).dp
            Column {
                MainView(state, mpr, actions, main)
                Row(Modifier.padding(top = 4.dp)) { SmallViews(mpr, actions) }
            }
        } else {
            val main = min(maxHeight.value, (maxWidth - maxHeight / 3.2f).value).dp
            Row {
                MainView(state, mpr, actions, main)
                Column(Modifier.padding(start = 4.dp)) { SmallViews(mpr, actions) }
            }
        }
    }
}

@Composable
private fun MainView(state: ViewerState, mpr: MprUi, actions: ViewerActions, size: Dp) {
    val is3d = mpr.threeDMain
    val color = if (is3d) ThreeDColor else mpr.main.color()
    Box(
        Modifier.size(size).border(2.dp, color).padding(2.dp).onSizeChanged(actions.onViewSize),
    ) {
        ImageCanvas(
            image = if (is3d) mpr.vr.image else state.image,
            zoom = state.zoom,
            pan = state.pan,
            sliceCount = state.sliceCount,
            onScroll = actions.onScroll,
            onTransform = actions.onTransform,
            onResetView = actions.onResetView,
            tool = state.tool,
            onWindowDrag = actions.onWindowDrag,
            lines = if (is3d) emptyList() else mpr.mainLines,
            onTap = if (is3d) null else actions.onTapImage,
            onTwist = actions.onTwist,
        )
        Text(if (is3d) mpr.vr.label else mpr.mainLabel, color = color, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.align(Alignment.TopStart).padding(6.dp))
        if (!is3d && state.sliceCount > 1) {
            SliceScrubber(state.targetIndex, state.sliceCount, actions.onSelectSlice, Modifier.align(Alignment.CenterEnd))
        }
    }
}

private val ThreeDColor = Color(0xFFE0E0E0)

@Composable
private fun RowScope.SmallViews(mpr: MprUi, actions: ViewerActions) {
    mpr.small.forEach { SmallTile(it, actions, Modifier.weight(1f)) }
    if (!mpr.threeDMain) ThreeDTile(mpr.vr, actions, Modifier.weight(1f))
}

@Composable
private fun ColumnScope.SmallViews(mpr: MprUi, actions: ViewerActions) {
    mpr.small.forEach { SmallTile(it, actions, Modifier.weight(1f)) }
    if (!mpr.threeDMain) ThreeDTile(mpr.vr, actions, Modifier.weight(1f))
}

/** Small 3D tile: the latest render, or a status message while uploading. Tap to make it main. */
@Composable
private fun ThreeDTile(vr: VrUi, actions: ViewerActions, modifier: Modifier) {
    Box(
        modifier.padding(2.dp).border(1.dp, ThreeDColor).clickable(enabled = vr.image != null) { actions.onSwap3D() }
            .padding(1.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (vr.image != null) FittedImage(vr.image, emptyList())
        else Text(vr.label, color = Color.Gray, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center,
            modifier = Modifier.padding(4.dp))
        if (vr.image != null) {
            Text(vr.label, color = ThreeDColor, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.TopStart).padding(4.dp))
        }
    }
}

@Composable
private fun SmallTile(v: SmallView, actions: ViewerActions, modifier: Modifier) {
    Box(
        modifier.padding(2.dp).border(1.dp, v.view.color()).clickable { actions.onSwap(v.view) }.padding(1.dp),
    ) {
        v.image?.let { FittedImage(it, v.lines) }
        Text(v.label, color = v.view.color(), style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.TopStart).padding(4.dp))
    }
}

/** Draws [img] scaled to fit, centred, preserving aspect, with its reference lines. */
@Composable
private fun FittedImage(img: ImageBitmap, lines: List<RefLine>) {
    Canvas(Modifier.fillMaxSize().clipToBounds()) {
        val fit = min(size.width / img.width, size.height / img.height)
        val w = (img.width * fit).roundToInt()
        val h = (img.height * fit).roundToInt()
        val left = ((size.width - w) / 2).roundToInt()
        val top = ((size.height - h) / 2).roundToInt()
        drawImage(
            img,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(img.width, img.height),
            dstOffset = IntOffset(left, top),
            dstSize = IntSize(w, h),
            filterQuality = FilterQuality.Low,
        )
        drawRefLines(lines, img.width, img.height, Offset(left.toFloat(), top.toFloat()), fit,
            strokePx = 1.dp.toPx(), gapPx = 5.dp.toPx())
    }
}
