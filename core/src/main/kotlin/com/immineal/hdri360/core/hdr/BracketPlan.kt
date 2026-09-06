package com.immineal.hdri360.core.hdr

/**
 * A global exposure ladder plus, for each capture direction, the run of rungs to
 * shoot.
 *
 * Not fixed for the length of a capture. A direction that comes back with its
 * highlights on the rail gets a shorter rung and is shot again, which is
 * [BracketPlanner.extendDarker]; what that returns is another plan of this
 * shape, on a ladder that may be one rung longer at the dark end. Every rung
 * already in the ladder is still in it, at an index one higher - which is why
 * the runs are renumbered there, and why nothing on disk is keyed by a ladder
 * index.
 */
class BracketPlan(
    @JvmField val ladder: ExposureLadder,
    /** indicesPerTarget[i] is a contiguous, ascending run of ladder indices. */
    @JvmField val indicesPerTarget: Array<IntArray>
) {

    /**
     * The same plan, with the ladder marked as short at its dark end.
     *
     * For the one case a growing ladder cannot answer: a direction that still
     * burns out at the fastest shutter the camera has. The capture proceeds -
     * there is nothing else it could do - and the flag is what lets the report
     * call the top of the range a lower bound rather than a measurement.
     */
    fun withDarkEndClamped(): BracketPlan {
        if (ladder.clampedLow) return this
        return BracketPlan(
            ExposureLadder.of(ladder.steps, ladder.baseIso, true, ladder.clampedHigh),
            indicesPerTarget)
    }

    fun totalShots(): Int {
        var n = 0
        for (idx in indicesPerTarget) n += idx.size
        return n
    }

    fun settings(target: Int, k: Int): ExposureSettings =
        ladder.steps[indicesPerTarget[target][k]]

    /** Rough capture time, ignoring readout: useful for warning the user before they start. */
    fun estimatedSecondsOfExposure(): Double {
        var t = 0.0
        for (idx in indicesPerTarget)
            for (i in idx) t += ladder.steps[i].exposureTimeSec
        return t
    }
}
