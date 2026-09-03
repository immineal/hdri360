package com.immineal.hdri360.core.hdr

import com.immineal.hdri360.core.image.ImageF

/** One frame of a bracket: the pixels plus everything needed to turn them into radiance. */
class Exposure(
    @JvmField val image: ImageF,
    /** Total light-gathering factor, t * ISO/baseISO. */
    @JvmField val relativeExposure: Double,
    /** Analog gain relative to base ISO, for the noise model. */
    @JvmField val gain: Double
) {
    init {
        if (!(relativeExposure > 0))
            throw IllegalArgumentException("relative exposure must be positive")
        if (!(gain > 0)) throw IllegalArgumentException("gain must be positive")
    }

    companion object {
        @JvmStatic
        fun of(image: ImageF, s: ExposureSettings, baseIso: Int) =
            Exposure(image, s.relativeExposure(baseIso), s.gain(baseIso))
    }
}
