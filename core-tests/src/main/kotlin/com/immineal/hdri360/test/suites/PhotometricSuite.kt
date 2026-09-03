package com.immineal.hdri360.test.suites

import com.immineal.hdri360.core.pano.PhotometricAligner
import com.immineal.hdri360.test.TestCase
import com.immineal.hdri360.test.TestKit

/**
 * Reconciling frames that disagree about brightness.
 *
 * Auto-exposure between frames, residual vignetting, flare - all of it shows up
 * as a per-frame scale factor. Solving for those factors globally is what turns
 * a set of separately-exposed frames into one consistent radiance map.
 */
class PhotometricSuite : TestCase {
    override fun name(): String = "photometric"

    override fun run(t: TestKit) {
        val r = t.rng(1234321)
        val n = 6

        // Each frame reports the same scene radiance times its own unknown scale.
        val trueScale = doubleArrayOf(1.0, 1.8, 0.55, 3.2, 0.9, 2.1)
        val samples = ArrayList<PhotometricAligner.Sample>()
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                for (s in 0 until 25) {
                    val radiance = 0.05 + r.nextDouble() * 4
                    samples.add(PhotometricAligner.Sample(i, j,
                        radiance * trueScale[i], radiance * trueScale[j], 1.0))
                }
            }
        }

        val gains = PhotometricAligner.solveGains(n, samples, 1e-6)
        t.eq(n.toLong(), gains.size.toLong(), "one gain per frame")
        // Gains are only defined up to a global factor; compare after normalising.
        var worst = 0.0
        val ref = gains[0] * trueScale[0]
        for (i in 0 until n) {
            val corrected = gains[i] * trueScale[i]
            worst = Math.max(worst, Math.abs(corrected - ref) / ref)
        }
        t.lessThan(worst, 1e-6, "gains make every frame agree on radiance")
        t.note("worst residual disagreement after gain solve: " + TestKit.fmt(worst * 100) + "%")

        // The gauge is fixed so the result is reproducible rather than drifting.
        var logSum = 0.0
        for (g in gains) logSum += Math.log(g)
        t.near(0.0, logSum, 1e-6, "the solution is normalised to unit geometric mean")
        for (g in gains) t.greaterThan(g, 0.0, "gains are positive")

        // --- with photometric noise -------------------------------------------
        val noisy = ArrayList<PhotometricAligner.Sample>()
        for (s in samples)
            noisy.add(PhotometricAligner.Sample(s.frameA, s.frameB,
                s.valueA * Math.exp(r.nextGaussian() * 0.05), s.valueB, s.weight))
        val noisyGains = PhotometricAligner.solveGains(n, noisy, 1e-6)
        var noisyWorst = 0.0
        val ref2 = noisyGains[0] * trueScale[0]
        for (i in 0 until n)
            noisyWorst = Math.max(noisyWorst, Math.abs(noisyGains[i] * trueScale[i] - ref2) / ref2)
        t.lessThan(noisyWorst, 0.03, "5% photometric noise leaves the gains within 3%")
        t.note("worst error with 5% sample noise: " + TestKit.fmt(noisyWorst * 100) + "%")

        // --- a frame with no overlap must not destabilise the solve ---------------
        val withOrphan = PhotometricAligner.solveGains(n + 1, samples, 1e-6)
        t.eq((n + 1).toLong(), withOrphan.size.toLong(), "the orphan frame still gets a gain")
        t.near(1.0, withOrphan[n], 0.35, "an unconstrained frame falls back to unity gain")
        for (g in withOrphan) t.check(g.isFinite() && g > 0, "no NaNs from a disconnected frame")

        // --- degenerate inputs ------------------------------------------------------
        t.throwsException({ PhotometricAligner.solveGains(0, samples, 1e-6) },
            "zero frames is an error")
        val noSamples = PhotometricAligner.solveGains(3, ArrayList(), 1e-6)
        for (g in noSamples) t.near(1.0, g, 1e-9, "with no samples every gain is unity")
        t.throwsException({ PhotometricAligner.Sample(0, 1, -1.0, 1.0, 1.0) },
            "a non-positive sample value is an error")

        // --- determinism --------------------------------------------------------------
        val again = PhotometricAligner.solveGains(n, samples, 1e-6)
        for (i in 0 until n) t.near(gains[i], again[i], 1e-12, "the solve is deterministic")

        // --- outliers -------------------------------------------------------------------
        val dirty = ArrayList(samples)
        for (i in 0 until samples.size / 5)
            dirty.add(PhotometricAligner.Sample(r.nextInt(n), r.nextInt(n),
                0.01 + r.nextDouble() * 8, 0.01 + r.nextDouble() * 8, 1.0))
        val robust = PhotometricAligner.solveGainsRobust(n, dirty, 1e-6, 6)
        var robustWorst = 0.0
        val ref3 = robust[0] * trueScale[0]
        for (i in 0 until n)
            robustWorst = Math.max(robustWorst, Math.abs(robust[i] * trueScale[i] - ref3) / ref3)
        t.lessThan(robustWorst, 0.06, "the robust solve survives 20% nonsense samples")
        t.note("worst error with 20% outlier samples: " + TestKit.fmt(robustWorst * 100) + "%")
    }
}
