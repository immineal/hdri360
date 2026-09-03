package com.immineal.hdri360.core.io

import com.immineal.hdri360.core.image.ImageF
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.zip.Deflater

/**
 * Minimal OpenEXR scanline writer: half float, RGB, no compression or ZIPS.
 *
 * Written by hand rather than pulled from a library because the app ships with
 * no third-party dependencies and the scanline format is small. Channels are
 * emitted in the alphabetical order the spec requires (B, G, R), which readers
 * rely on.
 */
object ExrWriter {

    enum class Compression(@JvmField internal val code: Int) {
        NONE(0), ZIPS(2)
    }

    @JvmStatic
    fun write(out: OutputStream, image: ImageF, compression: Compression) {
        if (image.channels < 3) throw IllegalArgumentException("EXR output expects at least RGB")
        val w = image.width
        val h = image.height
        val headerBytes = buildHeader(w, h, compression)

        // Build every scanline block first so the offset table can be exact.
        val chunks = arrayOfNulls<ByteArray>(h)
        for (y in 0 until h) {
            val raw = rawScanline(image, y)
            var payload = raw
            if (compression == Compression.ZIPS) {
                val packed = zipCompress(raw)
                // The spec says to store raw data when compression does not help.
                if (packed.size < raw.size) payload = packed
            }
            val chunk = ByteArrayOutputStream()
            LittleEndian.writeInt(chunk, y)
            LittleEndian.writeInt(chunk, payload.size)
            chunk.write(payload)
            chunks[y] = chunk.toByteArray()
        }

        var offset = headerBytes.size + 8L * h
        val table = ByteArrayOutputStream()
        for (y in 0 until h) {
            LittleEndian.writeLong(table, offset)
            offset += chunks[y]!!.size
        }

        out.write(headerBytes)
        out.write(table.toByteArray())
        for (c in chunks) out.write(c!!)
        out.flush()
    }

    /** The complete header, up to and including its terminating zero byte. */
    @JvmStatic
    internal fun buildHeader(w: Int, h: Int, compression: Compression): ByteArray {
        val header = ByteArrayOutputStream()
        LittleEndian.writeInt(header, 20000630)           // magic 0x01312f76
        LittleEndian.writeInt(header, 2)                  // version 2, no flags

        // channels: alphabetical order is mandatory
        val chlist = ByteArrayOutputStream()
        for (name in arrayOf("B", "G", "R")) {
            LittleEndian.writeNullTerminated(chlist, name)
            LittleEndian.writeInt(chlist, 1)              // HALF
            chlist.write(0)                               // pLinear
            chlist.write(0); chlist.write(0); chlist.write(0)   // reserved
            LittleEndian.writeInt(chlist, 1)              // xSampling
            LittleEndian.writeInt(chlist, 1)              // ySampling
        }
        chlist.write(0)                                   // end of channel list
        attribute(header, "channels", "chlist", chlist.toByteArray())

        attribute(header, "compression", "compression", byteArrayOf(compression.code.toByte()))

        val box = ByteArrayOutputStream()
        LittleEndian.writeInt(box, 0); LittleEndian.writeInt(box, 0)
        LittleEndian.writeInt(box, w - 1); LittleEndian.writeInt(box, h - 1)
        attribute(header, "dataWindow", "box2i", box.toByteArray())
        attribute(header, "displayWindow", "box2i", box.toByteArray())

        attribute(header, "lineOrder", "lineOrder", byteArrayOf(0))   // INCREASING_Y

        val f = ByteArrayOutputStream()
        LittleEndian.writeFloat(f, 1.0f)
        attribute(header, "pixelAspectRatio", "float", f.toByteArray())

        val v2 = ByteArrayOutputStream()
        LittleEndian.writeFloat(v2, 0f); LittleEndian.writeFloat(v2, 0f)
        attribute(header, "screenWindowCenter", "v2f", v2.toByteArray())

        val sw = ByteArrayOutputStream()
        LittleEndian.writeFloat(sw, 1.0f)
        attribute(header, "screenWindowWidth", "float", sw.toByteArray())

        header.write(0)                                   // end of header
        return header.toByteArray()
    }

    /** One scanline, channels in alphabetical order, half floats, little-endian. */
    @JvmStatic
    internal fun rawScanline(image: ImageF, y: Int): ByteArray {
        val w = image.width
        val ch = image.channels
        val raw = ByteArray(w * 3 * 2)
        val sourceChannel = intArrayOf(2, 1, 0)           // B, G, R
        var p = 0
        for (c in 0 until 3) {
            val src = sourceChannel[c]
            for (x in 0 until w) {
                val hv = Half.fromFloat(image.data[(y * w + x) * ch + src])
                raw[p++] = (hv.toInt() and 0xFF).toByte()
                raw[p++] = ((hv.toInt() ushr 8) and 0xFF).toByte()
            }
        }
        return raw
    }

    /**
     * EXR's ZIP preconditioning: de-interleave the bytes into two halves, then
     * delta-encode, then deflate. The two steps together turn half-float pixel
     * data - where the high bytes change slowly and the low bytes are noise -
     * into something zlib can actually compress.
     */
    @JvmStatic
    internal fun zipCompress(raw: ByteArray): ByteArray {
        val tmp = ByteArray(raw.size)
        val half = (raw.size + 1) / 2
        var t1 = 0
        var t2 = half
        var s = 0
        while (true) {
            if (s < raw.size) tmp[t1++] = raw[s++] else break
            if (s < raw.size) tmp[t2++] = raw[s++] else break
        }
        var previous = if (raw.isNotEmpty()) (tmp[0].toInt() and 0xFF) else 0
        for (i in 1 until tmp.size) {
            val cur = tmp[i].toInt() and 0xFF
            val d = cur - previous + (128 + 256)
            previous = cur
            tmp[i] = d.toByte()
        }
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
        deflater.setInput(tmp)
        deflater.finish()
        val out = ByteArrayOutputStream(tmp.size)
        val buf = ByteArray(8192)
        while (!deflater.finished()) {
            val n = deflater.deflate(buf)
            out.write(buf, 0, n)
        }
        deflater.end()
        return out.toByteArray()
    }

    private fun attribute(out: OutputStream, name: String, type: String, data: ByteArray) {
        LittleEndian.writeNullTerminated(out, name)
        LittleEndian.writeNullTerminated(out, type)
        LittleEndian.writeInt(out, data.size)
        out.write(data)
    }
}
