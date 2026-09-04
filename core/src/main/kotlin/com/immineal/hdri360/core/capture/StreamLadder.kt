package com.immineal.hdri360.core.capture

import com.immineal.hdri360.core.camera.Intrinsics

/** An output size the camera offers. */
class SensorSize(@JvmField val width: Int, @JvmField val height: Int) {
    fun pixels(): Long = width.toLong() * height
    fun aspect(): Double = width.toDouble() / height
    fun sameAs(o: SensorSize): Boolean = width == o.width && height == o.height
    override fun toString(): String = "${width}x$height"
}

enum class PixelFormat { RAW_SENSOR, YUV_420_888 }

/**
 * INFO_SUPPORTED_HARDWARE_LEVEL, which is the only honest predictor of how many
 * streams a device will accept at once.
 */
enum class HardwareLevel(@JvmField val rank: Int) {
    LEGACY(0),
    /** A USB or otherwise external camera. Behaves like LIMITED and lies about sizes. */
    EXTERNAL(1),
    LIMITED(1),
    FULL(2),
    LEVEL_3(3);

    /** LEGACY guarantees two streams and no more. Asking for three simply fails. */
    fun allowsThreeStreams(): Boolean = rank >= 1
}

/** What a camera says it can do, in the core's own types. */
class DeviceReport(
    @JvmField val hardwareLevel: HardwareLevel,
    @JvmField val hasRaw: Boolean,
    @JvmField val hasManualSensor: Boolean,
    @JvmField val rawSizes: List<SensorSize>,
    @JvmField val yuvSizes: List<SensorSize>,
    @JvmField val activeArray: SensorSize
)

/** One stream configuration to try, and what it would mean if it worked. */
class StreamPlan(
    @JvmField val tier: CaptureTier,
    @JvmField val format: PixelFormat,
    @JvmField val capture: SensorSize,
    @JvmField val preview: SensorSize,
    /** Whether a separate stream is asked for so metering can run during preview. */
    @JvmField val metering: Boolean,
    @JvmField val why: String
) {
    /** Preview surface, capture reader, and optionally a metering reader. */
    fun streamCount(): Int = if (metering) 3 else 2

    override fun toString(): String =
        "$format $capture preview $preview${if (metering) " + metering" else ""} -> $tier"
}

/**
 * The order in which stream configurations are tried until one is accepted.
 *
 * The predecessor's answer to onConfigureFailed was to give up, which means the
 * app works on the device it was written on and nowhere else. Camera2's
 * guarantees are stated per hardware level and are not generous: LEGACY promises
 * two streams, and a great many shipped phones are LEGACY.
 *
 * So the app asks for the best thing first and walks down until something is
 * accepted, and it says which rung it landed on rather than letting the output
 * imply the best case. The walk only ever descends - a fallback that asks for
 * more than the configuration that just failed is not a fallback.
 *
 * This is the one part of the camera layer that can be exercised without a
 * camera, which is exactly why the device-compatibility logic lives here rather
 * than tangled into the Camera2 callbacks.
 */
object StreamLadder {

    /** Roughly a screen. Larger previews cost bandwidth and buy nothing. */
    private const val PREVIEW_TARGET_WIDTH = 1280
    private const val PREVIEW_MAX_WIDTH = 1920
    private const val RECORD_PIXELS = 1920L * 1080

    @JvmStatic
    fun plansFor(r: DeviceReport): List<StreamPlan> {
        // Matched to the sensor's own shape, so the preview is the capture frame
        // scaled down rather than a crop of it. A 16:9 preview of a 4:3 capture
        // shows a different field of view from the one being recorded, and every
        // marker drawn on it is then in the wrong place.
        val native = (r.rawSizes + r.yuvSizes).maxByOrNull { it.pixels() }
        val preview = pickPreview(r.yuvSizes, native) ?: return emptyList()
        val threeStreams = r.hardwareLevel.allowsThreeStreams()
        // A manual sensor is what makes the bracket ours rather than the camera's.
        // LEGACY never has one, whatever it reports.
        val manual = r.hasManualSensor && r.hardwareLevel != HardwareLevel.LEGACY
        val yuvTier = if (manual) CaptureTier.MANUAL_YUV else CaptureTier.LOCKED_AUTO

        val out = ArrayList<StreamPlan>()

        // RAW is only worth planning where it would be both linear and driveable:
        // RAW frames at an exposure the camera picked are not a measurement.
        if (r.hasRaw && manual && r.rawSizes.isNotEmpty()) {
            // No metering stream. Metering a YUV preview would be metering the
            // camera's tone curve, and the whole point of this tier is that the
            // numbers are linear - so the scan meters from RAW stills through the
            // capture stream instead, which costs a frame a second and one stream
            // fewer.
            for (s in r.rawSizes.sortedByDescending { it.pixels() }.take(2))
                out.add(StreamPlan(CaptureTier.LINEAR_RAW, PixelFormat.RAW_SENSOR, s, preview,
                    false, "RAW at $s, metered from RAW"))
        }

        // Three YUV-shaped streams at once is only guaranteed from FULL upward.
        val yuvMetering = threeStreams && r.hardwareLevel.rank >= HardwareLevel.FULL.rank
        for (s in yuvCandidates(r.yuvSizes, preview)) {
            if (yuvMetering)
                out.add(StreamPlan(yuvTier, PixelFormat.YUV_420_888, s, preview, true,
                    "YUV at $s with live metering"))
            out.add(StreamPlan(yuvTier, PixelFormat.YUV_420_888, s, preview, false,
                "YUV at $s, metering from the preview"))
        }

        val seen = HashSet<String>()
        val unique = ArrayList<StreamPlan>()
        for (p in out) if (seen.add("${p.format}|${p.capture}|${p.preview}|${p.metering}")) unique.add(p)

        // The ladder descends in capability, not in stream count. A configuration
        // that failed did not fail because two streams was too many, so dropping to
        // a worse tier and then asking for a third stream is a legitimate next
        // thing to try; going back up a tier never is. Within a tier the cost
        // descends: streams first, then pixels.
        unique.sortWith(compareBy<StreamPlan> { it.tier.ordinal }
            .thenByDescending { it.streamCount() }
            .thenByDescending { it.capture.pixels() })
        return unique
    }

