package com.immineal.hdri360.core.pipeline

import com.immineal.hdri360.core.capture.StoredSession
import com.immineal.hdri360.core.image.ImageF
import com.immineal.hdri360.core.image.ImageOps
import com.immineal.hdri360.core.io.ExrStreamWriter
import com.immineal.hdri360.core.io.ExrWriter
import com.immineal.hdri360.core.io.Json
import com.immineal.hdri360.core.pano.Equirect
import com.immineal.hdri360.core.pano.FrameSet
import com.immineal.hdri360.core.pano.FrameSource
import com.immineal.hdri360.core.pano.PanoramaRenderer
import java.io.OutputStream
import java.util.Locale

/**
 * Writes the finished sphere.
 *
 * The output is 8192 x 4096 half-float RGB, which is 201 MB of pixels. Rendering
 * that into a float array first would need 400 MB more and would be killed on
 * most phones, so it is produced a strip at a time and each strip is compressed
 * and dropped before the next is rendered. The pipeline's own preview panorama
 * stays small and is never the thing that gets written.
 *
 * The seam decision comes from the pipeline rather than being made again here.
 * A seam map is sampled in normalised coordinates, so the choice made while
 * solving at a few hundred pixels wide applies unchanged at eight thousand -
 * which is the only reason full resolution is affordable at all.
 */
object OutputWriter {

    class Config {
        @JvmField var panoramaWidth = 8192
        /**
         * Rows rendered at once.
         *
         * Also how often each frame is re-opened: the composite walks frames on
         * the outside of a strip, so a strip half the size reads the sphere twice
         * as often. Wide enough that a frame spans two or three strips, narrow
         * enough that a strip of an 8K panorama is tens of megabytes.
         */
        @JvmField var stripRows = 256
        @JvmField var compression = ExrWriter.Compression.ZIPS
        @JvmField var featherPx = 60.0
        @JvmField var seamFeather = 2.5
        @JvmField var cosinePower = 0.0
    }

    fun interface Progress {
        fun rows(done: Int, total: Int)
    }

    /** What the finished sphere actually contains, measured while writing it. */
    class Stats(
        @JvmField val minRadiance: Double,
        @JvmField val maxRadiance: Double,
        @JvmField val meanRadiance: Double,
        /** Fraction of the sphere any frame saw at all. */
        @JvmField val coveredFraction: Double,
        /** Range between the 0.1th and 99.9th percentiles, in stops. */
        @JvmField val dynamicRangeStops: Double,
        /**
         * Pixels the file could not hold, clamped to half float's largest finite
         * value on the way in.
         *
         * Counted rather than left silent: a number that changed between the
         * pipeline and the file is exactly the sort of thing a report exists to
         * admit. Normally zero - the clamp is at 65504 kilocandela, sixty-five
         * million cd/m2, forty times the sun's own disc.
         */
        @JvmField val clampedToHalf: Long = 0L
    ) {
        override fun toString(): String = String.format(Locale.US,
            "%.4g to %.4g, mean %.4g, %.1f stops, %.1f%% covered",
            minRadiance, maxRadiance, meanRadiance, dynamicRangeStops, 100 * coveredFraction)
    }

    /**
     * Renders and writes the panorama, returning what it found in it.
     *
     * [out] is closed by the writer, because an EXR is only a valid file once its
     * offset table has been emitted and leaving that to the caller is how a
     * half-written file ends up looking like a finished one.
     */
    @JvmStatic
    @JvmOverloads
    fun writeExr(out: OutputStream, result: HdriPipeline.Result, cfg: Config,
                 progress: Progress? = null): Stats =
        writeExr(out, result.renderable, result.seamMap, cfg,
            FileScale.of(result.radianceScale, result.panorama), progress)

    @JvmStatic
    @JvmOverloads
    fun writeExr(out: OutputStream, frames: List<FrameSource>,
                 seamMap: PanoramaRenderer.SeamMap?, cfg: Config,
                 scale: FileScale = FileScale.IDENTITY,
                 progress: Progress? = null): Stats =
        writeExr(out, FrameSet.of(frames), seamMap, cfg, scale, progress)

    @JvmStatic
    @JvmOverloads
    fun writeExr(out: OutputStream, frames: FrameSet,
                 seamMap: PanoramaRenderer.SeamMap?, cfg: Config,
                 // Before the progress callback so that a trailing lambda still
                 // binds to the callback, which is how every caller writes it.
                 scale: FileScale = FileScale.IDENTITY,
                 progress: Progress? = null): Stats {
        if (frames.size == 0) throw IllegalArgumentException("nothing was placed, so nothing to write")
        val width = cfg.panoramaWidth
        val height = Equirect.heightFor(width)

        val render = PanoramaRenderer.Config()
        render.width = width
        render.featherPx = cfg.featherPx
        render.cosinePower = cfg.cosinePower
        render.seamFeather = cfg.seamFeather
        // Zero here means "use the map we were given"; the seam is not re-solved.
        render.seamWidth = 0

        val acc = Accumulator()
        val strip = Math.max(1, cfg.stripRows)
        ExrStreamWriter(out, width, height, cfg.compression).use { writer ->
            var y0 = 0
            while (y0 < height) {
                val y1 = Math.min(height, y0 + strip)
                val part = PanoramaRenderer.renderRows(frames, render, y0, y1, seamMap)
                // Into the file's units before anything measures or writes it, so
                // the statistics in the report describe the numbers a reader will
                // actually find rather than the pipeline's private ones.
                scale.applyTo(part.panorama)
                acc.add(part.panorama, part.coverage)
                writer.writeRows(part.panorama)
                y0 = y1
                progress?.rows(y0, height)
            }
        }
        return acc.finish()
    }

