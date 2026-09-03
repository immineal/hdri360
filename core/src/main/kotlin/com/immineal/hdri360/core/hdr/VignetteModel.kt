package com.immineal.hdri360.core.hdr

import java.util.Locale

/**
 * Radial illumination falloff, as a factor in (0,1] applied to the true radiance
 * before the sensor sees it.
 *
 * Uncorrected vignetting is the single most visible stitching artefact in a
 * panorama: every frame darkens toward its own corners, so the seams turn into a
 * grid of dark ribs no amount of feathering hides.
 */
class VignetteModel private constructor(
    @JvmField val a2: Double,
    @JvmField val a4: Double
) {

    fun isIdentity(): Boolean = a2 == 0.0 && a4 == 0.0

    fun falloff(x: Double, y: Double, width: Int, height: Int): Double {
        val cx = (width - 1) * 0.5
        val cy = (height - 1) * 0.5
        val dx = x - cx
        val dy = y - cy
        val norm = cx * cx + cy * cy
        val r2 = if (norm <= 0) 0.0 else (dx * dx + dy * dy) / norm
        val f = 1.0 + a2 * r2 + a4 * r2 * r2
        return Math.max(1e-3, f)
    }

    /** Falloff as a function of normalised radius, where 1 is the image corner. */
    fun falloffAtRadius(rNorm: Double): Double {
        val r2 = rNorm * rNorm
        return Math.max(1e-3, 1.0 + a2 * r2 + a4 * r2 * r2)
    }

    /** Precomputed falloff map, since it is the same for every frame of a shoot. */
    fun falloffMap(width: Int, height: Int): FloatArray {
        val m = FloatArray(width * height)
        for (y in 0 until height)
            for (x in 0 until width)
                m[y * width + x] = falloff(x.toDouble(), y.toDouble(), width, height).toFloat()
        return m
    }

    override fun toString(): String = String.format(Locale.US,
        "vignette[a2=%.4f a4=%.4f corner=%.3f]", a2, a4, 1 + a2 + a4)

    companion object {
        @JvmStatic
        fun none() = VignetteModel(0.0, 0.0)

        /** falloff(r) = 1 + a2 r^2 + a4 r^4 with r normalised so the corner is r = 1. */
        @JvmStatic
        fun radial(a2: Double, a4: Double) = VignetteModel(a2, a4)

        /** Normalised radius of a pixel: 0 at the optical centre, 1 at a corner. */
        @JvmStatic
        fun normalizedRadius(x: Double, y: Double, width: Int, height: Int): Double {
            val cx = (width - 1) * 0.5
            val cy = (height - 1) * 0.5
            val dx = x - cx
            val dy = y - cy
            val norm = cx * cx + cy * cy
            return if (norm <= 0) 0.0 else Math.sqrt((dx * dx + dy * dy) / norm)
        }
    }
}
