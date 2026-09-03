package com.immineal.hdri360.core.image

import java.util.Arrays

/**
 * Interleaved float image. Everything downstream of the sensor is float and
 * linear: merged radiance routinely spans six orders of magnitude, so there is
 * no 8-bit stage anywhere in the pipeline until the preview is tone mapped.
 *
 * Pixel coordinates are fractional pixel indices: pixel i is centred at i.
 *
 * [data] is public and written in place by nearly every consumer. That is
 * deliberate: wrapping it would allocate in the hot loops.
 */
class ImageF {
    @JvmField val width: Int
    @JvmField val height: Int
    @JvmField val channels: Int
    @JvmField val data: FloatArray

    constructor(width: Int, height: Int, channels: Int) {
        if (width <= 0 || height <= 0 || channels <= 0)
            throw IllegalArgumentException("image dimensions must be positive")
        this.width = width
        this.height = height
        this.channels = channels
        this.data = FloatArray(width * height * channels)
    }

    /** Adopts [data] without copying. */
    constructor(width: Int, height: Int, channels: Int, data: FloatArray) {
        if (data.size != width * height * channels)
            throw IllegalArgumentException("data length does not match dimensions")
        this.width = width; this.height = height; this.channels = channels; this.data = data
    }

    fun index(x: Int, y: Int, c: Int): Int = (y * width + x) * channels + c

    fun get(x: Int, y: Int, c: Int): Float = data[(y * width + x) * channels + c]

    fun set(x: Int, y: Int, c: Int, v: Float) { data[(y * width + x) * channels + c] = v }

    fun add(x: Int, y: Int, c: Int, v: Float) { data[(y * width + x) * channels + c] += v }

    fun fill(v: Float) { Arrays.fill(data, v) }

    fun copy(): ImageF = ImageF(width, height, channels, data.copyOf())

    fun sameShape(): ImageF = ImageF(width, height, channels)

    /** True if the coordinate is inside the sampled domain [0, w-1] x [0, h-1]. */
    fun contains(x: Double, y: Double): Boolean =
        x >= 0 && y >= 0 && x <= width - 1 && y <= height - 1

    /** Bilinear sample with edge clamping. */
    fun sampleBilinear(x: Double, y: Double, c: Int): Float {
        var px = x
        var py = y
        if (px < 0) px = 0.0 else if (px > width - 1) px = (width - 1).toDouble()
        if (py < 0) py = 0.0 else if (py > height - 1) py = (height - 1).toDouble()
        val x0 = px.toInt()
        val y0 = py.toInt()
        val x1 = Math.min(x0 + 1, width - 1)
        val y1 = Math.min(y0 + 1, height - 1)
        val fx = px - x0
        val fy = py - y0
        val s = channels
        val i00 = (y0 * width + x0) * s + c; val i10 = (y0 * width + x1) * s + c
        val i01 = (y1 * width + x0) * s + c; val i11 = (y1 * width + x1) * s + c
        val top = data[i00] + (data[i10] - data[i00]) * fx
        val bot = data[i01] + (data[i11] - data[i01]) * fx
        return (top + (bot - top) * fy).toFloat()
    }

    /** Bilinear sample of all channels into [out]. */
    fun sampleBilinear(x: Double, y: Double, out: FloatArray) {
        var c = 0
        while (c < channels && c < out.size) { out[c] = sampleBilinear(x, y, c); c++ }
    }
}
