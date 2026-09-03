package com.immineal.hdri360.core.pano

import com.immineal.hdri360.core.image.ImageF
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

    @JvmStatic
    fun render(frames: List<FrameSource>?, cfg: Config): Result =
        renderRows(frames, cfg, 0, Equirect.heightFor(cfg.width))

    /**
     * Renders the horizontal strip [rowStart, rowEnd) of the panorama.
     *
     * Strips let the output be streamed straight to a file: a 4096-wide float RGB
     * panorama is 100 MB, which on a phone is the difference between finishing
     * and being killed. Every strip sees the full frame list, so the result is
     * identical to rendering the whole canvas at once.
     */
    @JvmStatic
    fun renderRows(frames: List<FrameSource>?, cfg: Config, rowStart: Int, rowEnd: Int): Result {
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
        val acc = DoubleArray(channels)

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

        for (y in rowStart until rowEnd) {
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
