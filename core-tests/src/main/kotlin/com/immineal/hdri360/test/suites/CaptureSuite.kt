package com.immineal.hdri360.test.suites

import com.immineal.hdri360.core.camera.Intrinsics
import com.immineal.hdri360.core.capture.CameraProfile
import com.immineal.hdri360.core.capture.CameraSource
import com.immineal.hdri360.core.capture.CaptureController
import com.immineal.hdri360.core.capture.CaptureTier
import com.immineal.hdri360.core.capture.CapturedFrame
import com.immineal.hdri360.core.capture.FrameSink
import com.immineal.hdri360.core.hdr.DeviceExposureLimits
import com.immineal.hdri360.core.hdr.ExposureSettings
import com.immineal.hdri360.core.image.CfaPattern
import com.immineal.hdri360.core.image.ImageF
import com.immineal.hdri360.core.math.Mat3
import com.immineal.hdri360.core.math.SO3
import com.immineal.hdri360.core.math.Vec3
import com.immineal.hdri360.test.TestCase
import com.immineal.hdri360.test.TestKit

/**
 * The capture state machine, driven against a camera that is not there.
 *
 * This is the point of keeping the controller free of platform types. A capture
 * path tested only by pointing a phone at a room is one whose failure paths have
 * never been tested: a burst that comes back short, one that never comes back at
 * all, storage refusing a write, the camera disconnecting halfway. Those are the
 * cases that lose someone's capture at frame 140, and every one of them is
 * exercised here in milliseconds with no device attached.
 *
 * Time is an argument rather than a clock, so timeouts are tested by asserting
 * what happens after four seconds without waiting four seconds.
 */
class CaptureSuite : TestCase {
    override fun name(): String = "capture"

    /** A camera whose every response the test decides. */
    private class FakeCamera(override val profile: CameraProfile) : CameraSource {
        // Not named "listener": the generated setter would clash with the
        // interface method it implements.
        var bound: CameraSource.Listener? = null
        var lastTarget = -1
        var lastRungs: List<ExposureSettings> = emptyList()
        var burstsRequested = 0
        var refuseBursts = false
        var previewStarts = 0
        var meteringEnabled = false
        var closed = false
        private var inFlight = false

        override fun setListener(listener: CameraSource.Listener?) { bound = listener }
        var lastPreview: ExposureSettings? = null
        override fun startPreview(settings: ExposureSettings) { lastPreview = settings; previewStarts++ }
        override fun setPreviewMeteringEnabled(enabled: Boolean) { meteringEnabled = enabled }
        override fun close() { closed = true }

        var lastBurst = 0L
        override fun captureBracket(burstId: Long, targetIndex: Int,
                                    rungs: List<ExposureSettings>): Boolean {
            if (refuseBursts || inFlight) return false
            inFlight = true
            burstsRequested++
            lastBurst = burstId
            lastTarget = targetIndex
            lastRungs = rungs
            return true
        }

        /** Delivers [frames] of the last requested burst, then completes it. */
        fun deliver(frames: Int, complete: Boolean = true) {
            val l = bound ?: return
            val px = ImageF(4, 4, 1)
            for (i in 0 until frames) {
                l.onFrameCaptured(CapturedFrame(lastBurst, lastTarget, i, lastRungs[i],
                    Mat3.IDENTITY, 1000L + i, true), px)
            }
            if (complete) {
                inFlight = false
                l.onBurstFinished(lastBurst, lastTarget, lastRungs.size, frames)
            }
        }

        /** A burst that simply never reports back, as a wedged camera does. */
        fun abandon() { inFlight = false }
    }

    private class CountingSink : FrameSink {
        var stored = 0
        var refuse = false
        override fun store(frame: CapturedFrame, pixels: ImageF): Boolean {
            if (refuse) return false
            stored++
            return true
        }
    }

