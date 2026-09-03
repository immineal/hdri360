package com.immineal.hdri360.tools

import com.immineal.hdri360.core.pano.MaxFlow
import com.immineal.hdri360.core.pano.SeamFinder
import java.util.Random

/** Where does the seam solve actually spend its time? */
object SeamBench {
    @JvmStatic
    fun main(args: Array<String>) {
        val w = if (args.isNotEmpty()) args[0].toInt() else 256
        val h = w / 2
        val frames = if (args.size > 1) args[1].toInt() else 57
        val r = Random(1)

        // A plausible sphere: every direction seen by a few frames whose footprints
        // are contiguous caps, with smooth log radiance and a little disagreement.
        val maxCand = 8
        val p = SeamFinder.Problem(w, h, frames, maxCand)
        val cx = DoubleArray(frames) { r.nextDouble() * w }
        val cy = DoubleArray(frames) { r.nextDouble() * h }
        val radius = w * 0.16
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            for (f in 0 until frames) {
                var dx = Math.abs(x - cx[f]); if (dx > w / 2) dx = w - dx
                val dy = Math.abs(y - cy[f])
                val d = Math.hypot(dx, dy)
                if (d > radius) continue
                val lum = 0.5 * Math.sin(x * 0.05) + 0.3 * Math.cos(y * 0.07) +
                          (if (f % 5 == 0) 0.4 else 0.0)
                p.add(i, f, lum.toFloat(), ((1.0 - d / radius) + 0.05).toFloat())
            }
        }
        var covered = 0
        var cands = 0
        for (i in 0 until w * h) { if (p.count[i] > 0) covered++; cands += p.count[i] }
        println("grid ${w}x$h, $frames frames, covered $covered/${w * h}, " +
                "mean candidates ${"%.2f".format(cands / Math.max(1, covered).toDouble())}")

        val cfg = SeamFinder.Config()
        var t0 = System.nanoTime()
        val res = SeamFinder.solve(p, cfg)
        println("solve            %.2f s  (energy %.1f -> %.1f, %d sweeps)"
            .format((System.nanoTime() - t0) / 1e9, res.initialEnergy, res.energy, res.sweeps))

        // Max-flow on its own, at increasing size, to see how it scales.
        for (integral in booleanArrayOf(true, false)) {
            for (n in intArrayOf(200, 500, 800, 1000)) {
                val rr = Random(7)
                fun cap(): Double =
                    if (integral) (1 + rr.nextInt(9)).toDouble() else rr.nextDouble()
                t0 = System.nanoTime()
                val f = MaxFlow(n, n * 3)
                for (v in 0 until n) f.addTerminal(v, cap(), cap())
                for (v in 0 until n - 1) f.addEdge(v, v + 1, cap(), cap())
                for (v in 0 until n - 40) f.addEdge(v, v + 40, cap(), cap())
                val flow = f.solve()
                println("%s n=%5d  %.3f s  flow %.3f".format(
                    if (integral) "integer" else "real   ", n,
                    (System.nanoTime() - t0) / 1e9, flow))
            }
        }
    }
}
