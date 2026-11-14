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
import androidx.compose.ui.unit.IntSize
import com.tauslop.dicomviewer.data.LoadedSlice
import com.tauslop.dicomviewer.data.Session
import com.tauslop.dicomviewer.data.SessionStore
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
    /** Name of the active preset; null once the user drags the window. */
    val presetName: String? = null,
    val presetNames: List<String> = emptyList(),
    /** One-finger drag adjusts window/level instead of scrolling. */
    val wlMode: Boolean = false,
    val zoom: Float = 1f,
    val pan: Offset = Offset.Zero,
    val skippedNote: String? = null,
    val error: String? = null,
    /** Informational message on the start screen (e.g. lost access to the last folder). */
    val notice: String? = null,
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

    /** Last slice drawn; source of file windows and the auto window. */
    private var current: LoadedSlice? = null
    /** Which of the file's windows "Default" is on (tapping Default again cycles). */
    private var fileWindowIndex = 0
    /** Span of modality values in the series' first slice, for W/L drag sensitivity. */
    private var valueSpan = 4096.0

    /** What to draw. A StateFlow is conflated, so fast scrolling skips stale requests. */
    private data class RenderRequest(val seriesIndex: Int, val index: Int, val window: Window?, val generation: Int)
    private val request = MutableStateFlow<RenderRequest?>(null)
    private var generation = 0

    private val sessions = SessionStore(app)
    private var tree: Uri? = null
    private var viewSize = IntSize.Zero

    init {
        viewModelScope.launch {
            request.filterNotNull().collect { render(it) }
        }
        restoreSession()
    }

    /** Reopen the last folder if we still hold a persisted read grant for it. */
    private fun restoreSession() {
        val s = sessions.load() ?: return
        val granted = getApplication<Application>().contentResolver.persistedUriPermissions
            .any { it.uri == s.tree && it.isReadPermission }
        if (granted) openFolder(s.tree, s)
        else {
            sessions.clear()
            _state.update { it.copy(notice = "Access to the last folder was lost. Please open it again.") }
        }
    }

    fun openFolder(tree: Uri) = openFolder(tree, restore = null)

    private fun openFolder(tree: Uri, restore: Session?) {
        scanJob?.cancel()
        prefetchJob?.cancel()
        generation++
        request.value = null
        current = null
        series = emptyList()
        cache.clear()
        this.tree = tree
        _state.value = ViewerState(phase = ViewerState.Phase.Scanning)
        scanJob = viewModelScope.launch {
            try {
                val result = scanner.scan(tree) { done, total ->
                    _state.update { it.copy(scanDone = done, scanTotal = total) }
                }
                series = result.series
                val note = skippedNote(result.skipped)
                if (series.isEmpty()) {
                    val msg = if (result.skipped.isEmpty()) "This folder contains no files." else "No displayable images in this folder."
                    _state.update { it.copy(phase = ViewerState.Phase.Error, error = msg, skippedNote = note) }
                    return@launch
                }
                _state.update {
                    it.copy(phase = ViewerState.Phase.Ready, series = series.map(::summary), skippedNote = note)
                }
                val idx = restore?.seriesUid?.let { uid -> series.indexOfFirst { it.seriesUid == uid } } ?: -1
                if (restore != null && idx >= 0) selectSeries(idx, restore.sliceIndex, restore.window, restore.presetName)
                else selectSeries(0)
                saveSession()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val msg = when (e) {
                    is SecurityException -> "Lost access to this folder. Please open it again."
                    else -> "Could not read folder: ${e.message ?: e.javaClass.simpleName}"
                }
                if (e is SecurityException) sessions.clear()
                _state.update { it.copy(phase = ViewerState.Phase.Error, error = msg) }
            }
        }
    }

    fun cancelScan() {
        scanJob?.cancel()
        tree = null
        _state.value = ViewerState(notice = "Scan cancelled.")
    }

    /** Persist folder, series, slice and window (called when the app goes to the background). */
    fun saveSession() {
        val t = tree ?: return
        val st = _state.value
        sessions.save(Session(t, series.getOrNull(st.seriesIndex)?.seriesUid, st.targetIndex, st.window, st.presetName))
    }

    fun selectSeries(index: Int) = selectSeries(index, 0, null, null)

    private fun selectSeries(index: Int, startSlice: Int, window: Window?, presetName: String?) {
        val s = series.getOrNull(index) ?: return
        generation++
        current = null
        fileWindowIndex = 0
        val start = startSlice.coerceIn(0, s.size - 1)
        _state.update {
            it.copy(seriesIndex = index, targetIndex = start, sliceIndex = start, sliceCount = s.size, window = window,
                presetName = if (window != null) presetName else null, presetNames = emptyList(), image = null,
                zoom = 1f, pan = Offset.Zero)
        }
        showSlice(start)
    }

    /**
     * The image area changed size (e.g. rotation). Scale the pan so the same image point stays
     * centred: pan is in screen pixels, and the fit scale changes with the view.
     */
    fun onViewSize(size: IntSize) {
        val old = viewSize
        viewSize = size
        val img = _state.value.image ?: return
        if (old == IntSize.Zero || old == size || size.width == 0 || size.height == 0) return
        val oldFit = minOf(old.width.toFloat() / img.width, old.height.toFloat() / img.height)
        val newFit = minOf(size.width.toFloat() / img.width, size.height.toFloat() / img.height)
        _state.update { it.copy(pan = it.pan * (newFit / oldFit)) }
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

    /** Reset zoom/pan and return to the default window. */
    fun resetAll() {
        resetView()
        fileWindowIndex = 0
        applyPreset(_state.value.presetNames.firstOrNull() ?: Presets.AUTO)
    }

    fun toggleWlMode() = _state.update { it.copy(wlMode = !it.wlMode) }

    fun applyPreset(name: String) {
        val loaded = current ?: return
        val window = when (name) {
            Presets.DEFAULT -> {
                if (loaded.fileWindows.isEmpty()) return
                // Tapping Default while it's active cycles through the file's windows.
                if (_state.value.presetName == Presets.DEFAULT) fileWindowIndex = (fileWindowIndex + 1) % loaded.fileWindows.size
                loaded.fileWindows[fileWindowIndex.coerceIn(0, loaded.fileWindows.size - 1)]
            }
            Presets.AUTO -> autoWindow(loaded)
            else -> Presets.ct.firstOrNull { it.label == name } ?: return
        }
        setWindow(window, name)
    }

    /**
     * One-finger drag in W/L mode: right widens, up raises the level (image darker).
     * A full-width drag spans the series' value range.
     */
    fun dragWindow(dx: Float, dy: Float, viewWidth: Float) {
        val w = _state.value.window ?: return
        val perPx = valueSpan / maxOf(viewWidth, 1f)
        setWindow(Window(w.center - dy * perPx, maxOf(1.0, w.width + dx * perPx)), name = null)
    }

    private fun setWindow(window: Window, name: String?) {
        _state.update { it.copy(window = window, presetName = name) }
        val st = _state.value
        request.value = RenderRequest(st.seriesIndex, st.targetIndex, window, generation)
    }

    private fun autoWindow(loaded: LoadedSlice): Window =
        if (loaded.frame.gray != null) Voi.autoWindow(loaded.frame, loaded.rescale) else Window(127.5, 256.0)

    private suspend fun render(r: RenderRequest) {
        val s = series.getOrNull(r.seriesIndex) ?: return
        val slice = s.slices.getOrNull(r.index) ?: return
        try {
            val loaded = cache.get(slice)
            if (r.generation != generation) return
            val first = current == null
            current = loaded
            var window = r.window ?: _state.value.window
            var name = _state.value.presetName
            if (window == null) { // first slice of a series: file default, else auto
                window = loaded.fileWindows.firstOrNull() ?: autoWindow(loaded)
                name = if (loaded.fileWindows.isNotEmpty()) Presets.DEFAULT else Presets.AUTO
            }
            if (first) {
                valueSpan = withContext(Dispatchers.Default) { span(loaded) }
                _state.update { it.copy(presetNames = Presets.labelsFor(s.modality, loaded.fileWindows.isNotEmpty())) }
            }
            val bmp = withContext(Dispatchers.Default) { renderer.render(loaded.frame, loaded.rescale, window, userInvert = false) }
            if (r.generation != generation) return
            _state.update {
                it.copy(sliceIndex = r.index, image = bmp.asImageBitmap(), window = it.window ?: window,
                    presetName = if (it.window == null) name else it.presetName, error = null)
            }
            if (r.index != lastShown) prefetch(s, r.index, direction = if (r.index > lastShown) 1 else -1)
            else if (first) prefetch(s, r.index, 1)
            lastShown = r.index
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            _state.update { it.copy(sliceIndex = r.index, image = null, error = "Cannot display slice ${r.index + 1}: ${e.message}") }
        }
    }

    private fun span(loaded: LoadedSlice): Double {
        if (loaded.frame.gray == null) return 256.0
        val (lo, hi) = loaded.frame.storedRange().let { it[0] to it[1] }
        return maxOf(kotlin.math.abs(loaded.rescale.apply(hi) - loaded.rescale.apply(lo)), 64.0)
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
