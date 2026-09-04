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
        /**
         * Largest block of rows accumulated at once, in bytes.
         *
         * The composite is built one frame at a time, which means the running sums
         * for a block of output rows have to stay resident while every frame is
         * walked over them. That block is the only thing here that scales with the
         * output, so it is what gets bounded; the frames themselves come and go one
         * at a time however big the sphere is.
         */
        @JvmField var maxAccumulatorBytes = 48L shl 20
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
        if (frames == null || frames.isEmpty()) return null
        return buildSeamMap(FrameSet.of(frames), cfg)
    }

    @JvmStatic
    fun buildSeamMap(frames: FrameSet, cfg: Config): SeamMap? {
        if (frames.size < 2) return null
        if (cfg.seamWidth <= 0) return null
        val sw = cfg.seamWidth
        val sh = Equirect.heightFor(sw)
        if (sw != 2 * sh) throw IllegalArgumentException("seam width must be even and 2:1")

        val nf = frames.size
        // Most directions are seen by a handful of frames; a pixel that somehow
        // exceeds this simply keeps the strongest candidates.
        val maxCand = Math.min(nf, 8)
        val problem = SeamFinder.Problem(sw, sh, nf, maxCand)

        // One frame at a time, exactly as the render does - and in frame order, so
        // each direction's candidate list comes out in the same order it would
        // have from the pixel-major walk. The seam solve depends on that order.
        val cone = Cone(frames)
        val lonTable = Equirect.longitudeTable(sw)
        for (fi in 0 until nf) {
            if (!cone.touchesRows(fi, 0, sh, sh)) continue
            val f = frames.open(fi)
            val iw = f.radiance.width
            val ih = f.radiance.height
            val ch = f.radiance.channels
            val conf = f.confidence
            val rot = f.rotation
            val k = f.intrinsics
            val gain = f.gain
            Parallel.forRanges(sh, 512) { bandFrom, bandTo ->
            val cam = DoubleArray(3)
            val p = DoubleArray(2)
            for (y in bandFrom until bandTo) {
                val lat = Equirect.latitudeOf(y.toDouble(), sh)
                val sinLat = Math.sin(lat)
                val cosLat = Math.cos(lat)
                for (x in 0 until sw) {
                    val dx = -lonTable[2 * x] * cosLat
                    val dz = lonTable[2 * x + 1] * cosLat
                    if (dx * cone.ax[fi] + sinLat * cone.ay[fi] + dz * cone.az[fi] <
                        cone.cosLimit[fi]) continue
                    rot.mulTranspose(dx, sinLat, dz, cam)
                    if (!(cam[2] > 1e-9)) continue
                    if (!k.project(cam[0], cam[1], cam[2], p)) continue
                    val u = p[0]; val v = p[1]
                    if (u < -0.5 || v < -0.5 || u > iw - 0.5 || v > ih - 0.5) continue
                    var weight = featherWeight(u, v, iw, ih, cfg.featherPx)
                    if (weight <= 0) continue
                    if (conf != null) {
                        val cx = clamp(Math.round(u).toInt(), 0, iw - 1)
                        val cy = clamp(Math.round(v).toInt(), 0, ih - 1)
                        weight *= conf[cy * iw + cx].toDouble()
                    }
                    if (weight <= 0) continue
                    // Log radiance, because the seam cost has to mean the same thing
                    // in the sky and in the shadows. Gain is applied first so two
                    // frames are compared after photometric alignment, not before.
                    var lum = if (ch >= 3)
                        (ImageOps.LUMA_R * f.radiance.sampleBilinear(u, v, 0) +
                         ImageOps.LUMA_G * f.radiance.sampleBilinear(u, v, 1) +
                         ImageOps.LUMA_B * f.radiance.sampleBilinear(u, v, 2)).toDouble()
                    else f.radiance.sampleBilinear(u, v, 0).toDouble()
                    lum *= gain
                    val logLum = Math.log(Math.max(1e-8, lum)).toFloat()
                    problem.add(y * sw + x, fi, logLum, weight.toFloat())
                }
            }
            }
            frames.release(fi)
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
        return renderRows(FrameSet.of(frames), cfg, rowStart, rowEnd, seam)
    }

    /**
     * The same render, over frames that are fetched one at a time.
     *
     * Walking frames on the outside rather than the inside is what bounds the
     * memory: the running sums for a block of rows stay resident, each frame is
     * opened, added and dropped, and the peak is one frame however many there
     * are. The arithmetic is unchanged - every pixel still accumulates its frames
     * in ascending frame order - so this produces the same numbers as asking
     * every frame about every pixel would.
     */
    @JvmStatic
    @JvmOverloads
    fun renderRows(frames: FrameSet, cfg: Config, rowStart: Int, rowEnd: Int,
                   seam: SeamMap? = null): Result {
        val nf = frames.size
        if (nf == 0) throw IllegalArgumentException("no frames to render")
        val w = cfg.width
        val h = Equirect.heightFor(w)
        if (w != 2 * h) throw IllegalArgumentException("panorama width must be even and 2:1")
        if (rowStart < 0 || rowEnd > h || rowEnd <= rowStart)
            throw IllegalArgumentException(
                "bad row range " + rowStart + ".." + rowEnd + " for height " + h)
        val channels = frames.optics(0).channels
        for (i in 0 until nf)
            if (frames.optics(i).channels != channels)
                throw IllegalArgumentException("frames differ in channel count")

        val rows = rowEnd - rowStart
        val out = ImageF(w, rows, channels)
        val coverage = FloatArray(w * rows)
        val contributors = ShortArray(w * rows)

        val cone = Cone(frames)
        val lonTable = Equirect.longitudeTable(w)

        // Sums per output pixel: the radiance accumulator, the weight, and the
        // contributor count. This is the only thing that grows with the output.
        val bytesPerPixel = 8L * channels + 8L + 4L
        val bandRows = Math.max(1, Math.min(rows.toLong(),
            cfg.maxAccumulatorBytes / (bytesPerPixel * w)).toInt())
        val acc = DoubleArray(bandRows * w * channels)
        val wsum = DoubleArray(bandRows * w)
        val hits = IntArray(bandRows * w)

        var y0 = rowStart
        while (y0 < rowEnd) {
            val y1 = Math.min(rowEnd, y0 + bandRows)
            val bandPixels = (y1 - y0) * w
            Arrays.fill(acc, 0, bandPixels * channels, 0.0)
            Arrays.fill(wsum, 0, bandPixels, 0.0)
            Arrays.fill(hits, 0, bandPixels, 0)

            for (fi in 0 until nf) {
                if (!cone.touchesRows(fi, y0, y1, h)) continue
                val f = frames.open(fi)
                accumulate(f, fi, cone, lonTable, cfg, seam, w, h, channels,
                    y0, y1, acc, wsum, hits)
                frames.release(fi)
            }

            Parallel.forRanges(y1 - y0) { from, to ->
            for (y in from until to) {
                val src = y * w
                val dst = (y0 - rowStart + y) * w
                for (x in 0 until w) {
                    val i = src + x
                    val o = dst + x
                    coverage[o] = wsum[i].toFloat()
                    contributors[o] = Math.min(Short.MAX_VALUE.toInt(), hits[i]).toShort()
                    if (wsum[i] > 0)
                        for (c in 0 until channels)
                            out.data[o * channels + c] =
                                (acc[i * channels + c] / wsum[i]).toFloat()
                }
            }
            }
            y0 = y1
        }
        return Result(out, coverage, contributors)
    }

    /** Adds one frame's contribution to the running sums for rows [y0, y1). */
    private fun accumulate(f: FrameSource, fi: Int, cone: Cone, lonTable: DoubleArray,
                           cfg: Config, seam: SeamMap?, w: Int, h: Int, channels: Int,
                           y0: Int, y1: Int,
                           acc: DoubleArray, wsum: DoubleArray, hits: IntArray) {
        val iw = f.radiance.width
        val ih = f.radiance.height
        val conf = f.confidence
        val rot = f.rotation
        val k = f.intrinsics
        val gain = f.gain
        val ax = cone.ax[fi]
        val ay = cone.ay[fi]
        val az = cone.az[fi]
        val cosLimit = cone.cosLimit[fi]

        Parallel.forRanges(y1 - y0) { from, to ->
        val cam = DoubleArray(3)
        val p = DoubleArray(2)
        for (yy in from until to) {
            val y = y0 + yy
            val lat = Equirect.latitudeOf(y.toDouble(), h)
            val sinLat = Math.sin(lat)
            val cosLat = Math.cos(lat)
            val rowBase = yy * w
            for (x in 0 until w) {
                val dx = -lonTable[2 * x] * cosLat
                val dz = lonTable[2 * x + 1] * cosLat
                if (dx * ax + sinLat * ay + dz * az < cosLimit) continue
                rot.mulTranspose(dx, sinLat, dz, cam)
                if (!(cam[2] > 1e-9)) continue
                if (!k.project(cam[0], cam[1], cam[2], p)) continue
                val u = p[0]
                val v = p[1]
                if (u < -0.5 || v < -0.5 || u > iw - 0.5 || v > ih - 0.5) continue

                var weight = featherWeight(u, v, iw, ih, cfg.featherPx)
                if (weight <= 0) continue
                if (cfg.cosinePower > 0) {
                    val n = Math.sqrt(cam[0] * cam[0] + cam[1] * cam[1] + cam[2] * cam[2])
                    val cos = cam[2] * (1.0 / n)
                    weight *= Math.pow(Math.max(0.0, cos), cfg.cosinePower)
                }
                if (conf != null) {
                    val cx = clamp(Math.round(u).toInt(), 0, iw - 1)
                    val cy = clamp(Math.round(v).toInt(), 0, ih - 1)
                    weight *= conf[cy * iw + cx].toDouble()
                }
                if (seam != null) {
                    weight *= seam.shareAt(fi, (x + 0.5) / w, (y + 0.5) / h)
                }
                if (weight <= 0) continue

                val i = rowBase + x
                for (c in 0 until channels)
                    acc[i * channels + c] += weight * gain * f.radiance.sampleBilinear(u, v, c)
                wsum[i] += weight
                hits[i]++
            }
        }
        }
    }

    /**
     * Each frame's viewing cone, and the band of latitudes it can possibly reach.
     *
     * The latitude bounds are what make a frame-at-a-time render affordable: a
     * frame near the horizon cannot contribute to the rows around the zenith, so
     * it is never opened for them. The test is exact rather than conservative -
     * a direction outside the cone's latitude band is outside the cone, so the
     * pixels skipped here are precisely the ones the per-pixel test would have
     * rejected anyway.
     */
    private class Cone(frames: FrameSet) {
        @JvmField val ax: DoubleArray
        @JvmField val ay: DoubleArray
        @JvmField val az: DoubleArray
        @JvmField val cosLimit: DoubleArray
        private val latMin: DoubleArray
        private val latMax: DoubleArray

        init {
            val n = frames.size
            ax = DoubleArray(n); ay = DoubleArray(n); az = DoubleArray(n)
            cosLimit = DoubleArray(n)
            latMin = DoubleArray(n); latMax = DoubleArray(n)
            for (i in 0 until n) {
                val o = frames.optics(i)
                val axis = o.rotation.mul(Vec3(0.0, 0.0, 1.0))
                ax[i] = axis.x; ay[i] = axis.y; az[i] = axis.z
                val alpha = o.intrinsics.maxAngleFromAxisRad() + 1e-3
                cosLimit[i] = Math.cos(Math.min(Math.PI, alpha))
                val lat = Math.asin(Math.max(-1.0, Math.min(1.0, axis.y)))
                latMin[i] = lat - alpha
                latMax[i] = lat + alpha
            }
        }

        fun touchesRows(i: Int, y0: Int, y1: Int, height: Int): Boolean {
            if (latMax[i] - latMin[i] >= Math.PI) return true
            val top = Equirect.latitudeOf(y0.toDouble(), height)
            val bottom = Equirect.latitudeOf((y1 - 1).toDouble(), height)
            return latMax[i] >= bottom && latMin[i] <= top
        }
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
