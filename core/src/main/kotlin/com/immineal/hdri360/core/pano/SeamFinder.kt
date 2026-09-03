package com.immineal.hdri360.core.pano

/**
 * Decides which frame supplies each part of the panorama.
 *
 * Averaging every frame that can see a direction is right only when they agree.
 * When they do not - someone walked through the shot, or a near object shifted
 * because a handheld pivot is never exactly the entrance pupil - averaging turns
 * the disagreement into a ghost or a smear, spread over the whole overlap.
 *
 * The fix is to stop averaging across disagreement and instead pick one frame
 * per region, putting the boundaries where the frames happen to agree. That is a
 * labelling problem: minimise
 *
 *     sum_p  D(p, label(p))  +  sum_{p~q}  V(label(p), label(q), p, q)
 *
 * where D prefers frames that see a direction well, and V is what a seam between
 * two adjacent output pixels would cost - the disagreement between the two
 * candidate frames, measured at both pixels. Regions where frames disagree
 * become expensive to cut through, so the boundary routes around them and the
 * whole moving object comes from one frame. The same machinery handles parallax:
 * a near object that shifted between frames is disagreement like any other.
 *
 * ## Why log radiance
 *
 * The disagreement has to be measured in log radiance, not linear. Linear
 * radiance spans six orders of magnitude, so an absolute difference is dominated
 * entirely by the brightest pixels: every seam would be driven by the sky and
 * none by the shadowed interior where the ghosting actually shows. In log space
 * a given ratio of disagreement costs the same wherever it happens.
 *
 * ## Method
 *
 * Alpha-expansion: repeatedly pick a label and solve a binary minimum cut for
 * "keep what you have" against "switch to this one", until a whole sweep
 * produces no improvement. Each binary problem is built in the standard
 * Kolmogorov-Zabih form and solved exactly by [MaxFlow].
 *
 * Two practical points:
 *  - Each expansion is restricted to the pixels the label can actually reach.
 *    A frame covers a fraction of the sphere, so this is the difference between
 *    a graph per label and a graph per label per sphere.
 *  - The pairwise term is not guaranteed to satisfy the triangle inequality, and
 *    a binary subproblem that violates submodularity cannot be cut exactly. The
 *    offending term is clamped, which is the usual remedy and only ever makes a
 *    seam look cheaper than it is - never more expensive, so it cannot invent a
 *    boundary that the data does not support.
 */
object SeamFinder {

    /** Uncovered output pixels carry this label. */
    const val NO_LABEL = -1

    class Config {
        /** Weight on the seam term relative to the data term. */
        @JvmField var smoothness = 8.0
        /** Stop after this many sweeps even if the energy is still falling. */
        @JvmField var maxSweeps = 4
        /** Equirectangular canvases join at the left and right edges. */
        @JvmField var wrapHorizontally = true
        /**
         * Floor on the seam cost, so that a boundary through perfectly agreeing
         * pixels is still not free. Without it the cut is free to wander, which
         * costs nothing in energy but makes the label map noisy and its edges
         * impossible to feather cleanly.
         */
        @JvmField var minSeamCost = 0.02
    }

    /**
     * What each output pixel could be, and how well.
     *
     * Deliberately flat primitive arrays: at seam resolution this is a few
     * hundred thousand pixels times a handful of candidates, and a per-pixel
     * object list would cost more than the solve.
     */
    class Problem(
        @JvmField val width: Int,
        @JvmField val height: Int,
        @JvmField val labelCount: Int,
        @JvmField val maxCandidates: Int
    ) {
        @JvmField val count = IntArray(width * height)
        @JvmField val label = IntArray(width * height * maxCandidates)
        /** Log radiance the candidate frame reports for this direction. */
        @JvmField val logValue = FloatArray(width * height * maxCandidates)
        /** How well the frame sees it: blend weight, confidence, distance from its edge. */
        @JvmField val weight = FloatArray(width * height * maxCandidates)

        fun add(pixel: Int, frame: Int, logRadiance: Float, w: Float): Boolean {
            val c = count[pixel]
            if (c >= maxCandidates) return false
            val i = pixel * maxCandidates + c
            label[i] = frame
            logValue[i] = logRadiance
            weight[i] = w
            count[pixel] = c + 1
            return true
        }

        /** Index of [frame] among this pixel's candidates, or -1. */
        fun slotOf(pixel: Int, frame: Int): Int {
            val base = pixel * maxCandidates
            for (c in 0 until count[pixel]) if (label[base + c] == frame) return base + c
            return -1
        }
    }