    /** The largest, a record-sized one, the preview size, and the smallest. */
    private fun yuvCandidates(sizes: List<SensorSize>, preview: SensorSize): List<SensorSize> {
        if (sizes.isEmpty()) return emptyList()
        val byPixels = sizes.sortedByDescending { it.pixels() }
        val wanted = ArrayList<SensorSize>()
        wanted.add(byPixels.first())
        byPixels.firstOrNull { it.pixels() <= RECORD_PIXELS }?.let { wanted.add(it) }
        wanted.add(preview)
        wanted.add(byPixels.last())
        val out = ArrayList<SensorSize>()
        for (s in wanted) if (out.none { it.sameAs(s) }) out.add(s)
        out.sortByDescending { it.pixels() }
        return out
    }

    private fun pickPreview(sizes: List<SensorSize>, native: SensorSize?): SensorSize? {
        if (sizes.isEmpty()) return null
        val usable = sizes.filter { it.width <= PREVIEW_MAX_WIDTH }
        var pool = if (usable.isEmpty()) listOf(sizes.minByOrNull { it.pixels() }!!) else usable
        // Same shape as what is being captured, where the device offers one.
        if (native != null && native.height > 0) {
            val want = native.width.toDouble() / native.height
            val matched = pool.filter { it.height > 0 &&
                Math.abs(it.width.toDouble() / it.height - want) < 0.02 }
            if (matched.isNotEmpty()) pool = matched
        }
        var best = pool[0]
        for (s in pool) {
            val d = Math.abs(s.width - PREVIEW_TARGET_WIDTH)
            val bd = Math.abs(best.width - PREVIEW_TARGET_WIDTH)
            if (d < bd || (d == bd && s.pixels() > best.pixels())) best = s
        }
        return best
    }
}

/**
 * Turning physical sensor numbers into a camera model, correctly.
 *
 * Camera2 reports one physical size for the whole active array. An output
 * stream of a different aspect ratio is a crop of that array, not a differently
 * shaped view of all of it - so measuring the full physical width against a
 * cropped frame inflates the focal length and puts every reprojection off by
 * degrees. The predecessor did exactly that.
 */
object SensorGeometry {

    @JvmStatic
    fun intrinsicsFor(activeArray: SensorSize, sensorWidthMm: Double, sensorHeightMm: Double,
                      focalLengthMm: Double, output: SensorSize): Intrinsics {
        val activeAspect = activeArray.aspect()
        val outputAspect = output.aspect()
        var usedW = activeArray.width.toDouble()
        var usedH = activeArray.height.toDouble()
        if (outputAspect > activeAspect) usedH = activeArray.width / outputAspect   // trimmed top and bottom
        else if (outputAspect < activeAspect) usedW = activeArray.height * outputAspect  // trimmed at the sides
        val mmW = sensorWidthMm * (usedW / activeArray.width)
        val mmH = sensorHeightMm * (usedH / activeArray.height)
        return Intrinsics.pinhole(output.width, output.height,
            output.width * focalLengthMm / mmW,
            output.height * focalLengthMm / mmH)
    }

    /**
     * The same lens seen at a lower working resolution.
     *
     * Not a crop: the field of view is unchanged, so everything scales - focal
     * length included. Pixel i of the result covers source pixels [i*f, i*f+f-1],
     * whose centre sits at i*f + (f-1)/2, which is why the principal point is
     * shifted before it is scaled rather than simply divided.
     */
    @JvmStatic
    fun subsampled(k: Intrinsics, factor: Int): Intrinsics {
        if (factor < 1) throw IllegalArgumentException("subsample factor must be at least 1")
        if (factor == 1) return k
        val f = factor.toDouble()
        val w = k.width / factor
        val h = k.height / factor
        if (w <= 0 || h <= 0) throw IllegalArgumentException("subsampling would leave no image")
        return Intrinsics(w, h, k.fx / f, k.fy / f,
            (k.cx - (f - 1) / 2) / f, (k.cy - (f - 1) / 2) / f,
            k.k1, k.k2, k.k3)
    }
}
