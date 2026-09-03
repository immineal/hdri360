package com.immineal.hdri360.core.hdr

/**
 * Affine sensor noise: variance grows linearly with signal (photon shot noise)
 * on top of a floor (read noise). Both terms are expressed in normalised units
 * and scaled by the analog gain, which is what makes an ISO 800 frame carry less
 * weight than an ISO 50 frame of the same brightness.
 */
class NoiseModel(
    /** Shot-noise coefficient: variance per unit signal at base gain. */
    @JvmField val shotCoef: Double,
    /** Read-noise variance at base gain. */
    @JvmField val readVar: Double
) {
    init {
        if (shotCoef < 0 || readVar < 0)
            throw IllegalArgumentException("noise terms must be non-negative")
    }

    fun variance(normalizedValue: Double, gain: Double): Double {
        val v = Math.max(0.0, normalizedValue)
        return shotCoef * gain * v + readVar * gain * gain
    }

    fun sigma(normalizedValue: Double, gain: Double): Double =
        Math.sqrt(variance(normalizedValue, gain))

    companion object {
        /** Default for a modern ~12-bit phone sensor: shot-limited above roughly 1% signal. */
        @JvmStatic
        fun typicalPhone() = NoiseModel(1e-4, 4e-6)
    }
}
