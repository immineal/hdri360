package com.immineal.hdri360.test.suites

import com.immineal.hdri360.core.pano.MaxFlow
import com.immineal.hdri360.test.TestCase
import com.immineal.hdri360.test.TestKit
import java.util.Random

/**
 * Maximum flow, which is the engine the seam finder runs on.
 *
 * Verified against brute force: for a graph small enough, every one of the 2^n
 * source/sink partitions is enumerated and the cheapest cut found directly. Max
 * flow must equal that minimum exactly - not approximately - and the partition
 * the solver reports must be one that achieves it. A flow algorithm that is
 * subtly wrong still returns plausible numbers, so nothing less than an
 * independent minimum is worth testing against.
 */
class MaxFlowSuite : TestCase {
    override fun name(): String = "max-flow"

    /** One graph, held in a form both the solver and the brute force can read. */
    private class Graph(val n: Int) {
        val source = DoubleArray(n)
        val sink = DoubleArray(n)
        val ea = ArrayList<Int>()
        val eb = ArrayList<Int>()
        val cab = ArrayList<Double>()
        val cba = ArrayList<Double>()

        fun edge(a: Int, b: Int, capAB: Double, capBA: Double) {
            ea.add(a); eb.add(b); cab.add(capAB); cba.add(capBA)
        }

        fun build(): MaxFlow {
            val f = MaxFlow(n, ea.size)
            for (v in 0 until n) f.addTerminal(v, source[v], sink[v])
            for (i in ea.indices) f.addEdge(ea[i], eb[i], cab[i], cba[i])
            return f
        }

        /** Cost of cutting with [inSource] as the source side. */
        fun cutValue(inSource: BooleanArray): Double {
            var c = 0.0
            for (v in 0 until n) {
                if (!inSource[v]) c += source[v]
                if (inSource[v]) c += sink[v]
            }
            for (i in ea.indices) {
                if (inSource[ea[i]] && !inSource[eb[i]]) c += cab[i]
                if (inSource[eb[i]] && !inSource[ea[i]]) c += cba[i]
            }
            return c
        }

        /** The true minimum, by exhaustive enumeration. */
        fun bruteForceMinCut(): Double {
            var best = Double.MAX_VALUE
            val mask = BooleanArray(n)
            for (bits in 0 until (1 shl n)) {
                for (v in 0 until n) mask[v] = (bits shr v) and 1 == 1
                best = Math.min(best, cutValue(mask))
            }
            return best
        }
    }

