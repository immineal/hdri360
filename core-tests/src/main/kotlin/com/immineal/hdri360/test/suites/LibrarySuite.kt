package com.immineal.hdri360.test.suites

import com.immineal.hdri360.core.camera.Intrinsics
import com.immineal.hdri360.core.capture.CaptureTier
import com.immineal.hdri360.core.capture.CapturedFrame
import com.immineal.hdri360.core.capture.FrameStore
import com.immineal.hdri360.core.capture.SphereLibrary
import com.immineal.hdri360.core.capture.StoredSession
import com.immineal.hdri360.core.hdr.BracketPlan
import com.immineal.hdri360.core.hdr.DeviceExposureLimits
import com.immineal.hdri360.core.hdr.ExposureLadder
import com.immineal.hdri360.core.image.CfaPattern
import com.immineal.hdri360.core.image.ImageF
import com.immineal.hdri360.core.math.Mat3
import com.immineal.hdri360.test.TestCase
import com.immineal.hdri360.test.TestKit
import java.io.File

/**
 * The spheres on the phone, as something a person can find again.
 *
 * Captures are directories called `capture-1788604964095`. That is the right
 * name for a thing which has to survive the process being killed, and the wrong
 * one for a thing somebody wants to open next week - and until now the app could
 * only ever offer the most recent one, so a sphere shot the day before was on
 * the phone and unreachable.
 *
 * The rules worth testing are the ones about what happens to a name: that it
 * survives, that renaming replaces rather than accumulates, that clearing it
 * puts the capture back to being described by when it was shot rather than by an
 * empty string, and that a name somebody types cannot produce a file the export
 * path chokes on.
 */
class LibrarySuite : TestCase {
    override fun name(): String = "library"

    override fun run(t: TestKit) {
        inTemp("name") { root -> aNameSurvives(t, root) }
        inTemp("list") { root -> theListingIsWhatAPersonWouldExpect(t, root) }
        inTemp("file") { root -> aNameCanAlwaysBecomeAFilename(t, root) }
        inTemp("gone") { root -> deletingIsWholesale(t, root) }
        inTemp("dead") { root -> anAbandonedCaptureDoesNotLiveForever(t, root) }
        inTemp("raw") { root -> theSourceFramesCanBeSeenAndLetGo(t, root) }
    }

    // ---------------------------------------------------------------- fixtures

    private fun <R> inTemp(tag: String, body: (File) -> R): R {
        val f = File.createTempFile("hdri-lib-$tag", "")
        if (!f.delete()) throw IllegalStateException("cannot clear $f")
        if (!f.mkdirs()) throw IllegalStateException("cannot create $f")
        try {
            return body(f)
        } finally {
            wipe(f)
        }
    }

    private fun wipe(dir: File) {
        dir.listFiles()?.forEach { if (it.isDirectory) wipe(it) else it.delete() }
        dir.delete()
    }

    /** A capture directory of the shape the app actually leaves behind. */
    private fun capture(root: File, millis: Long, finished: Boolean,
                        report: String? = null, raw: Int = 0): File {
        val dir = File(root, "capture-$millis")
        dir.mkdirs()
        File(dir, "session.json").writeText("{}")
        File(dir, "frames.jsonl").writeText("{}\n")
        if (raw > 0) {
            val bundle = File(dir, "raw")
            bundle.mkdirs()
            for (i in 0 until raw) File(bundle, String.format("t%03d_b0.dng", i))
                .writeText("d".repeat(1000))
        }
        if (finished) {
            File(dir, "panorama.exr").writeText("x")
            File(dir, "processed").writeText("processed in 60.0 s at 8192 px")
            File(dir, "preview.jpg").writeText("x")
            if (report != null) File(dir, "report.json").writeText(report)
        }
        return dir
    }