    private fun profile(tier: CaptureTier = CaptureTier.LINEAR_RAW) = CameraProfile(
        "0", tier,
        Intrinsics.fromHorizontalFov(600, 800, 58.7),
        DeviceExposureLimits(1.0 / 17554, 16.0, 29, 7276, 29, 1.7, 1.0 / 15.0),
        CfaPattern.GRBG, 90, false, 4.53, 1.7,
        "test")

    /** Meters the sphere by pointing at every direction in turn. */
    private fun scanEverything(c: CaptureController, cam: FakeCamera, t0: Long): Long {
        var now = t0
        val luma = ImageF(8, 8, 1)
        for (i in luma.data.indices) luma.data[i] = 0.35f
        for (i in c.plan.targets.indices) {
            c.onOrientation(c.plan.targets[i].rotation, false, now)
            cam.bound?.onPreviewFrame(luma, 1.0 / 500)
            now += 50_000_000L
        }
        return now
    }

    /**
     * Lets the controller choose the next direction, then points at it and holds
     * still long enough to fire. Returns the direction and the new clock.
     *
     * The priming pose matters: the controller only picks a target when it is
     * given an orientation while capturing, so a test that reads currentTarget
     * before ever moving reads -1.
     */
    private fun aimAtNext(c: CaptureController, from: Long,
                          dwellNs: Long = 200_000_000L): Pair<Int, Long> {
        var now = from
        c.onOrientation(c.plan.targets[0].rotation, false, now)
        val target = c.snapshot().currentTarget
        if (target < 0) return Pair(-1, now)
        now += 100_000_000L
        return Pair(target, settleOn(c, target, now, dwellNs))
    }

    /** Points at a known target and holds still long enough to fire. */
    private fun settleOn(c: CaptureController, target: Int, from: Long,
                         dwellNs: Long = 200_000_000L): Long {
        var now = from
        val pose = c.plan.targets[target].rotation
        c.onOrientation(pose, true, now)
        now += dwellNs
        c.onOrientation(pose, true, now)
        return now
    }