    /** A small render of the same sphere, for the preview image and the viewer. */
    @JvmStatic
    @JvmOverloads
    fun preview(result: HdriPipeline.Result, width: Int, cfg: Config = Config()): ImageF =
        FileScale.of(result.radianceScale, result.panorama)
            .scaled(preview(result.renderable, result.seamMap, width, cfg))

    @JvmStatic
    @JvmOverloads
    fun preview(frames: List<FrameSource>, seamMap: PanoramaRenderer.SeamMap?,
                width: Int, cfg: Config = Config()): ImageF =
        preview(FrameSet.of(frames), seamMap, width, cfg)

    @JvmStatic
    @JvmOverloads
    fun preview(frames: FrameSet, seamMap: PanoramaRenderer.SeamMap?,
                width: Int, cfg: Config = Config()): ImageF {
        val render = PanoramaRenderer.Config()
        render.width = width
        render.featherPx = cfg.featherPx
        render.cosinePower = cfg.cosinePower
        render.seamFeather = cfg.seamFeather
        render.seamWidth = 0
        return PanoramaRenderer.renderRows(frames, render, 0,
            Equirect.heightFor(width), seamMap).panorama
    }

    /**
     * The sidecar that says what this file is and how much of it to believe.
     *
     * Everything a later reader would otherwise have to guess: which tier the
     * capture was, whether the values are absolute, what colour space they are
     * in, which directions were actually solved rather than placed on the phone's
     * own orientation, whether the top of the range is a measurement or a bound,
     * and how much of the sphere was seen at all.
     */
    @JvmStatic
    fun report(result: HdriPipeline.Result, session: StoredSession, stats: Stats,
               width: Int, elapsedSeconds: Double): Json.Obj {
        val scale = result.radianceScale
        val fileScale = FileScale.of(scale, result.panorama)
        val root = Json.Obj()
            // Version two. `cdPerM2PerUnit` used to be cd/m2 per *pipeline* unit
            // and is now per *file* unit, because the file is now written in a
            // stated unit rather than in the pipeline's own; `clippedFraction`
            // is gone, having counted pixels above 1.0 in a radiance map and so
            // called a whole correct sphere 99.99% clipped. A key whose meaning
            // changes under a version that does not is the single failure a
            // format version exists to prevent.
            .put("format", "hdri360-report-2")
            .put("width", width.toLong())
            .put("height", Equirect.heightFor(width).toLong())
            .put("camera", session.cameraId)
            .put("tier", session.tier.name)
            .put("measuresRadiance", session.tier.measuresRadiance)
            .put("absoluteScale", scale.absolute)
            .put("radianceBasis", scale.basis)
            // What the numbers in the file are. Without this the file is a set of
            // plausible floats and a reader has to guess, which is how one came to
            // be about eighty-six times too bright to open.
            .put("fileUnit", if (fileScale.absolute) "kilocandela-per-m2" else "normalised")
            .put("cdPerM2PerUnit", fileScale.cdPerM2PerUnit)
            .put("fileScaleBasis", fileScale.basis)
            .put("apertureF", session.apertureN)
            .put("focalLengthMm", session.focalLengthMm)
            .put("baseIso", session.baseIso.toLong())
            .put("framesPlaced", result.placed.count { it }.toLong())
            .put("framesTotal", result.placed.size.toLong())
            // Decision 9. A direction the solve never reached is in the sphere at
            // the pose the phone's orientation gave it, which is right to a few
            // degrees at best. Silence about that is what makes a soft seam
            // unexplainable; a hole that is named is a fact somebody can act on.
            .put("framesOnPriorAlone", result.placedOnPriorAlone.count { it }.toLong())
            .put("pairs", result.pairs.size.toLong())
            .put("bundleResidualDeg", result.baRmsDeg)
            .put("horizonConfidence", result.horizonConfidence)
            .put("k1", result.k1)
            .put("coveredFraction", stats.coveredFraction)
            .put("dynamicRangeStops", stats.dynamicRangeStops)
            .put("minRadiance", stats.minRadiance)
            .put("maxRadiance", stats.maxRadiance)
            .put("meanRadiance", stats.meanRadiance)
            .put("processingSeconds", elapsedSeconds)
            // Decision 2 - nothing may clip - answered per direction, from the
            // merge's own flags. What used to be here counted pixels above 1.0 in
            // the finished panorama, which in a radiance map with an absolute
            // scale is nearly all of them: a whole, correct 21-direction sphere
            // off the phone reported 99.99% "clipped". A number that is wrong in
            // the safe direction would be bad enough; that one was wrong in the
            // direction that makes a good capture look ruined.
            .put("directionsWithUnmeasuredHighlights",
                result.saturatedFraction.count { it > UNMEASURED_TOLERANCE }.toLong())
            .put("worstUnmeasuredFraction", result.saturatedFraction.maxOrNull() ?: 0.0)
            // Zero on any ordinary sphere. Reported anyway, because a pixel the
            // file could not hold is a number that changed between the pipeline
            // and the file, and that is what a report is for.
            .put("clampedToHalfFloat", stats.clampedToHalf)
            // Decision 8. Two linear EXRs, one in the camera's own primaries and
            // one in Rec.709, are indistinguishable once written - so the file has
            // to say which it is rather than let a reader assume the good case.
            .put("colorSpace", if (session.colorMatrix != null) "linear-rec709" else "camera-rgb")
            .put("colorSpaceBasis", if (session.colorMatrix != null)
                "the camera's own sensor-RGB to linear-sRGB matrix, applied after its white " +
                "balance gains; sRGB and Rec.709 share primaries and white point"
            else
                "this camera reported no colour matrix, so the values are in its own sensor " +
                "primaries with white balance applied - linear, but not convertible without " +
                "a matrix for this sensor")
            // Decision 3. The growing ladder answers a direction that clips while
            // there is still shutter to spend. Where there is not - direct sun in
            // a window - the top of the range is a bound and not a measurement.
            .put("highlightsAreLowerBound", session.plan.ladder.clampedLow)
        // Straight from the file's own numbers, which is the only way it can be
        // checked: multiply what is in the pixel by what the file says a unit is.
        if (fileScale.absolute)
            root.put("maxLuminanceCdPerM2", stats.maxRadiance * fileScale.cdPerM2PerUnit)
        val gains = Json.Arr()
        for (g in result.gains) gains.add(g)
        root.put("gains", gains)
        val poses = Json.Arr()
        for (i in result.rotations.indices)
            poses.add(Json.Obj()
                .put("frame", i.toLong())
                .put("placed", result.placed[i])
                .put("onPriorAlone", result.placedOnPriorAlone[i])
                .put("rotation", result.rotations[i].data()))
        root.put("poses", poses)
        return root
    }

