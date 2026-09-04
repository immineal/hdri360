package com.immineal.hdri360.core.pipeline

import com.immineal.hdri360.core.capture.StoredSession
import com.immineal.hdri360.core.image.ImageF
import com.immineal.hdri360.core.io.ExrStreamWriter
import com.immineal.hdri360.core.io.ExrWriter
import com.immineal.hdri360.core.io.Json
import com.immineal.hdri360.core.pano.Equirect
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
        /** Rows rendered at once. Smaller is less memory and slightly more overhead. */
        @JvmField var stripRows = 128
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
        @JvmField val clippedFraction: Double
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
        writeExr(out, result.renderable, result.seamMap, cfg, progress)

    @JvmStatic
    @JvmOverloads
    fun writeExr(out: OutputStream, frames: List<FrameSource>,
                 seamMap: PanoramaRenderer.SeamMap?, cfg: Config,
                 progress: Progress? = null): Stats {
        if (frames.isEmpty()) throw IllegalArgumentException("nothing was placed, so nothing to write")
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
        preview(result.renderable, result.seamMap, width, cfg)

    @JvmStatic
    @JvmOverloads
    fun preview(frames: List<FrameSource>, seamMap: PanoramaRenderer.SeamMap?,
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
     * capture was, whether the values are absolute, how well the poses solved,
     * and how much of the sphere was actually seen.
     */
    @JvmStatic
    fun report(result: HdriPipeline.Result, session: StoredSession, stats: Stats,
               width: Int, elapsedSeconds: Double): Json.Obj {
        val scale = result.radianceScale
        val root = Json.Obj()
            .put("format", "hdri360-report-1")
            .put("width", width.toLong())
            .put("height", Equirect.heightFor(width).toLong())
            .put("camera", session.cameraId)
            .put("tier", session.tier.name)
            .put("measuresRadiance", session.tier.measuresRadiance)
            .put("absoluteScale", scale.absolute)
            .put("radianceBasis", scale.basis)
            .put("apertureF", session.apertureN)
            .put("focalLengthMm", session.focalLengthMm)
            .put("baseIso", session.baseIso.toLong())
            .put("framesPlaced", result.placed.count { it }.toLong())
            .put("framesTotal", result.placed.size.toLong())
            .put("pairs", result.pairs.size.toLong())
            .put("bundleResidualDeg", result.baRmsDeg)
            .put("horizonConfidence", result.horizonConfidence)
            .put("k1", result.k1)
            .put("coveredFraction", stats.coveredFraction)
            .put("dynamicRangeStops", stats.dynamicRangeStops)
            .put("minRadiance", stats.minRadiance)
            .put("maxRadiance", stats.maxRadiance)
            .put("meanRadiance", stats.meanRadiance)
            .put("clippedFraction", stats.clippedFraction)
            .put("processingSeconds", elapsedSeconds)
        if (scale.absolute) {
            root.put("cdPerM2PerUnit", scale.cdPerM2PerUnit)
            root.put("maxLuminanceCdPerM2", scale.toCdPerM2(stats.maxRadiance))
        }
        val gains = Json.Arr()
        for (g in result.gains) gains.add(g)
        root.put("gains", gains)
        val poses = Json.Arr()
        for (i in result.rotations.indices)
            poses.add(Json.Obj()
                .put("frame", i.toLong())
                .put("placed", result.placed[i])
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
    private class Accumulator {
        private val bins = IntArray(BINS)
        private var min = Double.MAX_VALUE
        private var max = 0.0
        private var sum = 0.0
        private var count = 0L
        private var covered = 0L
        private var pixels = 0L
        private var clipped = 0L

        fun add(strip: ImageF, coverage: FloatArray) {
            val d = strip.data
            var i = 0
            while (i < d.size) {
                val v = ((d[i].toDouble() + d[i + 1] + d[i + 2]) / 3.0)
                if (v.isFinite() && v > 0) {
                    if (v < min) min = v
                    if (v > max) max = v
                    sum += v
                    count++
                    val b = ((Math.log(v) / LN2 - LOW) * BINS / (HIGH - LOW)).toInt()
                    bins[Math.max(0, Math.min(BINS - 1, b))]++
                }
                if (d[i] >= 1.0f && d[i + 1] >= 1.0f && d[i + 2] >= 1.0f) clipped++
                i += 3
            }
            for (c in coverage) { if (c > 0) covered++ }
            pixels += coverage.size.toLong()
        }

        fun finish(): Stats {
            if (count == 0L) return Stats(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
            val lo = percentile(0.001)
            val hi = percentile(0.999)
            return Stats(min, max, sum / count,
                if (pixels > 0) covered / pixels.toDouble() else 0.0,
                Math.max(0.0, hi - lo),
                clipped / Math.max(1L, pixels).toDouble())
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
