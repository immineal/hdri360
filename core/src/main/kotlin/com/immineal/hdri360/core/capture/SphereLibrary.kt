package com.immineal.hdri360.core.capture

import com.immineal.hdri360.core.io.Json
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * The spheres on this device, as something a person can find again.
 *
 * A capture directory is called `capture-1788604964095`, which is the right name
 * for a thing that has to survive the process being killed and the wrong one for
 * a thing somebody wants to open next week. Until this existed the app could
 * only offer the single most recent capture, so a sphere shot the day before was
 * on the phone and unreachable.
 *
 * Deliberately platform-free, like the rest of the core: it is plain files and
 * plain strings, so every rule about what happens to a name is exercised on a
 * bare JVM rather than by tapping at a phone. The Android side is a list and a
 * text field over this.
 *
 * ## No invented names
 *
 * A capture nobody has named has no name, and [Entry.name] is null. The
 * alternative - filling it in with a date at capture time - produces a library
 * where every row looks named and the person has to read each one carefully to
 * find out that none of them are. What to show for an unnamed sphere is a
 * presentation question, and it is answered where the device's locale and clock
 * format live, not here.
 */
class SphereLibrary(private val root: File) {

    /** What a finished sphere says about itself, read from its own report. */
    class Summary(
        @JvmField val dynamicRangeStops: Double,
        @JvmField val directions: Int,
        @JvmField val directionsTotal: Int,
        /**
         * Directions the feature solve never reached, placed on the phone's own
         * orientation alone.
         *
         * In the list because it is the difference between a sphere to trust and
         * one to shoot again, and because a soft seam nobody can explain is worse
         * than a hole somebody can.
         */
        @JvmField val directionsOnPriorAlone: Int,
        /** True when the scene was brighter than the camera could read. */
        @JvmField val highlightsAreLowerBound: Boolean,
        @JvmField val colorSpace: String
    )

    /** One capture, as a row in a list. */
    class Entry(
        @JvmField val dir: File,
        /** What the person called it, or null if they never did. */
        @JvmField val name: String?,
        @JvmField val capturedAtMillis: Long,
        /** Whether there is a panorama to open, as opposed to frames on disk. */
        @JvmField val hasSphere: Boolean,
        @JvmField val bytesOnDisk: Long,
        /**
         * What the raw frames this sphere was made from still cost, or zero once
         * they have been let go.
         *
         * Part of [bytesOnDisk] rather than on top of it. It is called out on its
         * own because it is almost all of it - 3.1 GB of DNGs against a few tens
         * of megabytes of sphere on a real 34 direction capture - and because it
         * is the only part somebody can free without losing anything they cannot
         * make again.
         */
        @JvmField val rawBytes: Long,
        /** Null when the capture never got far enough to write a report. */
        @JvmField val summary: Summary?
    ) {
        /** The small JPEG the processing wrote, or null if there is none. */
        val thumbnail: File? get() = File(dir, PREVIEW).takeIf { it.isFile }

        /** Whether the frames are still on the phone. */
        val hasRawFrames: Boolean get() = rawBytes > 0
    }

    /** Every capture with a sphere in it, newest first. */
    fun list(): List<Entry> {
        val dirs = root.listFiles() ?: return emptyList()
        val out = ArrayList<Entry>()
        for (d in dirs) {
            val e = entryFor(d) ?: continue
            if (e.hasSphere) out.add(e)
        }
        // Newest first: what somebody opening a library is looking for is almost
        // always the thing they just shot.
        out.sortWith(compareByDescending<Entry> { it.capturedAtMillis }.thenBy { it.dir.name })
        return out
    }

    /**
     * One capture, or null if [dir] is not one.
     *
     * A session header is what makes a directory a capture. The scratch
     * directories the processing leaves behind, and anything else that happens to
     * be alongside them, are not captures and must not appear as empty rows.
     */
    fun entryFor(dir: File): Entry? {
        if (!dir.isDirectory) return null
        if (!File(dir, FrameStore.SESSION).isFile) return null
        val done = File(dir, DONE).isFile && File(dir, PANORAMA).isFile
        return Entry(dir, nameOf(dir), capturedAtOf(dir), done, bytesOf(dir),
            bytesOf(File(dir, RAW)),
            if (done) summaryOf(dir) else null)
    }

