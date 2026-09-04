package com.immineal.hdri360.core.hdr

import java.util.Collections

/**
 * A shared, evenly spaced set of exposures spanning the whole sphere's dynamic
 * range, ordered darkest first.
 *
 * One global ladder rather than an independent bracket per direction: every
 * frame in the panorama then lands on the same radiance scale, so stitching
 * never has to reconcile two differently-calibrated exposure series.
 */
class ExposureLadder private constructor(
    steps: List<ExposureSettings>,
    @JvmField val baseIso: Int,
    @JvmField val clampedLow: Boolean,
    @JvmField val clampedHigh: Boolean
) {
    @JvmField val steps: List<ExposureSettings> = Collections.unmodifiableList(steps)

    fun size(): Int = steps.size

    fun relativeExposure(i: Int): Double = steps[i].relativeExposure(baseIso)

    fun evSpan(): Double {
        if (steps.size < 2) return 0.0
        return ExposureSettings.log2(relativeExposure(size() - 1) / relativeExposure(0))
    }

    /** Index of the rung closest to the given relative exposure. */
    fun nearest(relativeExposure: Double): Int {
        var best = 0
        var bestD = Double.MAX_VALUE
        for (i in steps.indices) {
            val d = Math.abs(Math.log(relativeExposure(i) / relativeExposure))
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    override fun toString(): String {
        val b = StringBuilder("ladder[")
        for (i in steps.indices) {
            if (i > 0) b.append(", ")
            b.append(steps[i])
        }
        return b.append(']').toString()
    }

    companion object {
        /**
         * Rebuilds a ladder that was planned earlier and stored.
         *
         * A capture that was interrupted has to resume on the ladder it started
         * on: re-planning would put the second half of the sphere on a different
         * radiance scale from the first, and no later gain solve fully repairs
         * that.
         */
        @JvmStatic
        @JvmOverloads
        fun of(steps: List<ExposureSettings>, baseIso: Int,
               clampedLow: Boolean = false, clampedHigh: Boolean = false): ExposureLadder {
            if (steps.isEmpty()) throw IllegalArgumentException("a ladder needs at least one rung")
            if (baseIso <= 0) throw IllegalArgumentException("base ISO must be positive")
            return ExposureLadder(ArrayList(steps), baseIso, clampedLow, clampedHigh)
        }

        @JvmStatic
        fun build(lim: DeviceExposureLimits, relLow: Double, relHigh: Double, evStep: Double):
                ExposureLadder = build(lim, relLow, relHigh, evStep, 2, 24)

        @JvmStatic
        fun build(lim: DeviceExposureLimits, relLow: Double, relHigh: Double,
                  evStep: Double, minRungs: Int, maxRungs: Int): ExposureLadder {
            if (!(evStep > 0)) throw IllegalArgumentException("EV step must be positive")
            var lo = Math.min(relLow, relHigh)
            var hi = Math.max(relLow, relHigh)
            val clampedLow = lo < lim.minRelativeExposure() * (1 - 1e-9)
            val clampedHigh = hi > lim.maxRelativeExposure() * (1 + 1e-9)
            lo = Math.max(lo, lim.minRelativeExposure())
            hi = Math.min(hi, lim.maxRelativeExposure())
            if (hi < lo) hi = lo

            // Widen until there are enough rungs to satisfy the minimum bracket length,
            // preferring to add brighter exposures (shadow SNR) over darker ones.
            var rungs = rungCount(lo, hi, evStep)
            while (rungs < minRungs) {
                val newHi = Math.min(lim.maxRelativeExposure(), hi * Math.pow(2.0, evStep))
                var newLo = lo
                if (newHi <= hi * (1 + 1e-12)) {
                    newLo = Math.max(lim.minRelativeExposure(), lo / Math.pow(2.0, evStep))
                    if (newLo >= lo * (1 - 1e-12)) break // device cannot widen any further
                }
                lo = newLo; hi = newHi
                rungs = rungCount(lo, hi, evStep)
            }
            rungs = Math.min(rungs, maxRungs)

            val out = ArrayList<ExposureSettings>()
            var lastRel = -1.0
            for (i in 0 until rungs) {
                val u = if (rungs == 1) 0.0 else i / (rungs - 1).toDouble()
                val target = lo * Math.pow(hi / lo, u)
                val e = lim.realize(target)
                val rel = e.relativeExposure(lim.baseIso)
                // Quantisation (ISO is an integer) can collapse two rungs onto one.
                if (rel <= lastRel * (1 + 1e-9)) continue
                out.add(e)
                lastRel = rel
            }
            if (out.isEmpty()) out.add(lim.realize(lo))
            return ExposureLadder(out, lim.baseIso, clampedLow, clampedHigh)
        }

        private fun rungCount(lo: Double, hi: Double, evStep: Double): Int {
            val span = ExposureSettings.log2(hi / lo)
            return Math.max(1, Math.ceil(span / evStep - 1e-9).toInt()) + 1
        }
    }
}
