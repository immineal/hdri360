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

    /**
     * Lengthens one direction's bracket downwards, because the frame that was
     * meant to hold its highlights came back on the rail.
     *
     * Two different things happen depending on where the direction sits, and
     * both are this one operation:
     *
     *  - Its run does not yet reach the ladder's darkest rung, because the sweep
     *    under-read this direction - which is exactly what a sweep does when the
     *    reading it took was itself clipped. Then the rung it needs is already on
     *    the ladder and only the run moves.
     *  - Its run already starts at the darkest rung there is. Then the ladder
     *    itself grows, one rung per [BracketConfig.evStep] below its dark end,
     *    and every other direction's run is renumbered onto the longer ladder
     *    without changing which exposures it names.
     *
     * Only this direction's run gets longer. A direction that did not clip does
     * not need a shorter exposure, and handing the new rung to all of them would
     * spend a frame everywhere to fix a fault in one.
     *
     * @param wantedRelative the exposure the clipped measurement asks for; rungs
     *   are added until the run's darkest reaches it, or until the camera has
     *   nothing shorter.
     * @return null when nothing moved at all - the run is already at the fastest
     *   shutter this camera has, which is the case [BracketPlan.withDarkEndClamped]
     *   exists to record.
     */
    @JvmStatic
    fun extendDarker(plan: BracketPlan, target: Int, lim: DeviceExposureLimits,
                     cfg: BracketConfig, wantedRelative: Double): BracketPlan? {
        val runs = plan.indicesPerTarget
        if (target < 0 || target >= runs.size)
            throw IllegalArgumentException("no such direction: $target")
        val run = runs[target]
        if (run.isEmpty()) return null
        if (!(wantedRelative > 0)) throw IllegalArgumentException("exposure must be positive")

        val baseIso = plan.ladder.baseIso
        val steps = ArrayList(plan.ladder.steps)
        val stepFactor = Math.pow(2.0, cfg.evStep)
        var start = run[0]
        var end = run[run.size - 1]
        var added = 0
        var moved = false

        // Bounded by the camera before it is bounded by anything here: each turn
        // is a whole EV step shorter, and the shutter runs out. The count is a
        // guard against a limits object that says otherwise, not the policy.
        var guard = steps.size + cfg.maxPerTarget + cfg.maxLadderRungs
        while (steps[start].relativeExposure(baseIso) > wantedRelative * (1 + 1e-9) && guard-- > 0) {
            if (start > 0) {
                start--
                moved = true
                continue
            }
            val darkest = steps[0].relativeExposure(baseIso)
            val next = lim.realize(darkest / stepFactor)
            // Quantisation can hand back the rung already there. When it does, the
            // camera has nothing shorter and neither has the ladder.
            if (next.relativeExposure(baseIso) >= darkest * (1 - 1e-9)) break
            steps.add(0, next)
            end++
            added++
            moved = true
        }
        if (!moved) return null

        // Trimmed from the bright end, which is the same trade the planner makes:
        // a blown highlight is gone, a noisy shadow is merely noisy.
        while (end - start + 1 > cfg.maxPerTarget && end > start) end--

        val ladder = if (added == 0) plan.ladder
                     else ExposureLadder.of(steps, baseIso,
                         plan.ladder.clampedLow, plan.ladder.clampedHigh)
        val out = Array(runs.size) { i ->
            if (i == target) IntArray(end - start + 1) { start + it }
            else IntArray(runs[i].size) { runs[i][it] + added }
        }
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
