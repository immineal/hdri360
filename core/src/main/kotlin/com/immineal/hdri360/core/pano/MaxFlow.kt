package com.immineal.hdri360.core.pano

/**
 * Boykov-Kolmogorov maximum flow, for the seam problem.
 *
 * Choosing where one frame stops contributing to the panorama and the next
 * begins is a labelling problem, and the standard way to solve it is as a
 * minimum cut: nodes are output pixels, terminal links say how much each pixel
 * would rather belong to one frame or the other, and neighbour links say how
 * expensive it is to put a seam between two adjacent pixels. The minimum cut is
 * then the cheapest possible seam.
 *
 * Boykov-Kolmogorov rather than a textbook augmenting-path method because this
 * is a grid graph with short augmenting paths: BK keeps two search trees alive
 * across augmentations and repairs them instead of rebuilding, which is what
 * makes it practical at image scale.
 *
 * Implementation notes that matter for correctness:
 *  - Edges are stored in pairs, so an edge and its reverse are `e` and `e xor 1`.
 *  - `terminal[v]` holds a single signed residual: positive is capacity from the
 *    source, negative is capacity to the sink. A node cannot usefully have both,
 *    since the smaller cancels and only contributes a constant to the flow.
 *  - Orphans are adopted in the order they are created, and the timestamp and
 *    distance fields are the standard heuristic for finding a new parent quickly
 *    without walking to a terminal every time.
 */
class MaxFlow(nodeCount: Int, edgeHint: Int) {

    private companion object {
        const val FREE: Byte = 0
        const val SOURCE: Byte = 1
        const val SINK: Byte = 2
        const val TERMINAL = -2   // parent value meaning "attached directly to a terminal"
        const val ORPHAN = -3     // parent value meaning "detached, awaiting adoption"
        const val NONE = -1
        /** A timestamp that can never match, marking a cached distance as void. */
        const val STALE = -1
    }

    private val n = nodeCount

    // Adjacency, as paired arcs in a linked list per node.
    private var head = IntArray(nodeCount) { NONE }
    private var nextArc = IntArray(Math.max(2, edgeHint * 2))
    private var target = IntArray(Math.max(2, edgeHint * 2))
    private var residual = DoubleArray(Math.max(2, edgeHint * 2))
    private var arcCount = 0

    private val terminal = DoubleArray(nodeCount)
    private val parent = IntArray(nodeCount) { NONE }
    private val tree = ByteArray(nodeCount)
    private val dist = IntArray(nodeCount)
    private val stamp = IntArray(nodeCount)
    private val inActive = BooleanArray(nodeCount)

    private var active = IntArray(Math.max(16, nodeCount))
    private var activeHead = 0
    private var activeTail = 0

    private var orphans = IntArray(64)
    private var orphanCount = 0

    /** Where each node's edge scan resumes; growing must not restart from scratch. */
    private val scan = IntArray(nodeCount) { NONE }

    private var time = 0
    private var flow = 0.0
    private var solved = false

    /** Capacity from the source into [node], and from [node] into the sink. */
    fun addTerminal(node: Int, fromSource: Double, toSink: Double) {
        if (fromSource < 0 || toSink < 0)
            throw IllegalArgumentException("terminal capacities must be non-negative")
        // Only the difference matters; the common part is flow that must pass
        // through regardless of the cut, so it is added straight to the total.
        flow += Math.min(fromSource, toSink)
        terminal[node] += fromSource - toSink
    }

    /** An undirected neighbour link, with capacity in each direction. */
    fun addEdge(a: Int, b: Int, capAB: Double, capBA: Double) {
        if (a == b) throw IllegalArgumentException("self loops are meaningless here")
        if (capAB < 0 || capBA < 0)
            throw IllegalArgumentException("edge capacities must be non-negative")
        ensureArcs(arcCount + 2)
        target[arcCount] = b; residual[arcCount] = capAB
        nextArc[arcCount] = head[a]; head[a] = arcCount; arcCount++
        target[arcCount] = a; residual[arcCount] = capBA
        nextArc[arcCount] = head[b]; head[b] = arcCount; arcCount++
    }

    private fun ensureArcs(needed: Int) {
        if (needed <= nextArc.size) return
        var size = nextArc.size
        while (size < needed) size *= 2
        nextArc = nextArc.copyOf(size)
        target = target.copyOf(size)
        residual = residual.copyOf(size)
    }