    /** A capture directory with a real store in it, holding [complete] whole directions. */
    private fun startedCapture(root: File, millis: Long, complete: Int): File {
        val dir = File(root, "capture-$millis")
        dir.mkdirs()
        val limits = DeviceExposureLimits(1.0 / 17554, 16.0, 29, 7276, 29, 1.7, 1.0 / 15.0)
        val ladder = ExposureLadder.build(limits, 1.0 / 2000.0, 1.0 / 4.0, 2.0)
        val rungs = 3
        val session = StoredSession(
            cameraId = "0",
            tier = CaptureTier.LINEAR_RAW,
            intrinsics = Intrinsics.fromHorizontalFov(64, 48, 58.7),
            apertureN = 1.7,
            focalLengthMm = 4.44,
            sensorOrientationDeg = 90,
            cfa = CfaPattern.RGGB,
            whiteLevel = 1023,
            blackLevel = doubleArrayOf(64.0, 64.0, 64.0, 64.0),
            baseIso = 29,
            plan = BracketPlan(ladder, Array(4) { IntArray(rungs) { k -> k } }),
            note = "synthetic")
        val store = FrameStore.create(dir, session)
        try {
            for (target in 0 until complete) {
                for (k in 0 until rungs) {
                    store.store(CapturedFrame((target * 8 + k + 1).toLong(), target, k,
                        session.plan.settings(target, k), Mat3.IDENTITY, 1000L + k, true),
                        ImageF(4, 4, 1))
                }
            }
        } finally {
            store.close()
        }
        return dir
    }

    // ------------------------------------------------------------------- tests


    /**
     * A capture nobody can use is not kept forever.
     *
     * Until now nothing ever removed one. A capture that failed part way through
     * has a session header, so it is a capture; it has no sphere, so the library
     * will not list it; and once it is not the thing being resumed, nothing will
     * ever mention it again. It simply sits on the phone. Two of them were made
     * in one evening of a camera that would not deliver frames, and on that
     * evidence a person who has a bad week silently loses gigabytes with nothing
     * on any screen to say where they went.
     *
     * The rule is deliberately narrow, because the thing being deleted is
     * somebody's work: unfinished, **not one whole direction in it**, and old
     * enough that it cannot be the capture they are in the middle of. Anything
     * with a single complete direction is real data - it can be resumed, and it
     * can be stitched into a partial sphere - and is left alone however old.
     */
    private fun anAbandonedCaptureDoesNotLiveForever(t: TestKit, root: File) {
        val lib = SphereLibrary(root)
        val now = 1788700000000L
        val day = 24L * 60 * 60 * 1000

        val dead = startedCapture(root, now - 3 * day, complete = 0)
        val partial = startedCapture(root, now - 3 * day + 1, complete = 1)
        val fresh = startedCapture(root, now - 60_000, complete = 0)
        val finished = capture(root, now - 5 * day, true)
        dead.setLastModified(now - 3 * day)
        partial.setLastModified(now - 3 * day)
        fresh.setLastModified(now - 60_000)
        finished.setLastModified(now - 5 * day)

        val removed = lib.deleteAbandoned(now, day)

        t.eq(1L, removed.toLong(), "exactly one directory was worth removing")
        t.check(!dead.exists(), "a capture with no complete direction and days old is gone")
        t.check(partial.exists(),
            "one with a single whole direction stays - that is data, and it can be resumed")
        t.check(fresh.exists(),
            "and a capture from a minute ago stays whatever is in it: it may be in progress")
        t.check(finished.exists(), "a finished sphere is never touched by this")

        // Nothing that is not a capture is at risk, whatever it looks like.
        val stray = File(root, "capture-1")
        stray.mkdirs()
        File(stray, "notes.txt").writeText("x")
        stray.setLastModified(now - 30 * day)
        val scratch = File(root, "scratch")
        scratch.mkdirs()
        scratch.setLastModified(now - 30 * day)
        t.eq(0L, lib.deleteAbandoned(now, day).toLong(),
            "a directory with no session header is not a capture and is not deleted")
        t.check(stray.exists(), "so it survives")
        t.check(scratch.exists(), "and so does anything else living alongside")

        // Idempotent, and the listing is unchanged by any of it.
        t.eq(0L, lib.deleteAbandoned(now, day).toLong(), "a second sweep finds nothing")
        t.eq(1L, lib.list().size.toLong(), "the library still shows the one finished sphere")
    }


