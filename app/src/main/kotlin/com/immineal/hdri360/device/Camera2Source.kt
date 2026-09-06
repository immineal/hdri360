package com.immineal.hdri360.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.RggbChannelVector
import android.media.Image
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.immineal.hdri360.core.capture.CameraProfile
import com.immineal.hdri360.core.capture.CameraSource
import com.immineal.hdri360.core.capture.CapturedFrame
import com.immineal.hdri360.core.capture.Lens
import com.immineal.hdri360.core.capture.CaptureTier
import com.immineal.hdri360.core.capture.PixelFormat
import com.immineal.hdri360.core.capture.StreamLadder
import com.immineal.hdri360.core.capture.StreamPlan
import com.immineal.hdri360.core.hdr.ExposureSettings
import com.immineal.hdri360.core.image.ImageF
import com.immineal.hdri360.core.math.Mat3
import java.util.concurrent.Executors

/**
 * Camera2, driven manually, with every automatic behaviour switched off and left
 * off for the whole capture: auto-exposure, auto-white-balance, autofocus, noise
 * reduction, edge enhancement. Not out of purism - each of them varies between
 * frames, and a panorama assembled from frames with different white balance or
 * different sharpening has seams no blending can hide. Focus is pinned at
 * infinity for the same reason: a refocus changes the effective focal length,
 * and the stitcher reads that as a pose error.
 *
 * ## Identity travels with the data
 *
 * The predecessor paired images to their metadata by arrival order, from two
 * separate queues, and a single out-of-order arrival silently mispaired an
 * entire burst - every frame then carrying someone else's exposure. Here the
 * pairing key is SENSOR_TIMESTAMP, which both sides carry and neither invents,
 * and the position in the bracket rides on the request as a tag. Nothing depends
 * on the order anything arrives in.
 *
 * ## A burst ends when its pixels have arrived
 *
 * Completion used to fire from the metadata callback, which can precede the
 * image it describes; the tail of a burst was then dropped while the direction
 * counted as shot. A burst here is settled when every frame has either produced
 * pixels or been reported lost, and a frame the camera failed or whose buffer
 * was dropped counts as lost rather than being waited for forever.
 */
