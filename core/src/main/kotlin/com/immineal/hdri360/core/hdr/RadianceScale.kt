package com.immineal.hdri360.core.hdr

import java.util.Locale

/**
 * What the numbers in a radiance map actually mean.
 *
 * A linear radiance map is useful without an absolute scale - renderers only
 * need ratios - so most captures legitimately carry no units. But a value in
 * cd/m^2 and a value on an arbitrary scale look identical once written to a
 * file, and quoting the second as though it were the first is the sort of
 * mistake that is invisible until someone builds on it.
 *
 * So the distinction is carried in the type rather than in a comment or a
 * convention: [toCdPerM2] refuses to answer when the scale is not absolute. A
 * caller that wants real units has to have established that it can have them.
 *
 * Absolute calibration requires the whole capture to be a genuine measurement:
 * RAW pixels that are a linear fraction of full well, at a shutter and ISO the
 * app itself chose. Where the camera picked its own exposure or applied its own
 * tone curve - anything below the top capability tier - the inputs to the
 * arithmetic are not what they claim, and the honest answer is [relative].
 */
class RadianceScale private constructor(
    /** cd/m^2 per unit of pipeline radiance. Zero when the scale is arbitrary. */
    @JvmField val cdPerM2PerUnit: Double,
    @JvmField val absolute: Boolean,
    /** Human-readable account of where the scale came from, or why there is none. */
    @JvmField val basis: String
) {

    /**
     * @throws IllegalStateException when the scale is arbitrary. That is the point
     *   of this class: a relative capture cannot be coaxed into producing units.
     */
    fun toCdPerM2(radiance: Double): Double {
        if (!absolute) throw IllegalStateException(
            "this capture has no absolute scale: $basis")
        return radiance * cdPerM2PerUnit
    }

    override fun toString(): String =
        if (absolute) String.format(Locale.US, "absolute[%.4g cd/m2 per unit; %s]",
            cdPerM2PerUnit, basis)
        else "relative[$basis]"

    companion object {
        /** The usual case: linear, comparable within itself, in no particular units. */
        @JvmStatic
        fun relative(why: String) = RadianceScale(0.0, false, why)

        /**
         * Calibrated from the exposure triangle. See [Photometry] for the physics.
         *
         * [baseIso] must be the sensor's true unity-gain sensitivity: the result
         * scales linearly with it, and a device that reports a minimum ISO below
         * its native base will read proportionally bright.
         */
        @JvmStatic
        @JvmOverloads
        fun absolute(apertureN: Double, baseIso: Int,
                     lensFactor: Double = Photometry.LENS_FACTOR): RadianceScale {
            val scale = Photometry.luminanceScale(apertureN, baseIso, lensFactor)
            return RadianceScale(scale, true, String.format(Locale.US,
                "ISO 12232 saturation speed, f/%.1f, base ISO %d, q=%.2f",
                apertureN, baseIso, lensFactor))
        }
    }
}
