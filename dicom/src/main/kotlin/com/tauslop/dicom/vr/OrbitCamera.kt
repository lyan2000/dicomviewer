package com.tauslop.dicom.vr

import com.tauslop.dicom.MprView
import com.tauslop.dicom.Rotation
import com.tauslop.dicom.Vec3
import com.tauslop.dicom.Volume
import kotlin.math.PI

/**
 * Orthographic camera orbiting a volume, in patient LPS coordinates.
 *
 * With [rotation] = identity the view is **coronal from the front**: looking along +Y
 * (anterior → posterior), screen-up = +Z (head), screen-right = +X (patient left), so the
 * patient's right is on screen left, matching coronal MPR. `right = forward × up`.
 */
data class OrbitCamera(
    /** Point the camera looks at (patient mm). */
    val target: Vec3,
    /** Extent (mm) that fits the shorter screen side at zoom 1. */
    val fieldMm: Double,
    val rotation: Rotation = Rotation.IDENTITY,
    val zoom: Double = 1.0,
    /** Image offset on screen in mm (+x right, +y down). */
    val panX: Double = 0.0,
    val panY: Double = 0.0,
) {
    val forward: Vec3 get() = rotation.apply(Vec3.Y)
    val up: Vec3 get() = rotation.apply(Vec3.Z)
    val right: Vec3 get() = rotation.apply(Vec3.X)

    /** mm per screen pixel for a [w]×[h] viewport. */
    fun mmPerPixel(w: Int, h: Int): Double = fieldMm / zoom / minOf(w, h)

    /**
     * Trackball rotation for a finger drag of ([dx], [dy]) px on a view of [viewPx] px.
     * The object follows the finger: dragging right turns its front to the right (we then see
     * the patient's right side); dragging down tips its top toward the viewer. A drag across
     * the full view turns 180°.
     */
    fun rotated(dx: Float, dy: Float, viewPx: Float): OrbitCamera {
        val ax = PI * dx / viewPx
        val ay = PI * dy / viewPx
        // The camera orbits opposite to the desired object motion: about its up axis for
        // horizontal drags, then about its (updated) right axis for vertical drags.
        val r = rotation.then(Rotation.about(up, -ax)).let { r1 ->
            r1.then(Rotation.about(r1.apply(Vec3.X), -ay))
        }
        return copy(rotation = orthonormalize(r))
    }

    fun zoomed(factor: Double): OrbitCamera = copy(zoom = (zoom * factor).coerceIn(0.25, 20.0))

    /** Pan by a screen delta in pixels on a [w]×[h] view. */
    fun panned(dxPx: Float, dyPx: Float, w: Int, h: Int): OrbitCamera {
        val s = mmPerPixel(w, h)
        return copy(panX = panX + dxPx * s, panY = panY + dyPx * s)
    }

    fun reset(): OrbitCamera = copy(rotation = Rotation.IDENTITY, zoom = 1.0, panX = 0.0, panY = 0.0)

    /** Quick view: look at the volume the way MPR shows [view] (same screen axes), fitted. */
    fun facing(view: MprView): OrbitCamera = copy(rotation = rotationFor(view), zoom = 1.0, panX = 0.0, panY = 0.0)

    /**
     * Ray through the centre of pixel ([px], [py]) of a [w]×[h] view: origin on the plane through
     * [target] facing the camera, shifted back far enough to start outside the volume (the
     * renderer clips rays to the volume box anyway), and unit direction [forward].
     */
    fun ray(px: Double, py: Double, w: Int, h: Int): Pair<Vec3, Vec3> {
        val s = mmPerPixel(w, h)
        val u = (px + 0.5 - w / 2.0) * s - panX
        val v = (py + 0.5 - h / 2.0) * s - panY
        val origin = target + right * u - up * v - forward * (fieldMm * 2)
        return origin to forward
    }

    /**
     * Screen position of patient point [p] on a [w]×[h] view, in continuous pixel coordinates
     * (pixel (i, j) spans [i, i+1) × [j, j+1)); the inverse of [ray] at `(u − ½, v − ½)`.
     */
    fun project(p: Vec3, w: Int, h: Int): Pair<Double, Double> {
        val s = mmPerPixel(w, h)
        val q = p - target
        return ((q dot right) + panX) / s + w / 2.0 to (-(q dot up) + panY) / s + h / 2.0
    }

    companion object {
        /**
         * Coronal-from-front camera framed tightly on the volume as seen from the front (its
         * extent along screen-right and screen-up, plus a 2% margin). Oblique rotations can then
         * clip the corners; the user can pinch out.
         */
        fun forVolume(vol: Volume): OrbitCamera {
            val cam = OrbitCamera(vol.center, 1.0)
            val c = vol.corners
            fun extent(axis: Vec3): Double = c.maxOf { it dot axis } - c.minOf { it dot axis }
            // Corner voxel centres are half a voxel inside the box edges; the margin covers it.
            val field = maxOf(extent(cam.right), extent(cam.up)) * 1.02
            return cam.copy(fieldMm = field)
        }

        /**
         * Camera rotation whose screen-right/up match MPR [view] (right = view.right,
         * up = −view.down): coronal from the front, sagittal from the patient's left, axial from
         * the feet. forward = up × right.
         */
        fun rotationFor(view: MprView): Rotation {
            val up = -view.down
            return Rotation(view.right, up cross view.right, up)
        }

        /** Gram–Schmidt to stop drift after many incremental rotations. */
        fun orthonormalize(r: Rotation): Rotation {
            val x = r.cx.normalized()
            val y = (r.cy - x * (r.cy dot x)).normalized()
            val z = x cross y
            return Rotation(x, y, z)
        }
    }
}
