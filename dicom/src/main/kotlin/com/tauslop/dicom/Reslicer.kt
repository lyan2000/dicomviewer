package com.tauslop.dicom

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The three MPR views at their standard orientation, in patient LPS coordinates
 * (+x = patient left, +y = posterior, +z = superior), using radiological display conventions.
 * [scroll] is the direction slice index increases (head → feet, anterior → posterior, right → left).
 */
enum class MprView(val label: String, val right: Vec3, val down: Vec3, val scroll: Vec3) {
    AXIAL("Axial", Vec3.X, Vec3.Y, -Vec3.Z),       // patient right on screen left, anterior up
    CORONAL("Coronal", Vec3.X, -Vec3.Z, Vec3.Y),   // patient right on screen left, head up
    SAGITTAL("Sagittal", Vec3.Y, -Vec3.Z, Vec3.X), // anterior on screen left, head up
}

/** A sampled image plane: pixel (u, v) is at `center + right·(u-(w-1)/2)·pixelMm + down·(v-(h-1)/2)·pixelMm`. */
class Plane(
    val center: Vec3,
    val right: Vec3,
    val down: Vec3,
    val width: Int,
    val height: Int,
    val pixelMm: Double,
) {
    val normal: Vec3 get() = right cross down

    fun pointAt(u: Double, v: Double): Vec3 =
        center + right * ((u - (width - 1) / 2.0) * pixelMm) + down * ((v - (height - 1) / 2.0) * pixelMm)

    /** Pixel coordinates (u, v) of patient point [p] projected onto this plane. */
    fun pixelOf(p: Vec3): Pair<Double, Double> {
        val d = p - center
        return Pair((d dot right) / pixelMm + (width - 1) / 2.0, (d dot down) / pixelMm + (height - 1) / 2.0)
    }
}

object Reslicer {

    /**
     * The plane for [view] rotated by [rotation], passing through [cursor]. Framing is centred on
     * the volume, so the image doesn't shift as the cursor moves within the plane.
     */
    fun planeFor(
        vol: Volume,
        view: MprView,
        cursor: Vec3,
        rotation: Rotation = Rotation.IDENTITY,
        pixelMm: Double = vol.minSpacing,
        maxDim: Int = 1024,
    ): Plane {
        val (right, down) = displayAxes(view, rotation)
        val n = right cross down
        val c = vol.center
        val center = c + n * ((cursor - c) dot n)
        var halfW = 0.0
        var halfH = 0.0
        for (q in vol.corners) {
            halfW = maxOf(halfW, abs((q - center) dot right))
            halfH = maxOf(halfH, abs((q - center) dot down))
        }
        var pix = pixelMm
        val longest = 2 * maxOf(halfW, halfH)
        if (longest / pix + 1 > maxDim) pix = longest / (maxDim - 1)
        val w = (2 * halfW / pix + 1e-6).roundToInt() + 1
        val h = (2 * halfH / pix + 1e-6).roundToInt() + 1
        return Plane(center, right, down, w, h, pix)
    }

    /**
     * Screen axes for [view] whose normal is rotated by [rotation]. Only the normal follows the
     * rotation; the in-plane axes stay as close as possible to the standard ones (the projection
     * of the standard "right" axis, or of "down" if right is nearly parallel to the normal). So
     * twisting about a view's own normal leaves that view's image upright, and oblique views stay
     * head-up.
     */
    fun displayAxes(view: MprView, rotation: Rotation): Pair<Vec3, Vec3> {
        val n = rotation.apply(view.right cross view.down).normalized()
        val r = view.right - n * (view.right dot n)
        return if (r.length > 1e-3) {
            val right = r.normalized()
            right to (n cross right) // right × down = n  ⇒  down = n × right
        } else {
            val down = (view.down - n * (view.down dot n)).normalized()
            (down cross n) to down
        }
    }

