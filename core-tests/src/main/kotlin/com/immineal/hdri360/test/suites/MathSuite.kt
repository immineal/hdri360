package com.immineal.hdri360.test.suites

import com.immineal.hdri360.core.math.Linalg
import com.immineal.hdri360.core.math.Mat3
import com.immineal.hdri360.core.math.Quat
import com.immineal.hdri360.core.math.SO3
import com.immineal.hdri360.core.math.Vec3
import com.immineal.hdri360.test.TestCase
import com.immineal.hdri360.test.TestKit

/** Rotation representations and the small dense solvers the stitcher leans on. */
class MathSuite : TestCase {
    override fun name(): String = "math"

    override fun run(t: TestKit) {
        val r = t.rng(12345)

        // --- Vec3 basics -------------------------------------------------
        val a = Vec3(1.0, 2.0, 3.0)
        val b = Vec3(-4.0, 5.0, 0.5)
        t.near(1 * -4 + 2 * 5 + 3 * 0.5, a.dot(b), 1e-12, "dot")
        t.near(0.0, a.cross(b).dot(a), 1e-12, "cross is orthogonal to a")
        t.near(0.0, a.cross(b).dot(b), 1e-12, "cross is orthogonal to b")
        t.near(1.0, a.normalized().norm(), 1e-12, "normalized has unit length")
        t.near(Math.PI / 2, Vec3(1.0, 0.0, 0.0).angleTo(Vec3(0.0, 3.0, 0.0)), 1e-12, "angleTo orthogonal")
        // angleTo must stay accurate for tiny angles (this is what pose error is measured in)
        val u = Vec3(1.0, 0.0, 0.0)
        val v = Vec3(Math.cos(1e-7), Math.sin(1e-7), 0.0)
        t.nearRel(1e-7, u.angleTo(v), 1e-3, "angleTo is accurate at 1e-7 rad")

        // --- SO3 exp/log round trip --------------------------------------
        for (i in 0 until 200) {
            val w = Vec3(r.nextGaussian(), r.nextGaussian(), r.nextGaussian())
                .normalized().scale(r.nextDouble() * 3.0)
            val R = SO3.exp(w)
            t.lessThan(orthonormalityError(R), 1e-12, "SO3.exp yields an orthonormal matrix")
            t.near(1.0, R.det(), 1e-12, "SO3.exp has det +1")
            val w2 = SO3.log(R)
            t.lessThan(w.sub(w2).norm(), 1e-9, "exp/log round trip")
        }
        // tiny and near-pi rotations are the numerically nasty ones
        val tiny = Vec3(1e-9, -2e-9, 0.5e-9)
        t.lessThan(SO3.log(SO3.exp(tiny)).sub(tiny).norm(), 1e-15, "exp/log round trip at 1e-9 rad")
        val nearPi = Vec3(0.0, 0.0, 1.0).scale(Math.PI - 1e-7)
        t.lessThan(SO3.log(SO3.exp(nearPi)).sub(nearPi).norm(), 1e-6, "exp/log round trip near pi")
        t.lessThan(SO3.exp(Vec3.ZERO).sub(Mat3.IDENTITY).maxAbs(), 1e-15, "exp(0) = I")

        // --- Mat3 composition --------------------------------------------
        val R1 = SO3.exp(Vec3(0.3, -0.2, 1.1))
        val R2 = SO3.exp(Vec3(-0.7, 0.4, 0.05))
        val p = Vec3(0.3, 0.9, -0.2)
        t.lessThan(R1.mul(R2).mul(p).sub(R1.mul(R2.mul(p))).norm(), 1e-12,
            "matrix product is associative on vectors")
        t.lessThan(R1.mul(R1.transpose()).sub(Mat3.IDENTITY).maxAbs(), 1e-12, "R R^T = I")
        t.near(R1.det(), 1.0, 1e-12, "rotation determinant")

        // --- Quaternion <-> Mat3 ------------------------------------------
        for (i in 0 until 200) {
            val q = Quat.fromAxisAngle(
                Vec3(r.nextGaussian(), r.nextGaussian(), r.nextGaussian()).normalized(),
                (r.nextDouble() * 2 - 1) * Math.PI)
            val R = q.toMat3()
            val q2 = Quat.fromMat3(R)
            // q and -q are the same rotation; compare via the matrix
            t.lessThan(q2.toMat3().sub(R).maxAbs(), 1e-12, "quat -> mat -> quat round trip")
            val x = Vec3(r.nextGaussian(), r.nextGaussian(), r.nextGaussian())
            t.lessThan(q.rotate(x).sub(R.mul(x)).norm(), 1e-12, "quat.rotate agrees with its matrix")
        }
        // Mat3 -> Quat must be stable when the trace is negative (180 deg turns)
        val flip = SO3.exp(Vec3(0.0, Math.PI, 0.0))
        t.lessThan(Quat.fromMat3(flip).toMat3().sub(flip).maxAbs(), 1e-9, "quat from 180-degree rotation")

        val qa = Quat.fromAxisAngle(Vec3(0.0, 0.0, 1.0), 0.0)
        val qb = Quat.fromAxisAngle(Vec3(0.0, 0.0, 1.0), 1.0)
        t.near(0.5, Quat.slerp(qa, qb, 0.5).angleTo(qa), 1e-9, "slerp midpoint")
        t.near(1.0, qa.angleTo(qb), 1e-12, "quaternion angular distance")

        // --- Symmetric dense solver (used by bundle adjustment) -----------
        val n = 6
        val A = Array(n) { DoubleArray(n) }
        for (i in 0 until n)
            for (j in 0 until n) A[i][j] = r.nextGaussian()
        val spd = Array(n) { DoubleArray(n) }
        for (i in 0 until n)
            for (j in 0 until n) {
                var s = 0.0
                for (k in 0 until n) s += A[k][i] * A[k][j]
                spd[i][j] = s + (if (i == j) n.toDouble() else 0.0)
            }
        val xTrue = DoubleArray(n)
        for (i in 0 until n) xTrue[i] = r.nextGaussian()
        val rhs = DoubleArray(n)
        for (i in 0 until n) {
            var s = 0.0
            for (j in 0 until n) s += spd[i][j] * xTrue[j]
            rhs[i] = s
        }
        val x = Linalg.solveSpd(spd, rhs)
        t.arrayNear(xTrue, x!!, 1e-9, "SPD solve recovers the known solution")
        t.check(Linalg.solveSpd(
            arrayOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(0.0, 0.0)),
            doubleArrayOf(1.0, 1.0)) == null,
            "singular system returns null rather than NaNs")

