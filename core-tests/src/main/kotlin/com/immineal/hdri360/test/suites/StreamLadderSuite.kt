package com.immineal.hdri360.test.suites

import com.immineal.hdri360.core.capture.CaptureTier
import com.immineal.hdri360.core.capture.DeviceReport
import com.immineal.hdri360.core.capture.HardwareLevel
import com.immineal.hdri360.core.capture.PixelFormat
import com.immineal.hdri360.core.capture.SensorGeometry
import com.immineal.hdri360.core.capture.SensorSize
import com.immineal.hdri360.core.capture.StreamLadder
import com.immineal.hdri360.test.TestCase
import com.immineal.hdri360.test.TestKit

/**
 * What the app does when the camera says no.
 *
 * The predecessor's answer to onConfigureFailed was to give up, which on any
 * device whose stream combinations differ from the one it was written on means
 * the app simply does not work. The requirement is the opposite: run on as many
 * phones as possible, and say plainly which tier it managed.
 *
 * This is the one part of the camera layer that can be tested without a camera,
 * so it is worth making it the part that carries the device-compatibility logic.
 */
class StreamLadderSuite : TestCase {

    override fun name(): String = "streams"

    override fun run(t: TestKit) {
        flagshipGetsTheRealThing(t)
        everyDeviceGetsSomething(t)
        theLadderOnlyEverDegrades(t)
        legacyStaysWithinItsLimits(t)
        aspectCropDoesNotWidenTheLens(t)
        subsamplingKeepsTheFieldOfView(t)
        thePreviewIsTheFrameBeingCaptured(t)
    }

    /**
     * The preview has to be the captured frame scaled down, not a crop of it.
     *
     * A 16:9 preview of a 4:3 capture shows a different field of view from the
     * one being recorded, so every target drawn on it is in the wrong place and
     * the edges of what is actually being captured are off screen entirely. The
     * device offers both shapes and nothing but this makes it pick the right one.
     */
    private fun thePreviewIsTheFrameBeingCaptured(t: TestKit) {
        for (report in all()) {
            for (plan in StreamLadder.plansFor(report)) {
                val capture = plan.capture.width.toDouble() / plan.capture.height
                val preview = plan.preview.width.toDouble() / plan.preview.height
                val offered = report.yuvSizes.any { it.height > 0 &&
                    Math.abs(it.width.toDouble() / it.height - capture) < 0.02 &&
                    it.width <= 1920 }
                if (!offered) continue
                t.near(capture, preview, 0.02,
                    "the preview is the shape of what is being captured ($plan)")
            }
        }
        // The device that offers the wrong shape first: 16:9 sits closest to the
        // preview's target width, and would win on width alone.
        val tempting = DeviceReport(
            hardwareLevel = HardwareLevel.LEVEL_3,
            hasRaw = true, hasManualSensor = true,
            rawSizes = listOf(SensorSize(4000, 3000)),
            yuvSizes = listOf(SensorSize(4000, 3000), SensorSize(1280, 720),
                SensorSize(1024, 768), SensorSize(640, 360)),
            activeArray = SensorSize(4000, 3000))
        val best = StreamLadder.plansFor(tempting).first()
        t.eq(1024L, best.preview.width.toLong(),
            "a 4:3 preview is chosen over a wider one that is closer to the target width")
        t.note("preview for a 4:3 sensor offering 1280x720: " + best.preview)
    }

    private val pixelYuv = listOf(
        SensorSize(4080, 3072), SensorSize(4080, 2296), SensorSize(1920, 1080),
        SensorSize(1280, 960), SensorSize(640, 480))

    private fun pixelLike() = DeviceReport(
        hardwareLevel = HardwareLevel.LEVEL_3,
        hasRaw = true, hasManualSensor = true,
        rawSizes = listOf(SensorSize(4080, 3072)),
        yuvSizes = pixelYuv,
        activeArray = SensorSize(4080, 3072))

    private fun midRange() = DeviceReport(
        hardwareLevel = HardwareLevel.LIMITED,
        hasRaw = false, hasManualSensor = true,
        rawSizes = emptyList(),
        yuvSizes = listOf(SensorSize(4000, 3000), SensorSize(1920, 1080), SensorSize(640, 480)),
        activeArray = SensorSize(4000, 3000))

    private fun cheapest() = DeviceReport(
        hardwareLevel = HardwareLevel.LEGACY,
        hasRaw = false, hasManualSensor = false,
        rawSizes = emptyList(),
        yuvSizes = listOf(SensorSize(1600, 1200), SensorSize(640, 480)),
        activeArray = SensorSize(1600, 1200))

