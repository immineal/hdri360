package com.immineal.hdri360.core.capture

import com.immineal.hdri360.core.hdr.BracketConfig
import com.immineal.hdri360.core.hdr.BracketPlan
import com.immineal.hdri360.core.hdr.BracketPlanner
import com.immineal.hdri360.core.hdr.MeterConfig
import com.immineal.hdri360.core.hdr.SceneMeter
import com.immineal.hdri360.core.hdr.SceneStats
import com.immineal.hdri360.core.image.ImageF
import com.immineal.hdri360.core.math.Mat3
import com.immineal.hdri360.core.pano.CaptureGuide
import com.immineal.hdri360.core.pano.CapturePlan
import com.immineal.hdri360.core.pano.CapturePlanConfig
import com.immineal.hdri360.core.pano.CaptureTarget

/**
 * Drives a full-sphere capture: meter the scene, plan one exposure ladder for
 * the whole sphere, then guide the user through the directions and fire each
 * bracket when the phone is pointed and still.
 *
 * Deliberately free of any platform type. The camera arrives as a
 * [CameraSource], storage as a [FrameSink], and time as an argument, which is
 * what lets the whole thing - including a camera that disconnects mid-burst, a
 * burst that never completes, and a process killed at frame 140 - be exercised
 * on a bare JVM against a recorded session. A capture path that has only ever
 * been tested by pointing a phone at a room is a capture path whose failure
 * modes have never been tested at all.
 *
 * ## What this fixes
 *
 * The predecessor had several contract defects that only appear under load:
 *
 *  - It marked a direction as shot when the burst's *metadata* completed, which
 *    can arrive before the pixels it describes; late frames were then dropped
 *    while the direction counted as done. Completion is now on frames received.
 *  - A failed burst never completed at all, leaving the controller waiting
 *    forever with no timeout and no retry.
 *  - It handed the UI its live mutable shot array, written from the camera
 *    thread while being read for drawing.
 *  - Stability was a single sub-threshold gyro sample, so a phone swinging
 *    through zero angular rate read as still.
 *  - Its metering, bracket and plan settings were private and hardcoded.
 */
