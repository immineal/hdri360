package com.immineal.hdri360.core.capture

import com.immineal.hdri360.core.camera.Intrinsics
import com.immineal.hdri360.core.hdr.DeviceExposureLimits
import com.immineal.hdri360.core.hdr.ExposureSettings
import com.immineal.hdri360.core.image.CfaPattern
import com.immineal.hdri360.core.image.ImageF
import com.immineal.hdri360.core.math.Mat3

/**
 * How honest a capture this device can actually produce.
 *
 * The distinction is not cosmetic. Only the top tier is a measurement: pixels
 * that are a linear fraction of full well, at a shutter and ISO the app itself
 * chose. Everything below is a reconstruction of varying confidence, and the app
 * has to say which one it did rather than let the output imply the best case.
 */
enum class CaptureTier {
    /** RAW plus manual sensor: linear data, known exposure, absolute radiance possible. */
    LINEAR_RAW,

    /**
     * Manual sensor but no RAW. Exposure is ours and the bracket is real, but the
     * pixels came through the camera's own tone curve, so the response has to be
     * recovered from the bracket before any of it is radiance.
     */
    MANUAL_YUV,

    /**
     * Neither. The camera chooses, and the best that can be done is to lock what
     * it chose so every frame at least shares one setting. Usable, and not a
     * measurement.
     */
    LOCKED_AUTO;

    val measuresRadiance: Boolean get() = this == LINEAR_RAW
    /** Whether the app drives the exposure of each frame, rather than the camera. */
    val drivesExposure: Boolean get() = this != LOCKED_AUTO
}

/**
 * Everything the capture logic needs to know about a camera, in the core's own
 * types.
 *
 * Deliberately not CameraCharacteristics. Keeping the platform out of here is
 * what lets the whole capture state machine be exercised on a bare JVM against
 * a recorded session, which is the only way the failure paths - a disconnect
 * mid-burst, a dropped frame, the process being killed at frame 140 - ever get
 * tested at all.
 */
class CameraProfile(
    @JvmField val id: String,
    @JvmField val tier: CaptureTier,
    @JvmField val intrinsics: Intrinsics,
    @JvmField val exposureLimits: DeviceExposureLimits,
    @JvmField val cfa: CfaPattern,
    /** Clockwise rotation making a captured frame upright in the natural orientation. */
    @JvmField val sensorOrientationDeg: Int,
    @JvmField val frontFacing: Boolean,
    @JvmField val focalLengthMm: Double,
    @JvmField val apertureN: Double,
    /** Human-readable account of which tier was chosen and why. */
    @JvmField val note: String
)

/** One frame as it comes off the camera, with what it was actually taken at. */
class CapturedFrame(
    /**
     * Which burst this belongs to, as issued by the controller.
     *
     * The target index alone is not enough to place a frame. A burst that is
     * abandoned and retried produces two bursts for the same direction, and a
     * straggler from the first is otherwise indistinguishable from a frame of
     * the second - so it would be counted toward a burst it was never part of.
     */
    @JvmField val burstId: Long,
    @JvmField val targetIndex: Int,
    @JvmField val bracketIndex: Int,
    /**
     * What the sensor actually used, read back from the capture result - not what
     * was asked for. On a device that cannot be driven manually those are two
     * different things, and recording the request would be recording a fiction.
     */
    @JvmField val settings: ExposureSettings,
    /** Device pose at the moment of capture, or null if there was no fix. */
    @JvmField val poseAtCapture: Mat3?,
    @JvmField val timestampNs: Long,
    /** True when the pixels are a linear sensor fraction rather than tone-mapped. */
    @JvmField val linear: Boolean
)

/**
 * A camera the capture logic can drive.
 *
 * Implemented by Camera2 on a device, and by a replay of a recorded session
 * everywhere else. Every callback carries the identity of what it refers to:
 * the original code paired images to their metadata by arrival order, and a
 * single out-of-order arrival silently mispaired an entire burst.
 */
interface CameraSource {

    val profile: CameraProfile

    /** Called on whichever thread the implementation uses; the controller serialises. */
    interface Listener {
        /** A preview frame for metering. Single channel luma, already subsampled. */
        fun onPreviewFrame(luma: ImageF, relativeExposure: Double)

        /**
         * One frame of a bracket. [frame] identifies which, so nothing depends on
         * the order these arrive in.
         */
        fun onFrameCaptured(frame: CapturedFrame, pixels: ImageF)

        /**
         * The burst for [targetIndex] is over, with [received] of [requested]
         * frames delivered. Raised after the last image, never after the last
         * metadata: those can arrive before the pixels they describe, and
         * completing on them drops the tail of every burst.
         */
        fun onBurstFinished(burstId: Long, targetIndex: Int, requested: Int, received: Int)

        /** Something went wrong. [fatal] means the camera is gone, not just this burst. */
        fun onCameraError(message: String, fatal: Boolean)
    }

    fun setListener(listener: Listener?)

    /** Continuous preview at these settings; also how metering drives exposure. */
    fun startPreview(settings: ExposureSettings)

    /** Whether preview frames are delivered to the listener. Metering is expensive. */
    fun setPreviewMeteringEnabled(enabled: Boolean)

    /**
     * Shoots one bracket for [targetIndex] as a burst, so the frames come back
     * with the shortest window in which the device can move between exposures
     * that are supposed to share a pose.
     *
     * [burstId] must be echoed back on every frame and on completion, so that
     * frames from a burst the controller has already given up on can be told
     * apart from those of its replacement.
     *
     * @return false if a burst is already in flight or the camera is not ready.
     */
    fun captureBracket(burstId: Long, targetIndex: Int, rungs: List<ExposureSettings>): Boolean

    fun close()
}
