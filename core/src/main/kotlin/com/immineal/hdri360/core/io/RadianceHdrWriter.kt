package com.immineal.hdri360.core.io

import com.immineal.hdri360.core.image.ImageF
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * Radiance .hdr (RGBE) writer with adaptive run-length encoding.
 *
 * Kept alongside the EXR path because .hdr is the format that opens everywhere -
 * every renderer, every game engine, every ancient plugin - at the cost of a
 * shared exponent and about 1% precision on the darker channels of a pixel.
 */
object RadianceHdrWriter {

    @JvmStatic
    fun write(out: OutputStream, image: ImageF) {
        if (image.channels < 3) throw IllegalArgumentException("Radiance output expects RGB")
        val w = image.width
        val h = image.height
        LittleEndian.writeAscii(out, "#?RADIANCE\n")
        LittleEndian.writeAscii(out, "SOFTWARE=HDRI360\n")
        LittleEndian.writeAscii(out, "FORMAT=32-bit_rle_rgbe\n")
        LittleEndian.writeAscii(out, "EXPOSURE=1.0\n\n")
        LittleEndian.writeAscii(out, "-Y " + h + " +X " + w + "\n")

        val scan = Array(4) { ByteArray(w) }
        for (y in 0 until h) {
            for (x in 0 until w) {
                val base = (y * w + x) * image.channels
                encode(image.data[base], image.data[base + 1], image.data[base + 2], scan, x)
            }
            if (w < 8 || w > 0x7FFF) {
                // Too narrow (or too wide) for the RLE form: write flat RGBE pixels.
                for (x in 0 until w)
                    for (c in 0 until 4) out.write(scan[c][x].toInt() and 0xFF)
            } else {
                out.write(2); out.write(2)
                out.write((w ushr 8) and 0xFF); out.write(w and 0xFF)
                for (c in 0 until 4) writeRleComponent(out, scan[c], w)
            }
        }
        out.flush()
    }

    @JvmStatic
    internal fun encode(r: Float, g: Float, b: Float, scan: Array<ByteArray>, x: Int) {
        val max = Math.max(r, Math.max(g, b))
        if (!(max > 1e-32f)) {
            scan[0][x] = 0; scan[1][x] = 0; scan[2][x] = 0; scan[3][x] = 0
            return
        }
        val e = Math.getExponent(max) + 1                 // max = m * 2^e with m in [0.5, 1)
        val scale = Math.scalb(256.0, -e)
        scan[0][x] = clamp255((r * scale).toInt()).toByte()
        scan[1][x] = clamp255((g * scale).toInt()).toByte()
        scan[2][x] = clamp255((b * scale).toInt()).toByte()
        scan[3][x] = clamp255(e + 128).toByte()
    }

    private fun clamp255(v: Int): Int = if (v < 0) 0 else (if (v > 255) 255 else v)

    /** New-style RLE: alternating literal runs (1..128) and repeat runs (4..127). */
    private fun writeRleComponent(out: OutputStream, data: ByteArray, w: Int) {
        var x = 0
        while (x < w) {
            var runStart = x
            var runLength = 0
            // Find the next run of at least four identical bytes.
            while (runStart < w) {
                runLength = 1
                while (runStart + runLength < w && runLength < 127 &&
                    data[runStart + runLength] == data[runStart]) runLength++
                if (runLength >= 4) break
                runStart += runLength
            }
            // Everything before it is emitted literally, in blocks of at most 128.
            while (x < runStart) {
                val n = Math.min(128, runStart - x)
                out.write(n)
                for (i in 0 until n) out.write(data[x + i].toInt() and 0xFF)
                x += n
            }
            if (runStart < w) {
                out.write(128 + runLength)
                out.write(data[runStart].toInt() and 0xFF)
                x = runStart + runLength
            }
        }
    }

    @JvmStatic
    fun toBytes(image: ImageF): ByteArray {
        try {
            val out = ByteArrayOutputStream()
            write(out, image)
            return out.toByteArray()
        } catch (e: IOException) {
            throw IllegalStateException("in-memory write failed", e)
        }
    }
}
