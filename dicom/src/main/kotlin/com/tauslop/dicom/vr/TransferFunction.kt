package com.tauslop.dicom.vr

import kotlin.math.pow

/** A transfer-function control point: Hounsfield value → colour (0..1) and opacity (0..1). */
data class TfPoint(val hu: Double, val r: Float, val g: Float, val b: Float, val a: Float)

/**
 * Piecewise-linear transfer function over HU. Below the first point and above the last, the end
 * points' values are held. Opacities are per [REFERENCE_STEP_MM] of ray travel; the renderer
 * corrects them for its actual step with [correctOpacity].
 */
class TransferFunction(val name: String, points: List<TfPoint>) {
    val points: List<TfPoint> = points.sortedBy { it.hu }

    init { require(this.points.isNotEmpty()) }

    /** RGBA at [hu]. */
    fun sample(hu: Double): FloatArray {
        val p = points
        if (hu <= p.first().hu) return p.first().rgba()
        if (hu >= p.last().hu) return p.last().rgba()
        val i = p.indexOfLast { it.hu <= hu }
        val a = p[i]
        val b = p[i + 1]
        val t = ((hu - a.hu) / (b.hu - a.hu)).toFloat()
        return floatArrayOf(lerp(a.r, b.r, t), lerp(a.g, b.g, t), lerp(a.b, b.b, t), lerp(a.a, b.a, t))
    }

    /**
     * Lookup table of [size] RGBA8 entries spanning [huMin, huMax] (entry i at
     * `huMin + i·(huMax-huMin)/(size-1)`), for upload as a 1D (N×1 2D) texture.
     */
    fun lut(size: Int = LUT_SIZE, huMin: Double = LUT_HU_MIN, huMax: Double = LUT_HU_MAX): ByteArray {
        val out = ByteArray(size * 4)
        for (i in 0 until size) {
            val c = sample(huMin + i * (huMax - huMin) / (size - 1))
            for (k in 0 until 4) out[i * 4 + k] = (c[k].coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()
        }
        return out
    }

    /** HU range where opacity is non-zero (the "window" this preset covers). */
    val visibleRange: ClosedFloatingPointRange<Double>
        get() {
            val vis = points.filter { it.a > 0f }
            if (vis.isEmpty()) return points.first().hu..points.last().hu
            val lo = points.lastOrNull { it.hu < vis.first().hu }?.hu ?: vis.first().hu
            val hi = points.firstOrNull { it.hu > vis.last().hu }?.hu ?: vis.last().hu
            return lo..hi
        }

    /**
     * The same curve moved so its visible range is centred on [center] with width [width]
     * (used by the W/L tool in 3D).
     */
    fun windowed(center: Double, width: Double): TransferFunction {
        val r = visibleRange
        val c0 = (r.start + r.endInclusive) / 2
        val w0 = maxOf(r.endInclusive - r.start, 1.0)
        val s = maxOf(width, 1.0) / w0
        return TransferFunction(name, points.map { it.copy(hu = center + (it.hu - c0) * s) })
    }

    private fun TfPoint.rgba() = floatArrayOf(r, g, b, a)
    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    companion object {
        const val LUT_SIZE = 1024
        const val LUT_HU_MIN = -1024.0
        const val LUT_HU_MAX = 3071.0
        const val REFERENCE_STEP_MM = 1.0

        /** Opacity for a step of [stepMm], given opacity [a] per [REFERENCE_STEP_MM]. */
        fun correctOpacity(a: Float, stepMm: Double): Float =
            (1.0 - (1.0 - a.toDouble()).pow(stepMm / REFERENCE_STEP_MM)).toFloat()

        private fun p(hu: Double, r: Float, g: Float, b: Float, a: Float) = TfPoint(hu, r, g, b, a)

        /** Airways and lung parenchyma, faint bone for context; soft tissue hidden. */
        val LUNG = TransferFunction("Lung", listOf(
            p(-1000.0, 0f, 0f, 0f, 0f),
            p(-930.0, 0.55f, 0.70f, 0.90f, 0f),
            p(-800.0, 0.70f, 0.80f, 0.95f, 0.04f),
            p(-600.0, 0.90f, 0.65f, 0.55f, 0.10f),
            p(-450.0, 0.90f, 0.60f, 0.50f, 0f),
            p(250.0, 0.95f, 0.90f, 0.80f, 0f),
            p(700.0, 1f, 1f, 0.95f, 0.25f),
        ))

        /** Skeleton: soft tissue hidden, bone ivory to white. */
        val BONE = TransferFunction("Bone", listOf(
            p(150.0, 0.90f, 0.80f, 0.65f, 0f),
            p(300.0, 0.95f, 0.88f, 0.75f, 0.35f),
            p(1000.0, 1f, 1f, 0.95f, 0.85f),
            p(3071.0, 1f, 1f, 1f, 1f),
        ))

        /** Skin and muscle in flesh tones, bone brighter. */
        val SOFT_TISSUE = TransferFunction("Soft tissue", listOf(
            p(-300.0, 0.80f, 0.55f, 0.45f, 0f),
            p(-100.0, 0.85f, 0.58f, 0.48f, 0.05f),
            p(40.0, 0.92f, 0.62f, 0.52f, 0.25f),
            p(200.0, 1f, 0.88f, 0.82f, 0.45f),
            p(1000.0, 1f, 1f, 1f, 0.80f),
        ))

        /** Contrast-filled vessels red, bone white (non-contrast CT shows mostly bone). */
        val VESSELS = TransferFunction("Vessels", listOf(
            p(100.0, 0.60f, 0.05f, 0.05f, 0f),
            p(200.0, 0.85f, 0.15f, 0.10f, 0.40f),
            p(400.0, 1f, 0.60f, 0.50f, 0.60f),
            p(1000.0, 1f, 1f, 0.95f, 0.90f),
        ))

        val PRESETS = listOf(LUNG, BONE, SOFT_TISSUE, VESSELS)
    }
}
