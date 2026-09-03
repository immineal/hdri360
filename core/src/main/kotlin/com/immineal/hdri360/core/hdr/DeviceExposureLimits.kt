package com.immineal.hdri360.core.hdr

import java.util.Locale

/**
 * What the hardware will actually accept, plus the one policy number that is not
 * hardware at all: the longest shutter a hand can hold. Everything the planner
 * asks for is passed through [realize] so no impossible request ever reaches the
 * camera.
 */
class DeviceExposureLimits(
    @JvmField val minExposureTimeSec: Double,
    @JvmField val maxExposureTimeSec: Double,
    @JvmField val minIso: Int,
    @JvmField val maxIso: Int,
    @JvmField val baseIso: Int,
    @JvmField val apertureN: Double,
    maxHandheldTimeSec: Double
) {
    @JvmField val maxHandheldTimeSec: Double

    init {
        if (!(minExposureTimeSec > 0) || maxExposureTimeSec < minExposureTimeSec)
            throw IllegalArgumentException("bad exposure time range")
        if (minIso <= 0 || maxIso < minIso || baseIso < minIso || baseIso > maxIso)
            throw IllegalArgumentException("bad ISO range")
        this.maxHandheldTimeSec = Math.min(maxHandheldTimeSec, maxExposureTimeSec)
    }

    fun minRelativeExposure(): Double = minExposureTimeSec * minIso / baseIso.toDouble()

    fun maxRelativeExposure(): Double = maxExposureTimeSec * maxIso / baseIso.toDouble()

    /**
     * Nearest achievable settings for a requested relative exposure.
     *
     * Policy, in order: spend shutter time at base ISO for the cleanest signal;
     * once past the hand-holdable limit spend ISO instead, because motion blur
     * cannot be undone but noise is partly averaged away by the merge; only when
     * ISO is exhausted go back to a longer shutter.
     */
    fun realize(targetRelativeExposure: Double): ExposureSettings {
        val target = Math.max(1e-12, targetRelativeExposure)
        var t: Double
        var iso: Int
        if (target <= minExposureTimeSec) {
            t = minExposureTimeSec
            iso = clampIso(Math.round(baseIso * target / t).toInt())
        } else if (target <= maxHandheldTimeSec) {
            t = target
            iso = baseIso
        } else {
            t = maxHandheldTimeSec
            val wantIso = baseIso * target / t
            iso = clampIso(Math.round(wantIso).toInt())
            if (wantIso > maxIso) {
                // ISO exhausted: fall back to a longer shutter, up to the sensor limit.
                t = Math.min(maxExposureTimeSec, target * baseIso / maxIso)
                iso = maxIso
            }
        }
        t = Math.max(minExposureTimeSec, Math.min(maxExposureTimeSec, t))
        return ExposureSettings(t, iso, apertureN)
    }

    private fun clampIso(iso: Int): Int = Math.max(minIso, Math.min(maxIso, iso))

    override fun toString(): String = String.format(Locale.US,
        "limits[t %.6g..%.6g s, ISO %d..%d (base %d), f/%.1f, handheld<=%.4gs]",
        minExposureTimeSec, maxExposureTimeSec, minIso, maxIso, baseIso, apertureN, maxHandheldTimeSec)
}
