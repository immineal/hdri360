package com.immineal.hdri360.core.pipeline

/**
 * How much longer, from the estimate and from the job's own clock.
 *
 * "About N minutes left" used to be `estimate * (1 - fraction)` and nothing
 * else, so a wrong estimate was wrong for the whole job: at three times too long
 * - which is what the startup benchmark really produced, 65 seconds predicted
 * for 21 - it counts down from a minute to twenty seconds while twenty seconds
 * is all it ever needed. The other way round it reaches "nearly there" and sits
 * there.
 *
 * [WorkCorrection] fixes the *next* job. This fixes the one running, out of the
 * only evidence that is certainly about this job on this phone with this data:
 * how long it has taken to get as far as it has.
 *
 * A job a third done has measured itself better than any benchmark can, so that
 * is where the estimate stops being consulted. Below that the two are blended,
 * so the number does not lurch the moment the first progress arrives.
 */
object TimeRemaining {

    /**
     * @param estimateSeconds what the job was predicted to take, or 0 if nothing
     *   was predicted
     * @param elapsedSeconds how long it has been running
     * @param fraction how much of it is done, 0 to 1
     */
    @JvmStatic
    fun seconds(estimateSeconds: Double, elapsedSeconds: Double, fraction: Double): Double {
        val elapsed = if (finite(elapsedSeconds) && elapsedSeconds > 0) elapsedSeconds else 0.0
        val estimate = if (finite(estimateSeconds) && estimateSeconds > 0) estimateSeconds else 0.0
        val done = if (finite(fraction)) Math.max(0.0, Math.min(1.0, fraction)) else 0.0
        if (done >= 1.0) return 0.0

        // What this job says about itself, once it has said anything at all.
        val observed = if (done > 0 && elapsed > 0) elapsed / done else 0.0
        if (observed <= 0) return Math.max(0.0, estimate - elapsed)
        if (estimate <= 0) return Math.max(0.0, observed - elapsed)

        val trust = Math.min(1.0, done / TRUST_FULLY_AT)
        val total = estimate * (1 - trust) + observed * trust
        return Math.max(0.0, total - elapsed)
    }

    private fun finite(v: Double): Boolean = !v.isNaN() && !v.isInfinite()

    /**
     * How far through a job its own clock becomes the whole answer.
     *
     * A third. Early progress is lumpy - the first stage of the pipeline is the
     * longest and does not report until a frame is merged - so extrapolating from
     * the first few percent overshoots; by a third the rate is real.
     */
    private const val TRUST_FULLY_AT = 1.0 / 3.0
}