    override fun run(t: TestKit) {
        // --- a graph anyone can check by hand --------------------------------
        // source -> 0 (3), 0 -> 1 (2), 1 -> sink (5). The middle link is the
        // bottleneck, so the flow is 2 and the cut separates 0 from 1.
        run {
            val g = Graph(2)
            g.source[0] = 3.0
            g.edge(0, 1, 2.0, 0.0)
            g.sink[1] = 5.0
            val f = g.build()
            t.near(2.0, f.solve(), 1e-12, "a chain is limited by its narrowest link")
            t.check(f.isSource(0), "the node fed by the source stays on the source side")
            t.check(!f.isSource(1), "the node feeding the sink stays on the sink side")
        }

        // Two parallel routes add up.
        run {
            val g = Graph(2)
            g.source[0] = 10.0; g.source[1] = 10.0
            g.sink[0] = 3.0; g.sink[1] = 4.0
            val f = g.build()
            t.near(7.0, f.solve(), 1e-12, "independent paths sum")
        }

        // A node wired to both terminals: the smaller capacity is always cut.
        run {
            val g = Graph(1)
            g.source[0] = 6.0
            g.sink[0] = 2.5
            val f = g.build()
            t.near(2.5, f.solve(), 1e-12, "a node on both terminals cuts at the smaller one")
            t.check(f.isSource(0), "and stays with the stronger terminal")
        }

        // --- against brute force, on random graphs ----------------------------
        val r = t.rng(918273)
        var worst = 0.0
        var graphs = 0
        for (trial in 0 until 400) {
            val n = 2 + r.nextInt(7)                 // up to 8 nodes: 256 partitions
            val g = Graph(n)
            for (v in 0 until n) {
                if (r.nextDouble() < 0.45) g.source[v] = Math.round(r.nextDouble() * 9).toDouble()
                if (r.nextDouble() < 0.45) g.sink[v] = Math.round(r.nextDouble() * 9).toDouble()
            }
            for (a in 0 until n)
                for (b in a + 1 until n)
                    if (r.nextDouble() < 0.55)
                        g.edge(a, b, Math.round(r.nextDouble() * 6).toDouble(),
                            Math.round(r.nextDouble() * 6).toDouble())

            val f = g.build()
            val flow = f.solve()
            val truth = g.bruteForceMinCut()
            t.near(truth, flow, 1e-9, "max flow equals the brute-force minimum cut")

            // The partition the solver reports must actually achieve that value.
            val mask = BooleanArray(n) { f.isSource(it) }
            t.near(truth, g.cutValue(mask), 1e-9,
                "the reported partition realises the minimum, not merely its value")
            worst = Math.max(worst, Math.abs(flow - truth))
            graphs++
        }
        t.greaterThan(graphs.toDouble(), 300.0, "a meaningful number of graphs were checked")
        t.note("checked " + graphs + " random graphs against exhaustive enumeration, worst " +
                "disagreement " + TestKit.fmt(worst))

        // --- real-valued capacities, not just integers --------------------------
        // Integer capacities can hide bugs that only bite when the bottleneck is
        // reached by an accumulation of fractions.
        var worstReal = 0.0
        for (trial in 0 until 200) {
            val n = 2 + r.nextInt(6)
            val g = Graph(n)
            for (v in 0 until n) {
                if (r.nextDouble() < 0.5) g.source[v] = r.nextDouble() * 3
                if (r.nextDouble() < 0.5) g.sink[v] = r.nextDouble() * 3
            }
            for (a in 0 until n)
                for (b in a + 1 until n)
                    if (r.nextDouble() < 0.6) g.edge(a, b, r.nextDouble() * 2, r.nextDouble() * 2)
            val f = g.build()
            worstReal = Math.max(worstReal, Math.abs(f.solve() - g.bruteForceMinCut()))
        }
        t.lessThan(worstReal, 1e-9, "real-valued capacities are handled exactly")
        t.note("worst disagreement with continuous capacities: " + TestKit.fmt(worstReal))

        // --- a grid, which is the shape the seam finder actually builds ----------
        // A 12x12 lattice with a cheap vertical channel: the cut must go down the
        // channel, and its value must be the channel's total cost.
        run {
            val w = 12
            val h = 12
            val g = Graph(w * h)
            val strong = 100.0
            val weak = 0.5
            val channel = 7
            for (y in 0 until h) {
                g.source[y * w] = strong                 // left column tied to one label
                g.sink[y * w + (w - 1)] = strong         // right column to the other
                for (x in 0 until w - 1) {
                    val cost = if (x == channel) weak else strong
                    g.edge(y * w + x, y * w + x + 1, cost, cost)
                }
            }
            for (y in 0 until h - 1)
                for (x in 0 until w)
                    g.edge(y * w + x, (y + 1) * w + x, strong, strong)

            val f = g.build()
            val flow = f.solve()
            t.near(h * weak, flow, 1e-9, "the cut runs down the cheap channel")
            for (y in 0 until h) {
                t.check(f.isSource(y * w + channel), "everything left of the channel is source side")
                t.check(!f.isSource(y * w + channel + 1), "and everything right of it is sink side")
            }
            t.note("12x12 lattice with a cheap channel cuts at " + TestKit.fmt(flow) +
                    ", the channel's own cost")
        }

        // --- graphs big enough to exercise the tree machinery ---------------------
        // The small random graphs above never orphan enough nodes to reach the
        // adoption path in anger. This one does, and an earlier version of the
        // solver hung on it outright: a stale cached origin distance let a node
        // adopt a parent whose chain ran back through it, and the resulting cycle
        // in the search tree made the augment walk loop forever. Size, not
        // subtlety, is what catches that.
        run {
            var worstNodes = 0
            val t0 = System.nanoTime()
            for (n in intArrayOf(400, 1000, 2500)) {
                for (integral in booleanArrayOf(true, false)) {
                    val rr = t.rng(4242L + n)
                    val f = MaxFlow(n, n * 3)
                    fun cap() = if (integral) (1 + rr.nextInt(9)).toDouble() else rr.nextDouble()
                    for (v in 0 until n) f.addTerminal(v, cap(), cap())
                    for (v in 0 until n - 1) f.addEdge(v, v + 1, cap(), cap())
                    for (v in 0 until n - 40) f.addEdge(v, v + 40, cap(), cap())
                    val flow = f.solve()
                    t.greaterThan(flow, 0.0, "a well-connected graph carries flow")
                    t.check(flow.isFinite(), "and a finite amount of it")
                    // The cut it reports must cost exactly what the flow says. This is
                    // the max-flow min-cut theorem used as an assertion, and it is what
                    // makes the check meaningful without a brute force to compare to.
                    var cut = 0.0
                    val src = BooleanArray(n) { f.isSource(it) }
                    val rr2 = t.rng(4242L + n)
                    fun cap2() = if (integral) (1 + rr2.nextInt(9)).toDouble() else rr2.nextDouble()
                    val ts = Array(n) { doubleArrayOf(cap2(), cap2()) }
                    for (v in 0 until n) {
                        if (!src[v]) cut += ts[v][0]
                        if (src[v]) cut += ts[v][1]
                    }
                    for (v in 0 until n - 1) {
                        val ab = cap2(); val ba = cap2()
                        if (src[v] && !src[v + 1]) cut += ab
                        if (src[v + 1] && !src[v]) cut += ba
                    }
                    for (v in 0 until n - 40) {
                        val ab = cap2(); val ba = cap2()
                        if (src[v] && !src[v + 40]) cut += ab
                        if (src[v + 40] && !src[v]) cut += ba
                    }
                    t.nearRel(flow, cut, 1e-9,
                        "the reported cut costs exactly the flow that was pushed")
                    worstNodes = Math.max(worstNodes, n)
                }
            }
            t.note("largest graph solved: " + worstNodes + " nodes, all sizes in " +
                    TestKit.fmt((System.nanoTime() - t0) / 1e9) + " s")
        }

        // --- degenerate inputs ---------------------------------------------------
        run {
            val f = MaxFlow(4, 0)
            t.near(0.0, f.solve(), 1e-12, "a graph with no capacity carries no flow")
            for (v in 0 until 4) t.check(!f.isSource(v), "and everything falls to the sink side")
        }
        run {
            val g = Graph(3)
            g.source[0] = 5.0
            g.sink[2] = 5.0        // node 1 is isolated, 0 and 2 are not connected
            val f = g.build()
            t.near(0.0, f.solve(), 1e-12, "disconnected terminals carry no flow")
        }
        run {
            val f = MaxFlow(2, 1)
            t.throwsException({ f.addEdge(0, 0, 1.0, 1.0) }, "a self loop is refused")
            t.throwsException({ f.addEdge(0, 1, -1.0, 1.0) }, "a negative capacity is refused")
            t.throwsException({ f.addTerminal(0, -1.0, 0.0) }, "a negative terminal is refused")
        }
        run {
            val f = MaxFlow(2, 1)
            t.throwsException({ f.isSource(0) }, "the cut cannot be read before it is computed")
        }

        // --- solving twice is idempotent -------------------------------------------
        run {
            val g = Graph(4)
            g.source[0] = 4.0; g.sink[3] = 4.0
            g.edge(0, 1, 3.0, 0.0); g.edge(1, 2, 2.0, 0.0); g.edge(2, 3, 5.0, 0.0)
            val f = g.build()
            val first = f.solve()
            t.near(first, f.solve(), 0.0, "solving again returns the same flow, exactly")
            t.near(2.0, first, 1e-12, "and it is the bottleneck of the chain")
        }
    }
}
