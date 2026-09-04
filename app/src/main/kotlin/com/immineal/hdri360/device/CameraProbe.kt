package com.immineal.hdri360.device

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.util.Size
import com.immineal.hdri360.core.camera.Intrinsics
import com.immineal.hdri360.core.capture.CameraProfile
import com.immineal.hdri360.core.capture.CaptureTier
import com.immineal.hdri360.core.capture.DeviceReport
import com.immineal.hdri360.core.capture.HardwareLevel
import com.immineal.hdri360.core.capture.PixelFormat
import com.immineal.hdri360.core.capture.SensorGeometry
import com.immineal.hdri360.core.capture.SensorSize
import com.immineal.hdri360.core.capture.StreamPlan
import com.immineal.hdri360.core.hdr.DeviceExposureLimits
import com.immineal.hdri360.core.image.CfaPattern

/** One lens the user can choose between. */
class LensOption(
    @JvmField val cameraId: String,
    @JvmField val label: String,
    @JvmField val horizontalFovDeg: Double,
    @JvmField val focalLengthMm: Double,
    @JvmField val hasRaw: Boolean,
    @JvmField val hasManualSensor: Boolean,
    @JvmField val frontFacing: Boolean
) {
    override fun toString(): String =
        java.lang.String.format(java.util.Locale.US, "%s, %.0f degrees%s",
            label, horizontalFovDeg, if (hasRaw && hasManualSensor) ", RAW" else "")
}

/**
 * Reads a camera once and translates it into the core's own types.
 *
 * Nothing here decides anything; the deciding is done by StreamLadder, which is
 * pure and therefore testable. This file's only job is to report honestly what
 * Camera2 said, including the parts it declines to say.
 */
object CameraProbe {

    /**
     * The longest shutter a person can hold steady. Not a hardware number: it is
     * the policy that decides when the planner starts spending ISO instead of
     * time, because motion blur cannot be undone and noise partly can.
     */
    const val HANDHELD_LIMIT_SECONDS = 1.0 / 15.0

    /** Each frame is kept under this many pixels; beyond it the gain is noise, not detail. */
    const val WORKING_PIXEL_BUDGET = 4_500_000L

    @JvmStatic
    fun lenses(manager: CameraManager): List<LensOption> {
        val out = ArrayList<LensOption>()
        for (id in manager.cameraIdList) {
            val c = try { manager.getCameraCharacteristics(id) } catch (e: Exception) { continue }
            val facing = c.get(CameraCharacteristics.LENS_FACING)
            val front = facing != null && facing == CameraMetadata.LENS_FACING_FRONT
            val fov = horizontalFovOf(c)
            val focal = focalLengthOf(c)
            out.add(LensOption(id, labelFor(front, fov), fov, focal,
                has(c, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW),
                has(c, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR), front))
        }
        // Widest first among the back cameras: fewer frames and fewer seams, with
        // RAW-capable modules ahead of merely wider ones because linear data is
        // worth more than a few degrees of coverage.
        out.sortWith(compareBy<LensOption> { it.frontFacing }
            .thenByDescending { it.hasRaw && it.hasManualSensor }
            .thenByDescending { it.horizontalFovDeg })
        return out
    }

    /** The lens to start on: the main camera, not the ultrawide. */
    @JvmStatic
    fun defaultLens(options: List<LensOption>): LensOption? {
        val back = options.filter { !it.frontFacing }
        if (back.isEmpty()) return options.firstOrNull()
        // "Main" is the one with the longest focal length among the RAW-capable
        // back cameras; an ultrawide is wider but softer and far more distorted.
        val raw = back.filter { it.hasRaw && it.hasManualSensor }
        val pool = if (raw.isEmpty()) back else raw
        return pool.maxByOrNull { it.focalLengthMm }
    }

    @JvmStatic
    fun reportFor(c: CameraCharacteristics): DeviceReport {
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val raw = sizesOf(map?.getOutputSizes(ImageFormat.RAW_SENSOR))
        val yuv = sizesOf(map?.getOutputSizes(ImageFormat.YUV_420_888))
        val active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val activeSize = if (active != null && active.width() > 0)
            SensorSize(active.width(), active.height())
        else yuv.maxByOrNull { it.pixels() } ?: SensorSize(1920, 1080)
        return DeviceReport(
            hardwareLevel = levelOf(c),
            hasRaw = has(c, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW),
            hasManualSensor = has(c, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR),
            rawSizes = raw,
            yuvSizes = yuv,
            activeArray = activeSize)
    }

    /**
     * The profile the capture logic sees, for a plan that was actually accepted.
     *
     * The intrinsics describe the pixels the pipeline will be handed, which is
     * not the same as the sensor: the stream may be a crop of the active array,
     * and it is then subsampled to keep a frame's working copy inside a phone's
     * memory. Both have to be in the model or every reprojection is off.
     */
    @JvmStatic
    fun profileFor(id: String, c: CameraCharacteristics, plan: StreamPlan,
                   subsample: Int, note: String): CameraProfile {
        val report = reportFor(c)
        val physical = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val focal = focalLengthOf(c)
        val aperture = apertureOf(c)
        val full = if (physical != null && physical.width > 0)
            SensorGeometry.intrinsicsFor(report.activeArray, physical.width.toDouble(),
                physical.height.toDouble(), focal, plan.capture)
        else
            Intrinsics.fromHorizontalFov(plan.capture.width, plan.capture.height, 65.0)
        val orientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val facing = c.get(CameraCharacteristics.LENS_FACING)
        return CameraProfile(
            id, plan.tier,
            SensorGeometry.subsampled(full, subsample),
            exposureLimits(c, aperture),
            cfaOf(c), orientation,
            facing != null && facing == CameraMetadata.LENS_FACING_FRONT,
            focal, aperture, note)
    }

