package com.immineal.hdri360.test.suites

import com.immineal.hdri360.core.math.Mat3
import com.immineal.hdri360.core.math.SO3
import com.immineal.hdri360.core.math.Vec3
import com.immineal.hdri360.core.pano.RotationBundleAdjuster
import com.immineal.hdri360.test.TestCase
import com.immineal.hdri360.test.TestKit
import java.util.Random

/**
 * Global rotation refinement. The gyro (or a chain of pairwise solves) gets the
 * frames roughly right; this is what turns "roughly" into a seam you cannot see.
 */
class BundleAdjustSuite : TestCase {
    override fun name(): String = "bundle-adjust"

    override fun run(t: TestKit) {
        val r = t.rng(70707)
        val frames = 8

        val truth = Array(frames) { i ->
            val yaw = Math.toRadians(i * 40.0)
            SO3.exp(Vec3(0.0, yaw, 0.0)).mul(SO3.exp(randomVec(r).scale(0.05)))
        }

        // Correspondences between every overlapping pair.
        val obs = ArrayList<RotationBundleAdjuster.Correspondence>()
        val bearingNoise = Math.toRadians(0.05)
        for (i in 0 until frames) {
            for (j in i + 1 until frames) {
                // only neighbours overlap
                if (Math.abs(i - j) > 2 && Math.abs(i - j) < frames - 2) continue
                for (n in 0 until 30) {
                    val world = randomVec(r)
                    val ba = perturb(truth[i].mulTranspose(world), bearingNoise, r)
                    val bb = perturb(truth[j].mulTranspose(world), bearingNoise, r)
                    obs.add(RotationBundleAdjuster.Correspondence(i, j, ba, bb, 1.0))
                }
            }
        }

        // Start from poses that are a few degrees off, as a gyro would leave them.
        val init = arrayOfNulls<Mat3>(frames)
        init[0] = truth[0]                                    // gauge: frame 0 is the reference
        for (i in 1 until frames)
            init[i] = SO3.exp(randomVec(r).scale(Math.toRadians(4.0))).mul(truth[i])
        @Suppress("UNCHECKED_CAST")
        val initial = Array(frames) { init[it]!! }

        val before = maxErrorDeg(truth, initial)
        val opt = RotationBundleAdjuster.Options()
        val res = RotationBundleAdjuster.solve(initial, obs, null, opt)
        val after = maxErrorDeg(truth, res.rotations)

        t.note("bundle adjustment: " + TestKit.fmt(before) + " deg -> " + TestKit.fmt(after) +
                " deg in " + res.iterations + " iterations")
        t.lessThan(after, 0.05, "poses are recovered to better than 0.05 degrees")
        t.lessThan(after, before, "adjustment improves on the initial guess")
        t.lessThan(Math.toDegrees(SO3.angleBetween(truth[0], res.rotations[0])), 1e-12,
            "the gauge frame is left exactly alone")
        for (R in res.rotations) {
            t.lessThan(R.transpose().mul(R).sub(Mat3.IDENTITY).maxAbs(), 1e-9,
                "results stay orthonormal")
            t.near(1.0, R.det(), 1e-9, "results stay proper rotations")
        }
        for (i in 1 until res.costHistory.size)
            t.check(res.costHistory[i] <= res.costHistory[i - 1] + 1e-12,
                "cost never increases (step $i)")
        // Two bearings each perturbed by 0.05 deg per tangent axis put the expected
        // pairwise residual at about 0.10 deg; anything near that means the solver
        // has fitted the geometry and is only seeing noise.
        t.lessThan(Math.toDegrees(res.rmsErrorRad), 0.15, "final residual is at the noise level")
        t.greaterThan(Math.toDegrees(res.rmsErrorRad), 0.05,
            "the solver has not overfitted the noise away")

        // --- robustness to mismatches ------------------------------------------
        val dirty = ArrayList(obs)
        val junk = (obs.size * 0.15).toInt()
        for (n in 0 until junk) {
            val i = r.nextInt(frames)
            val j = (i + 1) % frames
            dirty.add(RotationBundleAdjuster.Correspondence(i, j, randomVec(r), randomVec(r), 1.0))
        }
        val robust = RotationBundleAdjuster.solve(initial, dirty, null, opt)
        val robustErr = maxErrorDeg(truth, robust.rotations)
        t.lessThan(robustErr, 0.5, "15% gross mismatches do not wreck the solution")
        t.note("with 15% mismatches: " + TestKit.fmt(robustErr) + " degrees")

        // Without the robust kernel the same data should be visibly worse - proving
        // the kernel is doing the work rather than the problem being easy.
        val naive = RotationBundleAdjuster.Options()
        naive.huberRad = Double.POSITIVE_INFINITY
        val naiveErr = maxErrorDeg(truth,
            RotationBundleAdjuster.solve(initial, dirty, null, naive).rotations)
        t.greaterThan(naiveErr, robustErr, "the robust kernel measurably beats plain least squares")
        t.note("plain least squares on the same dirty data: " + TestKit.fmt(naiveErr) + " degrees")

        // --- priors ---------------------------------------------------------------
        val withPrior = RotationBundleAdjuster.Options()
        withPrior.priorWeight = 5.0
        val priorOnly = RotationBundleAdjuster.solve(
            initial, ArrayList(), truth, withPrior)
        t.lessThan(maxErrorDeg(truth, priorOnly.rotations), 0.01,
            "with no correspondences the solution falls back on the orientation prior")

        // A prior must not override good correspondences by much.
        val badPrior = Array(frames) { i ->
            SO3.exp(randomVec(r).scale(Math.toRadians(3.0))).mul(truth[i])
        }
        val weakPrior = RotationBundleAdjuster.Options()
        weakPrior.priorWeight = 0.05
        val mixed = RotationBundleAdjuster.solve(initial, obs, badPrior, weakPrior)
        t.lessThan(maxErrorDeg(truth, mixed.rotations), 0.5,
            "correspondences dominate a weak, biased prior")

        // --- edge cases --------------------------------------------------------------
        t.throwsException({ RotationBundleAdjuster.solve(arrayOf(), obs, null, opt) },
            "no frames is an error")
        val nothing = RotationBundleAdjuster.solve(initial, ArrayList(), null, opt)
        t.lessThan(maxErrorDeg(initial, nothing.rotations), 1e-12,
            "with nothing to fit, the input is returned untouched")
    }

    private fun maxErrorDeg(a: Array<Mat3>, b: Array<Mat3>): Double {
        var m = 0.0
        for (i in a.indices) m = Math.max(m, Math.toDegrees(SO3.angleBetween(a[i], b[i])))
        return m
    }

    private fun perturb(v: Vec3, sigmaRad: Double, r: Random): Vec3 {
        val t1 = v.anyPerpendicular()
        val t2 = v.cross(t1)
        return v.add(t1.scale(r.nextGaussian() * sigmaRad))
            .add(t2.scale(r.nextGaussian() * sigmaRad)).normalized()
    }

    private fun randomVec(r: Random): Vec3 =
        Vec3(r.nextGaussian(), r.nextGaussian(), r.nextGaussian()).normalized()
}
