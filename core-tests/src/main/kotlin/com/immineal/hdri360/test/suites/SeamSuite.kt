package com.immineal.hdri360.test.suites

import com.immineal.hdri360.core.pano.SeamFinder
import com.immineal.hdri360.test.TestCase
import com.immineal.hdri360.test.TestKit
import java.util.Random

/**
 * Choosing which frame supplies each part of the panorama.
 *
 * Two things have to be true and they are tested separately. The optimiser has
 * to actually minimise its energy - checked against exhaustive enumeration on
 * problems small enough to enumerate. And the energy has to encode the thing we
 * care about - checked by planting a moving object and a parallax shift in
 * synthetic frames and requiring that the seam routes around them rather than
 * through them, because a seam through a moving object is precisely the ghost
 * this exists to prevent.
 */
class SeamSuite : TestCase {
    override fun name(): String = "seam"

    /** Exhaustive minimum, for problems small enough to enumerate. */
    private fun bruteForce(p: SeamFinder.Problem, cfg: SeamFinder.Config): Double {
        val pixels = p.width * p.height
        val labels = IntArray(pixels)
        var best = Double.MAX_VALUE
        val choices = IntArray(pixels)
        for (i in 0 until pixels) choices[i] = Math.max(1, p.count[i])

        var total = 1L
        for (c in choices) total *= c
        for (n in 0 until total) {
            var rest = n
            for (i in 0 until pixels) {
                val c = choices[i]
                val pick = (rest % c).toInt()
                rest /= c
                labels[i] = if (p.count[i] == 0) SeamFinder.NO_LABEL
                            else p.label[i * p.maxCandidates + pick]
            }
            best = Math.min(best, SeamFinder.energyOf(p, cfg, labels))
        }
        return best
    }

    override fun run(t: TestKit) {
        val r = t.rng(70247)

        // --- against exhaustive enumeration ---------------------------------
        // Small enough to enumerate every labelling, so there is nothing to argue
        // about: the optimiser either found the minimum or it did not.
        val cfg = SeamFinder.Config()
        cfg.wrapHorizontally = false
        var exact = 0
        var trials = 0
        var worstExcess = 0.0
        for (trial in 0 until 60) {
            val w = 3
            val h = 3
            val labelCount = 2 + r.nextInt(2)
            val p = SeamFinder.Problem(w, h, labelCount, labelCount)
            for (i in 0 until w * h) {
                for (l in 0 until labelCount) {
                    if (r.nextDouble() < 0.75)
                        p.add(i, l, (r.nextGaussian() * 1.5).toFloat(),
                            (0.2 + r.nextDouble()).toFloat())
                }
                // Every pixel needs at least one candidate for the comparison to mean
                // anything; give it one rather than skewing the sample by discarding.
                if (p.count[i] == 0) p.add(i, 0, r.nextGaussian().toFloat(), 1.0f)
            }
            val res = SeamFinder.solve(p, cfg)
            val truth = bruteForce(p, cfg)
            t.greaterThan(res.energy, truth - 1e-9,
                "no labelling can be cheaper than the exhaustive minimum")
            t.lessThan(res.energy, res.initialEnergy + 1e-12,
                "and never leaves the labelling worse than it started")
            if (res.energy <= truth + 1e-9) exact++
            worstExcess = Math.max(worstExcess, (res.energy - truth) / Math.max(1e-9, truth))
            trials++
        }
        t.greaterThan(exact.toDouble(), trials * 0.9,
            "the global optimum is reached on the overwhelming majority of small problems")
        t.note("reached the exhaustive optimum on " + exact + " of " + trials +
                " enumerable problems, worst excess " + TestKit.fmt(worstExcess * 100) + "%")

        // --- a moving object must not be cut through --------------------------
        // Two frames of the same scene, one of which has someone standing in it.
        // Averaging would ghost them; a seam through them would slice them in half.
        // The whole blob has to come from one frame.
        run {
            val w = 48
            val h = 32
            val p = SeamFinder.Problem(w, h, 2, 2)
            val blobX0 = 14; val blobX1 = 26; val blobY0 = 8; val blobY1 = 24
            val inBlob = BooleanArray(w * h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val i = y * w + x
                    // Shared background, in log radiance.
                    val bg = (0.4 * Math.sin(x * 0.21) + 0.3 * Math.cos(y * 0.17)).toFloat()
                    val blob = x in blobX0..blobX1 && y in blobY0..blobY1
                    inBlob[i] = blob
                    // Frame 0 sees the scene as it is; frame 1 has a person in it.
                    p.add(i, 0, bg, weightAcross(x, w, true))
                    p.add(i, 1, if (blob) (bg + 2.5f) else bg, weightAcross(x, w, false))
                }
            }
            val c = SeamFinder.Config()
            c.wrapHorizontally = false
            val res = SeamFinder.solve(p, c)

            var blobPixels = 0
            var fromFrame0 = 0
            for (i in 0 until w * h) if (inBlob[i]) {
                blobPixels++
                if (res.labels[i] == 0) fromFrame0++
            }
            t.greaterThan(blobPixels.toDouble(), 100.0, "the planted object is a real region")
            val uniform = Math.max(fromFrame0, blobPixels - fromFrame0) / blobPixels.toDouble()
            t.greaterThan(uniform, 0.99,
                "the moving object is taken whole from one frame, not split by a seam")
            t.lessThan(res.energy, res.initialEnergy,
                "and the expansion improved on what weighting alone would have chosen")
            t.note("moving object: " + TestKit.fmt(uniform * 100) + "% of it from a single frame; " +
                    "energy " + TestKit.fmt(res.initialEnergy) + " -> " + TestKit.fmt(res.energy))
        }

