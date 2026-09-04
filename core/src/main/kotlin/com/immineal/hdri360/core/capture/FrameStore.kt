package com.immineal.hdri360.core.capture

import com.immineal.hdri360.core.camera.Intrinsics
import com.immineal.hdri360.core.hdr.BracketPlan
import com.immineal.hdri360.core.hdr.ExposureLadder
import com.immineal.hdri360.core.hdr.ExposureSettings
import com.immineal.hdri360.core.image.CfaPattern
import com.immineal.hdri360.core.image.ImageF
import com.immineal.hdri360.core.io.Half
import com.immineal.hdri360.core.io.Json
import com.immineal.hdri360.core.math.Mat3
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Everything a capture needs to know about itself in order to be resumed.
 *
 * Written once, before the first frame. The bracket plan is in here for a
 * reason that is not convenience: an interrupted capture must resume on the
 * ladder it started with, because re-planning would put the second half of the
 * sphere on a different radiance scale from the first.
 */
class StoredSession(
    @JvmField val cameraId: String,
    @JvmField val tier: CaptureTier,
    @JvmField val intrinsics: Intrinsics,
    @JvmField val apertureN: Double,
    @JvmField val focalLengthMm: Double,
    @JvmField val sensorOrientationDeg: Int,
    @JvmField val cfa: CfaPattern,
    @JvmField val whiteLevel: Int,
    @JvmField val blackLevel: DoubleArray,
    @JvmField val baseIso: Int,
    @JvmField val plan: BracketPlan,
    @JvmField val note: String = "",
    /**
     * Per-channel white balance the camera measured before the capture locked to
     * manual, or null if none was available.
     *
     * A gain is linear, so applying it leaves radiance as radiance - but it has
     * to be one gain for the whole sphere, measured once, or every direction
     * lands on a different colour scale.
     */
    @JvmField val neutralGains: DoubleArray? = null
) {

    fun toJson(): Json.Obj {
        val steps = Json.Arr()
        for (s in plan.ladder.steps) {
            steps.add(Json.Obj()
                .put("t", s.exposureTimeSec)
                .put("iso", s.iso.toLong())
                .put("f", s.apertureN))
        }
        val perTarget = Json.Arr()
        for (run in plan.indicesPerTarget) {
            val a = Json.Arr()
            for (i in run) a.add(i.toDouble())
            perTarget.add(a)
        }
        return Json.Obj()
            .put("format", FORMAT)
            .put("camera", cameraId)
            .put("tier", tier.name)
            .put("note", note)
            .put("apertureF", apertureN)
            .put("focalLengthMm", focalLengthMm)
            .put("sensorOrientationDeg", sensorOrientationDeg.toLong())
            .put("cfa", cfa.name)
            .put("whiteLevel", whiteLevel.toLong())
            .put("blackLevel", blackLevel)
            .put("baseIso", baseIso.toLong())
            .put("imageWidth", intrinsics.width.toLong())
            .put("imageHeight", intrinsics.height.toLong())
            .put("fx", intrinsics.fx).put("fy", intrinsics.fy)
            .put("cx", intrinsics.cx).put("cy", intrinsics.cy)
            .put("k1", intrinsics.k1).put("k2", intrinsics.k2).put("k3", intrinsics.k3)
            .put("ladder", steps)
            .put("ladderBaseIso", plan.ladder.baseIso.toLong())
            .put("ladderClampedLow", plan.ladder.clampedLow)
            .put("ladderClampedHigh", plan.ladder.clampedHigh)
            .put("indicesPerTarget", perTarget)
            .also { o -> neutralGains?.let { o.put("neutralGains", it) } }
    }

    companion object {
        const val FORMAT = "hdri360-capture-2"

        @JvmStatic
        fun fromJson(o: Json.Obj): StoredSession {
            if (!o.has("format") || o["format"].asString() != FORMAT)
                throw IOException("not an HDRI360 capture session")
            val stepsJson = o["ladder"]
            val steps = ArrayList<ExposureSettings>(stepsJson.size())
            for (i in 0 until stepsJson.size()) {
                val s = stepsJson.at(i)
                steps.add(ExposureSettings(s["t"].asDouble(),
                    s["iso"].asDouble().toInt(), s["f"].asDouble()))
            }
            val ladder = ExposureLadder.of(steps, o["ladderBaseIso"].asDouble().toInt(),
                o["ladderClampedLow"].asBoolean(), o["ladderClampedHigh"].asBoolean())
            val runsJson = o["indicesPerTarget"]
            val runs = Array(runsJson.size()) { r ->
                val a = runsJson.at(r)
                IntArray(a.size()) { k -> a.at(k).asDouble().toInt() }
            }
            val blackJson = o["blackLevel"]
            val black = DoubleArray(blackJson.size()) { blackJson.at(it).asDouble() }
            return StoredSession(
                cameraId = o["camera"].asString(),
                tier = CaptureTier.valueOf(o["tier"].asString()),
                intrinsics = Intrinsics(
                    o["imageWidth"].asDouble().toInt(), o["imageHeight"].asDouble().toInt(),
                    o["fx"].asDouble(), o["fy"].asDouble(),
                    o["cx"].asDouble(), o["cy"].asDouble(),
                    o["k1"].asDouble(), o["k2"].asDouble(), o["k3"].asDouble()),
                apertureN = o["apertureF"].asDouble(),
                focalLengthMm = o["focalLengthMm"].asDouble(),
                sensorOrientationDeg = o["sensorOrientationDeg"].asDouble().toInt(),
                cfa = CfaPattern.valueOf(o["cfa"].asString()),
                whiteLevel = o["whiteLevel"].asDouble().toInt(),
                blackLevel = black,
                baseIso = o["baseIso"].asDouble().toInt(),
                plan = BracketPlan(ladder, runs),
                note = if (o.has("note")) o["note"].asString() else "",
                neutralGains = if (o.has("neutralGains")) {
                    val g = o["neutralGains"]
                    DoubleArray(g.size()) { g.at(it).asDouble() }
                } else null)
        }
    }
}

