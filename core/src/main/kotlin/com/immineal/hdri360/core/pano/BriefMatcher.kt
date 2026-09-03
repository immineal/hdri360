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

        // One pass over the cross product, not two. The cross-check needs each b's
        // nearest a, which the same distances already determine; computing them
        // again doubled the cost of the single most expensive stage in the
        // pipeline. Tie-breaking is unchanged: i still increases monotonically for
        // any fixed j, and only a strictly smaller distance displaces the
        // incumbent, so the first minimum still wins.
        val bestB = IntArray(a.size())
        val bestDist = IntArray(a.size())
        val secondDist = IntArray(a.size())
        val crossCheck = cfg.crossCheck
        val reverseBest = if (crossCheck) IntArray(b.size()) { -1 } else null
        val reverseDist = if (crossCheck) IntArray(b.size()) { Int.MAX_VALUE } else null

        for (i in 0 until a.size()) {
            bestB[i] = -1
            bestDist[i] = Int.MAX_VALUE
            secondDist[i] = Int.MAX_VALUE
            val da = a.descriptors[i]
            for (j in 0 until b.size()) {
                val d = hamming(da, b.descriptors[j])
                if (d < bestDist[i]) { secondDist[i] = bestDist[i]; bestDist[i] = d; bestB[i] = j }
                else if (d < secondDist[i]) secondDist[i] = d
                if (crossCheck && d < reverseDist!![j]) { reverseDist[j] = d; reverseBest!![j] = i }
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
