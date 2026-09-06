package com.immineal.hdri360.test.suites

import com.immineal.hdri360.core.hdr.ExposureSettings
import com.immineal.hdri360.core.hdr.MeterConfig
import com.immineal.hdri360.core.hdr.SceneMeter
import com.immineal.hdri360.core.hdr.SceneStats
import com.immineal.hdri360.core.image.ImageF
import com.immineal.hdri360.test.TestCase
import com.immineal.hdri360.test.TestKit

/**
 * Scene metering: turning one probe frame into "how much dynamic range is
 * actually out there", and the controller that walks the probe onto a usable
 * exposure. This is what makes the bracket auto-adjusting rather than fixed.
 */
class MeteringSuite : TestCase {
    override fun name(): String = "metering"

    /** Renders a scene of known radiances at a given exposure, with clipping. */
    private fun expose(radiance: DoubleArray, relExposure: Double): ImageF {
        val n = radiance.size
        val img = ImageF(n, 1, 1)
        for (i in 0 until n) {
            img.data[i] = Math.min(1.0, radiance[i] * relExposure).toFloat()
        }
        return img
    }

    /** Log-uniform radiances between lo and hi. */
    private fun scene(lo: Double, hi: Double, n: Int): DoubleArray {
        val r = DoubleArray(n)
        for (i in 0 until n) {
            val u = i / (n - 1).toDouble()
            r[i] = lo * Math.pow(hi / lo, u)
        }
        return r
    }


    /**
     * A long exposure has to print as a long exposure.
     *
     * The format was `1/%.0f` of the shutter, unconditionally, which is right up
     * to about half a second and nonsense past it: two seconds prints as `1/0s`
     * and so does sixteen. It reached the log of a real capture -
     * `1/3s ISO7276 | 1/0s ISO7276 | 1/0s ISO7276` - during the evening spent
     * working out why bursts were timing out, where a diagnostic that rounds the
     * offending number to zero is worse than none. Two different exposures, three
     * stops apart, printed identically.
     */
    private fun anExposureNeverPrintsItselfAsALie(t: TestKit) {
        // The fraction form, where it belongs.
        t.eq("1/30s ISO100 f/1.7", ExposureSettings(1.0 / 30.0, 100, 1.7).toString(),
            "an ordinary handheld shutter reads as a fraction")
        t.eq("1/1695s ISO29 f/1.7", ExposureSettings(1.0 / 1695.0, 29, 1.7).toString(),
            "and so does a very short one")

        // Past half a second the fraction stops carrying information.
        for (secs in doubleArrayOf(0.6, 1.0, 1.5, 2.0, 4.0, 16.0)) {
            val said = ExposureSettings(secs, 7276, 1.7).toString()
            t.check(!said.contains("1/0s"),
                "a $secs second exposure does not print as 1/0s: " + said)
            t.check(said.contains("s ISO7276"), "and still says what it was: " + said)
        }
        t.eq("2.0s ISO7276 f/1.7", ExposureSettings(2.0, 7276, 1.7).toString(),
            "two seconds says two seconds")
        t.eq("16.0s ISO7276 f/1.7", ExposureSettings(16.0, 7276, 1.7).toString(),
            "and sixteen says sixteen")

        // The two that used to collide are now distinguishable, which is the
        // whole point of writing them down.
        t.check(ExposureSettings(2.0, 7276, 1.7).toString() !=
                ExposureSettings(16.0, 7276, 1.7).toString(),
            "three stops apart no longer print the same")
    }

