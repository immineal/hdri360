package com.immineal.hdri360.test.suites

import com.immineal.hdri360.core.hdr.ToneMapper
import com.immineal.hdri360.core.image.ImageF
import com.immineal.hdri360.test.TestCase
import com.immineal.hdri360.test.TestKit

/** The preview path: turning radiance into something a phone screen can show. */
class ToneMapSuite : TestCase {
    override fun name(): String = "tone-map"

    override fun run(t: TestKit) {
        // --- the curve itself ----------------------------------------------
        t.near(0.0, ToneMapper.filmic(0.0), 1e-9, "black stays black")
        var prev = -1.0
        var v = 0.0
        while (v < 1000) {
            val y = ToneMapper.filmic(v)
            t.greaterThan(y, prev - 1e-12, "the curve is monotone")
            t.check(y >= 0 && y <= 1.0 + 1e-9, "the curve stays in range at " + TestKit.fmt(v))
            prev = y
            v = v * 1.1 + 1e-4
        }
        t.greaterThan(ToneMapper.filmic(1e6), 0.97, "very bright radiance approaches white")
        t.lessThan(ToneMapper.filmic(1e-6), 1e-4, "very dark radiance stays near black")

        // --- auto exposure from the image itself -----------------------------
        // Two images differing only by a global scale must tone map to the same picture:
        // that is what makes the preview independent of the arbitrary radiance scale.
        val a = scene(1.0)
        val b = scene(37.0)
        val la = ToneMapper.toDisplay(a, ToneMapper.autoKey(a), 2.2)
        val lb = ToneMapper.toDisplay(b, ToneMapper.autoKey(b), 2.2)
        var worst = 0.0
        for (i in la.data.indices) worst = Math.max(worst, Math.abs(la.data[i] - lb.data[i]).toDouble())
        t.lessThan(worst, 0.02, "auto exposure removes the global radiance scale")
        t.note("largest difference between two exposures of the same scene: " + TestKit.fmt(worst))

        for (x in la.data) t.check(x >= 0 && x <= 1.0001f, "display values are in [0,1]")

        // --- gamma -------------------------------------------------------------
        val mid = ImageF(1, 1, 3)
        mid.fill(1.0f)
        val linearOut = ToneMapper.toDisplay(mid, 1.0, 1.0)
        val gammaOut = ToneMapper.toDisplay(mid, 1.0, 2.2)
        t.greaterThan(gammaOut.data[0].toDouble(), linearOut.data[0].toDouble(),
            "gamma encoding lifts the midtones")

        // --- ordering is preserved ----------------------------------------------
        val ramp = ImageF(64, 1, 1)
        for (i in 0 until 64) ramp.data[i] = Math.pow(10.0, i / 8.0 - 4).toFloat()
        val mapped = ToneMapper.toDisplay(ramp, ToneMapper.autoKey(ramp), 2.2)
        for (i in 1 until 64)
            t.check(mapped.data[i] >= mapped.data[i - 1] - 1e-6f, "tone mapping preserves ordering")

        // --- 8-bit conversion ------------------------------------------------------
        val bytes = ToneMapper.toBytes(mapped)
        t.eq(mapped.data.size.toLong(), bytes.size.toLong(), "one byte per sample")
        t.lessThan((bytes[0].toInt() and 0xFF).toDouble(), 3.0, "the darkest sample maps to near-black")
        t.eq(255L, (bytes[63].toInt() and 0xFF).toLong(), "the brightest sample maps to 255")

        // --- degenerate input ---------------------------------------------------------
        val black = ImageF(4, 4, 3)
        val key = ToneMapper.autoKey(black)
        t.check(key.isFinite() && key > 0, "a completely black image still yields a usable key")
        val blackOut = ToneMapper.toDisplay(black, key, 2.2)
        for (x in blackOut.data) t.near(0.0, x.toDouble(), 1e-6, "black maps to black")
    }

    private fun scene(scale: Double): ImageF {
        val img = ImageF(48, 32, 3)
        for (y in 0 until 32)
            for (x in 0 until 48)
                for (c in 0 until 3) {
                    val v = Math.pow(10.0, (x / 47.0) * 4 - 2) * (0.5 + 0.5 * c / 2.0) *
                            (0.6 + 0.4 * y / 31.0)
                    img.set(x, y, c, (v * scale).toFloat())
                }
        return img
    }
}
