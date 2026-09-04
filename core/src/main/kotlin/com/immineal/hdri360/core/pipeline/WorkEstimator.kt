package com.immineal.hdri360.core.pipeline

import com.immineal.hdri360.core.camera.Intrinsics
import com.immineal.hdri360.core.hdr.Exposure
import com.immineal.hdri360.core.hdr.ExposureSettings
import com.immineal.hdri360.core.hdr.HdrMerger
import com.immineal.hdri360.core.hdr.MergeConfig
import com.immineal.hdri360.core.image.ImageF
import com.immineal.hdri360.core.math.Mat3
import com.immineal.hdri360.core.math.SO3
import com.immineal.hdri360.core.math.Vec3
import com.immineal.hdri360.core.pano.Equirect
import com.immineal.hdri360.core.pano.FastCornerDetector
import com.immineal.hdri360.core.pano.FeatureSet
import com.immineal.hdri360.core.pano.FrameSource
import com.immineal.hdri360.core.pano.PanoramaRenderer
import java.util.Locale

/**
 * What this device costs per unit of work, measured on this device.
 *
 * Not a table of what some other phone managed: the spread between a flagship
 * and a budget phone on this pipeline is more than an order of magnitude, and an
 * estimate off by that much is worse than no estimate.
 */
class Calibration(
    /** Nanoseconds per merged sample, i.e. per pixel per bracket rung. */
    @JvmField val mergeNsPerSample: Double,
    /** Nanoseconds per pixel of the reduced images features are found in. */
    @JvmField val alignNsPerPixel: Double,
    /** Nanoseconds per rendered sample, i.e. per output pixel per contributing frame. */
    @JvmField val renderNsPerSample: Double,
    /** Where these numbers came from, so an estimate can be questioned. */
    @JvmField val basis: String
)

/** An estimate, broken down so that a user who does not believe it can see why. */
class WorkEstimate(
    @JvmField val mergeSeconds: Double,
    @JvmField val alignSeconds: Double,
    @JvmField val renderSeconds: Double,
    @JvmField val writeSeconds: Double
) {
    @JvmField val seconds: Double = mergeSeconds + alignSeconds + renderSeconds + writeSeconds

    /** "about 4 minutes", which is what a person actually wants to be told. */
    fun humanText(): String {
        val s = Math.round(seconds)
        return when {
            s < 45 -> "about $s seconds"
            s < 90 -> "about a minute"
            s < 3600 -> "about ${Math.round(seconds / 60.0)} minutes"
            else -> String.format(Locale.US, "about %.1f hours", seconds / 3600.0)
        }
    }
}

/** One output size the user can choose, with what it would cost. */
class ResolutionOption(
    @JvmField val width: Int,
    @JvmField val estimate: WorkEstimate
) {
    @JvmField val height: Int = width / 2
    val label: String get() = "${width / 1024}K"
}

/**
 * How long processing will take, from constants measured by doing the work.
 *
 * "Processing..." with no number is what makes someone kill an app that was two
 * minutes from finishing. A number invented from nothing is worse, so the model
 * is calibrated by running each stage on this machine, and it says what it was
 * calibrated from.
 */
object WorkEstimator {

    /** Features are found on reduced images, so this cost does not follow the sensor. */
    private const val FEATURE_PIXELS = 640L * 480

    /**
     * Matching and solving, expressed as an equivalent number of feature pixels
     * per direction. Pair matching is quadratic in descriptors but linear in
     * directions once the overlap graph is bounded by geometry, which it is.
     */
    private const val MATCH_EQUIVALENT_PIXELS = 640L * 480 * 3

    /** Conservative sustained write rate for phone flash, in bytes per second. */
    private const val WRITE_BYTES_PER_SEC = 40.0 * 1024 * 1024

    /** Half-float RGB. */
    private const val OUTPUT_BYTES_PER_PIXEL = 3L * 2

