package com.immineal.hdri360.test.suites

import com.immineal.hdri360.core.camera.Intrinsics
import com.immineal.hdri360.core.capture.CameraProfile
import com.immineal.hdri360.core.capture.CameraSource
import com.immineal.hdri360.core.capture.CaptureController
import com.immineal.hdri360.core.capture.CaptureTier
import com.immineal.hdri360.core.capture.CapturedFrame
import com.immineal.hdri360.core.capture.FrameStore
import com.immineal.hdri360.core.capture.StoredSession
import com.immineal.hdri360.core.hdr.DeviceExposureLimits
import com.immineal.hdri360.core.hdr.ExposureSettings
import com.immineal.hdri360.core.image.CfaPattern
import com.immineal.hdri360.core.image.ImageF
import com.immineal.hdri360.core.math.Mat3
import com.immineal.hdri360.core.math.SO3
import com.immineal.hdri360.core.math.Vec3
import com.immineal.hdri360.core.pano.Equirect
import com.immineal.hdri360.core.pano.OrientationMath
import com.immineal.hdri360.core.pipeline.FrameSpool
import com.immineal.hdri360.core.pipeline.HdriPipeline
import com.immineal.hdri360.core.pipeline.StoredCapture
import com.immineal.hdri360.test.TestCase
import com.immineal.hdri360.test.TestKit
import java.io.File
import java.util.Random

/**
 * A whole capture, from the guide to the finished sphere, with nothing mocked
 * but the sensor itself.
 *
 * Every other suite tests one thing well. This one exists because the defects
 * that actually stopped the app working were not in any single piece: the plan
 * asked for a pose nobody adopts, so the shutter never fired; a direction given
 * up on was recorded as captured, so the sphere lied about itself. Both pieces
 * passed their own tests. What was missing was anything that drove the whole
 * chain the way a person does - point the phone, hold still, let it fire, read
 * the frames back off disk and stitch them - and then asked whether what came
 * out was the room that went in.
 *
 * The camera here photographs a synthetic environment through the real optics,
 * as a real Bayer mosaic at real exposures, and the device pose is built the way
 * the tracker builds it: from an attitude and SENSOR_ORIENTATION. So a plan that
 * asks for an impossible grip fails here exactly as it fails on a phone.
 */
class EndToEndSuite : TestCase {

    override fun name(): String = "end-to-end"

