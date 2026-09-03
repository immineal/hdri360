package com.immineal.hdri360.test.suites

import com.immineal.hdri360.core.hdr.VignetteCalibrator
import com.immineal.hdri360.core.hdr.VignetteModel
import com.immineal.hdri360.core.image.ImageF
import com.immineal.hdri360.test.TestCase
import com.immineal.hdri360.test.TestKit

/** Lens falloff: the model, and recovering it from overlap between frames. */
class VignetteSuite : TestCase {
    override fun name(): String = "vignette"

    override fun run(t: TestKit) {
        val r = t.rng(5150)

        // --- the model ------------------------------------------------------
        val v = VignetteModel.radial(-0.30, 0.04)
        val w = 101
        val h = 81
        t.near(1.0, v.falloff((w - 1) / 2.0, (h - 1) / 2.0, w, h), 1e-9,
            "no falloff at the optical centre")
        t.near(1 - 0.30 + 0.04, v.falloff(0.0, 0.0, w, h), 1e-9, "corner falloff is 1 + a2 + a4")
        t.near(v.falloff(0.0, 0.0, w, h), v.falloff((w - 1).toDouble(), (h - 1).toDouble(), w, h),
            1e-9, "falloff is radially symmetric")
        t.greaterThan(v.falloff((w - 1) / 2.0, 0.0, w, h), v.falloff(0.0, 0.0, w, h),
            "the edge is brighter than the corner")
        t.check(VignetteModel.none().isIdentity(), "the null model is identity")
        val map = v.falloffMap(w, h)
        t.eq((w * h).toLong(), map.size.toLong(), "falloff map covers the frame")
        t.near(v.falloff(7.0, 9.0, w, h), map[9 * w + 7].toDouble(), 1e-6,
            "map agrees with the point evaluation")

        // --- recovery from overlapping observations --------------------------
        // Each observation is one scene point seen at two different image radii;
        // its brightness ratio depends only on the falloff.
        val truth = VignetteModel.radial(-0.42, 0.09)
        val obs = ArrayList<VignetteCalibrator.Observation>()
        for (i in 0 until 600) {
            val r1 = r.nextDouble()
            val r2 = r.nextDouble()
            var ratio = truth.falloffAtRadius(r1) / truth.falloffAtRadius(r2)
            ratio *= Math.exp(r.nextGaussian() * 0.01)      // 1% photometric noise
            obs.add(VignetteCalibrator.Observation(r1, r2, ratio))
        }
        val fit = VignetteCalibrator.calibrate(obs, VignetteModel.none())
        t.near(truth.a2, fit.a2, 0.03, "a2 recovered")
        t.near(truth.a4, fit.a4, 0.05, "a4 recovered")
        var worst = 0.0
        var rr = 0.0
        while (rr <= 1.0) {
            worst = Math.max(worst, Math.abs(fit.falloffAtRadius(rr) - truth.falloffAtRadius(rr)))
            rr += 0.02
        }
        t.lessThan(worst, 0.01, "the fitted falloff curve matches truth everywhere")
        t.note("vignette fit a2=" + TestKit.fmt(fit.a2) + " a4=" + TestKit.fmt(fit.a4) +
                " (truth " + TestKit.fmt(truth.a2) + ", " + TestKit.fmt(truth.a4) + ")")

        // Degenerate input must not produce a wild model.
        val flat = ArrayList<VignetteCalibrator.Observation>()
        for (i in 0 until 50) flat.add(VignetteCalibrator.Observation(0.5, 0.5, 1.0))
        val degenerate = VignetteCalibrator.calibrate(flat, VignetteModel.none())
        t.lessThan(Math.abs(degenerate.a2), 0.5, "an unconstrained fit stays near its prior")
        t.check(VignetteCalibrator.calibrate(
            ArrayList(), VignetteModel.radial(-0.1, 0.0)).a2 == -0.1,
            "no observations leaves the prior untouched")

        // --- flat-field calibration -------------------------------------------
        val flatField = ImageF(129, 97, 1)
        val ffTruth = VignetteModel.radial(-0.25, 0.03)
        for (y in 0 until 97)
            for (x in 0 until 129)
                flatField.set(x, y, 0,
                    (0.6 * ffTruth.falloff(x.toDouble(), y.toDouble(), 129, 97)).toFloat())
        val ffFit = VignetteCalibrator.calibrateFromFlatField(flatField)
        t.near(ffTruth.a2, ffFit.a2, 0.01, "a2 from a flat field")
        t.near(ffTruth.a4, ffFit.a4, 0.02, "a4 from a flat field")
    }
}
