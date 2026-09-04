package com.immineal.hdri360.test.suites

import com.immineal.hdri360.core.camera.Intrinsics
import com.immineal.hdri360.core.math.Mat3
import com.immineal.hdri360.core.math.SO3
import com.immineal.hdri360.core.math.Vec3
import com.immineal.hdri360.core.pano.RotationBundleAdjuster
import com.immineal.hdri360.test.TestCase
import com.immineal.hdri360.test.TestKit
import java.util.Random

/**
 * Recovering the lens's radial distortion from the capture itself.
 *
 * Assuming k1 = 0 does not merely blur the seams: the bundle adjustment has no
 * way to express the error, so it tilts the poses instead, and the mistake comes
 * out as pose bias that grows toward the frame edges. One shared coefficient is
 * enough to absorb it, and it is observable from a rotating camera alone - the
 * same point seen at the centre of one frame and the edge of another constrains
 * it without any calibration target.
 *
 * The bar here is not "a k1 comes out". It is that the recovered value matches a
 * known truth, and that solving for it measurably beats the k1 = 0 fit on
 * identical data.
 */
class DistortionSuite : TestCase {
    override fun name(): String = "distortion"

    override fun run(t: TestKit) {
        val r = t.rng(4242424)
        val k1True = -0.09

        // The camera the synthetic photographs were actually taken with.
        val undistorted = Intrinsics.fromHorizontalFov(800, 600, 70.0)
        val trueCamera = undistorted.withDistortion(k1True, 0.0, 0.0)

        val frames = 8
        val truth = Array(frames) { i ->
            SO3.exp(Vec3(0.0, Math.toRadians(i * 34.0), 0.0))
                .mul(SO3.exp(Vec3(0.03 * Math.sin(i.toDouble()), 0.0, 0.02 * Math.cos(i.toDouble()))))
        }

        // Correspondences as the matcher would deliver them: pixel positions in two
        // frames, produced by the true distorted camera, with a little noise.
        val obs = ArrayList<RotationBundleAdjuster.Correspondence>()
        val pixelNoise = 0.15
        for (i in 0 until frames) {
            for (j in i + 1 until frames) {
                if (j - i != 1) continue   // a 238 degree arc: neighbours only, no loop closure
                var made = 0
                var attempts = 0
                while (made < 60 && attempts < 4000) {
                    attempts++
                    val u = r.nextDouble() * (undistorted.width - 1)
                    val v = r.nextDouble() * (undistorted.height - 1)
                    // Where that pixel actually points, through the real lens.
                    val world = truth[i].mul(trueCamera.unproject(u, v))
                    val q = trueCamera.project(truth[j].mulTranspose(world)) ?: continue
                    if (q[0] < 0 || q[1] < 0 ||
                        q[0] > undistorted.width - 1 || q[1] > undistorted.height - 1) continue

                    val pa = doubleArrayOf(u + r.nextGaussian() * pixelNoise,
                                           v + r.nextGaussian() * pixelNoise)
                    val pb = doubleArrayOf(q[0] + r.nextGaussian() * pixelNoise,
                                           q[1] + r.nextGaussian() * pixelNoise)
                    // The bearings a stitcher would compute knowing nothing about k1.
                    obs.add(RotationBundleAdjuster.Correspondence(i, j,
                        undistorted.unproject(pa[0], pa[1]),
                        undistorted.unproject(pb[0], pb[1]),
                        1.0, pa, pb))
                    made++
                }
                t.greaterThan(made.toDouble(), 30.0, "pair $i-$j has enough overlap to constrain k1")
            }
        }
        t.greaterThan(obs.size.toDouble(), 300.0, "enough correspondences overall")

        // Both fits start from the same poses, a few degrees out, as a gyro would leave them.
        val init = Array(frames) { i ->
            if (i == 0) truth[0]
            else SO3.exp(randomVec(r).scale(Math.toRadians(2.0))).mul(truth[i])
        }
        val cameras = Array(frames) { undistorted }

        // --- control: the old behaviour, k1 assumed zero ---------------------
        val plain = RotationBundleAdjuster.Options()
        val plainResult = RotationBundleAdjuster.solve(init, obs, null, plain)
        val plainPose = maxErrorDeg(truth, plainResult.rotations)
        t.near(0.0, plainResult.k1, 1e-12, "the control fit does not invent a distortion")

        // --- solving for the shared coefficient -------------------------------
        val withK1 = RotationBundleAdjuster.Options()
        withK1.solveDistortion = true
        withK1.distortionIntrinsics = cameras
        val k1Result = RotationBundleAdjuster.solve(init, obs, null, withK1)
        val k1Pose = maxErrorDeg(truth, k1Result.rotations)

        // The fixture has to be self-consistent before any claim about the solver
        // means anything: evaluated at the true poses with the true lens, the only
        // thing left should be the pixel noise. This assertion is here because it
        // caught a real defect - see the fold-over test at the end of this suite.
        var floorRms = 0.0
        run {
            var s2 = 0.0
            var worstOne = 0.0
            for (c in obs) {
                val a = truth[c.frameA].mul(trueCamera.unproject(c.pixelA!![0], c.pixelA!![1]))
                val b = truth[c.frameB].mul(trueCamera.unproject(c.pixelB!![0], c.pixelB!![1]))
                val ang = a.angleTo(b)
                s2 += ang * ang
                worstOne = Math.max(worstOne, Math.toDegrees(ang))
            }
            floorRms = Math.toDegrees(Math.sqrt(s2 / obs.size))
            t.lessThan(floorRms, 0.05, "the fixture is consistent at truth, to within its pixel noise")
            t.lessThan(worstOne, 0.5, "and contains no wildly inconsistent correspondence")
            t.note("noise floor at true poses and true lens: " + TestKit.fmt(floorRms) + " deg RMS")
        }

        t.near(k1True, k1Result.k1, 0.01, "the true radial coefficient is recovered")
        t.note("k1 recovered " + TestKit.fmt(k1Result.k1) + " vs truth " + TestKit.fmt(k1True))

        // The point of the exercise: it has to actually be better.
        t.lessThan(k1Pose, plainPose, "solving k1 beats assuming it is zero, on identical data")
        t.lessThan(k1Pose, 0.05, "poses are recovered to better than 0.05 degrees")
        t.lessThan(Math.toDegrees(k1Result.rmsErrorRad), floorRms * 1.15,
            "the fit reaches the noise floor rather than merely improving on the control")
        t.lessThan(Math.toDegrees(k1Result.rmsErrorRad),
            Math.toDegrees(plainResult.rmsErrorRad),
            "and leaves a smaller angular residual")
        t.note("pose error " + TestKit.fmt(plainPose) + " deg assuming k1=0 -> " +
                TestKit.fmt(k1Pose) + " deg solving it; residual " +
                TestKit.fmt(Math.toDegrees(plainResult.rmsErrorRad)) + " -> " +
                TestKit.fmt(Math.toDegrees(k1Result.rmsErrorRad)) + " deg")

        // Results must still be proper rotations, and the gauge must still be fixed.
        for (R in k1Result.rotations) {
            t.lessThan(R.transpose().mul(R).sub(Mat3.IDENTITY).maxAbs(), 1e-9,
                "results stay orthonormal")
            t.near(1.0, R.det(), 1e-9, "results stay proper rotations")
        }
        t.lessThan(Math.toDegrees(SO3.angleBetween(truth[0], k1Result.rotations[0])), 1e-12,
            "the gauge frame is left exactly alone")
        for (i in 1 until k1Result.costHistory.size)
            t.check(k1Result.costHistory[i] <= k1Result.costHistory[i - 1] + 1e-12,
                "cost never increases (step $i)")

        // --- a genuinely distortion-free lens must not acquire one --------------
        val cleanObs = ArrayList<RotationBundleAdjuster.Correspondence>()
        for (c in obs) {
            val world = truth[c.frameA].mul(undistorted.unproject(c.pixelA!![0], c.pixelA!![1]))
            val q = undistorted.project(truth[c.frameB].mulTranspose(world)) ?: continue
            if (q[0] < 0 || q[1] < 0 ||
                q[0] > undistorted.width - 1 || q[1] > undistorted.height - 1) continue
            val pb = doubleArrayOf(q[0], q[1])
            cleanObs.add(RotationBundleAdjuster.Correspondence(c.frameA, c.frameB,
                undistorted.unproject(c.pixelA!![0], c.pixelA!![1]),
                undistorted.unproject(pb[0], pb[1]), 1.0, c.pixelA, pb))
        }
        val cleanOpt = RotationBundleAdjuster.Options()
        cleanOpt.solveDistortion = true
        cleanOpt.distortionIntrinsics = cameras
        val cleanResult = RotationBundleAdjuster.solve(init, cleanObs, null, cleanOpt)
        t.near(0.0, cleanResult.k1, 0.01, "a rectilinear lens is not given a spurious distortion")
        t.note("k1 on distortion-free data: " + TestKit.fmt(cleanResult.k1))

        // The real requirement is not that the coefficient be rejected, but that a
        // distortion-free capture is not made worse by asking for one. The gate
        // may still return the distortion arm when its k1 is numerically zero -
        // that arm simply *is* the k1 = 0 model, fitted along a different path.
        t.lessThan(Math.abs(cleanResult.k1), 1e-6,
            "a distortion-free capture yields a numerically zero coefficient")
        val cleanControl = RotationBundleAdjuster.Options()
        val cleanPlain = RotationBundleAdjuster.solve(init, cleanObs, null, cleanControl)
        val cleanPlainPose = maxErrorDeg(truth, cleanPlain.rotations)
        val cleanSolvedPose = maxErrorDeg(truth, cleanResult.rotations)
        t.lessThan(cleanSolvedPose, cleanPlainPose * 1.02 + 1e-9,
            "and asking for distortion where there is none costs no pose accuracy")
        t.note("distortion-free capture: pose " + TestKit.fmt(cleanPlainPose) +
                " deg with k1 fixed, " + TestKit.fmt(cleanSolvedPose) + " deg with k1 offered")

        // The gate must not be so strict that it throws away a real lens.
        t.check(Math.abs(k1Result.k1) > 0.01,
            "a genuine distortion clears the acceptance gate")

        // --- the coefficient is bounded ------------------------------------------
        val bounded = RotationBundleAdjuster.Options()
        bounded.solveDistortion = true
        bounded.distortionIntrinsics = cameras
        bounded.k1Limit = 0.02
        val clamped = RotationBundleAdjuster.solve(init, obs, null, bounded)
        t.check(Math.abs(clamped.k1) <= 0.02 + 1e-12, "k1 stays inside its stated limit")

        // --- misuse is refused rather than silently ignored -------------------------
        val noPixels = ArrayList<RotationBundleAdjuster.Correspondence>()
        for (c in obs) noPixels.add(RotationBundleAdjuster.Correspondence(
            c.frameA, c.frameB, c.bearingA, c.bearingB, 1.0))
        val bad = RotationBundleAdjuster.Options()
        bad.solveDistortion = true
        bad.distortionIntrinsics = cameras
        t.throwsException({ RotationBundleAdjuster.solve(init, noPixels, null, bad) },
            "solving distortion without pixel observations is an error")
        val wrongCount = RotationBundleAdjuster.Options()
        wrongCount.solveDistortion = true
        wrongCount.distortionIntrinsics = Array(3) { undistorted }
        t.throwsException({ RotationBundleAdjuster.solve(init, obs, null, wrongCount) },
            "a mismatched intrinsics count is an error")

        // --- a capture with no rotational baseline cannot measure a lens ---------
        //
        // Every frame shot from one aim - a phone on a desk, or a bench test with
        // the aim check disabled - matches each point to itself at the same image
        // radius. Distortion cancels exactly, k1 is unobservable, and yet the
        // solver will happily find one and improve the residual with it. This is
        // not hypothetical: a real capture on the phone returned k1 = 0.133 from
        // eighteen frames that shared a single aim.
        run {
            val jitter = Array(frames) { i ->
                SO3.exp(Vec3(0.004 * Math.sin(i * 1.7), 0.004 * Math.cos(i * 2.3),
                             0.002 * Math.sin(i * 0.9)))
            }
            val stuck = ArrayList<RotationBundleAdjuster.Correspondence>()
            for (i in 0 until frames) {
                for (j in i + 1 until frames) {
                    var made = 0
                    var attempts = 0
                    while (made < 60 && attempts < 4000) {
                        attempts++
                        val u = r.nextDouble() * (undistorted.width - 1)
                        val v = r.nextDouble() * (undistorted.height - 1)
                        val world = jitter[i].mul(trueCamera.unproject(u, v))
                        val q = trueCamera.project(jitter[j].mulTranspose(world)) ?: continue
                        if (q[0] < 0 || q[1] < 0 ||
                            q[0] > undistorted.width - 1 || q[1] > undistorted.height - 1) continue
                        val pa = doubleArrayOf(u + r.nextGaussian() * pixelNoise,
                                               v + r.nextGaussian() * pixelNoise)
                        val pb = doubleArrayOf(q[0] + r.nextGaussian() * pixelNoise,
                                               q[1] + r.nextGaussian() * pixelNoise)
                        stuck.add(RotationBundleAdjuster.Correspondence(i, j,
                            undistorted.unproject(pa[0], pa[1]),
                            undistorted.unproject(pb[0], pb[1]),
                            1.0, pa, pb))
                        made++
                    }
                }
            }
            t.greaterThan(stuck.size.toDouble(), 300.0, "the degenerate fixture has plenty of matches")

            val spread = RotationBundleAdjuster.distortionLeverage(obs, cameras)
            val none = RotationBundleAdjuster.distortionLeverage(stuck, cameras)
            t.greaterThan(spread, 0.05,
                "a real sphere pairs points at genuinely different radii")
            t.lessThan(none, spread / 20.0,
                "frames sharing one aim pair every point with itself")
            t.note("radial leverage: " + TestKit.fmt(spread) + " over a sweep, " +
                    TestKit.fmt(none) + " from a single aim")

            val stuckInit = Array(frames) { i ->
                if (i == 0) jitter[0]
                else SO3.exp(randomVec(r).scale(Math.toRadians(0.5))).mul(jitter[i])
            }
            val degenerate = RotationBundleAdjuster.Options()
            degenerate.solveDistortion = true
            degenerate.distortionIntrinsics = cameras
            val stuckResult = RotationBundleAdjuster.solve(stuckInit, stuck, null, degenerate)
            t.near(0.0, stuckResult.k1, 1e-12,
                "a capture with no rotational baseline reports no distortion at all")

            // And the refusal has to be the gate's doing, not an accident of the
            // fit: with the gate opened up, this is exactly the number that came
            // back off the phone.
            val ungated = RotationBundleAdjuster.Options()
            ungated.solveDistortion = true
            ungated.distortionIntrinsics = cameras
            ungated.distortionMinSignalDeg = 0.0
            val loose = RotationBundleAdjuster.solve(stuckInit, stuck, null, ungated)
            t.check(Math.abs(loose.k1) > 0.02,
                "the ungated solver really does invent a coefficient from this data")

            // The threshold has to sit between the two cases with room on both
            // sides, or it is a number that happens to work rather than a decision.
            val gate = RotationBundleAdjuster.Options().distortionMinSignalDeg
            val inventedDeg = Math.toDegrees(Math.abs(loose.k1) * none)
            val realDeg = Math.toDegrees(Math.abs(k1Result.k1) * spread)
            t.lessThan(inventedDeg * 2.0, gate,
                "what the invented coefficient claims to have measured is far under the gate")
            t.greaterThan(realDeg, gate * 5.0,
                "and what the real one claims is far over it")
            t.note("displacement claimed: " + TestKit.fmt(inventedDeg) +
                    " deg from one aim, " + TestKit.fmt(realDeg) +
                    " deg from a sweep, gate at " + TestKit.fmt(gate) + " deg")
        }

        foldOverTests(t)
    }

