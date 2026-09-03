package com.immineal.hdri360.core.math

/**
 * The rotation group, in the two forms bundle adjustment needs: a matrix to
 * apply and a 3-vector tangent to differentiate against.
 */
object SO3 {

    /** Skew-symmetric matrix with hat(w) * v == w.cross(v). */
    @JvmStatic
    fun hat(w: Vec3) = Mat3(doubleArrayOf(
        0.0, -w.z, w.y,
        w.z, 0.0, -w.x,
        -w.y, w.x, 0.0))

    /** Rodrigues' formula. Series-expands near zero so tiny updates stay exact. */
    @JvmStatic
    fun exp(w: Vec3): Mat3 {
        val theta2 = w.normSq()
        val K = hat(w)
        val a: Double
        val b: Double
        if (theta2 < 1e-12) {
            // sin(t)/t and (1-cos t)/t^2 to second order
            a = 1.0 - theta2 / 6.0
            b = 0.5 - theta2 / 24.0
        } else {
            val theta = Math.sqrt(theta2)
            a = Math.sin(theta) / theta
            b = (1.0 - Math.cos(theta)) / theta2
        }
        return Mat3.IDENTITY.add(K.scale(a)).add(K.mul(K).scale(b))
    }

    /** Inverse of [exp]; result magnitude is in [0, pi]. */
    @JvmStatic
    fun log(R: Mat3): Vec3 {
        var cos = (R.trace() - 1.0) * 0.5
        cos = Math.max(-1.0, Math.min(1.0, cos))
        val theta = Math.acos(cos)
        val axisRaw = Vec3(
            R.get(2, 1) - R.get(1, 2),
            R.get(0, 2) - R.get(2, 0),
            R.get(1, 0) - R.get(0, 1)).scale(0.5)
        if (theta < 1e-6) {
            // sin(t)/t -> 1; the antisymmetric part already is the tangent vector
            val f = 1.0 + theta * theta / 6.0
            return axisRaw.scale(f)
        }
        if (theta > Math.PI - 1e-4) {
            // Near pi the antisymmetric part vanishes; recover the axis from R + I,
            // whose columns are all parallel to the rotation axis.
            val S = R.add(Mat3.IDENTITY)
            var best = S.col(0)
            for (j in 1 until 3) if (S.col(j).normSq() > best.normSq()) best = S.col(j)
            var axis = best.normalized()
            // Sign is ambiguous from R + I alone; pick the one matching the (tiny)
            // antisymmetric part when it is usable.
            if (axis.dot(axisRaw) < 0) axis = axis.negate()
            return axis.scale(theta)
        }
        return axisRaw.scale(theta / Math.sin(theta))
    }

    /** Angle in radians of the rotation taking a to b. */
    @JvmStatic
    fun angleBetween(a: Mat3, b: Mat3): Double = log(a.transpose().mul(b)).norm()
}
