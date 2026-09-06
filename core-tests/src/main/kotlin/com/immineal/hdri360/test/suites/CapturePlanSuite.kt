package com.immineal.hdri360.test.suites

import com.immineal.hdri360.core.camera.Intrinsics
import com.immineal.hdri360.core.math.Mat3
import com.immineal.hdri360.core.math.SO3
import com.immineal.hdri360.core.math.Vec3
import com.immineal.hdri360.core.pano.CapturePlan
import com.immineal.hdri360.core.pano.CapturePlanConfig
import com.immineal.hdri360.core.pano.CaptureTarget
import com.immineal.hdri360.core.pano.Equirect
import com.immineal.hdri360.core.pano.OrientationMath
import com.immineal.hdri360.core.capture.StreamLadder
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
        rollingOneFrameCostsNoCoverage(t)
        aFrameDurationTheSensorCanActuallyKeep(t)
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

    /**
     * What one rolled frame costs the sphere, which is the justification for how
     * much roll the capture guide is willing to accept.
     *
     * Measured because it was being guessed. The guide judged aim and roll
     * separately - rightly, they are not equally costly - but the roll number was
     * a guess at 15 degrees, and on a real capture it was the binding constraint:
     * every direction was aimed within 0.8 degrees of a 7 degree budget while the
     * roll error at plus or minus 55 degrees of pitch reached 9.3 degrees of a 15
     * degree one. The two slowest directions of that ring, at 16.6 and 15.8
     * seconds against a 5 second median, were exactly the two with the largest
     * roll error.
     *
     * The reason roll grows with pitch is not that a wrist is less steady up
     * there. Gravity pins pitch and roll against itself and leaves the rotation
     * *about* gravity - the heading - as the badly determined one, and a heading
     * error of delta lands in roll as delta*sin(pitch): 31% of it at 18 degrees,
     * 82% at 55. Dividing the measured roll error by sin(pitch) gave the same 2
     * to 10 degrees at every ring, which is one constant heading uncertainty
     * showing up in different places.
     *
     * So the question is what roll actually costs, and the answer is nothing that
     * can be measured: this is where the tolerance comes from instead of a guess.
     */

    /**
     * The frame duration asked of the sensor has to be one it can keep.
     *
     * `SENSOR_FRAME_DURATION` was set to the exposure time, full stop. That reads
     * as "run as fast as this exposure allows", and for a single frame it is
     * harmless. For a bracket it is a demand: five frames back to back at
     * 1/30 s is thirty frames a second **while the gain changes from ISO 29 to
     * ISO 7276 between them**, and a sensor that cannot reconfigure that fast
     * inside one frame period does not slow down politely.
     *
     * Measured on the phone, from the camera HAL's own log:
     *
     *     CSIS Core context 0: LogicalChannel0LateConfigError
     *     Rear: 61 responses over 2.03 s, FPS: 30.08      (before the burst)
     *     Rear: 37 responses over 18.56 s, FPS: 1.99      (during it)
     *
     * Thirty frames a second to two. One frame of the bracket arrived, the rest
     * were still coming eighteen seconds later, the burst outlived its timeout
     * and the capture died. It had been surviving on 1/15 s rungs - 66 ms of
     * reconfiguration time per frame - and halving the handheld limit to 1/30 s
     * halved that too and pushed it over.
     *
     * So the frame duration is the longest of what the exposure needs and what
     * the stream can actually sustain. Camera2 requires the first; the second is
     * the device's own `getOutputMinFrameDuration` for the size being captured,
     * and asking for less than it is asking for something the sensor never
     * offered.
     */
    private fun aFrameDurationTheSensorCanActuallyKeep(t: TestKit) {
        val ms = 1_000_000L
        // A Pixel 9a's main camera: RAW at 4000x3000 is advertised at 30 fps,
        // and that is a minimum frame duration of 33.3 ms.
        val minimum = 33_333_333L

        // The rungs of the ladder that killed the capture.
        t.eq(minimum, StreamLadder.frameDurationFor(9 * ms, minimum),
            "a 1/107 s rung does not ask the sensor for 107 frames a second")
        t.eq(minimum, StreamLadder.frameDurationFor(33 * ms, minimum),
            "and a 1/30 s rung asks for exactly what the stream offers, not a hair less")

        // A long exposure needs at least its own length, whatever the stream says.
        t.eq(500 * ms, StreamLadder.frameDurationFor(500 * ms, minimum),
            "an exposure longer than the minimum sets the duration itself")
        t.greaterThan(StreamLadder.frameDurationFor(500 * ms, minimum).toDouble(),
            (500 * ms - 1).toDouble(),
            "because a frame can never be shorter than the exposure inside it")

        // Never below either bound, over the whole range this app plans.
        var micros = 12L
        while (micros < 16_000_000L) {
            val exposure = micros * 1000L
            val got = StreamLadder.frameDurationFor(exposure, minimum)
            t.check(got >= exposure, "never shorter than the exposure at ${micros} us")
            t.check(got >= minimum, "never shorter than the stream minimum at ${micros} us")
            micros = micros * 2 + 1
        }

        // A device that reports nothing is not a licence to invent a number: the
        // exposure alone is then the only thing actually known.
        t.eq(33 * ms, StreamLadder.frameDurationFor(33 * ms, 0L),
            "with no minimum reported, the exposure stands on its own")
        t.eq(1L, StreamLadder.frameDurationFor(0L, 0L),
            "and a nonsensical pair still yields a duration a request will accept")
    }

    private fun rollingOneFrameCostsNoCoverage(t: TestKit) {
        val k = Intrinsics.fromHorizontalFov(3000, 4000, 58.7)
        val cfg = CapturePlanConfig()
        // Rolled the way a phone is actually held, or the plan is a different one.
        val plan = CapturePlan.forCamera(k, cfg, 90.0)
        val targets = plan.targets

        // One representative per ring: rolling the frame at the pole and the frame
        // on the equator are different geometry.
        val perRing = LinkedHashMap<Long, Int>()
        for (i in targets.indices)
            perRing.putIfAbsent(Math.round(targets[i].pitchDeg), i)

        val sky = ArrayList<Vec3>()
        val weight = ArrayList<Double>()
        val nLat = 90
        val nLon = 180
        for (j in 0 until nLat) {
            val lat = -Math.PI / 2 + Math.PI * (j + 0.5) / nLat
            for (i in 0 until nLon) {
                val lon = -Math.PI + 2 * Math.PI * (i + 0.5) / nLon
                sky.add(Vec3(Math.cos(lat) * Math.sin(lon), Math.sin(lat),
                    Math.cos(lat) * Math.cos(lon)))
                weight.add(Math.cos(lat))
            }
        }
        val total = weight.sum()

        fun uncovered(rolled: Int, deg: Double): Double {
            val poses = Array(targets.size) { targets[it].rotation }
            poses[rolled] = targets[rolled].rotation
                .mul(SO3.exp(Vec3(0.0, 0.0, Math.toRadians(deg))))
            var miss = 0.0
            for (p in sky.indices) {
                var seen = false
                for (q in poses.indices) {
                    if (k.isVisible(poses[q].mulTranspose(sky[p]))) { seen = true; break }
                }
                if (!seen) miss += weight[p]
            }
            return miss / total
        }

        val base = uncovered(perRing.values.first(), 0.0)
        t.lessThan(base, 1e-9, "the plan covers the sphere to begin with")
        var worst = 0.0
        for ((pitch, i) in perRing) {
            for (deg in doubleArrayOf(15.0, 30.0, 45.0, 60.0)) {
                val miss = uncovered(i, deg)
                worst = Math.max(worst, miss)
                t.lessThan(miss, 1e-9,
                    "rolling the frame at $pitch degrees of pitch by $deg costs no coverage")
            }
        }
        t.note("worst uncovered fraction with one frame rolled up to 60 degrees: " +
                TestKit.fmt(100 * worst) + "%")

        // And it keeps enough partners to be placed: a frame nothing overlaps
        // cannot be tied into the pose graph however well the sphere is covered,
        // and a frame tied by one edge hangs off it.
        var worstPartners = Int.MAX_VALUE
        var worstLoss = 0
        for ((pitch, i) in perRing) {
            val before = plan.neighbourCount(i, k, 0.25, 16)
            t.greaterThan(before.toDouble(), 1.0, "the frame at $pitch has partners to begin with")
            for (deg in doubleArrayOf(30.0, 60.0)) {
                val after = partnersAfterRoll(plan, k, i, deg)
                worstPartners = Math.min(worstPartners, after)
                worstLoss = Math.max(worstLoss, before - after)
                t.greaterThan(after.toDouble(), 1.0,
                    "the frame at $pitch is still tied to the graph by more than one edge " +
                    "when rolled by $deg")
                t.greaterThan((after - before + 2).toDouble(), 0.0,
                    "and gives up at most one partner doing it")
            }
        }
        t.note("rolled by up to 60 degrees, the worst frame keeps $worstPartners partners " +
                "at a quarter overlap, having given up at most $worstLoss")
    }

    /** Partners sharing at least a quarter of a frame's view after it is rolled. */
    private fun partnersAfterRoll(plan: CapturePlan, k: Intrinsics, i: Int, deg: Double): Int {
        val grid = 16
        val rolled = plan.targets[i].rotation.mul(SO3.exp(Vec3(0.0, 0.0, Math.toRadians(deg))))
        val samples = ArrayList<Vec3>(grid * grid)
        for (gy in 0 until grid)
            for (gx in 0 until grid) {
                val u = (gx + 0.5) * k.width / grid - 0.5
                val v = (gy + 0.5) * k.height / grid - 0.5
                samples.add(rolled.mul(k.unproject(u, v)))
            }
        var n = 0
        for (j in plan.targets.indices) {
            if (j == i) continue
            var shared = 0
            for (d in samples) if (k.isVisible(plan.targets[j].rotation.mulTranspose(d))) shared++
            if (shared >= 0.25 * samples.size) n++
        }
        return n
    }
}
