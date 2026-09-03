package com.immineal.hdri360.core.hdr

import java.util.Locale

/**
 * What one probe frame says about the scene, expressed in radiance so that
 * measurements taken at different exposures are directly comparable.
 *
 * Clipped and crushed measurements are bounds, not estimates, and are flagged as
 * such: over-trusting a clipped highlight is exactly how an HDRI ends up with a
 * flat white sun of the wrong intensity.
 */
class SceneStats(
    lowRadiance: Double,
    highRadiance: Double,
    @JvmField val medianRadiance: Double,
    @JvmField val clippedFraction: Double,
    @JvmField val blackFraction: Double,
    @JvmField val highlightsClipped: Boolean,
    @JvmField val shadowsCrushed: Boolean,
    /** Normalised pixel value at the high quantile in the frame this came from; NaN if synthetic. */
    @JvmField val highValue: Double
) {
    @JvmField val lowRadiance: Double = Math.max(1e-12, lowRadiance)
    @JvmField val highRadiance: Double = Math.max(Math.max(1e-12, lowRadiance), highRadiance)

    constructor(
        lowRadiance: Double, highRadiance: Double, medianRadiance: Double,
        clippedFraction: Double, blackFraction: Double,
        highlightsClipped: Boolean, shadowsCrushed: Boolean
    ) : this(lowRadiance, highRadiance, medianRadiance, clippedFraction, blackFraction,
        highlightsClipped, shadowsCrushed, Double.NaN)

    fun dynamicRangeEv(): Double = ExposureSettings.log2(highRadiance / lowRadiance)

    override fun toString(): String = String.format(Locale.US, "scene[%.4g .. %.4g, %.1f EV%s%s]",
        lowRadiance, highRadiance, dynamicRangeEv(),
        if (highlightsClipped) ", clipped" else "", if (shadowsCrushed) ", crushed" else "")

    companion object {
        /** Widest envelope covering every direction probed so far. */
        @JvmStatic
        fun union(parts: List<SceneStats>?): SceneStats {
            if (parts == null || parts.isEmpty())
                throw IllegalArgumentException("cannot take the union of no measurements")
            var lo = Double.MAX_VALUE
            var hi = 0.0
            var med = 0.0
            var clip = 0.0
            var black = 0.0
            var clipped = false
            var crushed = false
            for (s in parts) {
                lo = Math.min(lo, s.lowRadiance)
                hi = Math.max(hi, s.highRadiance)
                med += s.medianRadiance
                clip = Math.max(clip, s.clippedFraction)
                black = Math.max(black, s.blackFraction)
                clipped = clipped or s.highlightsClipped
                crushed = crushed or s.shadowsCrushed
            }
            return SceneStats(lo, hi, med / parts.size, clip, black, clipped, crushed)
        }
    }
}