    /** Samples [plane] with trilinear interpolation; outside the volume is filled with its minimum. */
    fun sample(vol: Volume, plane: Plane): Frame {
        val w = plane.width
        val h = plane.height
        val out = ShortArray(w * h)
        val fill = vol.minValue
        val v0 = vol.toVoxel(plane.pointAt(0.0, 0.0))
        val du = vol.toVoxel(plane.pointAt(1.0, 0.0)) - v0
        val dv = vol.toVoxel(plane.pointAt(0.0, 1.0)) - v0
        val nx = vol.nx; val ny = vol.ny; val nz = vol.nz
        val maxX = nx - 1.0; val maxY = ny - 1.0; val maxZ = nz - 1.0
        val eps = 1e-6
        val data = vol.data
        val plane1 = nx * ny
        for (v in 0 until h) {
            val bx = v0.x + dv.x * v
            val by = v0.y + dv.y * v
            val bz = v0.z + dv.z * v
            val row = v * w
            for (u in 0 until w) {
                val x = bx + du.x * u
                val y = by + du.y * u
                val z = bz + du.z * u
                if (x < -eps || y < -eps || z < -eps || x > maxX + eps || y > maxY + eps || z > maxZ + eps) {
                    out[row + u] = fill
                    continue
                }
                val xc = x.coerceIn(0.0, maxX); val yc = y.coerceIn(0.0, maxY); val zc = z.coerceIn(0.0, maxZ)
                val i0 = minOf(floor(xc).toInt(), maxOf(nx - 2, 0))
                val j0 = minOf(floor(yc).toInt(), maxOf(ny - 2, 0))
                val k0 = minOf(floor(zc).toInt(), maxOf(nz - 2, 0))
                val fx = xc - i0; val fy = yc - j0; val fz = zc - k0
                val i1 = minOf(i0 + 1, nx - 1) - i0
                val j1 = (minOf(j0 + 1, ny - 1) - j0) * nx
                val k1 = (minOf(k0 + 1, nz - 1) - k0) * plane1
                val b = (k0 * ny + j0) * nx + i0
                val c00 = data[b] + (data[b + i1] - data[b]) * fx
                val c10 = data[b + j1] + (data[b + j1 + i1] - data[b + j1]) * fx
                val c01 = data[b + k1] + (data[b + k1 + i1] - data[b + k1]) * fx
                val c11 = data[b + k1 + j1] + (data[b + k1 + j1 + i1] - data[b + k1 + j1]) * fx
                val c0 = c00 + (c10 - c00) * fy
                val c1 = c01 + (c11 - c01) * fy
                out[row + u] = (c0 + (c1 - c0) * fz).roundToInt().toShort()
            }
        }
        val info = ImageInfo(h, w, 1, vol.photometric, 0, 16, 16, 15, signed = true, frames = 1)
        return Frame(w, h, info, out, null)
    }

    /** Slice position along a scroll direction: index, count and step (mm). */
    class Depth(val index: Int, val count: Int, val step: Double, val min: Double)

    fun depth(vol: Volume, dir: Vec3, cursor: Vec3): Depth {
        val d = dir.normalized()
        val proj = vol.corners.map { it dot d }
        val min = proj.min()
        val max = proj.max()
        val step = vol.spacingAlong(d)
        val count = floor((max - min) / step + 1e-6).toInt() + 1
        val index = ((cursor dot d) - min).div(step).roundToInt().coerceIn(0, count - 1)
        return Depth(index, count, step, min)
    }

    /** Moves [cursor] to slice [index] along [dir], keeping its in-plane position. */
    fun moveToIndex(vol: Volume, dir: Vec3, cursor: Vec3, index: Int): Vec3 {
        val d = dir.normalized()
        val dep = depth(vol, d, cursor)
        val target = dep.min + index.coerceIn(0, dep.count - 1) * dep.step
        return cursor + d * (target - (cursor dot d))
    }

    /** Angle in degrees between the rotated and standard normal of [view]. */
    fun obliquity(view: MprView, rotation: Rotation): Double {
        val a = rotation.apply(view.scroll).normalized() dot view.scroll
        return Math.toDegrees(acos(a.coerceIn(-1.0, 1.0)))
    }
}