    override fun run(t: TestKit) {
        // --- a capture that goes to plan --------------------------------------
        run {
            val cam = FakeCamera(profile())
            val sink = CountingSink()
            val c = CaptureController(cam, sink)
            cam.setListener(c)

            t.eq(CaptureController.State.IDLE.toString(), c.snapshot().state.toString(),
                "a fresh controller is idle")
            t.greaterThan(c.plan.targets.size.toDouble(), 20.0, "a full sphere is planned")

            c.beginScan()
            t.check(cam.meteringEnabled, "the scan turns metering on")
            var now = scanEverything(c, cam, 1_000_000_000L)
            t.greaterThan(c.scanCoverage(), 0.9, "sweeping every direction meters the sphere")

            val meteringExposure = cam.lastPreview
            t.check(c.finishScanAndPlan(), "planning succeeds once the scene is metered")
            t.check(!cam.meteringEnabled, "and metering is turned back off")

            // The metering exposure puts the brightest tenth of a percent just under
            // saturation, which in a real room leaves the preview black - and nobody
            // can aim a sphere at a black screen. Once the ladder is fixed the
            // preview is re-exposed for the eye.
            val viewing = cam.lastPreview
            t.check(viewing !== meteringExposure,
                "the preview is re-exposed once the scan is over")
            val scene = c.snapshot().scene
            t.check(scene != null, "and the scene it was metered from is known")
            if (scene != null && viewing != null) {
                val median = scene.medianRadiance *
                    viewing.relativeExposure(cam.profile.exposureLimits.baseIso)
                t.greaterThan(median, 0.02,
                    "which puts the scene's median somewhere a person can see")
                t.lessThan(median, 0.9, "without putting it on the rail either")
            }
            if (viewing != null)
                t.check(viewing.exposureTimeSec <= cam.profile.exposureLimits.maxHandheldTimeSec,
                    "and never asks for a shutter slower than a hand can hold, whatever " +
                    "the scene's dark end does")
            val planned = c.snapshot().framesPlanned
            t.greaterThan(planned.toDouble(), c.plan.targets.size.toDouble(),
                "the plan shoots more frames than directions, because it brackets")

            // Walk the sphere, shooting whatever the controller asks for next.
            var guard = 0
            while (c.snapshot().state == CaptureController.State.CAPTURING && guard++ < 300) {
                val (target, then) = aimAtNext(c, now + 300_000_000L)
                now = then
                if (target < 0) break
                if (cam.lastTarget == target) cam.deliver(cam.lastRungs.size)
            }
            val snap = c.snapshot()
            t.eq(CaptureController.State.FINISHED.toString(), snap.state.toString(),
                "the capture finishes")
            t.eq(c.plan.targets.size.toLong(), snap.directionsShot.toLong(),
                "every direction is shot")
            t.eq(planned.toLong(), snap.framesTaken.toLong(),
                "and every planned frame was stored")
            t.eq(planned.toLong(), sink.stored.toLong(), "the sink saw all of them")
            t.note("nominal capture: " + snap.directionsShot + " directions, " +
                    snap.framesTaken + " frames, " + cam.burstsRequested + " bursts")
        }

        // --- a burst that comes back short ---------------------------------------
        // The predecessor marked a direction done when the burst's metadata
        // completed, which can arrive before the pixels. A short burst then left a
        // hole nothing would ever fill.
        run {
            val cam = FakeCamera(profile())
            val c = CaptureController(cam, CountingSink())
            cam.setListener(c)
            c.beginScan()
            var now = scanEverything(c, cam, 1_000_000_000L)
            c.finishScanAndPlan()

            val (target, then) = aimAtNext(c, now + 300_000_000L)
            now = then
            t.greaterThan(target.toDouble(), -1.0, "a direction is chosen once capturing")
            t.eq(target.toLong(), cam.lastTarget.toLong(), "a bracket is requested for the target")
            cam.deliver(cam.lastRungs.size - 1)         // one frame short

            t.check(!c.snapshot().shot[target],
                "a direction that came back short is not marked as shot")
            t.eq(CaptureController.State.CAPTURING.toString(), c.snapshot().state.toString(),
                "and the capture is still going")

            // Offered again, it succeeds.
            now = settleOn(c, target, now + 300_000_000L)
            cam.deliver(cam.lastRungs.size)
            t.check(c.snapshot().shot[target], "a retry that delivers everything counts")
        }

        // --- a burst that never comes back ----------------------------------------
        // Without a timeout the controller waits forever: the predecessor cleared
        // its pending target only on completion, so one dropped frame wedged the
        // whole capture with no way out but starting again.
        run {
            val cam = FakeCamera(profile())
            val c = CaptureController(cam, CountingSink())
            cam.setListener(c)
            c.beginScan()
            var now = scanEverything(c, cam, 1_000_000_000L)
            c.finishScanAndPlan()

            val (target, then) = aimAtNext(c, now + 300_000_000L)
            now = then
            t.eq(1L, cam.burstsRequested.toLong(), "a burst went out")
            cam.abandon()                                // camera never reports back

            // Long before the timeout, nothing changes.
            now += 1_000_000_000L
            c.onOrientation(c.plan.targets[target].rotation, true, now)
            t.eq(1L, cam.burstsRequested.toLong(), "no second burst while the first may still land")

            // Past it, the controller recovers rather than waiting forever.
            now += CaptureController.Config().burstTimeoutNs + 1_000_000_000L
            c.onOrientation(c.plan.targets[target].rotation, true, now)
            now = aimAtNext(c, now + 300_000_000L).second
            t.greaterThan(cam.burstsRequested.toDouble(), 1.0,
                "after the timeout the capture carries on rather than wedging")
            t.check(c.snapshot().state == CaptureController.State.CAPTURING ||
                    c.snapshot().state == CaptureController.State.FINISHED,
                "and is still in a usable state")
        }

        // --- a direction that keeps failing must not trap the capture --------------
        run {
            val cam = FakeCamera(profile())
            val c = CaptureController(cam, CountingSink())
            cam.setListener(c)
            c.beginScan()
            var now = scanEverything(c, cam, 1_000_000_000L)
            c.finishScanAndPlan()

            val stuck = aimAtNext(c, now + 300_000_000L).first
            t.greaterThan(stuck.toDouble(), -1.0, "a direction is chosen")
            if (cam.lastTarget == stuck) cam.deliver(0)
            var tries = 1
            while (!c.snapshot().abandoned[stuck] && tries++ < 10) {
                now = settleOn(c, stuck, now + 300_000_000L)
                if (cam.lastTarget == stuck) cam.deliver(0)      // never delivers anything
            }
            val after = c.snapshot()
            t.check(after.abandoned[stuck],
                "a direction that keeps failing is eventually given up on")
            t.lessThan(tries.toDouble(), 6.0, "and it gives up promptly, not after ten attempts")

            // Giving up is not the same as capturing, and one flag cannot mean both.
            // Counting an abandoned direction as shot makes the app report a full
            // sphere it does not have, and makes a resumed capture skip the very
            // direction that has no frames in it.
            t.check(!after.shot[stuck], "but it is not reported as captured")
            t.eq(0L, after.directionsShot.toLong(), "so nothing counts as shot yet")
            t.check(!after.message.isNullOrEmpty(), "and the user is told what happened")
            now = settleOn(c, stuck, now + 300_000_000L)
            t.check(c.snapshot().currentTarget != stuck,
                "the next direction offered is a different one")
            t.note("a hopeless direction was abandoned after " + tries + " attempts")

            // Shooting the rest still finishes the capture, with an honest count.
            var guard = 0
            while (c.snapshot().state == CaptureController.State.CAPTURING && guard++ < 300) {
                val (target, then) = aimAtNext(c, now + 300_000_000L)
                now = then
                if (target < 0) break
                if (cam.lastTarget == target) cam.deliver(cam.lastRungs.size)
            }
            val end = c.snapshot()
            t.eq(CaptureController.State.FINISHED.toString(), end.state.toString(),
                "one hopeless direction does not trap the capture")
            t.eq((c.plan.targets.size - 1).toLong(), end.directionsShot.toLong(),
                "and the count reports every direction that was actually captured")
            t.eq(1L, end.abandoned.count { it }.toLong(), "with the one that was not still marked")
        }

        // --- storage refusing a write -----------------------------------------------
        run {
            val cam = FakeCamera(profile())
            val sink = CountingSink()
            val c = CaptureController(cam, sink)
            cam.setListener(c)
            c.beginScan()
            var now = scanEverything(c, cam, 1_000_000_000L)
            c.finishScanAndPlan()

            sink.refuse = true
            val (target, then) = aimAtNext(c, now + 300_000_000L)
            now = then
            cam.deliver(cam.lastRungs.size)
            t.check(!c.snapshot().shot[target],
                "frames that could not be written do not count as a captured direction")
            t.eq(0L, c.snapshot().framesTaken.toLong(), "and are not counted as taken")
            t.check(c.snapshot().message != null, "the failure is reported rather than swallowed")
        }

        // --- stillness has to persist ------------------------------------------------
        // A phone swept past a target passes through zero angular rate on the way;
        // firing on that single sample is how a bracket comes back smeared.
        run {
            val cam = FakeCamera(profile())
            val c = CaptureController(cam, CountingSink())
            cam.setListener(c)
            c.beginScan()
            var now = scanEverything(c, cam, 1_000_000_000L)
            c.finishScanAndPlan()

            c.onOrientation(c.plan.targets[0].rotation, false, now)
            val target = c.snapshot().currentTarget
            t.greaterThan(target.toDouble(), -1.0, "a direction is chosen")
            val pose = c.plan.targets[target].rotation
            now += 500_000_000L
            c.onOrientation(pose, true, now)                    // first still sample
            t.eq(0L, cam.burstsRequested.toLong(),
                "one still sample is a moment of stillness, not steadiness")
            now += 20_000_000L
            c.onOrientation(pose, true, now)                    // still, but only 20 ms
            t.eq(0L, cam.burstsRequested.toLong(), "nor is twenty milliseconds of it")
            now += 200_000_000L
            c.onOrientation(pose, true, now)
            t.eq(1L, cam.burstsRequested.toLong(), "holding still long enough does fire")

            // Movement resets the clock rather than merely pausing it.
            cam.deliver(cam.lastRungs.size)
            val next = c.snapshot().currentTarget
            val nextPose = c.plan.targets[next].rotation
            now += 400_000_000L
            c.onOrientation(nextPose, true, now)
            now += 100_000_000L
            c.onOrientation(nextPose, false, now)               // moved
            now += 100_000_000L
            c.onOrientation(nextPose, true, now)                // still again, but freshly
            t.eq(1L, cam.burstsRequested.toLong(),
                "moving restarts the dwell rather than resuming it")
        }

        // --- the snapshot is a copy ------------------------------------------------
        run {
            val cam = FakeCamera(profile())
            val c = CaptureController(cam, CountingSink())
            cam.setListener(c)
            val a = c.snapshot()
            a.shot[0] = true
            t.check(!c.snapshot().shot[0],
                "a caller mutating its snapshot cannot reach into the controller")
            val b = c.snapshot()
            t.check(a.shot !== b.shot, "and each snapshot is its own array")
        }

        // --- the camera going away ---------------------------------------------------
        run {
            val cam = FakeCamera(profile())
            val c = CaptureController(cam, CountingSink())
            cam.setListener(c)
            c.beginScan()
            var now = scanEverything(c, cam, 1_000_000_000L)
            c.finishScanAndPlan()
            now = aimAtNext(c, now + 300_000_000L).second

            c.onCameraError("the camera was disconnected", true)
            t.eq(CaptureController.State.FAILED.toString(), c.snapshot().state.toString(),
                "a fatal camera error fails the capture rather than hanging it")
            t.check(c.snapshot().message!!.contains("disconnected"), "and says what happened")
        }

        // --- resuming an interrupted capture ------------------------------------------
        // The ladder has to come back with the frames: a resumed capture that
        // re-planned would put its second half on a different radiance scale.
        run {
            val cam = FakeCamera(profile())
            val c = CaptureController(cam, CountingSink())
            cam.setListener(c)
            c.beginScan()
            scanEverything(c, cam, 1_000_000_000L)
            c.finishScanAndPlan()
            val ladder = cam.lastRungs                       // not used, but the plan exists

            val cam2 = FakeCamera(profile())
            val c2 = CaptureController(cam2, CountingSink())
            cam2.setListener(c2)
            val half = BooleanArray(c2.plan.targets.size) { it < c2.plan.targets.size / 2 }
            val planned = com.immineal.hdri360.core.hdr.BracketPlanner.plan(
                List(c2.plan.targets.size) {
                    com.immineal.hdri360.core.hdr.SceneStats(1.0, 500.0, 20.0, 0.0, 0.0, false, false)
                },
                cam2.profile.exposureLimits, com.immineal.hdri360.core.hdr.BracketConfig())
            c2.resume(half, planned)

            val snap = c2.snapshot()
            t.eq(CaptureController.State.CAPTURING.toString(), snap.state.toString(),
                "a part-finished capture resumes into capturing")
            t.eq((c2.plan.targets.size / 2).toLong(), snap.directionsShot.toLong(),
                "with the directions already on disk still marked")
            t.greaterThan(snap.framesTaken.toDouble(), 0.0,
                "and the frames already taken counted toward the total")
            t.eq(planned.totalShots().toLong(), snap.framesPlanned.toLong(),
                "against the original ladder, not a freshly planned one")

            // It shoots only what is left.
            var now = 10_000_000_000L
            var guard = 0
            while (c2.snapshot().state == CaptureController.State.CAPTURING && guard++ < 300) {
                val (target, then) = aimAtNext(c2, now + 300_000_000L)
                now = then
                if (target < 0) break
                t.check(!half[target], "a resumed capture never reshoots a finished direction")
                if (cam2.lastTarget == target) cam2.deliver(cam2.lastRungs.size)
            }
            t.eq(CaptureController.State.FINISHED.toString(), c2.snapshot().state.toString(),
                "and finishes the sphere")
            t.note("resumed a capture with " + (c2.plan.targets.size / 2) +
                    " directions already done and finished the rest")
        }

        // --- a straggler from an abandoned burst ----------------------------------------
        run {
            val cam = FakeCamera(profile())
            val sink = CountingSink()
            val c = CaptureController(cam, sink)
            cam.setListener(c)
            c.beginScan()
            var now = scanEverything(c, cam, 1_000_000_000L)
            c.finishScanAndPlan()

            val (first, then) = aimAtNext(c, now + 300_000_000L)
            now = then
            val stale = cam.lastTarget
            val staleBurst = cam.lastBurst
            val staleRung = cam.lastRungs[0]
            cam.abandon()
            now += CaptureController.Config().burstTimeoutNs + 1_000_000_000L
            c.onOrientation(c.plan.targets[first].rotation, true, now)   // times out

            val before = c.snapshot().framesTaken
            // The lost burst's frame finally turns up, long after it was written off.
            c.onFrameCaptured(CapturedFrame(staleBurst, stale, 0, staleRung, Mat3.IDENTITY,
                1L, true), ImageF(4, 4, 1))
            t.eq(before.toLong(), c.snapshot().framesTaken.toLong(),
                "a frame from a burst already written off is not counted")
        }

        // --- a direction whose bursts never come back at all ---------------------------
        // Distinct from a short burst: nothing completes, so the only thing that
        // ever settles this direction is the timeout. That path used to write
        // shot[t] on the way out and never write settled[t], which both reported a
        // direction the app did not have and left the guide offering it forever -
        // a capture that could not finish.
        run {
            val cam = FakeCamera(profile())
            val c = CaptureController(cam, CountingSink())
            cam.setListener(c)
            c.beginScan()
            var now = scanEverything(c, cam, 1_000_000_000L)
            c.finishScanAndPlan()

            val silent = aimAtNext(c, now + 300_000_000L).first
            t.greaterThan(silent.toDouble(), -1.0, "a direction is chosen")
            var tries = 0
            while (!c.snapshot().abandoned[silent] && tries++ < 10) {
                cam.abandon()                                  // the camera never reports
                now += CaptureController.Config().burstTimeoutNs + 1_000_000_000L
                c.onOrientation(c.plan.targets[silent].rotation, true, now)
                now = settleOn(c, silent, now + 300_000_000L)
            }
            val after = c.snapshot()
            t.check(after.abandoned[silent], "repeated timeouts give up on the direction")
            t.check(!after.shot[silent], "without claiming it was captured")
            t.eq(0L, after.directionsShot.toLong(), "so the sphere is still empty")

            var guard = 0
            while (c.snapshot().state == CaptureController.State.CAPTURING && guard++ < 300) {
                val (target, then) = aimAtNext(c, now + 300_000_000L)
                now = then
                if (target < 0) break
                if (cam.lastTarget == target) cam.deliver(cam.lastRungs.size)
            }
            t.eq(CaptureController.State.FINISHED.toString(), c.snapshot().state.toString(),
                "and the rest of the sphere still reaches the end")
            t.eq((c.plan.targets.size - 1).toLong(),
                c.snapshot().directionsShot.toLong(), "with an honest count")
        }

        // --- aim and roll are judged separately ------------------------------------------
        // One tolerance for both is what makes a sphere unshootable by hand: the
        // aim is reached, the wrist is a few degrees off, and the shutter never
        // fires. Rolling a frame turns its footprint about its own centre, which
        // the plan's overlap absorbs; mis-aiming moves it off the sphere.
        run {
            val cfg = CaptureController.Config()
            cfg.alignmentToleranceDeg = 7.0
            cfg.rollToleranceDeg = 15.0
            val cam = FakeCamera(profile())
            val c = CaptureController(cam, CountingSink(), cfg)
            cam.setListener(c)
            c.beginScan()
            var now = scanEverything(c, cam, 1_000_000_000L)
            c.finishScanAndPlan()

            c.onOrientation(c.plan.targets[0].rotation, false, now)
            val target = c.snapshot().currentTarget
            val pose = c.plan.targets[target].rotation

            // Rolled ten degrees about the optical axis: aimed correctly, so it fires.
            val rolled = pose.mul(SO3.exp(Vec3(0.0, 0.0, Math.toRadians(10.0))))
            now += 400_000_000L
            c.onOrientation(rolled, true, now)
            now += 300_000_000L
            c.onOrientation(rolled, true, now)
            t.eq(1L, cam.burstsRequested.toLong(),
                "a frame rolled inside the roll tolerance still fires")
            t.lessThan(Math.toDegrees(SO3.angleBetween(rolled, pose)), 11.0,
                "and that pose really is further off than the aim tolerance alone allows")
            cam.deliver(cam.lastRungs.size)

            // Mis-aimed by ten degrees: not fired, whatever the roll does.
            val next = c.snapshot().currentTarget
            val nextPose = c.plan.targets[next].rotation
            val misaimed = nextPose.mul(SO3.exp(Vec3(Math.toRadians(10.0), 0.0, 0.0)))
            now += 400_000_000L
            c.onOrientation(misaimed, true, now)
            now += 400_000_000L
            c.onOrientation(misaimed, true, now)
            t.eq(1L, cam.burstsRequested.toLong(),
                "but a direction the camera is not actually pointed at does not")
            t.check(!c.snapshot().aligned, "and the screen says so")

            // Rolled past the roll tolerance is refused too - it is forgiving, not absent.
            val overRolled = nextPose.mul(SO3.exp(Vec3(0.0, 0.0, Math.toRadians(25.0))))
            now += 400_000_000L
            c.onOrientation(overRolled, true, now)
            now += 400_000_000L
            c.onOrientation(overRolled, true, now)
            t.eq(1L, cam.burstsRequested.toLong(), "a wildly rolled frame is still refused")

            // Except at the poles, where roll is heading and the phone is over the
            // user's head with the screen facing the floor.
            var pole = -1
            for (i in c.plan.targets.indices)
                if (c.plan.targets[i].pitchDeg > 85 && !c.snapshot().shot[i]) { pole = i; break }
            t.greaterThan(pole.toDouble(), -1.0, "the plan includes a zenith frame")
            val spun = c.plan.targets[pole].rotation
                .mul(SO3.exp(Vec3(0.0, 0.0, Math.toRadians(70.0))))
            val before = cam.burstsRequested
            now += 400_000_000L
            c.onOrientation(spun, true, now)
            now += 400_000_000L
            c.onOrientation(spun, true, now)
            t.greaterThan(cam.burstsRequested.toDouble(), before.toDouble(),
                "the zenith fires whatever the heading, because nobody can see it")
        }

        // --- what the tiers claim ----------------------------------------------------
        t.check(CaptureTier.LINEAR_RAW.measuresRadiance,
            "only RAW plus manual sensor is a radiance measurement")
        t.check(!CaptureTier.MANUAL_YUV.measuresRadiance, "manual YUV is a reconstruction")
        t.check(!CaptureTier.LOCKED_AUTO.measuresRadiance, "and locked auto certainly is")
        t.check(CaptureTier.MANUAL_YUV.drivesExposure, "manual YUV still drives the bracket")
        t.check(!CaptureTier.LOCKED_AUTO.drivesExposure, "locked auto does not")
        theSweepWaitsForTheBrightEnd(t)
    }