/** One frame that is safely on disk, as the journal recorded it. */
class FrameRecord(
    @JvmField val targetIndex: Int,
    @JvmField val bracketIndex: Int,
    @JvmField val settings: ExposureSettings,
    @JvmField val pose: Mat3?,
    @JvmField val width: Int,
    @JvmField val height: Int,
    @JvmField val channels: Int,
    /** CFA ordinal for a Bayer plane, or -1 for interleaved RGB. */
    @JvmField val cfaOrdinal: Int,
    @JvmField val linear: Boolean,
    @JvmField val timestampNs: Long,
    @JvmField val file: String
) {
    /** Bytes the working file must be if it was written completely. */
    fun expectedBytes(): Long = FrameStore.HEADER_BYTES + 2L * width * height * channels

    fun toJson(): Json.Obj {
        val o = Json.Obj()
            .put("target", targetIndex.toLong())
            .put("bracket", bracketIndex.toLong())
            .put("t", settings.exposureTimeSec)
            .put("iso", settings.iso.toLong())
            .put("f", settings.apertureN)
            .put("w", width.toLong()).put("h", height.toLong()).put("c", channels.toLong())
            .put("cfa", cfaOrdinal.toLong())
            .put("linear", linear)
            .put("ts", timestampNs)
            .put("file", file)
        if (pose != null) o.put("pose", pose.data())
        return o
    }

    companion object {
        @JvmStatic
        fun fromJson(o: Json.Obj): FrameRecord {
            var pose: Mat3? = null
            if (o.has("pose")) {
                val a = o["pose"]
                if (a.size() == 9) pose = Mat3(DoubleArray(9) { a.at(it).asDouble() })
            }
            return FrameRecord(
                o["target"].asDouble().toInt(),
                o["bracket"].asDouble().toInt(),
                ExposureSettings(o["t"].asDouble(), o["iso"].asDouble().toInt(), o["f"].asDouble()),
                pose,
                o["w"].asDouble().toInt(), o["h"].asDouble().toInt(), o["c"].asDouble().toInt(),
                o["cfa"].asDouble().toInt(),
                o["linear"].asBoolean(),
                o["ts"].asDouble().toLong(),
                o["file"].asString())
        }
    }
}