    /** One usable size, no capabilities at all: the worst device that still has a camera. */
    private fun barelyACamera() = DeviceReport(
        hardwareLevel = HardwareLevel.LEGACY,
        hasRaw = false, hasManualSensor = false,
        rawSizes = emptyList(),
        yuvSizes = listOf(SensorSize(640, 480)),
        activeArray = SensorSize(640, 480))

    /** A device that claims RAW but offers no RAW sizes. Devices do lie. */
    private fun lyingAboutRaw() = DeviceReport(
        hardwareLevel = HardwareLevel.FULL,
        hasRaw = true, hasManualSensor = true,
        rawSizes = emptyList(),
        yuvSizes = listOf(SensorSize(4000, 3000), SensorSize(1280, 720)),
        activeArray = SensorSize(4000, 3000))

    private fun all() = listOf(pixelLike(), midRange(), cheapest(), barelyACamera(), lyingAboutRaw())

    private fun namesOf(r: DeviceReport) = StreamLadder.plansFor(r)

    /** The best device must actually get the measurement, not a safe fallback. */
    private fun flagshipGetsTheRealThing(t: TestKit) {
        val plans = namesOf(pixelLike())
        t.greaterThan(plans.size.toDouble(), 1.0, "a capable device gets fallbacks as well as a first choice")
        val first = plans[0]
        t.eq(CaptureTier.LINEAR_RAW, first.tier, "and its first choice is a radiance measurement")
        t.eq(PixelFormat.RAW_SENSOR, first.format, "shot as RAW")
        t.eq(4080L, first.capture.width.toLong(), "at the sensor's full width")
        t.check(!first.metering,
            "and asks for no YUV metering stream: metering a tone curve would defeat the tier, " +
            "so the scan meters from RAW through the capture stream")
        t.check(first.streamCount() <= 2, "which also leaves it inside two streams")
        t.check(first.preview.width <= 1920,
            "and a preview no larger than a screen, so it costs nothing to run")

        // The tier is about who controls the exposure, not about the stream shape.
        for (p in plans)
            if (p.format == PixelFormat.YUV_420_888)
                t.eq(CaptureTier.MANUAL_YUV, p.tier,
                    "a manual device without RAW is still driving its own exposure")
    }

    /** The hard requirement: no device is left with nothing to try. */
    private fun everyDeviceGetsSomething(t: TestKit) {
        for (r in all()) {
            val plans = namesOf(r)
            t.greaterThan(plans.size.toDouble(), 0.0,
                "${r.hardwareLevel} with ${r.yuvSizes.size} sizes still gets a plan")
            for (p in plans) {
                val offered = if (p.format == PixelFormat.RAW_SENSOR) r.rawSizes else r.yuvSizes
                t.check(offered.any { it.width == p.capture.width && it.height == p.capture.height },
                    "every plan asks only for a size the camera actually offers")
                t.check(r.yuvSizes.any { it.width == p.preview.width && it.height == p.preview.height },
                    "and a preview size it offers too")
                if (p.format == PixelFormat.RAW_SENSOR)
                    t.check(r.hasRaw && r.hasManualSensor,
                        "RAW is only planned where it would be linear and driveable")
            }
        }
        val last = namesOf(pixelLike()).last()
        t.eq(false, last.metering, "the final fallback asks for the fewest streams")
        t.eq(PixelFormat.YUV_420_888, last.format, "and the most widely supported format")
        t.check(namesOf(pixelLike()).none { it.format == PixelFormat.RAW_SENSOR && it.metering },
            "no RAW plan ever asks for a metering stream")
    }

    /** A fallback that asks for more than the thing that just failed is not a fallback. */
    private fun theLadderOnlyEverDegrades(t: TestKit) {
        for (r in all()) {
            val plans = namesOf(r)
            for (i in 1 until plans.size) {
                val a = plans[i - 1]
                val b = plans[i]
                t.check(b.tier.ordinal >= a.tier.ordinal,
                    "step $i of the ${r.hardwareLevel} ladder never claims a better tier " +
                    "than one that has already failed")
                if (b.tier == a.tier) {
                    t.check(b.streamCount() <= a.streamCount(),
                        "and within a tier, step $i does not ask for more streams")
                    if (b.streamCount() == a.streamCount() && b.format == a.format)
                        t.check(b.capture.pixels() < a.capture.pixels(),
                            "an otherwise identical step at least asks for fewer pixels")
                }
            }
            val distinct = plans.map { "${it.format}${it.capture}${it.preview}${it.metering}" }.toSet()
            t.eq(plans.size.toLong(), distinct.size.toLong(),
                "the ${r.hardwareLevel} ladder never tries the same thing twice")
        }
    }

