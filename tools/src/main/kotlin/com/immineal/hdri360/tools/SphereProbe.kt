package com.immineal.hdri360.tools

import com.immineal.hdri360.core.camera.Intrinsics
import com.immineal.hdri360.core.hdr.Exposure
import com.immineal.hdri360.core.hdr.ExposureSettings
import com.immineal.hdri360.core.image.BayerImage
import com.immineal.hdri360.core.image.CfaPattern
import com.immineal.hdri360.core.image.Demosaic
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
            inputs.add(HdriPipeline.FrameInput.deferred(k, prior, "t%03d".format(tIdx)) {
                rungs.map { r ->
                    val raw = readDng(File(dir, "raw/t%03d_b%d.dng".format(r.target, r.bracket)))
                    // Demosaiced per rung and merged in RGB, exactly as the
                    // stored path on the phone does it.
                    Exposure.of(
                        Demosaic.malvarHeCutler(mosaic(raw, white, black, subsample, cfa)),
                        r.settings, session["baseIso"].asDouble().toInt())
                }
            })
        }

        val opt = HdriPipeline.Options()
        opt.panoramaWidth = if (args.size > 2) args[2].toInt() else 2048
        opt.priorWeight = 0.5
        opt.levelHorizon = false
        val started = System.currentTimeMillis()
        val res = HdriPipeline.process(inputs, opt) { stage, f ->
            if (f == 0.0) println("  $stage")
        }
        val secs = (System.currentTimeMillis() - started) / 1000.0

        println()
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
        println("frames with no partner at all: ${alone.size} ${alone.take(20)}")
        println("degree: " + degree.joinToString(" "))
        println("features: " + res.matching.featuresPerFrame.joinToString(" "))
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
    private fun mosaic(raw: Raw, white: Int, black: Double, step: Int, cfa: CfaPattern): BayerImage {
        val w = raw.width / (2 * step) * 2
        val h = raw.height / (2 * step) * 2
        val out = BayerImage(w, h, cfa)
        val scale = 1.0f / Math.max(1.0, white - black).toFloat()
        for (y in 0 until h) {
            val by = (y / 2) * step * 2 + (y and 1)
            for (x in 0 until w) {
                val bx = (x / 2) * step * 2 + (x and 1)
                val v = (raw.data[by * raw.width + bx] - black).toFloat() * scale
                out.plane.data[y * w + x] = if (v > 0f) v else 0f
            }
        }
        return out
    }
}
