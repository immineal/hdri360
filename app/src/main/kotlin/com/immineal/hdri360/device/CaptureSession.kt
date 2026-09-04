package com.immineal.hdri360.device

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.os.SystemClock
import android.util.Log
import com.immineal.hdri360.core.camera.Intrinsics
import com.immineal.hdri360.core.capture.CameraProfile
import com.immineal.hdri360.core.capture.CaptureController
import com.immineal.hdri360.core.capture.CapturedFrame
import com.immineal.hdri360.core.capture.FrameSink
import com.immineal.hdri360.core.capture.FrameStore
import com.immineal.hdri360.core.capture.StoredSession
import com.immineal.hdri360.core.image.ImageF
import com.immineal.hdri360.core.math.Mat3
import com.immineal.hdri360.core.pano.CaptureTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/** What the capture screen needs to draw itself. */
class CaptureUiState(
    @JvmField val phase: Phase = Phase.IDLE,
    @JvmField val message: String = "",
    /** What this device can honestly produce, said in one sentence. */
    @JvmField val tierNote: String = "",
    @JvmField val snapshot: CaptureController.Snapshot? = null,
    @JvmField val targets: List<CaptureTarget> = emptyList(),
    @JvmField val pose: Mat3? = null,
    @JvmField val intrinsics: Intrinsics? = null,
    @JvmField val sessionDir: File? = null,
    @JvmField val lenses: List<LensOption> = emptyList(),
    @JvmField val chosenLens: String? = null,
    @JvmField val warning: String? = null,
    @JvmField val resumable: File? = null,
    /** Clockwise degrees between the sensor's frame and the phone's natural one. */
    @JvmField val sensorOrientationDeg: Int = 90
) {
    enum class Phase { IDLE, OPENING, SCANNING, CAPTURING, FINISHED, FAILED }
}

/**
 * Everything that has to be alive at once during a capture, held in one place.
 *
 * The camera, the sensors, the state machine and the store each have their own
 * lifetime and their own thread, and the bugs in this kind of code are almost
 * all about one of them outliving another. So there is exactly one owner, it is
 * this, and nothing else starts or stops any of them.
 */