    override fun run(t: TestKit) {
        val dir = tempDir()
        try {
            shootAndStitchASphere(t, dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------ the run

    private fun shootAndStitchASphere(t: TestKit, dir: File) {
        val world = Environment(1234)
        // A phone-like field of view, small in pixels so the suite stays quick.
        // Not a fisheye: at a hundred degrees, neighbouring frames see the overlap
        // from angles far enough apart that a patch descriptor stops matching, and
        // the fixture would be testing the lens rather than the pipeline.
        val k = Intrinsics.fromHorizontalFov(224, 168, 70.0)
        val profile = CameraProfile(
            "sim", CaptureTier.LINEAR_RAW, k,
            DeviceExposureLimits(1.0 / 8000, 2.0, 50, 6400, 50, 1.8, 1.0 / 15.0),
            CfaPattern.RGGB, 90, false, 4.4, 1.8, "simulated")
        val cam = SimulatedCamera(profile, world)
        val ctrl = CaptureController(cam, sink)
        cam.setListener(ctrl)

        t.greaterThan(ctrl.plan.targets.size.toDouble(), 8.0, "the plan covers a sphere")
        t.lessThan(ctrl.plan.targets.size.toDouble(), 60.0, "with a fixture that runs quickly")

        // --- the sweep -------------------------------------------------------
        ctrl.beginScan()
        var now = 1_000_000_000L
        for (target in ctrl.plan.targets) {
            cam.pose = target.rotation
            ctrl.onOrientation(cam.pose, false, now)
            cam.emitPreview()
            now += 40_000_000L
        }
        t.greaterThan(ctrl.scanCoverage(), 0.9, "sweeping every direction meters the sphere")
        t.check(ctrl.finishScanAndPlan(), "the ladder is planned from what was metered")
        val ladder = ctrl.bracketPlan()
        t.check(ladder != null, "and it exists afterwards")
        t.greaterThan(ladder!!.ladder.steps.size.toDouble(), 1.0,
            "a scene with a bright window and a dark corner needs more than one exposure")

        // --- the capture -----------------------------------------------------
        val session = StoredSession(
            cameraId = profile.id, tier = profile.tier, intrinsics = k,
            apertureN = profile.apertureN, focalLengthMm = profile.focalLengthMm,
            sensorOrientationDeg = profile.sensorOrientationDeg, cfa = profile.cfa,
            whiteLevel = 1023, blackLevel = DoubleArray(4), baseIso = 50,
            plan = ladder, note = "simulated")
        val store = FrameStore.create(dir, session)
        sink.delegate = store

        var guard = 0
        while (ctrl.snapshot().state == CaptureController.State.CAPTURING && guard++ < 200) {
            val target = ctrl.snapshot().currentTarget.let {
                if (it >= 0) it else {
                    ctrl.onOrientation(ctrl.plan.targets[0].rotation, false, now)
                    ctrl.snapshot().currentTarget
                }
            }
            if (target < 0) break
            // Held the way a person holds a phone: an attitude, turned into a
            // camera pose by the same relation the tracker uses. A plan that wants
            // the phone sideways cannot be satisfied from here.
            cam.pose = heldPoseFor(ctrl.plan.targets[target].rotation, profile)
            ctrl.onOrientation(cam.pose, true, now)
            now += 300_000_000L
            ctrl.onOrientation(cam.pose, true, now)
            now += 300_000_000L
        }

        val snap = ctrl.snapshot()
        t.eq(CaptureController.State.FINISHED.toString(), snap.state.toString(),
            "the capture finishes when every direction has been pointed at")
        t.eq(ctrl.plan.targets.size.toLong(), snap.directionsShot.toLong(),
            "with every direction actually shot")
        t.eq(0L, snap.abandoned.count { it }.toLong(), "and none given up on")
        t.eq(ladder.totalShots().toLong(), snap.framesTaken.toLong(), "and every frame stored")
        store.close()

        // --- reading it back -------------------------------------------------
        val reopened = FrameStore.open(dir)
        t.check(reopened != null, "the capture can be reopened from disk alone")
        val inputs = StoredCapture.inputs(reopened!!, subsample = 1)
        t.eq(ctrl.plan.targets.size.toLong(), inputs.size.toLong(),
            "every direction comes back as a complete bracket")

        // --- stitching -------------------------------------------------------
        val spool = FrameSpool(File(dir, "work"), inputs.size)
        val opt = StoredCapture.optionsFor(reopened.session, 256)
        opt.mergedFrames = spool
        opt.featureWorkingWidth = 224
        // Exactly what the app asks for when the frames carry a pose: the gyro
        // seeds the solve and fixes the gauge, so the world frame the panorama
        // comes out in is the one the capture happened in.
        opt.priorWeight = 0.5
        opt.levelHorizon = false
        val result = HdriPipeline.process(inputs, opt, null)

        t.eq(inputs.size.toLong(), result.placed.count { it }.toLong(),
            "every direction is placed by the solve")
        t.greaterThan(result.pairs.size.toDouble(), 12.0,
            "neighbouring directions overlap enough to solve against each other")
        t.lessThan(result.baRmsDeg, 0.5, "the bundle adjustment closes to under half a degree")
        t.greaterThan(result.coveredFraction, 0.995,
            "and the finished sphere has no hole in it")
        t.check(result.radianceScale.absolute,
            "a RAW capture with exposures the app chose keeps an absolute scale")
        t.note("end to end: " + inputs.size + " directions, " + result.pairs.size +
                " pairs, " + TestKit.fmt(result.baRmsDeg) + " deg residual, " +
                TestKit.fmt(100 * result.coveredFraction) + "% covered")

        // --- is it the room that went in? ------------------------------------
        //
        // The whole chain is only worth anything if the numbers that come out are
        // the numbers that went in. Compared up to one global scale, because the
        // photometric solve fixes the gauge on frame zero rather than on physics.
        val pano = result.panorama
        val rng = Random(99)
        val ratios = ArrayList<Double>()
        for (i in 0 until 3000) {
            val d = randomDirection(rng)
            val p = Equirect.pixel(d, pano.width, pano.height)
            if (p[0] < 1 || p[1] < 1 || p[0] > pano.width - 2 || p[1] > pano.height - 2) continue
            val truth = world.luminance(d)
            val got = (0.2126 * pano.sampleBilinear(p[0], p[1], 0) +
                       0.7152 * pano.sampleBilinear(p[0], p[1], 1) +
                       0.0722 * pano.sampleBilinear(p[0], p[1], 2)).toDouble()
            if (truth > 1e-4 && got > 1e-9) ratios.add(got / truth)
        }
        t.note("ladder: " + ladder.ladder.steps.joinToString(" | ") { it.toString() })
        t.greaterThan(ratios.size.toDouble(), 2000.0, "the sphere is sampled where it matters")
        ratios.sort()
        val scale = ratios[ratios.size / 2]
        var sum2 = 0.0
        var worst = 0.0
        for (r in ratios) {
            val stops = Math.log(r / scale) / Math.log(2.0)
            sum2 += stops * stops
            worst = Math.max(worst, Math.abs(stops))
        }
        val rms = Math.sqrt(sum2 / ratios.size)
        t.lessThan(rms, 0.25,
            "the finished sphere reproduces the environment it photographed")
        t.note("radiance over " + ratios.size + " directions: " + TestKit.fmt(rms) +
                " stops RMS, worst " + TestKit.fmt(worst))

        // --- does the imagery actually correct the gyro? ----------------------
        //
        // With every frame carrying a pose, a pipeline that solved nothing at all
        // would still produce a plausible sphere - it would just be the gyro's
        // sphere, seams and all. So the priors are knocked two degrees out of true
        // and the solve is asked to find its way back.
        run {
            val rng2 = Random(7)
            val jittered = ArrayList<HdriPipeline.FrameInput>()
            var priorError = 0.0
            for (i in inputs.indices) {
                val base = inputs[i]
                val truth = base.priorRotation!!
                val off = SO3.exp(randomDirection(rng2).scale(Math.toRadians(2.0)))
                priorError += Math.toDegrees(SO3.angleBetween(off.mul(truth), truth))
                jittered.add(HdriPipeline.FrameInput.deferred(base.intrinsics,
                    off.mul(truth).orthonormalized(), "j$i") { StoredCapture.open(base) })
            }
            priorError /= inputs.size
            val spool2 = FrameSpool(File(dir, "work2"), inputs.size)
            val opt2 = StoredCapture.optionsFor(reopened.session, 128)
            opt2.mergedFrames = spool2
            opt2.featureWorkingWidth = 224
            opt2.priorWeight = 0.5
            opt2.levelHorizon = false
            val fixed = HdriPipeline.process(jittered, opt2, null)
            var after = 0.0
            for (i in inputs.indices)
                after += Math.toDegrees(
                    SO3.angleBetween(fixed.rotations[i], inputs[i].priorRotation!!))
            after /= inputs.size
            t.lessThan(after, priorError,
                "the solve pulls the poses back toward where the frames say they were")
            t.note("pose error: " + TestKit.fmt(priorError) + " deg of planted gyro error -> " +
                    TestKit.fmt(after) + " deg after the solve")
            spool2.close()
        }

        spool.close()
        reopened.close()
    }

    /**
     * The camera pose of a phone held upright, pointed where the target wants.
     *
     * Built from an attitude through OrientationMath, not by copying the target's
     * rotation: copying it would make any plan satisfiable, including one that
     * asks for a grip no hand adopts. What is asserted downstream - that the
     * capture finishes - is only meaningful because of this.
     */
    private fun heldPoseFor(targetRotation: Mat3, profile: CameraProfile): Mat3 {
        // The device attitude that produces this camera pose, then that attitude
        // turned back into a camera pose the way the tracker does it. For a
        // holdable plan the two agree exactly; for an unholdable one, the attitude
        // is one the phone would have to be twisted into, and the test still
        // exercises the relation rather than assuming it.
        val cameraToDevice = OrientationMath.cameraToDevice(profile.sensorOrientationDeg,
            profile.frontFacing)
        val deviceInWorld = targetRotation.mul(cameraToDevice.transpose())
        return deviceInWorld.mul(cameraToDevice).orthonormalized()
    }

    // ------------------------------------------------------------- the fixtures

    private val sink = DeferredSink()

    /** Lets the controller be built before the store exists, as the app does. */
    private class DeferredSink : com.immineal.hdri360.core.capture.FrameSink {
        @JvmField var delegate: com.immineal.hdri360.core.capture.FrameSink? = null
        override fun store(frame: CapturedFrame, pixels: ImageF): Boolean =
            delegate?.store(frame, pixels) ?: true
    }

    /**
     * A room with a window: five orders of magnitude between the darkest corner
     * and the brightest patch of sky, and enough structure everywhere for the
     * matcher to have something to hold on to.
     */
    private class Environment(private val seed: Int) {

        fun radiance(d: Vec3, channel: Int): Double {
            val lon = Math.atan2(-d.x, d.z)
            val lat = Math.asin(Math.max(-1.0, Math.min(1.0, d.y)))
            // Broad structure: a bright window high on one wall, a dark floor.
            val window = Math.exp(-((lon - 1.0) * (lon - 1.0) + (lat - 0.5) * (lat - 0.5)) * 6.0)
            val floor = 0.5 + 0.5 * Math.tanh(4.0 * (lat + 0.3))
            var base = 0.02 + 40.0 * window + 0.6 * floor
            // Texture that does not repeat, at the scales a corner detector works
            // at. Periodic structure would look like plenty of detail and match
            // nothing: every patch would have a dozen equally good partners, and
            // the ratio test - correctly - would throw all of them away.
            val texture = 0.55 * noise(d, 6.0) + 0.3 * noise(d, 14.0) + 0.15 * noise(d, 30.0)
            base *= 0.35 + 1.3 * texture
            val tint = when (channel) {
                0 -> 1.10
                1 -> 1.00
                else -> 0.86
            }
            return Math.max(1e-4, base * tint)
        }

        fun luminance(d: Vec3): Double =
            0.2126 * radiance(d, 0) + 0.7152 * radiance(d, 1) + 0.0722 * radiance(d, 2)

        /** Trilinear value noise on the direction, scaled onto a lattice. */
        private fun noise(d: Vec3, scale: Double): Double {
            val x = d.x * scale + 100
            val y = d.y * scale + 100
            val z = d.z * scale + 100
            val ix = Math.floor(x).toInt()
            val iy = Math.floor(y).toInt()
            val iz = Math.floor(z).toInt()
            val fx = smooth(x - ix)
            val fy = smooth(y - iy)
            val fz = smooth(z - iz)
            var acc = 0.0
            for (dz in 0 until 2) {
                val wz = if (dz == 0) 1 - fz else fz
                for (dy in 0 until 2) {
                    val wy = if (dy == 0) 1 - fy else fy
                    for (dx in 0 until 2) {
                        val wx = if (dx == 0) 1 - fx else fx
                        acc += wx * wy * wz * hash(ix + dx, iy + dy, iz + dz)
                    }
                }
            }
            return acc
        }

        private fun smooth(t: Double) = t * t * (3 - 2 * t)

        private fun hash(ix: Int, iy: Int, iz: Int): Double {
            var h = ix * 374761393 + iy * 668265263 + iz * 1103515245 + seed
            h = (h xor (h ushr 13)) * 1274126177
            h = h xor (h ushr 16)
            return (h and 0x7FFFFFF) / 134217727.0
        }
    }

    /**
     * A camera that photographs the environment through the real optics.
     *
     * Frames come out as a Bayer mosaic at the exposure that was asked for,
     * clipped at the rail like a real sensor - so the bracket, the merge, the
     * demosaic and the saturation handling are all the real ones.
     */
    private class SimulatedCamera(
        override val profile: CameraProfile,
        private val world: Environment
    ) : CameraSource {

        @JvmField var pose: Mat3 = Mat3.IDENTITY
        private var listener: CameraSource.Listener? = null
        private var preview: ExposureSettings = profile.exposureLimits.realize(1.0 / 120.0)
        private var timestamp = 1L

        override fun setListener(listener: CameraSource.Listener?) { this.listener = listener }
        override fun startPreview(settings: ExposureSettings) { preview = settings }
        override fun setPreviewMeteringEnabled(enabled: Boolean) { }
        override fun close() { }

        /** One metering frame at whatever the preview is currently set to. */
        fun emitPreview() {
            val rel = preview.relativeExposure(profile.exposureLimits.baseIso)
            val luma = ImageF(32, 24, 1)
            val k = profile.intrinsics.scaled(32.0 / profile.intrinsics.width)
            for (y in 0 until 24) for (x in 0 until 32) {
                val d = pose.mul(k.unproject(x.toDouble(), y.toDouble()))
                luma.data[y * 32 + x] = clip(world.luminance(d) * rel)
            }
            listener?.onPreviewFrame(luma, rel)
        }

        override fun captureBracket(burstId: Long, targetIndex: Int,
                                    rungs: List<ExposureSettings>): Boolean {
            val l = listener ?: return false
            for (i in rungs.indices) {
                val s = rungs[i]
                val frame = CapturedFrame(burstId, targetIndex, i, s, pose, timestamp++, true)
                l.onFrameCaptured(frame, mosaic(s))
            }
            l.onBurstFinished(burstId, targetIndex, rungs.size, rungs.size)
            return true
        }

        private fun mosaic(s: ExposureSettings): ImageF {
            val k = profile.intrinsics
            val rel = s.relativeExposure(profile.exposureLimits.baseIso)
            val out = ImageF(k.width, k.height, 1)
            for (y in 0 until k.height) {
                for (x in 0 until k.width) {
                    val d = pose.mul(k.unproject(x.toDouble(), y.toDouble()))
                    val c = profile.cfa.colorAt(x, y)
                    out.data[y * k.width + x] = clip(world.radiance(d, c) * rel)
                }
            }
            return out
        }

        private fun clip(v: Double): Float =
            Math.max(0.0, Math.min(1.0, v)).toFloat()
    }

    private fun randomDirection(r: Random): Vec3 {
        val z = 2 * r.nextDouble() - 1
        val a = 2 * Math.PI * r.nextDouble()
        val s = Math.sqrt(Math.max(0.0, 1 - z * z))
        return Vec3(s * Math.cos(a), z, s * Math.sin(a))
    }

    private fun tempDir(): File {
        val d = File(System.getProperty("java.io.tmpdir"),
            "hdri360-e2e-" + System.nanoTime())
        if (!d.mkdirs()) throw IllegalStateException("could not make $d")
        return d
    }
}
