package com.tauslop.dicom.vr

import com.tauslop.dicom.Rotation
import com.tauslop.dicom.Vec3
import com.tauslop.dicom.Window

/**
 * 3D view state saved across launches. The camera's target and field come from the volume, so
 * only its orientation, zoom and pan are kept. [tfWindow] is the VR curve's visible range
 * (centre, width) when the user adjusted it with W/L, null for the unadjusted preset. Projection
 * windows keep their preset label (null after a W/L drag).
 */
data class VrPlace(
    val rotation: Rotation = Rotation.IDENTITY,
    val zoom: Double = 1.0,
    val panX: Double = 0.0,
    val panY: Double = 0.0,
    val mode: RenderMode = RenderMode.VR,
    val preset: String = TransferFunction.BONE.name,
    val tfWindow: Window? = null,
    val mipWindow: Window = Projection.defaultWindow(RenderMode.MIP),
    val minipWindow: Window = Projection.defaultWindow(RenderMode.MINIP),
    val shading: Boolean = true,
    /** The 3D view was in the main square. */
    val threeDMain: Boolean = false,
) {
    /** One line of `key=value` pairs separated by `;`. Labels never contain `;`, `=` or `,`. */
    fun encode(): String {
        val r = rotation
        val parts = linkedMapOf(
            "v" to "1",
            "rot" to listOf(r.cx, r.cy, r.cz).flatMap { listOf(it.x, it.y, it.z) }.joinToString(","),
            "zoom" to zoom.toString(),
            "pan" to "$panX,$panY",
            "mode" to mode.name,
            "preset" to preset,
            "mip" to win(mipWindow),
            "minip" to win(minipWindow),
            "shade" to shading.toString(),
            "main3d" to threeDMain.toString(),
        )
        tfWindow?.let { parts["tf"] = win(it) }
        return parts.entries.joinToString(";") { "${it.key}=${it.value}" }
    }

    companion object {
        private fun win(w: Window) = listOfNotNull(w.center.toString(), w.width.toString(), w.label).joinToString(",")

        private fun parseWin(s: String): Window? {
            val p = s.split(',')
            if (p.size !in 2..3) return null
            val c = p[0].toDouble()
            val w = p[1].toDouble()
            if (!c.isFinite() || !w.isFinite() || w < 1.0) return null
            return Window(c, w, p.getOrNull(2)?.takeIf { it.isNotEmpty() })
        }

        /** Parsed defensively: anything malformed or out of range means no 3D restore. */
        fun decode(s: String?): VrPlace? = runCatching {
            if (s.isNullOrEmpty()) return null
            val kv = s.split(';').associate { it.substringBefore('=') to it.substringAfter('=', "") }
            if (kv["v"] != "1") return null
            val r = kv.getValue("rot").split(',').map { it.toDouble() }
            if (r.size != 9 || r.any { !it.isFinite() }) return null
            val rot = Rotation(Vec3(r[0], r[1], r[2]), Vec3(r[3], r[4], r[5]), Vec3(r[6], r[7], r[8]))
            // Must be (close to) a rotation; re-orthonormalize the rest.
            if (listOf(rot.cx, rot.cy, rot.cz).any { kotlin.math.abs(it.length - 1) > 1e-3 }) return null
            val pan = kv.getValue("pan").split(',').map { it.toDouble() }
            val zoom = kv.getValue("zoom").toDouble()
            if (pan.size != 2 || pan.any { !it.isFinite() } || !zoom.isFinite() || zoom <= 0) return null
            val preset = kv.getValue("preset")
            if (TransferFunction.PRESETS.none { it.name == preset }) return null
            VrPlace(
                rotation = OrbitCamera.orthonormalize(rot),
                zoom = zoom.coerceIn(0.25, 20.0),
                panX = pan[0], panY = pan[1],
                mode = RenderMode.valueOf(kv.getValue("mode")),
                preset = preset,
                tfWindow = kv["tf"]?.let { parseWin(it) ?: return null },
                mipWindow = parseWin(kv.getValue("mip")) ?: return null,
                minipWindow = parseWin(kv.getValue("minip")) ?: return null,
                shading = kv.getValue("shade").toBooleanStrict(),
                threeDMain = kv.getValue("main3d").toBooleanStrict(),
            )
        }.getOrNull()
    }
}