class CaptureSession(
    private val context: Context,
    private val onFinished: (File) -> Unit
) : FrameSink {

    private val flow = MutableStateFlow(CaptureUiState())
    val state: StateFlow<CaptureUiState> get() = flow

    private val lock = Any()
    private var source: Camera2Source? = null
    private var tracker: OrientationTracker? = null
    private var controller: CaptureController? = null
    private var store: FrameStore? = null
    private var characteristics: CameraCharacteristics? = null
    private var scanStartedMs = 0L
    private var handedOff = false

    /** Where captures live. Internal storage, so nothing else can half-delete one. */
    private val root = File(context.filesDir, "captures")

    val lenses: List<LensOption> by lazy {
        val m = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        if (m == null) emptyList() else try { CameraProbe.lenses(m) } catch (e: Exception) { emptyList() }
    }

    init {
        CaptureLog.start(context)
        flow.value = CaptureUiState(
            lenses = lenses,
            chosenLens = CameraProbe.defaultLens(lenses)?.cameraId,
            resumable = unfinishedCapture())
    }

    /** A capture that was interrupted and still has frames worth keeping. */
    fun unfinishedCapture(): File? {
        val dirs = root.listFiles() ?: return null
        return dirs.filter { it.isDirectory && File(it, FrameStore.SESSION).isFile &&
                             !File(it, DONE).isFile }
            .maxByOrNull { it.lastModified() }
    }

    fun start(cameraId: String, previewTexture: SurfaceTexture?, resumeFrom: File? = null) {
        synchronized(lock) { if (source != null) return }
        flow.value = flow.value.let {
            CaptureUiState(CaptureUiState.Phase.OPENING, "Opening the camera",
                lenses = it.lenses, chosenLens = cameraId)
        }
        val free = root.parentFile?.usableSpace ?: 0L
        val warning = if (free in 1 until MIN_FREE_BYTES)
            "Only ${free / (1024 * 1024)} MB of space is left; a full sphere needs about " +
            "${MIN_FREE_BYTES / (1024 * 1024)} MB" else null

        Camera2Source.open(context, cameraId, previewTexture, { src ->
            onCameraReady(src, resumeFrom, warning)
        }, { why ->
            CaptureLog.warn("could not open camera $cameraId: $why")
            flow.value = CaptureUiState(CaptureUiState.Phase.FAILED, why,
                lenses = lenses, chosenLens = cameraId)
        })
    }

    private fun onCameraReady(src: Camera2Source, resumeFrom: File?, warning: String?) {
        val m = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val chars = try { m.getCameraCharacteristics(src.profile.id) } catch (e: Exception) { null }
        val cfg = CaptureController.Config()
        // A development escape hatch for exercising the burst path on a bench,
        // where the phone is not being aimed at anything. It needs a file only adb
        // can create, and it says loudly that it is on, because a capture taken
        // this way is not a sphere.
        if (java.io.File("/data/local/tmp/hdri360-anyaim").exists()) {
            cfg.alignmentToleranceDeg = 180.0
            CaptureLog.warn("aim check disabled by /data/local/tmp/hdri360-anyaim; " +
                "this capture will not be a real sphere")
        }
        val ctrl = CaptureController(src, this, cfg)
        val track = OrientationTracker(context, src.profile.sensorOrientationDeg,
            src.profile.frontFacing) { pose, steady, nowNs ->
            ctrl.onOrientation(pose, steady, nowNs)
            src.poseProvider = { pose }
        }
        val blocked = track.unavailableReason()
        if (blocked != null) {
            src.close()
            flow.value = CaptureUiState(CaptureUiState.Phase.FAILED, blocked, lenses = lenses)
            return
        }

        synchronized(lock) {
            source = src
            controller = ctrl
            tracker = track
            characteristics = chars
            handedOff = false
        }
        CaptureLog.log("camera ${src.profile.id}: ${src.profile.note}")
        CaptureLog.log("plan: ${ctrl.plan.targets.size} directions, " +
            "${src.profile.exposureLimits}")
        src.setListener(ctrl)
        src.bundleWriter = ::writeBundle
        ctrl.setObserver { snap -> publish(snap) }
        track.start()

        var resumed = false
        if (resumeFrom != null) resumed = tryResume(ctrl, resumeFrom, src.profile)
        if (!resumed) {
            scanStartedMs = SystemClock.elapsedRealtime()
            ctrl.beginScan()
        }
        flow.value = flow.value.let {
            CaptureUiState(
                if (resumed) CaptureUiState.Phase.CAPTURING else CaptureUiState.Phase.SCANNING,
                if (resumed) "Resuming where it stopped" else "Sweep the scene so it can be metered",
                src.profile.note, ctrl.snapshot(), ctrl.plan.targets, null,
                src.profile.intrinsics, store?.dir, lenses, src.profile.id, warning,
                null, src.profile.sensorOrientationDeg)
        }
    }

    private fun tryResume(ctrl: CaptureController, dir: File, profile: CameraProfile): Boolean {
        val existing = FrameStore.open(dir) ?: return false
        val mask = existing.shotMask()
        if (mask.size != ctrl.plan.targets.size) {
            // A capture started on a different lens cannot be finished on this one.
            existing.close()
            return false
        }
        synchronized(lock) { store = existing }
        return try {
            ctrl.resume(mask, existing.session.plan)
            true
        } catch (e: Exception) {
            Log.w(TAG, "that capture could not be resumed", e)
            existing.close()
            synchronized(lock) { store = null }
            false
        }
    }

    /** Ends the metering sweep and commits to one ladder for the whole sphere. */
    fun finishScan(): Boolean {
        val ctrl = synchronized(lock) { controller } ?: return false
        val src = synchronized(lock) { source } ?: return false
        val chars = synchronized(lock) { characteristics }
        if (!ctrl.finishScanAndPlan()) return false
        val plan = ctrl.snapshot()
        val ladder = ctrl.bracketPlan()
        CaptureLog.log("scan closed: ${plan.message}")
        CaptureLog.log("preview now at ${src.let { _ -> ctrl.previewExposure() }}")
        CaptureLog.log("scene: ${plan.scene}")
        CaptureLog.log("white balance: " +
            (src.neutralGains()?.joinToString(", ") { String.format(Locale.US, "%.3f", it) }
             ?: "none reported"))
        ladder?.let { l ->
            CaptureLog.log("ladder: " + l.ladder.steps.joinToString(" | ") { it.toString() })
        }
        // Now that the ladder exists, the size of the capture is actually known -
        // which it was not when the camera was opened. A sphere is hundreds of
        // frames, and running out of space at direction forty is a capture lost.
        ladder?.let { l ->
            val k = src.profile.intrinsics
            val perFrame = 2L * k.width * k.height          // half float on disk
            val perDng = 2L * k.width * k.height            // the bundle the user keeps
            val needed = l.totalShots().toLong() * (perFrame + perDng)
            val free = root.parentFile?.usableSpace ?: 0L
            CaptureLog.log("space: ${l.totalShots()} frames need about " +
                "${needed shr 20} MB, ${free shr 20} MB free")
            if (free in 1 until needed) {
                val short = "This sphere needs about ${needed shr 20} MB and only " +
                    "${free shr 20} MB is free"
                CaptureLog.warn(short)
                flow.value = flow.value.let {
                    CaptureUiState(it.phase, it.message, it.tierNote, it.snapshot, it.targets,
                        it.pose, it.intrinsics, it.sessionDir, it.lenses, it.chosenLens, short,
                        null, it.sensorOrientationDeg)
                }
            }
        }
        // The store can only be created once the ladder exists, because a resumed
        // capture has to come back on the same one.
        val session = StoredSession(
            cameraId = src.profile.id,
            tier = src.profile.tier,
            intrinsics = src.profile.intrinsics,
            apertureN = src.profile.apertureN,
            focalLengthMm = src.profile.focalLengthMm,
            sensorOrientationDeg = src.profile.sensorOrientationDeg,
            cfa = src.profile.cfa,
            whiteLevel = if (chars != null) CameraProbe.whiteLevelOf(chars) else 1023,
            blackLevel = if (chars != null) CameraProbe.blackLevelOf(chars) else DoubleArray(4),
            baseIso = src.profile.exposureLimits.baseIso,
            plan = ctrl.bracketPlan() ?: return false,
            note = src.profile.note,
            neutralGains = src.neutralGains())
        val dir = File(root, String.format(Locale.US, "capture-%d", System.currentTimeMillis()))
        return try {
            synchronized(lock) { store = FrameStore.create(dir, session) }
            publish(plan)
            true
        } catch (e: Exception) {
            flow.value = flow.value.let {
                CaptureUiState(CaptureUiState.Phase.FAILED,
                    "Could not open storage for this capture: ${e.message}",
                    it.tierNote, it.snapshot, it.targets, it.pose, it.intrinsics,
                    null, it.lenses, it.chosenLens)
            }
            false
        }
    }

    /** Stops the capture where it stands, keeping whatever was shot. */
    fun finish() {
        synchronized(lock) { controller }?.finish()
    }

    fun stop() {
        val (src, track, st) = synchronized(lock) {
            val t = Triple(source, tracker, store)
            source = null; tracker = null; controller = null; store = null
            t
        }
        track?.stop()
        src?.setListener(null)
        src?.close()
        st?.close()
    }

    // --------------------------------------------------------------- FrameSink

    override fun store(frame: CapturedFrame, pixels: ImageF): Boolean {
        val s = synchronized(lock) { store } ?: return false
        val ok = s.store(frame, pixels)
        if (!ok) CaptureLog.warn("frame t${frame.targetIndex} b${frame.bracketIndex} " +
            "was not stored: ${s.lastError}")
        if (!ok) s.lastError?.let { why ->
            flow.value = flow.value.let {
                CaptureUiState(it.phase, it.message, it.tierNote, it.snapshot, it.targets,
                    it.pose, it.intrinsics, it.sessionDir, it.lenses, it.chosenLens, why)
            }
        }
        return ok
    }

    /**
     * The DNG the user asked to keep, written from the image and its metadata
     * while both are still alive. Failing to write one never fails the capture:
     * the working frame is what the pipeline needs, and the bundle is a bonus.
     */
    private fun writeBundle(frame: CapturedFrame, image: Image, result: TotalCaptureResult) {
        val dir = synchronized(lock) { store?.dir } ?: return
        val chars = synchronized(lock) { characteristics } ?: return
        val bundle = File(dir, "raw")
        if (!bundle.isDirectory && !bundle.mkdirs()) return
        val out = File(bundle, String.format(Locale.US, "t%03d_b%d.dng",
            frame.targetIndex, frame.bracketIndex))
        try {
            val started = SystemClock.elapsedRealtime()
            DngCreator(chars, result).use { creator ->
                BufferedOutputStream(FileOutputStream(out)).use { creator.writeImage(it, image) }
            }
            // The bundle is written on the camera's own thread, because the image
            // and its metadata are both about to go away - so what it costs is
            // time the next frame of the burst spends waiting. Worth knowing.
            val took = SystemClock.elapsedRealtime() - started
            dngTotalMs += took
            dngCount++
            if (dngCount % 25 == 1)
                CaptureLog.log("DNG t${frame.targetIndex} b${frame.bracketIndex} in ${took} ms " +
                    "(${dngCount} written, ${dngTotalMs / dngCount} ms average)")
        } catch (e: Exception) {
            Log.w(TAG, "no DNG for this frame; the capture continues", e)
            out.delete()
        }
    }

    // ---------------------------------------------------------------- observing

    /**
     * A line per thing that actually happened, because the log is the only
     * account of a capture that went wrong on a phone that is not in front of me.
     *
     * Per direction rather than per frame: a sphere is a hundred and sixty frames
     * and a log nobody can read is not a log. What is worth knowing afterwards is
     * which directions landed, in what order, how long each took, and which ones
     * were given up on.
     */
    private fun logProgress(snap: CaptureController.Snapshot) {
        val shot = snap.directionsShot
        val lost = snap.abandoned.count { it }
        val now = SystemClock.elapsedRealtime()
        // Felt, not seen: the zenith is shot with the screen facing the floor.
        if (snap.aligned && !lastAligned) haptics.onTarget()
        lastAligned = snap.aligned
        if (shot > lastShot) haptics.captured()
        if (lost > lastAbandoned) haptics.missed()
        if (shot != lastShot || lost != lastAbandoned) {
            val since = if (lastSettledMs == 0L) 0L else now - lastSettledMs
            lastSettledMs = now
            CaptureLog.log("direction ${shot + lost} of ${snap.shot.size} settled " +
                "(${shot} shot, ${lost} given up) after ${since} ms, " +
                "${snap.framesTaken}/${snap.framesPlanned} frames")
            lastShot = shot
            lastAbandoned = lost
        }
        val m = snap.message
        if (m != null && m != lastMessage) {
            lastMessage = m
            CaptureLog.log("state ${snap.state}: $m")
        }
    }

    private fun publish(snap: CaptureController.Snapshot) {
        logProgress(snap)
        val src = synchronized(lock) { source }
        val pose = synchronized(lock) { tracker }?.currentPose()
        val ctrl = synchronized(lock) { controller }
        val dir = synchronized(lock) { store?.dir }
        val phase = when (snap.state) {
            CaptureController.State.SCANNING -> CaptureUiState.Phase.SCANNING
            CaptureController.State.CAPTURING -> CaptureUiState.Phase.CAPTURING
            CaptureController.State.FINISHED -> CaptureUiState.Phase.FINISHED
            CaptureController.State.FAILED -> CaptureUiState.Phase.FAILED
            else -> CaptureUiState.Phase.OPENING
        }
        flow.value = CaptureUiState(phase, snap.message ?: "", src?.profile?.note ?: "",
            snap, ctrl?.plan?.targets ?: emptyList(), pose, src?.profile?.intrinsics, dir,
            lenses, src?.profile?.id, flow.value.warning, null,
            src?.profile?.sensorOrientationDeg ?: 90)

        // The sweep ends by itself once it has seen enough. A capture the user has
        // to remember to advance is a capture that gets abandoned half metered.
        if (snap.state == CaptureController.State.SCANNING &&
            snap.scanCoverage >= SCAN_ENOUGH &&
            SystemClock.elapsedRealtime() - scanStartedMs > MIN_SCAN_MS) {
            finishScan()
        }

        if (phase == CaptureUiState.Phase.FINISHED) {
            haptics.finished()
            CaptureLog.log("capture finished: ${snap.directionsShot} of ${snap.shot.size} " +
                "directions, ${snap.framesTaken} frames")
            val done = synchronized(lock) {
                if (handedOff) null else { handedOff = true; dir }
            }
            if (done != null) {
                stop()
                onFinished(done)
            }
        }
    }

    private val haptics = Haptics(context)
    private var dngTotalMs = 0L
    private var dngCount = 0
    private var lastShot = 0
    private var lastAbandoned = 0
    private var lastAligned = false
    private var lastSettledMs = 0L
    private var lastMessage: String? = null

    companion object {
        private const val TAG = "Hdri360.Session"
        /** Marker written once a capture has been processed, so it stops offering to resume. */
        const val DONE = "processed"
        private const val SCAN_ENOUGH = 0.55
        private const val MIN_SCAN_MS = 3000L
        private const val MIN_FREE_BYTES = 1500L * 1024 * 1024
    }
}