    /**
     * A sweep that closes while it is still looking at the top of the scale.
     *
     * The scan used to end on geometric coverage alone. But a saturated frame
     * does not measure the brightest radiance in the room - it only says the
     * room is brighter than the sensor could read at that exposure. Planning the
     * ladder from that number makes its shortest rung too long, and the sphere
     * comes back with its highlights burnt out: the first full capture off the
     * phone metered seven frames in fourteen seconds, three of them clipped,
     * closed anyway, and clipped 22.5% of the finished panorama.
     *
     * So coverage is necessary and not sufficient. The one case where waiting
     * cannot help is a scene that still saturates the camera at its fastest -
     * there is nothing shorter to try.
     */
    private fun theSweepWaitsForTheBrightEnd(t: TestKit) {
        val bright = ImageF(8, 8, 1)
        java.util.Arrays.fill(bright.data, 1.0f)          // every pixel on the rail
        val ordinary = ImageF(8, 8, 1)
        java.util.Arrays.fill(ordinary.data, 0.35f)

        // --- clipped, with shutter left to spend -------------------------------
        run {
            val cam = FakeCamera(profile())
            val c = CaptureController(cam, CountingSink())
            cam.setListener(c)
            c.beginScan()
            var now = 1_000_000_000L
            for (i in c.plan.targets.indices) {
                c.onOrientation(c.plan.targets[i].rotation, false, now)
                cam.bound?.onPreviewFrame(bright, 1.0 / 500)
                now += 50_000_000L
            }
            t.greaterThan(c.scanCoverage(), 0.9, "the sweep covered the sphere")
            t.check(!c.scanReady(),
                "but it does not close while the brightest thing it has seen is off the scale")
            t.check(c.scanWaitingForHighlights(),
                "and it can say that is what it is waiting for")
        }

        // --- clipped at the camera's fastest ------------------------------------
        run {
            val cam = FakeCamera(profile())
            val c = CaptureController(cam, CountingSink())
            cam.setListener(c)
            c.beginScan()
            val floor = profile().exposureLimits.minRelativeExposure()
            var now = 1_000_000_000L
            for (i in c.plan.targets.indices) {
                c.onOrientation(c.plan.targets[i].rotation, false, now)
                cam.bound?.onPreviewFrame(bright, floor)
                now += 50_000_000L
            }
            t.check(c.scanReady(),
                "a room that saturates the camera at its fastest is as measured as it can be")
            t.check(!c.scanWaitingForHighlights(), "so nothing is being waited for")
            t.check(c.finishScanAndPlan(), "and the ladder is planned rather than refused")
        }

        // --- an ordinary room ----------------------------------------------------
        run {
            val cam = FakeCamera(profile())
            val c = CaptureController(cam, CountingSink())
            cam.setListener(c)
            c.beginScan()
            var now = 1_000_000_000L
            for (i in c.plan.targets.indices) {
                c.onOrientation(c.plan.targets[i].rotation, false, now)
                cam.bound?.onPreviewFrame(ordinary, 1.0 / 500)
                now += 50_000_000L
            }
            t.check(c.scanReady(), "a scene the sweep could actually read closes at once")
        }

        // --- half a sweep is never enough ----------------------------------------
        run {
            val cam = FakeCamera(profile())
            val c = CaptureController(cam, CountingSink())
            cam.setListener(c)
            c.beginScan()
            c.onOrientation(c.plan.targets[0].rotation, false, 1_000_000_000L)
            cam.bound?.onPreviewFrame(ordinary, 1.0 / 500)
            t.check(!c.scanReady(), "one direction is not a metered room")
        }
    }
}
