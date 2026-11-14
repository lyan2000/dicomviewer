package com.tauslop.dicom

import kotlin.math.abs

/**
 * A regular grayscale voxel grid. Voxel (i, j, k) is at
 * `origin + i·dx·rowDir + j·dy·colDir + k·dz·sliceDir` (patient mm), where i runs along image
 * columns, j along image rows and k across slices. Values are shorts; modality value =
 * `value · rescale.slope + rescale.intercept`.
 */
class Volume(
    val nx: Int,
    val ny: Int,
    val nz: Int,
    val data: ShortArray,
    val origin: Vec3,
    val rowDir: Vec3,
    val colDir: Vec3,
    val sliceDir: Vec3,
    val dx: Double,
    val dy: Double,
    val dz: Double,
    val rescale: Voi.Rescale,
    /** Photometric interpretation of the source (MONOCHROME1 renders inverted). */
    val photometric: String,
) {
    init { require(data.size == nx * ny * nz) }

    val minValue: Short by lazy { data.minOrNull() ?: 0 }
    val maxValue: Short by lazy { data.maxOrNull() ?: 0 }
    val minSpacing: Double get() = minOf(dx, dy, dz)

    fun index(i: Int, j: Int, k: Int) = (k * ny + j) * nx + i

    /** Patient position of voxel coordinates (may be fractional). */
    fun toPatient(i: Double, j: Double, k: Double): Vec3 =
        origin + rowDir * (i * dx) + colDir * (j * dy) + sliceDir * (k * dz)

    /** Voxel coordinates of a patient position (axes are orthonormal). */
    fun toVoxel(p: Vec3): Vec3 {
        val d = p - origin
        return Vec3((d dot rowDir) / dx, (d dot colDir) / dy, (d dot sliceDir) / dz)
    }

    val center: Vec3 get() = toPatient((nx - 1) / 2.0, (ny - 1) / 2.0, (nz - 1) / 2.0)

    /** Patient positions of the 8 corner voxel centres. */
    val corners: List<Vec3>
        get() = listOf(0.0, nx - 1.0).flatMap { i ->
            listOf(0.0, ny - 1.0).flatMap { j -> listOf(0.0, nz - 1.0).map { k -> toPatient(i, j, k) } }
        }

    /** Grid spacing along the volume axis best aligned with [dir]. */
    fun spacingAlong(dir: Vec3): Double {
        val a = listOf(abs(dir dot rowDir) to dx, abs(dir dot colDir) to dy, abs(dir dot sliceDir) to dz)
        return a.maxBy { it.first }.second
    }

    val byteSize: Long get() = data.size * 2L
}
