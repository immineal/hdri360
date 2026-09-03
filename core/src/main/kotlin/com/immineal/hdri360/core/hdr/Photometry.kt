package com.immineal.hdri360.core.hdr

/**
 * Puts the radiance map on an absolute scale, in cd/m^2.
 *
 * The pipeline's output is linear but its scale is arbitrary: a pixel is
 * "v / e", a sensor fraction over a relative exposure. That is enough to light a
 * scene, because renderers only care about ratios, but it means two captures of
 * the same room cannot be compared and no value can be quoted in real units.
 *
 * The fix needs no new measurement - the exposure triangle is already recorded
 * with every frame. ISO 12232 defines saturation-based speed as
 *
 *     S = 78 / H_sat
 *
 * with H_sat the focal-plane exposure in lux-seconds that saturates the sensor,
 * and relates focal-plane exposure to scene luminance by
 *
 *     H = q * L * t / N^2
 *
 * where q folds in lens transmission, vignetting and the cos^4 falloff, and is
 * conventionally 0.65. Eliminating H gives the luminance that saturates the
 * sensor at a given setting, and hence the luminance of any sensor fraction.
 *
 * The pipeline has already divided the per-frame exposure out, so what survives
 * is remarkably simple: the scale depends only on the aperture and the sensor's
 * base ISO, not on how any individual frame was exposed.
 *
 * ## What this is not
 *
 * Only meaningful when the pixels really are a linear fraction of full well at a
 * known shutter and ISO - the RAW plus manual-sensor path. Where the camera
 * chose its own exposure, or applied its own tone curve, the inputs to this
 * arithmetic are not what they claim to be and the answer would be a number with
 * a unit attached and no meaning behind it.
 *
 * Accuracy is limited by [baseIso] being the sensor's true unity-gain
 * sensitivity: the result scales linearly with it, so a device that reports a
 * minimum ISO below its native base will read proportionally bright. Treat the
 * output as accurate to a fraction of a stop, not to a percent.
 */
object Photometry {

    /** ISO 12232 saturation-based speed constant, S = 78 / H_sat. */
    const val SATURATION_CONSTANT = 78.0

    /**
     * ISO 12232 lens factor q: transmission, vignetting and cos^4 falloff
     * together. 0.65 is the value the standard specifies.
     */
    const val LENS_FACTOR = 0.65

    /**
     * Reflected-light meter calibration constant K, for the middle-grey form
     * L = K N^2 / (t S). Conventionally 12.5 for Canon and Nikon, 14 for Sekonic.
     * Provided for cross-checking and for quoting a metered luminance; the
     * saturation form above is what the conversion actually uses, because a
     * sensor fraction is defined against full well, not against middle grey.
     */
    const val METER_CONSTANT = 12.5

    /**
     * Multiplier taking pipeline radiance (sensor fraction over relative
     * exposure, at [baseIso]) to cd/m^2.
     *
     * Independent of shutter and ISO because the merge already divided them out.
     */
    @JvmStatic
    @JvmOverloads
    fun luminanceScale(apertureN: Double, baseIso: Int,
                       lensFactor: Double = LENS_FACTOR): Double {
        if (!(apertureN > 0)) throw IllegalArgumentException("aperture must be positive")
        if (baseIso <= 0) throw IllegalArgumentException("base ISO must be positive")
        if (!(lensFactor > 0)) throw IllegalArgumentException("lens factor must be positive")
        return SATURATION_CONSTANT * apertureN * apertureN / (lensFactor * baseIso)
    }

    /** Scene luminance in cd/m^2 that just saturates the sensor at these settings. */
    @JvmStatic
    @JvmOverloads
    fun saturationLuminance(s: ExposureSettings, lensFactor: Double = LENS_FACTOR): Double {
        if (!(lensFactor > 0)) throw IllegalArgumentException("lens factor must be positive")
        return SATURATION_CONSTANT * s.apertureN * s.apertureN /
               (lensFactor * s.iso * s.exposureTimeSec)
    }

    /**
     * Scene luminance in cd/m^2 of a normalised sensor value at these settings.
     * [normalizedValue] is black at 0 and full well at 1.
     */
    @JvmStatic
    @JvmOverloads
    fun luminanceOf(normalizedValue: Double, s: ExposureSettings,
                    lensFactor: Double = LENS_FACTOR): Double =
        normalizedValue * saturationLuminance(s, lensFactor)

    /**
     * The luminance these settings would render as middle grey, L = K N^2 / (t S).
     *
     * This is what a hand-held meter reads, and it sits a fixed distance below
     * saturation - see [headroomStops] - which is the cross-check that the two
     * formulations describe the same physics.
     */
    @JvmStatic
    fun meteredLuminance(s: ExposureSettings): Double =
        METER_CONSTANT * s.apertureN * s.apertureN / (s.exposureTimeSec * s.iso)

    /**
     * Stops between the metered middle grey and sensor saturation, about 3.26.
     *
     * A constant of the two calibration constants alone, independent of any
     * exposure: it is the highlight headroom the ISO standards imply.
     */
    @JvmStatic
    @JvmOverloads
    fun headroomStops(lensFactor: Double = LENS_FACTOR): Double =
        ExposureSettings.log2(SATURATION_CONSTANT / (lensFactor * METER_CONSTANT))
}
