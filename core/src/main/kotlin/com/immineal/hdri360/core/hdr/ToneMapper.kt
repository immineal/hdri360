package com.immineal.hdri360.core.hdr

import com.immineal.hdri360.core.image.ImageF
import com.immineal.hdri360.core.image.ImageOps

/**
 * Radiance to display values, for the preview only.
 *
 * The HDRI itself is never tone mapped - that would throw away the entire point
 * of capturing it. This exists so the photographer can see on the phone whether
 * the shot worked.
 *
 * The key is derived from the image's own log-average luminance, so the preview
 * looks the same no matter what arbitrary scale the radiance happens to be in.
 */
object ToneMapper {

    /** Middle grey the auto exposure aims for. */
    const val MIDDLE_GREY = 0.18

    /**
     * Filmic curve (Reinhard extended with a shoulder). Monotone, hits 0 at 0,
     * and asymptotes to 1, so no highlight ever wraps around to black.
     */
    @JvmStatic
    fun filmic(x: Double): Double {
        if (!(x > 0)) return 0.0
        val a = 2.51; val b = 0.03; val c = 2.43; val d = 0.59; val e = 0.14
        val y = (x * (a * x + b)) / (x * (c * x + d) + e)
        return Math.max(0.0, Math.min(1.0, y))
    }

    /** Exposure multiplier that puts the image's log-average luminance on middle grey. */
    @JvmStatic
    fun autoKey(radiance: ImageF): Double {
        val lum = if (radiance.channels >= 3) ImageOps.luminance(radiance) else radiance
        var sum = 0.0
        var n = 0
        for (i in 0 until lum.width * lum.height) {
            val v = lum.data[i * lum.channels].toDouble()
            if (!(v > 0) || v.isNaN()) continue
            sum += Math.log(v + 1e-9)
            n++
        }
        if (n == 0) return 1.0
        val logAverage = Math.exp(sum / n)
        if (!(logAverage > 0) || !logAverage.isFinite()) return 1.0
        return MIDDLE_GREY / logAverage
    }

    /** Applies exposure, the filmic curve and a display gamma. */
    @JvmStatic
    fun toDisplay(radiance: ImageF, key: Double, gamma: Double): ImageF {
        val out = radiance.sameShape()
        val invGamma = 1.0 / Math.max(1e-6, gamma)
        for (i in radiance.data.indices) {
            val v = radiance.data[i] * key
            val mapped = filmic(v)
            out.data[i] = Math.pow(mapped, invGamma).toFloat()
        }
        return out
    }

    /** Quantises display values to 8 bits. */
    @JvmStatic
    fun toBytes(display: ImageF): ByteArray {
        val out = ByteArray(display.data.size)
        for (i in out.indices) {
            val v = Math.round(Math.max(0.0, Math.min(1.0, display.data[i].toDouble())) * 255.0).toInt()
            out[i] = v.toByte()
        }
        return out
    }

    /** Convenience: radiance straight to an 8-bit sRGB-ish preview buffer. */
    @JvmStatic
    fun preview(radiance: ImageF): ByteArray =
        toBytes(toDisplay(radiance, autoKey(radiance), 2.2))
}
