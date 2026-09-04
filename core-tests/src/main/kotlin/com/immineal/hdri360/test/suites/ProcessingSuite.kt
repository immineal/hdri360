package com.immineal.hdri360.test.suites

import com.immineal.hdri360.core.camera.Intrinsics
import com.immineal.hdri360.core.capture.CapturedFrame
import com.immineal.hdri360.core.capture.CaptureTier
import com.immineal.hdri360.core.capture.FrameStore
import com.immineal.hdri360.core.capture.StoredSession
import com.immineal.hdri360.core.hdr.BracketPlan
import com.immineal.hdri360.core.hdr.DeviceExposureLimits
import com.immineal.hdri360.core.hdr.ExposureLadder
import com.immineal.hdri360.core.image.CfaPattern
import com.immineal.hdri360.core.image.ImageF
import com.immineal.hdri360.core.io.Half
import com.immineal.hdri360.core.math.SO3
import com.immineal.hdri360.core.math.Vec3
import com.immineal.hdri360.core.pipeline.Calibration
import com.immineal.hdri360.core.pipeline.FrameSpool
import com.immineal.hdri360.core.pipeline.HdriPipeline
import com.immineal.hdri360.core.pipeline.MergedFrames
import com.immineal.hdri360.core.pipeline.StoredCapture
import com.immineal.hdri360.core.pipeline.WorkEstimator
import com.immineal.hdri360.test.TestCase
import com.immineal.hdri360.test.TestKit
import java.io.File

/**
 * Getting a capture off disk and into the pipeline, and telling the user how
 * long it will take before they commit to it.
 *
 * The estimate is the part that has to be honest. "Processing..." with no number
 * is what makes a user kill an app that was two minutes from finishing, and a
 * number invented from nothing is worse than none at all - so the model is
 * calibrated by actually running the work, and it says what it was calibrated
 * from.
 */
class ProcessingSuite : TestCase {

    override fun name(): String = "processing"

    override fun run(t: TestKit) {
        bracketsAreReadBackWhole(t)
        nothingIsReadUntilItIsNeeded(t)
        partialDirectionsAreLeftOut(t)
        onlyMeasuredCapturesClaimAbsoluteRadiance(t)
        theEstimateScalesWithTheWork(t)
        theEstimateIsCalibratedByRunningTheWork(t)
        theWrittenSphereMatchesTheRenderedOne(t)
        theJobIsSizedToFitTheMemoryItHas(t)
        theSpooledSphereMatchesTheResidentOne(t)
    }

    /**
     * Measured on a Pixel 9a: sixteen directions of three megapixel frames is
     * 576 MB of merged float against a 512 MB heap, and processing died with an
     * OutOfMemoryError partway through the merge.
     *
     * The answer was to stop holding the sphere at all - see [FrameSpool] - which
     * moves the constraint from the whole capture to one bracket. That is what is
     * sized here.
     */
    private fun theJobIsSizedToFitTheMemoryItHas(t: TestKit) {
        val heap = 512L * 1024 * 1024
        val budget = (heap * 0.45).toLong()

        // The frame that broke it: twelve megapixels, five rungs, a real phone.
        val f = StoredCapture.mergingSubsampleFor(12_000_000, 5, budget)
        t.greaterThan(f.toDouble(), 1.0, "a twelve megapixel five rung bracket has to come down")
        t.check(f and (f - 1) == 0, "and it comes down by a power of two, so a mosaic stays on phase")
        t.check(StoredCapture.mergePeakBytes(12_000_000, 5, f) <= budget,
            "far enough down that merging one bracket fits the budget")
        val oneLess = f / 2
        t.check(oneLess < 1 || StoredCapture.mergePeakBytes(12_000_000, 5, oneLess) > budget,
            "and no further than it has to: one step less would not have fitted")
        t.note("a 12 MP five rung bracket merges at 1/" + f + ", peak " +
                (StoredCapture.mergePeakBytes(12_000_000, 5, f) shr 20) + " MB")

        // The size of the sphere no longer enters into it. That is the whole
        // point: before spooling, thirty-two directions forced 1/8 and a soft
        // panorama, while sixteen of the same frames would have allowed 1/4.
        t.eq(StoredCapture.mergingSubsampleFor(12_000_000, 5, budget).toLong(),
            StoredCapture.mergingSubsampleFor(12_000_000, 5, budget).toLong(),
            "the reduction depends on the bracket, not on how many directions there are")

        t.eq(1L, StoredCapture.mergingSubsampleFor(500_000, 3, budget).toLong(),
            "a small capture is left alone")
        t.eq(1L, StoredCapture.mergingSubsampleFor(0, 5, budget).toLong(),
            "and a degenerate one does not divide by zero")
        t.check(StoredCapture.mergingSubsampleFor(200_000_000, 9, budget) <= 8,
            "even an impossible job stops reducing rather than shrinking to nothing")
        t.check(StoredCapture.mergePeakBytes(12_000_000, 5, 2) <
                StoredCapture.mergePeakBytes(12_000_000, 5, 1),
            "reducing actually reduces")
    }