    private fun foldOverTests(t: TestKit) {
        // A negative k1 makes the radial polynomial turn over at r = 1/sqrt(3|k1|).
        // Past that, projection folds: a bearing far outside the real field of view
        // lands back inside the frame. Left unguarded it manufactures correspondences
        // with tens of degrees of error, which is how this suite first failed.
        val k1 = -0.09
        val cam = Intrinsics.fromHorizontalFov(800, 600, 70.0).withDistortion(k1, 0.0, 0.0)
        val turnover = Math.sqrt(1.0 / (3 * Math.abs(k1)))      // ~1.92 in normalised units

        val inside = Vec3(turnover * 0.5, 0.0, 1.0)
        t.check(cam.project(inside) != null, "a bearing inside the valid radius still projects")

        val beyond = Vec3(turnover * 1.6, 0.0, 1.0)
        t.check(cam.project(beyond) == null,
            "a bearing past the fold-over radius is refused rather than mirrored inside the frame")
        t.check(!cam.isVisible(beyond), "and is not reported visible")

        // The guard must not touch a rectilinear lens at all.
        val plain = Intrinsics.fromHorizontalFov(800, 600, 70.0)
        t.check(plain.project(Vec3(50.0, 0.0, 1.0)) != null,
            "an undistorted camera still projects far off-axis bearings")

        // Everything the guard admits must round-trip, which is the property that
        // makes the admitted region the right one.
        var worst = 0.0
        var tested = 0
        var rr = 0.02
        while (rr < turnover) {
            val d = Vec3(rr, rr * 0.3, 1.0)
            val p = cam.project(d)
            if (p != null) {
                worst = Math.max(worst, d.angleTo(cam.unproject(p[0], p[1])))
                tested++
            }
            rr += 0.02
        }
        t.greaterThan(tested.toDouble(), 50.0, "the admitted region is a useful size")
        t.lessThan(Math.toDegrees(worst), 1e-6,
            "every admitted bearing round-trips through project/unproject")
    }

    private fun maxErrorDeg(a: Array<Mat3>, b: Array<Mat3>): Double {
        var m = 0.0
        for (i in a.indices) m = Math.max(m, Math.toDegrees(SO3.angleBetween(a[i], b[i])))
        return m
    }

    private fun randomVec(r: Random): Vec3 =
        Vec3(r.nextGaussian(), r.nextGaussian(), r.nextGaussian()).normalized()
}