        // Damping must make an indefinite system solvable (Levenberg step).
        val singular = arrayOf(doubleArrayOf(1.0, 1.0), doubleArrayOf(1.0, 1.0))
        t.check(Linalg.solveSpdDamped(singular, doubleArrayOf(1.0, 1.0), 1e-6) != null,
            "damped solve succeeds on a rank-deficient normal matrix")

        // --- 3x3 SVD (Kabsch / Procrustes backbone) -----------------------
        for (i in 0 until 50) {
            val M = Mat3(doubleArrayOf(
                r.nextGaussian(), r.nextGaussian(), r.nextGaussian(),
                r.nextGaussian(), r.nextGaussian(), r.nextGaussian(),
                r.nextGaussian(), r.nextGaussian(), r.nextGaussian()))
            val svd = Linalg.svd3(M)
            val recon = svd.u.mul(Mat3.diag(svd.s[0], svd.s[1], svd.s[2])).mul(svd.v.transpose())
            t.lessThan(recon.sub(M).maxAbs(), 1e-9, "SVD reconstructs M = U S V^T")
            t.lessThan(orthonormalityError(svd.u), 1e-9, "U is orthonormal")
            t.lessThan(orthonormalityError(svd.v), 1e-9, "V is orthonormal")
            t.check(svd.s[0] >= svd.s[1] && svd.s[1] >= svd.s[2] && svd.s[2] >= 0,
                "singular values are sorted and non-negative")
        }
    }

    private fun orthonormalityError(R: Mat3): Double =
        R.transpose().mul(R).sub(Mat3.IDENTITY).maxAbs()
}