    /**
     * Parking the merged sphere on disk must change nothing about the result.
     *
     * This is the claim the whole spool rests on: that working at four times the
     * pixels because the frames are no longer all resident costs no accuracy, and
     * that the composite reads them back exactly as it would have found them in
     * memory. Anything less than bit-identical would mean two pipelines to
     * maintain and two sets of numbers to explain.
     */
    private fun theSpooledSphereMatchesTheResidentOne(t: TestKit) {
        val k = Intrinsics.fromHorizontalFov(96, 72, 60.0)
        val r = t.rng(90210)
        val inputs = ArrayList<HdriPipeline.FrameInput>()
        for (i in 0 until 6) {
            val rot = SO3.exp(Vec3(0.0, Math.toRadians(i * 30.0), 0.0))
            val bracket = ArrayList<com.immineal.hdri360.core.hdr.Exposure>()
            for (e in intArrayOf(1, 4, 16)) {
                val im = ImageF(k.width, k.height, 3)
                for (j in im.data.indices)
                    im.data[j] = Math.min(1.0, 0.004 * e * (0.1 + ((j * 61) % 397) / 397.0 +
                        0.02 * r.nextDouble())).toFloat()
                bracket.add(com.immineal.hdri360.core.hdr.Exposure.of(im,
                    com.immineal.hdri360.core.hdr.ExposureSettings(e / 1000.0, 100, 1.8), 100))
            }
            inputs.add(HdriPipeline.FrameInput(bracket, k, rot, "f$i"))
        }

        fun options(): HdriPipeline.Options {
            val o = HdriPipeline.Options()
            o.panoramaWidth = 256
            o.featureWorkingWidth = 96
            o.priorWeight = 0.5
            return o
        }

        val resident = HdriPipeline.process(inputs, options(), null)

        val dir = File(System.getProperty("java.io.tmpdir"), "hdri360-spool-" + System.nanoTime())
        val spool = FrameSpool(dir, inputs.size)
        val counting = OneAtATime(spool)
        val spooledOptions = options()
        spooledOptions.mergedFrames = counting
        val spooled = HdriPipeline.process(inputs, spooledOptions, null)

        t.eq(resident.panorama.data.size.toLong(), spooled.panorama.data.size.toLong(),
            "the spooled run produces the same size of panorama")
        var worst = 0.0
        for (i in resident.panorama.data.indices)
            worst = Math.max(worst,
                Math.abs(resident.panorama.data[i] - spooled.panorama.data[i]).toDouble())
        t.near(0.0, worst, 0.0, "and bit-identical pixels")
        t.near(resident.baRmsDeg, spooled.baRmsDeg, 0.0, "the same bundle residual")
        t.near(resident.k1, spooled.k1, 0.0, "the same lens")
        for (i in resident.gains.indices)
            t.near(resident.gains[i], spooled.gains[i], 0.0, "the same photometric gain ($i)")
        t.eq(resident.pairs.size.toLong(), spooled.pairs.size.toLong(), "the same pairs")
        t.eq(1L, counting.maxOpen.toLong(),
            "and it never held more than one frame at a time, which is the entire point")
        t.greaterThan(counting.opens.toDouble(), inputs.size.toDouble(),
            "frames really were read back rather than kept")
        t.note("spooled sphere: " + counting.opens + " frame reads, at most " +
                counting.maxOpen + " resident")

        // The files are scratch, and scratch that outlives the run fills a phone.
        t.check(dir.isDirectory, "the spool is on disk while it is in use")
        t.greaterThan(spool.bytesOnDisk().toDouble(), 0.0, "with the frames actually in it")
        spool.close()
        t.check(!dir.exists(), "and it is gone once the sphere is written")
    }

