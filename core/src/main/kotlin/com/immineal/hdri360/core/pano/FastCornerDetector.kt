package com.immineal.hdri360.core.pano

import com.immineal.hdri360.core.image.ImageF
import java.util.Collections

/**
 * FAST-9 corner detection on a single-channel image.
 *
 * Feed it something perceptually uniform - log luminance for merged radiance,
 * the gamma-encoded frame for JPEG input. Running it on raw linear radiance
 * would put every detection in the sky and none in the shadows.
 */
object FastCornerDetector {

    class Config {
        /** Intensity difference that counts as "clearly brighter/darker", in image units. */
        @JvmField var threshold = 0.05
        @JvmField var maxFeatures = 800
        /** Keep detections this far from the edge so descriptors always have a full patch. */
        @JvmField var border = 24
        @JvmField var nonMaxSuppress = true
    }

    /** Bresenham circle of radius 3, in order around the circle. */
    private val CX = intArrayOf(0, 1, 2, 3, 3, 3, 2, 1, 0, -1, -2, -3, -3, -3, -2, -1)
    private val CY = intArrayOf(-3, -3, -2, -1, 0, 1, 2, 3, 3, 3, 2, 1, 0, -1, -2, -3)
    private const val ARC = 9

    @JvmStatic
    fun detect(gray: ImageF, cfg: Config): List<Keypoint> {
        val w = gray.width
        val h = gray.height
        val ch = gray.channels
        val b = Math.max(3, cfg.border)
        val score = FloatArray(w * h)
        val thr = cfg.threshold

        for (y in b until h - b) {
            for (x in b until w - b) {
                val ip = gray.data[(y * w + x) * ch].toDouble()
                var brighter = 0
                var darker = 0
                // Cheap rejection on the four compass points. The familiar
                // "three of four" high-speed test is only valid for FAST-12; a
                // 9-pixel arc is guaranteed to contain just TWO of the four
                // equally spaced compass points, so requiring three silently
                // throws away real corners (an axis-aligned square corner, for
                // one, has exactly two).
                var q = 0
                while (q < 16) {
                    val v = gray.data[((y + CY[q]) * w + (x + CX[q])) * ch].toDouble()
                    if (v > ip + thr) brighter++
                    else if (v < ip - thr) darker++
                    q += 4
                }
                if (brighter < 2 && darker < 2) continue

                val ring = DoubleArray(16)
                for (i in 0 until 16) ring[i] = gray.data[((y + CY[i]) * w + (x + CX[i])) * ch].toDouble()
                if (!hasArc(ring, ip, thr, true) && !hasArc(ring, ip, thr, false)) continue

                var s = 0.0
                for (i in 0 until 16) s += Math.max(0.0, Math.abs(ring[i] - ip) - thr)
                score[y * w + x] = s.toFloat()
            }
        }

        var out = ArrayList<Keypoint>()
        for (y in b until h - b) {
            for (x in b until w - b) {
                val s = score[y * w + x]
                if (s <= 0) continue
                if (cfg.nonMaxSuppress && !isLocalMax(score, w, x, y)) continue
                out.add(Keypoint(x.toFloat(), y.toFloat(), s, 0f))
            }
        }
        out.sortWith { p, q -> java.lang.Float.compare(q.score, p.score) }
        if (out.size > cfg.maxFeatures) out = ArrayList(out.subList(0, cfg.maxFeatures))
        return Collections.unmodifiableList(out)
    }

    /** True if at least ARC consecutive ring samples are all above (or all below) the threshold. */
    private fun hasArc(ring: DoubleArray, ip: Double, thr: Double, brighter: Boolean): Boolean {
        val limit = if (brighter) ip + thr else ip - thr
        var run = 0
        var best = 0
        for (i in 0 until 16 + ARC) {
            val v = ring[i % 16]
            val ok = if (brighter) v > limit else v < limit
            if (ok) { run++; best = Math.max(best, run) } else run = 0
            if (best >= ARC) return true
        }
        return false
    }

    private fun isLocalMax(score: FloatArray, w: Int, x: Int, y: Int): Boolean {
        val s = score[y * w + x]
        for (dy in -1..1)
            for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val n = score[(y + dy) * w + (x + dx)]
                // Strict on one side, loose on the other, so a plateau keeps exactly one point.
                if (n > s || (n == s && (dy < 0 || (dy == 0 && dx < 0)))) return false
            }
        return true
    }
}
