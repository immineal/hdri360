package com.immineal.hdri360.core.pipeline

import com.immineal.hdri360.core.image.ImageF
import com.immineal.hdri360.core.pano.FrameOptics
import com.immineal.hdri360.core.pano.FrameSet
import com.immineal.hdri360.core.pano.FrameSource
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Where the pipeline puts each merged direction, and where the composite reads
 * them back from.
 *
 * The two implementations differ in one thing only: whether the radiance stays
 * in memory. Everything else - the order frames are written in, the optics they
 * end up with, the results that come out - is the same, which is what makes it
 * possible to check the spooled path against the resident one and require them
 * to agree exactly.
 */
interface MergedFrames : FrameSet {

    /**
     * Records the merged radiance for direction [i]. Safe to call from several
     * worker threads at once, one per index.
     */
    fun put(i: Int, radiance: ImageF, confidence: FloatArray?, optics: FrameOptics)

    /** Replaces the pose, lens and gain of frame [i] once they have been solved. */
    fun setOptics(i: Int, optics: FrameOptics)
}

/** Merged frames held in memory. Only workable when the whole sphere fits. */
class ResidentFrames(count: Int) : MergedFrames {
    private val radiance = arrayOfNulls<ImageF>(count)
    private val confidence = arrayOfNulls<FloatArray>(count)
    private val optics = arrayOfNulls<FrameOptics>(count)

    override val size: Int get() = radiance.size

    override fun put(i: Int, radiance: ImageF, confidence: FloatArray?, optics: FrameOptics) {
        this.radiance[i] = radiance
        this.confidence[i] = confidence
        this.optics[i] = optics
    }

    override fun setOptics(i: Int, optics: FrameOptics) { this.optics[i] = optics }

    override fun optics(i: Int): FrameOptics =
        optics[i] ?: throw IllegalStateException("frame $i has not been merged yet")

    override fun open(i: Int): FrameSource {
        val o = optics(i)
        return FrameSource(radiance[i]!!, o.intrinsics, o.rotation, confidence[i], o.gain)
    }
}

/**
 * Merged frames parked in a directory, read back one at a time.
 *
 * This is what makes a full sphere processable on a phone. Thirty-two
 * directions of merged radiance is well over a gigabyte, against a heap of half
 * of one; the alternative to writing them down was to shrink every frame until
 * the whole sphere fitted, which meant working at an eighth of the sensor and
 * throwing away three quarters of the resolution in each axis - to hold data
 * that is only ever read one frame at a time.
 *
 * The files are scratch, not output: [close] deletes them. They are plain float
 * rather than half so that a spooled run is bit for bit a resident one, which is
 * a property the suite checks rather than assumes.
 */
class FrameSpool(private val dir: File, count: Int) : MergedFrames, Closeable {

    private val optics = arrayOfNulls<FrameOptics>(count)
    private val shape = arrayOfNulls<IntArray>(count)     // width, height, channels, hasConfidence

    // One frame stays decoded, so opening the same direction twice in a row - which
    // is what a render banded into strips does - does not read it twice.
    private var cachedIndex = -1
    private var cached: FrameSource? = null

    init {
        if (!dir.isDirectory && !dir.mkdirs())
            throw IOException("could not create the working directory $dir")
    }

    override val size: Int get() = optics.size

    override fun optics(i: Int): FrameOptics =
        optics[i] ?: throw IllegalStateException("frame $i has not been merged yet")

    override fun setOptics(i: Int, optics: FrameOptics) {
        this.optics[i] = optics
        if (cachedIndex == i) { cachedIndex = -1; cached = null }
    }

    override fun put(i: Int, radiance: ImageF, confidence: FloatArray?, optics: FrameOptics) {
        val file = fileFor(i)
        val part = File(file.path + ".part")
        RandomAccessFile(part, "rw").use { raf ->
            raf.setLength(0)
            val ch = raf.channel
            val header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            header.putInt(MAGIC)
            header.putInt(radiance.width)
            header.putInt(radiance.height)
            header.putInt(radiance.channels)
            header.putInt(if (confidence != null) 1 else 0)
            header.flip()
            while (header.hasRemaining()) ch.write(header)
            writeFloats(ch, radiance.data)
            if (confidence != null) writeFloats(ch, confidence)
        }
        if (!part.renameTo(file)) {
            part.delete()
            throw IOException("could not park the merged frame $i")
        }
        this.optics[i] = optics
        shape[i] = intArrayOf(radiance.width, radiance.height, radiance.channels,
            if (confidence != null) 1 else 0)
    }

    override fun open(i: Int): FrameSource {
        if (cachedIndex == i) return cached!!
        cachedIndex = -1
        cached = null
        val o = optics(i)
        val file = fileFor(i)
        val frame = RandomAccessFile(file, "r").use { raf ->
            val ch = raf.channel
            val header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            while (header.hasRemaining()) if (ch.read(header) < 0) throw IOException("short $file")
            header.flip()
            if (header.int != MAGIC) throw IOException("not a parked frame: $file")
            val w = header.int
            val h = header.int
            val c = header.int
            val hasConfidence = header.int != 0
            val radiance = ImageF(w, h, c)
            readFloats(ch, radiance.data)
            val confidence = if (hasConfidence) FloatArray(w * h).also { readFloats(ch, it) } else null
            FrameSource(radiance, o.intrinsics, o.rotation, confidence, o.gain)
        }
        cachedIndex = i
        cached = frame
        return frame
    }

    /** Bytes this frame occupies on disk, for a free-space check before starting. */
    fun bytesOnDisk(): Long {
        var n = 0L
        for (i in shape.indices) n += fileFor(i).length()
        return n
    }

    override fun close() {
        cachedIndex = -1
        cached = null
        for (i in optics.indices) {
            fileFor(i).delete()
            File(fileFor(i).path + ".part").delete()
        }
        dir.delete()
    }

    private fun fileFor(i: Int) = File(dir, String.format(java.util.Locale.US, "merged-%04d.bin", i))

    private fun writeFloats(ch: FileChannel, data: FloatArray) {
        val buf = ByteBuffer.allocateDirect(CHUNK_FLOATS * 4).order(ByteOrder.LITTLE_ENDIAN)
        var i = 0
        while (i < data.size) {
            val n = Math.min(CHUNK_FLOATS, data.size - i)
            buf.clear()
            buf.asFloatBuffer().put(data, i, n)
            buf.limit(n * 4)
            while (buf.hasRemaining()) ch.write(buf)
            i += n
        }
    }

    private fun readFloats(ch: FileChannel, data: FloatArray) {
        val buf = ByteBuffer.allocateDirect(CHUNK_FLOATS * 4).order(ByteOrder.LITTLE_ENDIAN)
        var i = 0
        while (i < data.size) {
            val n = Math.min(CHUNK_FLOATS, data.size - i)
            buf.clear()
            buf.limit(n * 4)
            while (buf.hasRemaining()) if (ch.read(buf) < 0) throw IOException("parked frame truncated")
            buf.flip()
            buf.asFloatBuffer().get(data, i, n)
            i += n
        }
    }

    companion object {
        private const val MAGIC = 0x484D5246          // "HMRF"
        private const val HEADER_BYTES = 20
        private const val CHUNK_FLOATS = 1 shl 18     // 1 MB at a time

        /** What parking a sphere of this shape will cost on disk. */
        @JvmStatic
        fun bytesNeeded(count: Int, pixels: Long, channels: Int): Long =
            count.toLong() * (HEADER_BYTES + pixels * 4L * (channels + 1))
    }
}