    class Result internal constructor(
        /** Chosen frame per output pixel, or [NO_LABEL] where nothing saw it. */
        @JvmField val labels: IntArray,
        @JvmField val energy: Double,
        @JvmField val initialEnergy: Double,
        @JvmField val sweeps: Int
    )

    @JvmStatic
    fun solve(p: Problem, cfg: Config): Result {
        val pixels = p.width * p.height
        val labels = IntArray(pixels)

        // Start from the best-weighted candidate: the answer averaging would have
        // leaned toward, and a sane place for the expansion to improve on.
        for (i in 0 until pixels) {
            if (p.count[i] == 0) { labels[i] = NO_LABEL; continue }
            var best = 0
            var bestW = -1.0f
            val base = i * p.maxCandidates
            for (c in 0 until p.count[i]) {
                if (p.weight[base + c] > bestW) { bestW = p.weight[base + c]; best = c }
            }
            labels[i] = p.label[base + best]
        }

        val initial = energyOf(p, cfg, labels)
        var current = initial
        var sweeps = 0

        // Only labels that actually appear as a candidate are worth expanding.
        val present = BooleanArray(p.labelCount)
        for (i in 0 until pixels) {
            val base = i * p.maxCandidates
            for (c in 0 until p.count[i]) present[p.label[base + c]] = true
        }

        for (sweep in 0 until cfg.maxSweeps) {
            var improvedThisSweep = false
            for (alpha in 0 until p.labelCount) {
                if (!present[alpha]) continue
                if (expand(p, cfg, labels, alpha)) improvedThisSweep = true
            }
            sweeps++
            if (!improvedThisSweep) break
        }
        current = energyOf(p, cfg, labels)

        // The pairwise cost is not a metric - it depends on the pixel, not only on
        // the pair of labels - so alpha-expansion carries no optimality guarantee
        // here, and the submodularity clamp can leave a move unavailable. A
        // single-pixel polish costs almost nothing and recovers the cases where an
        // expansion could not express the improvement.
        current = polish(p, cfg, labels, current)
        return Result(labels, current, initial, sweeps)
    }

    /**
     * Iterated conditional modes: repeatedly give each pixel the best label its
     * neighbours allow. Monotone by construction, since a change is only kept when
     * it lowers that pixel's own contribution.
     */
    private fun polish(p: Problem, cfg: Config, labels: IntArray, startEnergy: Double): Double {
        val pixels = p.width * p.height
        var energy = startEnergy
        for (pass in 0 until 6) {
            var changed = false
            for (i in 0 until pixels) {
                val c = p.count[i]
                if (c <= 1) continue
                val base = i * p.maxCandidates
                val currentLabel = labels[i]
                var bestLabel = currentLabel
                var bestLocal = localCost(p, cfg, labels, i, currentLabel)
                for (k in 0 until c) {
                    val cand = p.label[base + k]
                    if (cand == currentLabel) continue
                    val cost = localCost(p, cfg, labels, i, cand)
                    if (cost < bestLocal - 1e-12) { bestLocal = cost; bestLabel = cand }
                }
                if (bestLabel != currentLabel) { labels[i] = bestLabel; changed = true }
            }
            if (!changed) break
            energy = energyOf(p, cfg, labels)
        }
        return energy
    }

    /** Everything in the energy that depends on pixel [i]'s own label. */
    private fun localCost(p: Problem, cfg: Config, labels: IntArray, i: Int, l: Int): Double {
        var e = dataCost(p, i, l)
        val x = i % p.width
        val y = i / p.width
        if (x > 0) e += neighbourCost(p, cfg, labels, i, i - 1, l)
        else if (cfg.wrapHorizontally) e += neighbourCost(p, cfg, labels, i, y * p.width + p.width - 1, l)
        if (x + 1 < p.width) e += neighbourCost(p, cfg, labels, i, i + 1, l)
        else if (cfg.wrapHorizontally) e += neighbourCost(p, cfg, labels, i, y * p.width, l)
        if (y > 0) e += neighbourCost(p, cfg, labels, i, i - p.width, l)
        if (y + 1 < p.height) e += neighbourCost(p, cfg, labels, i, i + p.width, l)
        return e
    }

    private fun neighbourCost(p: Problem, cfg: Config, labels: IntArray,
                              i: Int, j: Int, l: Int): Double {
        if (p.count[j] == 0) return 0.0
        return seamCost(p, cfg, i, j, l, labels[j])
    }

