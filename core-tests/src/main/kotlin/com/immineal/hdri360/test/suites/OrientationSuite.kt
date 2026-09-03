package com.immineal.hdri360.test.suites

import com.immineal.hdri360.core.camera.Intrinsics
import com.immineal.hdri360.core.math.Mat3
import com.immineal.hdri360.core.math.Quat
import com.immineal.hdri360.core.math.SO3
import com.immineal.hdri360.core.math.Vec3
import com.immineal.hdri360.core.pano.CaptureGuide
import com.immineal.hdri360.core.pano.CapturePlan
import com.immineal.hdri360.core.pano.CapturePlanConfig
import com.immineal.hdri360.core.pano.CaptureTarget
import com.immineal.hdri360.core.pano.OrientationMath
import com.immineal.hdri360.test.TestCase
import com.immineal.hdri360.test.TestKit
import java.util.Arrays

/**
 * Turning the device's rotation-vector sensor into a camera pose, and the
 * guidance logic that tells the user where to point next.
 *
 * Every axis convention in this chain is a chance to ship a panorama that is
 * upside down, mirrored, or 90 degrees out, so each one is pinned by a test.
 */
class OrientationSuite : TestCase {
    override fun name(): String = "orientation"

    override fun run(t: TestKit) {
        val r = t.rng(4711)

        // --- rotation vector decoding ---------------------------------------
        val q = OrientationMath.quaternionFromRotationVector(floatArrayOf(0f, 0f, 0f))
        t.near(1.0, Math.abs(q.w), 1e-9, "an all-zero rotation vector is the identity")
        val rv = floatArrayOf(0.1f, -0.2f, 0.3f)
        val q2 = OrientationMath.quaternionFromRotationVector(rv)
        t.near(1.0, q2.norm(), 1e-6, "the decoded quaternion is normalised")
        val expectedW = Math.sqrt(1 - (0.01 + 0.04 + 0.09))
        t.near(expectedW, q2.w, 1e-6, "w is reconstructed from the vector part")
        // Android may supply w as a fourth element; both forms must agree.
        val q3 = OrientationMath.quaternionFromRotationVector(
            floatArrayOf(0.1f, -0.2f, 0.3f, expectedW.toFloat()))
        t.lessThan(q3.toMat3().sub(q2.toMat3()).maxAbs(), 1e-6, "the four-element form agrees")

        // --- lying flat, screen up, top pointing north ------------------------
        // The back camera then stares straight at the ground.
        val flat = OrientationMath.cameraToWorld(Quat.IDENTITY, 0, false)
        val axis = flat.mul(Vec3(0.0, 0.0, 1.0))
        t.lessThan(axis.angleTo(Vec3(0.0, -1.0, 0.0)), 1e-9,
            "face-up on a table, the back camera looks down")

        // --- held upright, looking at the reference heading ---------------------
        // Tip the device 90 degrees about the east axis: the back camera swings
        // from the ground up to the horizon.
        val upright = Quat.fromAxisAngle(Vec3(1.0, 0.0, 0.0), Math.toRadians(90.0))
        val level = OrientationMath.cameraToWorld(upright, 0, false)
        val levelAxis = level.mul(Vec3(0.0, 0.0, 1.0))
        t.near(0.0, levelAxis.y, 1e-9, "tipped upright, the camera looks at the horizon")
        t.lessThan(levelAxis.angleTo(Vec3(0.0, 0.0, 1.0)), 1e-9, "and along the reference heading")

        // --- yaw follows the device --------------------------------------------
        for (yawDeg in doubleArrayOf(15.0, 90.0, -120.0, 179.0)) {
            // Rotate about the Android world's up axis (its Z) on top of the upright pose.
            val yawed = Quat.fromAxisAngle(Vec3(0.0, 0.0, 1.0), Math.toRadians(yawDeg)).mul(upright)
            val R = OrientationMath.cameraToWorld(yawed, 0, false)
            val dir = R.mul(Vec3(0.0, 0.0, 1.0))
            t.near(0.0, dir.y, 1e-9, "yawing keeps the camera level")
            val got = Math.toDegrees(Math.atan2(-dir.x, dir.z))
            val want = normalize(-yawDeg)
            t.near(want, normalize(got), 1e-6, "device yaw maps to panorama yaw at $yawDeg")
        }

        // --- sensor orientation is a roll about the optical axis -----------------
        for (sensor in intArrayOf(0, 90, 180, 270)) {
            val R = OrientationMath.cameraToWorld(upright, sensor, false)
            t.lessThan(R.transpose().mul(R).sub(Mat3.IDENTITY).maxAbs(), 1e-9,
                "pose is orthonormal at sensor orientation $sensor")
            t.near(1.0, R.det(), 1e-9, "pose is right-handed at sensor orientation $sensor")
            t.lessThan(R.mul(Vec3(0.0, 0.0, 1.0)).angleTo(level.mul(Vec3(0.0, 0.0, 1.0))), 1e-9,
                "sensor orientation does not move the optical axis ($sensor)")
            val relative = level.transpose().mul(R)
            val angle = Math.toDegrees(SO3.log(relative).norm())
            // log() reports the shortest rotation, so 270 degrees reads back as 90.
            val expected = Math.min(sensor, 360 - sensor).toDouble()
            t.near(expected, angle, 1e-6,
                "sensor orientation $sensor is a pure roll of the right size")
            val rollAxis = SO3.log(relative)
            if (rollAxis.norm() > 1e-9)
                t.lessThan(Math.min(rollAxis.normalized().angleTo(Vec3(0.0, 0.0, 1.0)),
                    rollAxis.normalized().angleTo(Vec3(0.0, 0.0, -1.0))), 1e-6,
                    "the roll is about the optical axis ($sensor)")
        }

        // --- always a valid rotation, whatever the sensor says ---------------------
        for (i in 0 until 300) {
            val rq = Quat.fromAxisAngle(
                Vec3(r.nextGaussian(), r.nextGaussian(), r.nextGaussian()).normalized(),
                (r.nextDouble() * 2 - 1) * Math.PI)
            val R = OrientationMath.cameraToWorld(rq, 90, false)
            t.lessThan(R.transpose().mul(R).sub(Mat3.IDENTITY).maxAbs(), 1e-9,
                "random pose is orthonormal")
            t.near(1.0, R.det(), 1e-9, "random pose is right-handed")
        }

        // --- motion gate -------------------------------------------------------------
        t.check(OrientationMath.isStable(floatArrayOf(0.001f, -0.002f, 0.0005f), 0.02),
            "a still hand counts as stable")
        t.check(!OrientationMath.isStable(floatArrayOf(0.05f, 0f, 0f), 0.02), "a moving hand does not")
        t.check(!OrientationMath.isStable(null, 0.02), "no gyro reading means not stable")

        // --- guidance -----------------------------------------------------------------
        val k = Intrinsics.fromHorizontalFov(3000, 4000, 58.7)
        val plan = CapturePlan.forCamera(k, CapturePlanConfig())
        val shot = BooleanArray(plan.targets.size)

        val pose = plan.targets[5].rotation
        val nearest = CaptureGuide.nearestPendingTarget(plan.targets, shot, pose)
        t.eq(5L, nearest.toLong(), "standing on a target selects it")
        t.check(CaptureGuide.withinTolerance(pose, plan.targets[5], Math.toRadians(4.0)),
            "an exact pose is within tolerance")

        shot[5] = true
        val next = CaptureGuide.nearestPendingTarget(plan.targets, shot, pose)
        t.check(next != 5, "an already-shot target is skipped")
        t.check(next >= 0, "another target is offered")
        val d5 = plan.targets[next].direction.angleTo(plan.targets[5].direction)
        for (i in plan.targets.indices) {
            if (shot[i]) continue
            t.check(plan.targets[i].direction.angleTo(plan.targets[5].direction) >= d5 - 1e-9,
                "the nearest pending target really is the nearest")
        }

        Arrays.fill(shot, true)
        t.eq(-1L, CaptureGuide.nearestPendingTarget(plan.targets, shot, pose).toLong(),
            "no targets left returns -1")

        // Offsets must point the user the right way.
        val ahead = CaptureTarget.lookingAt(CaptureTarget.directionFor(0.0, 0.0))
        val lookingLeft = CaptureTarget.lookingAt(CaptureTarget.directionFor(-12.0, 0.0)).rotation
        val offset = CaptureGuide.guidanceOffsetDeg(lookingLeft, ahead)
        t.greaterThan(offset[0], 0.0, "when the target is to the right, the yaw offset is positive")
        t.near(12.0, offset[0], 1e-6, "the yaw offset is the actual angle")
        t.near(0.0, offset[1], 1e-6, "no pitch offset when both are level")

        val lookingDown = CaptureTarget.lookingAt(CaptureTarget.directionFor(0.0, -20.0)).rotation
        val pitchOffset = CaptureGuide.guidanceOffsetDeg(lookingDown, ahead)
        t.greaterThan(pitchOffset[1], 0.0, "when the target is above, the pitch offset is positive")
        t.near(20.0, pitchOffset[1], 1e-6, "the pitch offset is the actual angle")
    }

    private fun normalize(deg: Double): Double {
        var d = deg % 360
        if (d > 180) d -= 360
        if (d < -180) d += 360
        return d
    }
}
