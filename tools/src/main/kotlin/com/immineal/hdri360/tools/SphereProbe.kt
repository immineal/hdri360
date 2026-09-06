package com.immineal.hdri360.tools

import com.immineal.hdri360.core.camera.Intrinsics
import com.immineal.hdri360.core.hdr.Exposure
import com.immineal.hdri360.core.hdr.ExposureSettings
import com.immineal.hdri360.core.image.BayerImage
import com.immineal.hdri360.core.image.CfaPattern
import com.immineal.hdri360.core.image.Demosaic
import com.immineal.hdri360.core.image.ImageF
import com.immineal.hdri360.core.io.Json
import com.immineal.hdri360.core.math.Mat3
import com.immineal.hdri360.core.pipeline.HdriPipeline
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * Runs a capture taken on the phone through the desktop pipeline.
 *
 * The point is the frames rather than the panorama. A sphere that comes back
 * fully covered and softly seamed is not obviously broken from its output, and
 * the thing that decides whether it is any good - how many frames the solver
 * could tie to each other - is invisible unless somebody counts. On the phone it
 * is also expensive to count twice. So the bundle comes off the device and is
 * asked the same question here, as many times as it takes.
 *
 * Reads the DNG bundle directly: DngCreator writes uncompressed 16 bit CFA, one
 * strip per row, which is a TIFF anyone can read and not a format worth pulling
 * in a library for.
 */
