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

    /**
     * Matching when it is already roughly known where each point should land.
     *
     * The plain matcher asks, of every point in one frame, which of five hundred
     * points in the other it most resembles. On a room full of repeated
     * structure - panels, edges, sockets, book spines - that contest is often won
     * by the wrong one, and the ratio test then throws away the right answer for
     * being insufficiently better than a decoy. Of 561 pairs from a real sphere,
     * 397 of those that reached the geometric check had three or fewer matches
     * that any rotation could reconcile.
     *
     * The phone knows roughly where it was pointing for each frame. That turns
     * the question into: of the handful of points near where this one should
     * have landed, which does it most resemble - a contest between five
     * candidates rather than five hundred, where a decoy on the far wall never
     * enters. [predictedX] and [predictedY] give, for each point of [a], where it
     * is expected in [b]'s pixels, or NaN where there is no prediction.
     */
    @JvmStatic
    fun matchNear(a: FeatureSet, b: FeatureSet, predictedX: DoubleArray,
                  predictedY: DoubleArray, radiusPx: Double, cfg: Config): List<Match> {
        val out = ArrayList<Match>()
        if (a.size() == 0 || b.size() == 0) return out
        val r2 = radiusPx * radiusPx
        // A grid over b, so a point looks at the handful of candidates near where
        // it should be rather than walking all of them. Without it the guided
        // matcher is still quadratic, and the whole point of guiding it is that
        // three thousand points a frame become affordable.
        val cell = Math.max(1.0, radiusPx)
        var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        for (kp in b.keypoints) {
            if (kp.x < minX) minX = kp.x.toDouble()
            if (kp.x > maxX) maxX = kp.x.toDouble()
            if (kp.y < minY) minY = kp.y.toDouble()
            if (kp.y > maxY) maxY = kp.y.toDouble()
        }
        val cols = Math.max(1, ((maxX - minX) / cell).toInt() + 1)
        val rows = Math.max(1, ((maxY - minY) / cell).toInt() + 1)
        val heads = IntArray(cols * rows) { -1 }
        val next = IntArray(b.size()) { -1 }
        for (j in 0 until b.size()) {
            val cx = Math.min(cols - 1, Math.max(0, ((b.keypoints[j].x - minX) / cell).toInt()))
            val cy = Math.min(rows - 1, Math.max(0, ((b.keypoints[j].y - minY) / cell).toInt()))
            val c = cy * cols + cx
            next[j] = heads[c]
            heads[c] = j
        }
        // One b may be the best answer for several a. Keep the closest, so a
        // point is used once and the pairs stay one to one as RANSAC expects.
        val takenBy = IntArray(b.size()) { -1 }
        val takenDist = IntArray(b.size()) { Int.MAX_VALUE }
        val bestOf = IntArray(a.size()) { -1 }
        val bestDist = IntArray(a.size()) { Int.MAX_VALUE }

        for (i in 0 until a.size()) {
            val px = predictedX[i]
            val py = predictedY[i]
            if (px.isNaN() || py.isNaN()) continue
            var best = -1
            var bd = Int.MAX_VALUE
            var sd = Int.MAX_VALUE
            val da = a.descriptors[i]
            val gx = ((px - minX) / cell).toInt()
            val gy = ((py - minY) / cell).toInt()
            for (oy in -1..1) for (ox in -1..1) {
                val cx = gx + ox
                val cy = gy + oy
                if (cx < 0 || cy < 0 || cx >= cols || cy >= rows) continue
                var j = heads[cy * cols + cx]
                while (j >= 0) {
                    val dx = b.keypoints[j].x - px
                    val dy = b.keypoints[j].y - py
                    if (dx * dx + dy * dy <= r2) {
                        val d = hamming(da, b.descriptors[j])
                        if (d < bd) { sd = bd; bd = d; best = j } else if (d < sd) sd = d
                    }
                    j = next[j]
                }
            }
            if (best < 0 || bd > cfg.maxDistance) continue
            // The ratio test still applies, but only among the candidates that
            // could geometrically be the point - which is the whole gain.
            if (sd != Int.MAX_VALUE && bd > cfg.ratio * sd) continue
            bestOf[i] = best
            bestDist[i] = bd
        }
        for (i in 0 until a.size()) {
            val j = bestOf[i]
            if (j < 0) continue
            if (bestDist[i] < takenDist[j]) { takenDist[j] = bestDist[i]; takenBy[j] = i }
        }
        for (j in 0 until b.size()) {
            val i = takenBy[j]
            if (i >= 0) out.add(Match(i, j, takenDist[j]))
        }
        return out
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
