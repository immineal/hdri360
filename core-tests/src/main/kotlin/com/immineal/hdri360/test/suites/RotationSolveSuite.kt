package com.immineal.hdri360.test.suites

import com.immineal.hdri360.core.math.Mat3
import com.immineal.hdri360.core.math.SO3
import com.immineal.hdri360.core.math.Vec3
import com.immineal.hdri360.core.pano.RotationSolver
import com.immineal.hdri360.test.TestCase
import com.immineal.hdri360.test.TestKit
import java.util.Random

/** Closed-form and robust estimation of the rotation between two bearing sets. */
class RotationSolveSuite : TestCase {
    override fun name(): String = "rotation-solve"

    override fun run(t: TestKit) {
        val r = t.rng(9001)

        // --- exact recovery -----------------------------------------------
        for (trial in 0 until 40) {
            val truth = SO3.exp(randomVec(r).scale(r.nextDouble() * 2.5))
            val from = ArrayList<Vec3>()
            val to = ArrayList<Vec3>()
            for (i in 0 until 8) {
                val a = randomVec(r)
                from.add(a)
                to.add(truth.mul(a))
            }
            val est = RotationSolver.kabsch(from, to)
            t.lessThan(Math.toDegrees(SO3.angleBetween(truth, est!!)), 1e-9,
                "Kabsch is exact on clean data")
        }

        // Two non-parallel correspondences already determine the rotation.
        val truth2 = SO3.exp(Vec3(0.3, -0.9, 0.15))
        val f2 = ArrayList<Vec3>()
        val t2 = ArrayList<Vec3>()
        for (v in arrayOf(Vec3(1.0, 0.2, 0.3).normalized(), Vec3(-0.4, 1.0, 0.1).normalized())) {
            f2.add(v)
            t2.add(truth2.mul(v))
        }
        t.lessThan(Math.toDegrees(SO3.angleBetween(truth2, RotationSolver.kabsch(f2, t2)!!)), 1e-8,
            "two correspondences determine the rotation")

        // Degenerate inputs are refused rather than returning a plausible-looking wrong answer.
        t.check(RotationSolver.kabsch(ArrayList(), ArrayList()) == null,
            "no correspondences yields null")
        val one = ArrayList(listOf(Vec3(0.0, 0.0, 1.0)))
        t.check(RotationSolver.kabsch(one, one) == null, "a single correspondence is not enough")
        val parallel = ArrayList<Vec3>()
        val parallelTo = ArrayList<Vec3>()
        for (i in 0 until 5) {
            parallel.add(Vec3(0.0, 0.0, 1.0))
            parallelTo.add(Vec3(0.0, 0.0, 1.0))
        }
        t.check(RotationSolver.kabsch(parallel, parallelTo) == null,
            "parallel correspondences cannot fix the roll and are refused")

        // The estimate must be a proper rotation, never a reflection.
        val mirrorFrom = ArrayList<Vec3>()
        val mirrorTo = ArrayList<Vec3>()
        for (i in 0 until 6) {
            val a = randomVec(r)
            mirrorFrom.add(a)
            mirrorTo.add(Vec3(a.x, a.y, -a.z))      // a reflection, not a rotation
        }
        val refl = RotationSolver.kabsch(mirrorFrom, mirrorTo)!!
        t.near(1.0, refl.det(), 1e-9, "the solver never returns a reflection")

        // --- noise ---------------------------------------------------------
        val truth3 = SO3.exp(Vec3(0.1, 0.5, -0.2))
        val nf = ArrayList<Vec3>()
        val nt = ArrayList<Vec3>()
        for (i in 0 until 200) {
            val a = randomVec(r)
            nf.add(a)
            nt.add(truth3.mul(a).add(randomVec(r).scale(0.002)).normalized())
        }
        val noisyErr = Math.toDegrees(SO3.angleBetween(truth3, RotationSolver.kabsch(nf, nt)!!))
        t.lessThan(noisyErr, 0.05, "Kabsch averages down bearing noise")
        t.note("Kabsch error with 0.11 degree bearing noise: " + TestKit.fmt(noisyErr) + " degrees")

        // --- RANSAC with heavy contamination ---------------------------------
        val truth4 = SO3.exp(Vec3(-0.25, 0.8, 0.05))
        val cf = ArrayList<Vec3>()
        val ct = ArrayList<Vec3>()
        var outliers = 0
        for (i in 0 until 300) {
            val a = randomVec(r)
            cf.add(a)
            if (r.nextDouble() < 0.5) {         // half the matches are garbage
                ct.add(randomVec(r))
                outliers++
            } else {
                ct.add(truth4.mul(a).add(randomVec(r).scale(0.001)).normalized())
            }
        }
        val res = RotationSolver.ransac(cf, ct, Math.toRadians(0.5), 500, 42)
        t.check(res != null, "RANSAC returns a result")
        t.lessThan(Math.toDegrees(SO3.angleBetween(truth4, res!!.rotation)), 0.2,
            "RANSAC recovers the rotation despite 50% outliers")
        t.greaterThan(res.inlierCount.toDouble(), (300 - outliers) * 0.85,
            "most true inliers are recovered")
        t.lessThan(res.inlierCount.toDouble(), 300 - outliers * 0.85,
            "outliers are not swept in as inliers")
        t.eq(300L, res.inliers.size.toLong(), "the inlier mask covers every correspondence")
        t.note("RANSAC with " + outliers + "/300 outliers: " + res.inlierCount + " inliers, error " +
                TestKit.fmt(Math.toDegrees(SO3.angleBetween(truth4, res.rotation))) + " degrees")

        // Deterministic for a given seed; unusable input returns null rather than nonsense.
        val again = RotationSolver.ransac(cf, ct, Math.toRadians(0.5), 500, 42)
        t.eq(res.inlierCount.toLong(), again!!.inlierCount.toLong(),
            "RANSAC is deterministic for a fixed seed")
        t.check(RotationSolver.ransac(one, one, 0.01, 100, 1) == null,
            "RANSAC refuses degenerate input")

        // All-outlier input must not be dressed up as a confident answer.
        val junkA = ArrayList<Vec3>()
        val junkB = ArrayList<Vec3>()
        for (i in 0 until 100) { junkA.add(randomVec(r)); junkB.add(randomVec(r)) }
        val junk = RotationSolver.ransac(junkA, junkB, Math.toRadians(0.5), 300, 7)
        t.lessThan((if (junk == null) 0 else junk.inlierCount).toDouble(), 12.0,
            "random correspondences produce almost no inliers")
    }

    private fun randomVec(r: Random): Vec3 =
        Vec3(r.nextGaussian(), r.nextGaussian(), r.nextGaussian()).normalized()
}
