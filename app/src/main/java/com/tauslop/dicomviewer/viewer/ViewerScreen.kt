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
import androidx.compose.material3.FilterChip
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
import kotlin.math.roundToInt

/** UI callbacks, bundled so the screen signature stays stable as tools are added. */
class ViewerActions(
    val onOpenFolder: () -> Unit,
    val onSelectSeries: (Int) -> Unit,
    val onSelectSlice: (Int) -> Unit,
    val onScroll: (Int) -> Unit,
    val onTransform: (Float, Offset) -> Unit,
    val onResetView: () -> Unit,
    val onResetAll: () -> Unit,
    val onToggleWl: () -> Unit,
    val onPreset: (String) -> Unit,
    val onWindowDrag: (Float, Float, Float) -> Unit,
    val onCancelScan: () -> Unit,
    val onViewSize: (IntSize) -> Unit,
    val onSwap: (MprView) -> Unit,
)

private val Accent = Color(0xFF8AB4F8)

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
                            TextButton(onClick = actions.onToggleWl) {
                                Text("W/L", color = if (state.wlMode) Accent else Color.Gray)
                            }
                            TextButton(onClick = actions.onResetAll) { Text("Reset") }
                        }
                        if (state.series.size > 1) TextButton(onClick = { pickerOpen = true }) { Text("Series") }
                        TextButton(onClick = actions.onOpenFolder) { Text("Open") }
                    },
                )
                if (ready && state.presetNames.isNotEmpty()) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.presetNames) { name ->
                            FilterChip(
                                selected = name == state.presetName,
                                onClick = { actions.onPreset(name) },
                                label = { Text(name) },
                            )
                        }
                    }
                }
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
                            wlMode = state.wlMode,
                            onWindowDrag = actions.onWindowDrag,
                        )
                        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (state.wlMode) {
                            Text("W/L mode: drag ↔ width, ↕ level", style = MaterialTheme.typography.labelSmall, color = Accent,
                                modifier = Modifier.align(Alignment.TopCenter).padding(8.dp))
                        }
                        listOfNotNull(state.stackReason, state.skippedNote).joinToString("\n").takeIf { it.isNotEmpty() }?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall, color = Color.Gray,
                                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp))
                        }
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

private fun statusText(s: ViewerState): String {
    if (s.phase != ViewerState.Phase.Ready || s.sliceCount == 0) return "DICOM Viewer"
    val w = s.window?.let { " · W ${it.width.roundToInt()} L ${it.center.roundToInt()}" } ?: ""
    val p = s.presetName?.let { " · $it" } ?: ""
    val view = s.mpr?.let { "${it.mainLabel} " } ?: ""
    return "$view${s.sliceIndex + 1}/${s.sliceCount}$w$p"
}
