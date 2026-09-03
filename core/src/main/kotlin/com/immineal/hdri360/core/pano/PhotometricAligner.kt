package com.immineal.hdri360.core.pano

import com.immineal.hdri360.core.math.Linalg
import java.util.Arrays

/**
 * Solves one brightness scale per frame so that overlapping frames agree.
 *
 * Even with locked exposure, frames disagree: residual vignetting, flare,
 * rolling-shutter gain wobble, and - for a set of ordinary photographs shot on
 * auto - wildly different exposures. Working in log space turns those
 * multiplicative offsets into a linear least-squares problem over the overlap
 * graph, which has a closed-form solution and no local minima.
 *
 * The scale is only determined up to a global factor, so the result is
 * normalised to unit geometric mean.
 */
object PhotometricAligner {

    /** One scene point seen by two frames, with the value each reported. */
    class Sample(
        @JvmField val frameA: Int,
        @JvmField val frameB: Int,
        @JvmField val valueA: Double,
        @JvmField val valueB: Double,
        @JvmField val weight: Double
    ) {
        init {
            if (!(valueA > 0) || !(valueB > 0))
                throw IllegalArgumentException("photometric samples must be positive radiances")
        }

        /** Log ratio the gains must explain. */
        fun logRatio(): Double = Math.log(valueB) - Math.log(valueA)
    }

    @JvmStatic
    fun solveGains(frameCount: Int, samples: List<Sample>?, regularization: Double): DoubleArray =
        solve(frameCount, samples, regularization, null)

    /**
     * Iteratively reweighted version. Mismatched correspondences produce wild log
     * ratios, and a plain least-squares fit will happily tilt the whole panorama
     * to accommodate them.
     */
    @JvmStatic
    fun solveGainsRobust(frameCount: Int, samples: List<Sample>?,
                         regularization: Double, iterations: Int): DoubleArray {
        if (samples == null || samples.isEmpty()) return solve(frameCount, samples, regularization, null)
        val extra = DoubleArray(samples.size)
        Arrays.fill(extra, 1.0)
        var x = solve(frameCount, samples, regularization, extra)
        for (it in 0 until iterations) {
            val residuals = DoubleArray(samples.size)
            for (i in samples.indices) {
                val s = samples[i]
                residuals[i] = Math.abs(Math.log(x[s.frameA]) - Math.log(x[s.frameB]) - s.logRatio())
            }
            val scale = 1.4826 * median(residuals.copyOf())
            val delta = Math.max(1e-3, 2.0 * scale)
            for (i in samples.indices)
                extra[i] = if (residuals[i] <= delta) 1.0 else delta / residuals[i]
            x = solve(frameCount, samples, regularization, extra)
        }
        return x
    }

    private fun solve(frameCount: Int, samples: List<Sample>?,
                      regularization: Double, extraWeights: DoubleArray?): DoubleArray {
        if (frameCount <= 0) throw IllegalArgumentException("frameCount must be positive")
        val a = Array(frameCount) { DoubleArray(frameCount) }
        val b = DoubleArray(frameCount)

        if (samples != null) {
            for (i in samples.indices) {
                val s = samples[i]
                if (s.frameA < 0 || s.frameB < 0 || s.frameA >= frameCount || s.frameB >= frameCount)
                    throw IllegalArgumentException("sample refers to a frame that does not exist")
                if (s.frameA == s.frameB) continue
                val w = s.weight * (if (extraWeights == null) 1.0 else extraWeights[i])
                if (!(w > 0)) continue
                // Residual: (xA - xB) - logRatio, where x is log gain.
                val r = s.logRatio()
                a[s.frameA][s.frameA] += w
                a[s.frameB][s.frameB] += w
                a[s.frameA][s.frameB] -= w
                a[s.frameB][s.frameA] -= w
                b[s.frameA] += w * r
                b[s.frameB] -= w * r
            }
        }
        val reg = Math.max(regularization, 1e-12)
        for (i in 0 until frameCount) a[i][i] += reg

        var x = Linalg.solveSpdDamped(a, b, 1e-12)
        if (x == null) x = DoubleArray(frameCount)

        var mean = 0.0
        for (v in x) mean += v
        mean /= frameCount

        val gains = DoubleArray(frameCount)
        for (i in 0 until frameCount) gains[i] = Math.exp(x[i] - mean)
        return gains
    }

    private fun median(v: DoubleArray): Double {
        Arrays.sort(v)
        return if (v.isEmpty()) 0.0 else v[v.size / 2]
    }
}