    /**
     * The raw frames a sphere was made from: visible, and removable on their own.
     *
     * A capture is gigabytes - 3.1 GB of DNGs for a real 34 direction sphere -
     * and the panorama that comes out of it is a few tens of megabytes. Once the
     * sphere is made and looked at, the frames are usually dead weight, and the
     * app kept them for ever with nothing on any screen even saying they were
     * there. Somebody wanting the space back had no way to find it and no way to
     * take it except deleting the sphere along with it.
     *
     * So an entry says what its frames cost and whether they are still there, and
     * they can be let go without touching the sphere - which is the whole point:
     * what is being freed is the thing that can be regenerated from nothing, and
     * what is kept is the thing that cannot.
     */
    private fun theSourceFramesCanBeSeenAndLetGo(t: TestKit, root: File) {
        val lib = SphereLibrary(root)
        val withRaw = capture(root, 1788604964095L, true, raw = 5)
        val withoutRaw = capture(root, 1788604964096L, true)

        var e = lib.entryFor(withRaw)
        if (e == null) { t.fail("a finished capture is an entry"); return }
        t.check(e.hasRawFrames, "a capture that still holds its frames says so")
        t.eq(5000L, e.rawBytes, "and says what they cost, to the byte")
        t.lessThan(e.rawBytes.toDouble(), e.bytesOnDisk.toDouble() + 1,
            "which is part of what the capture costs, not a separate bill")

        val bare = lib.entryFor(withoutRaw)
        if (bare == null) { t.fail("the other capture is an entry too"); return }
        t.check(!bare.hasRawFrames, "a capture whose frames are gone says that instead")
        t.eq(0L, bare.rawBytes, "and claims nothing for them")

        // Letting them go frees exactly what it said it would, and nothing else.
        val freed = lib.deleteRawFrames(withRaw)
        t.eq(5000L, freed, "removing them frees what the entry promised")
        t.check(!File(withRaw, "raw").exists(), "the bundle is gone")
        t.check(File(withRaw, "panorama.exr").isFile, "the sphere is not")
        t.check(File(withRaw, "session.json").isFile, "nor the header that makes it a capture")
        t.check(File(withRaw, "frames.jsonl").isFile,
            "nor the record of what was shot, which is small and is the only account " +
            "of where each frame was pointed")
        t.check(File(withRaw, "preview.jpg").isFile, "nor the thumbnail the library draws")

        e = lib.entryFor(withRaw)
        t.check(e != null && !e.hasRawFrames, "and the entry now says they are gone")
        t.eq(2L, lib.list().size.toLong(), "both spheres are still in the library")

        // Twice is not an error. Somebody tapping again, or two screens doing it
        // at once, must not produce a failure about a thing that is already true.
        t.eq(0L, lib.deleteRawFrames(withRaw), "doing it again frees nothing and says so")

        // The one case where it must refuse. Frames with no sphere yet made are
        // the only copy of the capture; deleting them is deleting the work, and
        // that is what deleting the whole capture is for.
        val unfinished = capture(root, 1788604964097L, false, raw = 3)
        t.eq(-1L, lib.deleteRawFrames(unfinished),
            "a capture with no sphere refuses: its frames are the only copy there is")
        t.check(File(unfinished, "raw").isDirectory, "so they are still there")
    }

    private fun aNameSurvives(t: TestKit, root: File) {
        val lib = SphereLibrary(root)
        val dir = capture(root, 1788604964095L, true)

        // Nothing is invented. An unnamed capture is unnamed, and the UI shows
        // when it was shot instead. A made-up default would be a name the person
        // has to notice is not theirs before they can trust any of the others.
        var entry = lib.entryFor(dir)
        if (entry == null) { t.fail("a finished capture is an entry"); return }
        t.check(entry.name == null, "a capture nobody named has no name")
        t.eq(1788604964095L, entry.capturedAtMillis,
            "and is dated from its own directory, not from when a file was last touched")

        t.check(lib.rename(dir, "Garten am Morgen"), "naming it succeeds")
        t.eq("Garten am Morgen", lib.entryFor(dir)?.name, "and the name comes back")

        // Renaming replaces. A name file that grew every time would read back as
        // every name the sphere had ever had.
        t.check(lib.rename(dir, "Garten"), "renaming succeeds")
        t.eq("Garten", lib.entryFor(dir)?.name, "the new name replaces the old one")

        // Surrounding space is a typing artefact, not part of the name.
        lib.rename(dir, "  Wohnzimmer, Abend  ")
        t.eq("Wohnzimmer, Abend", lib.entryFor(dir)?.name, "a name is trimmed")

        // Clearing puts the sphere back to being described by its date rather
        // than by an empty string, which is what a stored blank leaves on screen.
        t.check(lib.rename(dir, "   "), "clearing succeeds")
        t.check(lib.entryFor(dir)?.name == null, "a blank name is no name, not an empty one")
        t.check(!File(dir, SphereLibrary.NAME).exists(),
            "and nothing is left on disk to read back")

        // A newline would make the name file's own format ambiguous.
        lib.rename(dir, "Garten\nund Haus")
        t.eq("Garten und Haus", lib.entryFor(dir)?.name,
            "a name is one line, whatever was pasted into it")

        // Long enough to be a sentence is long enough to be a mistake, but it is
        // bounded rather than refused: refusing loses what was typed.
        lib.rename(dir, "x".repeat(400))
        val back = lib.entryFor(dir)?.name ?: ""
        t.check(back.length in 1..SphereLibrary.MAX_NAME_LENGTH,
            "an over-long name is bounded rather than refused or stored whole")
    }

