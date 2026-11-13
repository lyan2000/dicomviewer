package com.tauslop.dicomviewer.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** UI callbacks, bundled so the screen signature stays stable as tools are added. */
class ViewerActions(
    val onOpenFolder: () -> Unit,
    val onSelectSeries: (Int) -> Unit,
    val onSelectSlice: (Int) -> Unit,
    val onScroll: (Int) -> Unit,
    val onTransform: (Float, Offset) -> Unit,
    val onResetView: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(state: ViewerState, actions: ViewerActions) {
    val onOpenFolder = actions.onOpenFolder
    val onSelectSeries = actions.onSelectSeries
    var pickerOpen by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = { Text(statusText(state), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    if (state.series.size > 1) TextButton(onClick = { pickerOpen = true }) { Text("Series") }
                    TextButton(onClick = onOpenFolder) { Text("Open") }
                },
            )
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad).background(Color.Black), contentAlignment = Alignment.Center) {
            when (state.phase) {
                ViewerState.Phase.Idle -> Button(onClick = onOpenFolder) { Text("Open folder") }
                ViewerState.Phase.Scanning -> Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (state.scanTotal == 0) "Listing files…" else "Reading headers ${state.scanDone} / ${state.scanTotal}")
                    Spacer(Modifier.height(12.dp))
                    if (state.scanTotal > 0) LinearProgressIndicator(
                        progress = { state.scanDone.toFloat() / state.scanTotal },
                        modifier = Modifier.fillMaxWidth(),
                    ) else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                ViewerState.Phase.Error -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(state.error ?: "Error")
                    state.skippedNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onOpenFolder) { Text("Open another folder") }
                }
                ViewerState.Phase.Ready -> Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        ImageCanvas(
                            image = state.image,
                            zoom = state.zoom,
                            pan = state.pan,
                            sliceCount = state.sliceCount,
                            onScroll = actions.onScroll,
                            onTransform = actions.onTransform,
                            onResetView = actions.onResetView,
                        )
                        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        state.skippedNote?.let {
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
                        Modifier.fillMaxWidth().clickable { pickerOpen = false; onSelectSeries(i) }
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
    return "${s.sliceIndex + 1}/${s.sliceCount}$w"
}
