package com.tauslop.dicomviewer.viewer

import android.app.Application
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tauslop.dicom.Frame
import com.tauslop.dicom.MprView
import com.tauslop.dicom.Plane
import com.tauslop.dicom.Reslicer
import com.tauslop.dicom.Rotation
import com.tauslop.dicom.Series
import com.tauslop.dicom.SkipReason
import com.tauslop.dicom.Vec3
import com.tauslop.dicom.Voi
import com.tauslop.dicom.VolumeBuilder
import com.tauslop.dicom.Window
import com.tauslop.dicomviewer.data.LoadedSlice
import com.tauslop.dicomviewer.data.Session
import com.tauslop.dicomviewer.data.SessionStore
import com.tauslop.dicomviewer.data.SliceCache
import com.tauslop.dicomviewer.io.FolderScanner
import com.tauslop.dicomviewer.mpr.MprSession
import com.tauslop.dicomviewer.render.FrameRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

data class SeriesSummary(val label: String, val detail: String, val count: Int)

data class ViewerState(
    val phase: Phase = Phase.Idle,
    val scanDone: Int = 0,
    val scanTotal: Int = 0,
    val series: List<SeriesSummary> = emptyList(),
    val seriesIndex: Int = 0,
    /** Slice the user asked for (scrubber position). In MPR mode, the main view's depth index. */
    val targetIndex: Int = 0,
    /** Slice currently shown in [image]. */
    val sliceIndex: Int = 0,
    val sliceCount: Int = 0,
    /** Main image (stack slice, or the main MPR view). */
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
    /** MPR: non-null once a volume is built. */
    val mpr: MprUi? = null,
    /** Why this series is shown as a plain stack instead of MPR. */
    val stackReason: String? = null,
) {
    enum class Phase { Idle, Scanning, Building, Ready, Error }
}

/** What the MPR layout needs to draw. */
data class MprUi(
    val main: MprView,
    val mainLabel: String,
    /** Reference lines for the main image, in its pixel coordinates. */
    val mainLines: List<RefLine>,
    val small: List<SmallView>,
)

data class SmallView(val view: MprView, val label: String, val image: ImageBitmap?, val lines: List<RefLine>)

/**
 * Where plane [of] cuts an image: the line through pixel ([u], [v]) (the cursor) with unit
 * direction ([du], [dv]), in that image's pixel coordinates.
 */
data class RefLine(val of: MprView, val u: Float, val v: Float, val du: Float, val dv: Float)

class ViewerViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(ViewerState())
    val state: StateFlow<ViewerState> = _state.asStateFlow()

    private val scanner = FolderScanner(app.contentResolver)
    private val renderer = FrameRenderer()
    private val maxMemory = Runtime.getRuntime().maxMemory()
    private val cache = SliceCache(app.contentResolver, (maxMemory / 4).toInt())
    private var series: List<Series<Uri>> = emptyList()
    private var scanJob: Job? = null
    private var buildJob: Job? = null
    private var prefetchJob: Job? = null
    private var lastShown = 0

    /** First slice loaded for the series: source of file windows. */
    private var current: LoadedSlice? = null
    /** Last main frame drawn and its rescale: source of the auto window. */
    private var mainFrame: Frame? = null
    private var mainRescale: Voi.Rescale? = null
    /** Which of the file's windows "Default" is on (tapping Default again cycles). */
    private var fileWindowIndex = 0
    /** Span of modality values in the series' first slice, for W/L drag sensitivity. */
    private var valueSpan = 4096.0

    private var mpr: MprSession? = null
    private val smallImages = HashMap<MprView, ImageBitmap>()
    /** Reference lines matching each view's last rendered image. */
    private val viewLines = HashMap<MprView, List<RefLine>>()
    /** One renderer per view: each keeps a pixel buffer sized to its plane. */
    private val viewRenderers = MprView.entries.associateWith { FrameRenderer() }

    private sealed interface Req { val generation: Int }
    /** Stack mode: draw slice [index]. */
    private data class StackReq(val seriesIndex: Int, val index: Int, val window: Window?, override val generation: Int) : Req
    /** MPR mode: draw all views for this cursor/rotation/window. */
    private data class MprReq(
        val cursor: Vec3, val rotation: Rotation, val main: MprView, val window: Window, override val generation: Int,
    ) : Req

    /** What to draw. A StateFlow is conflated, so fast interaction skips stale requests. */
    private val request = MutableStateFlow<Req?>(null)
    private var generation = 0

    private val sessions = SessionStore(app)
    private var tree: Uri? = null
    private var viewSize = IntSize.Zero

    init {
        viewModelScope.launch {
            request.filterNotNull().collect { r ->
                when (r) {
                    is StackReq -> renderStack(r)
                    is MprReq -> renderMpr(r)
                }
            }
        }
        restoreSession()
    }

    // ---- folder / session --------------------------------------------------

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
        buildJob?.cancel()
        prefetchJob?.cancel()
        generation++
        request.value = null
        current = null
        mpr = null
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
                _state.update { it.copy(series = series.map(::summary), skippedNote = note) }
                val idx = restore?.seriesUid?.let { uid -> series.indexOfFirst { it.seriesUid == uid } } ?: -1
                if (restore != null && idx >= 0) selectSeries(idx, restore.sliceIndex, restore.window, restore.presetName)
                else selectSeries(0)
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
        buildJob?.cancel()
        tree = null
        _state.value = ViewerState(notice = "Cancelled.")
    }

    /** Persist folder, series, slice and window (called when the app goes to the background). */
    fun saveSession() {
        val t = tree ?: return
        val st = _state.value
        val slice = mpr?.let { it.depth(MprView.AXIAL).index } ?: st.targetIndex
        sessions.save(Session(t, series.getOrNull(st.seriesIndex)?.seriesUid, slice, st.window, st.presetName))
    }

    // ---- series / volume ---------------------------------------------------

    fun selectSeries(index: Int) = selectSeries(index, 0, null, null)

    private fun selectSeries(index: Int, startSlice: Int, window: Window?, presetName: String?) {
        val s = series.getOrNull(index) ?: return
        buildJob?.cancel()
        prefetchJob?.cancel()
        generation++
        current = null
        mpr = null
        smallImages.clear()
        viewLines.clear()
        fileWindowIndex = 0
        val start = startSlice.coerceIn(0, s.size - 1)
        _state.update {
            it.copy(seriesIndex = index, targetIndex = start, sliceIndex = start, sliceCount = s.size, window = window,
                presetName = if (window != null) presetName else null, presetNames = emptyList(), image = null,
                zoom = 1f, pan = Offset.Zero, mpr = null, stackReason = null)
        }
        when (val check = VolumeBuilder.plan(s, (maxMemory * 0.4).toLong())) {
            is VolumeBuilder.Check.Rejected -> {
                _state.update { it.copy(phase = ViewerState.Phase.Ready, stackReason = "MPR unavailable: ${check.reason}") }
                showSlice(start)
            }
            is VolumeBuilder.Check.Ok -> buildVolume(s, check.plan, start)
        }
    }

    private fun buildVolume(s: Series<Uri>, plan: VolumeBuilder.Plan, startSlice: Int) {
        val gen = generation
        _state.update { it.copy(phase = ViewerState.Phase.Building, scanDone = 0, scanTotal = s.size) }
        buildJob = viewModelScope.launch {
            try {
                val done = AtomicInteger()
                val permits = Semaphore(3)
                val first = cache.get(s.slices.first())
                withContext(Dispatchers.Default) {
                    coroutineScope {
                        s.slices.mapIndexed { k, slice ->
                            async {
                                permits.withPermit {
                                    val l = cache.get(slice)
                                    plan.put(k, l.frame, l.rescale)
                                    _state.update { it.copy(scanDone = done.incrementAndGet()) }
                                }
                            }
                        }.awaitAll()
                    }
                }
                val vol = withContext(Dispatchers.Default) { plan.finish() }
                if (gen != generation) return@launch
                cache.clear()
                current = first
                valueSpan = withContext(Dispatchers.Default) {
                    maxOf((vol.maxValue - vol.minValue) * kotlin.math.abs(vol.rescale.slope), 64.0)
                }
                val session = MprSession(vol).apply { goTo(MprView.AXIAL, startSlice) }
                mpr = session
                val st = _state.value
                val window = st.window ?: first.fileWindows.firstOrNull() ?: autoWindow(first.frame, first.rescale)
                val name = if (st.window != null) st.presetName
                    else if (first.fileWindows.isNotEmpty()) Presets.DEFAULT else Presets.AUTO
                _state.update {
                    it.copy(phase = ViewerState.Phase.Ready, window = window, presetName = name,
                        presetNames = Presets.labelsFor(s.modality, first.fileWindows.isNotEmpty()))
                }
                requestMpr()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // Fall back to the plain stack viewer.
                _state.update {
                    it.copy(phase = ViewerState.Phase.Ready, stackReason = "MPR unavailable: ${e.message ?: e.javaClass.simpleName}")
                }
                showSlice(startSlice)
            }
        }
    }

    // ---- navigation --------------------------------------------------------

    /** Jump to slice [index] (clamped) of the main view. */
    fun showSlice(index: Int) {
        val m = mpr
        if (m != null) {
            m.goTo(m.main, index)
            requestMpr()
            return
        }
        val st = _state.value
        if (st.sliceCount == 0) return
        val i = index.coerceIn(0, st.sliceCount - 1)
        _state.update { it.copy(targetIndex = i) }
        request.value = StackReq(st.seriesIndex, i, st.window, generation)
    }

    /** Move [steps] slices from the current target. */
    fun scrollBy(steps: Int) {
        if (steps == 0) return
        val m = mpr
        if (m != null) { m.scroll(m.main, steps); requestMpr() }
        else showSlice(_state.value.targetIndex + steps)
    }

    /** Swap a small view into the main slot. */
    fun swapMain(view: MprView) {
        val m = mpr ?: return
        if (m.main == view) return
        m.main = view
        _state.update { it.copy(zoom = 1f, pan = Offset.Zero, image = smallImages[view] ?: it.image, mpr = mprUi(m)) }
        requestMpr()
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

    // ---- window/level ------------------------------------------------------

    fun applyPreset(name: String) {
        val loaded = current ?: return
        val window = when (name) {
            Presets.DEFAULT -> {
                if (loaded.fileWindows.isEmpty()) return
                if (_state.value.presetName == Presets.DEFAULT) fileWindowIndex = (fileWindowIndex + 1) % loaded.fileWindows.size
                loaded.fileWindows[fileWindowIndex.coerceIn(0, loaded.fileWindows.size - 1)]
            }
            Presets.AUTO -> autoWindow(mainFrame ?: loaded.frame, mainRescale ?: loaded.rescale)
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
        if (mpr != null) requestMpr()
        else {
            val st = _state.value
            request.value = StackReq(st.seriesIndex, st.targetIndex, window, generation)
        }
    }

    private fun autoWindow(frame: Frame, rescale: Voi.Rescale): Window =
        if (frame.gray != null) Voi.autoWindow(frame, rescale) else Window(127.5, 256.0)

    // ---- MPR rendering -----------------------------------------------------

    private fun requestMpr() {
        val m = mpr ?: return
        val window = _state.value.window ?: return
        val d = m.depth(m.main)
        _state.update { it.copy(targetIndex = d.index, sliceCount = d.count) }
        request.value = MprReq(m.cursor, m.rotation, m.main, window, generation)
    }

    private suspend fun renderMpr(r: MprReq) {
        val m = mpr ?: return
        val vol = m.volume
        try {
            val mainPlane = Reslicer.planeFor(vol, r.main, r.cursor, r.rotation)
            val (frame, bmp) = withContext(Dispatchers.Default) {
                val f = Reslicer.sample(vol, mainPlane)
                f to viewRenderers.getValue(r.main).render(f, vol.rescale, r.window, userInvert = false).asImageBitmap()
            }
            if (r.generation != generation) return
            mainFrame = frame
            mainRescale = vol.rescale
            smallImages[r.main] = bmp
            viewLines[r.main] = refLines(r.main, mainPlane, r)
            val d = Reslicer.depth(vol, r.rotation.apply(r.main.scroll), r.cursor)
            _state.update { it.copy(image = bmp, sliceIndex = d.index, sliceCount = d.count, error = null, mpr = mprUi(m)) }

            // Small views: skip if a newer request is already waiting (keeps the main view responsive).
            for (v in MprView.entries.filter { it != r.main }) {
                if (request.value !== r) return
                val p = Reslicer.planeFor(vol, v, r.cursor, r.rotation)
                val small = withContext(Dispatchers.Default) {
                    viewRenderers.getValue(v).render(Reslicer.sample(vol, p), vol.rescale, r.window, userInvert = false).asImageBitmap()
                }
                if (r.generation != generation) return
                smallImages[v] = small
                viewLines[v] = refLines(v, p, r)
            }
            _state.update { it.copy(mpr = mprUi(m)) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            _state.update { it.copy(error = "Cannot render: ${e.message}") }
        }
    }

    private fun mprUi(m: MprSession) = MprUi(
        main = m.main,
        mainLabel = m.label(m.main),
        mainLines = viewLines[m.main].orEmpty(),
        small = m.smallViews.map { SmallView(it, m.label(it), smallImages[it], viewLines[it].orEmpty()) },
    )

    /** Lines where the other two planes cut [view]'s [plane], all through the cursor. */
    private fun refLines(view: MprView, plane: Plane, r: MprReq): List<RefLine> {
        val (u, v) = plane.pixelOf(r.cursor)
        return MprView.entries.filter { it != view }.map { other ->
            val dir = (plane.normal cross r.rotation.apply(other.scroll)).normalized()
            val du = dir dot plane.right
            val dv = dir dot plane.down
            RefLine(other, u.toFloat(), v.toFloat(), du.toFloat(), dv.toFloat())
        }
    }

    /** Single tap on the main image at pixel ([u], [v]): move the cursor there (depth unchanged). */
    fun tapAt(u: Float, v: Float) {
        val m = mpr ?: return
        val plane = m.plane(m.main)
        if (u < 0 || v < 0 || u > plane.width - 1 || v > plane.height - 1) return
        m.cursor = plane.pointAt(u.toDouble(), v.toDouble())
        requestMpr()
    }

    // ---- stack rendering (fallback) ----------------------------------------

    private suspend fun renderStack(r: StackReq) {
        val s = series.getOrNull(r.seriesIndex) ?: return
        val slice = s.slices.getOrNull(r.index) ?: return
        try {
            val loaded = cache.get(slice)
            if (r.generation != generation) return
            val first = current == null
            if (first) current = loaded
            var window = r.window ?: _state.value.window
            var name = _state.value.presetName
            if (window == null) {
                window = loaded.fileWindows.firstOrNull() ?: autoWindow(loaded.frame, loaded.rescale)
                name = if (loaded.fileWindows.isNotEmpty()) Presets.DEFAULT else Presets.AUTO
            }
            if (first) {
                valueSpan = withContext(Dispatchers.Default) { span(loaded) }
                _state.update { it.copy(presetNames = Presets.labelsFor(s.modality, loaded.fileWindows.isNotEmpty())) }
            }
            val bmp = withContext(Dispatchers.Default) { renderer.render(loaded.frame, loaded.rescale, window, userInvert = false) }
            if (r.generation != generation) return
            mainFrame = loaded.frame
            mainRescale = loaded.rescale
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
