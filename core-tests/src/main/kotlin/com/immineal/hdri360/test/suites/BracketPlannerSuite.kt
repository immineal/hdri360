package com.immineal.hdri360.test.suites

import com.immineal.hdri360.core.hdr.BracketConfig
import com.immineal.hdri360.core.hdr.BracketPlanner
import com.immineal.hdri360.core.hdr.DeviceExposureLimits
import com.immineal.hdri360.core.hdr.ExposureLadder
import com.immineal.hdri360.core.hdr.SceneStats
import com.immineal.hdri360.test.TestCase
import com.immineal.hdri360.test.TestKit

/** Exposure ladder construction and per-direction bracket selection. */
class BracketPlannerSuite : TestCase {
    override fun name(): String = "bracket-planner"

    private fun phone(): DeviceExposureLimits {
        // Numbers typical of a recent phone main camera.
        return DeviceExposureLimits(
            1.0 / 32000.0,   // min exposure time
            2.0,             // max exposure time the sensor allows
            50, 6400, 50,    // min / max / base ISO
            1.7,             // f-number
            1.0 / 15.0)      // longest hand-holdable time
    }

    override fun run(t: TestKit) {
        val lim = phone()

        // --- realize() clamps to what the hardware can actually do ---------
        val mid = lim.realize(1.0 / 500.0)
        t.nearRel(1.0 / 500.0, mid.relativeExposure(lim.baseIso), 1e-6, "mid exposure realised exactly")
        t.eq(lim.baseIso.toLong(), mid.iso.toLong(), "base ISO is preferred while shutter speed suffices")

        // Beyond the hand-holdable shutter speed, gain takes over rather than blur.
        val slow = lim.realize(1.0)
        t.lessThan(slow.exposureTimeSec, lim.maxHandheldTimeSec * 1.0001,
            "never exceeds the handheld limit first")
        t.greaterThan(slow.iso.toDouble(), lim.baseIso.toDouble(), "ISO rises once the shutter is maxed out")
        t.nearRel(1.0, slow.relativeExposure(lim.baseIso), 0.02, "slow exposure still hits its target")

        // Past everything the device has, it clamps instead of returning fiction.
        val tooSlow = lim.realize(1e6)
        t.lessThan(tooSlow.relativeExposure(lim.baseIso), 1e6, "impossible exposure is clamped")
        t.lessThan(tooSlow.exposureTimeSec, lim.maxExposureTimeSec * 1.0001,
            "clamped within max exposure time")
        t.lessThan(tooSlow.iso.toDouble(), (lim.maxIso + 1).toDouble(), "clamped within max ISO")
        val tooFast = lim.realize(1e-12)
        t.greaterThan(tooFast.exposureTimeSec, lim.minExposureTimeSec * 0.9999,
            "clamped at the fastest shutter")

        // --- ladder -------------------------------------------------------
        // Both ends must be physically reachable: the shortest shutter this device
        // offers at base ISO is 1/32000 s, so a relative exposure of 1e-6 is fiction.
        val relLow = 5e-5
        val relHigh = 0.5       // ~13.3 EV of required span
        val ladder = ExposureLadder.build(lim, relLow, relHigh, 3.0)
        t.greaterThan(ladder.size().toDouble(), 1.0, "ladder has multiple steps")
        for (i in 1 until ladder.size()) {
            t.greaterThan(ladder.relativeExposure(i), ladder.relativeExposure(i - 1),
                "ladder is strictly increasing in exposure")
            val ev = Math.log(ladder.relativeExposure(i) / ladder.relativeExposure(i - 1)) / Math.log(2.0)
            t.lessThan(ev, 3.0 + 1e-6, "no gap wider than the requested EV step")
            t.greaterThan(ev, 0.5, "no pointlessly small step")
        }
        t.lessThan(ladder.relativeExposure(0), relLow * 1.0001, "ladder reaches the dark end")
        t.greaterThan(ladder.relativeExposure(ladder.size() - 1), relHigh * 0.9999,
            "ladder reaches the bright end")
        for (e in ladder.steps) {
            t.check(e.exposureTimeSec >= lim.minExposureTimeSec * 0.9999 &&
                    e.exposureTimeSec <= lim.maxExposureTimeSec * 1.0001, "ladder step obeys shutter limits")
            t.check(e.iso >= lim.minIso && e.iso <= lim.maxIso, "ladder step obeys ISO limits")
        }
        t.check(!ladder.clampedLow && !ladder.clampedHigh, "a reachable range is not reported as clamped")

        val impossible = ExposureLadder.build(lim, 1e-12, 1e9, 3.0)
        t.check(impossible.clampedLow, "an unreachably dark request is flagged")
        t.check(impossible.clampedHigh, "an unreachably bright request is flagged")

        // A 1 EV step over a wide range must not explode the shot count.
        val fine = ExposureLadder.build(lim, 5e-5, 0.5, 1.0)
        t.greaterThan(fine.size().toDouble(), ladder.size().toDouble(), "a finer step means more rungs")
        t.lessThan(fine.size().toDouble(), 25.0, "ladder length stays sane")

        // --- per-direction selection ---------------------------------------
        val cfg = BracketConfig()
        // Three very different directions on one sphere.
        val sun = stats(1e2, 2e5)      // straight at the sun
        val sky = stats(5e1, 5e3)      // open sky
        val shade = stats(1e-1, 2e1)   // deep shade under a bridge
        val targets = ArrayList(listOf(sun, sky, shade, sky, shade))

        val plan = BracketPlanner.plan(targets, lim, cfg)
        t.eq(targets.size.toLong(), plan.indicesPerTarget.size.toLong(),
            "one bracket per capture direction")
        t.greaterThan(plan.ladder.size().toDouble(), 3.0,
            "global ladder spans the whole sphere's range")

        for (i in targets.indices) {
            val idx = plan.indicesPerTarget[i]
            t.greaterThan(idx.size.toDouble(), (cfg.minPerTarget - 1).toDouble(),
                "each direction gets at least the minimum bracket")
            t.lessThan(idx.size.toDouble(), (cfg.maxPerTarget + 1).toDouble(),
                "each direction stays under the maximum bracket")
            for (j in 1 until idx.size)
                t.eq((idx[j - 1] + 1).toLong(), idx[j].toLong(),
                    "a bracket is a contiguous run of ladder rungs")
            // The darkest chosen exposure must keep this direction's highlights off the rail.
            val darkest = plan.ladder.relativeExposure(idx[0])
            val coversHighlights = darkest * targets[i].highRadiance <= cfg.saturationTarget * 1.05
            t.check(coversHighlights || idx[0] == 0,
                "direction $i either holds its highlights or is already at the darkest rung")
            // The brightest chosen exposure must lift this direction's shadows off the noise floor.
            val brightest = plan.ladder.relativeExposure(idx[idx.size - 1])
            val coversShadows = brightest * targets[i].lowRadiance >= cfg.shadowTarget * 0.95
            t.check(coversShadows || idx[idx.size - 1] == plan.ladder.size() - 1,
                "direction $i either lifts its shadows or is already at the brightest rung")
        }

        // The sun direction must reach for the darkest rung; the shade direction the brightest.
        t.eq(0L, plan.indicesPerTarget[0][0].toLong(), "the sun direction starts at the darkest rung")
        val shadeIdx = plan.indicesPerTarget[2]
        t.eq((plan.ladder.size() - 1).toLong(), shadeIdx[shadeIdx.size - 1].toLong(),
            "the shade direction reaches the brightest rung")

        // Per-direction subsets must be cheaper than shooting the whole ladder everywhere.
        val naive = plan.ladder.size() * targets.size
        t.lessThan(plan.totalShots().toDouble(), naive.toDouble(),
            "adaptive brackets shoot fewer frames than the full ladder")
        t.note("plan: ladder=" + plan.ladder.size() + " rungs, " + plan.totalShots() +
                " shots vs " + naive + " naive")

        // A flat, low-contrast scene should collapse to the minimum bracket.
        val flat = ArrayList<SceneStats>()
        for (i in 0 until 4) flat.add(stats(30.0, 120.0))
        val flatPlan = BracketPlanner.plan(flat, lim, cfg)
        for (idx in flatPlan.indicesPerTarget)
            t.eq(cfg.minPerTarget.toLong(), idx.size.toLong(),
                "a low-contrast scene collapses to the minimum bracket")

        // Determinism: the planner must be a pure function of its inputs.
        val again = BracketPlanner.plan(targets, lim, cfg)
        t.eq(plan.totalShots().toLong(), again.totalShots().toLong(), "planning is deterministic")
    }

    private fun stats(lo: Double, hi: Double): SceneStats =
        SceneStats(lo, hi, Math.sqrt(lo * hi), 0.0, 0.0, false, false)
}