/**
 * Frames go to disk the moment they arrive, and the record of them goes down
 * with each one.
 *
 * A full sphere is thirty-odd directions times several exposures; held in memory
 * as decoded pixels that is gigabytes, and the process would be killed long
 * before the sweep finished. So the interesting question is not how to store a
 * frame but what the directory looks like after the process dies mid-capture,
 * which on a phone during a several-minute capture is the ordinary outcome
 * rather than the exotic one.
 *
 * Two rules make that survivable, and both are tested rather than asserted:
 *
 *  - **A frame file is complete or absent.** It is written to a `.part` file,
 *    forced to disk, and only then renamed into place. A crash leaves debris
 *    that is recognisable as debris.
 *  - **The journal is append-only and written after the rename.** One line per
 *    frame, forced to disk, never rewritten - so every line that is fully
 *    present refers to a file that was already fully present when it was
 *    written. A half-written last line is simply dropped.
 *
 * The predecessor wrote its manifest once, at the end. A capture killed at
 * frame 140 left 140 files and nothing that said what any of them were.
 */
class FrameStore private constructor(
    @JvmField val dir: File,
    @JvmField val session: StoredSession
) : FrameSink, Closeable {

    /** Refuse to write a frame that would take free space below this. */
    @JvmField var minFreeBytes: Long = 64L * 1024 * 1024

    /**
     * How much room is left. Injectable because the branch that matters - the
     * one taken when the disk is nearly full - is not otherwise reachable in a
     * test, and it is the branch that decides whether a capture ends gracefully
     * or halfway through a write.
     */
    @JvmField var freeSpace: () -> Long = { dir.usableSpace }

    /** Why the last frame was not stored, for the UI to show. */
    @JvmField @Volatile var lastError: String? = null

    private val journalFile = File(dir, JOURNAL)
    private var journal: FileOutputStream? = null
    /**
     * Set when a torn tail could not be trimmed, so the first append separates
     * itself from the garbage rather than fusing with it.
     */
    private var needsSeparator = false
    private val byKey = LinkedHashMap<Long, FrameRecord>()
    private val lock = Any()
    private val partSeq = java.util.concurrent.atomic.AtomicLong()

    fun records(): List<FrameRecord> = synchronized(lock) { ArrayList(byKey.values) }

    /**
     * Which directions are finished, judged by the frames actually on disk.
     *
     * A direction counts only when every rung of its bracket is present. A
     * partly-shot direction is worse than an unshot one - it would merge from an
     * incomplete ladder - so it is reported as not done and shot again.
     */
    fun shotMask(): BooleanArray = synchronized(lock) {
        val runs = session.plan.indicesPerTarget
        BooleanArray(runs.size) { t ->
            var all = runs[t].isNotEmpty()
            for (k in runs[t].indices) if (!byKey.containsKey(key(t, k))) all = false
            all
        }
    }

    /** Frames stored so far, counting only complete ones. */
    fun frameCount(): Int = synchronized(lock) { byKey.size }

    override fun store(frame: CapturedFrame, pixels: ImageF): Boolean {
        val name = String.format(Locale.US, "t%03d_b%d%s",
            frame.targetIndex, frame.bracketIndex, SUFFIX)
        val target = File(dir, name)
        // A part name of this writer's own. The camera thread and a retry can
        // both deliver the same direction and rung at once; through one shared
        // name each would truncate the file the other was renaming.
        val part = File(dir, "$name.${partSeq.incrementAndGet()}$PART")
        val needed = HEADER_BYTES + 2L * pixels.data.size

        // A reported zero is "cannot tell", not "full": a bogus zero must not be
        // allowed to stop a capture that would otherwise have succeeded.
        val free = try { freeSpace() } catch (e: Exception) { 0L }
        if (free > 0 && free < minFreeBytes + needed) {
            lastError = "not enough room to store this frame: " +
                "${free / (1024 * 1024)} MB free, ${(minFreeBytes + needed) / (1024 * 1024)} MB needed"
            return false
        }

        val cfaOrdinal = if (pixels.channels == 1 && frame.linear) session.cfa.ordinal else -1
        val record = FrameRecord(frame.targetIndex, frame.bracketIndex, frame.settings,
            frame.poseAtCapture, pixels.width, pixels.height, pixels.channels, cfaOrdinal,
            frame.linear, frame.timestampNs, name)

        try {
            writePlane(part, pixels, cfaOrdinal)
            // Rename after the bytes are on the platter: a file under its final
            // name is a file the journal is allowed to point at.
            if (!part.renameTo(target)) {
                // Renaming over an existing file fails on some filesystems, so
                // clearing the way is worth one try - but only while our own
                // bytes are still there to put in its place. Deleting the target
                // when they are not turns a failed write into a lost frame.
                if (!part.isFile || !target.delete() || !part.renameTo(target))
                    throw IOException("could not put $name into place")
            }
            appendJournal(record)
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            part.delete()
            return false
        }
        synchronized(lock) { byKey[key(frame.targetIndex, frame.bracketIndex)] = record }
        lastError = null
        return true
    }

    fun read(record: FrameRecord): ImageF = readPlane(File(dir, record.file))

    /** Removes the working copies once the HDRI has been written. The bundle stays. */
    fun deleteWorkingFiles() {
        synchronized(lock) {
            for (r in byKey.values) File(dir, r.file).delete()
        }
    }

    override fun close() {
        synchronized(lock) {
            try { journal?.close() } catch (e: IOException) { /* nothing left to do about it */ }
            journal = null
        }
    }

    private fun appendJournal(record: FrameRecord) {
        synchronized(lock) {
            var out = journal
            if (out == null) {
                out = FileOutputStream(journalFile, true)
                journal = out
            }
            val text = if (needsSeparator) "\n" + record.toJson() + "\n"
                       else record.toJson().toString() + "\n"
            needsSeparator = false
            val line = text.toByteArray(StandardCharsets.UTF_8)
            out.write(line)
            out.flush()
            try { out.fd.sync() } catch (e: IOException) { /* best effort; the rename already ordered it */ }
        }
    }

    // ------------------------------------------------------------------ files

    companion object {
        /** "HRF2": magic, width, height, channels, CFA ordinal. */
        const val MAGIC = 0x48524632
        const val HEADER_BYTES = 20L
        const val JOURNAL = "frames.jsonl"
        const val SESSION = "session.json"
        private const val SUFFIX = ".hrf"
        private const val PART = ".part"

        private fun key(target: Int, bracket: Int): Long = target.toLong() * 4096 + bracket

        /**
         * Starts a new session in [dir]. Refuses to write over an existing one:
         * a session directory is the only record of a capture, and silently
         * clobbering it would be the one bug that cannot be recovered from.
         */
        @JvmStatic
        fun create(dir: File, session: StoredSession): FrameStore {
            if (!dir.mkdirs() && !dir.isDirectory)
                throw IOException("cannot create $dir")
            val header = File(dir, SESSION)
            if (header.exists())
                throw IllegalStateException("$dir already holds a capture session")
            val tmp = File(dir, "$SESSION$PART")
            FileOutputStream(tmp).use {
                it.write(session.toJson().toString().toByteArray(StandardCharsets.UTF_8))
                it.flush()
                try { it.fd.sync() } catch (e: IOException) { }
            }
            if (!tmp.renameTo(header)) throw IOException("cannot write $header")
            return FrameStore(dir, session)
        }

        /**
         * Reopens an interrupted capture, keeping only what is genuinely there.
         *
         * Returns null when [dir] holds no session at all. A session whose
         * journal is damaged is not a failure - it is the normal shape of a
         * capture that was killed - so the readable prefix is kept and the rest
         * discarded.
         */
        @JvmStatic
        fun open(dir: File): FrameStore? {
            val header = File(dir, SESSION)
            if (!header.isFile) return null
            val session = try {
                StoredSession.fromJson(
                    Json.parse(String(header.readBytes(), StandardCharsets.UTF_8)) as Json.Obj)
            } catch (e: Exception) {
                return null
            }
            val store = FrameStore(dir, session)

            // Debris from a write that never finished. It is not a frame and it is
            // not going to become one.
            dir.listFiles()?.forEach { if (it.name.endsWith(PART)) it.delete() }

            val journal = File(dir, JOURNAL)
            if (journal.isFile) {
                val bytes = journal.readBytes()
                var start = 0
                for (i in bytes.indices) {
                    if (bytes[i] != '\n'.code.toByte()) continue
                    // Only complete lines count. A trailing fragment - the shape a
                    // kill mid-write leaves - never gets one, so it is never read.
                    val line = String(bytes, start, i - start, StandardCharsets.UTF_8).trim()
                    start = i + 1
                    if (line.isEmpty()) continue
                    val record = try {
                        FrameRecord.fromJson(Json.parse(line) as Json.Obj)
                    } catch (e: Exception) {
                        continue
                    }
                    // A journal line is a claim about a file. Check the claim: the
                    // file has to be there, and it has to be the whole thing.
                    val f = File(dir, record.file)
                    if (!f.isFile || f.length() != record.expectedBytes()) continue
                    store.byKey[key(record.targetIndex, record.bracketIndex)] = record
                }
                // Trim the torn tail. Without this the next frame's line would be
                // appended onto half a record and both would be lost - the capture
                // would resume and then quietly fail to record anything it shot.
                if (start < bytes.size) {
                    val trimmed = try {
                        RandomAccessFile(journal, "rw").use { it.setLength(start.toLong()) }
                        true
                    } catch (e: Exception) {
                        false
                    }
                    if (!trimmed) store.needsSeparator = true
                }
            }
            return store
        }

        /**
         * Half float on disk: the values are normalised to [0,1], where half gives
         * about three decimal digits - far finer than the sensor's own noise - at
         * half the size of float.
         */
        @JvmStatic
        fun writePlane(file: File, image: ImageF, cfaOrdinal: Int) {
            val out = FileOutputStream(file)
            try {
                val d = DataOutputStream(BufferedOutputStream(out, 1 shl 16))
                d.writeInt(MAGIC)
                d.writeInt(image.width)
                d.writeInt(image.height)
                d.writeInt(image.channels)
                d.writeInt(cfaOrdinal)
                for (v in image.data) d.writeShort(Half.fromFloat(v).toInt())
                d.flush()
                try { out.fd.sync() } catch (e: IOException) { }
            } finally {
                out.close()
            }
        }

        @JvmStatic
        fun readPlane(file: File): ImageF {
            DataInputStream(BufferedInputStream(FileInputStream(file), 1 shl 16)).use { d ->
                if (d.readInt() != MAGIC) throw IOException("not an HDRI360 working frame: $file")
                val w = d.readInt(); val h = d.readInt(); val c = d.readInt()
                d.readInt()
                if (w <= 0 || h <= 0 || c <= 0) throw IOException("corrupt frame header in $file")
                val image = ImageF(w, h, c)
                for (i in image.data.indices) image.data[i] = Half.toFloat(d.readShort())
                return image
            }
        }

        @JvmStatic
        fun readCfaOrdinal(file: File): Int {
            DataInputStream(FileInputStream(file)).use { d ->
                if (d.readInt() != MAGIC) throw IOException("not an HDRI360 working frame: $file")
                d.readInt(); d.readInt(); d.readInt()
                return d.readInt()
            }
        }
    }
}
