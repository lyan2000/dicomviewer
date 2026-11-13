package com.tauslop.dicom

import kotlin.math.roundToInt

/** A VOI window in modality units (e.g. HU for CT). */
data class Window(val center: Double, val width: Double, val label: String? = null)

/** Modality LUT (rescale) + linear VOI window → 8-bit display values. */
object Voi {

    class Rescale(val slope: Double, val intercept: Double) {
        fun apply(stored: Int): Double = stored * slope + intercept
        fun invert(value: Double): Double = (value - intercept) / slope
    }

    fun rescale(ds: DicomObject): Rescale {
        val slope = ds.double(Tag.RescaleSlope)?.takeIf { it != 0.0 } ?: 1.0
        return Rescale(slope, ds.double(Tag.RescaleIntercept) ?: 0.0)
    }

    /** Window presets stored in the file; there can be several. Widths < 1 are dropped. */
    fun fileWindows(ds: DicomObject): List<Window> {
        val c = ds.doubles(Tag.WindowCenter)
        val w = ds.doubles(Tag.WindowWidth)
        val labels = ds.strings(Tag.WindowCenterWidthExplanation)
        return (0 until minOf(c.size, w.size))
            .filter { w[it] >= 1.0 }
            .map { Window(c[it], w[it], labels.getOrNull(it)?.takeIf { s -> s.isNotEmpty() }) }
    }

    /** Linear VOI function, PS3.3 C.11.2.1.2.1. Returns 0..255. */
    fun linear(x: Double, center: Double, width: Double): Int {
        val w = maxOf(width, 1.0)
        val lo = center - 0.5 - (w - 1) / 2
        val hi = center - 0.5 + (w - 1) / 2
        return when {
            x <= lo -> 0
            x > hi -> 255
            else -> (((x - (center - 0.5)) / (w - 1) + 0.5) * 255).roundToInt().coerceIn(0, 255)
        }
    }

    /**
     * Lookup table from stored value to display byte for stored values in [minStored, maxStored].
     * Index with `stored - minStored`. [invert] flips the output (MONOCHROME1 or user invert).
     */
    fun buildLut(minStored: Int, maxStored: Int, rescale: Rescale, window: Window, invert: Boolean): ByteArray {
        val lut = ByteArray(maxStored - minStored + 1)
        for (i in lut.indices) {
            val v = linear(rescale.apply(minStored + i), window.center, window.width)
            lut[i] = (if (invert) 255 - v else v).toByte()
        }
        return lut
    }

    /** Window spanning the [lowPct]–[highPct] percentiles of the frame's modality values. */
    fun autoWindow(frame: Frame, rescale: Rescale, lowPct: Double = 0.01, highPct: Double = 0.99): Window {
        val range = frame.storedRange()
        val lo = range[0]
        val hist = IntArray(range[1] - lo + 1)
        val n = frame.width * frame.height
        for (i in 0 until n) hist[frame.stored(i) - lo]++
        fun pct(p: Double): Int {
            val target = (n * p).toLong()
            var acc = 0L
            for (i in hist.indices) { acc += hist[i]; if (acc > target) return lo + i }
            return range[1]
        }
        val a = rescale.apply(pct(lowPct))
        val b = rescale.apply(pct(highPct))
        val lowV = minOf(a, b)
        val highV = maxOf(a, b)
        return Window((lowV + highV) / 2, maxOf(highV - lowV, 1.0), "Auto")
    }
}