class CaptureController(
    private val source: CameraSource,
    private val sink: FrameSink,
    private val config: Config = Config()
) : CameraSource.Listener {

    class Config {
        @JvmField var plan = CapturePlanConfig()
        @JvmField var meter = MeterConfig()
        @JvmField var bracket = BracketConfig()
        /** How close the camera's axis must be to a target's direction before firing. */
        /**
         * How much of the sphere the sweep must have pointed at before its
         * measurements are taken to describe the room. Below this the ladder is
         * being planned from a corner of it.
         */
        @JvmField var scanCoverageEnough = 0.35
        /**
         * Coverage at which the sweep ends whatever else it has or has not
         * learned. Hunting the last few percent of a sphere with no idea which
         * way is left is a poor use of somebody's time, and the directions still
         * missing at this point are worth little: the ladder is set from the
         * whole scene, not from any one direction.
         */
        @JvmField var scanCoverageComplete = 0.85
        @JvmField var alignmentToleranceDeg = 7.0
        /**
         * How far the phone may be rolled about that axis and still fire.
         *
         * Wider than the aim tolerance on purpose: rolling a frame turns its
         * footprint about its own centre, which at the plan's overlap costs
         * nothing, while mis-aiming it moves the footprint off the part of the
         * sphere the plan assigned it. Judging both by one number is what makes a
         * sphere unshootable by hand.
         */
        @JvmField var rollToleranceDeg = 15.0
        /**
         * Above this pitch, roll is not judged at all.
         *
         * Pointing straight up, roll *is* heading - and the zenith is shot with
         * the screen facing the floor, where the person holding the phone has
         * nothing to aim by. Asking them to find a heading they cannot see, at
         * the one direction where the surrounding ring already overlaps
         * everything, is how the top of the sphere ends up missing.
         */
        @JvmField var freeRollAbovePitchDeg = 75.0
        /** Shutter lockout, so one steady moment does not fire twice. */
        @JvmField var minBracketIntervalNs = 250_000_000L
        /**
         * How long the device must stay still before a bracket fires.
         *
         * A single sub-threshold sample is not stillness: a phone swept past a
         * target passes through zero angular rate on the way. Requiring the
         * condition to hold is the difference between a sharp bracket and a
         * smeared one.
         */
        @JvmField var stabilityDwellNs = 150_000_000L
        /**
         * A burst that has not delivered its frames by now is presumed lost.
         *
         * Generous on purpose. A five rung burst of twelve megapixel RAW is a
         * hundred and twenty megabytes through one thread, and the DNG the user
         * asked to keep is written from the same image before it is released.
         * A burst that is merely slow must not be thrown away and shot again,
         * because shooting it again costs more than waiting for it.
         */
        @JvmField var burstTimeoutNs = 12_000_000_000L
        /** Give up on a direction after this many failed bursts and move on. */
        @JvmField var maxBurstAttempts = 3
        /** Exposure the scan starts at, before metering has anything to say. */
        @JvmField var initialScanExposure = 1.0 / 120.0
        /**
         * Where the preview puts the scene's median once the scan is over.
         *
         * The metering exposure is not a viewing exposure. Metering wants the
         * brightest tenth of a percent just under saturation so the top of the
         * range can be measured, which in an ordinary room leaves everything else
         * black - and a user cannot aim a sphere at a black screen. So once the
         * ladder is fixed the preview is re-exposed for the eye, while the
         * brackets go on being shot at the ladder's own exposures.
         */
        @JvmField var previewMedianTarget = 0.18
    }

    enum class State { IDLE, SCANNING, CAPTURING, FINISHED, FAILED }

    /**
     * An immutable view for whoever is drawing. Handed out by value precisely so
     * that no caller ever holds a reference to state the camera thread mutates.
     */
    class Snapshot(
        @JvmField val state: State,
        /** Directions that were actually captured. */
        @JvmField val shot: BooleanArray,
        /**
         * Directions given up on after repeated failures.
         *
         * Kept apart from [shot] because they are not the same thing and one flag
         * cannot mean both: conflating them makes the app report a full sphere it
         * does not have, and makes a resumed capture skip the one direction with
         * no frames in it.
         */
        @JvmField val abandoned: BooleanArray,
        @JvmField val currentTarget: Int,
        @JvmField val yawOffsetDeg: Double,
        @JvmField val pitchOffsetDeg: Double,
        @JvmField val aligned: Boolean,
        @JvmField val steady: Boolean,
        @JvmField val framesTaken: Int,
        @JvmField val framesPlanned: Int,
        @JvmField val scanCoverage: Double,
        /**
         * Directions the sweep has actually metered.
         *
         * Shown, because a coverage percentage tells someone how far they have
         * got and nothing at all about which way to turn next - so the last part
         * of every sweep was spent guessing.
         */
        @JvmField val metered: BooleanArray,
        @JvmField val scene: SceneStats?,
        @JvmField val message: String?
    ) {
        val directionsShot: Int get() = shot.count { it }
        val progress: Double
            get() = if (framesPlanned > 0) framesTaken / framesPlanned.toDouble()
                    else CaptureGuide.progress(shot)
    }

    fun interface Observer { fun onChanged(snapshot: Snapshot) }

    /**
     * Where to point, in poses the phone can actually be held in.
     *
     * The roll comes from the camera's own SENSOR_ORIENTATION: undoing it is what
     * makes "upright" mean upright to the person holding the phone rather than to
     * the sensor inside it.
     */
    @JvmField val plan: CapturePlan = CapturePlan.forCamera(
        source.profile.intrinsics, config.plan, source.profile.sensorOrientationDeg.toDouble())
    private val targetCount = plan.targets.size

    private val lock = Any()
    private var state = State.IDLE
    private val shot = BooleanArray(targetCount)
    private val abandoned = BooleanArray(targetCount)
    /** Directions the guide should stop offering: captured, or given up on. */
    private val settled = BooleanArray(targetCount)
    private val perTarget = arrayOfNulls<SceneStats>(targetCount)
    /**
     * The shortest exposure at which the sweep has still seen the top of the
     * scale, or +inf if it has never been clipped. A clipped reading bounds the
     * scene's brightest radiance from below and no further, so a ladder built on
     * it is short at the top by an unknown amount.
     */
    private var shortestClippedRelative = Double.POSITIVE_INFINITY
    private val attempts = IntArray(targetCount)
    private var bracketPlan: BracketPlan? = null
    private var framesTaken = 0
    private var framesPlanned = 0

    private var pose: Mat3? = null
    private var currentTarget = -1
    private var yawOffset = 0.0
    private var pitchOffset = 0.0
    private var aligned = false
    private var steady = false
    private var steadySinceNs = Long.MIN_VALUE
    private var message: String? = null

    private var pendingTarget = -1
    private var pendingBurst = 0L
    private var nextBurstId = 1L
    private var pendingRungs = 0
    private var pendingReceived = 0
    private var burstStartedNs = 0L
    private var lastBracketNs = Long.MIN_VALUE

    private var previewExposure = source.profile.exposureLimits.realize(config.initialScanExposure)
    /**
     * What metering frames are taken at, which is not what the viewfinder is
     * shown at. See CameraSource.setMeteringExposure.
     */
    private var meteringExposure = source.profile.exposureLimits.realize(config.initialScanExposure)
    private var observer: Observer? = null

    fun setObserver(o: Observer?) {
        synchronized(lock) { observer = o }
        publish()
    }

    fun snapshot(): Snapshot = synchronized(lock) { buildSnapshot() }

    /**
     * Restores a capture that was interrupted.
     *
     * The frames already on disk are the ones not to shoot again; the ladder has
     * to come back with them, because a resumed capture that re-plans would put
     * its second half on a different radiance scale from its first.
     */
    fun resume(alreadyShot: BooleanArray, plannedBrackets: BracketPlan) {
        synchronized(lock) {
            if (alreadyShot.size != targetCount)
                throw IllegalArgumentException("resuming a capture of a different shape")
            System.arraycopy(alreadyShot, 0, shot, 0, targetCount)
            System.arraycopy(alreadyShot, 0, settled, 0, targetCount)
            java.util.Arrays.fill(abandoned, false)
            bracketPlan = plannedBrackets
            framesPlanned = plannedBrackets.totalShots()
            framesTaken = 0
            for (i in 0 until targetCount)
                if (shot[i]) framesTaken += plannedBrackets.indicesPerTarget[i].size
            state = if (settled.all { it }) State.FINISHED else State.CAPTURING
            message = "resumed with ${shot.count { it }} of $targetCount directions already shot"
        }
        source.setPreviewMeteringEnabled(false)
        source.startPreview(previewExposure)
        publish()
    }

    /** Begins the metering sweep. One ladder for the sphere means metering it first. */
    fun beginScan() {
        synchronized(lock) {
            if (state != State.IDLE) return
            state = State.SCANNING
            message = "sweep the scene so it can be metered"
        }
        source.setPreviewMeteringEnabled(true)
        source.startPreview(previewExposure)
        source.setMeteringExposure(meteringExposure)
        publish()
    }

    /** Fraction of directions that have been metered at least once. */
    fun scanCoverage(): Double = synchronized(lock) { meteredFraction() }

    /**
     * Whether the sweep has learned enough to commit to one ladder.
     *
     * Coverage alone is not enough, and closing on it is what produced a sphere
     * with a fifth of its pixels clipped. A frame that saturates only says the
     * scene is brighter than the sensor could read at that exposure - so while
     * the brightest thing the sweep has seen is still on the rail, the top of
     * the ladder is being planned from a number that is known to be too low.
     *
     * The one case where waiting cannot help is a scene that still saturates the
     * camera at its fastest: there is nothing shorter to try, and the honest
     * response is to plan what can be planned and say the top is clipped.
     */
    fun scanReady(): Boolean = synchronized(lock) {
        val covered = meteredFraction()
        if (covered >= config.scanCoverageComplete) return true
        if (covered < config.scanCoverageEnough) return false
        val measured = perTarget.filterNotNull()
        if (measured.isEmpty()) return false
        if (!SceneStats.union(measured).highlightsClipped) return true
        val floor = source.profile.exposureLimits.minRelativeExposure()
        return shortestClippedRelative <= floor * 1.05
    }

    /** True while the sweep is holding on for an unclipped look at the bright end. */
    fun scanWaitingForHighlights(): Boolean = synchronized(lock) {
        val covered = meteredFraction()
        if (covered < config.scanCoverageEnough || covered >= config.scanCoverageComplete)
            return false
        val measured = perTarget.filterNotNull()
        if (measured.isEmpty()) return false
        val floor = source.profile.exposureLimits.minRelativeExposure()
        return SceneStats.union(measured).highlightsClipped &&
               shortestClippedRelative > floor * 1.05
    }

    /**
     * The ladder this capture committed to, once the scan has closed.
     *
     * Needed by whoever writes the frames down: a capture that is interrupted has
     * to come back on the same ladder, so the ladder has to be stored with it.
     */
    fun bracketPlan(): BracketPlan? = synchronized(lock) { bracketPlan }

    /** What the preview is currently being shown at, which is not what is being shot. */
    fun previewExposure(): com.immineal.hdri360.core.hdr.ExposureSettings =
        synchronized(lock) { previewExposure }

    private fun meteredFraction(): Double {
        var n = 0
        for (s in perTarget) if (s != null) n++
        return n / targetCount.toDouble()
    }

    /**
     * Closes the scan and builds the ladder.
     *
     * Directions never metered inherit the union of everything that was, so a
     * corner of the room nobody swept past is bracketed for the whole scene's
     * range rather than not at all.
     */
    fun finishScanAndPlan(): Boolean {
        val ladder: BracketPlan
        synchronized(lock) {
            if (state != State.SCANNING) return false
            val measured = perTarget.filterNotNull()
            if (measured.isEmpty()) {
                message = "nothing was metered - sweep the scene first"
                publishLocked()
                return false
            }
            val union = SceneStats.union(measured)
            val filled = ArrayList<SceneStats>(targetCount)
            for (i in 0 until targetCount) filled.add(perTarget[i] ?: union)
            ladder = BracketPlanner.plan(filled, source.profile.exposureLimits, config.bracket)
            bracketPlan = ladder
            framesPlanned = ladder.totalShots()
            framesTaken = 0
            state = State.CAPTURING
            message = String.format(java.util.Locale.US,
                "%.0f EV of scene: %d frames over %d directions",
                union.dynamicRangeEv(), ladder.totalShots(), targetCount)
            val want = SceneMeter.viewingRelativeExposure(union, config.previewMedianTarget)
            if (want.isFinite() && want > 0) {
                // Never slower than a hand can hold. A preview that updates once
                // every sixteen seconds is not a preview, and that is exactly where
                // an unbounded request lands when the scene's dark end is at zero.
                val lim = source.profile.exposureLimits
                val ceiling = lim.maxHandheldTimeSec * lim.maxIso / lim.baseIso.toDouble()
                previewExposure = lim.realize(Math.min(want, ceiling))
            }
        }
        source.setPreviewMeteringEnabled(false)
        source.startPreview(synchronized(lock) { previewExposure })
        publish()
        return true
    }

    /** Ends the capture where it stands, keeping whatever was shot. */
    fun finish() {
        synchronized(lock) {
            if (state == State.FINISHED) return
            state = State.FINISHED
            message = finishedMessageLocked()
        }
        publish()
    }

    // ---- CameraSource.Listener -------------------------------------------------

    override fun onPreviewFrame(luma: ImageF, relativeExposure: Double) {
        var next: com.immineal.hdri360.core.hdr.ExposureSettings? = null
        var show: com.immineal.hdri360.core.hdr.ExposureSettings? = null
        synchronized(lock) {
            if (state != State.SCANNING) return
            val p = pose ?: return
            val stats = SceneMeter.measure(luma, relativeExposure, config.meter)

            // Only credit a direction the sweep genuinely pointed at.
            val target = nearestTargetWithin(p, METER_CONE_RAD)
            if (target >= 0) {
                val prior = perTarget[target]
                perTarget[target] =
                    if (prior == null) stats else SceneStats.union(listOf(prior, stats))
            }
            if (stats.highlightsClipped)
                shortestClippedRelative = Math.min(shortestClippedRelative, relativeExposure)

            if (!SceneMeter.isWellExposed(stats, config.meter)) {
                val want = SceneMeter.suggestRelativeExposure(stats, relativeExposure, config.meter)
                val realised = source.profile.exposureLimits.realize(want)
                if (realised.iso != meteringExposure.iso ||
                    realised.exposureTimeSec != meteringExposure.exposureTimeSec) {
                    meteringExposure = realised
                    next = realised
                }
            }

            // And separately, something to look at. The sweep is when a person
            // most needs to see where they are pointing, and the exposure that
            // reads the top of the range is not one they can see anything in.
            //
            // Keyed to everything measured so far rather than to the frame in
            // hand. A single frame is whatever the phone happens to be pointing
            // at - a window, then a wall, then the floor - so keying to it made
            // the picture swing between blown and black on every metering tick,
            // which is worse to sweep by than a preview that is merely wrong.
            val all = perTarget.filterNotNull()
            val whole = if (all.isEmpty()) stats else SceneStats.union(all)
            val view = SceneMeter.viewingRelativeExposure(whole, config.previewMedianTarget)
            if (view.isFinite() && view > 0) {
                val lim = source.profile.exposureLimits
                val ceiling = lim.maxHandheldTimeSec * lim.maxIso / lim.baseIso.toDouble()
                val realised = lim.realize(Math.min(view, ceiling))
                if (realised.iso != previewExposure.iso ||
                    realised.exposureTimeSec != previewExposure.exposureTimeSec) {
                    previewExposure = realised
                    show = realised
                }
            }
        }
        next?.let { source.setMeteringExposure(it) }
        show?.let { source.startPreview(it) }
        publish()
    }

    /**
     * A new device pose. This is also the clock: it arrives continuously, so it
     * is where alignment, stillness and the burst timeout are all evaluated.
     */
    fun onOrientation(cameraToWorld: Mat3, stableNow: Boolean, nowNs: Long) {
        var fire: Pair<Int, List<com.immineal.hdri360.core.hdr.ExposureSettings>>? = null
        synchronized(lock) {
            pose = cameraToWorld

            // Stillness has to persist, not merely occur.
            if (stableNow) {
                if (steadySinceNs == Long.MIN_VALUE) steadySinceNs = nowNs
            } else {
                steadySinceNs = Long.MIN_VALUE
            }
            steady = steadySinceNs != Long.MIN_VALUE &&
                     nowNs - steadySinceNs >= config.stabilityDwellNs

            expireBurstLocked(nowNs)

            if (state != State.CAPTURING) return@synchronized
            val ladder = bracketPlan ?: return@synchronized

            val target = CaptureGuide.nearestPendingTarget(plan.targets, settled, cameraToWorld)
            currentTarget = target
            if (target < 0) {
                state = State.FINISHED
                message = finishedMessageLocked()
                return@synchronized
            }
            val t = plan.targets[target]
            val offset = CaptureGuide.guidanceOffsetDeg(cameraToWorld, t)
            yawOffset = offset[0]
            pitchOffset = offset[1]
            val rollTolerance = if (Math.abs(t.pitchDeg) >= config.freeRollAbovePitchDeg) 180.0
                                else config.rollToleranceDeg
            aligned = CaptureGuide.withinTolerance(cameraToWorld, t,
                Math.toRadians(config.alignmentToleranceDeg),
                Math.toRadians(rollTolerance))

            if (pendingTarget >= 0) return@synchronized
            if (!aligned || !steady) return@synchronized
            if (lastBracketNs != Long.MIN_VALUE &&
                nowNs - lastBracketNs < config.minBracketIntervalNs) return@synchronized

            val rungs = ladder.indicesPerTarget[target]
            val settings = ArrayList<com.immineal.hdri360.core.hdr.ExposureSettings>(rungs.size)
            for (k in rungs) settings.add(ladder.ladder.steps[k])
            pendingTarget = target
            pendingBurst = nextBurstId++
            pendingRungs = settings.size
            pendingReceived = 0
            burstStartedNs = nowNs
            fire = Pair(target, settings)
        }
        fire?.let { (target, settings) ->
            val id = synchronized(lock) { pendingBurst }
            if (!source.captureBracket(id, target, settings)) {
                synchronized(lock) { abandonBurstLocked("the camera refused the burst") }
            } else {
                synchronized(lock) { lastBracketNs = burstStartedNs }
            }
        }
        publish()
    }

    override fun onFrameCaptured(frame: CapturedFrame, pixels: ImageF) {
        var stored = false
        synchronized(lock) {
            // Matched on the burst, not the direction: a retry of the same
            // direction is a different burst, and a straggler from the abandoned
            // one must not be counted toward it.
            if (frame.burstId != pendingBurst) return
        }
        stored = try {
            sink.store(frame, pixels)
        } catch (e: Exception) {
            false
        }
        synchronized(lock) {
            if (frame.burstId != pendingBurst) return
            if (stored) {
                pendingReceived++
                framesTaken++
            } else {
                message = "could not write a frame to storage"
            }
        }
        publish()
    }

    override fun onBurstFinished(burstId: Long, targetIndex: Int, requested: Int, received: Int) {
        synchronized(lock) {
            if (burstId != pendingBurst) return
            // A direction counts as shot only when its frames actually landed. The
            // predecessor marked it done on the burst's metadata, so a short burst
            // left a hole that nothing later would fill.
            if (pendingReceived >= requested && requested > 0) {
                settleLocked(targetIndex, true)
            } else {
                attempts[targetIndex]++
                message = if (attempts[targetIndex] >= config.maxBurstAttempts)
                    "direction ${targetIndex + 1} kept failing; moving on"
                else "direction ${targetIndex + 1} came back short; retrying"
                if (attempts[targetIndex] >= config.maxBurstAttempts)
                    settleLocked(targetIndex, false)
            }
            pendingTarget = -1
            pendingBurst = 0L
            pendingRungs = 0
            pendingReceived = 0
            if (settled.all { it }) {
                state = State.FINISHED
                message = finishedMessageLocked()
            }
        }
        publish()
    }

    override fun onCameraError(message: String, fatal: Boolean) {
        synchronized(lock) {
            this.message = message
            if (pendingTarget >= 0) abandonBurstLocked(message)
            if (fatal) state = State.FAILED
        }
        publish()
    }

    // ---- internals -------------------------------------------------------------

    /**
     * Gives up on a burst that will not finish.
     *
     * Without this the controller waits forever: the predecessor cleared its
     * pending target only on completion, and a failed burst never completed, so
     * one dropped frame wedged the capture with no way out but restarting it.
     */
    private fun expireBurstLocked(nowNs: Long) {
        if (pendingTarget < 0) return
        if (nowNs - burstStartedNs < config.burstTimeoutNs) return
        val t = pendingTarget
        if (pendingReceived >= pendingRungs && pendingRungs > 0) {
            settleLocked(t, true)
        } else {
            attempts[t]++
            if (attempts[t] >= config.maxBurstAttempts) {
                settleLocked(t, false)
                message = "direction ${t + 1} timed out repeatedly; moving on"
            } else {
                message = "direction ${t + 1} timed out; retrying"
            }
        }
        pendingTarget = -1
        pendingBurst = 0L
        pendingRungs = 0
        pendingReceived = 0
    }

    private fun abandonBurstLocked(why: String) {
        if (pendingTarget < 0) return
        attempts[pendingTarget]++
        if (attempts[pendingTarget] >= config.maxBurstAttempts)
            settleLocked(pendingTarget, false)
        message = why
        pendingTarget = -1
        pendingBurst = 0L
        pendingRungs = 0
        pendingReceived = 0
    }

    /**
     * Records the fate of a direction, once and in one place.
     *
     * Captured and given-up-on are different states and one flag cannot hold
     * both: writing shot[t] on the way out of a failure makes the app report a
     * sphere it does not have, and makes a resumed capture skip the one direction
     * with no frames in it. Not writing settled[t] is the opposite failure - the
     * guide keeps offering a direction nothing will ever complete, and the
     * capture never reaches its end at all. Both were live defects; this is the
     * single path that cannot express either.
     */
    private fun settleLocked(t: Int, captured: Boolean) {
        shot[t] = captured
        abandoned[t] = !captured
        settled[t] = true
        if (captured) attempts[t] = 0
    }

    /** Nearest target within a cone, so a sweep only meters what it pointed at. */
    private fun nearestTargetWithin(p: Mat3, coneRad: Double): Int {
        val forward = p.mul(com.immineal.hdri360.core.math.Vec3(0.0, 0.0, 1.0))
        var best = -1
        var bestAngle = coneRad
        for (i in 0 until targetCount) {
            val a = forward.angleTo(plan.targets[i].direction)
            if (a < bestAngle) { bestAngle = a; best = i }
        }
        return best
    }

    /** Says what was actually captured, and admits to anything that was not. */
    private fun finishedMessageLocked(): String {
        val got = shot.count { it }
        val lost = abandoned.count { it }
        val never = targetCount - got - lost
        // "All" has to mean all. A capture stopped by hand after five directions
        // has nothing marked as lost, so judging by failures alone reported it as
        // a complete sphere - in the log as well as on screen, which is the one
        // record of what a capture actually did.
        return when {
            got == targetCount -> "captured all $targetCount directions"
            lost == 0 -> "stopped after $got of $targetCount directions"
            never == 0 -> "captured $got of $targetCount directions; $lost could not be shot"
            else -> "captured $got of $targetCount directions; $lost could not be shot, " +
                    "$never never reached"
        }
    }

    private fun buildSnapshot(): Snapshot {
        val measured = perTarget.filterNotNull()
        return Snapshot(state, shot.copyOf(), abandoned.copyOf(), currentTarget, yawOffset, pitchOffset,
            aligned, steady, framesTaken, framesPlanned, meteredFraction(),
            BooleanArray(targetCount) { perTarget[it] != null },
            if (measured.isEmpty()) null else SceneStats.union(measured), message)
    }

    private fun publish() {
        val o: Observer?
        val snap: Snapshot
        synchronized(lock) { o = observer; snap = buildSnapshot() }
        o?.onChanged(snap)
    }

    private fun publishLocked() {
        val snap = buildSnapshot()
        observer?.onChanged(snap)
    }

    private companion object {
        /**
         * A metering sample is only credited to a direction it genuinely points at.
         * Wider and a sweep smears one bright reading across half the sphere.
         */
        val METER_CONE_RAD = Math.toRadians(25.0)
    }
}

/** Where captured frames go. Implemented by the app's on-disk store. */
interface FrameSink {
    /**
     * Records one frame.
     *
     * @return true only when it is safely stored. A false here means the
     *   direction has not really been captured, and the controller will retry it
     *   rather than leave a hole.
     */
    fun store(frame: CapturedFrame, pixels: ImageF): Boolean
}

/** A sink that keeps nothing, for metering-only runs and for tests. */
object DiscardingFrameSink : FrameSink {
    override fun store(frame: CapturedFrame, pixels: ImageF): Boolean = true
}
