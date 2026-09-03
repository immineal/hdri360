package com.immineal.hdri360.test.suites

import com.immineal.hdri360.core.math.Mat3
import com.immineal.hdri360.core.math.SO3
import com.immineal.hdri360.core.math.Vec3
import com.immineal.hdri360.core.pano.CaptureTarget
import com.immineal.hdri360.core.pano.HorizonEstimator
import com.immineal.hdri360.test.TestCase
import com.immineal.hdri360.test.TestKit

/**
 * Recovering which way is up from the frames alone.
 *
 * A feature-only stitch has no idea where gravity is - its gauge is whatever
 * frame happened to be first - and an HDRI with a tilted horizon is useless for
 * lighting. The one thing a handheld sweep does tell you is that the
 * photographer was not rolling the phone, so every frame's horizontal axis lies
 * close to the horizontal plane, and the direction perpendicular to all of them
 * is up.
 */
class HorizonSuite : TestCase {
    override fun name(): String = "horizon"

    override fun run(t: TestKit) {
        val r = t.rng(606)

        for (trial in 0 until 20) {
            // A believable sweep: several yaws, a range of pitches, no deliberate roll.
            val poses = ArrayList<Mat3>()
            val gauge = SO3.exp(Vec3(r.nextGaussian(), r.nextGaussian(), r.nextGaussian())
                .normalized().scale(r.nextDouble() * 3))
            var yaw = -70.0
            while (yaw <= 70) {
                for (pitch in doubleArrayOf(-40.0, 0.0, 35.0))
                    poses.add(gauge.mul(
                        CaptureTarget.lookingAt(CaptureTarget.directionFor(yaw, pitch)).rotation))
                yaw += 20
            }

            val est = HorizonEstimator.estimate(poses)
            val trueUp = gauge.mul(Vec3(0.0, 1.0, 0.0))
            val err = Math.toDegrees(est.up.angleTo(trueUp))
            t.lessThan(err, 1e-6, "up is recovered exactly from roll-free frames")
            t.greaterThan(est.confidence, 0.5, "a spread of yaws gives a confident estimate")

            // Levelling must actually put the recovered up on +Y.
            val level = est.levelingRotation()
            val afterUp = level.mul(est.up)
            t.lessThan(afterUp.angleTo(Vec3(0.0, 1.0, 0.0)), 1e-9, "levelling puts up at +Y")
            for (p in poses) {
                val levelled = level.mul(p)
                t.lessThan(levelled.transpose().mul(levelled).sub(Mat3.IDENTITY).maxAbs(), 1e-9,
                    "levelled poses stay orthonormal")
            }
        }

        // --- with the roll a real hand introduces ------------------------------
        // Averaged over many draws, because a single sample of a random process
        // says nothing useful about the estimator.
        var sumSq = 0.0
        var worstShaky = 0.0
        val trials = 40
        for (trial in 0 until trials) {
            val shaky = ArrayList<Mat3>()
            val gauge = SO3.exp(Vec3(0.4, -1.1, 0.2))
            var yaw = -80.0
            while (yaw <= 80) {
                for (pitch in doubleArrayOf(-30.0, 5.0, 40.0)) {
                    val clean = CaptureTarget.lookingAt(CaptureTarget.directionFor(yaw, pitch)).rotation
                    // Roll is about the camera's own optical axis.
                    val rolled = clean.mul(
                        SO3.exp(Vec3(0.0, 0.0, Math.toRadians(r.nextGaussian() * 4))))
                    shaky.add(gauge.mul(rolled))
                }
                yaw += 16
            }
            val e = Math.toDegrees(
                HorizonEstimator.estimate(shaky).up.angleTo(gauge.mul(Vec3(0.0, 1.0, 0.0))))
            sumSq += e * e
            worstShaky = Math.max(worstShaky, e)
        }
        val rmsShaky = Math.sqrt(sumSq / trials)
        t.lessThan(rmsShaky, 1.5, "four degrees of random roll leaves the horizon within 1.5 degrees RMS")
        t.lessThan(worstShaky, 4.0, "and never wildly off")
        t.note("horizon error with 4 degrees of hand roll: " + TestKit.fmt(rmsShaky) +
                " degrees RMS, worst " + TestKit.fmt(worstShaky))

        // --- the sign must not flip -------------------------------------------------
        // Up and down fit the geometry equally well; only the cameras' own down axes
        // break the tie, and getting that wrong yields an upside-down panorama.
        for (trial in 0 until 20) {
            val g = SO3.exp(Vec3(r.nextGaussian(), r.nextGaussian(), r.nextGaussian())
                .normalized().scale(r.nextDouble() * Math.PI))
            val poses = ArrayList<Mat3>()
            var yaw = -60.0
            while (yaw <= 60) {
                poses.add(g.mul(CaptureTarget.lookingAt(CaptureTarget.directionFor(yaw, 10.0)).rotation))
                yaw += 30
            }
            val e = HorizonEstimator.estimate(poses)
            t.greaterThan(e.up.dot(g.mul(Vec3(0.0, 1.0, 0.0))), 0.99, "up points up, not down")
        }

        // --- a degenerate sweep is reported, not guessed at ---------------------------
        val singleYaw = ArrayList<Mat3>()
        var pitch = -30.0
        while (pitch <= 30) {
            singleYaw.add(CaptureTarget.lookingAt(CaptureTarget.directionFor(0.0, pitch)).rotation)
            pitch += 10
        }
        val degenerate = HorizonEstimator.estimate(singleYaw)
        t.lessThan(degenerate.confidence, 0.2,
            "frames sharing one yaw cannot fix the horizon and say so")
        t.check(degenerate.up.x.isFinite(), "a degenerate estimate is still a usable vector")

        t.throwsException({ HorizonEstimator.estimate(ArrayList()) }, "no poses is an error")
        val one = ArrayList<Mat3>()
        one.add(Mat3.IDENTITY)
        val single = HorizonEstimator.estimate(one)
        t.lessThan(single.confidence, 0.2, "a single frame cannot fix the horizon either")
        t.lessThan(single.up.angleTo(Vec3(0.0, -1.0, 0.0)), 1e-9,
            "with one frame the best guess is that frame's own up")
    }
}
