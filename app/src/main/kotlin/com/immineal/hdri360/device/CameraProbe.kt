package com.immineal.hdri360.device

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.util.Size
import com.immineal.hdri360.core.camera.Intrinsics
import com.immineal.hdri360.core.capture.CameraProfile
import com.immineal.hdri360.core.capture.CaptureTier
import com.immineal.hdri360.core.capture.DeviceReport
import com.immineal.hdri360.core.capture.HardwareLevel
import com.immineal.hdri360.core.capture.Lens
import com.immineal.hdri360.core.capture.LensChooser
import com.immineal.hdri360.core.capture.PixelFormat
import com.immineal.hdri360.core.capture.SensorGeometry
import com.immineal.hdri360.core.capture.SensorSize
import com.immineal.hdri360.core.capture.StreamLadder
import com.immineal.hdri360.core.capture.StreamPlan
import com.immineal.hdri360.core.hdr.DeviceExposureLimits
import com.immineal.hdri360.core.image.CfaPattern

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
     *
     * It was 1/15, and nobody had checked whether a hand holds 1/15. Measured on
     * a real 34-direction capture, comparing each rung against the rung below it
     * on identical blocks with the noise floor and the clipped highlights
     * excluded from both, the edge contrast of the rung reaching each shutter
     * came out:
     *
     *     1/2340 s   0.988      1/305 s   0.949
     *     1/40 s     0.857      1/15 s    0.808
     *
     * Monotone, and every figure is a lower bound: the long rungs are shot at
     * higher ISO and noise raises edge contrast. So a hand loses about a fifth of
     * its detail at 1/15 - and the merge, being an inverse-variance mean over
     * exposure squared, gives that rung some fifty times the weight of the one
     * below it in the shadows, which is the only place both are unsaturated and
     * exactly what the long rung is for. The smeared frame dominated precisely
     * where it was used.
     *
     * 1/30 removes about a fifth of that loss for one stop of gain - the
     * brightest rung of that capture becomes 1/30 s at ISO 164 instead of 1/15 s
     * at ISO 82, against a sensor that reaches 7276. There is no knee in the
     * curve to aim at: 0.032 of contrast per stop, all the way. This is the
     * cautious end of a smooth trade, chosen deliberately over 1/60 because
     * shadow noise is what the long rung exists to fight.
     */
    const val HANDHELD_LIMIT_SECONDS = 1.0 / 30.0

    /** Each frame is kept under this many pixels; beyond it the gain is noise, not detail. */
    const val WORKING_PIXEL_BUDGET = 4_500_000L

    /**
     * Every lens on the device, including the ones that are not cameras.
     *
     * A modern phone does not present its lenses as cameras. On a Pixel 9a
     * `cameraIdList` returns 0 and 1; camera 0 reports LOGICAL_MULTI_CAMERA and
     * lists physical ids 2 and 3, and physical 3 is a 1.84 mm module at 104
     * degrees with RAW and a manual sensor - the best lens on the phone for this
     * job, and one the app never offered. Walking only the listed cameras is what
     * made a sphere 34 directions when it could have been 21.
     *
     * Ordering, duplicate collapsing and the choice of default all live in
     * [LensChooser], in the core, where they are tested against exactly these
     * numbers. This function's only job is to report what Camera2 said.
     */
    @JvmStatic
    fun lenses(manager: CameraManager): List<Lens> {
        val found = ArrayList<Lens>()
        for (id in manager.cameraIdList) {
            val c = try { manager.getCameraCharacteristics(id) } catch (e: Exception) { continue }
            found.add(lensOf(id, id, null, c))
            // What sits behind a logical camera. Available from API 28, which is
            // also the first release that can bind a stream to one - so a phone
            // too old to name them is also too old to use them.
            if (Build.VERSION.SDK_INT < 28) continue
            val physicals = try { c.physicalCameraIds } catch (e: Exception) { emptySet<String>() }
            for (pid in physicals) {
                val pc = try { manager.getCameraCharacteristics(pid) } catch (e: Exception) { continue }
                found.add(lensOf("$id:$pid", id, pid, pc))
            }
        }
        return LensChooser.offer(found)
    }

    /** One lens by the id the UI and the stored sessions use. */
    @JvmStatic
    fun lensFor(manager: CameraManager, id: String): Lens? =
        lenses(manager).firstOrNull { it.id == id }

    /**
     * The characteristics that describe a lens, which for a physical camera are
     * its own and not its parent's.
     *
     * Getting this wrong is not subtle: the 9a's ultrawide is 4208x3120 behind a
     * 4000x3000 parent, with a different focal length, a different sensor size
     * and its own black and white levels. Describing it by the logical camera
     * would put every reprojection, every radiance scale and every demosaic on
     * the wrong sensor.
     */
    @JvmStatic
    fun characteristicsFor(manager: CameraManager, lens: Lens): CameraCharacteristics =
        manager.getCameraCharacteristics(lens.physicalId ?: lens.openId)

    private fun lensOf(id: String, openId: String, physicalId: String?,
                       c: CameraCharacteristics): Lens {
        val facing = c.get(CameraCharacteristics.LENS_FACING)
        val front = facing != null && facing == CameraMetadata.LENS_FACING_FRONT
        // The tier is what the stream ladder can actually reach on this lens,
        // rather than what its capability flags advertise: a camera can claim RAW
        // and still refuse every stream combination that would deliver it.
        val report = reportFor(c)
        val tier = StreamLadder.plansFor(report).firstOrNull()?.tier ?: CaptureTier.LOCKED_AUTO
        return Lens(id, openId, physicalId, front, focalLengthOf(c),
            horizontalFovOf(c), verticalFovOf(c), tier, report.activeArray)
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

    @JvmStatic
    fun horizontalFovOf(c: CameraCharacteristics): Double {
        val physical = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val focal = focalLengthOf(c)
        if (physical == null || physical.width <= 0 || focal <= 0) return 60.0
        return 2 * Math.toDegrees(Math.atan(physical.width / (2 * focal)))
    }

    /**
     * The other half of the field of view, which the capture plan needs.
     *
     * A sphere is tiled by rings whose spacing comes from the vertical extent and
     * whose azimuth step comes from the horizontal one - and a phone is held
     * sideways to its sensor, so which is which swaps. Reporting only the
     * horizontal number would leave the plan to guess the aspect ratio.
     */
    @JvmStatic
    fun verticalFovOf(c: CameraCharacteristics): Double {
        val physical = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val focal = focalLengthOf(c)
        if (physical == null || physical.height <= 0 || focal <= 0) return 45.0
        return 2 * Math.toDegrees(Math.atan(physical.height / (2 * focal)))
    }

    @JvmStatic
    fun focalLengthOf(c: CameraCharacteristics): Double {
        val f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        return if (f != null && f.isNotEmpty()) f[0].toDouble() else 4.0
    }

    private fun apertureOf(c: CameraCharacteristics): Double {
        val a = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
        return if (a != null && a.isNotEmpty()) a[0].toDouble() else 1.8
    }
}
