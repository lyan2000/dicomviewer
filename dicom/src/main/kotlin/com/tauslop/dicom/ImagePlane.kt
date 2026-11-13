package com.tauslop.dicom

import kotlin.math.abs
import kotlin.math.sqrt

/** Image Plane module geometry (PS3.3 C.7.6.2), in patient coordinates (mm). */
class ImagePlane(
    /** Image Position (Patient): centre of the first transmitted pixel. */
    val position: DoubleArray,
    /** Row direction cosines (x, y, z) followed by column direction cosines. */
    val orientation: DoubleArray,
    /** Row spacing then column spacing, mm; null if absent. */
    val pixelSpacing: DoubleArray?,
) {
    val rowDir: DoubleArray get() = orientation.copyOfRange(0, 3)
    val colDir: DoubleArray get() = orientation.copyOfRange(3, 6)

    /** Unit slice normal = row × column. */
    val normal: DoubleArray by lazy {
        val r = rowDir
        val c = colDir
        val n = doubleArrayOf(r[1] * c[2] - r[2] * c[1], r[2] * c[0] - r[0] * c[2], r[0] * c[1] - r[1] * c[0])
        val len = sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2])
        if (len > 0) DoubleArray(3) { n[it] / len } else n
    }

    /** Signed distance of this plane along [n] from the origin. */
    fun distanceAlong(n: DoubleArray): Double = position[0] * n[0] + position[1] * n[1] + position[2] * n[2]

    fun sameOrientation(other: ImagePlane, tolerance: Double = 1e-3): Boolean =
        orientation.indices.all { abs(orientation[it] - other.orientation[it]) < tolerance }

    companion object {
        /** Returns null if position or orientation is missing or malformed. */
        fun of(ds: DicomObject): ImagePlane? {
            val pos = ds.doubles(Tag.ImagePositionPatient)
            val ori = ds.doubles(Tag.ImageOrientationPatient)
            if (pos.size != 3 || ori.size != 6) return null
            val sp = ds.doubles(Tag.PixelSpacing).takeIf { it.size == 2 }
            return ImagePlane(pos, ori, sp)
        }
    }
}
