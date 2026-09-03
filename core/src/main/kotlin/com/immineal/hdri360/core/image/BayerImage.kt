package com.immineal.hdri360.core.image

/**
 * A single-plane mosaiced image in linear sensor units (black level already
 * subtracted, normalised so that 1.0 is the white level).
 *
 * Brackets are merged in this domain rather than after demosaicing: the frames
 * of a bracket share a pose, so the merge is per-pixel, and demosaicing once at
 * the end avoids smearing clipped and unclipped samples into each other.
 */
class BayerImage {
    @JvmField val width: Int
    @JvmField val height: Int
    @JvmField val pattern: CfaPattern
    @JvmField val plane: ImageF

    constructor(width: Int, height: Int, pattern: CfaPattern) {
        this.width = width; this.height = height; this.pattern = pattern
        this.plane = ImageF(width, height, 1)
    }

    constructor(width: Int, height: Int, pattern: CfaPattern, data: FloatArray) {
        this.width = width; this.height = height; this.pattern = pattern
        this.plane = ImageF(width, height, 1, data)
    }

    fun get(x: Int, y: Int): Float = plane.data[y * width + x]
    fun set(x: Int, y: Int, v: Float) { plane.data[y * width + x] = v }
    fun colorAt(x: Int, y: Int): Int = pattern.colorAt(x, y)
}
