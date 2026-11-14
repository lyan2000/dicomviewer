package com.tauslop.dicomviewer.data

import android.content.Context
import android.net.Uri
import com.tauslop.dicom.Window

/** Where the user left off: folder, series, slice and window. */
data class Session(
    val tree: Uri,
    val seriesUid: String?,
    val sliceIndex: Int,
    val window: Window?,
    val presetName: String?,
)

class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    fun load(): Session? {
        val tree = prefs.getString(KEY_TREE, null)?.let(Uri::parse) ?: return null
        val window = if (prefs.contains(KEY_WW)) {
            Window(prefs.getFloat(KEY_WC, 0f).toDouble(), prefs.getFloat(KEY_WW, 1f).toDouble())
        } else null
        return Session(
            tree = tree,
            seriesUid = prefs.getString(KEY_SERIES, null),
            sliceIndex = prefs.getInt(KEY_SLICE, 0),
            window = window,
            presetName = prefs.getString(KEY_PRESET, null),
        )
    }

    fun save(s: Session) {
        val e = prefs.edit()
            .putString(KEY_TREE, s.tree.toString())
            .putString(KEY_SERIES, s.seriesUid)
            .putInt(KEY_SLICE, s.sliceIndex)
            .putString(KEY_PRESET, s.presetName)
        if (s.window != null) {
            e.putFloat(KEY_WC, s.window.center.toFloat()).putFloat(KEY_WW, s.window.width.toFloat())
        } else {
            e.remove(KEY_WC).remove(KEY_WW)
        }
        e.apply()
    }

    fun clear() = prefs.edit().clear().apply()

    private companion object {
        const val KEY_TREE = "tree"
        const val KEY_SERIES = "series"
        const val KEY_SLICE = "slice"
        const val KEY_WC = "wc"
        const val KEY_WW = "ww"
        const val KEY_PRESET = "preset"
    }
}
