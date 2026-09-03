package com.immineal.hdri360.test.suites

import com.immineal.hdri360.core.hdr.Exposure
import com.immineal.hdri360.core.hdr.HdrMerger
import com.immineal.hdri360.core.hdr.MergeConfig
import com.immineal.hdri360.core.hdr.MergeResult
import com.immineal.hdri360.core.hdr.NoiseModel
import com.immineal.hdri360.core.hdr.ResponseCurve
import com.immineal.hdri360.core.hdr.VignetteModel
import com.immineal.hdri360.core.image.ImageF
import com.immineal.hdri360.test.TestCase
import com.immineal.hdri360.test.TestKit

/**
 * The radiance merge. This is the part that decides whether the output is a
 * physically meaningful HDRI or just a pleasant-looking composite, so it is
 * tested against known ground-truth radiance rather than against itself.
 */
class MergeSuite : TestCase {
    override fun name(): String = "hdr-merge"

    private fun exposeNoiseless(radiance: DoubleArray, rel: Double): ImageF {
        val img = ImageF(radiance.size, 1, 1)
        for (i in radiance.indices)
            img.data[i] = Math.min(1.0, radiance[i] * rel).toFloat()
        return img
    }

    override fun run(t: TestKit) {
        val cfg = MergeConfig()
        val ladder = doubleArrayOf(1e-5, 8e-5, 6.4e-4, 5.12e-3, 4.096e-2, 0.32768)

        // --- noiseless accuracy over the covered range --------------------
        val n = 400
        val truth = DoubleArray(n)
        for (i in 0 until n) truth[i] = 2.0 * Math.pow(10.0, 5.0 * i / (n - 1.0)) // 2 .. 2e5
        val frames = ArrayList<Exposure>()
        for (e in ladder) frames.add(Exposure(exposeNoiseless(truth, e), e, 1.0))
        val res = HdrMerger.merge(frames, cfg)
        t.eq(n.toLong(), res.radiance.width.toLong(), "merged image keeps its width")
        t.eq(1L, res.radiance.channels.toLong(), "merged image keeps its channel count")

        var covered = 0
        var worstRel = 0.0
        for (i in 0 until n) {
            val tooBright = truth[i] * ladder[0] > cfg.satHigh
            val tooDark = truth[i] * ladder[ladder.size - 1] < 1e-3
            if (tooBright || tooDark) continue
            covered++
            val got = res.radiance.data[i].toDouble()
            worstRel = Math.max(worstRel, Math.abs(got - truth[i]) / truth[i])
            t.eq(0L, (res.flags[i].toInt() and MergeResult.FLAG_SATURATED).toLong(),
                "covered pixel is not flagged saturated")
        }
        t.greaterThan(covered.toDouble(), n * 0.7, "the ladder covers most of the test range")
        t.lessThan(worstRel, 1e-5, "noiseless merge is exact to 1e-5 relative")
        t.note("worst noiseless relative error: " + TestKit.fmt(worstRel))

        // --- beyond the top of the ladder ----------------------------------
        val tooBrightRadiance = 10.0 * cfg.satHigh / ladder[0]
        val hot = ArrayList<Exposure>()
        for (e in ladder) hot.add(Exposure(exposeNoiseless(doubleArrayOf(tooBrightRadiance), e), e, 1.0))
        val hotRes = HdrMerger.merge(hot, cfg)
        t.check((hotRes.flags[0].toInt() and MergeResult.FLAG_SATURATED) != 0,
            "an unrecoverable highlight is flagged")
        t.greaterThan(hotRes.radiance.data[0].toDouble(), cfg.satHigh / ladder[0] * 0.99,
            "a saturated pixel still reports at least its lower bound")
        t.check(hotRes.radiance.data[0].isFinite(), "a saturated pixel is finite, never inf or NaN")

        // --- below the bottom of the ladder --------------------------------
        val cold = ArrayList<Exposure>()
        for (e in ladder) cold.add(Exposure(exposeNoiseless(doubleArrayOf(0.0), e), e, 1.0))
        val coldRes = HdrMerger.merge(cold, cfg)
        t.check((coldRes.flags[0].toInt() and MergeResult.FLAG_NOISE_LIMITED) != 0,
            "a black pixel is flagged noise-limited")
        t.near(0.0, coldRes.radiance.data[0].toDouble(), 1e-6,
            "a black pixel merges to zero, not to a divide-by-zero")

        // --- weights behave -------------------------------------------------
        t.near(0.0, HdrMerger.sampleWeight(1.0, 0.01, 1.0, cfg), 1e-12,
            "a fully saturated sample has zero weight")
        t.near(0.0, HdrMerger.sampleWeight(cfg.satHigh + 1e-6, 0.01, 1.0, cfg), 1e-12,
            "weight is zero at the saturation cut")
        t.greaterThan(HdrMerger.sampleWeight(0.5, 0.01, 1.0, cfg), 0.0,
            "a mid-tone sample has positive weight")
        val wMid = HdrMerger.sampleWeight(0.5, 0.01, 1.0, cfg)
        val wNearSat = HdrMerger.sampleWeight(0.95, 0.01, 1.0, cfg)
        t.lessThan(wNearSat, wMid, "weight rolls off approaching saturation")
        val wLongExp = HdrMerger.sampleWeight(0.5, 0.1, 1.0, cfg)
        t.greaterThan(wLongExp, wMid, "a longer exposure of the same value carries more weight")

        // --- statistical behaviour with a real noise model -------------------
        val r = t.rng(4242)
        val noise = NoiseModel(1e-4, 4e-6)
        val ncfg = MergeConfig()
        ncfg.noise = noise
        val trueE = 1.0
        val mergeLadder = doubleArrayOf(0.01, 0.08, 0.64)
        val trials = 4000
        var sum = 0.0
        var sumSq = 0.0
        var singleSum = 0.0
        var singleSq = 0.0
        for (k in 0 until trials) {
            val fs = ArrayList<Exposure>()
            var bright = 0.0
            for (e in mergeLadder) {
                val clean = Math.min(1.0, trueE * e)
                val sigma = Math.sqrt(noise.variance(clean, 1.0))
                val v = Math.max(0.0, Math.min(1.0, clean + r.nextGaussian() * sigma))
                val img = ImageF(1, 1, 1)
                img.data[0] = v.toFloat()
                fs.add(Exposure(img, e, 1.0))
                if (e == 0.64) bright = v / e
            }
            val m = HdrMerger.merge(fs, ncfg).radiance.data[0].toDouble()
            sum += m; sumSq += m * m
            singleSum += bright; singleSq += bright * bright
        }
        val mean = sum / trials
        val varr = sumSq / trials - mean * mean
        val singleMean = singleSum / trials
        val singleVar = singleSq / trials - singleMean * singleMean
        t.nearRel(trueE, mean, 0.005, "the merge is unbiased to within 0.5%")
        t.lessThan(Math.sqrt(varr), Math.sqrt(singleVar),
            "merging beats the single best exposure on variance")
        t.note("merge std " + TestKit.fmt(Math.sqrt(varr)) + " vs best-single " +
                TestKit.fmt(Math.sqrt(singleVar)))

        // --- monotone in the input --------------------------------------------
        var prev = -1.0
        var e2 = 1.0
        while (e2 < 1e4) {
            val fs = ArrayList<Exposure>()
            for (e in ladder) fs.add(Exposure(exposeNoiseless(doubleArrayOf(e2), e), e, 1.0))
            val m = HdrMerger.merge(fs, cfg).radiance.data[0].toDouble()
            t.greaterThan(m, prev, "merged radiance is monotone in scene radiance")
            prev = m
            e2 *= 1.7
        }

        // --- a single frame still merges --------------------------------------
        val one = HdrMerger.merge(
            listOf(Exposure(exposeNoiseless(doubleArrayOf(5.0), 0.02), 0.02, 1.0)), cfg)
        t.nearRel(5.0, one.radiance.data[0].toDouble(), 1e-5, "a single exposure passes through as v/e")

        // --- ISO gain is accounted for ------------------------------------------
        // Same relative exposure reached two ways must give the same radiance.
        val viaTime = exposeNoiseless(doubleArrayOf(3.0), 0.04)
        val a = HdrMerger.merge(listOf(Exposure(viaTime, 0.04, 1.0)), cfg)
        val b = HdrMerger.merge(listOf(Exposure(viaTime, 0.04, 4.0)), cfg)
        t.nearRel(a.radiance.data[0].toDouble(), b.radiance.data[0].toDouble(), 1e-9,
            "radiance depends on total exposure, not on how the gain was split")

        // --- vignetting is undone before merging ----------------------------------
        val vig = VignetteModel.radial(-0.35, 0.05)
        val w = 41
        val h = 31
        val vcfg = MergeConfig()
        vcfg.vignette = vig
        val vframes = ArrayList<Exposure>()
        val flatRadiance = 4.0
        for (e in doubleArrayOf(0.002, 0.016, 0.128)) {
            val img = ImageF(w, h, 1)
            for (y in 0 until h)
                for (x in 0 until w) {
                    val falloff = vig.falloff(x.toDouble(), y.toDouble(), w, h)
                    img.set(x, y, 0, Math.min(1.0, flatRadiance * e * falloff).toFloat())
                }
            vframes.add(Exposure(img, e, 1.0))
        }
        val vres = HdrMerger.merge(vframes, vcfg)
        var vWorst = 0.0
        for (i in 0 until w * h)
            vWorst = Math.max(vWorst, Math.abs(vres.radiance.data[i] - flatRadiance) / flatRadiance)
        t.lessThan(vWorst, 1e-4, "vignetting correction flattens a flat field")
        t.note("worst residual after vignetting correction: " + TestKit.fmt(vWorst))

        // --- a non-linear response is linearised before merging ---------------------
        val srgb = ResponseCurve.fromFunction(1024) { linear -> srgbEncode(linear) }
        val rcfg = MergeConfig()
        rcfg.response = srgb
        val rframes = ArrayList<Exposure>()
        val rl = doubleArrayOf(0.004, 0.032, 0.256)
        for (e in rl) {
            val img = ImageF(1, 1, 1)
            img.data[0] = srgbEncode(Math.min(1.0, 6.0 * e)).toFloat()
            rframes.add(Exposure(img, e, 1.0))
        }
        t.nearRel(6.0, HdrMerger.merge(rframes, rcfg).radiance.data[0].toDouble(), 2e-3,
            "an encoded bracket merges correctly once the response curve is applied")

        // --- input validation -------------------------------------------------------
        t.throwsException({ HdrMerger.merge(ArrayList<Exposure>(), cfg) },
            "merging nothing is an error")
        t.throwsException({
            HdrMerger.merge(listOf(
                Exposure(ImageF(4, 4, 1), 0.1, 1.0),
                Exposure(ImageF(5, 4, 1), 0.2, 1.0)), cfg)
        }, "mismatched frame sizes are an error")
        t.throwsException({ Exposure(ImageF(4, 4, 1), 0.0, 1.0) },
            "a zero exposure time is an error")
    }

    companion object {
        @JvmStatic
        fun srgbEncode(linear: Double): Double {
            val v = Math.max(0.0, Math.min(1.0, linear))
            return if (v <= 0.0031308) 12.92 * v else 1.055 * Math.pow(v, 1 / 2.4) - 0.055
        }
    }
}
