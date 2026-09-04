package com.immineal.hdri360.test.suites

import com.immineal.hdri360.core.camera.Intrinsics
import com.immineal.hdri360.core.capture.CapturedFrame
import com.immineal.hdri360.core.capture.CaptureTier
import com.immineal.hdri360.core.capture.FrameStore
import com.immineal.hdri360.core.capture.StoredSession
import com.immineal.hdri360.core.hdr.BracketPlan
import com.immineal.hdri360.core.hdr.DeviceExposureLimits
import com.immineal.hdri360.core.hdr.ExposureLadder
import com.immineal.hdri360.core.hdr.ExposureSettings
import com.immineal.hdri360.core.image.CfaPattern
import com.immineal.hdri360.core.image.ImageF
import com.immineal.hdri360.core.io.Half
import com.immineal.hdri360.core.math.SO3
import com.immineal.hdri360.core.math.Vec3
import com.immineal.hdri360.test.TestCase
import com.immineal.hdri360.test.TestKit
import java.io.File
import java.io.RandomAccessFile

/**
 * The store has one job that only matters when things go wrong: after the
 * process dies mid-sphere, what is on disk must be exactly the frames that were
 * safely written, and the capture must be able to pick up from there.
 *
 * The predecessor wrote its manifest once, at the end. A capture killed at frame
 * 140 - which is the normal outcome of a long capture on a phone, not an
 * exotic one - left 140 orphaned files and no way to tell what they were. There
 * was no test because there was no way to write one: the store was welded to
 * Camera2 types. Here it is plain files, so the crash can simply be simulated.
 */
class FrameStoreSuite : TestCase {

    override fun name(): String = "framestore"

    override fun run(t: TestKit) {
        roundTrip(t)
        appendOnlyJournal(t)
        tornJournalLine(t)
        missingAndTruncatedFrames(t)
        partialWritesNeverSurface(t)
        planSurvivesExactly(t)
        refusesWhenFull(t)
        survivesADisappearingDirectory(t)
        twoWritersOneFrame(t)
    }

    // ---------------------------------------------------------------- fixtures

    private fun session(targets: Int = 4, rungs: Int = 3): StoredSession {
        val limits = DeviceExposureLimits(1.0 / 17554, 16.0, 29, 7276, 29, 1.7, 1.0 / 15.0)
        val ladder = ExposureLadder.build(limits, 1.0 / 2000.0, 1.0 / 4.0, 2.0)
        val idx = Array(targets) { IntArray(rungs) { k -> k } }
        return StoredSession(
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
            plan = BracketPlan(ladder, idx),
            note = "synthetic"
        )
    }

    private fun frame(s: StoredSession, target: Int, k: Int) = CapturedFrame(
        burstId = (target * 8 + k + 1).toLong(),
        targetIndex = target,
        bracketIndex = k,
        settings = s.plan.settings(target, k),
        poseAtCapture = SO3.exp(Vec3(0.0, Math.toRadians(target * 30.0), 0.0)),
        timestampNs = 1_000_000L * (target * 8 + k),
        linear = true
    )

    /** A pattern that survives half precision exactly, so equality can be asserted. */
    private fun pixels(seed: Int): ImageF {
        val im = ImageF(8, 6, 1)
        for (i in im.data.indices) im.data[i] = Half.toFloat(Half.fromFloat(((seed * 31 + i) % 97) / 97.0f))
        return im
    }

    private fun tempDir(tag: String): File {
        val f = File.createTempFile("hdri-$tag", "")
        if (!f.delete()) throw IllegalStateException("cannot clear $f")
        return f
    }

    private fun wipe(dir: File) {
        dir.listFiles()?.forEach { if (it.isDirectory) wipe(it) else it.delete() }
        dir.delete()
    }

    private fun <R> inTemp(tag: String, body: (File) -> R): R {
        val dir = tempDir(tag)
        try {
            return body(dir)
        } finally {
            wipe(dir)
        }
    }

