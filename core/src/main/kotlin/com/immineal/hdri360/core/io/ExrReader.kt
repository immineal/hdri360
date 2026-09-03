package com.immineal.hdri360.core.io

import com.immineal.hdri360.core.image.ImageF
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Reader for the subset of OpenEXR this app writes.
 *
 * It exists so the writer can be verified against a genuinely independent
 * decode rather than against itself, and so a capture bundle can be re-opened
 * for re-stitching without any external dependency.
 */
object ExrReader {

    @JvmStatic
    fun read(bytes: ByteArray): ImageF {
        if (bytes.size < 8 || LittleEndian.readInt(bytes, 0) != 20000630)
            throw IllegalArgumentException("not an OpenEXR file")
        var p = 8

        val channelNames = ArrayList<String>()
        var compression = -1
        var xMin = 0
        var yMin = 0
        var xMax = -1
        var yMax = -1

        while (true) {
            if (p >= bytes.size) throw IllegalArgumentException("truncated EXR header")
            if (bytes[p].toInt() == 0) { p++; break }
            var nameEnd = p
            while (bytes[nameEnd].toInt() != 0) nameEnd++
            val name = String(bytes, p, nameEnd - p, StandardCharsets.US_ASCII)
            p = nameEnd + 1
            var typeEnd = p
            while (bytes[typeEnd].toInt() != 0) typeEnd++
            p = typeEnd + 1
            val size = LittleEndian.readInt(bytes, p)
            p += 4
            val dataStart = p

            if (name == "channels") {
                var q = dataStart
                while (bytes[q].toInt() != 0) {
                    var e = q
                    while (bytes[e].toInt() != 0) e++
                    channelNames.add(String(bytes, q, e - q, StandardCharsets.US_ASCII))
                    q = e + 1
                    val pixelType = LittleEndian.readInt(bytes, q)
                    if (pixelType != 1) throw IllegalArgumentException("only HALF channels are supported")
                    q += 4 + 4 + 4 + 4         // pixelType, pLinear+reserved, xSampling, ySampling
                }
            } else if (name == "compression") {
                compression = bytes[dataStart].toInt() and 0xFF
            } else if (name == "dataWindow") {
                xMin = LittleEndian.readInt(bytes, dataStart)
                yMin = LittleEndian.readInt(bytes, dataStart + 4)
                xMax = LittleEndian.readInt(bytes, dataStart + 8)
                yMax = LittleEndian.readInt(bytes, dataStart + 12)
            }
            p = dataStart + size
        }

        if (xMax < xMin || yMax < yMin) throw IllegalArgumentException("bad data window")
        if (compression != 0 && compression != 2)
            throw IllegalArgumentException("unsupported EXR compression $compression")
        val w = xMax - xMin + 1
        val h = yMax - yMin + 1
        val nch = channelNames.size
        if (nch < 3) throw IllegalArgumentException("expected at least three channels")

        val offsets = LongArray(h)
        for (y in 0 until h) {
            offsets[y] = LittleEndian.readLong(bytes, p)
            p += 8
        }

        val out = ImageF(w, h, 3)
        val rawSize = w * nch * 2
        for (line in 0 until h) {
            val q = offsets[line].toInt()
            val y = LittleEndian.readInt(bytes, q)
            val dataSize = LittleEndian.readInt(bytes, q + 4)
            val payload = ByteArray(dataSize)
            System.arraycopy(bytes, q + 8, payload, 0, dataSize)
            val raw = if (compression == 2 && dataSize < rawSize) zipDecompress(payload, rawSize)
                      else payload
            if (raw.size < rawSize) throw IllegalArgumentException("short scanline")

            val row = y - yMin
            for (c in 0 until nch) {
                val name = channelNames[c]
                val target = if (name == "R") 0 else if (name == "G") 1 else if (name == "B") 2 else -1
                val base = c * w * 2
                if (target < 0) continue
                for (x in 0 until w) {
                    val lo = raw[base + 2 * x].toInt() and 0xFF
                    val hi = raw[base + 2 * x + 1].toInt() and 0xFF
                    out.data[(row * w + x) * 3 + target] = Half.toFloat(((hi shl 8) or lo).toShort())
                }
            }
        }
        return out
    }

    /** Undo deflate, then the delta predictor, then the byte de-interleave. */
    @JvmStatic
    internal fun zipDecompress(packed: ByteArray, rawSize: Int): ByteArray {
        val tmp: ByteArray
        try {
            val inflater = Inflater()
            inflater.setInput(packed)
            val bos = ByteArrayOutputStream(rawSize)
            val buf = ByteArray(8192)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                bos.write(buf, 0, n)
            }
            inflater.end()
            tmp = bos.toByteArray()
        } catch (e: DataFormatException) {
            throw IllegalArgumentException("corrupt EXR scanline", e)
        }
        for (i in 1 until tmp.size) {
            val d = (tmp[i - 1].toInt() and 0xFF) + (tmp[i].toInt() and 0xFF) - 128
            tmp[i] = d.toByte()
        }
        val raw = ByteArray(tmp.size)
        val half = (tmp.size + 1) / 2
        var t1 = 0
        var t2 = half
        var s = 0
        while (true) {
            if (s < raw.size) raw[s++] = tmp[t1++] else break
            if (s < raw.size) raw[s++] = tmp[t2++] else break
        }
        return raw
    }
}
