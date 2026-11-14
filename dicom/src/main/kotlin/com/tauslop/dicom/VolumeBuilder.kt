package com.tauslop.dicom

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Builds a [Volume] from a sorted [Series] in two phases, so the caller controls slice loading:
 * [plan] checks geometry from headers only; then [Plan.put] each decoded slice and [Plan.finish].
 */
object VolumeBuilder {

    sealed class Check {
        class Ok(val plan: Plan) : Check()
        class Rejected(val reason: String) : Check()
    }

    /**
     * Validates that the series forms a regular grid. If the volume would exceed [maxBytes],
     * in-plane resolution is halved (2×2 averaging) until it fits.
     */
    fun <S> plan(series: Series<S>, maxBytes: Long): Check {
        val slices = series.slices
        if (slices.size < 2) return Check.Rejected("Needs at least 2 slices")
        val first = slices.first()
        val info = ImageInfo.of(first.header)
        if (info.samplesPerPixel != 1 || !info.isMonochrome) return Check.Rejected("Not grayscale")
        val planes = slices.map { it.plane ?: return Check.Rejected("Missing image position/orientation") }
        val p0 = planes.first()
        for ((s, p) in slices.zip(planes)) {
            val si = ImageInfo.of(s.header)
            if (si.rows != info.rows || si.columns != info.columns) return Check.Rejected("Slices differ in size")
            if (!p.sameOrientation(p0)) return Check.Rejected("Slices differ in orientation")
        }
        val spacing = p0.pixelSpacing ?: return Check.Rejected("Missing pixel spacing")

        val rowDir = Vec3.of(p0.orientation, 0).normalized()
        val colDir = Vec3.of(p0.orientation, 3).normalized()
        val positions = planes.map { Vec3.of(it.position) }
        val span = positions.last() - positions.first()
        if (span.length < 1e-6) return Check.Rejected("Slices share one position")
        val sliceDir = span.normalized()
        if (abs(sliceDir dot (rowDir cross colDir)) < 0.999) return Check.Rejected("Slices are tilted (gantry tilt)")

        val steps = positions.zipWithNext { a, b -> (b - a) dot sliceDir }
        val mean = steps.average()
        if (mean <= 0 || steps.any { abs(it - mean) > 0.01 * mean + 1e-4 }) {
            return Check.Rejected("Slice spacing is uneven or has gaps")
        }

        var factor = 1
        while (info.rows / factor * (info.columns / factor) * slices.size * 2L > maxBytes && factor < 8) factor *= 2
        if (info.rows / factor * (info.columns / factor) * slices.size * 2L > maxBytes) {
            return Check.Rejected("Series too large for memory")
        }
        return Check.Ok(
            Plan(
                nx = info.columns / factor, ny = info.rows / factor, nz = slices.size, factor = factor,
                origin = positions.first(), rowDir = rowDir, colDir = colDir, sliceDir = sliceDir,
                // PixelSpacing is (row spacing = between rows, column spacing = between columns).
                dx = spacing[1] * factor, dy = spacing[0] * factor, dz = mean,
                photometric = info.photometric,
            )
        )
    }

    class Plan internal constructor(
        val nx: Int, val ny: Int, val nz: Int, val factor: Int,
        val origin: Vec3, val rowDir: Vec3, val colDir: Vec3, val sliceDir: Vec3,
        val dx: Double, val dy: Double, val dz: Double, val photometric: String,
    ) {
        private val data = ShortArray(nx * ny * nz)
        private var rescale: Voi.Rescale? = null
        private val filled = BooleanArray(nz)

        /**
         * Stores decoded slice [k]. Values are kept in the first slice's stored units; slices with
         * a different rescale, or unsigned values above 32767, are converted (and clamped).
         */
        @Synchronized
        fun put(k: Int, frame: Frame, r: Voi.Rescale) {
            val base = rescale ?: run {
                // Unsigned data above Short.MAX_VALUE: shift by 32768 and fold it into the intercept.
                val shift = if (!frame.info.signed && frame.info.maxStored > Short.MAX_VALUE) 32768 else 0
                Voi.Rescale(r.slope, r.intercept + shift * r.slope).also { rescale = it; shiftFirst = shift }
            }
            val same = r.slope == base.slope && r.intercept + shiftFirst * r.slope == base.intercept
            val off = k * nx * ny
            val f = factor
            val w = frame.width
            for (j in 0 until ny) {
                for (i in 0 until nx) {
                    var sum = 0
                    for (b in 0 until f) for (a in 0 until f) sum += frame.stored((j * f + b) * w + i * f + a)
                    val stored = sum.toDouble() / (f * f)
                    val v = if (same) stored - shiftFirst else base.invert(stored * r.slope + r.intercept)
                    data[off + j * nx + i] = v.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
            }
            filled[k] = true
        }

        private var shiftFirst = 0

        fun finish(): Volume {
            check(filled.all { it }) { "Missing slices: ${filled.indices.filter { !filled[it] }}" }
            return Volume(nx, ny, nz, data, origin, rowDir, colDir, sliceDir, dx, dy, dz, rescale!!, photometric)
        }
    }
}
