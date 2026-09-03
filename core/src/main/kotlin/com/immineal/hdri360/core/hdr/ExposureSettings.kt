package com.immineal.hdri360.core.hdr

import java.util.Locale

/** One capture's exposure triangle. Aperture is fixed on phone modules but carried for EV bookkeeping. */
class ExposureSettings(
    @JvmField val exposureTimeSec: Double,
    @JvmField val iso: Int,
    @JvmField val apertureN: Double
) {
    init {
        if (!(exposureTimeSec > 0)) throw IllegalArgumentException("exposure time must be positive")
        if (iso <= 0) throw IllegalArgumentException("ISO must be positive")
        if (!(apertureN > 0)) throw IllegalArgumentException("aperture must be positive")
    }

    /**
     * Total light-gathering factor relative to base ISO: the single number the
     * merge divides by to turn pixel values into radiance.
     */
    fun relativeExposure(baseIso: Int): Double = exposureTimeSec * iso / baseIso.toDouble()

    /** Analog+digital gain relative to base ISO; drives the noise model. */
    fun gain(baseIso: Int): Double = iso / baseIso.toDouble()

    /** Standard EV at ISO 100. Larger EV means a darker exposure. */
    fun ev100(): Double = log2(apertureN * apertureN / exposureTimeSec) - log2(iso / 100.0)

    /** Nanoseconds, the unit Camera2 wants. */
    fun exposureTimeNs(): Long = Math.round(exposureTimeSec * 1e9)

    override fun toString(): String =
        String.format(Locale.US, "1/%.0fs ISO%d f/%.1f", 1.0 / exposureTimeSec, iso, apertureN)

    companion object {
        @JvmStatic
        fun log2(v: Double): Double = Math.log(v) / Math.log(2.0)
    }
}
