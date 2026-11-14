package com.tauslop.dicom

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Small immutable 3-vector in patient space (LPS, mm). */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    operator fun unaryMinus() = Vec3(-x, -y, -z)
    infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    infix fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    val length: Double get() = sqrt(this dot this)
    fun normalized(): Vec3 = length.let { if (it > 0) this * (1 / it) else this }

    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)
        val X = Vec3(1.0, 0.0, 0.0)
        val Y = Vec3(0.0, 1.0, 0.0)
        val Z = Vec3(0.0, 0.0, 1.0)
        fun of(a: DoubleArray, offset: Int = 0) = Vec3(a[offset], a[offset + 1], a[offset + 2])
    }
}

/** Rotation as an orthonormal 3×3 matrix, stored by columns (images of X, Y, Z). */
data class Rotation(val cx: Vec3, val cy: Vec3, val cz: Vec3) {
    fun apply(v: Vec3): Vec3 = cx * v.x + cy * v.y + cz * v.z

    /** This rotation followed by [other] (other ∘ this). */
    fun then(other: Rotation) = Rotation(other.apply(cx), other.apply(cy), other.apply(cz))

    val isIdentity: Boolean get() = this == IDENTITY

    companion object {
        val IDENTITY = Rotation(Vec3.X, Vec3.Y, Vec3.Z)

        /** Rodrigues rotation by [angle] radians about unit [axis]. */
        fun about(axis: Vec3, angle: Double): Rotation {
            val a = axis.normalized()
            val c = cos(angle)
            val s = sin(angle)
            fun rot(v: Vec3) = v * c + (a cross v) * s + a * ((a dot v) * (1 - c))
            return Rotation(rot(Vec3.X), rot(Vec3.Y), rot(Vec3.Z))
        }
    }
}