    private fun theListingIsWhatAPersonWouldExpect(t: TestKit, root: File) {
        val lib = SphereLibrary(root)
        val old = capture(root, 1_000_000_000_000L, true,
            "{\"dynamicRangeStops\":9.0,\"framesPlaced\":34,\"framesTotal\":34," +
            "\"framesOnPriorAlone\":2,\"highlightsAreLowerBound\":true," +
            "\"colorSpace\":\"linear-rec709\"}")
        val newer = capture(root, 1_000_000_100_000L, true)
        val unfinished = capture(root, 1_000_000_200_000L, false)
        // Things in the same directory that are not captures at all.
        File(root, "work").mkdirs()
        File(root, "stray.txt").writeText("x")

        val all = lib.list()
        t.eq(2L, all.size.toLong(), "only captures with a sphere are listed")
        t.eq(newer.name, all[0].dir.name, "newest first, because that is what is being looked for")
        t.eq(old.name, all[1].dir.name, "then the older one")
        for (e in all) t.check(e.hasSphere, "everything listed has something to open")

        // The unfinished one is not lost, it is just not something to look at.
        t.check(lib.entryFor(unfinished)?.hasSphere == false,
            "a capture that never finished processing has no sphere to show")

        // What a row has to say for itself, so two spheres of the same room can
        // be told apart without opening both.
        val summary = all[1].summary
        t.check(summary != null, "a finished capture summarises itself from its own report")
        if (summary != null) {
            t.nearRel(9.0, summary.dynamicRangeStops, 1e-9, "the range it captured")
            t.eq(34L, summary.directions.toLong(), "how many directions went into it")
            t.eq(2L, summary.directionsOnPriorAlone.toLong(),
                "and how many of those the solve never reached")
            t.check(summary.highlightsAreLowerBound,
                "and whether the top of its range is a measurement or a bound")
        }
        t.check(all[0].summary == null, "a capture with no report says so rather than guessing")

        // Size, because deciding what to delete is most of why a list exists at
        // all: a sphere is three gigabytes of raw frames.
        t.greaterThan(all[0].bytesOnDisk.toDouble(), 0.0, "a row knows what it costs")
    }

    private fun aNameCanAlwaysBecomeAFilename(t: TestKit, root: File) {
        val lib = SphereLibrary(root)
        val dir = capture(root, 1788604964095L, true)

        // Unnamed, the directory is the only honest thing to call it.
        t.eq("capture-1788604964095", SphereLibrary.fileNameFor(lib.entryFor(dir)!!),
            "an unnamed sphere exports under the name it has")

        // Named, the export is called what the person called it, with everything
        // a filesystem would refuse taken out rather than the export failing.
        lib.rename(dir, "Garten / Haus: 5. Sep")
        val f = SphereLibrary.fileNameFor(lib.entryFor(dir)!!)
        t.check(f.isNotEmpty(), "a named sphere exports under its name")
        for (bad in charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|', ' '))
            t.check(!f.contains(bad), "the export name has no '$bad' in it")
        t.check(!f.startsWith("."), "and is not a hidden file")
        t.eq(f, f.trim(), "nor a padded one")

        // A name made entirely of characters a filesystem refuses still has to
        // export, or the sphere becomes unreachable by having been named.
        lib.rename(dir, "///")
        t.eq("capture-1788604964095", SphereLibrary.fileNameFor(lib.entryFor(dir)!!),
            "a name with nothing usable in it falls back to the directory")
    }

    private fun deletingIsWholesale(t: TestKit, root: File) {
        val lib = SphereLibrary(root)
        val dir = capture(root, 1788604964095L, true)
        File(dir, "raw").mkdirs()
        File(dir, "raw/t000_b0.dng").writeText("x")
        lib.rename(dir, "Garten")

        t.eq(1L, lib.list().size.toLong(), "it is there to begin with")
        t.check(lib.delete(dir), "deleting succeeds")
        t.check(!dir.exists(), "the whole directory goes, raw bundle included")
        t.eq(0L, lib.list().size.toLong(), "and it is out of the listing")
        t.check(lib.entryFor(dir) == null, "with nothing left to describe")
        // Deleting what is already gone is not a failure worth reporting.
        t.check(lib.delete(dir), "deleting what is not there is not an error")
    }
}
