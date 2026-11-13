package com.tauslop.dicomviewer.viewer

import android.app.Application
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tauslop.dicom.Series
import com.tauslop.dicom.SkipReason
import com.tauslop.dicom.Voi
import com.tauslop.dicom.Window
import com.tauslop.dicomviewer.data.SliceCache
import com.tauslop.dicomviewer.io.FolderScanner
import com.tauslop.dicomviewer.render.FrameRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SeriesSummary(val label: String, val detail: String, val count: Int)

data class ViewerState(
    val phase: Phase = Phase.Idle,
    val scanDone: Int = 0,
    val scanTotal: Int = 0,
    val series: List<SeriesSummary> = emptyList(),
    val seriesIndex: Int = 0,
    /** Slice the user asked for (scrubber position). */
    val targetIndex: Int = 0,
    /** Slice currently shown in [image]. */
    val sliceIndex: Int = 0,
    val sliceCount: Int = 0,
    val image: ImageBitmap? = null,
    val window: Window? = null,
    val zoom: Float = 1f,
    val pan: Offset = Offset.Zero,
    val skippedNote: String? = null,
    val error: String? = null,
) {
    enum class Phase { Idle, Scanning, Ready, Error }
}

class ViewerViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(ViewerState())
    val state: StateFlow<ViewerState> = _state.asStateFlow()

    private val scanner = FolderScanner(app.contentResolver)
    private val renderer = FrameRenderer()
    private val cache = SliceCache(app.contentResolver, (Runtime.getRuntime().maxMemory() / 4).toInt())
    private var series: List<Series<Uri>> = emptyList()
    private var scanJob: Job? = null
    private var prefetchJob: Job? = null
    private var lastShown = 0

    /** What to draw. A StateFlow is conflated, so fast scrolling skips stale requests. */
    private data class RenderRequest(val seriesIndex: Int, val index: Int, val window: Window?, val generation: Int)
    private val request = MutableStateFlow<RenderRequest?>(null)
    private var generation = 0

    init {
        viewModelScope.launch {
            request.filterNotNull().collect { render(it) }
        }
    }

    fun openFolder(tree: Uri) {
        scanJob?.cancel()
        prefetchJob?.cancel()
        generation++
        request.value = null
        cache.clear()
        _state.value = ViewerState(phase = ViewerState.Phase.Scanning)
        scanJob = viewModelScope.launch {
            try {
                val result = scanner.scan(tree) { done, total ->
                    _state.update { it.copy(scanDone = done, scanTotal = total) }
                }
                series = result.series
                val note = skippedNote(result.skipped)
                if (series.isEmpty()) {
                    _state.update {
                        it.copy(phase = ViewerState.Phase.Error, error = "No displayable images found.", skippedNote = note)
                    }
                    return@launch
                }
                _state.update {
                    it.copy(phase = ViewerState.Phase.Ready, series = series.map(::summary), skippedNote = note)
                }
                selectSeries(0)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(phase = ViewerState.Phase.Error, error = "Scan failed: ${e.message}") }
            }
        }
    }

    fun selectSeries(index: Int) {
        val s = series.getOrNull(index) ?: return
        generation++
        _state.update {
            it.copy(seriesIndex = index, targetIndex = 0, sliceIndex = 0, sliceCount = s.size, window = null,
                image = null, zoom = 1f, pan = Offset.Zero)
        }
        showSlice(0)
    }

    /** Jump to slice [index] (clamped). */
    fun showSlice(index: Int) {
        val st = _state.value
        if (st.sliceCount == 0) return
        val i = index.coerceIn(0, st.sliceCount - 1)
        _state.update { it.copy(targetIndex = i) }
        request.value = RenderRequest(st.seriesIndex, i, st.window, generation)
    }

    /** Move [steps] slices from the current target. */
    fun scrollBy(steps: Int) {
        if (steps != 0) showSlice(_state.value.targetIndex + steps)
    }

    fun setTransform(zoom: Float, pan: Offset) {
        _state.update { it.copy(zoom = zoom.coerceIn(0.5f, 20f), pan = pan) }
    }

    fun resetView() = setTransform(1f, Offset.Zero)

    private suspend fun render(r: RenderRequest) {
        val s = series.getOrNull(r.seriesIndex) ?: return
        val slice = s.slices.getOrNull(r.index) ?: return
        try {
            val loaded = cache.get(slice)
            if (r.generation != generation) return
            val window = r.window ?: _state.value.window ?: loaded.fileWindows.firstOrNull()
                ?: if (loaded.frame.gray != null) Voi.autoWindow(loaded.frame, loaded.rescale) else Window(127.5, 256.0)
            val bmp = withContext(Dispatchers.Default) { renderer.render(loaded.frame, loaded.rescale, window, userInvert = false) }
            if (r.generation != generation) return
            _state.update { it.copy(sliceIndex = r.index, image = bmp.asImageBitmap(), window = window, error = null) }
            prefetch(s, r.index, direction = if (r.index >= lastShown) 1 else -1)
            lastShown = r.index
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            _state.update { it.copy(sliceIndex = r.index, image = null, error = "Cannot display slice ${r.index + 1}: ${e.message}") }
        }
    }

    /** Warm the cache around [center], favouring the direction of travel. */
    private fun prefetch(s: Series<Uri>, center: Int, direction: Int) {
        prefetchJob?.cancel()
        prefetchJob = viewModelScope.launch {
            val order = (1..8).map { center + it * direction } + (1..3).map { center - it * direction }
            for (i in order) {
                val slice = s.slices.getOrNull(i) ?: continue
                if (cache.peek(slice) == null) runCatching { cache.get(slice) }
            }
        }
    }

    override fun onCleared() {
        cache.close()
    }

    private fun summary(s: Series<Uri>): SeriesSummary {
        val label = listOfNotNull(s.seriesNumber?.let { "#$it" }, s.description).joinToString(" ").ifEmpty { "Series" }
        return SeriesSummary(label, listOfNotNull(s.modality, "${s.size} images").joinToString(" · "), s.size)
    }

    private fun skippedNote(skipped: Map<SkipReason, Int>): String? {
        val total = skipped.values.sum()
        if (total == 0) return null
        val parts = skipped.entries.sortedBy { it.key.ordinal }.map { (k, v) ->
            when (k) {
                SkipReason.NOT_DICOM -> "$v not DICOM"
                SkipReason.NO_PIXEL_DATA -> "$v without pixel data"
                SkipReason.UNSUPPORTED_TRANSFER_SYNTAX -> "$v compressed (unsupported)"
                SkipReason.UNSUPPORTED_PIXEL_FORMAT -> "$v unsupported pixel format"
            }
        }
        return "$total files skipped: ${parts.joinToString(", ")}"
    }
}
