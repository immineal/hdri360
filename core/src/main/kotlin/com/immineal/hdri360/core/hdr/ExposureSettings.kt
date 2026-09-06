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

    /**
     * The shutter as a photographer writes it, which is a fraction only while a
     * fraction still says something.
     *
     * `1/%.0f` unconditionally is fine to about half a second and nonsense past
     * it: two seconds rounds to `1/0s` and so does sixteen. That reached the log
     * of a real capture as `1/3s | 1/0s | 1/0s` on the evening spent working out
     * why its bursts were timing out - three stops apart and printed the same, in
     * the one record there was to go on.
     */
    override fun toString(): String =
        if (exposureTimeSec >= 0.5)
            String.format(Locale.US, "%.1fs ISO%d f/%.1f", exposureTimeSec, iso, apertureN)
        else
            String.format(Locale.US, "1/%.0fs ISO%d f/%.1f",
                1.0 / exposureTimeSec, iso, apertureN)

    companion object {
        @JvmStatic
        fun log2(v: Double): Double = Math.log(v) / Math.log(2.0)
    }
}
