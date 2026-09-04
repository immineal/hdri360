package com.immineal.hdri360.core.hdr

import com.immineal.hdri360.core.image.ImageF
import com.immineal.hdri360.core.image.ImageOps

/**
 * Turns a probe frame into scene statistics, and drives the probe onto a usable
 * exposure. The controller is deliberately two-regime: while highlights are on
 * the rail the measured value carries no gradient, so it steps by fixed amounts
 * sized from how much of the frame is clipped; once off the rail it solves for
 * the exposure that lands the high quantile on target in a single step.
 */
object SceneMeter {

    @JvmStatic
    fun measure(frame: ImageF, relativeExposure: Double, cfg: MeterConfig): SceneStats {
        if (!(relativeExposure > 0)) throw IllegalArgumentException("exposure must be positive")
        val lum = if (frame.channels >= 3) ImageOps.luminance(frame) else frame
        val n = lum.width * lum.height

        var clipped = 0
        var black = 0
        for (i in 0 until n) {
            val v = lum.data[i * lum.channels]
            if (v >= cfg.saturationThreshold) clipped++
            if (v <= cfg.noiseFloor) black++
        }
        val clipFrac = clipped / n.toDouble()
        val blackFrac = black / n.toDouble()

        val highValue = ImageOps.percentile(lum, 0, cfg.highPercentile).toDouble()
        val lowValue = ImageOps.percentile(lum, 0, cfg.lowPercentile).toDouble()
        val medValue = ImageOps.percentile(lum, 0, 0.5).toDouble()

        // Both have to agree. The bare count catches a frame that is genuinely on
        // the rail, but on its own it also catches the handful of stuck pixels
        // every real sensor has - which no shutter speed removes, so the
        // controller stops down through its whole range chasing them. The high
        // quantile cannot be moved by a speckle, so requiring it to agree is what
        // separates a blown highlight from a hot pixel.
        val highlightsClipped = clipFrac > cfg.clipTolerance && highValue >= cfg.saturationThreshold
        val shadowsCrushed = blackFrac > cfg.blackTolerance

        // A clipped frame can only bound the true radiance from below.
        val highRadiance = (if (highlightsClipped) cfg.saturationThreshold else highValue) / relativeExposure
        // A crushed frame can only bound the true dark end from above.
        val lowRadiance = (if (shadowsCrushed) cfg.noiseFloor
                           else Math.max(lowValue, cfg.noiseFloor)) / relativeExposure
        val medRadiance = medValue / relativeExposure

        return SceneStats(lowRadiance, highRadiance, medRadiance, clipFrac, blackFrac,
            highlightsClipped, shadowsCrushed, highValue)
    }

    @JvmStatic
    fun isWellExposed(s: SceneStats, cfg: MeterConfig): Boolean {
        if (s.highlightsClipped) return false
        if (s.highValue.isNaN()) return true
        return s.highValue >= cfg.aeTargetLow && s.highValue <= cfg.aeTargetHigh
    }

    /**
     * A relative exposure for looking at the scene rather than measuring it.
     *
     * Metering puts the brightest tenth of a percent just under saturation so the
     * top of the range can be read; that is the correct exposure for a
     * measurement and a useless one for a viewfinder, since in an ordinary room
     * it leaves everything else black.
     *
     * The median is the right anchor when there is one. In a room with a window
     * most of the frame can quantise to zero at the metering exposure, and then
     * the median says nothing at all - so the fallback is the geometric mean of
     * the two ends, which is the middle of the scene in the space the scene
     * actually lives in.
     */
    @JvmStatic
    @JvmOverloads
    fun viewingRelativeExposure(s: SceneStats, target: Double = 0.18): Double {
        val anchor = if (s.medianRadiance > 1e-9) s.medianRadiance
                     else Math.sqrt(s.lowRadiance * s.highRadiance)
        if (!(anchor > 0) || !anchor.isFinite()) return Double.NaN
        return target / anchor
    }

    /** Next relative exposure to probe with. */
    @JvmStatic
    fun suggestRelativeExposure(s: SceneStats, current: Double, cfg: MeterConfig): Double {
        if (s.highlightsClipped) {
            // No usable signal at the top: step down by an amount sized from how
            // much of the frame is on the rail.
            if (s.clippedFraction > 0.20) return current / 8.0
            if (s.clippedFraction > 0.05) return current / 4.0
            return current / 2.0
        }
        val hv = if (s.highValue.isNaN()) 0.0 else s.highValue
        if (hv < 1e-9) return current * cfg.aeMaxStep
        var factor = cfg.aeTarget / hv
        factor = Math.max(1.0 / cfg.aeMaxStep, Math.min(cfg.aeMaxStep, factor))
        if (Math.abs(factor - 1.0) < 1e-9) factor = if (factor < 1) 0.999 else 1.001
        return current * factor
    }
}
