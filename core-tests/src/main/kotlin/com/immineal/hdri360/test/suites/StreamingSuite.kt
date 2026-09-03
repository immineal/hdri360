package com.immineal.hdri360.test.suites

import com.immineal.hdri360.core.camera.Intrinsics
import com.immineal.hdri360.core.image.ImageF
import com.immineal.hdri360.core.io.ExrReader
import com.immineal.hdri360.core.io.ExrStreamWriter
import com.immineal.hdri360.core.io.ExrWriter
import com.immineal.hdri360.core.io.Half
import com.immineal.hdri360.core.pano.CaptureTarget
import com.immineal.hdri360.core.pano.FrameSource
import com.immineal.hdri360.core.pano.PanoramaRenderer
import com.immineal.hdri360.test.TestCase
import com.immineal.hdri360.test.TestKit
import java.io.ByteArrayOutputStream
import java.util.Arrays

/**
 * Rendering and writing the panorama in horizontal strips.
 *
 * A 4096 x 2048 float RGB panorama is 100 MB before anything else is in memory,
 * and the frames it is built from are hundreds more. On a phone that is the
 * difference between finishing and being killed, so the output path never has to
 * hold the whole picture at once.
 */
class StreamingSuite : TestCase {
    override fun name(): String = "streaming"

    override fun run(t: TestKit) {
        val r = t.rng(515)
        val k = Intrinsics.fromHorizontalFov(120, 90, 70.0)
        val frames = ArrayList<FrameSource>()
        for (yaw in doubleArrayOf(-40.0, 0.0, 40.0, 120.0)) {
            val img = ImageF(k.width, k.height, 3)
            for (i in img.data.indices) img.data[i] = (0.1 + r.nextDouble() * 30).toFloat()
            frames.add(FrameSource(img, k,
                CaptureTarget.lookingAt(CaptureTarget.directionFor(yaw, 0.0)).rotation, null, 1.0))
        }

        val cfg = PanoramaRenderer.Config()
        cfg.width = 256
        cfg.featherPx = 10.0
        val whole = PanoramaRenderer.render(frames, cfg)

        // --- strips must reproduce the single-shot render exactly ------------
        for (strip in intArrayOf(1, 7, 16, 128)) {
            val assembled = ImageF(cfg.width, cfg.width / 2, 3)
            val coverage = FloatArray(cfg.width * (cfg.width / 2))
            var y0 = 0
            while (y0 < cfg.width / 2) {
                val y1 = Math.min(cfg.width / 2, y0 + strip)
                val part = PanoramaRenderer.renderRows(frames, cfg, y0, y1)
                t.eq((y1 - y0).toLong(), part.panorama.height.toLong(),
                    "the strip has the requested height")
                System.arraycopy(part.panorama.data, 0, assembled.data, y0 * cfg.width * 3,
                    (y1 - y0) * cfg.width * 3)
                System.arraycopy(part.coverage, 0, coverage, y0 * cfg.width, (y1 - y0) * cfg.width)
                y0 += strip
            }
            var worst = 0.0
            for (i in whole.panorama.data.indices)
                worst = Math.max(worst, Math.abs(assembled.data[i] - whole.panorama.data[i]).toDouble())
            t.near(0.0, worst, 0.0, "strips of $strip rows reproduce the whole render bit for bit")
            for (i in coverage.indices)
                t.near(whole.coverage[i].toDouble(), coverage[i].toDouble(), 0.0, "coverage matches too")
        }

        t.throwsException({ PanoramaRenderer.renderRows(frames, cfg, 10, 5) },
            "an inverted row range is an error")
        t.throwsException({ PanoramaRenderer.renderRows(frames, cfg, 0, 10000) },
            "a row range past the end is an error")

        // --- the streaming writer must produce the same file as the one-shot writer ---
        val full = whole.panorama
        val oneShot = ByteArrayOutputStream()
        ExrWriter.write(oneShot, full, ExrWriter.Compression.ZIPS)

        val streamed = ByteArrayOutputStream()
        ExrStreamWriter(streamed, full.width, full.height, ExrWriter.Compression.ZIPS).use { w ->
            var y0 = 0
            while (y0 < full.height) {
                val y1 = Math.min(full.height, y0 + 13)
                val strip = ImageF(full.width, y1 - y0, 3)
                System.arraycopy(full.data, y0 * full.width * 3, strip.data, 0,
                    (y1 - y0) * full.width * 3)
                w.writeRows(strip)
                y0 += 13
            }
        }
        val a = oneShot.toByteArray()
        val b = streamed.toByteArray()
        t.eq(a.size.toLong(), b.size.toLong(), "the streamed file is the same size")
        val identical = Arrays.equals(a, b)
        t.check(identical, "the streamed file is byte-identical to the one-shot file")

        val back = ExrReader.read(b)
        var worstPixel = 0.0
        for (i in full.data.indices)
            worstPixel = Math.max(worstPixel,
                Math.abs(back.data[i] - Half.toFloat(Half.fromFloat(full.data[i]))).toDouble())
        t.lessThan(worstPixel, 1e-6, "and it reads back correctly")

        // --- the writer refuses to be left half-finished -------------------------------
        val partial = ByteArrayOutputStream()
        val incomplete = ExrStreamWriter(partial, 64, 32, ExrWriter.Compression.NONE)
        val oneRow = ImageF(64, 1, 3)
        incomplete.writeRows(oneRow)
        t.eq(1L, incomplete.rowsWritten().toLong(), "rows written are counted")
        // Kotlin has no checked exceptions, but TestKit.throwsException still only
        // catches RuntimeException, so the rethrow wrapper has to stay.
        t.throwsException({
            try { incomplete.close() } catch (e: Exception) { throw RuntimeException(e) }
        }, "closing before every row is written is an error, not a silently truncated file")

        val mismatched = ExrStreamWriter(ByteArrayOutputStream(), 64, 32, ExrWriter.Compression.NONE)
        t.throwsException({
            try { mismatched.writeRows(ImageF(65, 1, 3)) } catch (e: Exception) { throw RuntimeException(e) }
        }, "a strip of the wrong width is an error")
    }
}