    /**
     * Names, renames, or clears the name of a capture.
     *
     * A blank name clears it rather than storing an empty string, because an
     * empty string is a name that renders as nothing and cannot be told from a
     * bug. Returns false only when the write itself failed.
     */
    fun rename(dir: File, name: String?): Boolean {
        if (!dir.isDirectory) return false
        val clean = clean(name)
        val file = File(dir, NAME)
        return try {
            if (clean == null) {
                // Gone is gone; a file that is not there is the absence of a name.
                if (file.exists() && !file.delete()) return false
            } else {
                val part = File(dir, "$NAME.part")
                part.writeText(clean, StandardCharsets.UTF_8)
                if (!part.renameTo(file)) {
                    if (!file.delete() || !part.renameTo(file)) {
                        part.delete()
                        return false
                    }
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Removes a capture and everything in it, raw bundle included.
     *
     * Wholesale on purpose. Half a capture is not a smaller capture, it is a
     * directory that will confuse whoever finds it - and the raw frames are the
     * bulk of what somebody deleting a sphere is trying to reclaim.
     *
     * Deleting what is not there succeeds: the caller wanted it gone.
     */
    /**
     * Removes the raw frames a finished sphere was made from, and returns how many
     * bytes that freed - zero if there were none, and **-1 if it refused**.
     *
     * It refuses when there is no sphere yet. Frames with nothing made from them
     * are the only copy of the capture, and throwing them away is throwing away
     * the work; that is what deleting the whole capture is for, and it should say
     * so in those words rather than happening as a side effect of tidying up.
     *
     * What is kept is everything small: the session header, the record of where
     * each frame was pointed, the report, the thumbnail and the sphere itself.
     * The sphere cannot be made again without the frames, so the frames go and
     * the sphere stays - which is the way round that costs nothing to be wrong
     * about, since the frames can always be shot again and the moment cannot.
     */
    fun deleteRawFrames(dir: File): Long {
        val entry = entryFor(dir) ?: return -1
        if (!entry.hasSphere) return -1
        val bundle = File(dir, RAW)
        if (!bundle.exists()) return 0
        val was = bytesOf(bundle)
        if (!removeTree(bundle)) return bytesOf(bundle).let { was - it }
        return was
    }

    fun delete(dir: File): Boolean {
        if (!dir.exists()) return true
        if (!dir.isDirectory) return dir.delete()
        return removeTree(dir)
    }

    /**
     * Removes captures that failed early enough to be worth nothing, and returns
     * how many went.
     *
     * Nothing used to remove one. A capture that stopped part way through has a
     * session header, so it is a capture; it has no sphere, so [list] will never
     * show it; and once it is not the one being resumed, nothing mentions it
     * again. It just sits on the phone. One evening of a camera that would not
     * deliver frames left two of them, and on that evidence somebody who has a
     * bad week quietly loses gigabytes with nothing on any screen to say where.
     *
     * The rule is narrow on purpose, because what is being deleted is somebody's
     * work and there is no undo:
     *
     *  - unfinished, so a sphere is never at risk;
     *  - **not one whole direction in it**, so anything that could be resumed or
     *    stitched into a partial sphere stays, however old;
     *  - and older than [olderThanMillis], so it cannot be the capture they are
     *    standing in the middle of.
     *
     * A directory without a session header is not a capture and is never touched,
     * whatever it is called.
     */
    fun deleteAbandoned(now: Long, olderThanMillis: Long): Int {
        val dirs = root.listFiles() ?: return 0
        var gone = 0
        for (d in dirs) {
            if (!d.isDirectory) continue
            if (!File(d, FrameStore.SESSION).isFile) continue
            if (File(d, DONE).isFile) continue
            if (now - d.lastModified() < olderThanMillis) continue
            if (holdsAWholeDirection(d)) continue
            if (delete(d)) gone++
        }
        return gone
    }

    /** Whether any one direction in [dir] has every rung its plan asks for. */
    private fun holdsAWholeDirection(dir: File): Boolean {
        val store = try { FrameStore.open(dir) } catch (e: Exception) { null }
        // Unreadable is not the same as empty. A capture whose header will not
        // parse is one this cannot judge, so it is left alone.
            ?: return true
        return try {
            store.shotMask().any { it }
        } catch (e: Exception) {
            true
        } finally {
            try { store.close() } catch (e: Exception) { /* nothing left to do about it */ }
        }
    }

    private fun removeTree(f: File): Boolean {
        var ok = true
        if (f.isDirectory) f.listFiles()?.forEach { if (!removeTree(it)) ok = false }
        return f.delete() && ok
    }

    private fun nameOf(dir: File): String? {
        val f = File(dir, NAME)
        if (!f.isFile) return null
        return try {
            clean(String(f.readBytes(), StandardCharsets.UTF_8))
        } catch (e: Exception) {
            null
        }
    }

    /**
     * When the capture was made, from the name the app gave its directory.
     *
     * Not [File.lastModified]: processing rewrites files in there long after the
     * shutter, and a library sorted by that reorders itself behind the person's
     * back. The directory name is the one timestamp that records the capture.
     */
    private fun capturedAtOf(dir: File): Long {
        val n = dir.name
        if (n.startsWith(PREFIX)) {
            val millis = n.substring(PREFIX.length).toLongOrNull()
            if (millis != null && millis > 0) return millis
        }
        return dir.lastModified()
    }

    private fun bytesOf(f: File): Long {
        if (f.isFile) return f.length()
        var n = 0L
        f.listFiles()?.forEach { n += bytesOf(it) }
        return n
    }

    private fun summaryOf(dir: File): Summary? {
        val f = File(dir, REPORT)
        if (!f.isFile) return null
        return try {
            val o = Json.parse(String(f.readBytes(), StandardCharsets.UTF_8)) as Json.Obj
            Summary(
                dynamicRangeStops = if (o.has("dynamicRangeStops"))
                    o["dynamicRangeStops"].asDouble() else 0.0,
                directions = if (o.has("framesPlaced")) o["framesPlaced"].asDouble().toInt() else 0,
                directionsTotal = if (o.has("framesTotal")) o["framesTotal"].asDouble().toInt() else 0,
                directionsOnPriorAlone = if (o.has("framesOnPriorAlone"))
                    o["framesOnPriorAlone"].asDouble().toInt() else 0,
                highlightsAreLowerBound = o.has("highlightsAreLowerBound") &&
                    o["highlightsAreLowerBound"].asBoolean(),
                colorSpace = if (o.has("colorSpace")) o["colorSpace"].asString() else "unknown")
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        /** Where the DNG bundle is written during a capture. */
        const val RAW = "raw"

        const val NAME = "name.txt"
        const val PREVIEW = "preview.jpg"
        const val PANORAMA = "panorama.exr"
        const val REPORT = "report.json"
        const val DONE = "processed"
        private const val PREFIX = "capture-"

        /**
         * Longest name kept. Generous enough for a sentence, bounded because the
         * name goes on one line of a list and into a filename.
         */
        const val MAX_NAME_LENGTH = 80

        /**
         * One line, trimmed, bounded - or null if there is nothing left.
         *
         * Newlines go because the name file's own format would otherwise be
         * ambiguous, and because a name is a label rather than a note.
         */
        @JvmStatic
        fun clean(raw: String?): String? {
            if (raw == null) return null
            var s = raw.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ')
            s = s.replace(Regex("\\s+"), " ").trim()
            if (s.isEmpty()) return null
            if (s.length > MAX_NAME_LENGTH) s = s.substring(0, MAX_NAME_LENGTH).trim()
            return s.ifEmpty { null }
        }

        /**
         * What to call the file when this sphere is exported.
         *
         * The person's own name for it, with everything a filesystem or a sharing
         * target would refuse taken out. Falls back to the directory rather than
         * failing: a sphere must not become unreachable by having been named.
         */
        @JvmStatic
        fun fileNameFor(entry: Entry): String {
            val n = entry.name ?: return entry.dir.name
            val sb = StringBuilder(n.length)
            for (c in n) {
                sb.append(when {
                    c.isLetterOrDigit() -> c
                    c == '-' || c == '_' -> c
                    c == ' ' || c == ',' || c == '.' -> '-'
                    else -> '-'
                })
            }
            var out = sb.toString().replace(Regex("-+"), "-").trim('-', '.')
            if (out.length > MAX_NAME_LENGTH) out = out.substring(0, MAX_NAME_LENGTH).trim('-', '.')
            return out.ifEmpty { entry.dir.name }
        }
    }
}