    /**
     * LEGACY devices guarantee two streams and nothing more. Asking for three is
     * how an app becomes "does not work on half of Android".
     */
    private fun legacyStaysWithinItsLimits(t: TestKit) {
        for (r in listOf(cheapest(), barelyACamera())) {
            for (p in namesOf(r)) {
                t.check(p.streamCount() <= 2, "a LEGACY plan stays within two streams")
                t.check(!p.metering, "which means no separate metering stream")
                t.eq(CaptureTier.LOCKED_AUTO, p.tier,
                    "and the tier says plainly that the camera chose the exposure")
            }
        }
        val plans = namesOf(barelyACamera())
        t.eq(1L, plans.size.toLong(),
            "with one size on offer there is exactly one thing to try, not zero")
        t.eq(640L, plans[0].capture.width.toLong(), "and it is that size")
    }

    /**
     * A 16:9 output from a 4:3 sensor is a crop, not a wider lens. Using the full
     * physical width against a cropped frame inflates the focal length and puts
     * every reprojection off by degrees.
     */
    private fun aspectCropDoesNotWidenTheLens(t: TestKit) {
        val active = SensorSize(4000, 3000)
        val wideMm = 6.4
        val highMm = 4.8
        val focal = 4.44

        val full = SensorGeometry.intrinsicsFor(active, wideMm, highMm, focal, SensorSize(4000, 3000))
        val wide = SensorGeometry.intrinsicsFor(active, wideMm, highMm, focal, SensorSize(4000, 2250))
        val square = SensorGeometry.intrinsicsFor(active, wideMm, highMm, focal, SensorSize(3000, 3000))

        t.near(full.horizontalFovDeg(), wide.horizontalFovDeg(), 1e-9,
            "cropping to 16:9 takes nothing off the horizontal field of view")
        t.lessThan(wide.verticalFovDeg(), full.verticalFovDeg() - 5.0,
            "it takes it off the top and bottom, which is where the crop is")
        t.lessThan(square.horizontalFovDeg(), full.horizontalFovDeg() - 5.0,
            "and a square crop narrows the horizontal one instead")
        t.near(full.fx, wide.fx, 1e-9, "the same optics give the same focal length in pixels")
        t.near(wide.fy, wide.fx, 1e-9, "square pixels stay square through the crop")

        val naive = com.immineal.hdri360.core.camera.Intrinsics.fromSensor(
            4000, 2250, wideMm, highMm, focal)
        t.greaterThan(Math.abs(naive.verticalFovDeg() - wide.verticalFovDeg()), 5.0,
            "ignoring the crop, as the predecessor did, is wrong by more than five degrees")
    }

    /** Working at half resolution must change the pixels, not the field of view. */
    private fun subsamplingKeepsTheFieldOfView(t: TestKit) {
        val active = SensorSize(4000, 3000)
        val full = SensorGeometry.intrinsicsFor(active, 6.4, 4.8, 4.44, SensorSize(4000, 3000))
        val half = SensorGeometry.subsampled(full, 2)
        t.eq(2000L, half.width.toLong(), "halving gives half the width")
        t.eq(1500L, half.height.toLong(), "and half the height")
        t.near(full.horizontalFovDeg(), half.horizontalFovDeg(), 1e-9,
            "subsampling is not a crop: the field of view is untouched")
        t.near(full.fx / 2, half.fx, 1e-12, "the focal length scales with the pixels")
        t.near((half.width - 1) / 2.0, half.cx, 1e-12,
            "and the principal point lands on the centre of the smaller frame, not beside it")

        val quarter = SensorGeometry.subsampled(full, 4)
        t.near(full.horizontalFovDeg(), quarter.horizontalFovDeg(), 1e-9,
            "and at any factor that divides the frame")
        val third = SensorGeometry.subsampled(full, 3)
        t.lessThan(Math.abs(full.horizontalFovDeg() - third.horizontalFovDeg()), 0.02,
            "a factor that does not divide it loses only the truncated edge")
        t.eq(full.width.toLong(), SensorGeometry.subsampled(full, 1).width.toLong(),
            "a factor of one is the identity")
    }
}
