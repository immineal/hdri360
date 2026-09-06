package com.immineal.hdri360.test.suites

import com.immineal.hdri360.core.pipeline.CaptureStage
import com.immineal.hdri360.test.TestCase
import com.immineal.hdri360.test.TestKit

/**
 * What state a capture directory is in, from the three facts on disk.
 *
 * Written from decision 15 before the classifier existed. The foreground service
 * is gone, so a stitch stops when the app is closed, and the start screen has to
 * tell three situations apart that used to look identical: a capture that was
 * never finished shooting, a capture that was shot in full and never processed,
 * and one that is done. Before this, the middle case wore the first one's label
 * and the only route offered was to shoot the sphere again.
 *
 * The classifier is a pure function so the suite can pin every case without a
 * phone. `CaptureSession` reads the three facts off the store and asks it.
 */
class CaptureStageSuite : TestCase {
    override fun name(): String = "capture-stage"

    override fun run(t: TestKit) {
        theFourStates(t)
        doneWinsOverEverything(t)
        aDirectoryWithNoSessionIsNotACapture(t)
        oneMissingDirectionIsStillPartlyShot(t)
        anEmptyPlanIsNotSomethingToOffer(t)
    }

    private fun theFourStates(t: TestKit) {
        t.eq(CaptureStage.PROCESSED,
            CaptureStage.of(true, true, booleanArrayOf(true, true, true)),
            "session, processed marker, all shot")
        t.eq(CaptureStage.SHOT_NOT_PROCESSED,
            CaptureStage.of(true, false, booleanArrayOf(true, true, true)),
            "session, no marker, all shot")
        t.eq(CaptureStage.PARTLY_SHOT,
            CaptureStage.of(true, false, booleanArrayOf(true, false, false)),
            "session, no marker, some shot")
        t.eq(CaptureStage.NOTHING_SHOT,
            CaptureStage.of(true, false, booleanArrayOf(false, false, false)),
            "session, no marker, nothing shot")
    }

    /**
     * The marker is written last, after the EXR is renamed into place and the
     * working files are deleted. Once it is there the capture is finished no
     * matter what the mask says - a direction re-shot and then never used still
     * leaves a sphere on disk, and offering to process it again would overwrite
     * a good panorama with the same one.
     */
    private fun doneWinsOverEverything(t: TestKit) {
        t.eq(CaptureStage.PROCESSED, CaptureStage.of(true, true, booleanArrayOf(true, false)),
            "the processed marker beats a hole in the mask")
        t.eq(CaptureStage.PROCESSED, CaptureStage.of(true, true, BooleanArray(0)),
            "the processed marker beats an empty plan")
    }

    /**
     * A directory with no session header is not a capture at all: there is no
     * plan to say how many directions it should have had, so nothing can be
     * said about whether it is complete.
     */
    private fun aDirectoryWithNoSessionIsNotACapture(t: TestKit) {
        t.eq(CaptureStage.NOTHING_SHOT, CaptureStage.of(false, false, booleanArrayOf(true, true)),
            "no session header, whatever else is lying there")
        t.eq(CaptureStage.NOTHING_SHOT, CaptureStage.of(false, true, booleanArrayOf(true, true)),
            "not even with a stray marker")
    }

    /**
     * SHOT_NOT_PROCESSED is the state that earns the "Continue processing"
     * offer, and it has to mean *every* direction. One hole and the sphere has
     * a gap in it; the honest offer there is to go back and shoot the rest.
     */
    private fun oneMissingDirectionIsStillPartlyShot(t: TestKit) {
        val mask = BooleanArray(34) { true }
        t.eq(CaptureStage.SHOT_NOT_PROCESSED, CaptureStage.of(true, false, mask),
            "34 of 34")
        mask[17] = false
        t.eq(CaptureStage.PARTLY_SHOT, CaptureStage.of(true, false, mask),
            "33 of 34 is not shot in full")
        mask[17] = true
        mask[33] = false
        t.eq(CaptureStage.PARTLY_SHOT, CaptureStage.of(true, false, mask),
            "the last direction counts like any other")
    }

    /**
     * A session header written before the first frame lands leaves a plan of
     * zero directions for a moment. Vacuously "all shot" is the wrong reading:
     * there is nothing to process and nothing to resume.
     */
    private fun anEmptyPlanIsNotSomethingToOffer(t: TestKit) {
        t.eq(CaptureStage.NOTHING_SHOT, CaptureStage.of(true, false, BooleanArray(0)),
            "an empty plan is nothing shot, not everything shot")
    }
}
