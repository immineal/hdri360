package com.immineal.hdri360.core.math

import java.util.Locale

/**
 * Unit quaternion (w, x, y, z). Android's rotation-vector sensor speaks
 * quaternions, so poses enter the pipeline in this form.
 */
class Quat(
    @JvmField val w: Double,
    @JvmField val x: Double,
    @JvmField val y: Double,
    @JvmField val z: Double
) {

    fun norm(): Double = Math.sqrt(w * w + x * x + y * y + z * z)

    fun normalized(): Quat {
        val n = norm()
        if (n == 0.0) throw IllegalStateException("cannot normalize the zero quaternion")
        return Quat(w / n, x / n, y / n, z / n)
    }

    fun conjugate() = Quat(w, -x, -y, -z)

    fun mul(o: Quat) = Quat(
        w * o.w - x * o.x - y * o.y - z * o.z,
        w * o.x + x * o.w + y * o.z - z * o.y,
        w * o.y - x * o.z + y * o.w + z * o.x,
        w * o.z + x * o.y - y * o.x + z * o.w
    )

    fun rotate(v: Vec3): Vec3 = toMat3().mul(v)

    fun toMat3(): Mat3 {
        val q = normalized()
        val xx = q.x * q.x; val yy = q.y * q.y; val zz = q.z * q.z
        val xy = q.x * q.y; val xz = q.x * q.z; val yz = q.y * q.z
        val wx = q.w * q.x; val wy = q.w * q.y; val wz = q.w * q.z
        return Mat3(doubleArrayOf(
            1 - 2 * (yy + zz), 2 * (xy - wz), 2 * (xz + wy),
            2 * (xy + wz), 1 - 2 * (xx + zz), 2 * (yz - wx),
            2 * (xz - wy), 2 * (yz + wx), 1 - 2 * (xx + yy)))
    }

    fun dot(o: Quat): Double = w * o.w + x * o.x + y * o.y + z * o.z

    /** Rotation angle in radians between the two orientations, in [0, pi]. */
    fun angleTo(o: Quat): Double {
        var d = Math.abs(normalized().dot(o.normalized()))
        d = Math.min(1.0, d)
        // 2*atan2(|sin|, cos) is better conditioned than 2*acos near zero angle
        return 2.0 * Math.atan2(Math.sqrt(Math.max(0.0, 1 - d * d)), d)
    }

    override fun toString(): String =
        String.format(Locale.US, "q(%.6f, %.6f, %.6f, %.6f)", w, x, y, z)

    companion object {
        @JvmField val IDENTITY = Quat(1.0, 0.0, 0.0, 0.0)

        @JvmStatic
        fun fromAxisAngle(axis: Vec3, angleRad: Double): Quat {
            val a = axis.normalized()
            val h = angleRad * 0.5
            val s = Math.sin(h)
            return Quat(Math.cos(h), a.x * s, a.y * s, a.z * s)
        }

        /** Android SENSOR order is (x, y, z, w). */
        @JvmStatic
        fun fromXyzw(x: Double, y: Double, z: Double, w: Double): Quat =
            Quat(w, x, y, z).normalized()

        /** Shepperd's method: picks the branch with the largest pivot for stability. */
        @JvmStatic
        fun fromMat3(R: Mat3): Quat {
            val m00 = R.get(0, 0); val m11 = R.get(1, 1); val m22 = R.get(2, 2)
            val tr = m00 + m11 + m22
            val w: Double; val x: Double; val y: Double; val z: Double
            if (tr > 0) {
                val s = Math.sqrt(tr + 1.0) * 2
                w = 0.25 * s
                x = (R.get(2, 1) - R.get(1, 2)) / s
                y = (R.get(0, 2) - R.get(2, 0)) / s
                z = (R.get(1, 0) - R.get(0, 1)) / s
            } else if (m00 > m11 && m00 > m22) {
                val s = Math.sqrt(1.0 + m00 - m11 - m22) * 2
                w = (R.get(2, 1) - R.get(1, 2)) / s
                x = 0.25 * s
                y = (R.get(0, 1) + R.get(1, 0)) / s
                z = (R.get(0, 2) + R.get(2, 0)) / s
            } else if (m11 > m22) {
                val s = Math.sqrt(1.0 + m11 - m00 - m22) * 2
                w = (R.get(0, 2) - R.get(2, 0)) / s
                x = (R.get(0, 1) + R.get(1, 0)) / s
                y = 0.25 * s
                z = (R.get(1, 2) + R.get(2, 1)) / s
            } else {
                val s = Math.sqrt(1.0 + m22 - m00 - m11) * 2
                w = (R.get(1, 0) - R.get(0, 1)) / s
                x = (R.get(0, 2) + R.get(2, 0)) / s
                y = (R.get(1, 2) + R.get(2, 1)) / s
                z = 0.25 * s
            }
            return Quat(w, x, y, z).normalized()
        }

        @JvmStatic
        fun slerp(a: Quat, b: Quat, u: Double): Quat {
            val qa = a.normalized()
            var qb = b.normalized()
            var d = qa.dot(qb)
            if (d < 0) { qb = Quat(-qb.w, -qb.x, -qb.y, -qb.z); d = -d }
            if (d > 0.9995) { // linear is within float noise here
                val r = Quat(
                    qa.w + (qb.w - qa.w) * u, qa.x + (qb.x - qa.x) * u,
                    qa.y + (qb.y - qa.y) * u, qa.z + (qb.z - qa.z) * u)
                return r.normalized()
            }
            val theta0 = Math.acos(d)
            val theta = theta0 * u
            val s0 = Math.cos(theta) - d * Math.sin(theta) / Math.sin(theta0)
            val s1 = Math.sin(theta) / Math.sin(theta0)
            return Quat(
                qa.w * s0 + qb.w * s1, qa.x * s0 + qb.x * s1,
                qa.y * s0 + qb.y * s1, qa.z * s0 + qb.z * s1).normalized()
        }
    }
}
