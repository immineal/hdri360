package com.immineal.hdri360.core.pano

import com.immineal.hdri360.core.Parallel
import com.immineal.hdri360.core.image.ImageF
import com.immineal.hdri360.core.image.ImageOps
import com.immineal.hdri360.core.math.Vec3
import java.util.Arrays

/**
 * Composites frames into an equirectangular radiance map.
 *
 * Inverse mapping - for every output pixel, ask every frame what it saw - rather
 * than forward-splatting, so the output is gap-free by construction and each
 * pixel is a single normalised weighted average.
 *
 * The blend stays in linear radiance from end to end. Multi-band blending, the
 * usual answer to visible seams, assumes a display-referred image; applied to
 * radiance spanning six orders of magnitude it manufactures haloes around bright
 * sources. Feathering a linear weighted mean, after a global photometric solve
 * has removed the actual brightness offsets, keeps the result physically
 * meaningful - which is the entire point of an HDRI.
 */
object PanoramaRenderer {

    class Config {
        @JvmField var width = 4096
        /** Distance from a frame's edge over which its weight ramps up, in source pixels. */
        @JvmField var featherPx = 60.0
        /** Optional extra centre-weighting, cos^p of the off-axis angle. */
        @JvmField var cosinePower = 0.0
        /**
         * Width of the grid the seam is solved on; 0 leaves the plain weighted mean.
         *
         * Seams are found at reduced resolution and the resulting region map is
         * feathered back up. Solving at full resolution would cost far more and
         * decide nothing extra: a seam is a boundary between regions, and where it
         * runs to within a few pixels is not something the data determines.
         */
        @JvmField var seamWidth = 0
        /** Feather, in seam-grid pixels, applied across a region boundary. */
        @JvmField var seamFeather = 2.5
        @JvmField var seam = SeamFinder.Config()
    }

    class Result internal constructor(
        @JvmField val panorama: ImageF,
        /** Sum of blend weights per pixel; zero means no frame saw this direction. */
        @JvmField val coverage: FloatArray,
        /** How many frames contributed to each pixel. */
        @JvmField val contributors: ShortArray
    ) {
        fun coveredFraction(): Double {
            var n = 0
            for (c in coverage) if (c > 0) n++
            return n / coverage.size.toDouble()
        }
    }

    /**
     * Which frame owns which part of the sphere, feathered so the boundaries can
     * be crossed without a step.
     *
     * Held separately from the render because a streamed output renders in strips
     * and every strip must see the same decision - a seam solved per strip would
     * disagree with itself at the joins.
     */
    class SeamMap internal constructor(
        @JvmField val width: Int,
        @JvmField val height: Int,
        private val maxCandidates: Int,
        @JvmField val labels: IntArray,
        private val count: IntArray,
        private val label: IntArray,
        private val share: FloatArray
    ) {
        /** This frame's share of a direction, sampled bilinearly over the seam grid. */
        fun shareAt(frame: Int, uNorm: Double, vNorm: Double): Double {
            val fx = uNorm * width - 0.5
            val fy = vNorm * height - 0.5
            val x0 = Math.floor(fx).toInt()
            val y0 = Math.floor(fy).toInt()
            val tx = fx - x0
            val ty = fy - y0
            var acc = 0.0
            for (dy in 0 until 2) {
                val yy = clamp(y0 + dy, 0, height - 1)
                val wy = if (dy == 0) 1 - ty else ty
                if (wy <= 0) continue
                for (dx in 0 until 2) {
                    var xx = x0 + dx
                    // The canvas wraps in longitude, so the join must sample across it.
                    xx = ((xx % width) + width) % width
                    val wx = if (dx == 0) 1 - tx else tx
                    if (wx <= 0) continue
                    acc += wx * wy * shareOf(yy * width + xx, frame)
                }
            }
            return acc
        }

        private fun shareOf(pixel: Int, frame: Int): Double {
            val base = pixel * maxCandidates
            for (c in 0 until count[pixel]) if (label[base + c] == frame) return share[base + c].toDouble()
            return 0.0
        }

        private fun clamp(v: Int, lo: Int, hi: Int) = if (v < lo) lo else if (v > hi) hi else v
    }

