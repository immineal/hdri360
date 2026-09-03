package com.immineal.hdri360.core.hdr

/** A global exposure ladder plus, for each capture direction, the run of rungs to shoot. */
class BracketPlan(
    @JvmField val ladder: ExposureLadder,
    /** indicesPerTarget[i] is a contiguous, ascending run of ladder indices. */
    @JvmField val indicesPerTarget: Array<IntArray>
) {

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
