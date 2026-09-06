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

        theLadderGrowsDownwards(t)
        theLadderNeverPlansWhatAHandCannotTake(t)
    }

    /**
     * The ladder may not plan an exposure nobody can take.
     *
     * `realize` spends shutter time up to the handheld limit, then ISO, and then
     * - once ISO is exhausted - goes back to a longer shutter, up to whatever the
     * sensor allows. That last step is right for a tripod and wrong for a hand,
     * and the ladder was clamping its bright end to the sensor's limit rather
     * than to the hand's.
     *
     * In a dim room it produced this, off the phone: a ladder of 1/30 s at ISO
     * 787, 1/29 s at ISO 4824, **1/5 s** and **1 second**, all four to be shot
     * as one burst while somebody held still. The burst outlasted its own twelve
     * second timeout, the controller gave up and re-fired, the camera still had
     * the first one in hand, and the capture died with "the camera refused the
     * burst" on every direction.
     *
     * So the bright end stops where the hand does, and says so through
     * `clampedHigh` - which is what that flag has always meant. A room darker
     * than a handheld capture can reach is a fact to report, not a reason to plan
     * a one second frame.
     */
    private fun theLadderNeverPlansWhatAHandCannotTake(t: TestKit) {
        // The ultrawide of a Pixel 9a, as it reports itself, with the handheld
        // limit this app now uses.
        val lim = DeviceExposureLimits(1.24280e-05, 16.0, 50, 4824, 50, 2.2, 1.0 / 30.0)
        t.nearRel(1.0 / 30.0 * 4824 / 50.0, lim.maxHandheldRelativeExposure(), 1e-9,
            "the most exposure a hand can gather is its shutter at full gain")
        t.lessThan(lim.maxHandheldRelativeExposure(), lim.maxRelativeExposure(),
            "which is a great deal less than the sensor would allow on a tripod")

        // A dim room: the darkest thing in it needs far more light than a hand
        // can gather. This is the case that killed the capture.
        val dim = listOf(stats(3.788e-4, 1.334), stats(3.788e-4, 1.334))
        val cfg = BracketConfig()
        val plan = BracketPlanner.plan(dim, lim, cfg)

        for (i in 0 until plan.ladder.size()) {
            val e = plan.ladder.steps[i]
            t.check(e.exposureTimeSec <= lim.maxHandheldTimeSec * 1.0001,
                "rung $i is a shutter a hand can hold: " + e)
        }
        t.check(plan.ladder.clampedHigh,
            "and the ladder says its bright end was cut short, which is the fact to report")

        // The whole bracket has to fit inside a burst somebody can stand through.
        // Twelve seconds is the controller's own patience.
        t.lessThan(plan.estimatedSecondsOfExposure(), 12.0,
            "so the frames of a whole sphere still fit inside the burst timeout")

        // And a scene the device really can reach is not clamped for no reason.
        val ordinary = listOf(stats(2.0, 2e3), stats(2.0, 2e3))
        val fine = BracketPlanner.plan(ordinary, lim, cfg)
        t.check(!fine.ladder.clampedHigh,
            "an ordinary room is planned in full and claims no shortfall")
    }

    /**
     * Decision 1: a direction that came back clipped gets a shorter rung.
     *
     * A sweep can only bound a clipped reading from below - it says the scene is
     * brighter than the sensor could read at that exposure and nothing more - so
     * the rung the planner chose to hold a direction's highlights is a guess. The
     * capture is what measures it, and what it measures has to be able to change
     * the plan.
     */
    private fun theLadderGrowsDownwards(t: TestKit) {
        val lim = phone()
        val cfg = BracketConfig()
        // A room with a window rather than the open sun: the point of growing the
        // ladder is the scene whose top the sweep under-read, not the one that is
        // beyond the sensor whatever it does. That case is below, and this phone
        // reaches its own floor on the sun fixture used earlier in this suite.
        val window = stats(2e0, 2e3)
        val wall = stats(5e-1, 6e1)
        val corner = stats(1e-1, 2e1)
        val plan = BracketPlanner.plan(listOf(window, wall, corner, corner), lim, cfg)
        t.greaterThan(plan.ladder.relativeExposure(0), lim.minRelativeExposure() * 1.5,
            "the fixture leaves the camera shutter left to spend")

        // --- a direction that has ladder left below it ------------------------
        // The shade direction sits well up the ladder because the sweep read it as
        // dim. If it clips anyway, the rung it needs is already there and only its
        // own run moves: nothing else about the capture may change.
        run {
            val shadeRun = plan.indicesPerTarget[2]
            t.greaterThan(shadeRun[0].toDouble(), 0.0,
                "the shade direction does not start at the ladder's darkest rung")
            val had = plan.ladder.relativeExposure(shadeRun[0])
            val out = BracketPlanner.extendDarker(plan, 2, lim, cfg, had / 8.0)
            if (out == null) { t.fail("a direction with ladder below it must be extendable"); return }
            t.eq(plan.ladder.size().toLong(), out.ladder.size().toLong(),
                "a rung that is already on the ladder is not added to it twice")
            t.lessThan(out.ladder.relativeExposure(out.indicesPerTarget[2][0]), had,
                "the direction reaches a shorter exposure than it was planned")
            t.lessThan(out.ladder.relativeExposure(out.indicesPerTarget[2][0]), had / 8.0 * 1.0001,
                "at least as short as the clipped measurement asked for")
            for (i in out.indicesPerTarget.indices) {
                if (i == 2) continue
                t.arrayNear(plan.indicesPerTarget[i].map { it.toDouble() }.toDoubleArray(),
                    out.indicesPerTarget[i].map { it.toDouble() }.toDoubleArray(), 0.0,
                    "direction $i is untouched: it did not clip")
            }
            for (k in 1 until out.indicesPerTarget[2].size)
                t.eq((out.indicesPerTarget[2][k - 1] + 1).toLong(),
                    out.indicesPerTarget[2][k].toLong(),
                    "a bracket is still a contiguous run of rungs")
        }

        // --- a direction already at the ladder's dark end ---------------------
        // Then the ladder itself has to grow, and every other direction has to
        // come along onto the longer ladder still naming the exposures it was
        // planned for - a sphere whose radiance scale moved halfway through is
        // worse than one that clipped.
        run {
            t.eq(0L, plan.indicesPerTarget[0][0].toLong(),
                "the sun direction is already at the darkest rung there is")
            val had = plan.ladder.relativeExposure(0)
            val out = BracketPlanner.extendDarker(plan, 0, lim, cfg, had / 8.0)
            if (out == null) { t.fail("this phone has shutter left to spend"); return }
            t.eq((plan.ladder.size() + 1).toLong(), out.ladder.size().toLong(),
                "the ladder gains a rung at its dark end")
            t.lessThan(out.ladder.relativeExposure(0), had, "and it is shorter than the old darkest")
            for (i in 0 until plan.ladder.size())
                t.nearRel(plan.ladder.relativeExposure(i), out.ladder.relativeExposure(i + 1), 1e-12,
                    "rung $i keeps its exposure, one index further up")
            for (i in out.indicesPerTarget.indices) {
                val was = plan.indicesPerTarget[i]
                val isNow = out.indicesPerTarget[i]
                // The extended direction keeps everything it had and gains one at
                // the front; every other direction keeps everything unchanged.
                val offset = if (i == 0) 1 else 0
                for (k in was.indices)
                    t.nearRel(plan.ladder.relativeExposure(was[k]),
                        out.ladder.relativeExposure(isNow[k + offset]), 1e-12,
                        "direction $i still names exactly the exposures it was planned")
                if (i != 0)
                    t.eq(was.size.toLong(), isNow.size.toLong(),
                        "and direction $i is no longer than it was, because it did not clip")
            }
            t.eq((plan.indicesPerTarget[0].size + 1).toLong(),
                out.indicesPerTarget[0].size.toLong(),
                "decision 2: one extra frame, in the one direction that needed it")
            t.eq(0L, out.indicesPerTarget[0][0].toLong(),
                "which is the new darkest rung")
        }

        // --- nothing shorter exists ------------------------------------------
        // Decision 3: direct sun in a window is brighter than the shortest
        // exposure the sensor has. The answer is not to keep asking.
        run {
            var cur = plan
            var guard = 0
            while (guard++ < 20) {
                val next = BracketPlanner.extendDarker(cur, 0, lim, cfg,
                    cur.ladder.relativeExposure(0) / 8.0) ?: break
                cur = next
            }
            t.lessThan(guard.toDouble(), 20.0,
                "the ladder stops growing rather than chasing a rung the camera has not got")
            t.lessThan(cur.ladder.relativeExposure(0), lim.minRelativeExposure() * 1.0001,
                "and it stopped at the camera's own fastest, not before it")
            t.check(BracketPlanner.extendDarker(cur, 0, lim, cfg, 1e-30) == null,
                "asking again from there is refused rather than answered with a fiction")
            val admitted = cur.withDarkEndClamped()
            t.check(admitted.ladder.clampedLow,
                "which is recorded, so the report can call the top value a lower bound")
            t.check(!cur.ladder.clampedLow, "without rewriting the plan that was already made")
            t.note("growth: ladder " + plan.ladder.size() + " -> " + cur.ladder.size() +
                    " rungs, darkest " + TestKit.fmt(plan.ladder.relativeExposure(0)) +
                    " -> " + TestKit.fmt(cur.ladder.relativeExposure(0)))
        }

        // --- capture time is still the scarce resource ------------------------
        // Growing at the dark end may not grow a bracket without limit; past the
        // cap the bright end goes, which is the planner's own trade - a blown
        // highlight is gone, a noisy shadow is merely noisy.
        run {
            val tight = BracketConfig()
            tight.maxPerTarget = plan.indicesPerTarget[0].size
            val out = BracketPlanner.extendDarker(plan, 0, lim, tight,
                plan.ladder.relativeExposure(0) / 8.0)
            if (out == null) { t.fail("this phone has shutter left to spend"); return }
            t.eq(tight.maxPerTarget.toLong(), out.indicesPerTarget[0].size.toLong(),
                "a bracket at its cap stays at its cap")
            t.eq(0L, out.indicesPerTarget[0][0].toLong(),
                "having gained the darker rung it needed")
            t.lessThan(out.ladder.relativeExposure(out.indicesPerTarget[0].last()),
                plan.ladder.relativeExposure(plan.indicesPerTarget[0].last()) * 1.0001,
                "and given up the brightest one to pay for it")
        }
    }

    private fun stats(lo: Double, hi: Double): SceneStats =
        SceneStats(lo, hi, Math.sqrt(lo * hi), 0.0, 0.0, false, false)
}