    /**
     * One alpha-expansion move. Returns true if it lowered the energy.
     *
     * Everything here is scoped to the pixels the label can actually reach. A
     * frame sees a few percent of the sphere, so evaluating the whole canvas -
     * to build the graph, and again twice to compare energies - would spend
     * almost all of its time on pixels the move cannot touch. Only the data terms
     * of movable pixels and the edges incident to at least one of them can
     * change, so only those are summed.
     */
    private fun expand(p: Problem, cfg: Config, labels: IntArray, alpha: Int): Boolean {
        val pixels = p.width * p.height

        // Restrict to pixels the label can reach; everything else cannot change.
        val node = IntArray(pixels) { -1 }
        var nodes = 0
        val movable = IntArray(pixels)
        for (i in 0 until pixels) {
            if (p.count[i] == 0) continue
            if (labels[i] == alpha) continue          // already there, nothing to decide
            if (p.slotOf(i, alpha) < 0) continue      // alpha cannot see this direction
            movable[nodes] = i
            node[i] = nodes++
        }
        if (nodes == 0) return false

        // Edges that can change: those with at least one movable endpoint. Each is
        // collected once, from whichever endpoint reaches it first.
        var edgeA = IntArray(nodes * 2)
        var edgeB = IntArray(nodes * 2)
        var edges = 0
        for (k in 0 until nodes) {
            val i = movable[k]
            val x = i % p.width
            val y = i / p.width
            for (side in 0 until 4) {
                val j = when (side) {
                    0 -> if (x + 1 < p.width) i + 1 else if (cfg.wrapHorizontally) y * p.width else -1
                    1 -> if (x > 0) i - 1 else if (cfg.wrapHorizontally) y * p.width + p.width - 1 else -1
                    2 -> if (y + 1 < p.height) i + p.width else -1
                    else -> if (y > 0) i - p.width else -1
                }
                if (j < 0 || p.count[j] == 0) continue
                // Both movable: take it from the lower node index only.
                if (node[j] >= 0 && node[j] < node[i]) continue
                if (edges == edgeA.size) {
                    edgeA = edgeA.copyOf(edges * 2)
                    edgeB = edgeB.copyOf(edges * 2)
                }
                edgeA[edges] = i
                edgeB[edges] = j
                edges++
            }
        }

        val flow = MaxFlow(nodes, edges + nodes)
        for (k in 0 until nodes) {
            val i = movable[k]
            addUnary(flow, node[i], dataCost(p, i, labels[i]), dataCost(p, i, alpha))
        }
        for (e in 0 until edges) pair(p, cfg, labels, alpha, flow, node, edgeA[e], edgeB[e])

        val before = scopedEnergy(p, cfg, labels, movable, nodes, edgeA, edgeB, edges)
        flow.solve()

        val proposed = labels.copyOf()
        for (k in 0 until nodes) {
            val i = movable[k]
            if (!flow.isSource(node[i])) proposed[i] = alpha
        }
        val after = scopedEnergy(p, cfg, proposed, movable, nodes, edgeA, edgeB, edges)
        if (after < before - 1e-12) {
            System.arraycopy(proposed, 0, labels, 0, pixels)
            return true
        }
        return false
    }

    /** The part of the total energy this move can change. */
    private fun scopedEnergy(p: Problem, cfg: Config, labels: IntArray,
                             movable: IntArray, nodes: Int,
                             edgeA: IntArray, edgeB: IntArray, edges: Int): Double {
        var e = 0.0
        for (k in 0 until nodes) e += dataCost(p, movable[k], labels[movable[k]])
        for (i in 0 until edges)
            e += seamCost(p, cfg, edgeA[i], edgeB[i], labels[edgeA[i]], labels[edgeB[i]])
        return e
    }

    /**
     * Adds a unary term and returns the constant part.
     *
     * Convention: a node left on the source side keeps its label, a node on the
     * sink side takes alpha. The cut pays `fromSource` when the node lands on the
     * sink side and `toSink` when it lands on the source side, which is exactly
     * the other way round from the costs, hence the crossing here.
     */
    private fun addUnary(flow: MaxFlow, v: Int, costKeep: Double, costTake: Double): Double {
        val base = Math.min(costKeep, costTake)
        flow.addTerminal(v, costTake - base, costKeep - base)
        return base
    }