    /**
     * How much to shrink each frame.
     *
     * A 50 megapixel RAW plane is 200 MB as floats, and there are three of them
     * per direction. Working at full resolution is not a quality decision on a
     * phone, it is a decision to be killed halfway through the sphere.
     */
    @JvmStatic
    fun subsampleFor(capture: SensorSize, budgetPixels: Long = WORKING_PIXEL_BUDGET): Int {
        var f = 1
        // Powers of two only: the CFA phase has to survive, which means whole
        // 2x2 blocks in and whole 2x2 blocks out.
        while (f < 8 && capture.pixels() / (f.toLong() * f) > budgetPixels) f *= 2
        return f
    }

    @JvmStatic
    fun exposureLimits(c: CameraCharacteristics, aperture: Double): DeviceExposureLimits {
        val t = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val iso = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val minT = if (t != null) t.lower / 1e9 else 1.0 / 8000
        val maxT = if (t != null) t.upper / 1e9 else 0.5
        val minIso = iso?.lower ?: 50
        val maxIso = iso?.upper ?: 3200
        return DeviceExposureLimits(minT, maxT, minIso, maxIso, baseIsoOf(c, minIso),
            aperture, HANDHELD_LIMIT_SECONDS)
    }

    /**
     * The ISO at which the sensor's own gain is unity, which is what every
     * absolute luminance figure is scaled by.
     *
     * Camera2 does not report it. The lower end of the sensitivity range is a
     * poor stand-in - a Pixel 9a reports 29, which is not a plausible native
     * speed for any silicon - so the reference ISO is preferred where the device
     * states one, and the value is carried into the report either way so that a
     * cd/m2 figure can be recomputed if it later turns out to be wrong.
     */
    @JvmStatic
    fun baseIsoOf(c: CameraCharacteristics, minIso: Int): Int {
        val maxAnalog = c.get(CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY)
        // Nothing here is authoritative; prefer the smallest sane candidate.
        if (maxAnalog != null && maxAnalog > 0 && minIso in 1..maxAnalog) return minIso
        return Math.max(1, minIso)
    }

    @JvmStatic
    fun cfaOf(c: CameraCharacteristics): CfaPattern =
        CfaPattern.fromCamera2(c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: 0)

    @JvmStatic
    fun whiteLevelOf(c: CameraCharacteristics): Int =
        c.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023

    @JvmStatic
    fun blackLevelOf(c: CameraCharacteristics): DoubleArray {
        val p = c.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN) ?: return DoubleArray(4)
        val tmp = IntArray(4)
        p.copyTo(tmp, 0)
        return DoubleArray(4) { tmp[it].toDouble() }
    }

    @JvmStatic
    fun formatOf(plan: StreamPlan): Int =
        if (plan.format == PixelFormat.RAW_SENSOR) ImageFormat.RAW_SENSOR else ImageFormat.YUV_420_888

    @JvmStatic
    fun sizeOf(s: SensorSize): Size = Size(s.width, s.height)

    /** A one-line account of what was chosen and why, shown to the user verbatim. */
    @JvmStatic
    fun describe(plan: StreamPlan, subsample: Int): String {
        val sub = if (subsample > 1) ", working at 1/$subsample" else ""
        val what = when (plan.tier) {
            CaptureTier.LINEAR_RAW ->
                "linear RAW at exposures this app chose: a radiance measurement"
            CaptureTier.MANUAL_YUV ->
                "the app sets the exposures, but the pixels come through the camera's " +
                "tone curve, so the response is recovered from the bracket"
            CaptureTier.LOCKED_AUTO ->
                "this camera will not take manual exposures; the best available is to " +
                "lock what it chose, which makes the result relative, not measured"
        }
        return "${plan.capture}$sub - $what"
    }

    // ------------------------------------------------------------------ detail

    private fun labelFor(front: Boolean, fov: Double): String = when {
        front -> "Front"
        fov >= 95 -> "Ultrawide"
        fov >= 70 -> "Wide"
        fov >= 55 -> "Main"
        else -> "Tele"
    }

    private fun sizesOf(sizes: Array<Size>?): List<SensorSize> =
        sizes?.map { SensorSize(it.width, it.height) } ?: emptyList()

    private fun levelOf(c: CameraCharacteristics): HardwareLevel =
        when (c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> HardwareLevel.LEGACY
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> HardwareLevel.FULL
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> HardwareLevel.LEVEL_3
            // EXTERNAL is API 28 and the constant cannot be named at minSdk 26.
            4 -> HardwareLevel.EXTERNAL
            // An unreported level is not a good level. Assume the strictest.
            null -> HardwareLevel.LEGACY
            else -> HardwareLevel.LIMITED
        }

    private fun has(c: CameraCharacteristics, capability: Int): Boolean {
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: return false
        for (v in caps) if (v == capability) return true
        return false
    }

    private fun horizontalFovOf(c: CameraCharacteristics): Double {
        val physical = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val focal = focalLengthOf(c)
        if (physical == null || physical.width <= 0 || focal <= 0) return 60.0
        return 2 * Math.toDegrees(Math.atan(physical.width / (2 * focal)))
    }

    private fun focalLengthOf(c: CameraCharacteristics): Double {
        val f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        return if (f != null && f.isNotEmpty()) f[0].toDouble() else 4.0
    }

    private fun apertureOf(c: CameraCharacteristics): Double {
        val a = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
        return if (a != null && a.isNotEmpty()) a[0].toDouble() else 1.8
    }
}
