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
 * The ladder is planned before the capture and is not finished there. A sweep
 * that saw a direction saturate learned only that the direction is brighter than
 * the sensor could read, so the rung meant to hold its highlights can still come
 * back on the rail; when it does, a shorter rung is added and that direction is
 * shot again at once, while the person is still pointing at it. Directions that
 * did not clip are left alone - they do not need the new rung, and giving it to
 * them would spend a frame everywhere to fix a fault in one.
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
         * footprint about its own centre, while mis-aiming it moves the footprint
         * off the part of the sphere the plan assigned it. Judging both by one
         * number is what makes a sphere unshootable by hand.
         *
         * The number itself was a guess at 15 degrees, and on a real 34 direction
         * capture it was the binding constraint. Every direction was aimed within
         * 0.8 degrees of its 7 degree budget - nine times better than needed -
         * while the roll error on the plus and minus 55 degree rings reached 9.3
         * degrees of 15. The two slowest directions of that ring took 16.6 and
         * 15.8 seconds against a 5 second median, and they were exactly the two
         * with the largest roll error.
         *
         * Roll grows with pitch for a reason that has nothing to do with a
         * steadier wrist near the horizon. Gravity pins pitch and roll against
         * itself and leaves the rotation *about* gravity - the heading - as the
         * badly determined one, and a heading error of d lands in roll as
         * d*sin(pitch): 31% of it at 18 degrees, 82% at 55. Dividing the measured
         * roll error by sin(pitch) gave the same 2 to 10 degrees at every ring,
         * which is one heading uncertainty showing up in different places.
         *
         * So 30, derived rather than picked. Two neighbours may be off in
         * opposite directions, so a per frame budget of 30 is up to 60 degrees of
         * relative roll, and at 60 the capture-plan suite measures no coverage
         * lost at all and every frame still holding four partners at a quarter
         * overlap; the feature suite has the descriptors still matching out to 90
         * degrees of relative roll. 30 is also more than three times the worst
         * roll error a hand has actually produced here.
         */
        @JvmField var rollToleranceDeg = 30.0
        /**
         * Above this pitch, roll is not judged at all.
         *
         * Pointing straight up, roll *is* heading - and the zenith is shot with
         * the screen facing the floor, where the person holding the phone has
         * nothing to aim by. Asking them to find a heading they cannot see, at
         * the one direction where the surrounding ring already overlaps
         * everything, is how the top of the sphere ends up missing.
         *
         * Kept as a step rather than folded into the tolerance above, because at
         * the poles it is not a matter of degree: the measured roll errors there
         * were 51 and 77 degrees, which is what "roll is heading" looks like when
         * heading is what nobody can see.
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
        /**
         * How long to wait for a burst before the camera has *ever* delivered a
         * frame: four seconds.
         *
         * A whole five rung bracket takes 0.07 to 0.10 seconds on the phone this
         * was measured on, timestamp to timestamp, and does not grow through a
         * capture. So four seconds is a fortyfold margin on a camera that works,
         * and three attempts at it is twelve seconds rather than thirty-six -
         * which is thirty-six seconds of somebody standing in a room holding a
         * phone still to be told a lens delivers nothing.
         *
         * Only before the first frame. Once the camera has produced anything at
         * all it is known to work, and a burst that is merely slow gets the full
         * [burstTimeoutNs] and the benefit of the doubt.
         */
        @JvmField var firstFrameTimeoutNs = 4_000_000_000L
        /** Give up on a direction after this many failed bursts and move on. */
        @JvmField var maxBurstAttempts = 3
        /**
         * How many writes may fail back to back before the capture stops.
         *
         * Three, which is a whole bracket on the shortest ladder this plans: a
         * burst delivered in full and not one frame of it on disk. Fewer would
         * end a capture over a single unlucky write; more spends the person's
         * time proving something already proved.
         */
        @JvmField var storeFailuresBeforeGivingUp = 3
        /**
         * How many times one direction may have a shorter rung added before the
         * capture takes what it can get.
         *
         * A backstop rather than a policy. The real bound is the camera's fastest
         * shutter, which every added rung moves a whole EV step closer to; this
         * exists so that a device whose stated limits do not describe it can
         * never put a capture into a loop the person cannot get out of.
         */
        @JvmField var maxDarkerRungsPerDirection = 4
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
        /**
         * Where the preview puts the scene's median, in the sensor's own linear
         * scale.
         *
         * Not 0.18. The number the eye wants at the middle is 0.18 of a
         * *display*, and the preview stream is rendered by the camera's own tone
         * curve on its way to the screen - which lifts the middle a long way.
         * Measured on the phone, aiming the linear median at 0.18 put the
         * displayed median at 211 of 255 and a fifth of the screen at full white.
         * This is that measurement, divided out.
         */
        @JvmField var previewMedianTarget = 0.035
        /**
         * Longest shutter the viewfinder may use.
         *
         * Exposure is bought with time first because at base ISO that is the
         * cleanest signal - right for the frames that become the panorama, wrong
         * for the picture somebody is aiming by. A dim room put the preview on
         * the same 1/15 s the captures use, which is fifteen frames a second,
         * each smeared by any movement, while the person is being asked to swing
         * the phone around a room. Noise in a viewfinder costs nothing. Lag and
         * blur cost the aim.
         */
        @JvmField var previewMaxTimeSec = 1.0 / 60.0
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
        /**
         * Per direction, how many shorter rungs it needed before it stopped
         * burning out.
         *
         * For the overlay to mark, rather than for a line of text to announce.
         * A capture screen's text is read once and then ignored; a mark on the
         * direction it happened to is still there when the person looks back at
         * that part of the sphere and wonders why it took two goes.
         */
        @JvmField val extraRungs: IntArray,
        /**
         * Frames in the bracket being taken right now, or 0 when none is.
         *
         * The capture screen showed nothing while a burst was in flight, and a
         * burst is a fifth of a second on a good direction and two and a half on
         * one being shot again - all of it spent holding a phone still with
         * nothing to say why. Given as a count rather than a flag because "how
         * much longer" is the question, and a flag cannot answer it.
         */
        @JvmField val burstRungs: Int,
        @JvmField val burstReceived: Int,
        @JvmField val scene: SceneStats?,
        @JvmField val message: String?
    ) {
        val directionsShot: Int get() = shot.count { it }

        /**
         * How far through the sphere, counted in directions.
         *
         * Not in frames. Decision 1 makes the frame count grow: a direction that
         * comes back burnt out adds a frame to the plan, so a fraction with
         * frames in the denominator *falls* at the moment the app decides to do
         * more work. On screen that is indistinguishable from a stall, at exactly
         * the point where the capture has started taking longer - which is when
         * somebody is most likely to decide it has hung.
         *
         * Directions do not grow. There are as many at the end as at the start,
         * each is settled once, and settled is what the person is counting: how
         * many more times must I stop, aim and hold still.
         */
        val progress: Double
            get() {
                if (shot.isEmpty()) return 1.0
                var settled = 0
                for (i in shot.indices) if (shot[i] || abandoned[i]) settled++
                return settled / shot.size.toDouble()
            }
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
    /**
     * The shortest exposure each direction has actually been shot at, and how
     * much of that frame came back on the rail.
     *
     * Measured from the frames themselves, not from the plan: what the ladder
     * asked for and what the sensor did are two different things, and whether a
     * direction burnt out is a fact about its pixels. This is what decision 1
     * turns on - a sweep can only bound a clipped reading from below, so the
     * measurement that settles the top of the range is the capture itself.
     */
    private val darkestShotRelative = DoubleArray(targetCount) { Double.POSITIVE_INFINITY }
    private val clipAtDarkestShot = DoubleArray(targetCount)
    private val darkerRungsAdded = IntArray(targetCount)
    /**
     * Frames of each direction that are on disk.
     *
     * Per direction rather than a running total, because a re-shot direction
     * writes over its own files: counting every stored frame made a direction
     * that was shot twice look like two directions' worth of progress, and with
     * a ladder that grows a re-shoot is ordinary rather than exceptional.
     */
    private val storedPerTarget = IntArray(targetCount)
    private var bracketPlan: BracketPlan? = null
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

    /**
     * The device's limits with the viewfinder's shorter shutter cap, so gain is
     * spent where the capture path would have spent time.
     */
    private val previewLimits: com.immineal.hdri360.core.hdr.DeviceExposureLimits = run {
        val l = source.profile.exposureLimits
        com.immineal.hdri360.core.hdr.DeviceExposureLimits(
            l.minExposureTimeSec, l.maxExposureTimeSec, l.minIso, l.maxIso, l.baseIso,
            l.apertureN, Math.min(config.previewMaxTimeSec, l.maxHandheldTimeSec))
    }

    private var previewExposure = previewLimits.realize(config.initialScanExposure)
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
            java.util.Arrays.fill(storedPerTarget, 0)
            for (i in 0 until targetCount)
                if (shot[i]) storedPerTarget[i] = plannedBrackets.indicesPerTarget[i].size
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
            java.util.Arrays.fill(storedPerTarget, 0)
            state = State.CAPTURING
            message = String.format(java.util.Locale.US,
                "%.0f EV of scene: %d frames over %d directions",
                union.dynamicRangeEv(), ladder.totalShots(), targetCount)
            val want = SceneMeter.viewingRelativeExposure(union, config.previewMedianTarget)
            if (want.isFinite() && want > 0) {
                // Never slower than a hand can hold. A preview that updates once
                // every sixteen seconds is not a preview, and that is exactly where
                // an unbounded request lands when the scene's dark end is at zero.
                val lim = previewLimits
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
                val lim = previewLimits
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
                synchronized(lock) { releaseBurstLocked(nowNs) }
            } else {
                synchronized(lock) { lastBracketNs = burstStartedNs }
            }
        }
        publish()
    }

    override fun onFrameCaptured(frame: CapturedFrame, pixels: ImageF) {
        val relative = frame.settings.relativeExposure(source.profile.exposureLimits.baseIso)
        val t = frame.targetIndex
        val measure: Boolean
        synchronized(lock) {
            // Counted before the burst is matched, and never reset: a straggler
            // from a burst already given up on still proves the camera delivers,
            // which is the only question this flag answers.
            aFrameHasArrived = true
            // Matched on the burst, not the direction: a retry of the same
            // direction is a different burst, and a straggler from the abandoned
            // one must not be counted toward it.
            if (frame.burstId != pendingBurst) return
            if (t < 0 || t >= targetCount) return
            // Only the shortest exposure a direction is shot at is measured. That
            // is the rung which has to hold the highlights; the longer rungs of a
            // bracket are meant to saturate, and reading them would cost a pass
            // over twelve megapixels each to learn nothing. In an ordinary burst,
            // which arrives darkest first, this is one frame in four.
            measure = relative <= darkestShotRelative[t]
        }
        val stored = try {
            sink.store(frame, pixels)
        } catch (e: Exception) {
            false
        }
        // Outside the lock. It is a counting pass over a whole frame, and nothing
        // else may wait on the camera thread for it.
        val clipped = if (measure) SceneMeter.clippedFraction(pixels, config.meter) else 0.0
        synchronized(lock) {
            if (frame.burstId != pendingBurst) return
            // Re-checked, because frames are not promised in order: a darker one
            // arriving second must not be overwritten by a brighter one.
            if (measure && relative <= darkestShotRelative[t]) {
                darkestShotRelative[t] = relative
                clipAtDarkestShot[t] = clipped
            }
            if (stored) {
                storeFailuresInARow = 0
                pendingReceived++
                // Frames on disk, which is not frames delivered: a re-shot
                // direction writes over its own files.
                if (pendingReceived > storedPerTarget[t]) storedPerTarget[t] = pendingReceived
            } else if (++storeFailuresInARow >= config.storeFailuresBeforeGivingUp) {
                // A whole burst delivered and not one frame of it written. The
                // camera is working perfectly; it is the disk that is not, and no
                // amount of standing still will change that. Without this the
                // capture worked through the entire sphere blaming one direction
                // after another - "direction 5 kept failing; moving on", thirty-two
                // times - and finished by announcing nothing had been captured.
                state = State.FAILED
                message = "Frames cannot be written to storage. Check the free " +
                    "space on this phone and try again."
                pendingTarget = -1
                pendingBurst = 0L
                pendingRungs = 0
                pendingReceived = 0
            } else {
                message = "could not write a frame to storage"
            }
        }
        publish()
    }

    override fun onBurstFinished(burstId: Long, targetIndex: Int, requested: Int, received: Int) {
        var revised: BracketPlan? = null
        synchronized(lock) {
            if (burstId != pendingBurst) return
            // A direction counts as shot only when its frames actually landed. The
            // predecessor marked it done on the burst's metadata, so a short burst
            // left a hole that nothing later would fill.
            if (pendingReceived >= requested && requested > 0) {
                val before = bracketPlan
                // Decision 1: a direction that came back burnt out is not finished
                // with. It gets a shorter rung and is shot again now, rather than
                // being discovered days later as a white hole in the sphere.
                if (!extendForClippingLocked(targetIndex)) settleLocked(targetIndex, true)
                if (bracketPlan !== before) revised = bracketPlan
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
        // Told outside the lock and before the next burst can fire, because the
        // frames about to be written are the ones this plan describes.
        revised?.let { sink.planChanged(it) }
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
    /**
     * Whether this camera has ever handed over a single frame.
     *
     * Not a statistic. It separates "this direction is hard to hold" from "this
     * lens does not work", and those need opposite responses: the first is worth
     * retrying and moving past, the second is worth stopping for at once.
     */
    private var aFrameHasArrived = false

    /**
     * Writes that failed back to back, cleared by the first that succeeds.
     *
     * One failed write is a thing that happens and the retry is what this
     * controller is for. A burst's worth in a row is a disk that is full or a
     * directory that has gone, and that is a different fault with a different
     * answer.
     */
    private var storeFailuresInARow = 0

    private fun expireBurstLocked(nowNs: Long) {
        if (pendingTarget < 0) return
        val patience = if (aFrameHasArrived) config.burstTimeoutNs
                       else Math.min(config.firstFrameTimeoutNs, config.burstTimeoutNs)
        if (nowNs - burstStartedNs < patience) return
        val t = pendingTarget
        if (pendingReceived >= pendingRungs && pendingRungs > 0) {
            settleLocked(t, true)
        } else {
            attempts[t]++
            if (attempts[t] >= config.maxBurstAttempts) {
                if (!aFrameHasArrived) {
                    // Three bursts handed over, well past a minute of somebody
                    // standing in a room holding a phone still, and not one frame
                    // back. Nothing they do differently will change that, and
                    // working through the remaining twenty directions only spends
                    // more of their evening to abandon each in turn - which is
                    // exactly what this app did on a physical ultrawide, twice.
                    // Stop, and say the one thing that is actually true.
                    state = State.FAILED
                    message = "No frames are arriving from this lens. " +
                        "Pick another one and try again."
                    pendingTarget = -1
                    pendingBurst = 0L
                    pendingRungs = 0
                    pendingReceived = 0
                    return
                }
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

    /**
     * Hands a burst back because the camera would not take it.
     *
     * A refusal means the camera is busy - almost always still finishing the
     * burst before this one - which is a fact about timing, not about the
     * direction being aimed at. So it costs the direction nothing, and it waits
     * the ordinary bracket interval before trying again.
     *
     * Both halves matter, and their absence killed a whole capture in a dim
     * room. A long burst outlasted its own timeout, the controller expired it
     * and re-fired, the camera still had the first one in hand and said no, and
     * that refusal was charged as a failed attempt. Refusals arrive at whatever
     * rate the orientation sensor ticks, so three of them landed inside forty
     * milliseconds and the direction was given up for good - then the next, and
     * the next. Twenty-one directions abandoned, two frames out of eighty-four
     * written, and the message on screen said only "the camera refused the
     * burst".
     */
    private fun releaseBurstLocked(nowNs: Long) {
        if (pendingTarget < 0) return
        message = "waiting for the camera to catch up"
        pendingTarget = -1
        pendingBurst = 0L
        pendingRungs = 0
        pendingReceived = 0
        // Not at sensor rate. Without this the retry is immediate and refuses
        // again, and the log fills faster than it can be read.
        lastBracketNs = nowNs
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
     * Adds a shorter rung when a direction came back burnt out, and leaves the
     * direction unsettled so the guide offers it again at once.
     *
     * This is decision 1. The ladder was planned from a sweep, and a sweep can
     * only bound a clipped reading from below - so the rung meant to hold a
     * direction's highlights is a guess until that direction has actually been
     * shot. The measurement that settles it is the capture. Re-shot immediately
     * because the person is still pointing at it: coming back to a direction
     * later means finding it again, and finding it again by hand is what the
     * plan exists to avoid.
     *
     * The whole bracket is fired again rather than the new rung alone. The merge
     * combines a direction's rungs pixel for pixel with no alignment of its own,
     * which is exactly what makes a burst a burst; a rung shot a second later,
     * handheld, would ghost against its own siblings in the highlights it was
     * added to save.
     *
     * @return true when the direction is to be shot again.
     */
    private fun extendForClippingLocked(t: Int): Boolean {
        val plan = bracketPlan ?: return false
        val clipped = clipAtDarkestShot[t]
        if (!SceneMeter.highlightsClipped(clipped, config.meter)) return false
        val relative = darkestShotRelative[t]
        if (!relative.isFinite()) return false

        val grown = if (darkerRungsAdded[t] >= config.maxDarkerRungsPerDirection) null
                    else BracketPlanner.extendDarker(plan, t, source.profile.exposureLimits,
                        config.bracket, relative / SceneMeter.clippedStepDown(clipped))
        if (grown == null) {
            // Decision 3. Where a shorter exposure is physically impossible -
            // direct sun in a window is brighter than the shortest exposure the
            // sensor has - the capture proceeds and the file says so: the flag is
            // what lets the report call the top of the range a lower bound rather
            // than a measurement. Nothing is thrown away over it.
            bracketPlan = plan.withDarkEndClamped()
            message = "direction ${t + 1} is brighter than this camera can read; " +
                      "its top value is a lower bound"
            return false
        }
        darkerRungsAdded[t]++
        bracketPlan = grown
        framesPlanned = grown.totalShots()
        message = "direction ${t + 1} came back burnt out; shooting it again " +
                  "with a shorter exposure"
        return true
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

    /** Frames on disk across the whole sphere, counting each direction once. */
    private fun framesStoredLocked(): Int {
        var n = 0
        for (c in storedPerTarget) n += c
        return n
    }

    private fun buildSnapshot(): Snapshot {
        val measured = perTarget.filterNotNull()
        return Snapshot(state, shot.copyOf(), abandoned.copyOf(), currentTarget, yawOffset, pitchOffset,
            aligned, steady, framesStoredLocked(), framesPlanned, meteredFraction(),
            BooleanArray(targetCount) { perTarget[it] != null },
            darkerRungsAdded.copyOf(),
            if (pendingTarget >= 0) pendingRungs else 0,
            if (pendingTarget >= 0) Math.min(pendingReceived, pendingRungs) else 0,
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

    /**
     * The ladder grew, and this is the plan the rest of the capture is on.
     *
     * A sink that can be resumed has to be told. It wrote the plan down before
     * the first frame precisely so that an interrupted capture comes back on the
     * same ladder; a plan it never heard about is one that comes back a rung
     * short in the one direction that needed the rung.
     *
     * Does nothing by default, because a sink that keeps nothing has nothing to
     * revise.
     */
    fun planChanged(plan: BracketPlan) {}
}

/** A sink that keeps nothing, for metering-only runs and for tests. */
object DiscardingFrameSink : FrameSink {
    override fun store(frame: CapturedFrame, pixels: ImageF): Boolean = true
}
