package com.immineal.hdri360.core.io

import java.io.OutputStream
import java.nio.charset.StandardCharsets

/** Byte helpers. EXR is little-endian throughout. */
internal object LittleEndian {

    fun writeInt(out: OutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 24) and 0xFF)
    }

    fun writeLong(out: OutputStream, v: Long) {
        for (i in 0 until 8) out.write(((v ushr (8 * i)) and 0xFF).toInt())
    }

    fun writeFloat(out: OutputStream, v: Float) {
        writeInt(out, java.lang.Float.floatToIntBits(v))
    }

    fun writeAscii(out: OutputStream, s: String) {
        out.write(s.toByteArray(StandardCharsets.US_ASCII))
    }

    fun writeNullTerminated(out: OutputStream, s: String) {
        writeAscii(out, s)
        out.write(0)
    }

    fun readInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
        ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

    fun readLong(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        return v
    }
}