    // ------------------------------------------------------------------ tests

    /** The plain path: what went in comes back out, pixels included. */
    private fun roundTrip(t: TestKit) = inTemp("round") { dir ->
        val s = session()
        val store = FrameStore.create(dir, s)
        for (target in 0 until 2)
            for (k in 0 until 3)
                t.check(store.store(frame(s, target, k), pixels(target * 3 + k)),
                    "frame ($target,$k) was stored")
        store.close()

        val back = FrameStore.open(dir) ?: run { t.fail("the session did not reopen"); return@inTemp }
        t.eq(6L, back.records().size.toLong(), "every stored frame is in the journal")

        val r = back.records()[4]
        t.eq(1L, r.targetIndex.toLong(), "the fifth frame belongs to the second direction")
        t.eq(1L, r.bracketIndex.toLong(), "and to the second rung")
        t.near(s.plan.settings(1, 1).exposureTimeSec, r.settings.exposureTimeSec, 0.0,
            "its exposure came back bit-identical")
        t.eq(s.plan.settings(1, 1).iso.toLong(), r.settings.iso.toLong(), "as did its ISO")
        t.check(r.pose != null, "the pose at capture was kept")

        val expected = pixels(4)
        val actual = back.read(r)
        t.eq(expected.width.toLong(), actual.width.toLong(), "the frame kept its width")
        t.eq(expected.channels.toLong(), actual.channels.toLong(), "and its channel count")
        var worst = 0.0
        for (i in expected.data.indices)
            worst = Math.max(worst, Math.abs(expected.data[i] - actual.data[i]).toDouble())
        t.near(0.0, worst, 0.0, "half float storage is exact for values that are already half")
        t.eq(CfaPattern.RGGB.ordinal.toLong(), r.cfaOrdinal.toLong(),
            "a single channel linear frame is recorded as Bayer, with its pattern")

        val mask = back.shotMask()
        t.eq(4L, mask.size.toLong(), "the mask covers every planned direction")
        t.check(mask[0] && mask[1], "the two complete directions count as shot")
        t.check(!mask[2] && !mask[3], "the untouched ones do not")
        back.close()
    }

    /**
     * The journal may only ever be appended to. If closing, or a later frame,
     * rewrote earlier bytes then a crash could destroy frames already safe.
     */
    private fun appendOnlyJournal(t: TestKit) = inTemp("append") { dir ->
        val s = session()
        val store = FrameStore.create(dir, s)
        store.store(frame(s, 0, 0), pixels(0))
        store.store(frame(s, 0, 1), pixels(1))
        val journal = File(dir, "frames.jsonl")
        val prefix = journal.readBytes()
        t.greaterThan(prefix.size.toDouble(), 0.0, "the journal is on disk before the capture ends")

        store.store(frame(s, 0, 2), pixels(2))
        store.close()
        val after = journal.readBytes()
        t.greaterThan(after.size.toDouble(), prefix.size.toDouble(), "later frames extend it")
        var same = true
        for (i in prefix.indices) if (prefix[i] != after[i]) same = false
        t.check(same, "and never rewrite a byte of what was already there")
        t.eq('\n'.code.toLong(), after[after.size - 1].toLong(),
            "the journal ends on a record boundary, so a reader can trust its last line")
    }

