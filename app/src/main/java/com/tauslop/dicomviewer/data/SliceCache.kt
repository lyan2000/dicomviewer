package com.tauslop.dicomviewer.data

import android.content.ContentResolver
import android.net.Uri
import android.util.LruCache
import com.tauslop.dicom.DicomParser
import com.tauslop.dicom.Frame
import com.tauslop.dicom.PixelDecoder
import com.tauslop.dicom.Slice
import com.tauslop.dicom.Voi
import com.tauslop.dicom.Window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel

/** A decoded slice plus what's needed to render it. */
class LoadedSlice(val frame: Frame, val rescale: Voi.Rescale, val fileWindows: List<Window>)

/**
 * Memory-bounded cache of decoded slices. Concurrent requests for the same slice share one load,
 * and loads run in the cache's own scope, so a cancelled caller still leaves the result cached.
 */
class SliceCache(private val resolver: ContentResolver, maxBytes: Int) {

    private data class Key(val uri: Uri, val frame: Int)

    private val lru = object : LruCache<Key, LoadedSlice>(maxBytes) {
        override fun sizeOf(key: Key, value: LoadedSlice) = value.frame.byteSize
    }
    private val inFlight = HashMap<Key, Deferred<LoadedSlice>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(3))

    fun peek(slice: Slice<Uri>): LoadedSlice? = lru.get(Key(slice.source, slice.frame))

    suspend fun get(slice: Slice<Uri>): LoadedSlice {
        val key = Key(slice.source, slice.frame)
        lru.get(key)?.let { return it }
        val job = synchronized(inFlight) {
            inFlight.getOrPut(key) {
                scope.async {
                    try {
                        load(slice).also { lru.put(key, it) }
                    } finally {
                        synchronized(inFlight) { inFlight.remove(key) }
                    }
                }
            }
        }
        return job.await()
    }

    private fun load(slice: Slice<Uri>): LoadedSlice {
        val file = resolver.openInputStream(slice.source)?.use { DicomParser.parse(it) }
            ?: error("Cannot open ${slice.source}")
        return LoadedSlice(PixelDecoder.decode(file, slice.frame), Voi.rescale(file.dataset), Voi.fileWindows(file.dataset))
    }

    fun clear() {
        lru.evictAll()
    }

    fun close() {
        scope.cancel()
        lru.evictAll()
    }
}
