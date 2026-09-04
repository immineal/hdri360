package com.immineal.hdri360.test.suites

import com.immineal.hdri360.core.camera.Intrinsics
import com.immineal.hdri360.core.math.Mat3
import com.immineal.hdri360.core.math.Vec3
import com.immineal.hdri360.core.pano.CapturePlan
import com.immineal.hdri360.core.pano.CapturePlanConfig
import com.immineal.hdri360.core.pano.CaptureTarget
import com.immineal.hdri360.core.pano.Equirect
import com.immineal.hdri360.core.pano.OrientationMath
import com.immineal.hdri360.test.TestCase
import com.immineal.hdri360.test.TestKit
import java.util.Random

/**
 * The shooting pattern. Getting this wrong is not recoverable later: a hole in
 * the sphere is a hole in the HDRI, and too little overlap starves the stitcher
 * of the correspondences it needs to solve orientation.
 */
class CapturePlanSuite : TestCase {
    override fun name(): String = "capture-plan"

    override fun run(t: TestKit) {
        val r = t.rng(808)
        // A Pixel-class main camera held in portrait: roughly 59 x 74 degrees.
        val k = Intrinsics.fromHorizontalFov(3000, 4000, 58.7)
        val cfg = CapturePlanConfig()
        val plan = CapturePlan.forCamera(k, cfg)

        t.greaterThan(plan.targets.size.toDouble(), 8.0, "a full sphere needs a real number of frames")
        t.lessThan(plan.targets.size.toDouble(), 80.0, "the pattern is not absurdly redundant")
        t.note("plan for " + TestKit.fmt(k.horizontalFovDeg()) + "x" + TestKit.fmt(k.verticalFovDeg()) +
                " degrees: " + plan.targets.size + " frames")

        for (target in plan.targets) {
            t.near(1.0, target.direction.norm(), 1e-9, "target direction is a unit vector")
            val R = target.rotation
            t.lessThan(R.transpose().mul(R).sub(Mat3.IDENTITY).maxAbs(), 1e-9,
                "target rotation is orthonormal")
            t.near(1.0, R.det(), 1e-9, "target rotation is right-handed")
            // The camera's optical axis must point at the target direction.
            t.lessThan(R.mul(Vec3(0.0, 0.0, 1.0)).angleTo(target.direction), 1e-9,
                "the camera axis points at the target")
        }

        // --- full sphere coverage --------------------------------------------
        var misses = 0
        val samples = 20000
        for (i in 0 until samples) {
            val d = randomDirection(r)
            if (!plan.covers(d, k)) misses++
        }
        val coverage = 1.0 - misses / samples.toDouble()
        t.greaterThan(coverage, 0.9995, "the pattern covers the whole sphere")
        t.note("coverage " + TestKit.fmt(coverage * 100) + "%")

        // --- overlap, which is what the stitcher eats -------------------------
        var lonely = 0
        for (i in 0 until samples / 4) {
            val d = randomDirection(r)
            if (plan.frameCount(d, k) < 2) lonely++
        }
        val doubleCovered = 1.0 - lonely / (samples / 4).toDouble()
        t.greaterThan(doubleCovered, 0.45, "much of the sphere is seen by at least two frames")
        t.note("seen by 2+ frames: " + TestKit.fmt(doubleCovered * 100) + "%")

        for (i in plan.targets.indices) {
            t.greaterThan(plan.neighbourCount(i, k).toDouble(), 1.0,
                "every frame overlaps at least two others (frame $i)")
        }

        // --- the frames are upright and unmirrored -------------------------------
        val fwd = CaptureTarget.lookingAt(Vec3(0.0, 0.0, 1.0))
        t.lessThan(fwd.rotation.mul(Vec3(0.0, 1.0, 0.0)).angleTo(Vec3(0.0, -1.0, 0.0)), 1e-9,
            "the camera's down axis points down in the world")
        val pc = Equirect.pixel(fwd.direction, 2048, 1024)
        val pr = Equirect.pixel(fwd.rotation.mul(Vec3(0.05, 0.0, 1.0).normalized()), 2048, 1024)
        val pd = Equirect.pixel(fwd.rotation.mul(Vec3(0.0, 0.05, 1.0).normalized()), 2048, 1024)
        t.greaterThan(pr[0], pc[0], "moving right in the frame moves right in the panorama")
        t.greaterThan(pd[1], pc[1], "moving down in the frame moves down in the panorama")

        // --- poles are explicitly covered --------------------------------------
        t.check(plan.covers(Vec3(0.0, 1.0, 0.0), k), "the zenith is covered")
        t.check(plan.covers(Vec3(0.0, -1.0, 0.0), k), "the nadir is covered")

        // --- more overlap means more frames ------------------------------------
        val tight = CapturePlanConfig()
        tight.overlapFraction = 0.55
        val dense = CapturePlan.forCamera(k, tight)
        t.greaterThan(dense.targets.size.toDouble(), plan.targets.size.toDouble(),
            "more overlap requires more frames")

        // --- a wider lens needs fewer frames -------------------------------------
        val wide = Intrinsics.fromHorizontalFov(3000, 4000, 105.0)
        val widePlan = CapturePlan.forCamera(wide, cfg)
        t.lessThan(widePlan.targets.size.toDouble(), plan.targets.size.toDouble(),
            "an ultra-wide lens needs fewer frames")
        var wideMisses = 0
        for (i in 0 until samples) if (!widePlan.covers(randomDirection(r), wide)) wideMisses++
        t.greaterThan(1.0 - wideMisses / samples.toDouble(), 0.9995,
            "the ultra-wide pattern still covers the sphere")

        // --- ordering: consecutive targets are close together --------------------
        // Shooting order matters for handheld work; the user should sweep, not hop.
        var worstStep = 0.0
        for (i in 1 until plan.targets.size)
            worstStep = Math.max(worstStep,
                Math.toDegrees(plan.targets[i - 1].direction.angleTo(plan.targets[i].direction)))
        t.lessThan(worstStep, 100.0, "the capture order never asks for a big jump")
        t.note("largest step between consecutive targets: " + TestKit.fmt(worstStep) + " degrees")

        // --- determinism ----------------------------------------------------------
        val again = CapturePlan.forCamera(k, cfg)
        t.eq(plan.targets.size.toLong(), again.targets.size.toLong(), "planning is deterministic")

        theTargetsAreHoldable(t)
        t.lessThan(plan.targets[3].direction.angleTo(again.targets[3].direction), 1e-12,
            "planning is deterministic in detail")
    }