    /**
     * Running statistics over the strips, so nothing has to be kept.
     *
     * The range is taken between percentiles rather than between the extremes: a
     * single hot pixel on the sun, or one dead pixel, would otherwise decide the
     * headline number. The histogram is in log2 space because that is the space
     * the answer is wanted in.
     */
    /**
     * Below this a direction's unmeasured pixels are the sensor's own stuck ones
     * rather than a blown highlight, and counting them would put a warning on
     * every sphere.
     */
    private const val UNMEASURED_TOLERANCE = 1e-4

    private class Accumulator {
        private val bins = IntArray(BINS)
        private var min = Double.MAX_VALUE
        private var max = 0.0
        private var sum = 0.0
        private var count = 0L
        private var covered = 0L
        private var pixels = 0L
        private var clamped = 0L

        fun add(strip: ImageF, coverage: FloatArray) {
            val d = strip.data
            var i = 0
            while (i < d.size) {
                // Rec.709 luma, not the mean of three channels. The file is in
                // Rec.709 primaries with a stated unit and the report multiplies
                // this by that unit to quote a figure in cd/m2 - so it has to be
                // a luminance. Averaging the channels calls 100 units of pure
                // green 33 where its luminance is 72.
                val v = ImageOps.LUMA_R * d[i] + ImageOps.LUMA_G * d[i + 1] +
                        ImageOps.LUMA_B * d[i + 2].toDouble()
                for (k in 0 until 3) {
                    val c = d[i + k]
                    if (c.isNaN() || Math.abs(c) > com.immineal.hdri360.core.io.Half.MAX_FINITE)
                        clamped++
                }
                if (v.isFinite() && v > 0) {
                    if (v < min) min = v
                    if (v > max) max = v
                    sum += v
                    count++
                    val b = ((Math.log(v) / LN2 - LOW) * BINS / (HIGH - LOW)).toInt()
                    bins[Math.max(0, Math.min(BINS - 1, b))]++
                }
                i += 3
            }
            for (c in coverage) { if (c > 0) covered++ }
            pixels += coverage.size.toLong()
        }

        fun finish(): Stats {
            if (count == 0L) return Stats(0.0, 0.0, 0.0, 0.0, 0.0, clamped)
            val lo = percentile(0.001)
            val hi = percentile(0.999)
            return Stats(min, max, sum / count,
                if (pixels > 0) covered / pixels.toDouble() else 0.0,
                Math.max(0.0, hi - lo), clamped)
        }

        private fun percentile(p: Double): Double {
            val target = (p * count).toLong()
            var seen = 0L
            for (b in 0 until BINS) {
                seen += bins[b]
                if (seen >= target) return LOW + (b + 0.5) * (HIGH - LOW) / BINS
            }
            return HIGH
        }

        companion object {
            private const val BINS = 512
            private const val LOW = -24.0
            private const val HIGH = 24.0
            private val LN2 = Math.log(2.0)
        }
    }
}
