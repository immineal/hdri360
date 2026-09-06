package com.immineal.hdri360.test.suites

import com.immineal.hdri360.core.image.BayerImage
import com.immineal.hdri360.core.image.CfaPattern
import com.immineal.hdri360.core.image.Demosaic
import com.immineal.hdri360.core.image.ImageF
import com.immineal.hdri360.core.image.ShadingMap
import com.immineal.hdri360.core.image.ImageOps
import com.immineal.hdri360.core.image.RawPlane
import com.immineal.hdri360.test.TestCase
import com.immineal.hdri360.test.TestKit

/** Float image container, sampling, and Bayer demosaic. */
class ImageSuite : TestCase {
    override fun name(): String = "image"

    override fun run(t: TestKit) {
        theShadingMapStretchesOverTheFrame(t)
        aShadingSamplerIsTheSameAnswerAtAThousandthOfTheCost(t)
        theRawConversionIsCoreArithmeticAndTestedAsSuch(t)
        theShadingGainBelongsOnRadianceNotOnSensorReadings(t)
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

    /**
     * The lens shading correction, interpolated over a frame.
     *
     * A phone lens loses well over a stop at the corners; the camera measures it
     * and reports a coarse grid of gains, which has to be stretched over four
     * thousand pixels from about a dozen nodes. Tested here because it moved into
     * the core so that a capture bundle can be re-processed off the phone at all -
     * without the map, the desktop path stitches frames whose corners are a stop
     * darker than the ones the phone stitched.
     */

    /**
     * The shading correction, precomputed once instead of derived per pixel.
     *
     * [ShadingMap.gainAt] is the readable definition and it is what the answer
     * has to agree with. It is also, per pixel, two floating point divisions, a
     * floor, three nested calls and four array reads - and a phone frame is
     * three million pixels. Measured on a Pixel 9a, in the app's own log:
     *
     *     convert breakdown: buffer reads 109 ms, shading lookups 8042 ms
     *
     * Eight seconds a frame, on the camera thread, which is the thread the next
     * frame of the burst has to arrive on. Four rungs took thirty-two seconds,
     * the burst outlived its twelve second timeout, and no capture on this phone
     * could finish. It went unnoticed until the day the shading map was first
     * recorded at all, because until then this branch never ran.
     *
     * Nothing about the arithmetic needed to be per pixel. The output geometry is
     * fixed for a whole frame, so the grid indices, the interpolation weights and
     * the CFA plane are all known before the loop starts: what is left inside it
     * is four array reads and three multiply-adds, with no division, no floor and
     * no call. This asserts the two agree **exactly**, because a fast path that
     * is merely close would put a faint grid across every frame.
     */
    private fun aShadingSamplerIsTheSameAnswerAtAThousandthOfTheCost(t: TestKit) {
        val r = t.rng(20260906)
        // The 9a's own map: 33 x 25 nodes, gains rising to about 5 at the corners.
        val columns = 33
        val rows = 25
        val gains = DoubleArray(columns * rows * 4)
        for (gy in 0 until rows) {
            for (gx in 0 until columns) {
                val nx = gx / (columns - 1.0) * 2 - 1
                val ny = gy / (rows - 1.0) * 2 - 1
                val radial = 1.0 + 4.0 * (nx * nx + ny * ny) / 2.0
                for (c in 0 until 4)
                    gains[(gy * columns + gx) * 4 + c] = radial * (1.0 + 0.05 * c)
            }
        }
        val map = ShadingMap(gains, columns, rows)
        val pattern = CfaPattern.RGGB

        // The exact geometry the converter uses: whole 2x2 blocks in, whole
        // blocks out, so the CFA phase survives the subsampling.
        val sensorW = 4000
        val sensorH = 3000
        val subsample = 2
        val outW = (sensorW / (2 * subsample)) * 2
        val outH = (sensorH / (2 * subsample)) * 2
        val sensorX = IntArray(outW) { (it / 2) * 2 * subsample + (it and 1) }
        val sensorY = IntArray(outH) { (it / 2) * 2 * subsample + (it and 1) }

        val sampler = map.samplerFor(pattern, sensorX, sensorY, sensorW, sensorH)

        // Exactly, not "close": a fast path that differs in the last bits draws a
        // faint grid over every picture this app takes.
        //
        // Every pixel of a small frame below, since that exercises the same code
        // with the same edges; here, on the real three million pixel geometry,
        // the first and last rows and columns in full plus a wide random sample.
        // Checking all three million would mean three million of the slow calls
        // this exists to avoid, and it put eighty seconds on the suite.
        var worst = 0.0
        fun compare(x: Int, y: Int) {
            val d = Math.abs(map.gainAt(pattern, sensorX[x], sensorY[y], sensorW, sensorH) -
                sampler.gain(x, y))
            if (d > worst) worst = d
        }
        for (x in 0 until outW) {
            compare(x, 0); compare(x, 1); compare(x, outH / 2)
            compare(x, outH - 2); compare(x, outH - 1)
        }
        for (y in 0 until outH) {
            compare(0, y); compare(1, y); compare(outW / 2, y)
            compare(outW - 2, y); compare(outW - 1, y)
        }
        for (i in 0 until 200000) compare(r.nextInt(outW), r.nextInt(outH))
        t.eq(0.0, worst, "the sampler is bit for bit the same answer as gainAt")

        // Applying a row multiplies in place and touches nothing outside it.
        val row = FloatArray(outW + 4) { 2.0f }
        val y = 731
        sampler.applyRow(y, row, 2, outW)
        t.near(2.0, row[0].toDouble(), 0.0, "the pixel before the row is untouched")
        t.near(2.0, row[1].toDouble(), 0.0, "and the one before that")
        t.near(2.0, row[outW + 2].toDouble(), 0.0, "and the one after the row")
        for (x in 0 until outW) {
            val want = (2.0 * map.gainAt(pattern, sensorX[x], sensorY[y], sensorW, sensorH)).toFloat()
            if (row[x + 2] != want) {
                t.fail("applyRow disagrees at x=$x: ${row[x + 2]} against $want")
                break
            }
        }
        t.check(true, "applyRow multiplies each sample by its own gain")

        // The corners really are being corrected, or this whole exercise is
        // about nothing.
        t.greaterThan(sampler.gain(0, 0), 4.0, "the corner gain is the corner loss")
        t.lessThan(sampler.gain(outW / 2, outH / 2), 1.2, "and the centre is barely touched")

        // Every pixel of a whole small frame, which is the exhaustive half of the
        // guarantee: same code, same edges, few enough to check all of.
        val smallW = 200
        val smallH = 150
        val sxs = IntArray(smallW) { (it / 2) * 2 * subsample + (it and 1) }
        val sys = IntArray(smallH) { (it / 2) * 2 * subsample + (it and 1) }
        val smallSampler = map.samplerFor(pattern, sxs, sys, smallW * subsample, smallH * subsample)
        var smallWorst = 0.0
        for (y in 0 until smallH) {
            for (x in 0 until smallW) {
                val d = Math.abs(map.gainAt(pattern, sxs[x], sys[y],
                    smallW * subsample, smallH * subsample) - smallSampler.gain(x, y))
                if (d > smallWorst) smallWorst = d
            }
        }
        t.eq(0.0, smallWorst, "and every single pixel of a whole frame agrees")

        // A frame the size of one grid cell, and one a single pixel wide, are the
        // edges where an index or a weight goes out of range.
        for (small in intArrayOf(1, 2, 3, 4, 7)) {
            val xs = IntArray(small) { it }
            val ys = IntArray(small) { it }
            val s = map.samplerFor(pattern, xs, ys, small, small)
            for (yy in 0 until small) for (xx in 0 until small) {
                val slow = map.gainAt(pattern, xs[xx], ys[yy], small, small)
                t.eq(slow, s.gain(xx, yy), "a ${small}x$small frame agrees at ($xx, $yy)")
            }
        }

        // And a random geometry, because the real one is not the only one: a
        // different lens gives a different sensor size and a different subsample.
        for (trial in 0 until 6) {
            val w = 64 + r.nextInt(400) * 2
            val h = 64 + r.nextInt(400) * 2
            val sub = 1 shl r.nextInt(3)
            val ow = (w / (2 * sub)) * 2
            val oh = (h / (2 * sub)) * 2
            if (ow <= 0 || oh <= 0) continue
            val xs = IntArray(ow) { (it / 2) * 2 * sub + (it and 1) }
            val ys = IntArray(oh) { (it / 2) * 2 * sub + (it and 1) }
            val s = map.samplerFor(pattern, xs, ys, w, h)
            for (i in 0 until 40) {
                val xx = r.nextInt(ow)
                val yy = r.nextInt(oh)
                t.eq(map.gainAt(pattern, xs[xx], ys[yy], w, h), s.gain(xx, yy),
                    "trial $trial agrees at ($xx, $yy) of ${ow}x$oh")
            }
        }

        // Every CFA phase, because the plane a pixel belongs to is the one thing
        // the fast path caches and the slow path recomputes.
        for (p in CfaPattern.values()) {
            val s = map.samplerFor(p, sensorX, sensorY, sensorW, sensorH)
            for (yy in intArrayOf(0, 1, 2, 3, outH - 2, outH - 1)) {
                for (xx in intArrayOf(0, 1, 2, 3, outW - 2, outW - 1)) {
                    t.eq(map.gainAt(p, sensorX[xx], sensorY[yy], sensorW, sensorH),
                        s.gain(xx, yy), "$p agrees at ($xx, $yy)")
                }
            }
        }
    }


    /**
     * Turning a sensor buffer into linear samples, in the core where it can be
     * checked.
     *
     * This lived in the Android layer because it starts from a camera `Image`,
     * and so the only arithmetic between the sensor and everything downstream had
     * no test at all. That is where eight seconds a frame hid (see
     * [ShadingMap.Sampler]), and it is also the step that decides what "linear"
     * means for this whole app: subtract the per-channel black level, divide by
     * the range to white, apply the lens shading, and clamp.
     *
     * Splitting the buffer copy from the arithmetic buys the second thing too:
     * the copy is a memcpy that has to happen on the camera thread while the
     * image is still alive, and the arithmetic does not have to happen there at
     * all.
     */
    private fun theRawConversionIsCoreArithmeticAndTestedAsSuch(t: TestKit) {
        val r = t.rng(4242)
        val w = 16
        val h = 12
        val stride = w + 3          // A real plane's row stride is not its width.
        val shorts = ShortArray(stride * h)
        for (i in shorts.indices) shorts[i] = (r.nextInt(1024)).toShort()
        // Per-channel black, as a camera reports it: the four CFA positions do
        // not share one pedestal.
        val black = doubleArrayOf(64.0, 65.0, 63.0, 66.0)
        val white = 1023.0

        // With no shading, every sample is (raw - its own black) / (white - it).
        val plain = RawPlane.convert(shorts, stride, w, h, 1, black, white,
            CfaPattern.RGGB, null)
        t.eq(w.toLong(), plain.width.toLong(), "an unsubsampled frame keeps its width")
        t.eq(h.toLong(), plain.height.toLong(), "and its height")
        t.eq(1L, plain.channels.toLong(), "and is one channel: it is still a mosaic")
        for (y in 0 until h) {
            for (x in 0 until w) {
                val raw = shorts[y * stride + x].toInt() and 0xFFFF
                val b = black[(y and 1) * 2 + (x and 1)]
                val want = Math.max(0.0, Math.min(1.0, (raw - b) / (white - b))).toFloat()
                if (plain.get(x, y, 0) != want) {
                    t.fail("sample ($x, $y) is ${plain.get(x, y, 0)} and should be $want")
                    return
                }
            }
        }
        t.check(true, "every sample is its own black level subtracted and scaled to white")

        // Subsampling takes whole 2x2 blocks, so the mosaic phase survives. A
        // frame subsampled off phase is not a Bayer image any more, it is four
        // interleaved wrong ones - and nothing downstream would notice.
        val half = RawPlane.convert(shorts, stride, w, h, 2, black, white,
            CfaPattern.RGGB, null)
        t.eq((w / 2).toLong(), half.width.toLong(), "half size across")
        t.eq((h / 2).toLong(), half.height.toLong(), "and down")
        for (y in 0 until h / 2) {
            for (x in 0 until w / 2) {
                val sx = (x / 2) * 4 + (x and 1)
                val sy = (y / 2) * 4 + (y and 1)
                t.eq((sx and 1).toLong(), (x and 1).toLong(),
                    "the column parity survives at x=$x")
                t.eq((sy and 1).toLong(), (y and 1).toLong(),
                    "and the row parity at y=$y")
                val raw = shorts[sy * stride + sx].toInt() and 0xFFFF
                val b = black[(sy and 1) * 2 + (sx and 1)]
                val want = Math.max(0.0, Math.min(1.0, (raw - b) / (white - b))).toFloat()
                t.eq(want.toDouble(), half.get(x, y, 0).toDouble(),
                    "and the sample at ($x, $y) is the one from ($sx, $sy)")
            }
        }

        // A sample below black is not a negative radiance, and one above white is
        // not more than full. Both happen: black levels are an average and hot
        // pixels exist.
        val extreme = ShortArray(stride * h)
        for (i in extreme.indices) extreme[i] = if (i % 2 == 0) 0 else 4095
        val clamped = RawPlane.convert(extreme, stride, w, h, 1, black, white,
            CfaPattern.RGGB, null)
        var low = 0
        var high = 0
        for (v in clamped.data) {
            t.check(v >= 0f && v <= 1f, "every sample is inside [0, 1], got $v")
            if (v == 0f) low++
            if (v == 1f) high++
        }
        t.greaterThan(low.toDouble(), 0.0, "a sample under the black level lands at zero")
        t.greaterThan(high.toDouble(), 0.0, "and one over white at one")

        // With shading, and clamped *after* the gain: a corner sample at 0.99
        // with a gain of five is a clipped highlight, and clamping first would
        // hide that it ever left the range.
        val columns = 5
        val rows = 4
        val gains = DoubleArray(columns * rows * 4)
        for (gy in 0 until rows) for (gx in 0 until columns) {
            val nx = gx / (columns - 1.0) * 2 - 1
            val ny = gy / (rows - 1.0) * 2 - 1
            for (c in 0 until 4)
                gains[(gy * columns + gx) * 4 + c] = 1.0 + 3.0 * (nx * nx + ny * ny) / 2.0
        }
        val map = ShadingMap(gains, columns, rows)
        val shaded = RawPlane.convert(shorts, stride, w, h, 1, black, white,
            CfaPattern.RGGB, map)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val raw = shorts[y * stride + x].toInt() and 0xFFFF
                val b = black[(y and 1) * 2 + (x and 1)]
                val v = (raw - b) / (white - b)
                val g = map.gainAt(CfaPattern.RGGB, x, y, w, h)
                val want = Math.max(0.0, Math.min(1.0, (v * g.toFloat()).toDouble())).toFloat()
                if (Math.abs(shaded.get(x, y, 0) - want) > 1e-6f) {
                    t.fail("shaded sample ($x, $y) is ${shaded.get(x, y, 0)}, want $want")
                    return
                }
            }
        }
        t.check(true, "the shading gain is applied to every sample, and clamped after it")
        t.greaterThan(shaded.get(0, 0, 0).toDouble(), plain.get(0, 0, 0).toDouble(),
            "so a corner really is brought up")

