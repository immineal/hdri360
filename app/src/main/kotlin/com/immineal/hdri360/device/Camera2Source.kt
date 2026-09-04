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
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.immineal.hdri360.core.capture.CameraProfile
import com.immineal.hdri360.core.capture.CameraSource
import com.immineal.hdri360.core.capture.CapturedFrame
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
        synchronized(lock) { previewSettings = settings }
        applyPreview()
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
            s.captureBurst(requests, burstCallback, handler)
            true
        } catch (e: Exception) {
            Log.w(TAG, "the bracket could not be submitted", e)
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
        try {
            val b = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(previewSurface)
            meteringReader?.let { if (synchronized(lock) { meteringEnabled }) b.addTarget(it.surface) }
            applyManualSettings(b, settings)
            s.setRepeatingRequest(b.build(), null, handler)
        } catch (e: Exception) {
            report("The preview stopped: ${e.message}", false)
        }
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
            val active = synchronized(lock) { meteringEnabled && burstId == 0L && !closed }
            if (s == null || reader == null || meteringReader != null || !active) {
                if (synchronized(lock) { meteringEnabled && !closed } && meteringReader == null)
                    handler.postDelayed(this, METERING_PERIOD_MS)
                return
            }
            try {
                val b = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                b.addTarget(reader.surface)
                applyManualSettings(b, synchronized(lock) { previewSettings })
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
            if (lockedWhiteBalance == null)
                lockedWhiteBalance = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
            val ts = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
            synchronized(lock) { pendingResults[ts] = result }
            pairAny()
        }
    }

    // ------------------------------------------------------------------- bursts

    private val burstCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest,
                                        result: TotalCaptureResult) {
            if (lockedWhiteBalance == null)
                lockedWhiteBalance = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
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
            Log.w(TAG, "a bracket frame failed, reason ${failure.reason}")
            settle(false)
        }

        override fun onCaptureBufferLost(s: CameraCaptureSession, request: CaptureRequest,
                                         target: Surface, frameNumber: Long) {
            Log.w(TAG, "a bracket frame's buffer was dropped")
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
                val luma = try { FrameConverters.luma(image, meteringSubsample()) } catch (e: Exception) { null }
                image.close()
                if (luma != null) {
                    val rel = settings.relativeExposure(profile.exposureLimits.baseIso)
                    work.execute { synchronized(lock) { listener }?.onPreviewFrame(luma, rel) }
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

            val pixels = convert(image, result)
            image.close()
            work.execute {
                synchronized(lock) { listener }?.onFrameCaptured(frame, pixels)
                settle(true)
            }
        } catch (e: Exception) {
            try { image.close() } catch (ignored: Exception) { }
            Log.w(TAG, "a captured frame could not be converted", e)
            settle(false)
        }
    }

    private fun convert(image: Image, result: TotalCaptureResult): ImageF =
        if (plan.format == PixelFormat.RAW_SENSOR)
            FrameConverters.rawPlane(image, characteristics, result, subsample)
        else
            FrameConverters.rgb(image, subsample)

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
        applyPreview()
        // Raised after the last image, never after the last metadata.
        work.execute {
            synchronized(lock) { listener }?.onBurstFinished(id, target, expected, received)
        }
    }

    private fun discardPendingLocked() {
        for (i in pendingImages.values) try { i.close() } catch (e: Exception) { }
        pendingImages.clear()
        pendingResults.clear()
    }

    private fun report(message: String, fatal: Boolean) {
        val l = synchronized(lock) { listener } ?: return
        work.execute { l.onCameraError(message, fatal) }
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

    private fun applyManualSettings(b: CaptureRequest.Builder, settings: ExposureSettings) {
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
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, Math.max(settings.exposureTimeNs(), 1L))
            lockedWhiteBalance?.let {
                b.set(CaptureRequest.COLOR_CORRECTION_MODE,
                    CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                b.set(CaptureRequest.COLOR_CORRECTION_GAINS, it)
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
        var f = 1
        while (w / f > 320 && f < 16) f *= 2
        return f
    }

    // ---------------------------------------------------------------- companion

    companion object {
        private const val TAG = "Hdri360.Camera"
        private const val METERING_TAG = -1
        private const val METERING_PERIOD_MS = 700L
        private const val MAX_PENDING = 16

        /**
         * Opens a camera and walks down the stream ladder until one configuration
         * is accepted, reporting which rung it landed on.
         *
         * [onReady] receives a source that is configured and previewing; the
         * profile is only knowable at that point, because it depends on which
         * plan the device agreed to.
         */
        @JvmStatic
        fun open(context: Context, cameraId: String, previewTexture: SurfaceTexture?,
                 onReady: (Camera2Source) -> Unit, onFailed: (String) -> Unit) {
            if (context.checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
                onFailed("Camera permission has not been granted")
                return
            }
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            if (manager == null) { onFailed("This device has no camera service"); return }
            val characteristics = try {
                manager.getCameraCharacteristics(cameraId)
            } catch (e: Exception) {
                onFailed("Could not read camera $cameraId: ${e.message}"); return
            }
            val plans = StreamLadder.plansFor(CameraProbe.reportFor(characteristics))
            if (plans.isEmpty()) { onFailed("Camera $cameraId offers no usable output"); return }

            val thread = HandlerThread("hdri-camera").apply { start() }
            val handler = Handler(thread.looper)
            try {
                manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                    private var handed = false

                    override fun onOpened(camera: CameraDevice) {
                        Configurator(context, manager, characteristics, camera, thread, handler,
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

                val profile = CameraProbe.profileFor(device.id, characteristics, plan, subsample,
                    CameraProbe.describe(plan, subsample))
                val readerForClose = captureReader
                val meteringForClose = meteringReader

                @Suppress("DEPRECATION")   // SessionConfiguration is API 28; minSdk is 26
                device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
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
                                        source.work.execute {
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
                        onReady(source)
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        refused.add("  $plan")
                        try { s.close() } catch (e: Exception) { }
                        readerForClose.close()
                        meteringForClose?.close()
                        previewSurface.release()
                        tryNext()
                    }
                }, handler)
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
