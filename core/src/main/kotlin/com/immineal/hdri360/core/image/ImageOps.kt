package com.immineal.hdri360.core.image

import java.util.Arrays

/** Small image utilities used by metering, feature detection and blending. */
object ImageOps {

    /** Rec.709 luma weights; the pipeline is linear so these apply to radiance. */
    const val LUMA_R = 0.2126f
    const val LUMA_G = 0.7152f
    const val LUMA_B = 0.0722f

    @JvmStatic
    fun luminance(src: ImageF): ImageF {
        val out = ImageF(src.width, src.height, 1)
        val n = src.width * src.height
        if (src.channels >= 3) {
            for (i in 0 until n) {
                val b = i * src.channels
                out.data[i] = LUMA_R * src.data[b] + LUMA_G * src.data[b + 1] + LUMA_B * src.data[b + 2]
            }
        } else {
            for (i in 0 until n) out.data[i] = src.data[i * src.channels]
        }
        return out
    }

    /** Box-average 2x2 decimation. Odd trailing rows/columns are dropped. */
    @JvmStatic
    fun downsample2x(src: ImageF): ImageF {
        val w = src.width / 2
        val h = src.height / 2
        val c = src.channels
        if (w < 1 || h < 1) throw IllegalArgumentException("image too small to halve")
        val out = ImageF(w, h, c)
        for (y in 0 until h) {
            for (x in 0 until w) {
                for (ch in 0 until c) {
                    val s = src.get(2 * x, 2 * y, ch) + src.get(2 * x + 1, 2 * y, ch) +
                            src.get(2 * x, 2 * y + 1, ch) + src.get(2 * x + 1, 2 * y + 1, ch)
                    out.set(x, y, ch, s * 0.25f)
                }
            }
        }
        return out
    }

    /** Repeated halving until both dimensions are at or below [maxDim]. */
    @JvmStatic
    fun downsampleTo(src: ImageF, maxDim: Int): ImageF {
        var cur = src
        while (Math.max(cur.width, cur.height) > maxDim && cur.width >= 2 && cur.height >= 2) {
            cur = downsample2x(cur)
        }
        return cur
    }

    /** Value below which the given fraction of samples of one channel falls. */
    @JvmStatic
    fun percentile(src: ImageF, channel: Int, fraction: Double): Float {
        val n = src.width * src.height
        val v = FloatArray(n)
        for (i in 0 until n) v[i] = src.data[i * src.channels + channel]
        Arrays.sort(v)
        val idx = fraction * (n - 1)
        val lo = Math.floor(idx).toInt()
        val hi = Math.min(lo + 1, n - 1)
        val f = idx - lo
        return (v[lo] + (v[hi] - v[lo]) * f).toFloat()
    }

    /**
     * Separable Gaussian with clamped borders, renormalised per pixel so a flat
     * field stays flat all the way into the corners.
     */
    @JvmStatic
    fun gaussianBlur(src: ImageF, sigma: Double): ImageF {
        if (sigma <= 0) return src.copy()
        val radius = Math.max(1, Math.ceil(sigma * 3).toInt())
        val k = DoubleArray(2 * radius + 1)
        var sum = 0.0
        for (i in -radius..radius) {
            k[i + radius] = Math.exp(-0.5 * (i * i) / (sigma * sigma))
            sum += k[i + radius]
        }
        for (i in k.indices) k[i] /= sum

        val tmp = src.sameShape()
        val out = src.sameShape()
        val w = src.width; val h = src.height; val c = src.channels
        for (y in 0 until h)
            for (x in 0 until w)
                for (ch in 0 until c) {
                    var acc = 0.0
                    for (i in -radius..radius) {
                        val xx = clamp(x + i, 0, w - 1)
                        acc += k[i + radius] * src.get(xx, y, ch)
                    }
                    tmp.set(x, y, ch, acc.toFloat())
                }
        for (y in 0 until h)
            for (x in 0 until w)
                for (ch in 0 until c) {
                    var acc = 0.0
                    for (i in -radius..radius) {
                        val yy = clamp(y + i, 0, h - 1)
                        acc += k[i + radius] * tmp.get(x, yy, ch)
                    }
                    out.set(x, y, ch, acc.toFloat())
                }
        return out
    }

    @JvmStatic
    fun clamp(v: Int, lo: Int, hi: Int): Int = if (v < lo) lo else (if (v > hi) hi else v)

    @JvmStatic
    fun clamp(v: Double, lo: Double, hi: Double): Double = if (v < lo) lo else (if (v > hi) hi else v)

    @JvmStatic
    fun max(src: ImageF): Float {
        var m = Float.NEGATIVE_INFINITY
        for (v in src.data) if (v > m) m = v
        return m
    }

    @JvmStatic
    fun min(src: ImageF): Float {
        var m = Float.POSITIVE_INFINITY
        for (v in src.data) if (v < m) m = v
        return m
    }
}