        // --- parallax: the same object in two places --------------------------
        // A near object that shifted between frames is disagreement in two places
        // at once. Neither copy may be cut through, or it appears twice.
        run {
            val w = 56
            val h = 28
            val p = SeamFinder.Problem(w, h, 2, 2)
            val shift = 7
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val i = y * w + x
                    val bg = (0.25 * Math.sin(x * 0.3) * Math.cos(y * 0.2)).toFloat()
                    val inA = x in 20..30 && y in 8..20
                    val inB = x in (20 + shift)..(30 + shift) && y in 8..20
                    p.add(i, 0, if (inA) bg + 2.0f else bg, 1.0f)
                    p.add(i, 1, if (inB) bg + 2.0f else bg, 1.0f)
                }
            }
            val c = SeamFinder.Config()
            c.wrapHorizontally = false
            val res = SeamFinder.solve(p, c)

            // Neither copy of the object may be split.
            var splitA = 0
            var splitB = 0
            var nA = 0
            var nB = 0
            var aFrom0 = 0
            var bFrom0 = 0
            for (y in 8..20) {
                for (x in 20..30) { nA++; if (res.labels[y * w + x] == 0) aFrom0++ }
                for (x in (20 + shift)..(30 + shift)) { nB++; if (res.labels[y * w + x] == 0) bFrom0++ }
            }
            splitA = Math.min(aFrom0, nA - aFrom0)
            splitB = Math.min(bFrom0, nB - bFrom0)
            t.lessThan(splitA / nA.toDouble(), 0.02, "the object's first position is not cut through")
            t.lessThan(splitB / nB.toDouble(), 0.02, "nor its second")
            t.note("parallax pair: " + splitA + "/" + nA + " and " + splitB + "/" + nB +
                    " pixels on the minority side of a seam")
        }

        // --- agreement leaves the seam free to follow the data ------------------
        run {
            val w = 24
            val h = 16
            val p = SeamFinder.Problem(w, h, 2, 2)
            for (y in 0 until h) for (x in 0 until w) {
                val i = y * w + x
                val v = (0.1 * x).toFloat()
                // Identical radiance; frame 0 is better on the left, frame 1 on the right.
                p.add(i, 0, v, (1.0 - x / (w - 1.0)).toFloat() + 0.01f)
                p.add(i, 1, v, (x / (w - 1.0)).toFloat() + 0.01f)
            }
            val c = SeamFinder.Config()
            c.wrapHorizontally = false
            val res = SeamFinder.solve(p, c)
            var leftFrom0 = 0
            var rightFrom1 = 0
            for (y in 0 until h) {
                if (res.labels[y * w + 1] == 0) leftFrom0++
                if (res.labels[y * w + (w - 2)] == 1) rightFrom1++
            }
            t.eq(h.toLong(), leftFrom0.toLong(),
                "where frames agree, each side is taken from the frame that sees it best")
            t.eq(h.toLong(), rightFrom1.toLong(), "on both sides")
        }

        // --- coverage and bookkeeping --------------------------------------------
        run {
            val w = 8
            val h = 8
            val p = SeamFinder.Problem(w, h, 2, 2)
            for (i in 0 until w * h) {
                if (i % 3 == 0) continue                     // deliberately uncovered
                p.add(i, i % 2, 0.5f, 1.0f)
            }
            val res = SeamFinder.solve(p, SeamFinder.Config())
            for (i in 0 until w * h) {
                if (i % 3 == 0) t.eq(SeamFinder.NO_LABEL.toLong(), res.labels[i].toLong(),
                    "an uncovered pixel gets no label")
                else t.check(res.labels[i] >= 0, "a covered pixel gets one")
            }
        }

        // --- the wrap is respected -----------------------------------------------
        // An equirectangular canvas joins at its left and right edges, so a seam
        // must be able to run across the join, and the column pair either side of
        // it has to be charged like any other.
        run {
            val w = 16
            val h = 8
            val p = SeamFinder.Problem(w, h, 2, 2)
            for (y in 0 until h) for (x in 0 until w) {
                val i = y * w + x
                p.add(i, 0, if (x == 0) 3.0f else 0.0f, 1.0f)
                p.add(i, 1, 0.0f, 1.0f)
            }
            val labelsAll1 = IntArray(w * h) { 1 }
            val wrapped = SeamFinder.Config()
            wrapped.wrapHorizontally = true
            val open = SeamFinder.Config()
            open.wrapHorizontally = false
            t.greaterThan(SeamFinder.energyOf(p, wrapped, labelsAll1),
                SeamFinder.energyOf(p, open, labelsAll1) - 1e-12,
                "wrapping adds the join's own seam cost, never removes cost")
            val mixed = IntArray(w * h) { if (it % w < w / 2) 0 else 1 }
            t.greaterThan(SeamFinder.energyOf(p, wrapped, mixed),
                SeamFinder.energyOf(p, open, mixed),
                "and a labelling that changes across the join is charged for it")
        }
    }

    /** A frame's weight falling off toward one side, as a real frame's does. */
    private fun weightAcross(x: Int, w: Int, leftFrame: Boolean): Float {
        val u = x / (w - 1.0)
        return (if (leftFrame) (1.0 - u) else u).toFloat() * 0.9f + 0.1f
    }
}