    override fun run(t: TestKit) {
        anExposureNeverPrintsItselfAsALie(t)
        val cfg = MeterConfig()

        // --- an exposure that sees everything -----------------------------
        val sc = scene(1.0, 1000.0, 4001)   // 10 EV of scene
        val rel = 0.5 / 1000.0              // brightest lands at 0.5
        val s = SceneMeter.measure(expose(sc, rel), rel, cfg)
        t.check(!s.highlightsClipped, "nothing clips when the brightest pixel lands at half scale")
        t.near(0.0, s.clippedFraction, 1e-9, "no clipped pixels")
        t.nearRel(1000.0, s.highRadiance, 0.02, "high radiance recovered")
        t.nearRel(1.0, s.lowRadiance, 0.10, "low radiance recovered")
        t.nearRel(Math.log(1000.0) / Math.log(2.0), s.dynamicRangeEv(), 0.10, "dynamic range in EV")

        // --- an exposure that blows the highlights -------------------------
        val relHot = 4.0 / 1000.0           // top 4x over saturation
        val hot = SceneMeter.measure(expose(sc, relHot), relHot, cfg)
        t.check(hot.highlightsClipped, "clipping is detected")
        t.greaterThan(hot.clippedFraction, 0.05, "a meaningful fraction is reported clipped")
        // A clipped measurement may only ever be a LOWER bound on the true radiance.
        t.lessThan(hot.highRadiance, 1000.0 * 1.001, "clipped high radiance never overestimates")
        t.greaterThan(hot.highRadiance, cfg.saturationThreshold / relHot * 0.99,
            "clipped high radiance is at least the saturation bound")

        // --- an exposure that buries everything in the noise ---------------
        val relDark = 1e-6
        val dark = SceneMeter.measure(expose(sc, relDark), relDark, cfg)
        t.check(dark.shadowsCrushed, "crushed shadows are detected")
        t.check(!dark.highlightsClipped, "a dark frame does not report clipping")

        // Measured on a Pixel 9a: every RAW frame reported max exactly 1.0 however
        // short the exposure, because a real sensor always has some pixels stuck at
        // full scale. Counting them made the controller believe the scene was blown
        // and chase the shutter down through its whole range, oscillating between
        // 1/3700 and 1/7800 of a second on a scene that was not clipping at all.
        val speckled = ImageF(250, 186, 1)
        java.util.Arrays.fill(speckled.data, 0.2f)
        for (i in 0 until 20) speckled.data[(i * 1973) % speckled.data.size] = 1.0f
        val spotty = SceneMeter.measure(speckled, 1.0, cfg)
        t.greaterThan(spotty.clippedFraction, cfg.clipTolerance,
            "the hot pixels are more numerous than the bare count tolerance allows")
        t.check(!spotty.highlightsClipped,
            "but a handful of stuck pixels is not a blown highlight")
        t.near(0.2, spotty.highRadiance, 1e-6,
            "so the scene's high end is read from the scene rather than from the rail")
        t.check(SceneMeter.isWellExposed(spotty, cfg) ||
            SceneMeter.suggestRelativeExposure(spotty, 1.0, cfg) > 1.0,
            "and the controller opens up rather than stopping down")

        // A genuinely blown frame still has to be caught: there the bright pixels
        // are not a speckle, they are the top of the distribution.
        val blown = ImageF(64, 48, 1)
        for (i in blown.data.indices) blown.data[i] = if (i % 4 == 0) 0.3f else 1.0f
        t.check(SceneMeter.measure(blown, 1.0, cfg).highlightsClipped,
            "a frame that really is on the rail is still reported as clipped")

        // Exposing to view is a different question from exposing to measure.
        val ordinary = SceneStats(0.05, 5.0, 0.5, 0.0, 0.0, false, false)
        t.nearRel(0.18 / 0.5, SceneMeter.viewingRelativeExposure(ordinary), 1e-12,
            "an ordinary scene is exposed to put its median at mid grey")

        // Measured on a Pixel 9a in a room with a bright window: at the metering
        // exposure more than half the frame quantises to zero, so the median is
        // zero and target/median is infinite. That asked for a sixteen second
        // preview at maximum ISO, which updates once every sixteen seconds.
        val roomWithAWindow = SceneStats(0.024, 1192.0, 0.0, 0.02, 0.6, true, true)
        val viewing = SceneMeter.viewingRelativeExposure(roomWithAWindow)
        t.check(viewing.isFinite() && viewing > 0,
            "a scene whose median has fallen to zero still gets a usable exposure")
        t.check(viewing < 0.18 / Math.sqrt(0.024 * 1192.0) * 1.0001 &&
                viewing > 0.18 / Math.sqrt(0.024 * 1192.0) * 0.9999,
            "taken from the middle of the range instead, in the space the range lives in")
        t.lessThan(viewing, 1.0, "which is a fraction of a second, not sixteen of them")

        // --- the auto-exposure controller ----------------------------------
        // Start 6 EV too bright and require convergence to an unclipped frame.
        for (startEv in doubleArrayOf(-6.0, -3.0, 0.0, 3.0, 6.0, 10.0)) {
            var cur = (0.5 / 1000.0) * Math.pow(2.0, startEv)
            var iterations = 0
            var st = SceneMeter.measure(expose(sc, cur), cur, cfg)
            while (iterations < 12 && !SceneMeter.isWellExposed(st, cfg)) {
                val next = SceneMeter.suggestRelativeExposure(st, cur, cfg)
                t.greaterThan(next, 0.0, "suggested exposure is positive")
                t.check(next != cur, "controller moves when the frame is not well exposed")
                cur = next
                st = SceneMeter.measure(expose(sc, cur), cur, cfg)
                iterations++
            }
            t.check(SceneMeter.isWellExposed(st, cfg),
                "auto-exposure converges from $startEv EV off (took $iterations)")
            t.lessThan(iterations.toDouble(), 9.0,
                "converges in fewer than 9 probes from $startEv EV off")
            t.note("AE from $startEv EV off converged in $iterations probes")
        }

        // --- stats merge across the sphere ----------------------------------
        val sun = SceneMeter.measure(expose(scene(50.0, 3e5, 2001), 1e-6), 1e-6, cfg)
        val room = SceneMeter.measure(expose(scene(0.02, 5.0, 2001), 0.1), 0.1, cfg)
        val all = SceneStats.union(listOf(sun, room))
        t.lessThan(all.lowRadiance, room.lowRadiance * 1.001, "union takes the darkest low")
        t.greaterThan(all.highRadiance, sun.highRadiance * 0.999, "union takes the brightest high")
        t.greaterThan(all.dynamicRangeEv(), 20.0, "sun plus interior is a >20 EV scene")
        t.throwsException({ SceneStats.union(emptyList()) },
            "union of nothing is an error, not a silent default")

        // --- one bright direction must not carry the whole sweep -------------------
        // The union's middle is what the viewfinder is exposed for during the
        // sweep. Averaging medians arithmetically lets a single direction that
        // happened to contain a window drag it up by two orders of magnitude, and
        // the preview then goes black on a room that is perfectly visible - which
        // is what a sweep past a window actually did.
        run {
            val dim = ArrayList<SceneStats>()
            for (i in 0 until 9)
                dim.add(SceneStats(0.01, 1.0, 0.1, 0.0, 0.0, false, false))
            val window = SceneStats(0.01, 20000.0, 1000.0, 0.0, 0.0, false, false)
            val typical = SceneStats.union(dim)
            t.near(0.1, typical.medianRadiance, 1e-9,
                "nine directions of the same room average to that room")

            val withWindow = SceneStats.union(dim + window)
            t.greaterThan(withWindow.highRadiance, 19999.0,
                "the bright end still reaches the window, which the ladder needs")
            t.lessThan(withWindow.medianRadiance, 0.4,
                "but the middle stays near the room, which the viewfinder needs")

            // Said as the thing that matters: the exposure a person would be shown.
            val alone = SceneMeter.viewingRelativeExposure(typical)
            val after = SceneMeter.viewingRelativeExposure(withWindow)
            t.lessThan(Math.abs(Math.log(after / alone) / Math.log(2.0)), 2.0,
                "sweeping past a window moves the viewfinder by under two stops")
        }

        countingWhatReachedTheTop(t)
    }