class Camera2Source private constructor(
    private val context: Context,
    private val manager: CameraManager,
    private val characteristics: CameraCharacteristics,
    private val device: CameraDevice,
    private val thread: HandlerThread,
    private val handler: Handler,
    private val previewSurface: Surface,
    private val previewTexture: SurfaceTexture?,
    @JvmField val plan: StreamPlan,
    @JvmField val subsample: Int,
    override val profile: CameraProfile
) : CameraSource {

    /**
     * The shortest frame the capture stream is actually offered at, in
     * nanoseconds, or 0 when the device would not say.
     *
     * Read once from `getOutputMinFrameDuration` for the exact format and size
     * being captured. Requesting anything shorter is requesting something the
     * device never advertised, and on a bracket that changes gain every frame it
     * is what makes the sensor miss its configuration deadline - see
     * [StreamLadder.frameDurationFor], which has the measurement.
     */
    /** When the current burst was handed to the camera, for measuring latency. */
    @Volatile private var burstSubmittedMs = 0L

    /** The last slow frame reported, so one bad burst is one line and not five. */
    @Volatile private var lastSlowReportMs = 0L

    private val minFrameDurationNs: Long = try {
        characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputMinFrameDuration(CameraProbe.formatOf(plan),
                android.util.Size(plan.capture.width, plan.capture.height)) ?: 0L
    } catch (e: Exception) {
        0L
    }

    /** Where the device is pointing, asked at the moment a frame is paired. */
    @JvmField @Volatile var poseProvider: () -> Mat3? = { null }

    /**
     * Given a RAW frame and its metadata before the image is released, so the
     * user's DNG bundle can be written without the core knowing what a DNG is.
     */
    @JvmField @Volatile var bundleWriter: ((CapturedFrame, Image, TotalCaptureResult) -> Unit)? = null

    private var session: CameraCaptureSession? = null
    private var captureReader: ImageReader? = null
    private var meteringReader: ImageReader? = null

    private val lock = Any()
    private var listener: CameraSource.Listener? = null
    private var previewSettings: ExposureSettings = profile.exposureLimits.realize(1.0 / 120.0)
    /**
     * What metering frames are taken at. Separate from the viewfinder's exposure
     * because the two want different things: see CameraSource.setMeteringExposure.
     */
    private var meteringSettings: ExposureSettings = profile.exposureLimits.realize(1.0 / 120.0)
    private var meteringEnabled = false
    private var closed = false

    /** Locked once, from the first metering result, so every frame shares one colour scale. */
    private var lockedWhiteBalance: RggbChannelVector? = null

    // Per-burst state. All of it is touched only under [lock].
    private var burstId = 0L
    private var burstTarget = -1
    private var burstExpected = 0
    private var burstPaired = 0
    private var burstSettled = 0
    private val pendingResults = HashMap<Long, TotalCaptureResult>()
    private val pendingImages = HashMap<Long, Image>()

    /** Conversion and delivery are far too slow to run on the camera's own thread. */
    private val work = Executors.newSingleThreadExecutor { r ->
        Thread(r, "hdri-frames").apply { isDaemon = true }
    }

    override fun setListener(listener: CameraSource.Listener?) {
        synchronized(lock) { this.listener = listener }
    }

    override fun startPreview(settings: ExposureSettings) {
        val changed = synchronized(lock) {
            val was = previewSettings
            previewSettings = settings
            was.iso != settings.iso || was.exposureTimeSec != settings.exposureTimeSec
        }
        // What the viewfinder is being shown at, whenever it moves. Without this
        // there is no way to tell a preview that is dark because the room is dark
        // from one that is dark because it is being exposed for a light meter.
        if (changed) CaptureLog.log("preview at $settings")
        applyPreview()
    }

    override fun setMeteringExposure(settings: ExposureSettings) {
        val ridesThePreview = synchronized(lock) {
            meteringSettings = settings
            meteringReader != null
        }
        // Where metering comes off a second stream of the repeating request there
        // is only one exposure to give, and a measurement that is wrong is worse
        // than a viewfinder that is dark - so on that path the meter still wins.
        // The RAW tiers take metering as their own capture and are unaffected.
        if (ridesThePreview) {
            synchronized(lock) { previewSettings = settings }
            applyPreview()
        }
    }

    override fun setPreviewMeteringEnabled(enabled: Boolean) {
        val changed = synchronized(lock) {
            if (meteringEnabled == enabled) return
            meteringEnabled = enabled
            true
        }
        if (changed) {
            applyPreview()
            if (enabled && meteringReader == null) handler.post(meteringPoll)
        }
    }

    override fun captureBracket(burstId: Long, targetIndex: Int,
                                rungs: List<ExposureSettings>): Boolean {
        if (rungs.isEmpty()) return false
        val s = session ?: return false
        synchronized(lock) {
            if (closed || this.burstId != 0L) return false
            this.burstId = burstId
            burstTarget = targetIndex
            burstExpected = rungs.size
            burstPaired = 0
            burstSettled = 0
            // Anything still queued belongs to a burst nobody is waiting for.
            discardPendingLocked()
        }
        return try {
            val reader = captureReader ?: throw IllegalStateException("no capture stream")
            val requests = ArrayList<CaptureRequest>(rungs.size)
            for (i in rungs.indices) {
                val b = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                b.addTarget(reader.surface)
                applyManualSettings(b, rungs[i])
                // The position in the bracket rides on the request, so a frame can
                // always say which rung it is regardless of when it turns up.
                b.setTag(i)
                requests.add(b.build())
            }
            s.stopRepeating()
            burstSubmittedMs = SystemClock.elapsedRealtime()
            s.captureBurst(requests, burstCallback, handler)
            true
        } catch (e: Exception) {
            CaptureLog.warn("the bracket could not be submitted", e)
            synchronized(lock) { this.burstId = 0L; burstTarget = -1; burstExpected = 0 }
            applyPreview()
            report("This bracket could not be started: ${e.message}", false)
            false
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            discardPendingLocked()
            listener = null
        }
        handler.removeCallbacks(meteringPoll)
        try { session?.close() } catch (e: Exception) { }
        try { device.close() } catch (e: Exception) { }
        try { captureReader?.close() } catch (e: Exception) { }
        try { meteringReader?.close() } catch (e: Exception) { }
        try { previewSurface.release() } catch (e: Exception) { }
        work.shutdown()
        thread.quitSafely()
    }

    // ------------------------------------------------------------------ preview

    private fun applyPreview() {
        val s = session ?: return
        val settings = synchronized(lock) { previewSettings }
        // White balance has to come from somewhere, and with AWB switched off the
        // camera will not compute one. So the very first preview runs under the
        // camera's own algorithms purely to read the gains it picks, and
        // everything after that is manual with those gains frozen in. One scale
        // for the whole sphere is the point: a per-frame white balance puts every
        // direction on a different colour scale and no blending hides it.
        val probing = profile.tier.drivesExposure && lockedWhiteBalance == null
        try {
            val b = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(previewSurface)
            meteringReader?.let { if (synchronized(lock) { meteringEnabled }) b.addTarget(it.surface) }
            if (probing) applyAutoSettings(b) else applyManualSettings(b, settings)
            previewReported = false
            s.setRepeatingRequest(b.build(),
                if (probing) whiteBalanceProbe else previewReport, handler)
        } catch (e: Exception) {
            report("The preview stopped: ${e.message}", false)
        }
    }

    /** Whether the lens can be told to hold still, from the device's own list. */
    private val opticalStabilisationOff: Boolean = try {
        characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
            ?.any { it == CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF } ?: false
    } catch (e: Exception) {
        false
    }

    @Volatile private var previewReported = false
    @Volatile private var previewLastReport: String? = null
    /** The colour matrix the camera chose, frozen alongside the white balance. */
    @Volatile private var lockedColorTransform: android.hardware.camera2.params.ColorSpaceTransform? = null
    /** The lens shading correction of the same converged frame the gains came from. */
    @Volatile private var lockedShading: com.immineal.hdri360.core.image.ShadingMap? = null

    /**
     * What the viewfinder actually got, once per change.
     *
     * Requesting an exposure and receiving it are different things - a device can
     * clamp the frame duration, ignore a sensitivity, or run the request through
     * a pipeline that darkens it - and without reading the result back there is
     * no way to tell a preview that is dark because the room is dark from one
     * that is dark because the request did not take.
     */
    private val previewReport = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest,
                                        result: TotalCaptureResult) {
            if (previewReported) return
            previewReported = true
            val got = actualSettings(result)
            val tone = result.get(CaptureResult.TONEMAP_MODE)
            val xf = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
            // Once per distinct answer. The request is rebuilt after every burst,
            // so reporting each one puts a line per direction in the log and the
            // colour matrix is long: what is worth knowing is when it changes.
            val line = "preview got $got, tonemap $tone, colour $xf"
            if (line == previewLastReport) return
            previewLastReport = line
            CaptureLog.log(line)
        }
    }

    private val whiteBalanceProbe = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest,
                                        result: TotalCaptureResult) {
            if (lockedWhiteBalance != null) return
            val awb = result.get(CaptureResult.CONTROL_AWB_STATE)
            // Wait for it to settle; the first frames report gains it is still moving.
            if (awb != null && awb != CameraMetadata.CONTROL_AWB_STATE_CONVERGED &&
                awb != CameraMetadata.CONTROL_AWB_STATE_LOCKED) return
            val gains = result.get(CaptureResult.COLOR_CORRECTION_GAINS) ?: return
            lockedColorTransform = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
            lockedShading = FrameConverters.shadingOf(result)
            lockedWhiteBalance = gains
            handler.post { applyPreview() }
        }
    }

    /**
     * The sensor RGB to linear sRGB matrix this capture is fixed to, row-major.
     *
     * Read off the same converged auto frame as the gains and locked with them,
     * because the pair is what defines the colour: the gains move the grey point
     * and this pulls the sensor's overlapping filters out to Rec.709 primaries.
     * Applying one without the other is what left the output green.
     */
    /**
     * The lens shading correction every frame of this capture was flattened
     * with, for the session header.
     *
     * Recorded because it cannot be recovered afterwards: the DNG bundle is raw
     * and the map is only ever reported alongside a capture result. A bundle
     * without it can be re-processed but not reproduced - the corners come out a
     * stop darker than the frames the phone actually stitched, which on a real
     * capture cost seven solved pairs and split the pose graph into four pieces.
     */
    fun shadingMap(): com.immineal.hdri360.core.image.ShadingMap? = lockedShading

    fun colorTransform(): DoubleArray? {
        val m = lockedColorTransform ?: return null
        val out = DoubleArray(9)
        for (row in 0 until 3)
            for (col in 0 until 3) {
                val r = m.getElement(col, row)
                out[row * 3 + col] = r.numerator.toDouble() / r.denominator.toDouble()
            }
        return out
    }

    /** The per-channel gains this capture is fixed to, greens averaged. */
    fun neutralGains(): DoubleArray? {
        val g = lockedWhiteBalance ?: return null
        return doubleArrayOf(g.red.toDouble(),
            0.5 * (g.greenEven.toDouble() + g.greenOdd.toDouble()), g.blue.toDouble())
    }

    /** Everything automatic, briefly, so there is something to freeze. */
    private fun applyAutoSettings(b: CaptureRequest.Builder) {
        applyStillOptics(b)
        b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
        b.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0.0f)
        b.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_OFF)
        b.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF)
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
        // The one request whose result is actually mined for calibration, so it
        // has to ask for all of it. The shading map is read off the converged
        // probe frame together with the gains and the colour matrix, and asking
        // for it only on the still path - which is where it used to be set -
        // meant the probe frame never carried one: every capture on the phone
        // recorded "shading: none reported" and shipped a bundle the desktop
        // path could not reproduce, which is the whole reason the map is
        // recorded at all.
        b.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
            CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON)
    }

    /**
     * Metering when the device would not give us a third stream.
     *
     * Rather than go without - which would leave the exposure ladder guessing -
     * a single still is taken every so often through the capture stream. It costs
     * one frame a second during the sweep and nothing at all afterwards.
     */
    private val meteringPoll = object : Runnable {
        override fun run() {
            val s = session
            val reader = captureReader
            val settled = !profile.tier.drivesExposure || lockedWhiteBalance != null
            val active = settled && synchronized(lock) { meteringEnabled && burstId == 0L && !closed }
            if (s == null || reader == null || meteringReader != null || !active) {
                if (synchronized(lock) { meteringEnabled && !closed } && meteringReader == null)
                    handler.postDelayed(this, METERING_PERIOD_MS)
                return
            }
            try {
                val b = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                b.addTarget(reader.surface)
                applyManualSettings(b, synchronized(lock) { meteringSettings })
                b.setTag(METERING_TAG)
                s.capture(b.build(), meteringCallback, handler)
            } catch (e: Exception) {
                Log.w(TAG, "a metering frame could not be taken", e)
            }
            handler.postDelayed(this, METERING_PERIOD_MS)
        }
    }

    private val meteringCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest,
                                        result: TotalCaptureResult) {
            if (lockedWhiteBalance == null) {
                lockedColorTransform = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
                lockedWhiteBalance = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
            }
            val ts = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
            synchronized(lock) { pendingResults[ts] = result }
            pairAny()
        }
    }

    // ------------------------------------------------------------------- bursts

    private val burstCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest,
                                        result: TotalCaptureResult) {
            if (lockedWhiteBalance == null) {
                lockedColorTransform = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
                lockedWhiteBalance = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
            }
            // A rung that took its time says so, and a rung that did not says
            // nothing. Written this way round after an evening in which a whole
            // sphere failed because frames were arriving eight seconds apart
            // while the log said only that a direction had timed out: what was
            // needed was the latency of the frame itself, and what is not needed
            // is that line a hundred and thirty-six times per capture.
            //
            // Requesting an exposure and receiving it are also different things,
            // so when it is slow the line says both.
            val since = if (burstSubmittedMs > 0) SystemClock.elapsedRealtime() - burstSubmittedMs
                        else -1L
            if (since > SLOW_FRAME_MS && since > lastSlowReportMs + SLOW_REPORT_GAP_MS) {
                lastSlowReportMs = since
                CaptureLog.log(String.format(java.util.Locale.US,
                    "slow frame: rung %s back at +%d ms, asked %.1f ms ISO%d, got %.1f ms ISO%d",
                    (request.tag ?: "?").toString(), since,
                    (request.get(CaptureRequest.SENSOR_EXPOSURE_TIME) ?: 0L) / 1e6,
                    request.get(CaptureRequest.SENSOR_SENSITIVITY) ?: 0,
                    (result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L) / 1e6,
                    result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0))
            }
            val ts = result.get(CaptureResult.SENSOR_TIMESTAMP)
            if (ts == null) {
                // Without a timestamp there is no way to say which pixels this
                // describes, and guessing is what broke the predecessor.
                settle(false)
                return
            }
            synchronized(lock) { pendingResults[ts] = result }
            pairAny()
        }

        override fun onCaptureFailed(s: CameraCaptureSession, request: CaptureRequest,
                                     failure: CaptureFailure) {
            // The frame is not coming. Settling it is what stops the controller
            // waiting forever, which is the failure the predecessor never left.
            //
            // Into the app's own log and not only logcat. When a capture dies on
            // somebody's phone this file is the whole of the evidence, and an
            // evening was spent guessing at frames that never arrived while the
            // reason for it went to a buffer nobody kept.
            CaptureLog.log("a bracket frame failed, reason ${failure.reason}" +
                (if (failure.wasImageCaptured()) " (the image was captured)" else ""))
            settle(false)
        }

        override fun onCaptureBufferLost(s: CameraCaptureSession, request: CaptureRequest,
                                         target: Surface, frameNumber: Long) {
            CaptureLog.log("a bracket frame's buffer was dropped")
            settle(false)
        }
    }

    private fun onCaptureImage(reader: ImageReader) {
        val image = try { reader.acquireNextImage() } catch (e: Exception) { null } ?: return
        synchronized(lock) {
            if (closed) { image.close(); return }
            val previous = pendingImages.put(image.timestamp, image)
            previous?.close()
        }
        pairAny()
    }

    /** Emits every frame whose pixels and metadata are both in hand. */
    private fun pairAny() {
        while (true) {
            var image: Image? = null
            var result: TotalCaptureResult? = null
            var id = 0L
            var target = -1
            synchronized(lock) {
                if (closed) return
                for (ts in pendingImages.keys) {
                    val r = pendingResults[ts] ?: continue
                    image = pendingImages.remove(ts)
                    pendingResults.remove(ts)
                    result = r
                    break
                }
                id = burstId
                target = burstTarget
                // Metadata whose pixels never turned up would otherwise pile up
                // across bursts; a burst's own accounting decides when to give up.
                if (pendingResults.size > MAX_PENDING) pendingResults.clear()
            }
            val im = image ?: return
            val res = result ?: return
            deliver(im, res, id, target)
        }
    }

    private fun deliver(image: Image, result: TotalCaptureResult, id: Long, target: Int) {
        val tag = result.request.tag
        val metering = tag == METERING_TAG || id == 0L
        val settings = actualSettings(result)
        try {
            if (metering) {
                val luma = try { meteringPlane(image, result) } catch (e: Exception) { null }
                image.close()
                if (luma != null) {
                    val rel = settings.relativeExposure(profile.exposureLimits.baseIso)
                    logMetering(luma, settings)
                    post { synchronized(lock) { listener }?.onPreviewFrame(luma, rel) }
                }
                return
            }

            val bracketIndex = if (tag is Int) tag else 0
            val frame = CapturedFrame(id, target, bracketIndex, settings,
                poseProvider(), image.timestamp, plan.tier == CaptureTier.LINEAR_RAW)

            // The DNG needs the image and its metadata together, and both are
            // about to go away, so the bundle is written here rather than later.
            if (plan.format == PixelFormat.RAW_SENSOR)
                try { bundleWriter?.invoke(frame, image, result) }
                catch (e: Exception) { Log.w(TAG, "the DNG for this frame was not written", e) }

            // The camera thread does the one thing that has to happen here - get
            // the pixels out of the buffer so the image can be closed and the
            // camera can fill the next one - and nothing else.
            //
            // Everything after this used to happen here too, and it was eight
            // seconds a frame: the next frame of the burst arrives on this
            // thread, so a burst of four took thirty-two seconds, outlived its
            // timeout, and no capture could finish. It is 200 ms now, which is
            // 200 ms this thread still would not be listening for.
            val t0 = SystemClock.elapsedRealtime()
            // Without the shading correction. It is recorded in the session and
            // applied to merged radiance at processing time, where saturation has
            // already been read off the sensor and there is no ceiling to clamp
            // against - see HdriPipeline.Options.shading. Applied here it invented
            // blown highlights out of dim corners.
            val raw = if (plan.format == PixelFormat.RAW_SENSOR)
                FrameConverters.rawFrameOf(image, characteristics, result,
                    applyShading = false) else null
            val copyMs = SystemClock.elapsedRealtime() - t0
            val yuv = if (raw == null) FrameConverters.rgb(image, subsample) else null
            image.close()
            if (copyMs > SLOW_COPY_MS)
                CaptureLog.log("copying t${frame.targetIndex} b${frame.bracketIndex} out of " +
                    "the camera buffer took $copyMs ms")
            post {
                val convertStart = SystemClock.elapsedRealtime()
                val pixels = try {
                    raw?.convert(subsample) ?: yuv
                } catch (e: Exception) {
                    Log.w(TAG, "a captured frame could not be converted", e)
                    null
                }
                val convertMs = SystemClock.elapsedRealtime() - convertStart
                if (convertMs > SLOW_CONVERT_MS)
                    CaptureLog.log("converting t${frame.targetIndex} b${frame.bracketIndex} " +
                        "took $convertMs ms")
                if (pixels == null) {
                    settle(false)
                } else {
                    synchronized(lock) { listener }?.onFrameCaptured(frame, pixels)
                    settle(true)
                }
            }
        } catch (e: Exception) {
            try { image.close() } catch (ignored: Exception) { }
            Log.w(TAG, "a captured frame could not be converted", e)
            settle(false)
        }
    }

    /**
     * A metering frame, in whatever space this tier actually measures in.
     *
     * On the RAW tier that has to be RAW: metering a YUV preview would be
     * metering the camera's tone curve, and every number the bracket planner
     * derives from it - the scene's dynamic range, where the highlights sit -
     * assumes linear sensor units.
     *
     * Uncorrected, too: whether the sensor is clipping is a question about the
     * sensor, and shading correction multiplies the corners by three or four
     * before the clamp.
     */
    private fun meteringPlane(image: Image, result: TotalCaptureResult): ImageF =
        if (image.format == ImageFormat.RAW_SENSOR)
            FrameConverters.rawPlane(image, characteristics, result, meteringSubsample(),
                applyShading = false)
        else
            FrameConverters.luma(image, meteringSubsample())

    private var meteringLogged = 0L

    /** Occasional, because a line per preview frame would drown everything else. */
    private fun logMetering(plane: ImageF, settings: ExposureSettings) {
        val now = System.currentTimeMillis()
        if (now - meteringLogged < 1500) return
        meteringLogged = now
        var max = 0f
        var sum = 0.0
        for (v in plane.data) { if (v > max) max = v; sum += v.toDouble() }
        CaptureLog.log(String.format(java.util.Locale.US,
            "meter %dx%d mean %.4f max %.4f at %s",
            plane.width, plane.height, sum / plane.data.size, max, settings))
    }

    /**
     * Records one frame of the burst as accounted for, whether it arrived or not,
     * and finishes the burst when none are outstanding.
     */
    private fun settle(paired: Boolean) {
        var finished = false
        var id = 0L
        var target = -1
        var expected = 0
        var received = 0
        synchronized(lock) {
            if (burstId == 0L) return
            burstSettled++
            if (paired) burstPaired++
            if (burstSettled >= burstExpected) {
                finished = true
                id = burstId; target = burstTarget
                expected = burstExpected; received = burstPaired
                burstId = 0L; burstTarget = -1; burstExpected = 0
                burstPaired = 0; burstSettled = 0
                discardPendingLocked()
            }
        }
        if (!finished) return
        // A burst that came back short says so here, where the count is still in
        // hand. The controller sees only how many arrived; this says how many of
        // them were paired pixels-to-metadata and how many were settled without,
        // which is the difference between a camera that failed a frame and one
        // that never produced it.
        if (received < expected)
            CaptureLog.log("burst on direction ${target + 1} came back " +
                "$received of $expected")
        applyPreview()
        // Raised after the last image, never after the last metadata.
        //
        // Handed over through post(), which drops the work if this source has
        // been closed. The camera keeps delivering for a moment after close() -
        // a burst in flight does not stop because the app stopped waiting for it
        // - and the executor is already shut down by then, so this threw
        // RejectedExecutionException straight into a Camera2 callback and took
        // the camera's own thread down with it. Seen on a real capture, in the
        // app's own crash log:
        //
        //   CRASH on hdri-camera
        //   java.util.concurrent.RejectedExecutionException: Task ... rejected
        //   from ThreadPoolExecutor[Terminated, pool size = 0, ...]
        //
        // At shutdown, so mostly harmless - but a crash in the log is a thing
        // that has to be read and dismissed every time somebody goes looking for
        // a real one, and the frames still arriving were being thrown away by an
        // exception rather than by a decision.
        post { synchronized(lock) { listener }?.onBurstFinished(id, target, expected, received) }
    }

    /**
     * Runs something on the worker, unless this source has been closed.
     *
     * Checked and submitted under the lock, because close() sets the flag and
     * shuts the executor down in that order and a submission that slips between
     * them is exactly the crash this exists to stop.
     */
    private fun post(body: () -> Unit) {
        synchronized(lock) {
            if (closed) return
            try {
                work.execute(body)
            } catch (e: java.util.concurrent.RejectedExecutionException) {
                // Closed underneath us anyway. Nothing is waiting for this.
            }
        }
    }

    private fun discardPendingLocked() {
        // What was left unpaired says which half of the camera went missing, and
        // that is the difference between a diagnosis and a guess. Pixels with no
        // metadata is a stream that delivered and a result that did not; metadata
        // with no pixels is the opposite; neither is a frame that simply never
        // came. All three look identical from the controller, which sees only a
        // count that came up short.
        if (pendingImages.isNotEmpty() || pendingResults.isNotEmpty())
            CaptureLog.log("discarding ${pendingImages.size} image(s) and " +
                "${pendingResults.size} result(s) that never paired")
        for (i in pendingImages.values) try { i.close() } catch (e: Exception) { }
        pendingImages.clear()
        pendingResults.clear()
    }

    private fun report(message: String, fatal: Boolean) {
        val l = synchronized(lock) { listener } ?: return
        post { l.onCameraError(message, fatal) }
    }

    // ----------------------------------------------------------------- requests

    /**
     * What the sensor actually used, read back rather than assumed.
     *
     * On a camera without manual sensor control the requested exposure is quietly
     * ignored; the predecessor stored the request anyway, so every frame carried
     * an exposure it had never been taken at and the merge divided by fiction.
     */
    private fun actualSettings(result: TotalCaptureResult): ExposureSettings {
        val requested = synchronized(lock) { previewSettings }
        val t = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
        val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
        val aperture = result.get(CaptureResult.LENS_APERTURE)
        return ExposureSettings(
            if (t != null && t > 0) t / 1e9 else requested.exposureTimeSec,
            if (iso != null && iso > 0) iso else requested.iso,
            if (aperture != null && aperture > 0) aperture.toDouble() else profile.apertureN)
    }

    /**
     * Both kinds of stabilisation, off.
     *
     * Optical stabilisation moves the lens to cancel hand shake, which is the
     * right thing for a photograph and the wrong thing for a panorama: it shifts
     * the optical centre between frames, so the frames are no longer views from
     * one point through one lens. The stitcher solves rotations about a fixed
     * centre with a fixed camera model, and a lens that quietly moves puts that
     * error into the seams where it cannot be told from parallax. Digital
     * stabilisation is worse still - it crops and warps.
     */
    private fun applyStillOptics(b: CaptureRequest.Builder) {
        if (opticalStabilisationOff)
            b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF)
        b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
            CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
    }

    private fun applyManualSettings(b: CaptureRequest.Builder, settings: ExposureSettings) {
        applyStillOptics(b)
        b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
        b.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0.0f)                  // infinity
        b.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_OFF)
        b.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF)
        b.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
            CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON)

        if (profile.tier.drivesExposure) {
            b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_OFF)
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME,
                clampNs(settings.exposureTimeNs()))
            b.set(CaptureRequest.SENSOR_SENSITIVITY, clampIso(settings.iso))
            b.set(CaptureRequest.SENSOR_FRAME_DURATION,
                StreamLadder.frameDurationFor(settings.exposureTimeNs(), minFrameDurationNs))
            lockedWhiteBalance?.let {
                b.set(CaptureRequest.COLOR_CORRECTION_MODE,
                    CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                b.set(CaptureRequest.COLOR_CORRECTION_GAINS, it)
                // The mode says "use the transform in this request", so a request
                // without one hands the pipeline whatever its unset default is -
                // and on this device that is a matrix which multiplies the frame
                // to nothing. RAW never passes through colour correction, so the
                // measurement and the stored frames were untouched and only the
                // viewfinder went black.
                b.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM,
                    lockedColorTransform ?: IDENTITY_TRANSFORM)
            }
        } else {
            // The camera chooses. Locking what it chose at least gives every frame
            // one shared setting, which is the most this tier can honestly offer.
            b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            b.set(CaptureRequest.CONTROL_AE_LOCK, true)
            b.set(CaptureRequest.CONTROL_AWB_LOCK, true)
        }
    }

    private fun clampNs(ns: Long): Long {
        val lim = profile.exposureLimits
        return Math.max(Math.round(lim.minExposureTimeSec * 1e9),
            Math.min(Math.round(lim.maxExposureTimeSec * 1e9), ns))
    }

    private fun clampIso(iso: Int): Int {
        val lim = profile.exposureLimits
        return Math.max(lim.minIso, Math.min(lim.maxIso, iso))
    }

    private fun meteringSubsample(): Int {
        val w = (meteringReader ?: captureReader)?.width ?: return 1
        // Powers of two, because a RAW plane has to stay on its CFA phase.
        var f = 1
        while (w / f > 320 && f < 16) f *= 2
        return f
    }

    // ---------------------------------------------------------------- companion

    companion object {
        private const val TAG = "Hdri360.Camera"
        private const val METERING_TAG = -1
        private const val METERING_PERIOD_MS = 700L

        /** Nine rationals, row major: the matrix that changes nothing. */
        private val IDENTITY_TRANSFORM = android.hardware.camera2.params.ColorSpaceTransform(
            intArrayOf(1, 1, 0, 1, 0, 1,
                       0, 1, 1, 1, 0, 1,
                       0, 1, 0, 1, 1, 1))
        private const val MAX_PENDING = 16

        /**
         * A burst frame that takes longer than this to come back is worth a line
         * in the log. Two seconds: a whole four rung bracket lands in about one,
         * measured, so anything past two is the beginning of a fault and not a
         * slow moment.
         */
        private const val SLOW_FRAME_MS = 2_000L
        /** And one bad burst is one line, not one per rung. */
        private const val SLOW_REPORT_GAP_MS = 1_000L
        /**
         * A frame converts in about 200 ms on a Pixel 9a. Past a second, the
         * camera thread is being held long enough to matter.
         */
        private const val SLOW_CONVERT_MS = 1_000L
        /**
         * Getting a frame out of the camera's buffer is a memcpy and measures
         * around 30 ms; past 300 the buffer itself is the problem, which is a
         * different fault from the arithmetic being slow.
         */
        private const val SLOW_COPY_MS = 300L

        /**
         * Opens a camera and walks down the stream ladder until one configuration
         * is accepted, reporting which rung it landed on.
         *
         * [onReady] receives a source that is configured and previewing; the
         * profile is only knowable at that point, because it depends on which
         * plan the device agreed to.
         */
        /**
         * [lensId] is a [Lens] id, which is not always a camera id.
         *
         * A lens behind a logical camera is `<logical>:<physical>`, and reaching
         * it means opening the parent and naming the physical id on every output
         * configuration - a physical camera generally cannot be opened on its
         * own. What describes it, though, is its *own* characteristics: the 9a's
         * ultrawide is 4208x3120 at 1.84 mm behind a 4000x3000 parent at 4.53 mm,
         * and describing it by the parent would put every reprojection, every
         * radiance scale and every demosaic on the wrong sensor.
         */
        @JvmStatic
        fun open(context: Context, lensId: String, previewTexture: SurfaceTexture?,
                 onReady: (Camera2Source) -> Unit, onFailed: (String) -> Unit) {
            if (context.checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
                onFailed("Camera permission has not been granted")
                return
            }
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            if (manager == null) { onFailed("This device has no camera service"); return }
            val lens = try {
                CameraProbe.lensFor(manager, lensId)
            } catch (e: Exception) {
                onFailed("Could not read the cameras: ${e.message}"); return
            }
            if (lens == null) { onFailed("This phone has no lens $lensId"); return }
            val characteristics = try {
                CameraProbe.characteristicsFor(manager, lens)
            } catch (e: Exception) {
                onFailed("Could not read lens $lensId: ${e.message}"); return
            }
            val plans = StreamLadder.plansFor(CameraProbe.reportFor(characteristics))
            if (plans.isEmpty()) { onFailed("Lens $lensId offers no usable output"); return }

            val thread = HandlerThread("hdri-camera").apply { start() }
            val handler = Handler(thread.looper)
            try {
                manager.openCamera(lens.openId, object : CameraDevice.StateCallback() {
                    private var handed = false

                    override fun onOpened(camera: CameraDevice) {
                        Configurator(context, manager, lens, characteristics, camera, thread, handler,
                            previewTexture, plans,
                            { source -> if (!handed) { handed = true; onReady(source) } },
                            { why ->
                                if (!handed) {
                                    handed = true
                                    try { camera.close() } catch (e: Exception) { }
                                    thread.quitSafely()
                                    onFailed(why)
                                }
                            }).start()
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        try { camera.close() } catch (e: Exception) { }
                        if (!handed) { handed = true; thread.quitSafely(); onFailed("The camera was disconnected") }
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        try { camera.close() } catch (e: Exception) { }
                        if (!handed) { handed = true; thread.quitSafely(); onFailed(errorText(error)) }
                    }
                }, handler)
            } catch (e: Exception) {
                thread.quitSafely()
                onFailed("Could not open the camera: ${e.message}")
            }
        }

        private fun errorText(error: Int): String = when (error) {
            CameraDevice.StateCallback.ERROR_CAMERA_IN_USE ->
                "Another app is using the camera"
            CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE ->
                "Too many cameras are already open"
            CameraDevice.StateCallback.ERROR_CAMERA_DISABLED ->
                "The camera has been disabled by a device policy"
            else -> "The camera reported error $error"
        }
    }

    /**
     * Tries the stream plans in order.
     *
     * onConfigureFailed is not an error, it is an answer: this device will not
     * give us that combination. The predecessor treated it as fatal, which is why
     * it worked on the phone it was written on and nowhere else.
     */
    private class Configurator(
        private val context: Context,
        private val manager: CameraManager,
        private val lens: Lens,
        private val characteristics: CameraCharacteristics,
        private val device: CameraDevice,
        private val thread: HandlerThread,
        private val handler: Handler,
        private val previewTexture: SurfaceTexture?,
        private val plans: List<StreamPlan>,
        private val onReady: (Camera2Source) -> Unit,
        private val onFailed: (String) -> Unit
    ) {
        private var index = 0
        private val refused = ArrayList<String>()

        fun start() = tryNext()

        private fun tryNext() {
            if (index >= plans.size) {
                onFailed("This camera refused every stream combination:\n" + refused.joinToString("\n"))
                return
            }
            val plan = plans[index++]
            val subsample = CameraProbe.subsampleFor(plan.capture)
            var captureReader: ImageReader? = null
            var meteringReader: ImageReader? = null
            try {
                previewTexture?.setDefaultBufferSize(plan.preview.width, plan.preview.height)
                val previewSurface = Surface(previewTexture ?: SurfaceTexture(0).also {
                    it.setDefaultBufferSize(plan.preview.width, plan.preview.height)
                })
                captureReader = ImageReader.newInstance(plan.capture.width, plan.capture.height,
                    CameraProbe.formatOf(plan), MAX_BUFFERED)
                val surfaces = ArrayList<Surface>()
                surfaces.add(previewSurface)
                surfaces.add(captureReader.surface)
                if (plan.metering) {
                    meteringReader = ImageReader.newInstance(plan.preview.width, plan.preview.height,
                        ImageFormat.YUV_420_888, 3)
                    surfaces.add(meteringReader.surface)
                }

                // Named by the lens. A stored session that recorded "0" for a
                // capture shot on the ultrawide behind it could not be resumed on
                // the right lens, and its report would name the wrong optics.
                val profile = CameraProbe.profileFor(lens.id, characteristics, plan, subsample,
                    CameraProbe.describe(plan, subsample))
                val readerForClose = captureReader
                val meteringForClose = meteringReader

                val callback = object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        val source = Camera2Source(context, manager, characteristics, device,
                            thread, handler, previewSurface, previewTexture, plan, subsample, profile)
                        source.session = s
                        source.captureReader = readerForClose
                        source.meteringReader = meteringForClose
                        readerForClose.setOnImageAvailableListener(source::onCaptureImage, handler)
                        meteringForClose?.setOnImageAvailableListener({ r ->
                            val im = try { r.acquireLatestImage() } catch (e: Exception) { null }
                            if (im != null) {
                                try {
                                    if (synchronized(source.lock) { source.meteringEnabled }) {
                                        val luma = FrameConverters.luma(im, source.meteringSubsample())
                                        val rel = source.previewSettings
                                            .relativeExposure(profile.exposureLimits.baseIso)
                                        source.post {
                                            synchronized(source.lock) { source.listener }
                                                ?.onPreviewFrame(luma, rel)
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.w(TAG, "a metering frame was dropped", e)
                                } finally {
                                    im.close()
                                }
                            }
                        }, handler)
                        source.applyPreview()
                        CaptureLog.log("configured: $plan, working at 1/$subsample" +
                            (source.minFrameDurationNs.let {
                                if (it > 0) String.format(java.util.Locale.US,
                                    ", stream floor %.1f ms (%.1f fps)",
                                    it / 1e6, 1e9 / it)
                                else ", the device would not say its frame floor"
                            }))
                        onReady(source)
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        CaptureLog.log("refused: $plan")
                        refused.add("  $plan")
                        try { s.close() } catch (e: Exception) { }
                        readerForClose.close()
                        meteringForClose?.close()
                        previewSurface.release()
                        tryNext()
                    }
                }

                // A lens behind a logical camera is reached by naming its physical
                // id on every output, preview included. Binding only the captures
                // would leave the person aiming a 70 degree viewfinder at a plan
                // drawn for 104 - which is not a smaller mistake than shooting the
                // wrong lens, it is the same mistake made harder to see.
                //
                // If the device refuses a physical binding on one of these
                // surfaces the configuration fails, the ladder tries its next rung
                // and the refusals are reported. That is the honest outcome: some
                // phones will not stream a physical camera to a preview.
                if (lens.isPhysical && Build.VERSION.SDK_INT >= 28) {
                    val pid = lens.physicalId
                    val configs = surfaces.map { surface ->
                        OutputConfiguration(surface).apply { setPhysicalCameraId(pid) }
                    }
                    device.createCaptureSession(SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR, configs,
                        java.util.concurrent.Executor { r -> handler.post(r) }, callback))
                } else {
                    @Suppress("DEPRECATION")   // SessionConfiguration is API 28; minSdk is 26
                    device.createCaptureSession(surfaces, callback, handler)
                }
            } catch (e: CameraAccessException) {
                onFailed("Lost access to the camera while configuring it: ${e.message}")
            } catch (e: Exception) {
                // A size the camera advertised but cannot actually allocate. Not
                // fatal - that is what the next rung of the ladder is for.
                refused.add("  $plan (${e.javaClass.simpleName})")
                captureReader?.close()
                meteringReader?.close()
                tryNext()
            }
        }

        companion object {
            /** Enough for the longest bracket plus the frames still in flight behind it. */
            private const val MAX_BUFFERED = 8
        }
    }
}
