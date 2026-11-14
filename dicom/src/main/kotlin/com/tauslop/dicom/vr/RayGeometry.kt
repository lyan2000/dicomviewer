package com.tauslop.dicom.vr

import com.tauslop.dicom.Vec3
import com.tauslop.dicom.Volume
import kotlin.math.floor

/**
 * Geometry shared by the GPU ray caster and its CPU reference (used in tests). Rays are traced
 * in **texture space** [0,1]³, where voxel (i, j, k) has its centre at ((i+½)/nx, (j+½)/ny, (k+½)/nz).
 * Because the patient→texture map is affine and ray directions are unit vectors in patient
 * space, the ray parameter t stays in millimetres after the transform.
 */
object RayGeometry {

    /** Column-major 4×4 affine matrix mapping patient mm → texture coordinates. */
    fun patientToTexture(vol: Volume): FloatArray {
        val rows = listOf(
            Triple(vol.rowDir, vol.dx, vol.nx),
            Triple(vol.colDir, vol.dy, vol.ny),
            Triple(vol.sliceDir, vol.dz, vol.nz),
        )
        val m = FloatArray(16)
        rows.forEachIndexed { r, (dir, d, n) ->
            val s = 1.0 / (d * n)
            m[0 * 4 + r] = (dir.x * s).toFloat()
            m[1 * 4 + r] = (dir.y * s).toFloat()
            m[2 * 4 + r] = (dir.z * s).toFloat()
            m[3 * 4 + r] = ((-(vol.origin dot dir) / d + 0.5) / n).toFloat()
        }
        m[15] = 1f
        return m
    }

    fun applyPoint(m: FloatArray, p: Vec3) = Vec3(
        m[0] * p.x + m[4] * p.y + m[8] * p.z + m[12],
        m[1] * p.x + m[5] * p.y + m[9] * p.z + m[13],
        m[2] * p.x + m[6] * p.y + m[10] * p.z + m[14],
    )

    fun applyDir(m: FloatArray, d: Vec3) = Vec3(
        m[0] * d.x + m[4] * d.y + m[8] * d.z,
        m[1] * d.x + m[5] * d.y + m[9] * d.z,
        m[2] * d.x + m[6] * d.y + m[10] * d.z,
    )

    /** Slab test of a ray against the box [lo, hi]; returns (tNear, tFar) or null if it misses. */
    fun intersectBox(o: Vec3, d: Vec3, lo: Vec3 = Vec3.ZERO, hi: Vec3 = Vec3(1.0, 1.0, 1.0)): Pair<Double, Double>? {
        var t0 = Double.NEGATIVE_INFINITY
        var t1 = Double.POSITIVE_INFINITY
        for ((oc, dc, l, h) in listOf(
            arrayOf(o.x, d.x, lo.x, hi.x), arrayOf(o.y, d.y, lo.y, hi.y), arrayOf(o.z, d.z, lo.z, hi.z),
        )) {
            if (kotlin.math.abs(dc) < 1e-12) {
                if (oc < l || oc > h) return null
            } else {
                val a = (l - oc) / dc
                val b = (h - oc) / dc
                t0 = maxOf(t0, minOf(a, b))
                t1 = minOf(t1, maxOf(a, b))
            }
        }
        return if (t0 <= t1 && t1 >= 0) maxOf(t0, 0.0) to t1 else null
    }

    /** Trilinear HU at texture coordinate [t] with clamp-to-edge, like the GPU sampler. */
    fun sampleHu(vol: Volume, t: Vec3): Double {
        fun axis(c: Double, n: Int): Triple<Int, Int, Double> {
            val x = (c * n - 0.5).coerceIn(0.0, n - 1.0)
            val i0 = minOf(floor(x).toInt(), maxOf(n - 2, 0))
            return Triple(i0, minOf(i0 + 1, n - 1), x - i0)
        }
        val (i0, i1, fx) = axis(t.x, vol.nx)
        val (j0, j1, fy) = axis(t.y, vol.ny)
        val (k0, k1, fz) = axis(t.z, vol.nz)
        fun v(i: Int, j: Int, k: Int) = vol.data[vol.index(i, j, k)].toDouble()
        fun lerp(a: Double, b: Double, f: Double) = a + (b - a) * f
        val c = lerp(
            lerp(lerp(v(i0, j0, k0), v(i1, j0, k0), fx), lerp(v(i0, j1, k0), v(i1, j1, k0), fx), fy),
            lerp(lerp(v(i0, j0, k1), v(i1, j0, k1), fx), lerp(v(i0, j1, k1), v(i1, j1, k1), fx), fy),
            fz,
        )
        return c * vol.rescale.slope + vol.rescale.intercept
    }

    /**
     * CPU reference of the shader's front-to-back compositing (no shading). Returns premultiplied
     * RGBA. Stops when opacity exceeds [stopAlpha].
     */
    fun composite(
        vol: Volume,
        tf: TransferFunction,
        origin: Vec3,
        dir: Vec3,
        stepMm: Double,
        stopAlpha: Double = 0.95,
    ): FloatArray {
        val m = patientToTexture(vol)
        val o = applyPoint(m, origin)
        val d = applyDir(m, dir)
        val acc = FloatArray(4)
        val (tNear, tFar) = intersectBox(o, d) ?: return acc
        var t = tNear + stepMm / 2
        while (t <= tFar && acc[3] < stopAlpha) {
            val c = tf.sample(sampleHu(vol, o + d * t))
            val a = TransferFunction.correctOpacity(c[3], stepMm)
            val w = (1 - acc[3]) * a
            acc[0] += w * c[0]; acc[1] += w * c[1]; acc[2] += w * c[2]; acc[3] += w
            t += stepMm
        }
        return acc
    }
}