    /**
     * The same question as [SceneMeter.measure] asks about highlights, put to a
     * frame far too big to sort.
     *
     * A capture asks this of every bracket's shortest rung, on the camera thread,
     * of twelve megapixels, between two exposures of a burst - and answers it by
     * adding a shorter rung and shooting that direction again. Sorting twelve
     * million floats three times to learn a count is not a thing that can happen
     * there, so the count is taken directly; what has to hold is that it says the
     * same thing about a frame as the metering path does.
     */
    private fun countingWhatReachedTheTop(t: TestKit) {
        val cfg = MeterConfig()

        // Across a range of exposures from far under to far over, the cheap count
        // and the sorted measurement must reach the same verdict.
        val sc = scene(1.0, 1000.0, 4001)
        var agreed = 0
        var sawBoth = 0
        var everClipped = false
        var everClear = false
        for (i in 0 until 24) {
            val rel = 1e-4 * Math.pow(2.0, i / 2.0)
            val frame = expose(sc, rel)
            val measured = SceneMeter.measure(frame, rel, cfg)
            val counted = SceneMeter.clippedFraction(frame, cfg)
            t.near(measured.clippedFraction, counted, 1e-12,
                "the count is the fraction measure() counts")
            if (SceneMeter.highlightsClipped(counted, cfg) == measured.highlightsClipped) agreed++
            sawBoth++
            everClipped = everClipped or measured.highlightsClipped
            everClear = everClear or !measured.highlightsClipped
        }
        t.eq(sawBoth.toLong(), agreed.toLong(),
            "and reaches the same verdict at every exposure from under to over")
        t.check(everClipped && everClear, "with both verdicts actually exercised")

        // A handful of stuck pixels is not a blown highlight. Every sensor has
        // some, no shutter speed removes them, and treating them as clipping is
        // what sends an exposure controller down through its whole range.
        val speckled = ImageF(1000, 1, 1)
        java.util.Arrays.fill(speckled.data, 0.3f)
        speckled.data[0] = 1.0f
        val speckle = SceneMeter.clippedFraction(speckled, cfg)
        t.near(0.001, speckle, 1e-9, "one pixel in a thousand is on the rail")
        t.check(!SceneMeter.highlightsClipped(0.0009, cfg), "a speckle is not a blown highlight")
        t.check(SceneMeter.highlightsClipped(0.05, cfg), "five percent of the frame is")

        // A colour frame is judged on its luminance, as measure() judges it, and a
        // Bayer mosaic on the plane itself - the frames a capture actually
        // delivers are one or the other.
        val colour = ImageF(4, 4, 3)
        java.util.Arrays.fill(colour.data, 1.0f)
        t.near(1.0, SceneMeter.clippedFraction(colour, cfg), 1e-12,
            "a white colour frame is entirely clipped")
        val green = ImageF(4, 4, 3)
        for (i in 0 until 16) green.data[i * 3 + 1] = 1.0f
        t.near(0.0, SceneMeter.clippedFraction(green, cfg), 1e-12,
            "green alone at the rail is not a clipped luminance")

        // The step away from a rail is sized from how much of the frame is on it,
        // and is the one the sweep uses - one policy, not two.
        t.greaterThan(SceneMeter.clippedStepDown(0.9), SceneMeter.clippedStepDown(0.1),
            "a frame mostly on the rail steps further than one barely on it")
        val badly = SceneStats(1.0, 100.0, 10.0, 0.9, 0.0, true, false)
        t.near(1.0 / SceneMeter.clippedStepDown(0.9),
            SceneMeter.suggestRelativeExposure(badly, 1.0, cfg), 1e-12,
            "and it is exactly the step the sweep takes")
    }
}
