package com.immineal.hdri360.test.suites

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

    override fun run(t: TestKit) {
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
    }
}
