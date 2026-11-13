package com.tauslop.dicomviewer.render

import android.graphics.Bitmap
import com.tauslop.dicom.Frame
import com.tauslop.dicom.Voi
import com.tauslop.dicom.Window

/** Renders a decoded frame to an ARGB bitmap through a VOI lookup table. */
class FrameRenderer {
    private var pixels = IntArray(0)

    // Cached LUT, rebuilt only when the window or value range changes.
    private var lut: ByteArray? = null
    private var lutKey: List<Any>? = null

    fun render(frame: Frame, rescale: Voi.Rescale, window: Window, userInvert: Boolean): Bitmap {
        val n = frame.width * frame.height
        if (pixels.size != n) pixels = IntArray(n)
        val argb = frame.argb
        if (argb != null) {
            System.arraycopy(argb, 0, pixels, 0, n)
        } else {
            val info = frame.info
            val invert = info.invertedGrayscale != userInvert
            val key = listOf(info.minStored, info.maxStored, rescale.slope, rescale.intercept, window.center, window.width, invert)
            val table = if (key == lutKey) lut!! else
                Voi.buildLut(info.minStored, info.maxStored, rescale, window, invert).also { lut = it; lutKey = key }
            val min = info.minStored
            val gray = frame.gray!!
            if (info.signed) {
                for (i in 0 until n) {
                    val v = table[gray[i] - min].toInt() and 0xFF
                    pixels[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                }
            } else {
                for (i in 0 until n) {
                    val v = table[(gray[i].toInt() and 0xFFFF) - min].toInt() and 0xFF
                    pixels[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                }
            }
        }
        return Bitmap.createBitmap(pixels, frame.width, frame.height, Bitmap.Config.ARGB_8888)
    }
}
