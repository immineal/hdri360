package com.immineal.hdri360.test.suites

import com.immineal.hdri360.core.hdr.CrfEstimator
import com.immineal.hdri360.core.hdr.Exposure
import com.immineal.hdri360.core.hdr.HdrMerger
import com.immineal.hdri360.core.hdr.MergeConfig
import com.immineal.hdri360.core.hdr.ResponseCurve
import com.immineal.hdri360.core.image.ImageF
import com.immineal.hdri360.test.TestCase
import com.immineal.hdri360.test.TestKit

/**
 * Camera response recovery for the non-RAW path. A phone JPEG is not linear and
 * its tone curve is not a gamma, so the curve has to be recovered from the
 * bracket itself before any of the radiance maths means anything.
 */
class ResponseCurveSuite : TestCase {
    override fun name(): String = "response-curve"

    override fun run(t: TestKit) {
        // --- LUT plumbing -------------------------------------------------
        val lin = ResponseCurve.linear()
        t.check(lin.isIdentity(), "the linear curve reports itself as identity")
        t.near(0.37, lin.toLinear(0.37), 1e-9, "the linear curve is a pass-through")

        val known = ResponseCurve.fromFunction(2048) { linear -> encode(linear) }
        t.check(!known.isIdentity(), "a real curve is not identity")
        t.near(0.0, known.toLinear(0.0), 1e-4, "black decodes to zero")
        t.near(1.0, known.toLinear(1.0), 1e-4, "white decodes to one")
        var worstDecode = 0.0
        var z0 = 0.02
        while (z0 <= 0.98) {
            worstDecode = Math.max(worstDecode, Math.abs(known.toLinear(z0) - decode(z0)))
            z0 += 0.01
        }
        t.lessThan(worstDecode, 1e-4, "fromFunction inverts the encoding")
        var prev = -1.0
        var z1 = 0.0
        while (z1 <= 1.0) {
            val v = known.toLinear(z1)
            t.greaterThan(v, prev - 1e-12, "the decoded curve is monotone")
            prev = v
            z1 += 0.005
        }

        // --- Debevec recovery from a synthetic bracket ---------------------
        val r = t.rng(2024)
        val nSamples = 220
        val levels = 256
        val relExposures = doubleArrayOf(1.0 / 800, 1.0 / 200, 1.0 / 50, 1.0 / 12, 1.0 / 3)
        val radiance = DoubleArray(nSamples)
        for (i in 0 until nSamples) radiance[i] = 0.05 * Math.pow(10.0, 3.0 * i / (nSamples - 1.0))

        val z = Array(nSamples) { DoubleArray(relExposures.size) }
        for (i in 0 until nSamples)
            for (j in relExposures.indices) {
                val linear = Math.min(1.0, radiance[i] * relExposures[j])
                val enc = encode(linear)
                // 8-bit quantisation plus a little sensor noise, as in a real JPEG
                val q = Math.round(
                    Math.max(0.0, Math.min(1.0, enc + r.nextGaussian() * 0.002)) * (levels - 1)).toInt()
                z[i][j] = q / (levels - 1).toDouble()
            }

        val cfg = CrfEstimator.Config()
        cfg.levels = levels
        val recovered = CrfEstimator.estimate(z, relExposures, cfg)
        t.eq(levels.toLong(), recovered.size().toLong(), "recovered curve has the requested resolution")

        var worst = 0.0
        var worstAt = 0.0
        var enc2 = 0.10
        while (enc2 <= 0.95) {
            val got = recovered.toLinear(enc2)
            val want = decode(enc2)
            val rel = Math.abs(got - want) / Math.max(want, 1e-6)
            if (rel > worst) { worst = rel; worstAt = enc2 }
            enc2 += 0.01
        }
        t.lessThan(worst, 0.08, "recovered response is within 8% of truth over the usable range")
        t.note("worst response error " + TestKit.fmt(worst * 100) + "% at encoded " + TestKit.fmt(worstAt))

        var p = -1.0
        for (i in 0 until recovered.size()) {
            val v = recovered.toLinear(i / (recovered.size() - 1).toDouble())
            t.greaterThan(v, p - 1e-9, "the recovered curve is monotone")
            p = v
        }

        // --- what actually matters: radiance ratios after merging ----------
        // Two scene points three stops apart must come out three stops apart.
        val mc = MergeConfig()
        mc.response = recovered
        val testRadiance = doubleArrayOf(2.0, 16.0, 128.0)
        val merged = DoubleArray(testRadiance.size)
        for (k in testRadiance.indices) {
            val frames = ArrayList<Exposure>()
            for (e in relExposures) {
                val img = ImageF(1, 1, 1)
                img.data[0] = encode(Math.min(1.0, testRadiance[k] * e)).toFloat()
                frames.add(Exposure(img, e, 1.0))
            }
            merged[k] = HdrMerger.merge(frames, mc).radiance.data[0].toDouble()
        }
        t.nearRel(8.0, merged[1] / merged[0], 0.05, "a 3-stop step is reproduced as 3 stops")
        t.nearRel(8.0, merged[2] / merged[1], 0.05, "a second 3-stop step is reproduced as 3 stops")
        t.note("merged radiance ratios: " + TestKit.fmt(merged[1] / merged[0]) +
                ", " + TestKit.fmt(merged[2] / merged[1]))

        // --- degenerate inputs ---------------------------------------------
        t.throwsException({ CrfEstimator.estimate(arrayOf(), relExposures, cfg) },
            "no samples is an error")
        t.throwsException({ CrfEstimator.estimate(z, doubleArrayOf(1.0), cfg) },
            "exposure count must match the sample table")
        val single = Array(nSamples) { DoubleArray(1) }
        for (i in 0 until nSamples) single[i][0] = z[i][2]
        t.throwsException({ CrfEstimator.estimate(single, doubleArrayOf(1.0 / 50), cfg) },
            "a single exposure cannot determine a response curve")

        // --- pixel sampling helper ------------------------------------------
        val frames = ArrayList<ImageF>()
        for (j in 0 until 3) {
            val f = ImageF(64, 48, 3)
            for (i in f.data.indices) f.data[i] = r.nextDouble().toFloat()
            frames.add(f)
        }
        val sampled = CrfEstimator.sampleFrames(frames, 100, 0)
        t.eq(100L, sampled.size.toLong(), "requested number of samples returned")
        t.eq(3L, sampled[0].size.toLong(), "one column per frame")
        val again = CrfEstimator.sampleFrames(frames, 100, 0)
        t.near(sampled[17][1], again[17][1], 1e-12, "sampling is deterministic for a given seed")
    }

    companion object {
        /** A deliberately non-gamma tone curve: sRGB with an extra shoulder. */
        @JvmStatic
        fun encode(linear: Double): Double {
            val v = Math.max(0.0, Math.min(1.0, linear))
            val s = if (v <= 0.0031308) 12.92 * v else 1.055 * Math.pow(v, 1 / 2.4) - 0.055
            return Math.max(0.0, Math.min(1.0, s * (1.06 - 0.06 * s)))   // gentle highlight shoulder
        }

        @JvmStatic
        fun decode(encoded: Double): Double {
            var lo = 0.0
            var hi = 1.0
            for (i in 0 until 60) {
                val mid = 0.5 * (lo + hi)
                if (encode(mid) < encoded) lo = mid else hi = mid
            }
            return 0.5 * (lo + hi)
        }
    }
}
