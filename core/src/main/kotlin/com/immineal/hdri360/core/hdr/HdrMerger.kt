package com.immineal.hdri360.core.hdr

import com.immineal.hdri360.core.image.ImageF

/**
 * Merges a bracket into linear radiance.
 *
 * Estimator: an inverse-variance weighted mean of v_i / e_i, which is the
 * minimum-variance unbiased combination under the affine noise model. Two
 * details matter for accuracy:
 *
 *  - Weights are computed in a second pass from the *predicted* value at each
 *    exposure rather than the measured one. Weighting by a noisy measurement
 *    correlates the weight with its own error and biases the mean low; the
 *    second pass removes that.
 *  - The saturation roll-off is always evaluated on the measured value, because
 *    whether a photosite hit the rail is a fact about the measurement, not about
 *    the estimate.
 *
 * When no exposure holds a pixel, the result is reported as a bound with a flag
 * rather than quietly extrapolated.
 */
object HdrMerger {

    @JvmStatic
    fun merge(frames: List<Exposure>?, cfg: MergeConfig): MergeResult {
        if (frames == null || frames.isEmpty()) throw IllegalArgumentException("no frames to merge")
        val first = frames[0].image
        val w = first.width
        val h = first.height
        val c = first.channels
        for (e in frames) {
            if (e.image.width != w || e.image.height != h || e.image.channels != c)
                throw IllegalArgumentException("bracket frames differ in size")
        }

        val nf = frames.size
        val rel = DoubleArray(nf)
        val gain = DoubleArray(nf)
        var darkest = 0
        for (i in 0 until nf) {
            rel[i] = frames[i].relativeExposure
            gain[i] = frames[i].gain
            if (rel[i] < rel[darkest]) darkest = i
        }

        var falloff: FloatArray? = null
        val vig = cfg.vignette
        if (vig != null && !vig.isIdentity()) falloff = vig.falloffMap(w, h)
        val cfgResp = cfg.response
        val resp: ResponseCurve? = if (cfgResp != null && !cfgResp.isIdentity()) cfgResp else null

        val out = ImageF(w, h, c)
        val flags = ByteArray(w * h)
        val weightOut = FloatArray(w * h)

        val v = DoubleArray(nf)
        val eEff = DoubleArray(nf)

        for (p in 0 until w * h) {
            val fo = if (falloff == null) 1.0 else falloff[p].toDouble()
            for (i in 0 until nf) eEff[i] = rel[i] * fo

            var pixFlags = 0
            var totalWeight = 0.0
            for (ch in 0 until c) {
                for (i in 0 until nf) {
                    val z = frames[i].image.data[p * c + ch].toDouble()
                    v[i] = if (resp == null) z else resp.toLinear(z)
                }

                // Pass 1: rough estimate using measured values for the weights.
                var acc = 0.0
                var wsum = 0.0
                for (i in 0 until nf) {
                    val roll = saturationRolloff(v[i], cfg)
                    if (roll <= 0) continue
                    val varr = cfg.noise.variance(v[i], gain[i])
                    if (!(varr > 0)) continue
                    val wt = roll * eEff[i] * eEff[i] / varr
                    acc += wt * (v[i] / eEff[i])
                    wsum += wt
                }
                val e1 = if (wsum > 0) acc / wsum else 0.0

                // Pass 2: weights from the predicted value, breaking the
                // weight/noise correlation that would otherwise bias the mean.
                var acc2 = 0.0
                var wsum2 = 0.0
                for (i in 0 until nf) {
                    val roll = saturationRolloff(v[i], cfg)
                    if (roll <= 0) continue
                    val predicted = Math.max(0.0, Math.min(1.0, e1 * eEff[i]))
                    val varr = cfg.noise.variance(predicted, gain[i])
                    if (!(varr > 0)) continue
                    val wt = roll * eEff[i] * eEff[i] / varr
                    acc2 += wt * (v[i] / eEff[i])
                    wsum2 += wt
                }
                var e = if (wsum2 > 0) acc2 / wsum2 else e1

                // Even the shortest exposure was on the rail: report the lower bound.
                if (v[darkest] >= cfg.satHigh) {
                    pixFlags = pixFlags or MergeResult.FLAG_SATURATED
                    e = cfg.satHigh / eEff[darkest]
                }
                // Nothing anywhere in the bracket rose meaningfully above the noise.
                var bestSnr = 0.0
                for (i in 0 until nf) {
                    val sigma = cfg.noise.sigma(v[i], gain[i])
                    if (sigma > 0) bestSnr = Math.max(bestSnr, v[i] / sigma)
                }
                if (bestSnr < cfg.minSnr) pixFlags = pixFlags or MergeResult.FLAG_NOISE_LIMITED

                out.data[p * c + ch] = e.toFloat()
                totalWeight += wsum2
            }
            flags[p] = pixFlags.toByte()
            weightOut[p] = (totalWeight / c).toFloat()
        }
        return MergeResult(out, flags, weightOut)
    }

    /** Weight one sample would carry. Exposed so the planner and tests can reason about it. */
    @JvmStatic
    fun sampleWeight(value: Double, relativeExposure: Double, gain: Double, cfg: MergeConfig): Double {
        val roll = saturationRolloff(value, cfg)
        if (roll <= 0) return 0.0
        val varr = cfg.noise.variance(value, gain)
        if (!(varr > 0)) return 0.0
        return roll * relativeExposure * relativeExposure / varr
    }

    /** 1 well below saturation, smoothly to 0 at satHigh. */
    @JvmStatic
    internal fun saturationRolloff(v: Double, cfg: MergeConfig): Double {
        if (v >= cfg.satHigh) return 0.0
        if (v <= cfg.satLow) return 1.0
        val u = (v - cfg.satLow) / (cfg.satHigh - cfg.satLow)
        val s = u * u * (3 - 2 * u)
        return 1 - s
    }
}