    /**
     * A process killed mid-write leaves half a line. That line must be dropped
     * and everything before it kept - and the store must be able to carry on
     * appending afterwards.
     */
    private fun tornJournalLine(t: TestKit) = inTemp("torn") { dir ->
        val s = session()
        val store = FrameStore.create(dir, s)
        for (k in 0 until 3) store.store(frame(s, 0, k), pixels(k))
        store.close()

        val journal = File(dir, "frames.jsonl")
        val full = journal.length()
        val lastLineStart = journal.readBytes().let { b ->
            var i = b.size - 2
            while (i >= 0 && b[i] != '\n'.code.toByte()) i--
            (i + 1).toLong()
        }
        // Cut the final record in half, exactly as an interrupted write would.
        RandomAccessFile(journal, "rw").use { it.setLength((lastLineStart + full) / 2) }

        val back = FrameStore.open(dir) ?: run { t.fail("a torn journal made the session unreadable"); return@inTemp }
        t.eq(2L, back.records().size.toLong(), "the torn record is dropped and the rest survive")
        t.check(!back.shotMask()[0], "so the direction is not reported as finished")

        t.check(back.store(frame(s, 0, 2), pixels(2)), "and the capture can continue")
        back.close()
        val again = FrameStore.open(dir)!!
        t.eq(3L, again.records().size.toLong(), "the retried frame lands cleanly after the truncation")
        t.check(again.shotMask()[0], "and completes the direction")
        again.close()
    }

    /** A journal entry is only worth as much as the file it points at. */
    private fun missingAndTruncatedFrames(t: TestKit) = inTemp("missing") { dir ->
        val s = session()
        val store = FrameStore.create(dir, s)
        for (k in 0 until 3) store.store(frame(s, 0, k), pixels(k))
        store.close()

        val files = FrameStore.open(dir)!!.let { it.records().map { r -> File(dir, r.file) }.also { _ -> it.close() } }
        t.check(files[0].delete(), "the first frame's file is removed, as a failed write would leave it")
        RandomAccessFile(files[1], "rw").use { it.setLength(it.length() - 7) }

        val back = FrameStore.open(dir)!!
        t.eq(1L, back.records().size.toLong(),
            "a record whose file is missing or short is not a captured frame")
        t.eq(2L, back.records()[0].bracketIndex.toLong(), "the intact one is the one that survives")
        back.close()
    }

    /** A frame file appears complete or not at all; never half written. */
    private fun partialWritesNeverSurface(t: TestKit) = inTemp("partial") { dir ->
        val s = session()
        val store = FrameStore.create(dir, s)
        store.store(frame(s, 0, 0), pixels(0))
        store.close()
        // Leave the debris an interrupted write would leave behind.
        File(dir, "t000_b1.hrf.part").writeBytes(ByteArray(64))

        val back = FrameStore.open(dir)!!
        t.eq(1L, back.records().size.toLong(), "a leftover part file is not mistaken for a frame")
        t.check(!File(dir, "t000_b1.hrf.part").exists(), "and is cleaned up on open")
        back.close()
    }

    /**
     * A resumed capture must shoot its second half on the same ladder as its
     * first. A re-planned ladder would put the two halves on different radiance
     * scales, which no amount of later gain solving fully repairs.
     */
    private fun planSurvivesExactly(t: TestKit) = inTemp("plan") { dir ->
        val s = session(targets = 6, rungs = 4)
        FrameStore.create(dir, s).close()
        val back = FrameStore.open(dir)!!
        val a = s.plan
        val b = back.session.plan
        t.eq(a.ladder.size().toLong(), b.ladder.size().toLong(), "the ladder came back the same length")
        t.eq(a.ladder.baseIso.toLong(), b.ladder.baseIso.toLong(), "on the same base ISO")
        for (i in 0 until a.ladder.size()) {
            t.near(a.ladder.steps[i].exposureTimeSec, b.ladder.steps[i].exposureTimeSec, 0.0,
                "rung $i kept its exposure to the last bit")
            t.eq(a.ladder.steps[i].iso.toLong(), b.ladder.steps[i].iso.toLong(), "rung $i kept its ISO")
            t.near(a.ladder.steps[i].apertureN, b.ladder.steps[i].apertureN, 0.0, "rung $i kept its aperture")
        }
        t.eq(a.totalShots().toLong(), b.totalShots().toLong(), "and the same number of shots")
        t.eq(6L, b.indicesPerTarget.size.toLong(), "for the same number of directions")
        t.near(s.intrinsics.fx, back.session.intrinsics.fx, 0.0, "the camera model came back unchanged")
        t.eq(s.tier, back.session.tier, "as did the tier, which decides what the output may claim")
        t.eq(s.cfa, back.session.cfa, "and the CFA pattern needed to read the frames back")
        back.close()
    }

