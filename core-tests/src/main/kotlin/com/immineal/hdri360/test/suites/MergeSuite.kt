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
        aColourTransformGoesAfterTheMerge(t)
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

    /**
     * A colour transform belongs after the merge, never before it.
     *
     * The merge decides which rungs of a bracket to believe from the pixel value
     * itself: at [MergeConfig.satHigh] a sample is on the rail and carries no
     * information, so it is dropped. That test is only meaningful in the domain
     * the sensor actually measures in - a fraction of full well, in [0,1].
     *
     * A colour matrix breaks that domain. Its diagonal is well above one, so a
     * channel comfortably below saturation on the sensor lands above the
     * threshold after the transform, and the merge throws away a sample that was
     * perfectly good. Green has the largest coefficient on every phone matrix
     * ever shipped, so green loses its brightest valid rungs first and comes back
     * biased low - which is a magenta sphere.
     *
     * Measured on a real capture: a neutral patch that merges to 35.93 / 35.92 /
     * 35.91 in sensor space came out 35.76 / 11.88 / 27.06 when the camera's own
     * matrix was applied per rung beforehand. The same matrix applied to the
     * merged radiance leaves it at 35.94 / 35.91 / 35.90.
     */
    private fun aColourTransformGoesAfterTheMerge(t: TestKit) {
        val cfg = MergeConfig()
        // The Pixel 9a's own matrix: strongly diagonal, rows summing to one, so a
        // neutral in must be a neutral out.
        val m = doubleArrayOf(
            1.59375, -0.4609375, -0.1328125,
            -0.33203125, 1.44921875, -0.1171875,
            -0.01171875, -1.0, 2.01171875)

        // A grey card as the *sensor* sees it. Three equal numbers is not what a
        // neutral looks like in raw: green is the most sensitive channel, so a
        // neutral reads high in green and the white balance is what evens it up.
        // Those are the gains the Pixel 9a reported alongside the matrix above.
        val gains = doubleArrayOf(1.5622116327285767, 1.0, 1.6853481531143188)
        val neutral = doubleArrayOf(1.0 / gains[0], 1.0, 1.0 / gains[2])
        val truth = 2200.0
        // Deliberately spanning the rail: the brightest rung saturates green
        // while red and blue are still reading, which is the ordinary state of a
        // bracket and the case that breaks.
        val ladder = doubleArrayOf(1.0 / 8000, 1.0 / 3000, 1.0 / 1667)

        fun rung(rel: Double, colourFirst: Boolean): Exposure {
            val img = ImageF(1, 1, 3)
            for (c in 0 until 3)
                img.data[c] = Math.min(1.0, truth * neutral[c] * rel).toFloat()
            if (colourFirst) colourise(img, gains, m)
            return Exposure(img, rel, 1.0)
        }

        // Confirm the fixture really does contain the situation being tested.
        run {
            val hot = ImageF(1, 1, 3)
            for (c in 0 until 3)
                hot.data[c] = Math.min(1.0, truth * neutral[c] * ladder[ladder.size - 1]).toFloat()
            t.check(hot.data[1] >= cfg.satHigh, "the brightest rung really does saturate green")
            t.check(hot.data[0] < cfg.satHigh && hot.data[2] < cfg.satHigh,
                "while red and blue are still reading, which is what makes it interesting")
        }

        // Merged in the sensor's own domain, then coloured: grey stays grey and
        // the radiance survives.
        val sensor = HdrMerger.merge(ladder.map { rung(it, false) }, cfg).radiance
        colourise(sensor, gains, m)
        t.nearRel(sensor.data[0].toDouble(), sensor.data[1].toDouble(), 0.02,
            "a grey card merged then coloured is still grey")
        t.nearRel(sensor.data[0].toDouble(), sensor.data[2].toDouble(), 0.02,
            "in all three channels")
        t.nearRel(truth, sensor.data[1].toDouble(), 0.10,
            "and still the radiance that went in")

        // Coloured per rung and then merged, it is not. Green carries the largest
        // coefficient, so green is the channel that loses its samples.
        val ahead = HdrMerger.merge(ladder.map { rung(it, true) }, cfg).radiance
        val err = Math.max(
            Math.abs(ahead.data[1] / ahead.data[0] - 1.0),
            Math.abs(ahead.data[1] / ahead.data[2] - 1.0))
        t.greaterThan(err, 0.10,
            "colouring before the merge really does break it, which is why it is not done")
        t.lessThan(ahead.data[1] / sensor.data[1].toDouble(), 0.95,
            "and it breaks it by losing green, which is what made the sphere magenta")
        t.note(String.format(java.util.Locale.US,
            "grey card: merge-then-colour %.4g/%.4g/%.4g; colour-then-merge %.4g/%.4g/%.4g",
            sensor.data[0], sensor.data[1], sensor.data[2],
            ahead.data[0], ahead.data[1], ahead.data[2]))
    }

    /** White balance then matrix, the way the pipeline does it. */
    private fun colourise(image: ImageF, gains: DoubleArray, m: DoubleArray) {
        val g = gains[1]
        val gr = (gains[0] / g).toFloat()
        val gb = (gains[2] / g).toFloat()
        var i = 0
        while (i < image.data.size) {
            image.data[i] *= gr
            image.data[i + 2] *= gb
            val r = image.data[i].toDouble()
            val gg = image.data[i + 1].toDouble()
            val b = image.data[i + 2].toDouble()
            image.data[i] = (m[0] * r + m[1] * gg + m[2] * b).toFloat()
            image.data[i + 1] = (m[3] * r + m[4] * gg + m[5] * b).toFloat()
            image.data[i + 2] = (m[6] * r + m[7] * gg + m[8] * b).toFloat()
            i += image.channels
        }
    }
}