    @JvmStatic
    fun estimate(directions: Int, rungs: Int, framePixels: Long,
                 panoramaWidth: Int, cal: Calibration): WorkEstimate {
        if (directions <= 0 || rungs <= 0 || framePixels <= 0)
            throw IllegalArgumentException("nothing to estimate")
        val h = Equirect.heightFor(panoramaWidth).toLong()
        val outPixels = panoramaWidth.toLong() * h
        val merge = directions.toLong() * rungs * framePixels * cal.mergeNsPerSample / 1e9
        val align = directions.toLong() * (FEATURE_PIXELS + MATCH_EQUIVALENT_PIXELS) *
            cal.alignNsPerPixel / 1e9
        val render = outPixels * directions * cal.renderNsPerSample / 1e9
        val write = outPixels * OUTPUT_BYTES_PER_PIXEL / WRITE_BYTES_PER_SEC
        return WorkEstimate(merge, align, render, write)
    }

    /** The sizes offered, best first. Resolution is the only dial worth giving a user. */
    @JvmStatic
    @JvmOverloads
    fun resolutionOptions(directions: Int, rungs: Int, framePixels: Long, cal: Calibration,
                          widths: IntArray = intArrayOf(8192, 4096, 2048)): List<ResolutionOption> =
        widths.map { ResolutionOption(it, estimate(directions, rungs, framePixels, it, cal)) }

    /**
     * Times each stage on a small synthetic problem and scales per unit of work.
     *
     * Small deliberately: this runs before the user has agreed to anything, so it
     * has to cost a fraction of a second. It is timed after a warm-up pass, since
     * the first pass through any of this is measuring the JIT rather than the
     * machine.
     */
    @JvmStatic
    fun calibrate(): Calibration {
        val mergeNs = timeMerge()
        val alignNs = timeAlign()
        val renderNs = timeRender()
        return Calibration(mergeNs, alignNs, renderNs,
            String.format(Locale.US, "measured here on %d cores",
                Runtime.getRuntime().availableProcessors()))
    }

    private fun timeMerge(): Double {
        val w = 192
        val h = 144
        val bracket = ArrayList<Exposure>(3)
        for (k in 0 until 3) {
            val im = ImageF(w, h, 3)
            for (i in im.data.indices) im.data[i] = pattern(i, k)
            bracket.add(Exposure.of(im, ExposureSettings(1.0 / (250 shr k), 100, 1.8), 100))
        }
        val cfg = MergeConfig()
        HdrMerger.merge(bracket, cfg)                     // warm up the JIT, then measure
        val t0 = System.nanoTime()
        HdrMerger.merge(bracket, cfg)
        val elapsed = System.nanoTime() - t0
        return Math.max(1e-3, elapsed / (3.0 * w * h))
    }

    private fun timeAlign(): Double {
        val w = 320
        val h = 240
        val gray = ImageF(w, h, 1)
        for (i in gray.data.indices) gray.data[i] = pattern(i, 1)
        val cfg = FastCornerDetector.Config()
        val warm = FastCornerDetector.detect(gray, cfg)
        FeatureSet.describe(gray, warm)
        val t0 = System.nanoTime()
        val corners = FastCornerDetector.detect(gray, cfg)
        FeatureSet.describe(gray, corners)
        val elapsed = System.nanoTime() - t0
        return Math.max(1e-3, elapsed / (w.toDouble() * h))
    }

    private fun timeRender(): Double {
        val k = Intrinsics.fromHorizontalFov(128, 96, 60.0)
        val frames = ArrayList<FrameSource>(2)
        for (i in 0 until 2) {
            val im = ImageF(128, 96, 3)
            for (j in im.data.indices) im.data[j] = pattern(j, i)
            frames.add(FrameSource(im, k, rotationFor(i), null, 1.0))
        }
        val cfg = PanoramaRenderer.Config()
        cfg.width = 256
        val h = Equirect.heightFor(cfg.width)
        PanoramaRenderer.renderRows(frames, cfg, 0, h, null)
        val t0 = System.nanoTime()
        PanoramaRenderer.renderRows(frames, cfg, 0, h, null)
        val elapsed = System.nanoTime() - t0
        return Math.max(1e-3, elapsed / (256.0 * h * frames.size))
    }

    private fun rotationFor(i: Int): Mat3 =
        if (i == 0) Mat3.IDENTITY else SO3.exp(Vec3(0.0, Math.toRadians(30.0), 0.0))

    /** Something with structure in it: a flat field would flatter every stage. */
    private fun pattern(i: Int, k: Int): Float {
        val x = (i * 2654435761L + k * 40503L) and 0xFFFF
        return 0.05f + 0.9f * (x / 65535.0f)
    }
}