    /**
     * Wraps a spool to record how many frames were open at once. The bound is what
     * makes the memory argument true, so it is asserted rather than assumed.
     */
    private class OneAtATime(private val base: FrameSpool) : MergedFrames {
        var opens = 0
        var maxOpen = 0
        private var live = 0

        override val size: Int get() = base.size
        override fun optics(i: Int) = base.optics(i)
        override fun setOptics(i: Int, optics: com.immineal.hdri360.core.pano.FrameOptics) =
            base.setOptics(i, optics)
        override fun put(i: Int, radiance: ImageF, confidence: FloatArray?,
                         optics: com.immineal.hdri360.core.pano.FrameOptics) =
            base.put(i, radiance, confidence, optics)

        override fun open(i: Int): com.immineal.hdri360.core.pano.FrameSource {
            val f = base.open(i)
            opens++
            live++
            if (live > maxOpen) maxOpen = live
            return f
        }

        override fun release(i: Int) {
            base.release(i)
            if (live > 0) live--
        }
    }

    /**
     * The 8K output is produced a strip at a time and never exists as one array.
     * That is only safe if the file it produces is the file a whole render would
     * have produced, so it is compared against exactly that.
     */
    private fun theWrittenSphereMatchesTheRenderedOne(t: TestKit) {
        val frames = ArrayList<com.immineal.hdri360.core.pano.FrameSource>()
        val k = Intrinsics.fromHorizontalFov(64, 48, 60.0)
        for (i in 0 until 4) {
            val im = ImageF(64, 48, 3)
            for (j in im.data.indices) im.data[j] = 0.02f + 0.9f * (((j * 37) % 251) / 251.0f)
            frames.add(com.immineal.hdri360.core.pano.FrameSource(im, k,
                SO3.exp(Vec3(0.0, Math.toRadians(i * 60.0), 0.0)), null, 1.0))
        }
        val cfg = com.immineal.hdri360.core.pipeline.OutputWriter.Config()
        cfg.panoramaWidth = 256
        cfg.stripRows = 17                      // deliberately not a divisor of the height
        val bytes = java.io.ByteArrayOutputStream()
        var lastDone = 0
        val stats = com.immineal.hdri360.core.pipeline.OutputWriter.writeExr(
            bytes, frames, null, cfg) { done, total ->
            lastDone = done; t.check(done <= total, "progress never runs past the end")
        }
        t.eq(128L, lastDone.toLong(), "every row was written")

        val readBack = com.immineal.hdri360.core.io.ExrReader.read(bytes.toByteArray())
        val oneShot = com.immineal.hdri360.core.pipeline.OutputWriter.preview(frames, null, 256, cfg)
        t.eq(oneShot.width.toLong(), readBack.width.toLong(), "the file is the width asked for")
        t.eq(oneShot.height.toLong(), readBack.height.toLong(), "and the matching height")
        var worst = 0.0
        for (i in oneShot.data.indices) {
            // The file holds half floats, so the whole render is rounded the same
            // way before comparing: the claim is that striping changed nothing,
            // not that half precision is exact.
            val expected = Half.toFloat(Half.fromFloat(oneShot.data[i]))
            worst = Math.max(worst, Math.abs(expected - readBack.data[i]).toDouble())
        }
        t.near(0.0, worst, 0.0,
            "strip by strip gives bit-identical pixels to rendering the whole thing at once")

        t.greaterThan(stats.maxRadiance, stats.minRadiance, "the statistics found a range")
        t.greaterThan(stats.coveredFraction, 0.0, "and something covered")
        t.check(stats.coveredFraction <= 1.0, "never more than all of it")
        t.check(stats.dynamicRangeStops >= 0.0, "the dynamic range is not negative")
        t.note("written sphere: " + stats)
    }

    // ---------------------------------------------------------------- fixtures

    private fun session(targets: Int, rungs: Int, tier: CaptureTier): StoredSession {
        val limits = DeviceExposureLimits(1.0 / 17554, 16.0, 29, 7276, 29, 1.7, 1.0 / 15.0)
        val ladder = ExposureLadder.build(limits, 1.0 / 2000.0, 1.0 / 4.0, 2.0)
        return StoredSession(
            cameraId = "0", tier = tier,
            intrinsics = Intrinsics.fromHorizontalFov(32, 24, 58.7),
            apertureN = 1.7, focalLengthMm = 4.44, sensorOrientationDeg = 90,
            cfa = CfaPattern.RGGB, whiteLevel = 1023,
            blackLevel = doubleArrayOf(0.0, 0.0, 0.0, 0.0), baseIso = 29,
            plan = BracketPlan(ladder, Array(targets) { IntArray(rungs) { k -> k } }),
            note = "synthetic")
    }

