package com.immineal.hdri360.test.suites

import com.immineal.hdri360.core.camera.Intrinsics
import com.immineal.hdri360.core.math.Mat3
import com.immineal.hdri360.core.math.Vec3
import com.immineal.hdri360.core.pano.CapturePlan
import com.immineal.hdri360.core.pano.CapturePlanConfig
import com.immineal.hdri360.core.pano.CaptureTarget
import com.immineal.hdri360.core.pano.Equirect
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
        t.lessThan(plan.targets[3].direction.angleTo(again.targets[3].direction), 1e-12,
            "planning is deterministic in detail")
    }

    private fun randomDirection(r: Random): Vec3 {
        val z = 2 * r.nextDouble() - 1
        val phi = 2 * Math.PI * r.nextDouble()
        val s = Math.sqrt(Math.max(0.0, 1 - z * z))
        return Vec3(s * Math.cos(phi), z, s * Math.sin(phi))
    }
}