    /** @return the value of the maximum flow, equal to the minimum cut. */
    fun solve(): Double {
        if (solved) return flow
        solved = true
        initTrees()

        // BK keeps working the same node until its edges are exhausted rather than
        // taking a fresh one after every augmentation: a node that yielded one
        // path usually yields several, and dropping it after the first loses them.
        // That is also why the edge scan resumes rather than restarting.
        var current = NONE
        while (true) {
            var p = current
            // The node may have been orphaned by the last augmentation.
            if (p != NONE && (tree[p] == FREE || parent[p] == NONE)) p = NONE
            if (p == NONE) {
                p = nextActive()
                if (p == NONE) break
                scan[p] = head[p]
            }
            val connecting = grow(p)
            if (connecting == NONE) {
                current = NONE
                continue
            }
            time++
            val pushed = augment(connecting)
            if (pushed <= 0.0) throw IllegalStateException(
                "augmentation pushed no flow from node " + p + " via arc " + connecting +
                "; residual " + residual[connecting])
            adoptAll()
            current = p
        }
        return flow
    }

    /**
     * Which side of the cut a node fell on. Source side means the first label.
     * Only meaningful after [solve].
     */
    fun isSource(node: Int): Boolean {
        if (!solved) throw IllegalStateException("solve() first")
        return tree[node] == SOURCE
    }

    private fun initTrees() {
        for (v in 0 until n) {
            if (terminal[v] > 0) {
                tree[v] = SOURCE; parent[v] = TERMINAL
            } else if (terminal[v] < 0) {
                tree[v] = SINK; parent[v] = TERMINAL
            } else {
                tree[v] = FREE; parent[v] = NONE
                continue
            }
            dist[v] = 1
            stamp[v] = 0
            enqueue(v)
        }
    }

    private fun enqueue(v: Int) {
        if (inActive[v]) return
        inActive[v] = true
        if (activeTail == active.size) {
            if (activeHead > 0) {
                System.arraycopy(active, activeHead, active, 0, activeTail - activeHead)
                activeTail -= activeHead
                activeHead = 0
            } else {
                active = active.copyOf(active.size * 2)
            }
        }
        active[activeTail++] = v
        scan[v] = head[v]
    }

    private fun nextActive(): Int {
        while (activeHead < activeTail) {
            val v = active[activeHead++]
            inActive[v] = false
            // A node can be queued and then orphaned before it is reached.
            if (tree[v] != FREE && parent[v] != NONE) return v
        }
        return NONE
    }

    /** Residual capacity of arc [e] in the direction that grows tree [t]. */
    private fun growCapacity(e: Int, t: Byte): Double =
        if (t == SOURCE) residual[e] else residual[e xor 1]

    /**
     * Expands one tree from [p]. Returns the arc joining the two trees, oriented
     * source-side to sink-side, or NONE if no contact was made.
     */
    private fun grow(p: Int): Int {
        val tp = tree[p]
        var e = scan[p]
        while (e != NONE) {
            if (growCapacity(e, tp) > 0) {
                val q = target[e]
                val tq = tree[q]
                if (tq == FREE) {
                    tree[q] = tp
                    parent[q] = e xor 1          // the arc from q back to p
                    stamp[q] = stamp[p]
                    dist[q] = dist[p] + 1
                    enqueue(q)
                } else if (tq != tp) {
                    // Leave the scan on this arc: after the augmentation it may be
                    // saturated, in which case the next pass simply steps over it.
                    scan[p] = e
                    return if (tp == SOURCE) e else (e xor 1)
                }
                // The reference algorithm also re-parents a same-tree neighbour that
                // looks further from its terminal than it needs to be. That is purely
                // a shortening heuristic, and it relies on cached distances being
                // accurate; when they are not it can point an ancestor at its own
                // descendant, which is a cycle. Left out: it buys a little speed and
                // costs correctness, and the adoption path already keeps distances
                // short enough.
            }
            e = nextArc[e]
        }
        scan[p] = NONE
        return NONE
    }

