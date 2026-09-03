package com.immineal.hdri360.core.io

import com.immineal.hdri360.core.image.ImageF
import java.nio.charset.StandardCharsets
import java.util.Locale

/** Reader for the .hdr files the writer produces, used to verify them independently. */
object RadianceHdrReader {

    @JvmStatic
    fun read(bytes: ByteArray): ImageF {
        var p = 0
        val first = readLine(bytes, p)
        if (!first.startsWith("#?")) throw IllegalArgumentException("not a Radiance file")
        p += first.length + 1
        var rgbe = false
        while (true) {
            val line = readLine(bytes, p)
            p += line.length + 1
            if (line.isEmpty()) break
            if (line.uppercase(Locale.US).contains("32-BIT_RLE_RGBE")) rgbe = true
        }
        if (!rgbe) throw IllegalArgumentException("unsupported Radiance format")

        val res = readLine(bytes, p)
        p += res.length + 1
        val parts = res.trim().split(Regex("\\s+"))
        if (parts.size != 4 || parts[0] != "-Y" || parts[2] != "+X")
            throw IllegalArgumentException("unsupported Radiance resolution line: $res")
        val h = parts[1].toInt()
        val w = parts[3].toInt()

        val out = ImageF(w, h, 3)
        val scan = Array(4) { ByteArray(w) }
        for (y in 0 until h) {
            val rle = w >= 8 && w <= 0x7FFF &&
                    (bytes[p].toInt() and 0xFF) == 2 && (bytes[p + 1].toInt() and 0xFF) == 2 &&
                    ((((bytes[p + 2].toInt() and 0xFF) shl 8) or (bytes[p + 3].toInt() and 0xFF)) == w)
            if (rle) {
                p += 4
                for (c in 0 until 4) {
                    var x = 0
                    while (x < w) {
                        val count = bytes[p++].toInt() and 0xFF
                        if (count > 128) {
                            val v = bytes[p++]
                            for (i in 0 until count - 128) scan[c][x++] = v
                        } else {
                            for (i in 0 until count) scan[c][x++] = bytes[p++]
                        }
                    }
                }
            } else {
                for (x in 0 until w)
                    for (c in 0 until 4) scan[c][x] = bytes[p++]
            }
            for (x in 0 until w) {
                val e = scan[3][x].toInt() and 0xFF
                val base = (y * w + x) * 3
                if (e == 0) continue
                val f = Math.scalb(1.0, e - (128 + 8))
                out.data[base] = (((scan[0][x].toInt() and 0xFF) + 0.5) * f).toFloat()
                out.data[base + 1] = (((scan[1][x].toInt() and 0xFF) + 0.5) * f).toFloat()
                out.data[base + 2] = (((scan[2][x].toInt() and 0xFF) + 0.5) * f).toFloat()
            }
        }
        return out
    }

    private fun readLine(b: ByteArray, p: Int): String {
        var e = p
        while (e < b.size && b[e] != '\n'.code.toByte()) e++
        return String(b, p, e - p, StandardCharsets.US_ASCII)
    }
}
