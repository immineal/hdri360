package com.immineal.hdri360.test.suites

import com.immineal.hdri360.core.camera.Intrinsics
import com.immineal.hdri360.core.capture.CameraProfile
import com.immineal.hdri360.core.capture.CameraSource
import com.immineal.hdri360.core.capture.CaptureController
import com.immineal.hdri360.core.capture.CaptureTier
import com.immineal.hdri360.core.capture.CapturedFrame
import com.immineal.hdri360.core.capture.FrameSink
import com.immineal.hdri360.core.hdr.BracketPlan
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
        var lastMetering: ExposureSettings? = null
        override fun setMeteringExposure(settings: ExposureSettings) { lastMetering = settings }
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

        /**
         * What a delivered frame contains, chosen by the test from the direction
         * and the exposure it was shot at. A room that burns out at one direction
         * and not at another is the whole of decision 1.
         */
        var pixelsFor: (Int, ExposureSettings) -> ImageF = { _, _ -> ImageF(4, 4, 1) }

        /** Delivers [frames] of the last requested burst, then completes it. */
        fun deliver(frames: Int, complete: Boolean = true) {
            val l = bound ?: return
            for (i in 0 until frames) {
                l.onFrameCaptured(CapturedFrame(lastBurst, lastTarget, i, lastRungs[i],
                    Mat3.IDENTITY, 1000L + i, true), pixelsFor(lastTarget, lastRungs[i]))
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
        /** Plans the controller revised mid-capture; a store has to be told of each. */
        var plans = 0
        var lastPlan: BracketPlan? = null
        override fun store(frame: CapturedFrame, pixels: ImageF): Boolean {
            if (refuse) return false
            stored++
            return true
        }
        override fun planChanged(plan: BracketPlan) {
            plans++
            lastPlan = plan
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

            // And it keeps saying it, in the words that are true. A disk that
            // will not take a frame is not a direction that was aimed badly, and
            // working through the sphere blaming one direction after another -
            // which is what "direction 5 kept failing; moving on" does - sends
            // somebody back out to hold the phone steadier at a problem no
            // steadiness can reach. The camera is delivering perfectly here; it
            // is the writing that fails.
            var guard = 0
            while (c.snapshot().state == CaptureController.State.CAPTURING && guard++ < 200) {
                val next = c.snapshot().currentTarget
                if (next < 0) break
                now = settleOn(c, next, now + 300_000_000L)
                if (cam.lastTarget == next) cam.deliver(cam.lastRungs.size)
            }
            val stuck = c.snapshot()
            t.check(stuck.state == CaptureController.State.FAILED,
                "a sink that will not take a frame stops the capture")
            val said = stuck.message ?: ""
            t.check(said.contains("stor") || said.contains("space") || said.contains("writ"),
                "and says the frames cannot be written: " + said)
            t.check(!said.contains("direction "),
                "rather than blaming a direction for it: " + said)
            t.eq(0L, stuck.directionsShot.toLong(), "nothing was captured")
            t.check(guard < 200, "and it stops rather than grinding through the sphere")

            // A sink that refuses a couple of writes and then works is not a
            // broken sink. A failed write is a thing that happens, the retry is
            // what this controller is for, and only a whole burst's worth in a
            // row means the disk itself has stopped taking frames.
            val cam2 = FakeCamera(profile())
            val sink2 = CountingSink()
            val c2 = CaptureController(cam2, sink2)
            cam2.setListener(c2)
            c2.beginScan()
            var now2 = scanEverything(c2, cam2, 1_000_000_000L)
            c2.finishScanAndPlan()
            sink2.refuse = true
            val first = aimAtNext(c2, now2 + 300_000_000L)
            now2 = first.second
            t.eq(first.first.toLong(), cam2.lastTarget.toLong(), "a burst was taken")
            // Two failures, one short of the burst that would condemn the disk.
            cam2.deliver(2, complete = false)
            t.check(c2.snapshot().state == CaptureController.State.CAPTURING,
                "writes failing but fewer than a whole burst do not end a capture")

            // The rest of the burst goes through, and the run of failures is
            // cleared by the first that lands rather than accumulating for ever.
            sink2.refuse = false
            cam2.deliver(cam2.lastRungs.size)
            t.check(c2.snapshot().state == CaptureController.State.CAPTURING,
                "and a sink that starts working again is not condemned for its past")
            t.check(c2.snapshot().shot[first.first], "the direction lands")
            t.greaterThan(sink2.stored.toDouble(), 0.0, "and the frames really were written")
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
        //
        // One direction goes silent in a capture that is otherwise working, which
        // is what makes this a direction's problem rather than the lens's. A
        // camera that has never delivered anything at all is a different fault
        // with a different answer, and is tested on its own further down.
        run {
            val cam = FakeCamera(profile())
            val c = CaptureController(cam, CountingSink())
            cam.setListener(c)
            c.beginScan()
            var now = scanEverything(c, cam, 1_000_000_000L)
            c.finishScanAndPlan()

            val working = aimAtNext(c, now + 300_000_000L)
            now = working.second
            if (cam.lastTarget == working.first) cam.deliver(cam.lastRungs.size)
            t.check(c.snapshot().shot[working.first],
                "the capture is under way and this camera does deliver")

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
            t.eq(1L, after.directionsShot.toLong(),
                "so the sphere holds only what actually arrived")

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
        theViewfinderIsNotTheLightMeter(t)
        theViewfinderStaysQuick(t)
        stoppingEarlyIsNotFinishing(t)
        aBurntDirectionGrowsTheLadder(t)
        progressNeverGoesBackwards(t)
        aBurstInFlightIsVisible(t)
        brighterThanTheCameraCanRead(t)
        aGrownLadderStillFinishes(t)
    }

    /** Every pixel on the rail. */
    private fun rail(): ImageF {
        val im = ImageF(8, 8, 1)
        java.util.Arrays.fill(im.data, 1.0f)
        return im
    }

    /** A frame with something in it. */
    private fun room(): ImageF {
        val im = ImageF(8, 8, 1)
        java.util.Arrays.fill(im.data, 0.35f)
        return im
    }

    /** A metered sphere with the ladder committed, ready to shoot. */
    private fun metered(cam: FakeCamera, sink: CountingSink): Pair<CaptureController, Long> {
        val c = CaptureController(cam, sink)
        cam.setListener(c)
        c.beginScan()
        val now = scanEverything(c, cam, 1_000_000_000L)
        c.finishScanAndPlan()
        return Pair(c, now)
    }

    /**
     * Decision 1: the exposure ladder grows during the capture.
     *
     * The ladder is planned from a sweep, and a sweep that saw a direction
     * saturate learned only that the direction is brighter than the sensor could
     * read at that exposure - never how much brighter. So the rung meant to hold
     * a direction's highlights is a guess until that direction has actually been
     * shot, and the measurement that settles it is the capture itself. When the
     * shortest rung comes back on the rail, a shorter one is added and that
     * direction is shot again at once, while the person is still pointing at it.
     *
     * What this replaced: a sphere off the phone with 100% coverage, 10.4 stops
     * and ten frames that could not be matched to any neighbour - the floor, the
     * ceiling and the top ring - because 82%, 78% and 52% of their green samples
     * were at the white level. Nothing in the capture noticed.
     */
    private fun aBurntDirectionGrowsTheLadder(t: TestKit) {
        val cam = FakeCamera(profile())
        val sink = CountingSink()
        val (c, t0) = metered(cam, sink)
        var now = t0

        val planned = c.bracketPlan()
        if (planned == null) { t.fail("the sweep produced no ladder"); return }
        val baseIso = profile().exposureLimits.baseIso
        val rungsBefore = planned.ladder.size()
        val darkestBefore = planned.ladder.relativeExposure(0)
        val exposuresBefore = DoubleArray(rungsBefore) { planned.ladder.relativeExposure(it) }

        // One direction with a window in it: at every exposure the sweep planned
        // it comes back entirely on the rail, and only something shorter reads.
        var burnt = -1
        val readableBelow = darkestBefore / 2.0
        cam.pixelsFor = { target, e ->
            if (target == burnt && e.relativeExposure(baseIso) > readableBelow) rail() else room()
        }

        val (first, then) = aimAtNext(c, now + 300_000_000L)
        burnt = first
        now = then
        t.greaterThan(burnt.toDouble(), -1.0, "a direction is chosen once capturing")
        val plannedRungs = planned.indicesPerTarget[burnt].size
        cam.deliver(cam.lastRungs.size)

        val grown = c.bracketPlan()
        if (grown == null) { t.fail("the plan disappeared"); return }
        t.eq((rungsBefore + 1).toLong(), grown.ladder.size().toLong(),
            "a direction that came back burnt out puts a shorter rung on the ladder")
        t.lessThan(grown.ladder.relativeExposure(0), darkestBefore,
            "and the new rung really is shorter than anything the sweep planned")
        t.check(!c.snapshot().shot[burnt],
            "the direction is not finished with while it is still burning out")
        t.check(!c.snapshot().abandoned[burnt], "nor is it given up on")
        t.eq(1L, sink.plans.toLong(),
            "the store is told, or a resumed capture comes back on the ladder that failed")

        // Every rung the ladder already had is still on it, naming the same
        // exposure. A capture whose radiance scale moved halfway through is worse
        // than one that clipped.
        for (i in 0 until rungsBefore)
            t.nearRel(exposuresBefore[i], grown.ladder.relativeExposure(i + 1), 1e-12,
                "rung $i keeps its exposure, one index further up the longer ladder")

        // Decision 1, the second half: directions already finished are left alone.
        // A direction that did not clip does not need the new rung, and the
        // rejected alternative - one insurance rung at every direction - is
        // exactly what this must not turn into.
        for (i in grown.indicesPerTarget.indices) {
            if (i == burnt) continue
            val was = planned.indicesPerTarget[i]
            val isNow = grown.indicesPerTarget[i]
            t.eq(was.size.toLong(), isNow.size.toLong(),
                "direction $i did not clip, so it does not get the new rung")
            for (k in was.indices)
                t.nearRel(planned.ladder.relativeExposure(was[k]),
                    grown.ladder.relativeExposure(isNow[k]), 1e-12,
                    "and still names exactly the exposures it was planned")
        }
        // Decision 2: nothing may clip, at the cost of an extra frame per
        // direction where needed. One frame, in the direction that needed it.
        t.eq((plannedRungs + 1).toLong(), grown.indicesPerTarget[burnt].size.toLong(),
            "the direction that burnt out costs exactly one extra frame")
        t.eq(0L, grown.indicesPerTarget[burnt][0].toLong(),
            "and reaches the new darkest rung")

        // "Immediately, while the person is still pointing at it" - not queued for
        // a second pass round the sphere, which would mean finding it again by hand.
        now = settleOn(c, burnt, now + 300_000_000L)
        t.eq(burnt.toLong(), cam.lastTarget.toLong(),
            "the same direction is shot again at once, not left for later")
        t.eq(grown.indicesPerTarget[burnt].size.toLong(), cam.lastRungs.size.toLong(),
            "with the whole bracket, because a merge aligns a direction's rungs by nothing")
        cam.deliver(cam.lastRungs.size)
        t.check(c.snapshot().shot[burnt], "and now the direction is done")
        t.check(!c.bracketPlan()!!.ladder.clampedLow,
            "nothing is recorded as out of the camera's reach, because it was not")

        // A direction shot twice is one direction's worth of frames on disk, not
        // two. With a ladder that grows, a re-shoot is ordinary rather than rare.
        t.eq(grown.indicesPerTarget[burnt].size.toLong(), c.snapshot().framesTaken.toLong(),
            "the second burst wrote over the first rather than counting on top of it")
        t.greaterThan(sink.stored.toDouble(), c.snapshot().framesTaken.toDouble(),
            "even though the sink really was handed both bursts")
    }

    /**
     * While a bracket is being taken, the snapshot says so and says how far.
     *
     * The one thing the capture screen never showed. A burst is a fifth of a
     * second on a good direction and two and a half on one that is being shot
     * again, and throughout it the person has to keep holding still with nothing
     * on screen to say why. "Hold still" as a line of text is read once; a ring
     * that fills as the frames land is read every time.
     *
     * Exposed as frames rather than as a flag because a flag can only say
     * "working", and what somebody holding a phone at arm's length wants to know
     * is how much longer.
     */
    private fun aBurstInFlightIsVisible(t: TestKit) {
        val cam = FakeCamera(profile())
        val (c, t0) = metered(cam, CountingSink())
        var now = t0

        t.eq(0L, c.snapshot().burstRungs.toLong(), "nothing is in flight before anything fires")

        val (target, then) = aimAtNext(c, now + 300_000_000L)
        now = then
        t.greaterThan(target.toDouble(), -1.0, "a direction was chosen")
        val rungs = cam.lastRungs.size
        t.eq(rungs.toLong(), c.snapshot().burstRungs.toLong(),
            "once a bracket is requested, the snapshot says how many frames it is")
        t.eq(0L, c.snapshot().burstReceived.toLong(), "and that none of them have landed")

        // Frame by frame, which is what the ring is drawn from.
        cam.deliver(1, complete = false)
        t.eq(1L, c.snapshot().burstReceived.toLong(), "the first frame is counted as it lands")
        t.eq(rungs.toLong(), c.snapshot().burstRungs.toLong(), "the burst is still the same size")
        cam.deliver(rungs - 1, complete = false)
        t.eq(rungs.toLong(), c.snapshot().burstReceived.toLong(), "and so is the rest of it")

        // The count is of frames handed over, and a camera that hands over more
        // than it was asked for is a camera that exists. The ring is drawn from
        // this, so it is bounded here rather than in the drawing.
        cam.deliver(2, complete = false)
        t.check(c.snapshot().burstReceived <= c.snapshot().burstRungs,
            "the ring never fills past the end of the burst, whatever the camera does")

        cam.bound?.onBurstFinished(cam.lastBurst, target, rungs, rungs)
        t.eq(0L, c.snapshot().burstRungs.toLong(),
            "and when the burst is over nothing is in flight again")
        t.eq(0L, c.snapshot().burstReceived.toLong(), "with nothing left half drawn")
    }

    /**
     * Progress has to be monotone, and frames are not.
     *
     * The bar was frames stored over frames planned, and decision 1 makes the
     * denominator grow: a direction that comes back burnt out adds a frame to the
     * plan, so the fraction drops the instant the app decides to do more work.
     * On screen that is indistinguishable from a stall, at the exact moment the
     * capture has started taking longer - which is when a person is most likely
     * to think it has hung and stop.
     *
     * Directions do not grow. There are as many at the end as at the start, each
     * is settled once, and settled is what the person is counting: how many more
     * times do I have to stop, aim and hold still.
     */
    private fun progressNeverGoesBackwards(t: TestKit) {
        val cam = FakeCamera(profile())
        val sink = CountingSink()
        val (c, t0) = metered(cam, sink)
        var now = t0
        val baseIso = profile().exposureLimits.baseIso
        val darkestBefore = c.bracketPlan()!!.ladder.relativeExposure(0)
        val readableBelow = darkestBefore / 2.0
        // Every other direction burns out, so the plan grows repeatedly and the
        // frame-based fraction would fall repeatedly.
        cam.pixelsFor = { target, e ->
            if (target % 2 == 0 && e.relativeExposure(baseIso) > readableBelow) rail() else room()
        }

        var worstDrop = 0.0
        var previous = c.snapshot().progress
        var grew = false
        var plannedBefore = c.snapshot().framesPlanned
        var guard = 0
        while (c.snapshot().state == CaptureController.State.CAPTURING && guard++ < 400) {
            val (target, then) = aimAtNext(c, now + 300_000_000L)
            now = then
            if (target < 0) break
            if (cam.lastTarget == target) cam.deliver(cam.lastRungs.size)
            val snap = c.snapshot()
            if (snap.framesPlanned > plannedBefore) { grew = true; plannedBefore = snap.framesPlanned }
            worstDrop = Math.max(worstDrop, previous - snap.progress)
            previous = snap.progress
        }
        t.check(grew, "the fixture really did make the plan grow")
        t.near(0.0, worstDrop, 1e-12,
            "progress never falls back, however much the plan grows under it")
        val end = c.snapshot()
        t.eq(CaptureController.State.FINISHED.toString(), end.state.toString(), "and it finishes")
        t.near(1.0, end.progress, 1e-12, "at exactly one, with every direction settled")

        // And what the overlay draws instead of a number: which directions cost
        // an extra rung. Text on a capture screen is read once and then ignored;
        // a mark on the direction it happened to is still there afterwards.
        var marked = 0
        for (i in end.extraRungs.indices) {
            t.check(end.extraRungs[i] >= 0, "a direction cannot need a negative rung")
            if (end.extraRungs[i] > 0) marked++
        }
        t.greaterThan(marked.toDouble(), 0.0,
            "the directions that burnt out are marked, one by one")
        t.eq(end.shot.size.toLong(), end.extraRungs.size.toLong(), "one entry per direction")
        for (i in end.extraRungs.indices)
            if (end.extraRungs[i] > 0)
                t.check(i % 2 == 0, "and only the ones the fixture actually blew out")
    }

    /**
     * Decision 3: where a shorter exposure is physically impossible.
     *
     * Direct sun in a window is brighter than the shortest exposure the sensor
     * has. There is nothing to add to the ladder, so the capture proceeds and
     * what is written down says the top value is a lower bound rather than a
     * measurement. What must not happen is the capture chasing a rung that does
     * not exist.
     */
    private fun brighterThanTheCameraCanRead(t: TestKit) {
        val cam = FakeCamera(profile())
        val sink = CountingSink()
        val (c, t0) = metered(cam, sink)
        var now = t0
        val rungsBefore = c.bracketPlan()!!.ladder.size()

        var burnt = -1
        cam.pixelsFor = { target, _ -> if (target == burnt) rail() else room() }

        val (first, then) = aimAtNext(c, now + 300_000_000L)
        burnt = first
        now = then

        var bursts = 0
        var guard = 0
        while (!c.snapshot().shot[burnt] && !c.snapshot().abandoned[burnt] && guard++ < 20) {
            if (cam.lastTarget != burnt) break
            cam.deliver(cam.lastRungs.size)
            bursts++
            if (c.snapshot().shot[burnt] || c.snapshot().abandoned[burnt]) break
            now = settleOn(c, burnt, now + 300_000_000L)
        }
        t.check(c.snapshot().shot[burnt],
            "a direction the camera cannot read is still captured, not chased and not dropped")
        t.lessThan(bursts.toDouble(), 5.0,
            "and the capture stops asking for a rung the camera does not have")
        val ended = c.bracketPlan()
        if (ended == null) { t.fail("the plan disappeared"); return }
        t.check(ended.ladder.clampedLow,
            "what is written down says the top of the range is a lower bound")
        t.greaterThan(ended.ladder.size().toDouble(), (rungsBefore - 1).toDouble(),
            "the ladder went as short as the camera would go")
        t.check(sink.lastPlan?.ladder?.clampedLow == true,
            "and the store was told, because the report is written from the store")
        t.eq(CaptureController.State.CAPTURING.toString(), c.snapshot().state.toString(),
            "the rest of the sphere is still there to shoot")
    }

    /**
     * A whole sphere with a ladder that grows under it still ends.
     *
     * The failure this rules out is the one that costs somebody an afternoon: a
     * direction that is offered, shot, found wanting and offered again forever,
     * with no way out but killing the app.
     */
    private fun aGrownLadderStillFinishes(t: TestKit) {
        val cam = FakeCamera(profile())
        val sink = CountingSink()
        val (c, t0) = metered(cam, sink)
        var now = t0
        val baseIso = profile().exposureLimits.baseIso
        val darkestBefore = c.bracketPlan()!!.ladder.relativeExposure(0)
        val plannedBefore = c.snapshot().framesPlanned

        // Half the sphere has a window in it; the other half is an ordinary room.
        val readableBelow = darkestBefore / 2.0
        cam.pixelsFor = { target, e ->
            if (target % 2 == 0 && e.relativeExposure(baseIso) > readableBelow) rail() else room()
        }

        var guard = 0
        while (c.snapshot().state == CaptureController.State.CAPTURING && guard++ < 400) {
            val (target, then) = aimAtNext(c, now + 300_000_000L)
            now = then
            if (target < 0) break
            if (cam.lastTarget == target) cam.deliver(cam.lastRungs.size)
        }
        val snap = c.snapshot()
        t.eq(CaptureController.State.FINISHED.toString(), snap.state.toString(),
            "a sphere whose ladder grew under it still finishes")
        t.eq(c.plan.targets.size.toLong(), snap.directionsShot.toLong(),
            "with every direction shot")
        val ended = c.bracketPlan()
        if (ended == null) { t.fail("the plan disappeared"); return }
        t.greaterThan(ended.ladder.size().toDouble(), 0.0, "on a ladder")
        t.greaterThan(snap.framesPlanned.toDouble(), plannedBefore.toDouble(),
            "which cost more frames than the sweep planned, in the directions that needed them")
        t.eq(snap.framesPlanned.toLong(), snap.framesTaken.toLong(),
            "and every frame the grown plan asks for is on disk")
        t.greaterThan(sink.plans.toDouble(), 0.0, "the store heard about the growth")
        t.note("grown ladder: " + ended.ladder.size() + " rungs, " +
                snap.framesTaken + " frames vs " + plannedBefore + " planned from the sweep")
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
            // Past "enough to describe the room" but short of the coverage at
            // which the sweep gives up and takes what it has.
            var now = 1_000_000_000L
            val half = c.plan.targets.size / 2
            for (i in 0 until half) {
                c.onOrientation(c.plan.targets[i].rotation, false, now)
                cam.bound?.onPreviewFrame(bright, 1.0 / 500)
                now += 50_000_000L
            }
            t.greaterThan(c.scanCoverage(), CaptureController.Config().scanCoverageEnough,
                "the sweep covered enough of the sphere to describe it")
            t.lessThan(c.scanCoverage(), CaptureController.Config().scanCoverageComplete,
                "and not so much that it would stop regardless")
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

        // --- swept nearly all of it, and still on the rail -------------------------
        // Holding out for an unclipped look is right up to a point. Past that
        // point the person is hunting the last few directions of a sphere with
        // nothing telling them which way is left, for a ladder that is set from
        // the whole scene anyway.
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
            t.greaterThan(c.scanCoverage(), CaptureController.Config().scanCoverageComplete,
                "the sweep reached nearly all of the sphere")
            t.check(c.scanReady(), "and ends, rather than hunting the last few directions")
            t.check(!c.scanWaitingForHighlights(), "with nothing left to wait for")
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

    /**
     * A viewfinder and a light meter want different exposures.
     *
     * Metering puts the brightest tenth of a percent just under saturation so the
     * top of the range can be read. Shown on screen, that is a black rectangle
     * with a window in it - and the sweep is precisely when a person most needs
     * to see where they are pointing. Driven from one number, the preview
     * appeared for the length of the camera's own auto-exposure probe and then
     * went black for the rest of the capture.
     *
     * What has to hold: while the meter walks the exposure down towards the
     * highlights, the preview stays somewhere a person can see.
     */
    private fun theViewfinderIsNotTheLightMeter(t: TestKit) {
        val cam = FakeCamera(profile())
        val c = CaptureController(cam, CountingSink())
        cam.setListener(c)
        c.beginScan()

        // A room with a window: almost all of it dim, a few pixels blazing. Metered
        // for the window, everything else is black.
        val room = ImageF(16, 16, 1)
        java.util.Arrays.fill(room.data, 0.004f)
        room.data[0] = 1.0f
        room.data[1] = 1.0f
        room.data[2] = 1.0f

        var now = 1_000_000_000L
        var meteringWalkedDown = false
        val base = profile().exposureLimits.baseIso
        var previousMetering = Double.MAX_VALUE
        for (i in c.plan.targets.indices) {
            c.onOrientation(c.plan.targets[i].rotation, false, now)
            cam.bound?.onPreviewFrame(room, 1.0 / 60)
            now += 50_000_000L
            val m = cam.lastMetering?.relativeExposure(base)
            if (m != null) {
                if (m < previousMetering) meteringWalkedDown = true
                previousMetering = m
            }
        }

        t.check(meteringWalkedDown,
            "the meter shortens its exposure to get the highlights off the rail")
        val shown = cam.lastPreview
        t.check(shown != null, "and the viewfinder is given an exposure of its own")
        val metered = cam.lastMetering
        t.check(metered != null, "as is the meter")

        // The scene's dim body sits at 0.004 of full scale at the exposure the
        // frames came in at. A viewable preview has to lift that towards the
        // middle, which means a longer exposure than the meter is using - not the
        // same one.
        val shownRel = shown!!.relativeExposure(base)
        val meteredRel = metered!!.relativeExposure(base)
        t.greaterThan(shownRel / meteredRel, 4.0,
            "the viewfinder is exposed for the room, not for the window")
    }

    /**
     * A capture stopped by hand is not a complete sphere.
     *
     * The finish message was worded from failures alone, so a sphere abandoned
     * after five of thirty-four directions - nothing failed, the rest were simply
     * never reached - reported "captured all 34 directions", on screen and in the
     * log. The log is the only record of what a capture actually did.
     */
    private fun stoppingEarlyIsNotFinishing(t: TestKit) {
        val cam = FakeCamera(profile())
        val c = CaptureController(cam, CountingSink())
        cam.setListener(c)
        c.beginScan()
        scanEverything(c, cam, 1_000_000_000L)
        t.check(c.finishScanAndPlan(), "the sweep planned a ladder")

        var now = 5_000_000_000L
        for (i in 0 until 2) {
            val step = aimAtNext(c, now)
            now = step.second
            cam.deliver(cam.lastRungs.size)
        }
        c.finish()
        val snap = c.snapshot()
        t.eq(CaptureController.State.FINISHED.toString(), snap.state.toString(),
            "finishing by hand finishes the capture")
        t.check(snap.directionsShot < snap.shot.size, "with directions left unshot")
        val said = snap.message ?: ""
        t.check(!said.contains("all"), "and it does not report a whole sphere: " + said)
        t.check(said.contains(snap.directionsShot.toString()),
            "it says how many were actually taken: " + said)

        // --- a busy camera is not a bad direction ---------------------------------
        // Off the phone, in a dim room: a burst outlasted its own twelve second
        // timeout, the controller expired it and re-fired, and the camera still
        // had the first one in hand and said no. That refusal was charged to the
        // direction as a failed attempt - and refusals arrive at whatever rate
        // the orientation sensor ticks, so three of them landed inside forty
        // milliseconds and the direction was given up for good. Every direction
        // in turn, and the capture died with two frames out of eighty-four.
        //
        // "The camera is busy" is a fact about timing, not about the direction.
        // It costs the direction nothing, and it waits the ordinary interval
        // before trying again rather than spinning at sensor rate.
        run {
            val cam = FakeCamera(profile())
            val c = CaptureController(cam, CountingSink(), CaptureController.Config())
            cam.setListener(c)
            c.beginScan()
            var now = scanEverything(c, cam, 1_000_000_000L)
            c.finishScanAndPlan()

            c.onOrientation(c.plan.targets[0].rotation, false, now)
            val target = c.snapshot().currentTarget
            val pose = c.plan.targets[target].rotation

            cam.refuseBursts = true
            // Far more refusals than maxBurstAttempts, arriving as fast as the
            // sensor speaks - which is how the phone met this.
            for (i in 0 until 40) {
                now += 20_000_000L
                c.onOrientation(pose, true, now)
            }
            t.eq(0L, cam.burstsRequested.toLong(), "a busy camera takes no bursts")
            val busy = c.snapshot()
            t.check(!busy.abandoned[target],
                "and the direction it was busy during is not given up on")
            t.check(!busy.shot[target], "nor marked shot by refusals")
            t.eq(0L, busy.directionsShot.toLong(), "nothing was shot")
            t.check(busy.state == CaptureController.State.CAPTURING,
                "and the capture is still running rather than dead")

            // The camera frees up. The very next aim fires, and the direction is
            // shot exactly as if nothing had happened.
            cam.refuseBursts = false
            now += 400_000_000L
            c.onOrientation(pose, true, now)
            t.eq(1L, cam.burstsRequested.toLong(),
                "the moment the camera is free the burst goes")
            t.eq(target.toLong(), cam.lastTarget.toLong(), "at the same direction")
            cam.deliver(cam.lastRungs.size)
            t.check(c.snapshot().shot[target],
                "and it is shot, having cost it nothing to have been refused")
        }

        // --- a camera that delivers nothing says so ------------------------------
        // The other half of the same evening. Once the refusals stopped costing
        // the direction anything, the capture no longer died in forty
        // milliseconds - it died in minutes instead, with somebody standing in a
        // room holding a phone as still as they could while the app abandoned one
        // direction after another and finally said the burst had timed out.
        //
        // Nothing about that is the person's doing and nothing about it is the
        // direction's. A lens from which not one frame has ever arrived is broken
        // for this purpose, and the only useful thing to do is stop and say which
        // it is - the app has another lens to offer.
        run {
            val cam = FakeCamera(profile())
            val c = CaptureController(cam, CountingSink(), CaptureController.Config())
            cam.setListener(c)
            c.beginScan()
            var now = scanEverything(c, cam, 1_000_000_000L)
            c.finishScanAndPlan()

            c.onOrientation(c.plan.targets[0].rotation, false, now)
            val target = c.snapshot().currentTarget
            val pose = c.plan.targets[target].rotation
            val startedAt = now

            // The camera takes every burst it is handed and never reports one.
            // This is the physical ultrawide, exactly as the phone behaved.
            // Time steps forward a tenth of a second at a time rather than
            // jumping a whole timeout, so what this measures is how long the
            // controller was actually willing to wait. The camera swallows every
            // burst it is handed and reports none of them.
            var guard = 0
            var seen = 0
            while (c.snapshot().state == CaptureController.State.CAPTURING && guard++ < 900) {
                now += 100_000_000L
                c.onOrientation(pose, true, now)
                if (cam.burstsRequested > seen) { seen = cam.burstsRequested; cam.abandon() }
            }

            val dead = c.snapshot()
            t.check(cam.burstsRequested > 0, "the bursts really were handed over")
            t.eq(0L, dead.directionsShot.toLong(), "and not one of them came back")
            t.check(dead.state == CaptureController.State.FAILED,
                "so the capture stops rather than working through the sphere in vain")
            val said = dead.message ?: ""
            t.check(said.contains("frame"),
                "and the message is about frames not arriving: " + said)
            t.check(said.contains("lens") || said.contains("camera"),
                "naming the thing that is actually wrong: " + said)
            t.check(!said.contains("direction "),
                "not blaming a direction for a camera that never delivered: " + said)
            t.check(guard < 900, "and it gives up rather than grinding on for ever")

            // Promptly means something measurable. A whole five rung bracket
            // takes 0.07 to 0.10 seconds on the phone this was written for, so
            // waiting the full twelve second burst timeout three times over -
            // thirty-six seconds of somebody standing still - to learn that a
            // lens delivers nothing is not patience, it is a hundredfold margin
            // spent on a question already answered. Until the first frame of a
            // capture has ever arrived the wait is short; after that it is the
            // full timeout, because then the camera is known to work and a slow
            // burst deserves the benefit of the doubt.
            t.lessThan((now - startedAt) / 1e9, 20.0,
                "and the whole diagnosis takes under twenty seconds of holding still")

            // It is specifically about never having received anything. A camera
            // that delivers and then stops keeps the old per-direction handling,
            // because there the sphere may still be worth finishing.
            val cam2 = FakeCamera(profile())
            val c2 = CaptureController(cam2, CountingSink(), CaptureController.Config())
            cam2.setListener(c2)
            c2.beginScan()
            var now2 = scanEverything(c2, cam2, 1_000_000_000L)
            c2.finishScanAndPlan()
            val aimed = aimAtNext(c2, now2 + 300_000_000L)
            val first = aimed.first
            now2 = aimed.second
            t.eq(first.toLong(), cam2.lastTarget.toLong(), "the first burst was taken")
            cam2.deliver(cam2.lastRungs.size)
            t.check(c2.snapshot().shot[first], "one direction landed")

            var guard2 = 0
            while (c2.snapshot().state == CaptureController.State.CAPTURING && guard2++ < 12) {
                val next = c2.snapshot().currentTarget
                if (next < 0) break
                now2 = settleOn(c2, next, now2 + 300_000_000L)
                cam2.abandon()
                now2 += CaptureController.Config().burstTimeoutNs + 1_000_000L
                c2.onOrientation(c2.plan.targets[next].rotation, true, now2)
            }
            t.check(c2.snapshot().state != CaptureController.State.FAILED,
                "a camera that has delivered before is given the benefit of the doubt")
            t.eq(1L, c2.snapshot().directionsShot.toLong(),
                "and what it did deliver is kept")
        }
    }

    /**
     * A viewfinder may be noisy. It may not be slow.
     *
     * Exposure is bought with shutter time first, because at base ISO that is the
     * cleanest signal - which is right for the frames that become the panorama and
     * wrong for the picture someone is aiming by. A dim room put the preview on
     * the same 1/15 s the captures use: fifteen frames a second, each one smeared
     * by any movement, while the person is being asked to swing the phone around a
     * room. Noise in a viewfinder costs nothing; lag and blur cost the aim.
     */
    private fun theViewfinderStaysQuick(t: TestKit) {
        val cam = FakeCamera(profile())
        val c = CaptureController(cam, CountingSink())
        cam.setListener(c)
        c.beginScan()

        val dim = ImageF(16, 16, 1)
        java.util.Arrays.fill(dim.data, 0.002f)      // a room at dusk
        var now = 1_000_000_000L
        for (i in c.plan.targets.indices) {
            c.onOrientation(c.plan.targets[i].rotation, false, now)
            cam.bound?.onPreviewFrame(dim, 1.0 / 60)
            now += 50_000_000L
        }

        val cfg = CaptureController.Config()
        val shown = cam.lastPreview
        t.check(shown != null, "the viewfinder was given an exposure")
        t.lessThan(shown!!.exposureTimeSec, cfg.previewMaxTimeSec * 1.001,
            "and it is short enough to follow a moving phone")
        t.greaterThan(shown.iso.toDouble(), profile().exposureLimits.baseIso.toDouble(),
            "the brightness it needed was bought with gain instead")

        // The frames that matter are unaffected: they are still allowed the long
        // handheld exposures, because there the noise is what costs.
        t.check(c.finishScanAndPlan(), "the ladder is planned")
        val ladder = c.bracketPlan()!!.ladder
        var longest = 0.0
        for (i in 0 until ladder.size()) longest = Math.max(longest, ladder.steps[i].exposureTimeSec)
        t.greaterThan(longest, cfg.previewMaxTimeSec,
            "the capture ladder still reaches past the viewfinder's limit")
    }
}
