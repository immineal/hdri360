package com.immineal.hdri360.test.suites

import com.immineal.hdri360.core.hdr.Exposure
import com.immineal.hdri360.core.hdr.ExposureSettings
import com.immineal.hdri360.core.hdr.HdrMerger
import com.immineal.hdri360.core.hdr.MergeConfig
import com.immineal.hdri360.core.hdr.Photometry
import com.immineal.hdri360.core.image.ImageF
import com.immineal.hdri360.test.TestCase
import com.immineal.hdri360.test.TestKit

/**
 * Absolute photometric calibration.
 *
 * The output being linear is what makes an HDRI usable; the output being in real
 * units is what makes two of them comparable. The scale is recoverable from the
 * exposure triangle alone, and the property that proves it is right is
 * invariance: the same scene luminance, photographed at wildly different shutter
 * and ISO combinations, must come back as the same number of cd/m^2.
 */
class PhotometrySuite : TestCase {
    override fun name(): String = "photometry"

    /** Sensor fraction a surface of the given luminance produces at these settings. */
    private fun sensorValue(luminanceCdM2: Double, s: ExposureSettings): Double =
        luminanceCdM2 / Photometry.saturationLuminance(s)

    override fun run(t: TestKit) {
        // --- the two ISO constants have to describe the same physics ----------
        // Saturation-based speed and the reflected-meter constant are different
        // anchors on one tone scale; the gap between them is the highlight
        // headroom above middle grey, and it must come out at the familiar value.
        val headroom = Photometry.headroomStops()
        t.near(3.26, headroom, 0.02, "middle grey sits about 3.26 stops below saturation")
        t.note("implied highlight headroom above middle grey: " +
                TestKit.fmt(headroom) + " stops")

        val sunny16 = ExposureSettings(1.0 / 100, 100, 16.0)
        // The sunny 16 rule: f/16 at 1/ISO correctly exposes a sunlit subject.
        // A meter reading such a scene sits in the low thousands of cd/m^2.
        val metered = Photometry.meteredLuminance(sunny16)
        t.near(3200.0, metered, 1.0, "sunny 16 meters a scene at 3200 cd/m2")
        t.nearRel(metered * Math.pow(2.0, headroom), Photometry.saturationLuminance(sunny16),
            1e-9, "the metered and saturation forms differ by exactly the headroom")
        t.note("sunny 16 (f/16, 1/100 s, ISO 100): meters " + TestKit.fmt(metered) +
                " cd/m2, saturates at " + TestKit.fmt(Photometry.saturationLuminance(sunny16)))

        // --- a single value round trips ----------------------------------------
        val settings = ExposureSettings(1.0 / 250, 200, 1.8)
        for (l in doubleArrayOf(5.0, 120.0, 3000.0, 250000.0)) {
            val v = sensorValue(l, settings)
            t.nearRel(l, Photometry.luminanceOf(v, settings), 1e-9,
                "luminance round trips at " + TestKit.fmt(l) + " cd/m2")
        }

        // --- invariance to how the exposure was reached -------------------------
        // This is the real test. Same scene, six different ways of photographing
        // it; the recovered luminance must not know which was used.
        val baseIso = 50
        val trueLuminance = 1800.0
        val ways = arrayOf(
            ExposureSettings(1.0 / 4000, 50, 1.8),
            ExposureSettings(1.0 / 2000, 100, 1.8),
            ExposureSettings(1.0 / 8000, 200, 1.8),
            // The same total exposure reached three different ways:
            ExposureSettings(1.0 / 1000, 100, 1.8),
            ExposureSettings(1.0 / 500, 50, 1.8),
            ExposureSettings(1.0 / 8000, 800, 1.8))
        val scale = Photometry.luminanceScale(1.8, baseIso)
        var worst = 0.0
        for (s in ways) {
            val v = sensorValue(trueLuminance, s)
            t.check(v > 0 && v < 1.0, "the test exposure is on scale, not clipped")
            // Exactly what the pipeline carries: sensor fraction over relative exposure.
            val pipelineRadiance = v / s.relativeExposure(baseIso)
            val recovered = pipelineRadiance * scale
            worst = Math.max(worst, Math.abs(recovered - trueLuminance) / trueLuminance)
            t.nearRel(trueLuminance, recovered, 1e-9,
                "recovered luminance does not depend on the shutter/ISO pairing used")
        }
        t.note("luminance recovered to " + TestKit.fmt(worst * 100) +
                "% across six shutter/ISO pairings of the same scene")

        // Aperture is part of the scale, not divided out, because the pipeline
        // never sees it: it changes how much light arrives, not how it is counted.
        val wide = Photometry.luminanceScale(1.8, baseIso)
        val stopped = Photometry.luminanceScale(3.6, baseIso)
        t.nearRel(4.0, stopped / wide, 1e-12, "two stops of aperture is four times the scale")

        // --- through the actual merge -------------------------------------------
        // Not the arithmetic in isolation: a real bracket, merged by the real
        // estimator, then converted.
        val cfg = MergeConfig()
        val bracket = ArrayList<Exposure>()
        val rungs = arrayOf(
            ExposureSettings(1.0 / 8000, 50, 1.8),
            ExposureSettings(1.0 / 1000, 50, 1.8),
            ExposureSettings(1.0 / 125, 50, 1.8))
        for (s in rungs) {
            val img = ImageF(1, 1, 1)
            img.data[0] = Math.min(1.0, sensorValue(trueLuminance, s)).toFloat()
            bracket.add(Exposure(img, s.relativeExposure(baseIso), s.gain(baseIso)))
        }
        val merged = HdrMerger.merge(bracket, cfg).radiance.data[0].toDouble()
        val mergedLuminance = merged * Photometry.luminanceScale(1.8, baseIso)
        t.nearRel(trueLuminance, mergedLuminance, 1e-3,
            "a merged bracket converts to the right absolute luminance")
        t.note("merged bracket of an " + TestKit.fmt(trueLuminance) + " cd/m2 surface reads " +
                TestKit.fmt(mergedLuminance) + " cd/m2")

        // --- the scale is linear in the radiance, as a scale must be --------------
        val dark = 0.4 * merged * Photometry.luminanceScale(1.8, baseIso)
        t.nearRel(0.4, dark / mergedLuminance, 1e-12, "conversion is a pure scale")

        // --- base ISO is the accuracy limit, and it is linear ---------------------
        // Worth pinning because devices report a minimum ISO that is not always the
        // sensor's true unity-gain point, and the error passes straight through.
        t.nearRel(2.0, Photometry.luminanceScale(1.8, 50) / Photometry.luminanceScale(1.8, 100),
            1e-12, "halving the assumed base ISO doubles the reported luminance")

        // --- plausibility against things whose brightness is known ----------------
        // A calibration that is a stop out is still useful; one that is a decade out
        // is broken, and these anchors would catch that.
        // A dimly lit room: 50-150 lux on a half-reflective surface is of order
        // 10 cd/m2, and that is the exposure a phone actually picks indoors.
        val room = ExposureSettings(1.0 / 60, 400, 2.0)
        val roomLuminance = Photometry.meteredLuminance(room)
        t.greaterThan(roomLuminance, 1.0, "a dim indoor exposure implies single-digit cd/m2")
        t.lessThan(roomLuminance, 100.0, "and nothing like daylight")

        // Open daylight, several thousand cd/m2, three decades brighter.
        val daylight = ExposureSettings(1.0 / 2000, 50, 8.0)
        val dayLuminance = Photometry.meteredLuminance(daylight)
        t.greaterThan(dayLuminance, 2000.0, "a daylight exposure implies daylight luminance")
        t.greaterThan(dayLuminance / roomLuminance, 1000.0,
            "and the two are the three decades apart that they physically are")
        t.note("dim room " + TestKit.fmt(roomLuminance) + " cd/m2 vs open daylight " +
                TestKit.fmt(dayLuminance) + " cd/m2, " +
                TestKit.fmt(ExposureSettings.log2(dayLuminance / roomLuminance)) + " stops apart")

        // --- the scale refuses to be misread ------------------------------------------
        // A relative capture and an absolute one look identical once they are just
        // floats in a file, so the difference is enforced by the type rather than
        // by a naming convention someone will eventually ignore.
        val abs = com.immineal.hdri360.core.hdr.RadianceScale.absolute(1.8, baseIso)
        t.check(abs.absolute, "a calibrated scale reports itself absolute")
        t.nearRel(trueLuminance, abs.toCdPerM2(
            sensorValue(trueLuminance, ways[0]) / ways[0].relativeExposure(baseIso)), 1e-9,
            "and converts pipeline radiance to cd/m2")
        t.nearRel(Photometry.luminanceScale(1.8, baseIso), abs.cdPerM2PerUnit, 1e-12,
            "carrying the same factor Photometry computes")

        val rel = com.immineal.hdri360.core.hdr.RadianceScale.relative("camera chose its own exposure")
        t.check(!rel.absolute, "an uncalibrated scale reports itself relative")
        t.throwsException({ rel.toCdPerM2(1.0) },
            "and refuses to produce cd/m2 rather than quoting a meaningless number")
        t.check(rel.toString().contains("camera chose its own exposure"),
            "a relative scale says why it has no units")
        t.note("absolute scale reads: " + abs.toString())

        // --- validation -------------------------------------------------------------
        t.throwsException({ Photometry.luminanceScale(0.0, 50) }, "a zero aperture is an error")
        t.throwsException({ Photometry.luminanceScale(1.8, 0) }, "a zero base ISO is an error")
        t.throwsException({ Photometry.luminanceScale(1.8, 50, 0.0) },
            "a zero lens factor is an error")
    }
}