    private fun randomDirection(r: Random): Vec3 {
        val z = 2 * r.nextDouble() - 1
        val phi = 2 * Math.PI * r.nextDouble()
        val s = Math.sqrt(Math.max(0.0, 1 - z * z))
        return Vec3(s * Math.cos(phi), z, s * Math.sin(phi))
    }
    /**
     * The pose the plan asks for has to be one a person actually adopts.
     *
     * A phone's sensor rows do not run along the horizon when the phone is held
     * upright: SENSOR_ORIENTATION is 90 degrees on a typical device, so a target
     * whose camera-down axis points at world-down is a target that requires the
     * phone to be held sideways. Held the natural way instead, the pose is a
     * quarter turn out - and since alignment is judged on roll as well as aim,
     * the shutter simply never fires. The whole capture path is unreachable, and
     * nothing in the old suite said so.
     */
    private fun theTargetsAreHoldable(t: TestKit) {
        // The real thing: a 4:3 sensor that reads out landscape, in a phone whose
        // camera is mounted a quarter turn round.
        val sensor = Intrinsics.fromHorizontalFov(4000, 3000, 58.7)
        val sensorOrientation = 90
        val plan = CapturePlan.forCamera(sensor, CapturePlanConfig(),
            sensorOrientation.toDouble())
        val cameraToDevice = OrientationMath.cameraToDevice(sensorOrientation, false)

        // The device attitude each target implies, from the same relation the
        // tracker uses: cameraToWorld = deviceInWorld * cameraToDevice.
        var worstUpright = 0.0
        var checked = 0
        for (target in plan.targets) {
            val f = target.direction
            if (Math.abs(f.y) > 0.94) continue      // near a pole, upright means nothing
            val deviceInWorld = target.rotation.mul(cameraToDevice.transpose())
            val screenUp = deviceInWorld.mul(Vec3(0.0, 1.0, 0.0))
            // As upright as the aim allows: world up, with the part along the
            // optical axis taken out.
            val upright = Vec3(0.0, 1.0, 0.0).sub(f.scale(f.y)).normalized()
            worstUpright = Math.max(worstUpright, Math.toDegrees(screenUp.angleTo(upright)))
            checked++
        }
        t.greaterThan(checked.toDouble(), 20.0, "there are targets away from the poles to check")
        t.lessThan(worstUpright, 1e-6,
            "the plan asks for a phone held straight up, screen upright")
        t.note("portrait grip: worst screen tilt " + TestKit.fmt(worstUpright) +
                " degrees over " + checked + " targets")

        // The same plan without the correction asks for the phone on its side. This
        // is the defect, stated: it is not a preference, it is ninety degrees.
        val sideways = CapturePlan.forCamera(sensor, CapturePlanConfig(), 0.0)
        var worstSideways = 0.0
        var leastSideways = 180.0
        for (target in sideways.targets) {
            val f = target.direction
            if (Math.abs(f.y) > 0.94) continue
            val deviceInWorld = target.rotation.mul(cameraToDevice.transpose())
            val screenUp = deviceInWorld.mul(Vec3(0.0, 1.0, 0.0))
            val upright = Vec3(0.0, 1.0, 0.0).sub(f.scale(f.y)).normalized()
            val tilt = Math.toDegrees(screenUp.angleTo(upright))
            worstSideways = Math.max(worstSideways, tilt)
            leastSideways = Math.min(leastSideways, tilt)
        }
        t.near(90.0, worstSideways, 1e-6,
            "and without it, exactly a quarter turn - which is why nothing ever fired")
        t.near(90.0, leastSideways, 1e-6, "for every target, not just the worst one")

        // A quarter turn also swaps which field of view sets which spacing. The
        // tiling has to follow the frame onto the sky, not the sensor's own idea
        // of which way is wide.
        val turned = plan.targets.size
        val flat = sideways.targets.size
        t.greaterThan(turned.toDouble(), 0.0, "the portrait plan exists")
        t.greaterThan(flat.toDouble(), 0.0, "so does the landscape one")
        t.note("a " + TestKit.fmt(sensor.horizontalFovDeg()) + "x" +
                TestKit.fmt(sensor.verticalFovDeg()) + " degree sensor tiles the sphere in " +
                turned + " directions held upright, " + flat + " held sideways")

        // Whatever the grip, the sphere still has to be covered.
        val rng = Random(4242)
        var covered = 0
        val trials = 4000
        for (i in 0 until trials) {
            val d = randomDirection(rng)
            if (plan.covers(d, sensor)) covered++
        }
        t.greaterThan(covered / trials.toDouble(), 0.999,
            "and the rolled plan still covers the whole sphere")
    }
}
