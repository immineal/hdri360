package com.immineal.hdri360.core.math

import java.util.Locale

/** Immutable 3-vector. Doubles throughout: pose error budgets here are arc-seconds. */
class Vec3(@JvmField val x: Double, @JvmField val y: Double, @JvmField val z: Double) {

    fun add(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    fun sub(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    fun scale(s: Double) = Vec3(x * s, y * s, z * s)
    fun negate() = Vec3(-x, -y, -z)
    fun dot(o: Vec3): Double = x * o.x + y * o.y + z * o.z
    fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun norm(): Double = Math.sqrt(x * x + y * y + z * z)
    fun normSq(): Double = x * x + y * y + z * z

    fun normalized(): Vec3 {
        val n = norm()
        if (n == 0.0) throw IllegalStateException("cannot normalize the zero vector")
        return scale(1.0 / n)
    }

    /**
     * Angle in radians between the two directions. Uses atan2 of the cross and dot
     * products rather than acos(dot) because acos loses all precision for the small
     * angles that dominate pose-error measurement.
     */
    fun angleTo(o: Vec3): Double {
        val a = this.normalized()
        val b = o.normalized()
        return Math.atan2(a.cross(b).norm(), a.dot(b))
    }

    /** Any unit vector orthogonal to this one; used to build local tangent frames. */
    fun anyPerpendicular(): Vec3 {
        val seed = if (Math.abs(x) < 0.9) X else Y
        return cross(seed).normalized()
    }

    fun get(i: Int): Double = if (i == 0) x else if (i == 1) y else z

    fun toArray(): DoubleArray = doubleArrayOf(x, y, z)

    override fun toString(): String = String.format(Locale.US, "(%.6g, %.6g, %.6g)", x, y, z)

    companion object {
        @JvmField val ZERO = Vec3(0.0, 0.0, 0.0)
        @JvmField val X = Vec3(1.0, 0.0, 0.0)
        @JvmField val Y = Vec3(0.0, 1.0, 0.0)
        @JvmField val Z = Vec3(0.0, 0.0, 1.0)
    }
}