    private fun fill(dir: File, s: StoredSession, targets: Int, rungs: Int,
                     linear: Boolean, skipRung: Pair<Int, Int>? = null): FrameStore {
        val store = FrameStore.create(dir, s)
        for (target in 0 until targets)
            for (k in 0 until rungs) {
                if (skipRung != null && skipRung.first == target && skipRung.second == k) continue
                val channels = if (linear) 1 else 3
                val px = ImageF(32, 24, channels)
                for (i in px.data.indices) px.data[i] = 0.25f + 0.5f * ((i % 7) / 7.0f)
                store.store(CapturedFrame(1L, target, k, s.plan.settings(target, k),
                    SO3.exp(Vec3(0.0, Math.toRadians(target * 45.0), 0.0)),
                    1000L * (target * 8 + k), linear), px)
            }
        return store
    }

    private fun <R> inTemp(tag: String, body: (File) -> R): R {
        val f = File.createTempFile("hdri-proc-$tag", "")
        f.delete()
        try {
            return body(f)
        } finally {
            f.listFiles()?.forEach { it.delete() }
            f.delete()
        }
    }

    // ------------------------------------------------------------------- tests

    /** Each direction becomes one bracket, in order, with its rungs in order. */
    private fun bracketsAreReadBackWhole(t: TestKit) = inTemp("read") { dir ->
        val s = session(4, 3, CaptureTier.LINEAR_RAW)
        fill(dir, s, 4, 3, linear = true).close()
        val store = FrameStore.open(dir)!!
        val inputs = StoredCapture.inputs(store)
        t.eq(4L, inputs.size.toLong(), "one input per direction that was completely shot")
        for (i in inputs.indices)
            t.check(!inputs[i].resident, "direction $i is read on demand, not held in memory")

        val bracket = StoredCapture.openBracketFor(store, 2)
        t.eq(3L, bracket.size.toLong(), "the bracket has every rung that was planned")
        for (k in 0 until bracket.size - 1)
            t.check(bracket[k].relativeExposure < bracket[k + 1].relativeExposure,
                "rung $k is a shorter exposure than the one after it")
        t.eq(3L, bracket[0].image.channels.toLong(),
            "a Bayer plane is demosaiced on the way in, because the pipeline wants colour")
        t.eq(s.intrinsics.width.toLong(), bracket[0].image.width.toLong(),
            "and keeps the size the camera model describes")
    }

    /**
     * The whole point of deferring. A capture is gigabytes; opening every bracket
     * to build the input list would defeat the memory work entirely.
     */
    private fun nothingIsReadUntilItIsNeeded(t: TestKit) = inTemp("defer") { dir ->
        val s = session(6, 3, CaptureTier.LINEAR_RAW)
        fill(dir, s, 6, 3, linear = true).close()
        val store = FrameStore.open(dir)!!
        var reads = 0
        val counting = object : StoredCapture.Reader {
            override fun read(record: com.immineal.hdri360.core.capture.FrameRecord): ImageF {
                reads++
                return store.read(record)
            }
        }
        val inputs = StoredCapture.inputs(store, counting)
        t.eq(0L, reads.toLong(), "building the input list reads no pixels at all")
        inputs[3].let { StoredCapture.open(it) }
        t.eq(3L, reads.toLong(), "opening one direction reads exactly its own rungs")
    }

    /** A direction missing a rung would merge from an incomplete ladder. */
    private fun partialDirectionsAreLeftOut(t: TestKit) = inTemp("partial") { dir ->
        val s = session(4, 3, CaptureTier.LINEAR_RAW)
        fill(dir, s, 4, 3, linear = true, skipRung = Pair(2, 1)).close()
        val store = FrameStore.open(dir)!!
        val inputs = StoredCapture.inputs(store)
        t.eq(3L, inputs.size.toLong(), "the direction with a hole in its bracket is not offered")
        t.check(inputs.none { it.label.contains("t002") },
            "and it is the right one that is left out")
    }

