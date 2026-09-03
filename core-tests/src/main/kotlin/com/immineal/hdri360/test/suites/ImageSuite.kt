package com.immineal.hdri360.test.suites

import com.immineal.hdri360.core.image.BayerImage
import com.immineal.hdri360.core.image.CfaPattern
import com.immineal.hdri360.core.image.Demosaic
import com.immineal.hdri360.core.image.ImageF
import com.immineal.hdri360.core.image.ImageOps
import com.immineal.hdri360.test.TestCase
import com.immineal.hdri360.test.TestKit

/** Float image container, sampling, and Bayer demosaic. */
class ImageSuite : TestCase {
    override fun name(): String = "image"

    override fun run(t: TestKit) {
        val r = t.rng(7)

        // --- container ----------------------------------------------------
        val img = ImageF(5, 4, 3)
        t.eq(5L, img.width.toLong(), "width")
        t.eq(4L, img.height.toLong(), "height")
        t.eq(3L, img.channels.toLong(), "channels")
        t.eq((5 * 4 * 3).toLong(), img.data.size.toLong(), "backing array size")
        img.set(2, 1, 0, 0.5f)
        t.near(0.5, img.get(2, 1, 0).toDouble(), 1e-7, "set/get round trip")
        t.near(0.0, img.get(2, 1, 1).toDouble(), 1e-7, "channels are independent")

        // --- bilinear sampling -------------------------------------------
        // A plane f(x,y) = 3x + 5y + 1 must be reproduced exactly by bilinear interpolation.
        val plane = ImageF(16, 12, 1)
        for (y in 0 until 12)
            for (x in 0 until 16) plane.set(x, y, 0, (3 * x + 5 * y + 1).toFloat())
        for (i in 0 until 200) {
            val x = r.nextDouble() * 15
            val y = r.nextDouble() * 11
            t.near(3 * x + 5 * y + 1, plane.sampleBilinear(x, y, 0).toDouble(), 1e-3,
                "bilinear reproduces a plane")
        }
        t.near((3 * 7 + 5 * 3 + 1).toDouble(), plane.sampleBilinear(7.0, 3.0, 0).toDouble(), 1e-4,
            "bilinear is exact at pixel centres")
        // Out of bounds clamps to the edge rather than exploding.
        t.near(plane.get(0, 0, 0).toDouble(), plane.sampleBilinear(-4.0, -9.0, 0).toDouble(), 1e-4,
            "sampling clamps below the origin")
        t.near(plane.get(15, 11, 0).toDouble(), plane.sampleBilinear(99.0, 99.0, 0).toDouble(), 1e-4,
            "sampling clamps past the far corner")
        t.check(!plane.contains(-0.001, 5.0), "contains() rejects negative coordinates")
        t.check(plane.contains(15.0, 11.0), "contains() accepts the far pixel centre")

        // --- downsample ---------------------------------------------------
        val flat = ImageF(8, 8, 1)
        flat.fill(2.5f)
        val half = ImageOps.downsample2x(flat)
        t.eq(4L, half.width.toLong(), "downsample halves width")
        t.eq(4L, half.height.toLong(), "downsample halves height")
        t.near(2.5, half.get(1, 1, 0).toDouble(), 1e-6,
            "downsampling a constant image is a no-op in value")
        val ramp = ImageF(4, 2, 1)
        val vals = floatArrayOf(0f, 2f, 4f, 6f, 8f, 10f, 12f, 14f)
        for (i in 0 until 8) ramp.data[i] = vals[i]
        val rh = ImageOps.downsample2x(ramp)
        t.near((0 + 2 + 8 + 10) / 4.0, rh.get(0, 0, 0).toDouble(), 1e-6,
            "downsample averages a 2x2 block")

        // --- statistics ---------------------------------------------------
        val stat = ImageF(10, 10, 1)
        for (i in 0 until 100) stat.data[i] = i.toFloat()
        t.near(0.0, ImageOps.percentile(stat, 0, 0.0).toDouble(), 1e-6, "0th percentile is the minimum")
        t.near(99.0, ImageOps.percentile(stat, 0, 1.0).toDouble(), 1e-6, "100th percentile is the maximum")
        t.near(50.0, ImageOps.percentile(stat, 0, 0.5).toDouble(), 1.5, "median of 0..99")

        // --- luminance ------------------------------------------------------
        val rgb = ImageF(1, 1, 3)
        rgb.set(0, 0, 0, 1f); rgb.set(0, 0, 1, 1f); rgb.set(0, 0, 2, 1f)
        t.near(1.0, ImageOps.luminance(rgb).get(0, 0, 0).toDouble(), 1e-5, "white has luminance 1")
        rgb.set(0, 0, 0, 0f); rgb.set(0, 0, 1, 1f); rgb.set(0, 0, 2, 0f)
        t.greaterThan(ImageOps.luminance(rgb).get(0, 0, 0).toDouble(), 0.6, "green dominates luminance")

        // --- gaussian blur ---------------------------------------------------
        val blurInput = ImageF(32, 32, 1)
        blurInput.fill(3f)
        val blurred = ImageOps.gaussianBlur(blurInput, 2.0)
        t.near(3.0, blurred.get(16, 16, 0).toDouble(), 1e-4,
            "blur preserves a constant (normalized kernel)")
        t.near(3.0, blurred.get(0, 0, 0).toDouble(), 1e-4,
            "blur preserves a constant at the border too")
        val impulse = ImageF(31, 31, 1)
        impulse.set(15, 15, 0, 1f)
        val spread = ImageOps.gaussianBlur(impulse, 3.0)
        var sum = 0.0
        for (v in spread.data) sum += v
        t.near(1.0, sum, 1e-3, "blur conserves total energy")
        t.near(spread.get(12, 15, 0).toDouble(), spread.get(18, 15, 0).toDouble(), 1e-6,
            "blur is symmetric")

        // --- Bayer demosaic ---------------------------------------------------
        // A constant scene must demosaic to that constant everywhere, on every CFA phase.
        for (pat in CfaPattern.values()) {
            val bay = BayerImage(24, 24, pat)
            bay.plane.fill(0.4f)
            val dem = Demosaic.malvarHeCutler(bay)
            t.eq(3L, dem.channels.toLong(), "demosaic emits RGB")
            var maxErr = 0.0
            for (y in 2 until 22)
                for (x in 2 until 22)
                    for (c in 0 until 3) maxErr = Math.max(maxErr, Math.abs(dem.get(x, y, c) - 0.4))
            t.lessThan(maxErr, 1e-5, "constant scene demosaics flat for $pat")
        }
        // A grey linear ramp: all three channels should track the ramp closely.
        val ramp2 = BayerImage(32, 32, CfaPattern.RGGB)
        for (y in 0 until 32)
            for (x in 0 until 32) ramp2.plane.set(x, y, 0, (0.01 * x + 0.005 * y).toFloat())
        val dm = Demosaic.malvarHeCutler(ramp2)
        var worst = 0.0
        for (y in 4 until 28)
            for (x in 4 until 28) {
                val want = 0.01 * x + 0.005 * y
                for (c in 0 until 3) worst = Math.max(worst, Math.abs(dm.get(x, y, c) - want))
            }
        t.lessThan(worst, 2e-3, "demosaic reproduces a linear grey ramp")
        // Green is measured directly at green sites: it must be passed through untouched.
        val noisy = BayerImage(16, 16, CfaPattern.RGGB)
        for (i in noisy.plane.data.indices) noisy.plane.data[i] = r.nextDouble().toFloat()
        val dn = Demosaic.malvarHeCutler(noisy)
        t.near(noisy.plane.get(1, 0, 0).toDouble(), dn.get(1, 0, 1).toDouble(), 1e-6,
            "green site keeps its measured green")
        t.near(noisy.plane.get(0, 0, 0).toDouble(), dn.get(0, 0, 0).toDouble(), 1e-6,
            "red site keeps its measured red")
        t.near(noisy.plane.get(1, 1, 0).toDouble(), dn.get(1, 1, 2).toDouble(), 1e-6,
            "blue site keeps its measured blue")

        // --- CFA colour lookup -------------------------------------------------
        t.eq(0L, CfaPattern.RGGB.colorAt(0, 0).toLong(), "RGGB (0,0) is red")
        t.eq(1L, CfaPattern.RGGB.colorAt(1, 0).toLong(), "RGGB (1,0) is green")
        t.eq(1L, CfaPattern.RGGB.colorAt(0, 1).toLong(), "RGGB (0,1) is green")
        t.eq(2L, CfaPattern.RGGB.colorAt(1, 1).toLong(), "RGGB (1,1) is blue")
        t.eq(2L, CfaPattern.BGGR.colorAt(0, 0).toLong(), "BGGR (0,0) is blue")
        t.eq(1L, CfaPattern.GRBG.colorAt(0, 0).toLong(), "GRBG (0,0) is green")
        t.eq(0L, CfaPattern.GRBG.colorAt(1, 0).toLong(), "GRBG (1,0) is red")
    }
}
