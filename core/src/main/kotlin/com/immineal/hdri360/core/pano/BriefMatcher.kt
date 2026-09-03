package com.immineal.hdri360.core.pano

/**
 * Brute-force Hamming matching with Lowe's ratio test and cross-check.
 *
 * Brute force is the right call here: a panorama frame carries a few hundred
 * features, so the whole cross product is well under a millisecond, and an
 * approximate index would only trade that for missed matches.
 */
object BriefMatcher {

    class Match(
        @JvmField val a: Int,
        @JvmField val b: Int,
        @JvmField val distance: Int
    )

    class Config {
        /** Best distance must be at most this fraction of the runner-up's. */
        @JvmField var ratio = 0.80
        @JvmField var crossCheck = true
        /** Reject matches worse than this many differing bits out of 256. */
        @JvmField var maxDistance = 96
    }

    @JvmStatic
    fun hamming(a: LongArray, b: LongArray): Int {
        var d = 0
        for (i in a.indices) d += java.lang.Long.bitCount(a[i] xor b[i])
        return d
    }

    @JvmStatic
    fun match(a: FeatureSet, b: FeatureSet, cfg: Config): List<Match> {
        val out = ArrayList<Match>()
        if (a.size() == 0 || b.size() == 0) return out

        val bestB = IntArray(a.size())
        val bestDist = IntArray(a.size())
        val secondDist = IntArray(a.size())
        for (i in 0 until a.size()) {
            bestB[i] = -1
            bestDist[i] = Int.MAX_VALUE
            secondDist[i] = Int.MAX_VALUE
            for (j in 0 until b.size()) {
                val d = hamming(a.descriptors[i], b.descriptors[j])
                if (d < bestDist[i]) { secondDist[i] = bestDist[i]; bestDist[i] = d; bestB[i] = j }
                else if (d < secondDist[i]) secondDist[i] = d
            }
        }

        var reverseBest: IntArray? = null
        if (cfg.crossCheck) {
            reverseBest = IntArray(b.size())
            for (j in 0 until b.size()) {
                var best = Int.MAX_VALUE
                var arg = -1
                for (i in 0 until a.size()) {
                    val d = hamming(a.descriptors[i], b.descriptors[j])
                    if (d < best) { best = d; arg = i }
                }
                reverseBest[j] = arg
            }
        }

        for (i in 0 until a.size()) {
            val j = bestB[i]
            if (j < 0 || bestDist[i] > cfg.maxDistance) continue
            if (secondDist[i] != Int.MAX_VALUE && bestDist[i] > cfg.ratio * secondDist[i]) continue
            if (reverseBest != null && reverseBest[j] != i) continue
            out.add(Match(i, j, bestDist[i]))
        }
        return out
    }
}
