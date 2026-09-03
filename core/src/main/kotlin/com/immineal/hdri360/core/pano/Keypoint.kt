package com.immineal.hdri360.core.pano

import java.util.Locale

/** A detected corner: sub-pixel position, corner strength, and dominant orientation. */
class Keypoint(
    @JvmField val x: Float,
    @JvmField val y: Float,
    @JvmField val score: Float,
    @JvmField val angle: Float
) {
    fun withAngle(a: Float) = Keypoint(x, y, score, a)

    override fun toString(): String = String.format(Locale.US, "kp(%.1f, %.1f, s=%.3f)", x, y, score)
}