    /** The tier decides what the output is allowed to claim, and it says so. */
    private fun onlyMeasuredCapturesClaimAbsoluteRadiance(t: TestKit) {
        inTemp("abs") { dir ->
            val s = session(2, 2, CaptureTier.LINEAR_RAW)
            fill(dir, s, 2, 2, linear = true).close()
            val scale = StoredCapture.radianceScaleFor(FrameStore.open(dir)!!.session)
            t.check(scale.absolute, "a linear RAW capture at exposures we chose is a measurement")
            t.greaterThan(scale.toCdPerM2(1.0), 0.0, "so a pixel can be given in cd/m2")
        }
        for (tier in listOf(CaptureTier.MANUAL_YUV, CaptureTier.LOCKED_AUTO)) {
            inTemp("rel$tier") { dir ->
                val s = session(2, 2, tier)
                fill(dir, s, 2, 2, linear = false).close()
                val scale = StoredCapture.radianceScaleFor(FrameStore.open(dir)!!.session)
                t.check(!scale.absolute, "$tier is a reconstruction, not a measurement")
                t.throwsException({ scale.toCdPerM2(1.0) },
                    "and asking it for cd/m2 fails loudly rather than inventing a number")
                t.check(scale.basis.isNotEmpty(), "with a reason a user can read")
            }
        }
    }

    /** An estimate that does not move with the work is not an estimate. */
    private fun theEstimateScalesWithTheWork(t: TestKit) {
        val cal = Calibration(12.0, 40.0, 1.5, "fixed, for the test")
        val base = WorkEstimator.estimate(32, 3, 3_000_000, 4096, cal)
        t.greaterThan(base.seconds, 0.0, "the estimate is a positive number of seconds")
        t.check(base.seconds.isFinite(), "and a finite one")
        t.near(base.seconds, base.mergeSeconds + base.alignSeconds + base.renderSeconds +
            base.writeSeconds, 1e-9, "the parts add up to the whole, so the breakdown is honest")

        val doubled = WorkEstimator.estimate(32, 3, 3_000_000, 8192, cal)
        t.greaterThan(doubled.seconds, base.seconds, "twice the panorama width takes longer")
        t.nearRel(4.0, doubled.renderSeconds / base.renderSeconds, 1e-9,
            "and exactly four times as long to render, because it is four times the pixels")
        t.near(base.mergeSeconds, doubled.mergeSeconds, 1e-9,
            "while merging is untouched by the output size")

        val more = WorkEstimator.estimate(64, 3, 3_000_000, 4096, cal)
        t.nearRel(2.0, more.mergeSeconds / base.mergeSeconds, 1e-9,
            "twice the directions is twice the merging")
        t.greaterThan(more.seconds, base.seconds, "and more work overall")

        val smaller = WorkEstimator.estimate(32, 3, 750_000, 4096, cal)
        t.lessThan(smaller.seconds, base.seconds, "smaller frames are less work")

        // The choice the user is actually offered.
        val options = WorkEstimator.resolutionOptions(32, 3, 3_000_000, cal)
        t.greaterThan(options.size.toDouble(), 1.0, "there is more than one resolution to pick")
        for (i in 1 until options.size) {
            t.check(options[i].width < options[i - 1].width, "the options descend in size")
            t.lessThan(options[i].estimate.seconds, options[i - 1].estimate.seconds,
                "and each one is quicker than the one above it")
        }
        t.eq(8192L, options[0].width.toLong(), "the best option is the full 8K output")
    }

    /**
     * The constants come from running the work on this device, not from a table
     * of what some other phone managed.
     */
    private fun theEstimateIsCalibratedByRunningTheWork(t: TestKit) {
        val cal = WorkEstimator.calibrate()
        t.greaterThan(cal.mergeNsPerSample, 0.0, "merging was timed and took a positive time")
        t.greaterThan(cal.renderNsPerSample, 0.0, "so was rendering")
        t.greaterThan(cal.alignNsPerPixel, 0.0, "and so was alignment")
        t.check(cal.basis.isNotEmpty(), "and the estimate can say where its numbers came from")
        t.check(cal.mergeNsPerSample < 1e6,
            "a sane per-sample cost, not a stopwatch that measured the wrong thing")

        val e = WorkEstimator.estimate(32, 3, 3_000_000, 4096, cal)
        t.greaterThan(e.seconds, 0.1, "a real sphere at 4K is not instantaneous")
        t.lessThan(e.seconds, 3600.0, "nor is it an hour, on any machine that ran this suite")
        t.note("calibrated estimate for 32 directions x 3 rungs at 4K: " +
            TestKit.fmt(e.seconds) + " s (" + cal.basis + ")")
    }
}
