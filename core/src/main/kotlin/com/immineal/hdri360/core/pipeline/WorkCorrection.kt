package com.immineal.hdri360.core.pipeline

/**
 * How wrong the work estimate has been, learned from what actually happened.
 *
 * [WorkEstimator.calibrate] times a merge of a 192x144 image, a render and a
 * decode, once, at startup. That is a reasonable way to tell a fast phone from a
 * slow one and a poor way to predict a three million pixel frame: cache
 * behaviour, memory bandwidth and thermal limits all differ at scale. Measured
 * on a real seven direction job at 4K, from the app's own log:
 *
 *     from an estimate of 64 s (merge 28, align 9, render 26, write 1)
 *     ... 11.7 stops in 20.2 s
 *
 * Three times too long. The size picker quotes those minutes to somebody
 * *before* they choose, and the progress bar divides by them - so the bar
 * crawled to a third and then jumped to done.
 *
 * One multiplicative factor is enough, and it has to be learned geometrically:
 * twice as fast and twice as slow are the same size of mistake, and averaging
 * them arithmetically is not.
 *
 * Deliberately a single scalar and not one per stage. The estimator's structure
 * already says how the work divides; what it gets wrong is the scale, and a
 * per-stage correction would need per-stage timings that the phone does not
 * report. If that ever changes this is the place to widen.
 */
class WorkCorrection private constructor(@JvmField val factor: Double) {

    /** The estimate, corrected by what has been learned so far. */
    fun applyTo(seconds: Double): Double = seconds * factor

    /**
     * What this becomes after a job the estimator predicted at [predicted]
     * seconds - the **raw** estimate, before any correction - which took
     * [actual].
     *
     * Moves most of the way, not all of it: one job can be slow for reasons that
     * are not the phone - something else running, a screen that went off, a
     * thermal moment - and a single sample should not overwrite everything
     * learned before it. And the step is bounded, so a hundredfold outlier is
     * evidence rather than a catastrophe.
     */
    fun after(predicted: Double, actual: Double): WorkCorrection {
        if (!usable(predicted) || !usable(actual)) return this
        // Under a second is mostly startup, whatever the job was.
        if (actual < MIN_MEASURABLE_SECONDS || predicted < MIN_MEASURABLE_SECONDS) return this
        // The factor that would have been exactly right for this job. Against
        // the raw estimate, not the corrected one: dividing by the current factor
        // would compound it and send the correction off to zero.
        val observed = actual / predicted
        val bounded = Math.max(factor / MAX_STEP, Math.min(factor * MAX_STEP, observed))
        // Geometric blend: the factor is a ratio, so it is averaged in the log.
        val blended = Math.exp((1 - WEIGHT) * Math.log(factor) + WEIGHT * Math.log(bounded))
        return of(blended)
    }

    override fun toString(): String =
        String.format(java.util.Locale.US, "work estimates run %.2fx", factor)

    companion object {
        /** Knowing nothing: quote the benchmark rather than a number nobody measured. */
        @JvmStatic
        fun unlearned(): WorkCorrection = WorkCorrection(1.0)

        /**
         * A factor read back from wherever it was stored, or [unlearned] if what
         * came back is not a number this could have written.
         */
        @JvmStatic
        fun of(factor: Double): WorkCorrection {
            if (factor.isNaN() || factor.isInfinite() || factor <= 0.0) return unlearned()
            return WorkCorrection(Math.max(MIN_FACTOR, Math.min(MAX_FACTOR, factor)))
        }

        private fun usable(v: Double): Boolean = !v.isNaN() && !v.isInfinite() && v > 0.0

        /** How much of one job's evidence to take. Enough to converge in a handful. */
        private const val WEIGHT = 0.4
        /** The most one job may move the factor, in either direction. */
        private const val MAX_STEP = 3.0
        private const val MIN_MEASURABLE_SECONDS = 1.0
        private const val MIN_FACTOR = 0.01
        private const val MAX_FACTOR = 100.0
    }
}
