package com.immineal.hdri360.core.math

import java.util.Locale

/** Immutable row-major 3x3 matrix. */
class Mat3(rowMajor9: DoubleArray) {

    /** Row-major: m[3*row + col]. */
    private val m: DoubleArray

    init {
        if (rowMajor9.size != 9) throw IllegalArgumentException("need 9 elements")
        m = rowMajor9.copyOf()
    }

    fun get(row: Int, col: Int): Double = m[3 * row + col]
    fun data(): DoubleArray = m.copyOf()

    fun row(i: Int) = Vec3(m[3 * i], m[3 * i + 1], m[3 * i + 2])
    fun col(j: Int) = Vec3(m[j], m[3 + j], m[6 + j])

    fun mul(o: Mat3): Mat3 {
        val r = DoubleArray(9)
        for (i in 0 until 3)
            for (j in 0 until 3) {
                var s = 0.0
                for (k in 0 until 3) s += m[3 * i + k] * o.m[3 * k + j]
                r[3 * i + j] = s
            }
        return Mat3(r)
    }

    fun mul(v: Vec3) = Vec3(
        m[0] * v.x + m[1] * v.y + m[2] * v.z,
        m[3] * v.x + m[4] * v.y + m[5] * v.z,
        m[6] * v.x + m[7] * v.y + m[8] * v.z
    )

    /** R^T * v, i.e. the inverse rotation for an orthonormal matrix (no allocation of R^T). */
    fun mulTranspose(v: Vec3) = Vec3(
        m[0] * v.x + m[3] * v.y + m[6] * v.z,
        m[1] * v.x + m[4] * v.y + m[7] * v.z,
        m[2] * v.x + m[5] * v.y + m[8] * v.z
    )

    fun transpose() = Mat3(doubleArrayOf(m[0], m[3], m[6], m[1], m[4], m[7], m[2], m[5], m[8]))

    fun add(o: Mat3): Mat3 {
        val r = DoubleArray(9)
        for (i in 0 until 9) r[i] = m[i] + o.m[i]
        return Mat3(r)
    }

    fun sub(o: Mat3): Mat3 {
        val r = DoubleArray(9)
        for (i in 0 until 9) r[i] = m[i] - o.m[i]
        return Mat3(r)
    }

    fun scale(s: Double): Mat3 {
        val r = DoubleArray(9)
        for (i in 0 until 9) r[i] = m[i] * s
        return Mat3(r)
    }

    fun det(): Double =
        m[0] * (m[4] * m[8] - m[5] * m[7]) -
        m[1] * (m[3] * m[8] - m[5] * m[6]) +
        m[2] * (m[3] * m[7] - m[4] * m[6])

    fun trace(): Double = m[0] + m[4] + m[8]

    fun maxAbs(): Double {
        var mx = 0.0
        for (v in m) mx = Math.max(mx, Math.abs(v))
        return mx
    }

    /**
     * Nearest orthonormal matrix with det +1. Repeated composition of rotations
     * drifts; every place that stores a pose re-projects through this.
     */
    fun orthonormalized(): Mat3 {
        val svd = Linalg.svd3(this)
        val r = svd.u.mul(svd.v.transpose())
        if (r.det() < 0) {
            // flip the least-significant singular direction
            return svd.u.mul(diag(1.0, 1.0, -1.0)).mul(svd.v.transpose())
        }
        return r
    }

    override fun toString(): String {
        val b = StringBuilder("[")
        for (i in 0 until 9) {
            b.append(String.format(Locale.US, "%.6g", m[i]))
            b.append(if (i == 8) "]" else if (i % 3 == 2) "; " else ", ")
        }
        return b.toString()
    }

    companion object {
        @JvmField val IDENTITY = Mat3(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))

        @JvmStatic
        fun diag(a: Double, b: Double, c: Double) =
            Mat3(doubleArrayOf(a, 0.0, 0.0, 0.0, b, 0.0, 0.0, 0.0, c))

        @JvmStatic
        fun fromColumns(c0: Vec3, c1: Vec3, c2: Vec3) = Mat3(doubleArrayOf(
            c0.x, c1.x, c2.x,
            c0.y, c1.y, c2.y,
            c0.z, c1.z, c2.z))

        @JvmStatic
        fun fromRows(r0: Vec3, r1: Vec3, r2: Vec3) = Mat3(doubleArrayOf(
            r0.x, r0.y, r0.z, r1.x, r1.y, r1.z, r2.x, r2.y, r2.z))

        /** Outer product a * b^T. */
        @JvmStatic
        fun outer(a: Vec3, b: Vec3) = Mat3(doubleArrayOf(
            a.x * b.x, a.x * b.y, a.x * b.z,
            a.y * b.x, a.y * b.y, a.y * b.z,
            a.z * b.x, a.z * b.y, a.z * b.z))
    }
}
