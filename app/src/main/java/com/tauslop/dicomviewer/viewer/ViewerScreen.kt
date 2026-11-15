package com.tauslop.dicomviewer.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.tauslop.dicom.MprView
import com.tauslop.dicomviewer.data.DisplayPrefs
import com.tauslop.dicom.vr.RenderMode

/** UI callbacks, bundled so the screen signature stays stable as tools are added. */
class ViewerActions(
    val onOpenFolder: () -> Unit,
    val onSelectSeries: (Int) -> Unit,
    val onSelectSlice: (Int) -> Unit,
    val onScroll: (Int) -> Unit,
    val onTransform: (Float, Offset) -> Unit,
    val onResetView: () -> Unit,
    val onResetAll: () -> Unit,
    val onTool: (Tool) -> Unit,
    val onPreset: (String) -> Unit,
    val onWindowDrag: (Float, Float, Float) -> Unit,
    val onCancelScan: () -> Unit,
    val onViewSize: (IntSize) -> Unit,
    val onSwap: (MprView) -> Unit,
    val onSwap3D: () -> Unit,
    val onVrPreset: (String) -> Unit,
    val onVrMode: (RenderMode) -> Unit,
    val onView3D: (MprView) -> Unit,
    val onProbe: (Float, Float) -> Unit,
    val onProbeEnd: () -> Unit,
    val onToggleOverlay: () -> Unit,
    val onToggleHidePatient: () -> Unit,
    val onToggleShading: () -> Unit,
    val onRotate3D: (Float, Float, Float) -> Unit,
    val onTapImage: (Float, Float) -> Unit,
    val onTwist: (Float) -> Unit,
    val onResetPlanes: () -> Unit,
)

private val Accent = Color(0xFF8AB4F8)
/** Selected render-mode chip, set apart from the preset chips' selection colour. */
private val ModeSelected = Color(0xFF3C4A5E)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(state: ViewerState, actions: ViewerActions) {
    var pickerOpen by remember { mutableStateOf(false) }
    val ready = state.phase == ViewerState.Phase.Ready

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(statusText(state), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    actions = {
                        if (ready) {
                            if (state.mpr?.oblique == true) {
                                TextButton(onClick = actions.onResetPlanes) { Text("Planes ⟲", color = Accent) }
                            }
                            TextButton(onClick = actions.onResetAll) { Text("Reset") }
                        }
                        if (state.series.size > 1) TextButton(onClick = { pickerOpen = true }) { Text("Series") }
                        TextButton(onClick = actions.onOpenFolder) { Text("Open") }
                        DisplayMenu(state.display, actions)
                    },
                )
                // With 3D in the main view the chips select 3D presets; otherwise MPR/stack windows.
                val vr = state.mpr?.takeIf { it.threeDMain }?.vr
                val chipNames = vr?.presets ?: state.presetNames
                val selected = if (vr != null) vr.presetName else state.presetName
                val onChip = if (vr != null) actions.onVrPreset else actions.onPreset
                if (ready && chipNames.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                    // 3D main: VR / MIP / MinIP stay put at the start; the mode's presets scroll.
                    if (vr != null) {
                        Row(Modifier.padding(start = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            RenderMode.entries.forEach { m ->
                                FilterChip(
                                    selected = vr.mode == m,
                                    onClick = { actions.onVrMode(m) },
                                    label = { Text(m.label) },
                                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = ModeSelected),
                                )
                            }
                        }
                        VerticalDivider(Modifier.height(24.dp).padding(start = 8.dp))
                    }
                    LazyRow(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = if (vr != null) 8.dp else 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(chipNames) { name ->
                            FilterChip(
                                selected = name == selected,
                                onClick = { onChip(name) },
                                label = { Text(name) },
                            )
                        }
                    }
                }
                if (ready) ToolRow(state, actions)
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad).background(Color.Black), contentAlignment = Alignment.Center) {
            when (state.phase) {
                ViewerState.Phase.Idle -> Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Open a folder of DICOM files to view it.", color = Color.Gray, textAlign = TextAlign.Center)
                    state.notice?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = Accent, textAlign = TextAlign.Center)
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = actions.onOpenFolder) { Text("Open folder") }
                }
                ViewerState.Phase.Scanning -> Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (state.scanTotal == 0) "Listing files…" else "Reading headers ${state.scanDone} / ${state.scanTotal}")
                    Spacer(Modifier.height(12.dp))
                    if (state.scanTotal > 0) LinearProgressIndicator(
                        progress = { state.scanDone.toFloat() / state.scanTotal },
                        modifier = Modifier.fillMaxWidth(),
                    ) else LinearProgressIndicator(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = actions.onCancelScan) { Text("Cancel") }
                }
                ViewerState.Phase.Building -> Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Building volume ${state.scanDone} / ${state.scanTotal}")
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { if (state.scanTotal == 0) 0f else state.scanDone.toFloat() / state.scanTotal },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = actions.onCancelScan) { Text("Cancel") }
                }
                ViewerState.Phase.Error -> Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(state.error ?: "Error", textAlign = TextAlign.Center)
                    state.skippedNote?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = Color.Gray, textAlign = TextAlign.Center)
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = actions.onOpenFolder) { Text("Open another folder") }
                }
                ViewerState.Phase.Ready -> if (state.mpr != null) {
                    MprLayout(state, state.mpr, actions)
                } else Row(Modifier.fillMaxSize()) {
                    Box(
                        Modifier.weight(1f).fillMaxHeight().onSizeChanged(actions.onViewSize),
                        contentAlignment = Alignment.Center,
                    ) {
                        ImageCanvas(
                            image = state.image,
                            zoom = state.zoom,
                            pan = state.pan,
                            sliceCount = state.sliceCount,
                            onScroll = actions.onScroll,
                            onTransform = actions.onTransform,
                            onResetView = actions.onResetView,
                            tool = state.tool,
                            onWindowDrag = actions.onWindowDrag,
                            onProbe = actions.onProbe,
                            onProbeEnd = actions.onProbeEnd,
                            probe = state.probe?.let { Offset(it.u, it.v) },
                        )
                        // Corner text, including the "MPR unavailable" / skipped-files notes (bottom left).
                        // The scrubber is beside this box, so no inset is needed.
                        if (state.display.showOverlay) {
                            CornerOverlay(overlayFor(state), geom = state.stackGeom,
                                imgW = state.image?.width ?: 0, imgH = state.image?.height ?: 0, zoom = state.zoom)
                        }
                        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                    if (state.sliceCount > 1) {
                        SliceScrubber(state.targetIndex, state.sliceCount, actions.onSelectSlice)
                    }
                }
            }
        }
    }

    if (pickerOpen) {
        ModalBottomSheet(onDismissRequest = { pickerOpen = false }) {
            LazyColumn {
                itemsIndexed(state.series) { i, s ->
                    Column(
                        Modifier.fillMaxWidth().clickable { pickerOpen = false; actions.onSelectSeries(i) }
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(s.label, color = if (i == state.seriesIndex) MaterialTheme.colorScheme.primary else Color.Unspecified)
                        Text(s.detail, style = MaterialTheme.typography.bodySmall)
                    }
                }
                item { Spacer(Modifier.height(24.dp).width(1.dp)) }
            }
        }
    }
}