object SphereProbe {

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty()) {
            System.err.println("usage: SphereProbe <capture dir> [output dir] [width]")
            return
        }
        val dir = File(args[0])
        val session = Json.parse(File(dir, "session.json").readText())
        val subsample = session["imageWidth"].asDouble().toInt().let { w -> if (w <= 2200) 2 else 1 }
        val k = Intrinsics(session["imageWidth"].asDouble().toInt(), session["imageHeight"].asDouble().toInt(),
            session["fx"].asDouble(), session["fy"].asDouble(),
            session["cx"].asDouble(), session["cy"].asDouble(), 0.0, 0.0, 0.0)
        val cfa = CfaPattern.valueOf(session["cfa"].asString())
        val white = session["whiteLevel"].asDouble().toInt()
        val blacks = session["blackLevel"]
        var black = 0.0
        for (i in 0 until blacks.size()) black += blacks.at(i).asDouble()
        black /= Math.max(1, blacks.size())

        // The journal names every frame that was actually stored, with the
        // exposure it was taken at and the pose the phone thought it had.
        // The same colour chain the phone now runs: the capture's one white
        // balance, then the camera's own sensor-RGB to linear-sRGB matrix. Without
        // these the probe measures a different picture from the one the app
        // produces, which is the opposite of what a probe is for.
        val header = session as Json.Obj
        val gains = if (header.has("neutralGains")) {
            val g = header["neutralGains"]; DoubleArray(g.size()) { g.at(it).asDouble() }
        } else null
        val matrix = if (header.has("colorMatrix")) {
            val m = header["colorMatrix"]
            if (m.size() == 9) DoubleArray(9) { m.at(it).asDouble() } else null
        } else null
        // Which half of the colour chain to run, so a colour fault can be
        // bisected by looking rather than by argument: wb, matrix, both, none,
        // or "t" for the matrix transposed - the one mistake whose signature is
        // a plausible-looking picture in the wrong hue.
        // A fifth argument fixes the radial distortion instead of solving it, so
        // the residual can be asked what it thinks of each value. A solve that
        // lands anywhere between 0.14 and 0.26 depending only on how the poses
        // were initialised is either badly conditioned or estimating something
        // the data does not contain, and a sweep says which.
        val fixedK1 = args.getOrNull(4)?.toDoubleOrNull()

        val mode = if (args.size > 3) args[3] else "both"
        val useGains = mode == "both" || mode == "wb" || mode == "t"
        val useMatrix = mode == "both" || mode == "matrix" || mode == "t"
        val m = if (!useMatrix) null else if (mode == "t" && matrix != null)
            DoubleArray(9) { i -> matrix[(i % 3) * 3 + i / 3] } else matrix
        // The lens shading correction the phone applied to every frame it
        // stitched. Without it this path works on frames whose corners are a
        // stop darker, which is exactly where the overlap is - and the probe then
        // measures a different sphere from the one the app produced. Captures
        // taken before it was recorded have none, and say so.
        val shading = if (!header.has("shadingGains") || !header.has("shadingCols")) null else {
            val g = header["shadingGains"]
            try {
                com.immineal.hdri360.core.image.ShadingMap(
                    DoubleArray(g.size()) { g.at(it).asDouble() },
                    header["shadingCols"].asDouble().toInt(),
                    header["shadingRows"].asDouble().toInt())
            } catch (e: Exception) { null }
        }
        println("shading: " + (shading?.toString()
            ?: "not recorded in this capture - the corners will be a stop dark"))

        println("mode $mode: white balance " +
                (if (useGains) gains?.joinToString(", ") { "%.3f".format(it) } ?: "none" else "off") +
                "; colour matrix " + (if (m != null) "on" else "off"))

        val byTarget = LinkedHashMap<Int, MutableList<Rec>>()
        for (line in File(dir, "frames.jsonl").readLines()) {
            if (line.isBlank()) continue
            val o = try { Json.parse(line) } catch (e: Exception) { continue }
            val r = Rec(o["target"].asDouble().toInt(), o["bracket"].asDouble().toInt(),
                ExposureSettings(o["t"].asDouble(), o["iso"].asDouble().toInt(), o["f"].asDouble()),
                poseOf(o))
            byTarget.getOrPut(r.target) { ArrayList() }.add(r)
        }
        val targets = byTarget.keys.sorted()
        println("capture $dir: ${targets.size} directions, " +
                "${byTarget.values.sumOf { it.size }} frames, working ${k.width}x${k.height}")

        val inputs = ArrayList<HdriPipeline.FrameInput>()
        for (tIdx in targets) {
            val rungs = byTarget[tIdx]!!.sortedBy { it.bracket }
            val prior = rungs.firstOrNull { it.pose != null }?.pose
            val ki = if (fixedK1 == null) k else k.withDistortion(fixedK1, 0.0, 0.0)
            inputs.add(HdriPipeline.FrameInput.deferred(ki, prior, "t%03d".format(tIdx)) {
                rungs.map { r ->
                    val raw = readDng(File(dir, "raw/t%03d_b%d.dng".format(r.target, r.bracket)))
                    // Demosaiced per rung and merged in RGB, exactly as the
                    // stored path on the phone does it.
                    Exposure.of(
                        Demosaic.malvarHeCutler(
                            // No shading here: it goes on merged radiance, as
                            // opt.shading below, exactly as the phone now does.
                            // Applied to sensor fractions it invented saturation
                            // out of dim corners - see HdriPipeline.Options.shading.
                            mosaic(raw, white, black, subsample, cfa, null)),
                        r.settings, session["baseIso"].asDouble().toInt())
                }
            })
        }

        val opt = HdriPipeline.Options()
        // One matrix, applied to the merged radiance by the pipeline - not to the
        // rungs. Colouring a rung before it is merged moves it out of the [0,1]
        // sensor domain the merge's saturation test lives in, and the channel with
        // the largest coefficient silently loses its brightest samples.
        opt.shading = shading
        opt.colorTransform = if (!useMatrix && !useGains) null else {
            val mm = if (useMatrix && m != null) m
                     else doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
            val gr = if (useGains && gains != null && gains[1] > 1e-9) gains[0] / gains[1] else 1.0
            val gb = if (useGains && gains != null && gains[1] > 1e-9) gains[2] / gains[1] else 1.0
            doubleArrayOf(mm[0] * gr, mm[1], mm[2] * gb,
                          mm[3] * gr, mm[4], mm[5] * gb,
                          mm[6] * gr, mm[7], mm[8] * gb)
        }
        if (fixedK1 != null) opt.solveDistortion = false
        opt.panoramaWidth = if (args.size > 2) args[2].toInt() else 2048
        opt.priorWeight = 0.5
        opt.levelHorizon = false
        val started = System.currentTimeMillis()
        // Stage timings, so the progress bar's weights can be measured rather
        // than guessed. Same pipeline the phone runs; the absolute seconds differ
        // but the shares are what a bar needs.
        var stageStarted = System.nanoTime()
        var stageName = ""
        val stageMs = LinkedHashMap<String, Long>()
        val res = HdriPipeline.process(inputs, opt) { stage, f ->
            if (stage != stageName) {
                if (stageName.isNotEmpty())
                    stageMs[stageName] = (stageMs[stageName] ?: 0L) +
                        (System.nanoTime() - stageStarted) / 1_000_000L
                stageName = stage
                stageStarted = System.nanoTime()
                println("  $stage")
            }
        }
        val secs = (System.currentTimeMillis() - started) / 1000.0

        println()
        if (stageName.isNotEmpty())
            stageMs[stageName] = (stageMs[stageName] ?: 0L) +
                (System.nanoTime() - stageStarted) / 1_000_000L
        val stageTotal = stageMs.values.sum().coerceAtLeast(1L)
        println("stage shares (of " + stageTotal + " ms): " +
            stageMs.entries.joinToString("  ") {
                String.format(java.util.Locale.US, "%s %.3f (%d ms)",
                    it.key, it.value / stageTotal.toDouble(), it.value)
            })
        println(res.matching)
        println(String.format(Locale.US,
            "placed %d of %d, %d pairs, residual %.4f deg, k1 %.4f, covered %.1f%%, %.1f s",
            res.placed.count { it }, res.placed.size, res.pairs.size, res.baRmsDeg, res.k1,
            100 * res.coveredFraction, secs))

        // Where the pairs are, rather than how many: a graph can have plenty of
        // edges and still leave half the sphere hanging off one frame.
        val degree = IntArray(inputs.size)
        for (p in res.pairs) { degree[p.a]++; degree[p.b]++ }
        val alone = (0 until inputs.size).filter { degree[it] == 0 }
        // Decision 2, answered: how much of each direction no exposure held. The
        // app reports the same numbers, so a disagreement here is a real one.
        val blown = res.saturatedFraction.withIndex().filter { it.value > 1e-4 }
        println("directions with unmeasured highlights: ${blown.size}" +
                if (blown.isEmpty()) "" else " " + blown.joinToString(" ") {
                    "t%03d:%.1f%%".format(it.index, 100 * it.value) })
        println("frames with no partner at all: ${alone.size} ${alone.take(20)}")
        println("degree: " + degree.joinToString(" "))
        println("features: " + res.matching.featuresPerFrame.joinToString(" "))

        // A picture, because the numbers say the graph holds together and only
        // looking says whether the seams do.
        if (args.size > 1 && args[1] != "x") {
            val out = File(args[1])
            out.mkdirs()
            RestitchTool.writePng(res.panorama, File(out, "preview.png").path)
            // The radiance as well as the picture. A tone-mapped PNG cannot be
            // measured - its brightest pixels are wherever the curve saturated,
            // which is a large patch of sky rather than the sun - and measuring
            // the sphere is most of what this probe is for.
            java.io.BufferedOutputStream(
                java.io.FileOutputStream(File(out, "panorama.exr")), 1 shl 16).use { o ->
                com.immineal.hdri360.core.io.ExrWriter.write(o, res.panorama,
                    com.immineal.hdri360.core.io.ExrWriter.Compression.ZIPS)
            }
            RestitchTool.writeCoveragePng(res.coverage, res.panorama.width, res.panorama.height,
                File(out, "coverage.png").path)
            println("wrote " + File(out, "preview.png"))
        }
    }

    private class Rec(val target: Int, val bracket: Int,
                      val settings: ExposureSettings, val pose: Mat3?)

    private fun poseOf(o: Json.Value): Mat3? {
        val a = try { o["pose"] } catch (e: Exception) { return null }
        val n = try { a.size() } catch (e: Exception) { return null }
        if (n < 9) return null
        return Mat3(DoubleArray(9) { a.at(it).asDouble() })
    }

    /**
     * One uncompressed 16 bit CFA DNG as a raw sample array.
     *
     * Only the tags that matter, and only the layout DngCreator writes: strips of
     * one row each, no compression, no tiles.
     */
    private fun readDng(file: File): Raw {
        RandomAccessFile(file, "r").use { f ->
            val head = ByteArray(8)
            f.readFully(head)
            val order = if (head[0] == 'I'.code.toByte()) ByteOrder.LITTLE_ENDIAN
                        else ByteOrder.BIG_ENDIAN
            fun u16(b: ByteArray, at: Int) = ByteBuffer.wrap(b, at, 2).order(order).short.toInt() and 0xFFFF
            fun u32(b: ByteArray, at: Int) = ByteBuffer.wrap(b, at, 4).order(order).int.toLong() and 0xFFFFFFFFL
            var off = u32(head, 4)
            f.seek(off)
            val cnt = ByteArray(2).also { f.readFully(it) }.let { u16(it, 0) }
            var width = 0; var height = 0
            var stripOffsets = 0L; var stripCounts = 0L; var strips = 0
            for (i in 0 until cnt) {
                val e = ByteArray(12)
                f.readFully(e)
                val tag = u16(e, 0)
                val n = u32(e, 4).toInt()
                val v = u32(e, 8)
                when (tag) {
                    256 -> width = v.toInt()
                    257 -> height = v.toInt()
                    273 -> { stripOffsets = v; strips = n }
                    279 -> stripCounts = v
                }
            }
            if (width == 0 || height == 0 || strips == 0)
                throw IllegalStateException("$file is not the DNG layout this reads")

            // Strip offsets are an array of longs at stripOffsets when there is
            // more than one strip; a single strip stores its offset inline.
            val offsets = LongArray(strips)
            if (strips == 1) offsets[0] = stripOffsets
            else {
                f.seek(stripOffsets)
                val raw = ByteArray(4 * strips)
                f.readFully(raw)
                for (i in 0 until strips) offsets[i] = u32(raw, 4 * i)
            }
            val data = IntArray(width * height)
            val row = ByteArray(2 * width)
            for (y in 0 until strips) {
                f.seek(offsets[y])
                f.readFully(row)
                val base = y * width
                for (x in 0 until width) data[base + x] = u16(row, 2 * x)
            }
            return Raw(width, height, data)
        }
    }

    private class Raw(val width: Int, val height: Int, val data: IntArray)

    /**
     * Black level off, white level normalised, decimated by whole CFA blocks.
     *
     * Decimation keeps the pattern's phase - it takes every [step]th 2x2 block
     * rather than every [step]th sample - so the result is the same mosaic seen
     * from further away rather than a different one.
     */
    private fun mosaic(raw: Raw, white: Int, black: Double, step: Int, cfa: CfaPattern,
                       shading: com.immineal.hdri360.core.image.ShadingMap? = null): BayerImage {
        val w = raw.width / (2 * step) * 2
        val h = raw.height / (2 * step) * 2
        val out = BayerImage(w, h, cfa)
        val scale = 1.0f / Math.max(1.0, white - black).toFloat()
        for (y in 0 until h) {
            val by = (y / 2) * step * 2 + (y and 1)
            for (x in 0 until w) {
                val bx = (x / 2) * step * 2 + (x and 1)
                var v = (raw.data[by * raw.width + bx] - black).toFloat() * scale
                // At the full-frame coordinate, not the decimated one: the map is
                // stretched over the sensor and decimation does not move the
                // corners.
                if (shading != null)
                    v *= shading.gainAt(cfa, bx, by, raw.width, raw.height).toFloat()
                out.plane.data[y * w + x] = if (v > 0f) v.coerceAtMost(1f) else 0f
            }
        }
        return out
    }
}