    /**
     * Running out of space must fail the frame, not the capture. Returning false
     * makes the controller retry the direction; throwing would lose the sphere.
     */
    private fun refusesWhenFull(t: TestKit) = inTemp("full") { dir ->
        val s = session()
        val store = FrameStore.create(dir, s)
        store.minFreeBytes = 100L * 1024 * 1024
        store.freeSpace = { 4L * 1024 * 1024 }
        t.check(!store.store(frame(s, 0, 0), pixels(0)), "a frame is refused when the disk is nearly full")
        t.check(store.lastError != null, "and the reason is available to show the user")
        t.eq(0L, (dir.listFiles()?.count { it.name.endsWith(".hrf") } ?: 0).toLong(),
            "nothing was left behind by the refusal")

        store.freeSpace = { 8L * 1024 * 1024 * 1024 }
        t.check(store.store(frame(s, 0, 0), pixels(0)), "and it works again once there is room")
        store.close()
    }

    /** Removable storage can be pulled out mid-capture. That is a false, not a crash. */
    private fun survivesADisappearingDirectory(t: TestKit) = inTemp("gone") { dir ->
        val s = session()
        val store = FrameStore.create(dir, s)
        t.check(store.store(frame(s, 0, 0), pixels(0)), "the first frame is stored normally")
        wipe(dir)
        var threw = false
        var ok = true
        try {
            ok = store.store(frame(s, 0, 1), pixels(1))
        } catch (e: Exception) {
            threw = true
        }
        t.check(!threw, "storage vanishing under the capture does not throw")
        t.check(!ok, "it reports the frame as not stored")
        store.close()
    }

    /**
     * Two writers, one frame.
     *
     * A burst that timed out and the retry that replaced it can both deliver the
     * same direction and rung, from the camera thread and from the retry's, at
     * once. Both went through one shared ".part" name: one writer truncated the
     * file the other was about to rename, and the rename that then failed took
     * the recovery branch - which deleted the good frame that had just landed,
     * failed anyway, and left the journal pointing at nothing. That is the one
     * shape of loss a journalled store exists to prevent.
     *
     * What must hold is not that both writes win. It is that one of them does,
     * whole: the file is there after every store that claimed success, it reads
     * back, and it is one writer's pixels rather than a splice of both.
     */
    private fun twoWritersOneFrame(t: TestKit) = inTemp("race") { dir ->
        val s = session()
        val store = FrameStore.create(dir, s)
        val values = floatArrayOf(0.25f, 0.75f)   // both exact in half
        fun flat(v: Float): ImageF {
            val im = ImageF(8, 6, 1)
            java.util.Arrays.fill(im.data, v)
            return im
        }
        val trouble = java.util.Collections.synchronizedList(ArrayList<String>())
        val file = File(dir, "t001_b0.hrf")
        val threads = (0 until 2).map { w ->
            Thread {
                for (i in 0 until 60) {
                    val ok = try {
                        store.store(frame(s, 1, 0), flat(values[w]))
                    } catch (e: Exception) {
                        trouble.add("store threw $e"); false
                    }
                    if (!ok) trouble.add("store refused: " + store.lastError)
                    else if (!file.isFile) trouble.add("the frame was gone after a successful store")
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        t.eq(0L, trouble.size.toLong(),
            "concurrent writers of one frame all succeed: " + trouble.take(3))

        val back = store.read(store.records().first())
        var spliced = false
        for (v in back.data) if (v != back.data[0]) spliced = true
        t.check(!spliced, "and the frame on disk is one writer's, not a splice of both")
        t.check(back.data[0] == values[0] || back.data[0] == values[1],
            "with the pixels one of them actually wrote")
        store.close()
    }
}