/**
 * Mutually exclusive tool toggles for one-finger drag. Tapping the active tool turns it off,
 * returning to the default (slice scrolling). Oblique is only offered in MPR.
 */
@Composable
private fun ToolRow(state: ViewerState, actions: ViewerActions) {
    val tools = listOf(Tool.PAN, Tool.ZOOM, Tool.WL) +
        if (state.mpr != null && !state.mpr.threeDMain) listOf(Tool.OBLIQUE) else emptyList()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tools.forEach { t ->
            FilterChip(selected = state.tool == t, onClick = { actions.onTool(t) }, label = { Text(t.label) })
        }
        // 3D volume rendering only, independent of the drag tool: lighting on/off.
        state.mpr?.takeIf { it.threeDMain && it.vr.mode == RenderMode.VR }?.let { m ->
            FilterChip(selected = m.vr.shading, onClick = actions.onToggleShading, label = { Text("Shading") })
        }
        Spacer(Modifier.weight(1f))
        val hint = if (state.tool == Tool.SCROLL && state.mpr?.threeDMain == true) "drag to rotate" else toolHint(state.tool)
        Text(hint, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
    }
}

private fun toolHint(t: Tool): String = when (t) {
    Tool.SCROLL -> "drag ↕ slices"
    Tool.PAN -> "drag to pan"
    Tool.ZOOM -> "drag ↑ in, ↓ out"
    Tool.WL -> "↔ width, ↕ level"
    Tool.OBLIQUE -> "drag around ✛ to tilt"
}

/** "⋮" menu in the app bar: overlay on/off and hide patient details (both remembered across launches). */
@Composable
private fun DisplayMenu(d: DisplayPrefs, actions: ViewerActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text("⋮") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Show overlay") },
                onClick = { actions.onToggleOverlay() },
                trailingIcon = { Checkbox(checked = d.showOverlay, onCheckedChange = null) },
            )
            DropdownMenuItem(
                text = { Text("Hide patient details") },
                onClick = { actions.onToggleHidePatient() },
                trailingIcon = { Checkbox(checked = d.hidePatient, onCheckedChange = null) },
            )
        }
    }
}

/** App bar title: the app name until an image is shown; then the corner overlay carries the status. */
private fun statusText(s: ViewerState): String =
    if (s.phase != ViewerState.Phase.Ready || s.sliceCount == 0) "DICOM Viewer" else ""
