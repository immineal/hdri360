package com.immineal.hdri360.core.hdr

import com.immineal.hdri360.core.image.ImageF
import com.immineal.hdri360.core.math.Linalg

/**
 * Fits the radial falloff.
 *
 * The interesting case is self-calibration: a panorama shoots the same scene
 * point near one frame's centre and near another's edge, and the brightness
 * ratio between those two views depends only on the lens, not on the scene. That
 * gives a calibration for free from the overlap the capture already requires,
 * with no grey card and no flat-field shot.
 *
 * Solved by Gauss-Newton on the log residual, which makes the multiplicative
 * ratio noise additive and keeps the two parameters well conditioned.
 */
object VignetteCalibrator {

    /** One scene point seen at two image radii, with the observed brightness ratio v1/v2. */
    class Observation(
        @JvmField val r1: Double,
        @JvmField val r2: Double,
        @JvmField val ratio: Double
    ) {
        init {
            if (!(ratio > 0)) throw IllegalArgumentException("ratio must be positive")
        }
    }

    private const val A2_MIN = -0.9
    private const val A2_MAX = 0.5
    private const val A4_MIN = -0.5
    private const val A4_MAX = 0.5

    @JvmStatic
    fun calibrate(obs: List<Observation>?, prior: VignetteModel): VignetteModel {
        if (obs == null || obs.isEmpty()) return prior
        var a2 = prior.a2
        var a4 = prior.a4

        for (iter in 0 until 40) {
            var h00 = 0.0; var h01 = 0.0; var h11 = 0.0
            var g0 = 0.0; var g1 = 0.0; var cost = 0.0
            for (o in obs) {
                val f1 = falloff(a2, a4, o.r1)
                val f2 = falloff(a2, a4, o.r2)
                val e = Math.log(f1) - Math.log(f2) - Math.log(o.ratio)
                val s1 = o.r1 * o.r1
                val s2 = o.r2 * o.r2
                val j0 = s1 / f1 - s2 / f2
                val j1 = s1 * s1 / f1 - s2 * s2 / f2
                h00 += j0 * j0; h01 += j0 * j1; h11 += j1 * j1
                g0 += j0 * e; g1 += j1 * e
                cost += e * e
            }
            // Damped 2x2 solve; a degenerate set of observations simply produces no step.
            val lambda = 1e-6 * Math.max(1.0, h00 + h11) + 1e-12
            val m00 = h00 + lambda
            val m11 = h11 + lambda
            val det = m00 * m11 - h01 * h01
            if (!(Math.abs(det) > 1e-300)) break
            val d0 = -(m11 * g0 - h01 * g1) / det
            val d1 = -(m00 * g1 - h01 * g0) / det
            val na2 = clamp(a2 + d0, A2_MIN, A2_MAX)
            val na4 = clamp(a4 + d1, A4_MIN, A4_MAX)
            if (Math.abs(na2 - a2) < 1e-12 && Math.abs(na4 - a4) < 1e-12) { a2 = na2; a4 = na4; break }
            a2 = na2; a4 = na4
            if (cost == 0.0) break
        }
        return VignetteModel.radial(a2, a4)
    }

    /** Fit from a photograph of an evenly lit surface: unknown brightness plus the two shape terms. */
    @JvmStatic
    fun calibrateFromFlatField(flat: ImageF): VignetteModel {
        val w = flat.width
        val h = flat.height
        var a2 = 0.0
        var a4 = 0.0
        // Seed the brightness from the centre so the first step is small.
        var lnK = Math.log(Math.max(1e-9,
            flat.sampleBilinear((w - 1) / 2.0, (h - 1) / 2.0, 0).toDouble()))

        for (iter in 0 until 60) {
            val hh = Array(3) { DoubleArray(3) }
            val gg = DoubleArray(3)
            var y = 0
            while (y < h) {
                var x = 0
                while (x < w) {
                    val v = flat.get(x, y, 0).toDouble()
                    if (v > 1e-9) {
                        val r = VignetteModel.normalizedRadius(x.toDouble(), y.toDouble(), w, h)
                        val f = falloff(a2, a4, r)
                        val e = Math.log(v) - lnK - Math.log(f)
                        val r2 = r * r
                        val j = doubleArrayOf(1.0, r2 / f, r2 * r2 / f)
                        for (i in 0 until 3) {
                            gg[i] += j[i] * e
                            for (k in 0 until 3) hh[i][k] += j[i] * j[k]
                        }
                    }
                    x += Math.max(1, w / 96)
                }
                y += Math.max(1, h / 96)
            }
            for (i in 0 until 3) hh[i][i] += 1e-9 * Math.max(1.0, hh[i][i]) + 1e-12
            val step = Linalg.solveSpdDamped(hh, gg, 1e-9) ?: break
            lnK += step[0]
            a2 = clamp(a2 + step[1], A2_MIN, A2_MAX)
            a4 = clamp(a4 + step[2], A4_MIN, A4_MAX)
            if (Math.abs(step[0]) + Math.abs(step[1]) + Math.abs(step[2]) < 1e-12) break
        }
        return VignetteModel.radial(a2, a4)
    }

    private fun falloff(a2: Double, a4: Double, r: Double): Double {
        val r2 = r * r
        return Math.max(1e-3, 1.0 + a2 * r2 + a4 * r2 * r2)
    }

    private fun clamp(v: Double, lo: Double, hi: Double): Double =
        if (v < lo) lo else (if (v > hi) hi else v)
}
