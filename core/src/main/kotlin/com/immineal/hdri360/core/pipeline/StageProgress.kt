package com.immineal.hdri360.core.pipeline

/**
 * Turns a sequence of per-stage fractions into one number that only goes
 * forward.
 *
 * The pipeline reports progress **within** a stage: merging runs 0 to 1, then
 * features runs 0 to 1, then matching, aligning, blending. That is the right
 * thing for it to report - it does not know what else is going to happen - and
 * the wrong thing to hand to a bar. Passing each straight through, which is what
 * the processing service did, gives a bar that fills and resets four times over
 * eight minutes with the estimated time remaining resetting alongside it. The
 * capture screen had the same shape of bug across two phases: the sweep filled
 * the bar to a hundred percent, then the capture started it again at nothing.
 *
 * So: each stage owns a share of the whole, a stage's own fraction moves the bar
 * only within that share, and the result is clamped to never decrease. Never
 * decreasing is the part that matters - a bar that jumps forward has still told
 * the truth about what is done, and a bar that goes backwards has not.
 *
 * Shares are relative weights, not fractions. Whoever writes them down should be
 * able to say "merging is three times features" without doing the arithmetic to
 * make them sum to one.
 */
class StageProgress(stages: List<Stage>) {

    /** One stage of the work and what it is worth relative to the others. */
    class Stage(@JvmField val name: String, @JvmField val weight: Double)

    private val names: Array<String> = Array(stages.size) { stages[it].name }
    /** Where each stage begins, as a fraction of the whole. */
    private val begins = DoubleArray(stages.size)
    /** What each stage is worth, as a fraction of the whole. */
    private val widths = DoubleArray(stages.size)

    @Volatile private var high = 0.0

    init {
        var total = 0.0
        for (s in stages) if (s.weight > 0) total += s.weight
        var at = 0.0
        for (i in stages.indices) {
            val w = if (total > 0 && stages[i].weight > 0) stages[i].weight / total else 0.0
            begins[i] = at
            widths[i] = w
            at += w
        }
    }

    /**
     * The overall fraction, given a stage and how far through that stage the work
     * is. Monotonic: the highest value ever returned is the floor for every
     * value after it.
     *
     * A stage this was not told about, or one arriving out of order, holds the
     * bar where it is rather than moving it - progress is reported from several
     * threads and a late reading from a stage already left behind must not rewind
     * anything.
     */
    fun overall(stage: String, fraction: Double): Double {
        val i = indexOf(stage)
        if (i >= 0) {
            val f = if (fraction < 0) 0.0 else if (fraction > 1) 1.0 else fraction
            val candidate = begins[i] + widths[i] * f
            if (candidate > high) high = candidate
        }
        return high
    }

    /** The highest point reached, without reporting anything new. */
    fun reached(): Double = high

    private fun indexOf(stage: String): Int {
        for (i in names.indices) if (names[i] == stage) return i
        return -1
    }
}