    /**
     * Adds the pairwise term for one neighbouring pair, in Kolmogorov-Zabih form:
     *
     *     E = A + (C-A) x_p + (D-C) x_q + (B+C-A-D) (1-x_p) x_q
     *
     * with A..D the four label combinations. The last coefficient must be
     * non-negative for the problem to be submodular; where the pairwise cost
     * violates the triangle inequality it is clamped, which can only understate a
     * seam's cost.
     */
    private fun pair(p: Problem, cfg: Config, labels: IntArray, alpha: Int,
                     flow: MaxFlow, node: IntArray, i: Int, j: Int): Double {
        if (p.count[j] == 0) return 0.0
        val vi = node[i]
        val vj = node[j]
        val li = labels[i]
        val lj = labels[j]

        if (vi < 0 && vj < 0) return 0.0                // neither can change

        val a = seamCost(p, cfg, i, j, li, lj)          // both keep
        val b = seamCost(p, cfg, i, j, li, alpha)       // j takes alpha
        val c = seamCost(p, cfg, i, j, alpha, lj)       // i takes alpha
        val d = 0.0                                     // both alpha: no seam

        if (vi < 0) {
            // i is fixed at li; the term collapses to a unary on j.
            return addUnary(flow, vj, a, b)
        }
        if (vj < 0) {
            // j is fixed at lj.
            return addUnary(flow, vi, a, c)
        }

        var lambda = b + c - a - d
        var aa = a
        if (lambda < 0) { aa = b + c - d; lambda = 0.0 }  // clamp to submodular

        var constant = aa
        constant += addUnary(flow, vi, 0.0, c - aa)
        constant += addUnary(flow, vj, 0.0, d - c)
        if (lambda > 0) flow.addEdge(vi, vj, lambda, 0.0)
        return constant
    }

    /** How reluctant we are to source pixel [i] from [frame]. */
    private fun dataCost(p: Problem, i: Int, frame: Int): Double {
        val slot = p.slotOf(i, frame)
        if (slot < 0) return UNAVAILABLE
        val base = i * p.maxCandidates
        var bestW = 0.0f
        for (c in 0 until p.count[i]) bestW = Math.max(bestW, p.weight[base + c])
        if (bestW <= 0) return 0.0
        return 1.0 - p.weight[slot] / bestW
    }

    /**
     * Cost of a seam between adjacent pixels [i] and [j] when they come from
     * different frames: how far apart the two frames are at both pixels.
     */
    private fun seamCost(p: Problem, cfg: Config, i: Int, j: Int, la: Int, lb: Int): Double {
        if (la == lb) return 0.0
        val d = disagreement(p, i, la, lb) + disagreement(p, j, la, lb)
        return cfg.smoothness * (d + cfg.minSeamCost)
    }

    /** |log radiance difference| between two frames at one pixel, if both see it. */
    private fun disagreement(p: Problem, pixel: Int, la: Int, lb: Int): Double {
        val sa = p.slotOf(pixel, la)
        val sb = p.slotOf(pixel, lb)
        // Where only one of the two sees the direction there is nothing to compare;
        // charging zero would make the frame boundary a free place to cut, which is
        // exactly where a seam is least defensible. Charge a full unit instead.
        if (sa < 0 || sb < 0) return 1.0
        return Math.abs(p.logValue[sa] - p.logValue[sb]).toDouble()
    }

    /** Total energy of a labelling; the thing every move has to reduce. */
    @JvmStatic
    fun energyOf(p: Problem, cfg: Config, labels: IntArray): Double {
        var e = 0.0
        for (y in 0 until p.height) {
            for (x in 0 until p.width) {
                val i = y * p.width + x
                if (p.count[i] == 0) continue
                e += dataCost(p, i, labels[i])
                if (x + 1 < p.width) {
                    val j = i + 1
                    if (p.count[j] != 0) e += seamCost(p, cfg, i, j, labels[i], labels[j])
                } else if (cfg.wrapHorizontally) {
                    val j = y * p.width
                    if (p.count[j] != 0) e += seamCost(p, cfg, i, j, labels[i], labels[j])
                }
                if (y + 1 < p.height) {
                    val j = i + p.width
                    if (p.count[j] != 0) e += seamCost(p, cfg, i, j, labels[i], labels[j])
                }
            }
        }
        return e
    }

    /** Cost of a label that cannot see a direction: large, but finite and comparable. */
    private const val UNAVAILABLE = 1e6
}