        // The things a caller can get wrong.
        var threw = 0
        for (bad in intArrayOf(0, 3, -2)) {
            try { RawPlane.convert(shorts, stride, w, h, bad, black, white,
                CfaPattern.RGGB, null) } catch (e: Exception) { threw++ }
        }
        t.eq(3L, threw.toLong(), "a subsample that is not a positive power of two is refused")
        try {
            RawPlane.convert(shorts, stride, w, h, 32, black, white, CfaPattern.RGGB, null)
            t.fail("subsampling a 16x12 frame by 32 should leave nothing and say so")
        } catch (e: Exception) {
            t.check(true, "subsampling past the frame is refused rather than returning nothing")
        }
        try {
            RawPlane.convert(ShortArray(4), stride, w, h, 1, black, white,
                CfaPattern.RGGB, null)
            t.fail("a buffer too small for the frame should be refused")
        } catch (e: Exception) {
            t.check(true, "a buffer that cannot hold the frame is refused")
        }
    }


    /**
     * The lens shading correction belongs on radiance, not on sensor readings.
     *
     * Found by the owner looking at a finished sphere: the app had announced that
     * a direction with a window in it "came back burnt out" and re-shot it, and
     * yet at minus three and a half stops there is detail in the sunlit white
     * wall behind that window. The highlight it claimed to have lost was there
     * all along.
     *
     * The arithmetic says why. The correction was applied to the sensor fraction
     * and the result clamped into [0, 1]. This phone's map peaks at a gain of
     * **5.03**, so a corner sample above `0.98 / 5.03 = 0.195` came out at or
     * past the saturation threshold - and "burnt out" is declared at a tenth of a
     * percent of the frame, three thousand pixels of three million. A bright
     * corner at a fifth of white was enough.
     *
     * Two faults from one mistake:
     *
     *  - **saturation invented where there is none**, which costs a re-shoot and
     *    tells the report the top of the range is a lower bound when it is not;
     *  - **real corner signal thrown away**, because everything above 1/gain
     *    clamped to the same 1.0.
     *
     * Saturation is a property of the sensor well. The shading gain is a
     * multiplicative correction, it commutes with the exposure scaling, and on
     * radiance there is no ceiling to clamp against - so it goes exactly where
     * the colour matrix goes, on the merged radiance, for exactly the same
     * reason.
     *
     * Which also means the gain has to be de-mosaiced: merged radiance is RGB,
     * and the map's four planes are R, Gr, Gb, B.
     */
    private fun theShadingGainBelongsOnRadianceNotOnSensorReadings(t: TestKit) {
        val columns = 9
        val rows = 7
        val gains = DoubleArray(columns * rows * 4)
        for (gy in 0 until rows) for (gx in 0 until columns) {
            val nx = gx / (columns - 1.0) * 2 - 1
            val ny = gy / (rows - 1.0) * 2 - 1
            val radial = 1.0 + 4.03 * (nx * nx + ny * ny) / 2.0
            // The two greens differ slightly, as a real map's do.
            gains[(gy * columns + gx) * 4 + 0] = radial
            gains[(gy * columns + gx) * 4 + 1] = radial * 0.98
            gains[(gy * columns + gx) * 4 + 2] = radial * 1.02
            gains[(gy * columns + gx) * 4 + 3] = radial * 1.05
        }
        val map = ShadingMap(gains, columns, rows)
        // The radial term peaks where the phone's own map does; the blue plane
        // sits five percent above it, as a real map's planes differ.
        t.near(5.03 * 1.05, map.peakGain(), 0.06,
            "this map peaks where the real one does, blue highest")

        // The fault, stated as arithmetic: a mid-grey corner read as saturated.
        val corner = 0.3
        t.greaterThan(corner * map.peakGain(), 0.98,
            "a corner sample at 0.3 times white, gained, lands past the saturation " +
            "threshold - which is the false 'burnt out'")

        // On radiance: three channels, each by its own plane, and greens averaged
        // because a demosaiced green came from both.
        val w = 40
        val h = 30
        val radiance = ImageF(w, h, 3)
        for (i in radiance.data.indices) radiance.data[i] = 2.0f
        val rgb = map.rgbSamplerFor(w, h)
        rgb.apply(radiance)

        for (y in intArrayOf(0, 1, h / 2, h - 1)) {
            for (x in intArrayOf(0, 1, w / 2, w - 1)) {
                val fx = x / (w - 1.0) * (columns - 1)
                val fy = y / (h - 1.0) * (rows - 1)
                // Straight from the grid, bilinear, per plane - the definition
                // this has to agree with.
                fun node(plane: Int): Double {
                    val x0 = Math.min(columns - 1, Math.floor(fx).toInt())
                    val y0 = Math.min(rows - 1, Math.floor(fy).toInt())
                    val x1 = Math.min(columns - 1, x0 + 1)
                    val y1 = Math.min(rows - 1, y0 + 1)
                    val tx = fx - x0
                    val ty = fy - y0
                    val g00 = gains[(y0 * columns + x0) * 4 + plane]
                    val g10 = gains[(y0 * columns + x1) * 4 + plane]
                    val g01 = gains[(y1 * columns + x0) * 4 + plane]
                    val g11 = gains[(y1 * columns + x1) * 4 + plane]
                    val top = g00 + (g10 - g00) * tx
                    val bot = g01 + (g11 - g01) * tx
                    return top + (bot - top) * ty
                }
                t.near(2.0 * node(0), radiance.get(x, y, 0).toDouble(), 1e-5,
                    "red at ($x, $y) is gained by the red plane")
                t.near(2.0 * (node(1) + node(2)) / 2.0, radiance.get(x, y, 1).toDouble(), 1e-5,
                    "green by the mean of the two green planes")
                t.near(2.0 * node(3), radiance.get(x, y, 2).toDouble(), 1e-5,
                    "and blue by the blue plane")
            }
        }

        // Nothing is clamped, which is the whole point: a corner brought up past
        // the nominal white is a real measurement of a real highlight.
        var above = 0
        for (v in radiance.data) if (v > 2.0f) above++
        t.greaterThan(above.toDouble(), 0.0, "corners are brought up")
        var maxV = 0.0f
        for (v in radiance.data) if (v > maxV) maxV = v
        t.greaterThan(maxV.toDouble(), 2.0 * 4.0,
            "and are allowed past any ceiling, because radiance has none")

        // The centre is barely touched, so this is a correction and not a tint.
        t.near(2.0, radiance.get(w / 2, h / 2, 1).toDouble(), 0.25,
            "the centre of the frame is left about as it was")

        // Applying it twice is applying it twice - it is not idempotent and must
        // not pretend to be, so the pipeline has to apply it exactly once.
        val once = radiance.get(0, 0, 0)
        rgb.apply(radiance)
        t.greaterThan(radiance.get(0, 0, 0).toDouble(), once.toDouble() * 1.5,
            "a second application multiplies again, as multiplication does")

        // Shape errors are refused rather than silently mangling a frame.
        var threw = 0
        for (bad in arrayOf(ImageF(w, h, 1), ImageF(w + 1, h, 3), ImageF(w, h + 1, 3))) {
            try { rgb.apply(bad) } catch (e: Exception) { threw++ }
        }
        t.eq(3L, threw.toLong(),
            "a frame of the wrong shape or channel count is refused")
    }

    private fun theShadingMapStretchesOverTheFrame(t: TestKit) {
        val cols = 5
        val rows = 4
        // Flat at the centre, rising to the corners, and a different amount per
        // channel - which is what a real map looks like.
        val gains = DoubleArray(cols * rows * 4)
        for (y in 0 until rows)
            for (x in 0 until cols) {
                val dx = (x / (cols - 1.0)) * 2 - 1
                val dy = (y / (rows - 1.0)) * 2 - 1
                val r2 = dx * dx + dy * dy
                for (p in 0 until 4)
                    gains[(y * cols + x) * 4 + p] = 1.0 + (0.5 + 0.1 * p) * r2
            }
        val map = ShadingMap(gains, cols, rows)

        val w = 400
        val h = 300
        // The centre of a map like that is unity, whatever the channel.
        for (p in 0 until 4) {
            val g = map.gainAt(CfaPattern.RGGB, (w / 2) and 1.inv(), (h / 2) and 1.inv(), w, h)
            t.check(g > 0.9 && g < 1.35, "the middle of the frame is barely corrected")
        }
        // The corners are, and by more than the middle.
        val corner = map.gainAt(CfaPattern.RGGB, 0, 0, w, h)
        val middle = map.gainAt(CfaPattern.RGGB, w / 2, h / 2, w, h)
        t.greaterThan(corner, middle * 1.5, "and the corner is corrected a great deal more")
        t.nearRel(gains[0], corner, 1e-9, "the very corner is the grid node itself")

        // The four channels are R, Gr, Gb, B whatever the CFA is, so the phase
        // has to be read off the pattern. Getting the two greens the wrong way
        // round is invisible in a flat field and a checkerboard everywhere else.
        t.eq(0L, map.planeOf(CfaPattern.RGGB, 0, 0).toLong(), "RGGB starts on red")
        t.eq(1L, map.planeOf(CfaPattern.RGGB, 1, 0).toLong(), "the green beside it is Gr")
        t.eq(2L, map.planeOf(CfaPattern.RGGB, 0, 1).toLong(), "the green below it is Gb")
        t.eq(3L, map.planeOf(CfaPattern.RGGB, 1, 1).toLong(), "and the far corner is blue")
        t.eq(1L, map.planeOf(CfaPattern.GRBG, 0, 0).toLong(),
            "GRBG starts on the green that shares its row with red")
        t.eq(0L, map.planeOf(CfaPattern.GRBG, 1, 0).toLong(), "with red beside it")
        t.eq(3L, map.planeOf(CfaPattern.GRBG, 0, 1).toLong(), "blue below")
        t.eq(2L, map.planeOf(CfaPattern.GRBG, 1, 1).toLong(), "and Gb in the corner")

        // A grid too small to interpolate is refused rather than read out of
        // bounds later.
        t.throwsException({ ShadingMap(DoubleArray(4), 1, 1) }, "a one-node grid is not a map")
        t.throwsException({ ShadingMap(DoubleArray(7), 2, 2) }, "nor is a short array")
        t.note("shading: " + map)
    }
}