    /** Pushes as much flow as the joining arc's path allows, orphaning what saturates. */
    private fun augment(connecting: Int): Double {
        // The arc runs from a source-tree node to a sink-tree node.
        val sourceEnd = target[connecting xor 1]
        val sinkEnd = target[connecting]

        var bottleneck = residual[connecting]
        // Walk up the source tree.
        var v = sourceEnd
        var guard = 0
        while (parent[v] != TERMINAL) {
            val e = parent[v]
            if (e == ORPHAN || e == NONE) throw IllegalStateException(
                "source walk hit a detached node " + v)
            if (guard++ > n) throw IllegalStateException("cycle in the source tree at " + v)
            bottleneck = Math.min(bottleneck, residual[e xor 1])
            v = target[e]
        }
        bottleneck = Math.min(bottleneck, terminal[v])
        // Walk down the sink tree.
        v = sinkEnd
        guard = 0
        while (parent[v] != TERMINAL) {
            val e = parent[v]
            if (e == ORPHAN || e == NONE) throw IllegalStateException(
                "sink walk hit a detached node " + v)
            if (guard++ > n) throw IllegalStateException("cycle in the sink tree at " + v)
            bottleneck = Math.min(bottleneck, residual[e])
            v = target[e]
        }
        bottleneck = Math.min(bottleneck, -terminal[v])

        // Push it.
        residual[connecting xor 1] += bottleneck
        residual[connecting] -= bottleneck

        v = sourceEnd
        while (parent[v] != TERMINAL) {
            val e = parent[v]
            residual[e] += bottleneck
            residual[e xor 1] -= bottleneck
            if (residual[e xor 1] <= 0) { parent[v] = ORPHAN; pushOrphan(v) }
            v = target[e]
        }
        terminal[v] -= bottleneck
        if (terminal[v] <= 0) { parent[v] = ORPHAN; pushOrphan(v) }

        v = sinkEnd
        while (parent[v] != TERMINAL) {
            val e = parent[v]
            residual[e xor 1] += bottleneck
            residual[e] -= bottleneck
            if (residual[e] <= 0) { parent[v] = ORPHAN; pushOrphan(v) }
            v = target[e]
        }
        terminal[v] += bottleneck
        if (terminal[v] >= 0) { parent[v] = ORPHAN; pushOrphan(v) }

        flow += bottleneck
        return bottleneck
    }

    private fun pushOrphan(v: Int) {
        // Invalidate any cached origin distance. The cache says "this node reached a
        // terminal, as of this augmentation", and detaching it makes that false. A
        // later walk that short-circuited on the stale entry would never reach the
        // ORPHAN marker below it, and could adopt a parent whose chain runs back
        // through the orphan - which is a cycle, and hangs the augment walk.
        stamp[v] = STALE
        if (orphanCount == orphans.size) orphans = orphans.copyOf(orphans.size * 2)
        orphans[orphanCount++] = v
    }

    private fun adoptAll() {
        while (orphanCount > 0) {
            val v = orphans[--orphanCount]
            adopt(v)
        }
    }

    /**
     * Finds [v] a new parent in its own tree, or frees it and orphans its children.
     *
     * A candidate parent must still have residual capacity toward v and must trace
     * back to a terminal - the origin walk is what stops two orphans adopting each
     * other and inventing a path that no longer exists.
     */
    private fun adopt(v: Int) {
        // Every adoption gets its own generation, so no cached origin distance can
        // survive from before an earlier adoption in this same augmentation
        // detached something the cache depended on.
        time++
        val tv = tree[v]
        var bestParent = NONE
        var bestDist = Int.MAX_VALUE

        var e = head[v]
        while (e != NONE) {
            val q = target[e]
            if (tree[q] == tv) {
                // Capacity must flow from q toward v.
                val cap = if (tv == SOURCE) residual[e xor 1] else residual[e]
                if (cap > 0) {
                    val d = originDistance(q, v)
                    if (d < bestDist) { bestDist = d; bestParent = e }
                }
            }
            e = nextArc[e]
        }

        if (bestParent != NONE) {
            parent[v] = bestParent
            dist[v] = bestDist + 1
            stamp[v] = time
            return
        }

        // No parent: v leaves the tree, and anything hanging off it is orphaned too.
        e = head[v]
        while (e != NONE) {
            val q = target[e]
            if (tree[q] == tv) {
                val cap = if (tv == SOURCE) residual[e xor 1] else residual[e]
                if (cap > 0) enqueue(q)
                if (parent[q] != NONE && parent[q] != TERMINAL && parent[q] != ORPHAN &&
                    target[parent[q]] == v) {
                    parent[q] = ORPHAN
                    pushOrphan(q)
                }
            }
            e = nextArc[e]
        }
        tree[v] = FREE
        parent[v] = NONE
        stamp[v] = STALE
        inActive[v] = false
    }

    /**
     * Steps back to a terminal, returning the distance, or MAX_VALUE if the walk
     * runs into an orphan or a free node. Results are memoised with [time] so a
     * whole augmentation's adoptions share the work.
     */
    private fun originDistance(start: Int, forbidden: Int): Int {
        var v = start
        var steps = 0
        while (true) {
            // Never certify a chain that runs back through the node being adopted:
            // that is precisely the cycle this has to avoid.
            if (v == forbidden) return Int.MAX_VALUE
            if (stamp[v] == time) return dist[v] + steps
            val p = parent[v]
            if (p == TERMINAL) {
                stamp[v] = time
                dist[v] = 1
                return 1 + steps
            }
            if (p == ORPHAN || p == NONE) return Int.MAX_VALUE
            steps++
            v = target[p]
            if (steps > n) return Int.MAX_VALUE      // cycle guard; should not happen
        }
    }
}
