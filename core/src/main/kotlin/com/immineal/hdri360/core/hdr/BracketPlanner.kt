package com.immineal.hdri360.core.hdr

/**
 * Chooses what to actually shoot.
 *
 * The sphere is metered first, so the planner knows that the direction facing
 * the sun needs the darkest rungs and the one facing into a doorway needs the
 * brightest. Every direction draws from one shared ladder, and each shoots only
 * the contiguous run it needs, which is what keeps a 20+ EV scene inside a
 * capture time a person will actually stand still for.
 */
object BracketPlanner {

    @JvmStatic
    fun plan(perTarget: List<SceneStats>?, lim: DeviceExposureLimits, cfg: BracketConfig): BracketPlan {
        if (perTarget == null || perTarget.isEmpty())
            throw IllegalArgumentException("nothing to plan for")
        val all = SceneStats.union(perTarget)

        // Exposure that keeps the brightest thing anywhere off the rail, and the one
        // that lifts the darkest thing anywhere clear of the noise floor.
        val relForHighlights = cfg.saturationTarget / all.highRadiance
        val relForShadows = cfg.shadowTarget / all.lowRadiance
        val lo = Math.min(relForHighlights, relForShadows)
        val hi = Math.max(relForHighlights, relForShadows)

        val ladder = ExposureLadder.build(lim, lo, hi, cfg.evStep,
            cfg.minPerTarget, cfg.maxLadderRungs)

        val out = Array(perTarget.size) { i -> selectRun(ladder, perTarget[i], cfg) }
        return BracketPlan(ladder, out)
    }

    /** Contiguous run of rungs covering one direction's own dynamic range. */
    private fun selectRun(ladder: ExposureLadder, s: SceneStats, cfg: BracketConfig): IntArray {
        val size = ladder.size()
        val needDark = cfg.saturationTarget / s.highRadiance   // must expose at most this
        val needBright = cfg.shadowTarget / s.lowRadiance      // must expose at least this

        // Darkest rung actually needed: the brightest rung that still holds highlights.
        var start = 0
        for (k in 0 until size) if (ladder.relativeExposure(k) <= needDark * (1 + 1e-9)) start = k
        if (ladder.relativeExposure(0) > needDark * (1 + 1e-9)) start = 0

        // Brightest rung actually needed: the darkest rung that still lifts shadows.
        var end = size - 1
        for (k in size - 1 downTo 0) if (ladder.relativeExposure(k) >= needBright * (1 - 1e-9)) end = k
        if (ladder.relativeExposure(size - 1) < needBright * (1 - 1e-9)) end = size - 1

        if (end < start) end = start

        // Pad to the minimum, preferring extra shadow detail over extra highlight headroom.
        while (end - start + 1 < cfg.minPerTarget) {
            if (end < size - 1) end++
            else if (start > 0) start--
            else break
        }
        // Trim to the maximum from the bright end: blown highlights are unrecoverable,
        // noisy shadows merely noisy.
        while (end - start + 1 > cfg.maxPerTarget) end--

        val run = IntArray(end - start + 1)
        for (k in run.indices) run[k] = start + k
        return run
    }
}
