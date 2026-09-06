package com.immineal.hdri360.test.suites

import com.immineal.hdri360.core.camera.Intrinsics
import com.immineal.hdri360.core.capture.CapturedFrame
import com.immineal.hdri360.core.capture.CaptureTier
import com.immineal.hdri360.core.capture.FrameStore
import com.immineal.hdri360.core.capture.StoredSession
import com.immineal.hdri360.core.hdr.BracketPlan
import com.immineal.hdri360.core.hdr.DeviceExposureLimits
import com.immineal.hdri360.core.hdr.ExposureLadder
import com.immineal.hdri360.core.hdr.RadianceScale
import com.immineal.hdri360.core.image.CfaPattern
import com.immineal.hdri360.core.image.ImageF
import com.immineal.hdri360.core.io.Half
import com.immineal.hdri360.core.math.SO3
import com.immineal.hdri360.core.math.Vec3
import com.immineal.hdri360.core.pipeline.Calibration
import com.immineal.hdri360.core.pipeline.FileScale
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
        theFileIsInLinearRec709(t)
        theFileIsInAUnitThatMeansSomething(t)
        theReportsLuminanceIsALuminance(t)
    }

    /**
     * A figure quoted in cd/m2 has to be a luminance.
     *
     * The statistics over the finished sphere - its range, its extremes, the
     * dynamic range in stops - were taken over `(R + G + B) / 3`, which is the
     * mean of three channels and not the luminance of anything. It did not much
     * matter while the file was in arbitrary units. It matters now that the file
     * is in kilocandela per square metre and the report multiplies
     * `stats.maxRadiance` by that unit to quote `maxLuminanceCdPerM2`: for a
     * saturated colour the two differ by a lot, and the quoted number was a
     * confident answer to a question nobody asked.
     *
     * Rec.709 luma, which is what the file's own primaries define and what every
     * reader of the file will compute.
     */
    private fun theReportsLuminanceIsALuminance(t: TestKit) {
        val k = Intrinsics.fromHorizontalFov(64, 48, 60.0)
        // One flat frame of pure saturated green, whose channel mean and whose
        // luminance are as far apart as they get.
        val green = ImageF(64, 48, 3)
        for (p in 0 until 64 * 48) {
            green.data[p * 3] = 0f
            green.data[p * 3 + 1] = 100f
            green.data[p * 3 + 2] = 0f
        }
        val frames = listOf(com.immineal.hdri360.core.pano.FrameSource(
            green, k, SO3.exp(Vec3(0.0, 0.0, 0.0)), null, 1.0))
        val cfg = com.immineal.hdri360.core.pipeline.OutputWriter.Config()
        cfg.panoramaWidth = 128
        val stats = com.immineal.hdri360.core.pipeline.OutputWriter.writeExr(
            java.io.ByteArrayOutputStream(), frames, null, cfg)

        // Rec.709 says green carries 0.7152 of the luminance, so 100 units of
        // pure green is 71.52 - not the 33.3 that averaging three channels gives.
        t.nearRel(71.52, stats.maxRadiance, 0.02,
            "the brightest value is the luminance of that colour, not its channel mean")
        t.check(stats.maxRadiance > 50.0,
            "and emphatically not a third of it, which is what averaging gave")
        t.note(String.format(java.util.Locale.US,
            "pure green at 100: luminance %.2f, channel mean would be %.2f",
            stats.maxRadiance, 100.0 / 3))
    }

    /**
     * What the numbers in the file are, and why they were wrong.
     *
     * The radiance scale was computed, put in the report, and never applied to a
     * pixel. So the file was in the pipeline's own arbitrary units - a sensor
     * fraction over a relative exposure - and the factor from those to cd/m2 is
     * `78 N^2 / (q * baseIso)`, which depends on the **lens**. Two spheres of the
     * same room, one on each of a phone's back cameras, came out at 11.96 and
     * 11.62 cd/m2 per unit while both reported an absolute scale. Three percent
     * apart on that phone by coincidence; on a phone whose lenses differ in
     * aperture and base ISO, far more.
     *
     * It also made every file about 86 times too bright to open: dropped into a
     * renderer as a world texture at strength 1.0, an ordinary sunny garden with
     * a mean of 143 read as 143 rather than as the 1.7 that real environment maps
     * carry.
     *
     * So the file is written in kilocandela per square metre - 1.0 means 1000
     * cd/m2 - when the capture earned an absolute scale, and normalised so its
     * own median is 1.0 when it did not. Both are lens-independent; the first is
     * a measurement and the second says it is not.
     */
    private fun theFileIsInAUnitThatMeansSomething(t: TestKit) {
        // The two back lenses of a Pixel 9a, as it reports them.
        val main = RadianceScale.absolute(1.7, 29)
        val wide = RadianceScale.absolute(2.2, 50)
        t.check(Math.abs(main.cdPerM2PerUnit - wide.cdPerM2PerUnit) > 0.1,
            "the two lenses really do put a different number on the same light")

        // The claim: the same physical luminance lands on the same file number,
        // whichever lens shot it. This is the whole point.
        val luminance = 1667.0                       // cd/m2, a sunny garden's mean
        val mainUnits = luminance / main.cdPerM2PerUnit
        val wideUnits = luminance / wide.cdPerM2PerUnit
        val mainFile = FileScale.of(main, null).apply(mainUnits)
        val wideFile = FileScale.of(wide, null).apply(wideUnits)
        t.nearRel(mainFile, wideFile, 1e-9,
            "the same light is the same number in the file, whichever lens shot it")
        t.nearRel(1.667, mainFile, 1e-9, "and that number is the luminance in kilocandela")

        // What a reader has to be told to get back to physics.
        val fs = FileScale.of(main, null)
        t.nearRel(FileScale.CD_PER_M2_PER_UNIT, fs.cdPerM2PerUnit, 1e-12,
            "an absolute file says one unit is a thousand candela per square metre")
        t.check(fs.absolute, "and that it is a measurement")
        t.nearRel(luminance, fs.cdPerM2PerUnit * mainFile, 1e-9,
            "so the luminance comes straight back out")

        // A capture with no absolute scale cannot be converted, so it is
        // normalised instead - and says so rather than implying units it has not
        // got.
        val relative = RadianceScale.relative("this camera would not take manual exposures")
        val pano = ImageF(8, 4, 3)
        // Values spread over two orders of magnitude, median 40.
        val values = doubleArrayOf(4.0, 8.0, 15.0, 25.0, 35.0, 45.0, 60.0, 90.0,
            120.0, 200.0, 300.0, 400.0, 3.0, 6.0, 12.0, 20.0,
            30.0, 50.0, 70.0, 110.0, 150.0, 250.0, 350.0, 500.0,
            5.0, 9.0, 18.0, 28.0, 38.0, 55.0, 80.0, 130.0)
        for (i in 0 until 32) for (ch in 0 until 3) pano.data[i * 3 + ch] = values[i].toFloat()
        val rfs = FileScale.of(relative, pano)
        t.check(!rfs.absolute, "a relative capture claims no units")
        t.near(0.0, rfs.cdPerM2PerUnit, 1e-12, "and offers no conversion")
        t.check(rfs.basis.contains("normalised"),
            "but says what was done to it, so nobody reads it as a measurement")
        // The median of that set, scaled, is one - the upper of the two middle
        // values, which is the definition the normalisation states.
        val scaled = values.map { rfs.apply(it) }.sorted()
        t.nearRel(1.0, scaled[16], 1e-6, "a relative file is normalised to a median of one")
        t.check(scaled[15] < 1.0 && scaled[17] > 1.0, "with half the sphere either side of it")

        // Degenerate inputs must not produce a file of infinities.
        t.nearRel(1.0, FileScale.of(relative, null).factor, 1e-12,
            "with nothing to measure the median from, nothing is scaled")
        val black = ImageF(4, 2, 3)
        t.nearRel(1.0, FileScale.of(relative, black).factor, 1e-12,
            "and an all-black sphere is left alone rather than divided by zero")
    }

    /**
     * Decision 8: the EXR is written in linear Rec.709, not in the camera's own
     * RGB.
     *
     * What it was before: the pipeline applied the white balance gains and
     * stopped. Gains alone put a frame in the sensor's own primaries with a grey
     * point moved - a space with no name - and a sphere written that way reads
     * flat and undersaturated in anything that opens it. The camera has the
     * missing half and reports it with every frame
     * (COLOR_CORRECTION_TRANSFORM, defined as sensor RGB to linear sRGB and
     * applied after exactly those gains); it was read for the preview and then
     * dropped on the floor.
     *
     * Where it goes is the whole of it. The transform is handed to the pipeline
     * and applied to the *merged* radiance; a bracket read off disk is still in
     * the sensor's own numbers, because that is the domain the merge's saturation
     * test is defined in. See HdriPipeline.Options.colorTransform, and the
     * hdr-merge suite for what happens when that is got wrong.
     */
    private fun theFileIsInLinearRec709(t: TestKit) = inTemp("rec709") { dir ->
        // A matrix of the shape a phone reports: strongly diagonal, negative off
        // diagonals that pull the sensor's broad, overlapping filters apart, and
        // rows that sum to one so that neutral maps to neutral. This one is the
        // Pixel 9a's, as it reported it.
        val m = doubleArrayOf(
            1.59375, -0.4609375, -0.1328125,
            -0.33203125, 1.44921875, -0.1171875,
            -0.01171875, -1.0, 2.01171875)
        for (row in 0 until 3) {
            var sum = 0.0
            for (col in 0 until 3) sum += m[row * 3 + col]
            t.near(1.0, sum, 1e-9, "the matrix maps a neutral to a neutral")
        }
        val gains = doubleArrayOf(1.5622116327285767, 1.0, 1.6853481531143188)

        val base = session(2, 1, CaptureTier.LINEAR_RAW)
        val s = StoredSession(
            cameraId = base.cameraId, tier = base.tier, intrinsics = base.intrinsics,
            apertureN = base.apertureN, focalLengthMm = base.focalLengthMm,
            sensorOrientationDeg = base.sensorOrientationDeg, cfa = base.cfa,
            whiteLevel = base.whiteLevel, blackLevel = base.blackLevel,
            baseIso = base.baseIso, plan = base.plan, note = base.note,
            neutralGains = gains, colorMatrix = m)

        // A grey card as the sensor sees it: not three equal numbers, but whatever
        // the gains were measured to correct.
        val store = FrameStore.create(dir, s)
        val px = ImageF(2, 1, 3)
        val grey = doubleArrayOf(0.40 / gains[0], 0.40, 0.40 / gains[2])
        val red = doubleArrayOf(0.50, 0.22, 0.10)
        for (c in 0 until 3) {
            px.data[c] = grey[c].toFloat()
            px.data[3 + c] = red[c].toFloat()
        }
        for (target in 0 until 2)
            store.store(CapturedFrame(1L, target, 0, s.plan.settings(target, 0),
                SO3.exp(Vec3(0.0, target * 0.5, 0.0)), 1000L, false), px)
        store.close()

        val back = FrameStore.open(dir)!!
        t.arrayNear(m, back.session.colorMatrix ?: DoubleArray(9), 1e-12,
            "the matrix survives the session header, or a reopened capture loses its colour")

        // A bracket off disk is in the sensor's own numbers. Nothing has coloured
        // it, because colouring it here is what broke the merge.
        val bracket = StoredCapture.openBracketFor(back, 0)[0].image
        t.nearRel(grey[0], bracket.data[0].toDouble(), 1e-3,
            "a bracket read back is still in sensor RGB, uncoloured")
        t.nearRel(grey[2], bracket.data[2].toDouble(), 1e-3, "in every channel")

        // The transform the pipeline is handed is the two halves composed, in the
        // order the camera defines them.
        val tf = StoredCapture.colorTransformFor(back.session)
        if (tf == null) { t.fail("a session with gains and a matrix must yield a transform"); return@inTemp }
        t.eq(9L, tf.size.toLong(), "it is a 3x3")
        fun apply(v: DoubleArray) = DoubleArray(3) { r ->
            tf[r * 3] * v[0] + tf[r * 3 + 1] * v[1] + tf[r * 3 + 2] * v[2]
        }
        // Composition, checked against doing it in two steps by hand.
        fun byHand(v: DoubleArray): DoubleArray {
            val w = doubleArrayOf(v[0] * gains[0] / gains[1], v[1], v[2] * gains[2] / gains[1])
            return DoubleArray(3) { r -> m[r * 3] * w[0] + m[r * 3 + 1] * w[1] + m[r * 3 + 2] * w[2] }
        }
        for (probe in listOf(grey, red, doubleArrayOf(0.1, 0.9, 0.3))) {
            val a = apply(probe)
            val b = byHand(probe)
            for (c in 0 until 3)
                t.nearRel(b[c], a[c], 1e-12,
                    "one matrix does exactly what the gains and the matrix did in turn")
        }

        // A grey card comes out grey, at the level the green-anchored gains left
        // it. This is what protects the absolute scale: the cd/m2 conversion is
        // calibrated against green, and a transform that moved a neutral would
        // invalidate every luminance the report quotes while changing nothing a
        // reader could see.
        val neutral = apply(grey)
        t.nearRel(neutral[0], neutral[1], 1e-6, "a grey card stays grey")
        t.nearRel(neutral[0], neutral[2], 1e-6, "in all three channels")
        t.nearRel(0.40, neutral[1], 1e-6, "at the level the white balance left it")

        // And the colour actually moves. A transform that changes nothing is the
        // bug, not the fix: a phone sensor's filters overlap far more than
        // Rec.709's primaries, so nothing but the matrix pulls a red patch off
        // green.
        val warm = apply(red)
        val gainsOnly = doubleArrayOf(red[0] * gains[0] / gains[1], red[1],
            red[2] * gains[2] / gains[1])
        t.greaterThan(warm[0] / warm[1], gainsOnly[0] / gainsOnly[1],
            "a red patch is redder against green than white balance alone made it")

        // A capture with no matrix is still a capture, and says so rather than
        // inventing one.
        t.check(StoredCapture.colorTransformFor(base) == null,
            "a camera that reported no matrix yields no transform")
        t.check(StoredCapture.optionsFor(base, 512).colorTransform == null,
            "and the pipeline is told to leave the radiance in camera RGB")
        t.check(StoredCapture.optionsFor(back.session, 512).colorTransform != null,
            "while a capture that has one gets it")
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

        // Reading and demosaicing are charged against the sensor, not the reduced
        // frame. Leaving them out is what made the estimate say a minute for a job
        // that took three: they are most of the work, and they do not shrink when
        // the frames do.
        val decoding = Calibration(12.0, 40.0, 1.5, "fixed, for the test", 8.0)
        val reduced = WorkEstimator.estimate(32, 5, 750_000, 4096, decoding, 12_000_000)
        val asIfSmall = WorkEstimator.estimate(32, 5, 750_000, 4096, decoding, 750_000)
        t.greaterThan(reduced.mergeSeconds, asIfSmall.mergeSeconds * 4,
            "a twelve megapixel sensor reduced to 750k still costs twelve megapixels to read")
        t.greaterThan(reduced.seconds, asIfSmall.seconds, "so the whole job takes longer")
        t.note("32 x 5 frames of 12 MP, worked at 750k: " + reduced.humanText() +
                " against " + asIfSmall.humanText() + " if the read were free")

        // And the sensor size cannot make the job cheaper than the work it implies.
        val plain = WorkEstimator.estimate(32, 5, 750_000, 4096, decoding)
        t.check(plain.seconds <= reduced.seconds + 1e-9,
            "omitting the sensor size never overstates the work")
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