    /**
     * Solves the seam at reduced resolution.
     *
     * @return null when seams are disabled or there is nothing to decide.
     */
    @JvmStatic
    fun buildSeamMap(frames: List<FrameSource>?, cfg: Config): SeamMap? {
        if (frames == null || frames.size < 2) return null
        if (cfg.seamWidth <= 0) return null
        val sw = cfg.seamWidth
        val sh = Equirect.heightFor(sw)
        if (sw != 2 * sh) throw IllegalArgumentException("seam width must be even and 2:1")

        val nf = frames.size
        // Most directions are seen by a handful of frames; a pixel that somehow
        // exceeds this simply keeps the strongest candidates.
        val maxCand = Math.min(nf, 8)
        val problem = SeamFinder.Problem(sw, sh, nf, maxCand)

        val axes = arrayOfNulls<Vec3>(nf)
        val cosLimit = DoubleArray(nf)
        for (i in 0 until nf) {
            val f = frames[i]
            axes[i] = f.rotation.mul(Vec3(0.0, 0.0, 1.0))
            cosLimit[i] = Math.cos(Math.min(Math.PI, f.intrinsics.maxAngleFromAxisRad() + 1e-3))
        }

        Parallel.forRanges(sh, 512) { bandFrom, bandTo ->
        for (y in bandFrom until bandTo) {
            for (x in 0 until sw) {
                val i = y * sw + x
                val dir = Equirect.direction(x.toDouble(), y.toDouble(), sw, sh)
                for (fi in 0 until nf) {
                    if (dir.dot(axes[fi]!!) < cosLimit[fi]) continue
                    val f = frames[fi]
                    val cam = f.rotation.mulTranspose(dir)
                    if (!(cam.z > 1e-9)) continue
                    val p = f.intrinsics.project(cam) ?: continue
                    val u = p[0]; val v = p[1]
                    val iw = f.radiance.width; val ih = f.radiance.height
                    if (u < -0.5 || v < -0.5 || u > iw - 0.5 || v > ih - 0.5) continue
                    var weight = featherWeight(u, v, iw, ih, cfg.featherPx)
                    if (weight <= 0) continue
                    val conf = f.confidence
                    if (conf != null) {
                        val cx = clamp(Math.round(u).toInt(), 0, iw - 1)
                        val cy = clamp(Math.round(v).toInt(), 0, ih - 1)
                        weight *= conf[cy * iw + cx].toDouble()
                    }
                    if (weight <= 0) continue
                    // Log radiance, because the seam cost has to mean the same thing
                    // in the sky and in the shadows. Gain is applied first so two
                    // frames are compared after photometric alignment, not before.
                    val ch = f.radiance.channels
                    var lum = if (ch >= 3)
                        (ImageOps.LUMA_R * f.radiance.sampleBilinear(u, v, 0) +
                         ImageOps.LUMA_G * f.radiance.sampleBilinear(u, v, 1) +
                         ImageOps.LUMA_B * f.radiance.sampleBilinear(u, v, 2)).toDouble()
                    else f.radiance.sampleBilinear(u, v, 0).toDouble()
                    lum *= f.gain
                    val logLum = Math.log(Math.max(1e-8, lum)).toFloat()
                    problem.add(i, fi, logLum, weight.toFloat())
                }
            }
        }
        }

        val solved = SeamFinder.solve(problem, cfg.seam)
        val share = featherRegions(problem, solved.labels, cfg.seamFeather)
        return SeamMap(sw, sh, maxCand, solved.labels, problem.count, problem.label, share)
    }

    /**
     * Turns hard region ownership into a feathered share.
     *
     * A hard boundary would show: the frames still differ slightly in exposure and
     * residual vignetting even after the photometric solve, and a step of a few
     * percent is visible. Averaging the ownership indicator over a small
     * neighbourhood gives a ramp instead, narrow enough that nothing moving is
     * blended across it.
     */
    private fun featherRegions(p: SeamFinder.Problem, labels: IntArray,
                               featherPx: Double): FloatArray {
        val share = FloatArray(p.label.size)
        val radius = Math.max(1, Math.ceil(featherPx).toInt())
        val w = p.width
        val h = p.height
        Parallel.forRanges(h, 512) { bandFrom, bandTo ->
        for (y in bandFrom until bandTo) {
            for (x in 0 until w) {
                val i = y * w + x
                val n = p.count[i]
                if (n == 0) continue
                val base = i * p.maxCandidates
                var total = 0.0
                for (c in 0 until n) {
                    val l = p.label[base + c]
                    var hits = 0.0
                    var seen = 0.0
                    for (dy in -radius..radius) {
                        val yy = y + dy
                        if (yy < 0 || yy >= h) continue
                        for (dx in -radius..radius) {
                            val xx = ((x + dx) % w + w) % w
                            val j = yy * w + xx
                            if (p.count[j] == 0) continue
                            val d = Math.sqrt((dx * dx + dy * dy).toDouble())
                            if (d > featherPx) continue
                            val wgt = 1.0 - d / (featherPx + 1e-9)
                            seen += wgt
                            if (labels[j] == l) hits += wgt
                        }
                    }
                    val s = if (seen > 0) hits / seen else 0.0
                    share[base + c] = s.toFloat()
                    total += s
                }
                // Normalise so the shares of a direction always sum to one.
                if (total > 0) for (c in 0 until n) share[base + c] = (share[base + c] / total).toFloat()
            }
        }
        }
        return share
    }

    @JvmStatic
    fun render(frames: List<FrameSource>?, cfg: Config): Result =
        renderRows(frames, cfg, 0, Equirect.heightFor(cfg.width), buildSeamMap(frames, cfg))

