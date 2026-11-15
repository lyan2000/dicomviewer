package com.tauslop.dicom.vr

import com.tauslop.dicom.Vec3
import com.tauslop.dicom.Volume
import com.tauslop.dicom.Window

/** How the 3D view turns a ray into a pixel. */
enum class RenderMode(val label: String) {
    /** Volume rendering: transfer function, front-to-back compositing. */
    VR("VR"),
    /** Maximum intensity projection. */
    MIP("MIP"),
    /** Minimum intensity projection, limited to the inside of the body (see [Projection]). */
    MINIP("MinIP"),
}

/**
 * MIP / MinIP: each ray yields one HU value (its maximum or minimum), shown through a linear
 * grey window of its own (separate from the MPR window and the VR transfer function).
 *
 * MinIP over a whole CT volume would be dominated by the air around the patient (and the air
 * gap above the table), so it only looks at low-density "holes" inside the body: samples below
 * [BODY_HU] that lie between two points where the ray has been inside the body (HU above
 * [BODY_HU]) for at least [BODY_RUN_MM]. Requiring a run, not a single sample, means a thin table
 * shell behind the patient does not pull the air gap under the back into the projection. Body
 * samples themselves are not counted (their edges would add partial-volume noise), so a ray
 * through solid tissue only reads [BODY_HU]: MinIP shows lungs and airways against a flat
 * soft-tissue background.
 * The shader in the app mirrors [project].
 */
object Projection {
    const val BODY_HU = -400.0
    const val BODY_RUN_MM = 8.0

    /** Presets per mode; the first is the default. Window(center = level, width). */
    val MIP_PRESETS = listOf(
        Window(300.0, 1500.0, "MIP"),
        Window(600.0, 2500.0, "Bone"),
        Window(200.0, 600.0, "Vessels"),
        Window(-600.0, 1500.0, "Lung"),
    )
    val MINIP_PRESETS = listOf(
        Window(-600.0, 1500.0, "MinIP"),
        Window(-850.0, 600.0, "Airways"),
        Window(-900.0, 300.0, "Emphysema"),
    )

    fun presets(mode: RenderMode): List<Window> = when (mode) {
        RenderMode.VR -> emptyList()
        RenderMode.MIP -> MIP_PRESETS
        RenderMode.MINIP -> MINIP_PRESETS
    }

    fun defaultWindow(mode: RenderMode): Window = presets(mode).first()

    /** Linear window: grey 0..1 for [hu]. */
    fun gray(hu: Double, w: Window): Double {
        val lo = w.center - w.width / 2
        return ((hu - lo) / maxOf(w.width, 1e-6)).coerceIn(0.0, 1.0)
    }

    /**
     * CPU reference of the shader's MIP / MinIP for one ray (first sample half a step inside the
     * box; the shader jitters it instead). Returns the projected HU, or null when the ray misses
     * the box or (MinIP) never enters the body. MinIP rays with no hole return [BODY_HU].
     */
    fun project(vol: Volume, mode: RenderMode, origin: Vec3, dir: Vec3, stepMm: Double): Double? =
        trace(vol, mode, origin, dir, stepMm)?.hu

    /** Projected value [hu] and where along the ray it was found ([t] mm from the origin; null for flat MinIP body). */
    data class Hit(val hu: Double, val t: Double?)

    /** [project], also returning the depth of the maximum (MIP) or minimum (MinIP). */
    fun trace(vol: Volume, mode: RenderMode, origin: Vec3, dir: Vec3, stepMm: Double): Hit? {
        require(mode != RenderMode.VR)
        val m = RayGeometry.patientToTexture(vol)
        val o = RayGeometry.applyPoint(m, origin)
        val d = RayGeometry.applyDir(m, dir)
        val (tNear, tFar) = RayGeometry.intersectBox(o, d) ?: return null
        var t = tNear + stepMm / 2
        var max = Double.NEGATIVE_INFINITY
        var tMax = t
        // MinIP state: min over holes since entering the body, committed at each in-body point.
        var run = 0.0
        var entered = false
        var holes = Double.POSITIVE_INFINITY
        var tHoles = t
        var committed = Double.POSITIVE_INFINITY
        var tCommitted = t
        while (t <= tFar) {
            val hu = RayGeometry.sampleHu(vol, o + d * t)
            if (mode == RenderMode.MIP) {
                if (hu > max) { max = hu; tMax = t }
            } else if (hu > BODY_HU) {
                run += stepMm
                if (run >= BODY_RUN_MM) { entered = true; committed = holes; tCommitted = tHoles }
            } else {
                run = 0.0
                if (entered && hu < holes) { holes = hu; tHoles = t }
            }
            t += stepMm
        }
        return when (mode) {
            RenderMode.MIP -> if (max.isFinite()) Hit(max, tMax) else null
            else -> if (!entered) null else if (committed.isFinite()) Hit(committed, tCommitted) else Hit(BODY_HU, null)
        }
    }

    /**
     * The 3D point a tap on this ray refers to: VR, where the composited opacity first reaches
     * [vrAlpha] (the surface you see); MIP, the brightest sample; MinIP, the darkest hole. Null
     * if the ray misses, stays transparent, or (MinIP) crosses no hole.
     */
    fun pick(
        vol: Volume, mode: RenderMode, tf: TransferFunction, origin: Vec3, dir: Vec3, stepMm: Double,
        vrAlpha: Double = 0.5,
    ): Vec3? {
        val t = if (mode == RenderMode.VR) {
            val m = RayGeometry.patientToTexture(vol)
            val o = RayGeometry.applyPoint(m, origin)
            val d = RayGeometry.applyDir(m, dir)
            val (tNear, tFar) = RayGeometry.intersectBox(o, d) ?: return null
            var t = tNear + stepMm / 2
            var acc = 0.0
            var hit: Double? = null
            while (t <= tFar) {
                val a = TransferFunction.correctOpacity(tf.sample(RayGeometry.sampleHu(vol, o + d * t))[3], stepMm)
                acc += (1 - acc) * a
                if (acc >= vrAlpha) { hit = t; break }
                t += stepMm
            }
            hit
        } else trace(vol, mode, origin, dir, stepMm)?.t
        // t is in mm along the unit patient direction (the patient→texture map is affine).
        return t?.let { origin + dir * it }
    }
}
