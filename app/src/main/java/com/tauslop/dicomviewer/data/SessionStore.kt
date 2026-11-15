package com.tauslop.dicomviewer.data

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import com.tauslop.dicom.MprView
import com.tauslop.dicom.Rotation
import com.tauslop.dicom.Vec3
import com.tauslop.dicom.Window
import com.tauslop.dicom.vr.VrPlace

/** MPR navigation state: which view is main, the cursor (patient mm) and the plane rotation. */
data class MprPlace(val main: MprView, val cursor: Vec3, val rotation: Rotation)

/** Where the user left off: folder, series, slice, window and (in MPR) view/cursor/rotation. */
data class Session(
    val tree: Uri,
    val seriesUid: String?,
    val sliceIndex: Int,
    val window: Window?,
    val presetName: String?,
    val mpr: MprPlace? = null,
    /** 3D camera, preset/curve, render mode and MIP/MinIP windows. */
    val vr: VrPlace? = null,
)

/** Corner overlay on/off, and whether patient-identifying text is hidden (shown by default). */
data class DisplayPrefs(val showOverlay: Boolean = true, val hidePatient: Boolean = false)

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
            mpr = loadMpr(),
            vr = VrPlace.decode(prefs.getString(KEY_VR, null)), // malformed → null
        )
    }

    /** Parsed defensively: anything malformed just means no MPR restore. */
    private fun loadMpr(): MprPlace? = runCatching {
        val main = MprView.valueOf(prefs.getString(KEY_MAIN, null) ?: return null)
        val c = (prefs.getString(KEY_CURSOR, null) ?: return null).split(',').map { it.toDouble() }
        val r = (prefs.getString(KEY_ROTATION, null) ?: return null).split(',').map { it.toDouble() }
        if (c.size != 3 || r.size != 9) return null
        MprPlace(
            main,
            Vec3(c[0], c[1], c[2]),
            Rotation(Vec3(r[0], r[1], r[2]), Vec3(r[3], r[4], r[5]), Vec3(r[6], r[7], r[8])),
        )
    }.getOrNull()

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
        val m = s.mpr
        if (m != null) {
            val r = m.rotation
            e.putString(KEY_MAIN, m.main.name)
                .putString(KEY_CURSOR, listOf(m.cursor.x, m.cursor.y, m.cursor.z).joinToString(","))
                .putString(KEY_ROTATION, listOf(r.cx, r.cy, r.cz).flatMap { listOf(it.x, it.y, it.z) }.joinToString(","))
        } else {
            e.remove(KEY_MAIN).remove(KEY_CURSOR).remove(KEY_ROTATION)
        }
        if (s.vr != null) e.putString(KEY_VR, s.vr.encode()) else e.remove(KEY_VR)
        e.apply()
    }

    /** Forgets the folder and where the user was; display preferences are kept. */
    fun clear() = prefs.edit {
        prefs.all.keys.filter { it !in DISPLAY_KEYS }.forEach { remove(it) }
    }

    /** Overlay toggles; stored with the session but independent of the folder (they survive [clear]). */
    fun loadDisplay() = DisplayPrefs(
        showOverlay = prefs.getBoolean(KEY_OVERLAY, true),
        hidePatient = prefs.getBoolean(KEY_HIDE_PATIENT, false),
    )

    fun saveDisplay(d: DisplayPrefs) = prefs.edit {
        putBoolean(KEY_OVERLAY, d.showOverlay)
        putBoolean(KEY_HIDE_PATIENT, d.hidePatient)
    }

    private companion object {
        const val KEY_OVERLAY = "showOverlay"
        const val KEY_HIDE_PATIENT = "hidePatient"
        val DISPLAY_KEYS = setOf(KEY_OVERLAY, KEY_HIDE_PATIENT)
        const val KEY_TREE = "tree"
        const val KEY_SERIES = "series"
        const val KEY_SLICE = "slice"
        const val KEY_WC = "wc"
        const val KEY_WW = "ww"
        const val KEY_PRESET = "preset"
        const val KEY_MAIN = "mprMain"
        const val KEY_CURSOR = "mprCursor"
        const val KEY_ROTATION = "mprRotation"
        const val KEY_VR = "vr"
    }
}