    /**
     * Renders the horizontal strip [rowStart, rowEnd) of the panorama.
     *
     * Strips let the output be streamed straight to a file: a 4096-wide float RGB
     * panorama is 100 MB, which on a phone is the difference between finishing
     * and being killed. Every strip sees the full frame list, so the result is
     * identical to rendering the whole canvas at once.
     */
    @JvmStatic
    @JvmOverloads
    fun renderRows(frames: List<FrameSource>?, cfg: Config, rowStart: Int, rowEnd: Int,
                   seam: SeamMap? = null): Result {
        if (frames == null || frames.isEmpty()) throw IllegalArgumentException("no frames to render")
        val w = cfg.width
        val h = Equirect.heightFor(w)
        if (w != 2 * h) throw IllegalArgumentException("panorama width must be even and 2:1")
        if (rowStart < 0 || rowEnd > h || rowEnd <= rowStart)
            throw IllegalArgumentException(
                "bad row range " + rowStart + ".." + rowEnd + " for height " + h)
        val channels = frames[0].radiance.channels
        for (f in frames)
            if (f.radiance.channels != channels)
                throw IllegalArgumentException("frames differ in channel count")

        val rows = rowEnd - rowStart
        val out = ImageF(w, rows, channels)
        val coverage = FloatArray(w * rows)
        val contributors = ShortArray(w * rows)
        // Precompute each frame's viewing cone so the inner loop can reject the
        // frames that cannot possibly see a direction with one dot product. With
        // thirty-odd frames on a sphere this is the difference between a few
        // seconds and a few minutes.
        val nf = frames.size
        val axes = arrayOfNulls<Vec3>(nf)
        val cosLimit = DoubleArray(nf)
        for (i in 0 until nf) {
            val f = frames[i]
            axes[i] = f.rotation.mul(Vec3(0.0, 0.0, 1.0))
            cosLimit[i] = Math.cos(Math.min(Math.PI, f.intrinsics.maxAngleFromAxisRad() + 1e-3))
        }

        Parallel.forRanges(rowEnd - rowStart) { bandFrom, bandTo ->
        val acc = DoubleArray(channels)
        for (y in rowStart + bandFrom until rowStart + bandTo) {
            for (x in 0 until w) {
                val dir = Equirect.direction(x.toDouble(), y.toDouble(), w, h)
                Arrays.fill(acc, 0.0)
                var wsum = 0.0
                var count = 0

                for (fi in 0 until nf) {
                    if (dir.dot(axes[fi]!!) < cosLimit[fi]) continue
                    val f = frames[fi]
                    val cam = f.rotation.mulTranspose(dir)
                    if (!(cam.z > 1e-9)) continue
                    val p = f.intrinsics.project(cam) ?: continue
                    val u = p[0]
                    val v = p[1]
                    val iw = f.radiance.width
                    val ih = f.radiance.height
                    if (u < -0.5 || v < -0.5 || u > iw - 0.5 || v > ih - 0.5) continue

                    var weight = featherWeight(u, v, iw, ih, cfg.featherPx)
                    if (weight <= 0) continue
                    if (cfg.cosinePower > 0) {
                        val cos = cam.normalized().z
                        weight *= Math.pow(Math.max(0.0, cos), cfg.cosinePower)
                    }
                    val conf = f.confidence
                    if (conf != null) {
                        val cx = clamp(Math.round(u).toInt(), 0, iw - 1)
                        val cy = clamp(Math.round(v).toInt(), 0, ih - 1)
                        weight *= conf[cy * iw + cx].toDouble()
                    }
                    if (seam != null) {
                        weight *= seam.shareAt(fi, (x + 0.5) / w, (y + 0.5) / h)
                    }
                    if (weight <= 0) continue

                    for (c in 0 until channels)
                        acc[c] += weight * f.gain * f.radiance.sampleBilinear(u, v, c)
                    wsum += weight
                    count++
                }

                val i = (y - rowStart) * w + x
                coverage[i] = wsum.toFloat()
                contributors[i] = Math.min(Short.MAX_VALUE.toInt(), count).toShort()
                if (wsum > 0)
                    for (c in 0 until channels) out.data[i * channels + c] = (acc[c] / wsum).toFloat()
            }
        }
        }
        return Result(out, coverage, contributors)
    }

    /** Smoothstep ramp from 0 at the frame border to 1 once featherPx inside it. */
    @JvmStatic
    internal fun featherWeight(u: Double, v: Double, w: Int, h: Int, featherPx: Double): Double {
        if (featherPx <= 0) return 1.0
        val d = Math.min(Math.min(u + 0.5, w - 0.5 - u), Math.min(v + 0.5, h - 0.5 - v))
        if (d <= 0) return 0.0
        val t = Math.min(1.0, d / featherPx)
        return t * t * (3 - 2 * t)
    }

    private fun clamp(v: Int, lo: Int, hi: Int): Int = if (v < lo) lo else (if (v > hi) hi else v)
}
