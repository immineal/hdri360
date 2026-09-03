package com.immineal.hdri360.core.io

import com.immineal.hdri360.core.image.ImageF
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.OutputStream

/**
 * Writes an OpenEXR file a few scanlines at a time.
 *
 * The scanline offset table has to come before the pixel data but cannot be
 * filled in until every chunk's length is known, so the chunks are buffered and
 * the whole file is emitted on close. That still keeps peak memory to the
 * compressed pixel data - a fraction of the uncompressed float panorama - and
 * lets the renderer discard each strip as soon as it is written.
 *
 * The output is byte-identical to [ExrWriter] for the same pixels.
 */
class ExrStreamWriter(
    private val out: OutputStream,
    private val width: Int,
    private val height: Int,
    private val compression: ExrWriter.Compression
) : Closeable {

    private val chunks = ArrayList<ByteArray>()
    private var rowsWrittenCount = 0
    private var closed = false

    init {
        if (width <= 0 || height <= 0) throw IllegalArgumentException("bad dimensions")
    }

    fun rowsWritten(): Int = rowsWrittenCount

    /** Appends a strip. Its width must match, and its rows follow the ones already written. */
    fun writeRows(strip: ImageF) {
        if (closed) throw IllegalStateException("writer already closed")
        if (strip.width != width) throw IllegalArgumentException("strip width does not match the file")
        if (strip.channels < 3) throw IllegalArgumentException("EXR output expects at least RGB")
        if (rowsWrittenCount + strip.height > height)
            throw IllegalArgumentException("more rows than the file holds")

        for (y in 0 until strip.height) {
            val raw = ExrWriter.rawScanline(strip, y)
            var payload = raw
            if (compression == ExrWriter.Compression.ZIPS) {
                val packed = ExrWriter.zipCompress(raw)
                if (packed.size < raw.size) payload = packed
            }
            val chunk = ByteArrayOutputStream()
            LittleEndian.writeInt(chunk, rowsWrittenCount + y)
            LittleEndian.writeInt(chunk, payload.size)
            chunk.write(payload)
            chunks.add(chunk.toByteArray())
        }
        rowsWrittenCount += strip.height
    }

    override fun close() {
        if (closed) return
        if (rowsWrittenCount != height)
            throw IllegalStateException(
                "only " + rowsWrittenCount + " of " + height + " rows were written")
        closed = true
        val header = ExrWriter.buildHeader(width, height, compression)
        var offset = header.size + 8L * height
        val table = ByteArrayOutputStream()
        for (c in chunks) {
            LittleEndian.writeLong(table, offset)
            offset += c.size
        }
        out.write(header)
        out.write(table.toByteArray())
        for (c in chunks) out.write(c)
        out.flush()
    }
}
