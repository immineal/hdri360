package com.immineal.hdri360.core.hdr

import com.immineal.hdri360.core.image.ImageF
import com.immineal.hdri360.core.image.ImageOps
import com.immineal.hdri360.core.math.Linalg
import java.util.Random

/**
 * Debevec-Malik camera response recovery.
 *
 * Solves for log-exposure g(z) at every quantisation level together with a log
 * radiance per sample point, subject to a second-difference smoothness prior and
 * a gauge constraint at mid grey. Samples are weighted by a hat function so that
 * values near black and near clipping - the two places the sensor lies - carry
 * no influence.
 *
 * Only needed when the device cannot give RAW; a RAW frame is already linear.
 */
object CrfEstimator {

    class Config {
        /** Quantisation levels of the encoded input; 256 for 8-bit JPEG. */
        @JvmField var levels = 256
        /** Weight on the second-difference prior. Higher is smoother. */
        @JvmField var smoothness = 40.0
    }

    /**
     * @param encoded encoded values in [0,1]; encoded[sample][frame]
     * @param relativeExposure one per frame, same order as the columns
     */
    @JvmStatic
    fun estimate(encoded: Array<DoubleArray>?, relativeExposure: DoubleArray?, cfg: Config): ResponseCurve {
        if (encoded == null || encoded.isEmpty()) throw IllegalArgumentException("no samples")
        if (relativeExposure == null || relativeExposure.size < 2)
            throw IllegalArgumentException("at least two exposures are needed to recover a response")
        val p = relativeExposure.size
        for (row in encoded)
            if (row.size != p)
                throw IllegalArgumentException("sample table does not match the exposure list")

        val n = cfg.levels
        val nSamples = encoded.size
        val unknowns = n + nSamples

        val ata = Array(unknowns) { DoubleArray(unknowns) }
        val atb = DoubleArray(unknowns)

        // Data terms: g(z_ij) - lnE_i = ln t_j
        for (i in 0 until nSamples) {
            for (j in 0 until p) {
                val k = level(encoded[i][j], n)
                val w = hatWeight(k, n)
                if (w <= 0) continue
                addRow3(ata, atb, k, w, n + i, -w, -1, 0.0, w * Math.log(relativeExposure[j]))
            }
        }
        // Smoothness: lambda * (g[k-1] - 2 g[k] + g[k+1]) = 0.
        // Deliberately NOT scaled by the hat weight. The hat correctly removes
        // clipped and black samples from the DATA terms, but if it also weakens
        // the prior there, the top few levels end up unconstrained - and since
        // the curve is normalised at white, that leaks straight into a global
        // scale error on every recovered radiance.
        for (k in 1 until n - 1) {
            val w = cfg.smoothness
            if (w <= 0) continue
            addRow3(ata, atb, k - 1, w, k, -2 * w, k + 1, w, 0.0)
        }
        // Gauge: fix the scale by pinning mid grey.
        addRow3(ata, atb, n / 2, 1.0, -1, 0.0, -1, 0.0, 0.0)

        for (i in 0 until unknowns) ata[i][i] += 1e-9
        val x = Linalg.solveSpdDamped(ata, atb, 1e-10)
            ?: throw IllegalStateException("response curve solve failed")

        val lut = DoubleArray(n)
        var running = 0.0
        for (k in 0 until n) {
            val v = Math.exp(x[k])
            running = Math.max(running, v)       // enforce monotonicity
            lut[k] = running
        }
        val top = lut[n - 1]
        if (!(top > 0)) throw IllegalStateException("degenerate response curve")
        for (k in 0 until n) lut[k] /= top       // white encodes to linear 1
        return ResponseCurve.fromLut(lut)
    }

    /** Adds one weighted equation with up to three non-zero coefficients to the normal equations. */
    private fun addRow3(ata: Array<DoubleArray>, atb: DoubleArray,
                        i0: Int, c0: Double, i1: Int, c1: Double,
                        i2: Int, c2: Double, rhs: Double) {
        val idx = intArrayOf(i0, i1, i2)
        val coef = doubleArrayOf(c0, c1, c2)
        for (a in 0 until 3) {
            if (idx[a] < 0 || coef[a] == 0.0) continue
            atb[idx[a]] += coef[a] * rhs
            for (b in 0 until 3) {
                if (idx[b] < 0 || coef[b] == 0.0) continue
                ata[idx[a]][idx[b]] += coef[a] * coef[b]
            }
        }
    }

    private fun level(encoded: Double, n: Int): Int {
        val k = Math.round(Math.max(0.0, Math.min(1.0, encoded)) * (n - 1)).toInt()
        return Math.max(0, Math.min(n - 1, k))
    }

    /** Triangular weight: zero at both rails, peak at mid grey. */
    private fun hatWeight(k: Int, n: Int): Double {
        val mid = (n - 1) / 2.0
        val w = if (k <= mid) k.toDouble() else (n - 1 - k).toDouble()
        return w / mid
    }

    /**
     * Draws a spatially stratified, deterministic set of sample points and reads
     * them from every frame. Stratified rather than uniform so that a small
     * sample still spans the whole scene's brightness range.
     */
    @JvmStatic
    fun sampleFrames(frames: List<ImageF>?, maxSamples: Int, seed: Long): Array<DoubleArray> {
        if (frames == null || frames.isEmpty()) throw IllegalArgumentException("no frames")
        val first = frames[0]
        val w = first.width
        val h = first.height
        for (f in frames)
            if (f.width != w || f.height != h) throw IllegalArgumentException("frames differ in size")

        val lum = arrayOfNulls<ImageF>(frames.size)
        for (i in frames.indices)
            lum[i] = if (frames[i].channels >= 3) ImageOps.luminance(frames[i]) else frames[i]

        val rng = Random(seed)
        val cols = Math.max(1, Math.ceil(Math.sqrt(maxSamples * w / h.toDouble())).toInt())
        val rows = Math.max(1, Math.ceil(maxSamples / cols.toDouble()).toInt())
        val out = Array(maxSamples) { DoubleArray(frames.size) }
        var written = 0
        var gy = 0
        while (gy < rows && written < maxSamples) {
            var gx = 0
            while (gx < cols && written < maxSamples) {
                var x = ((gx + rng.nextDouble()) * w / cols).toInt()
                var y = ((gy + rng.nextDouble()) * h / rows).toInt()
                x = ImageOps.clamp(x, 0, w - 1)
                y = ImageOps.clamp(y, 0, h - 1)
                for (i in frames.indices) out[written][i] = lum[i]!!.get(x, y, 0).toDouble()
                written++
                gx++
            }
            gy++
        }
        if (written < maxSamples) {
            return Array(written) { out[it] }
        }
        return out
    }
}
