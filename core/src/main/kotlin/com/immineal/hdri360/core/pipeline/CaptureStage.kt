package com.immineal.hdri360.core.pipeline

/**
 * How far along a capture directory is, from the three facts that are on disk.
 *
 * There is no foreground service any more - see decision 15 - so a stitch stops
 * when the app is closed, and an interrupted stitch leaves a directory that is
 * fully shot and has no sphere in it. That state used to be indistinguishable
 * from a capture abandoned halfway through the sweep, and the only route the
 * start screen offered was to shoot the whole thing again.
 *
 * Pure on purpose: the suite pins every case without a phone in the loop, and
 * `CaptureSession` reads the three facts off the store and asks.
 */
enum class CaptureStage {
    /** No session header, or a plan with nothing in it yet. Nothing to offer. */
    NOTHING_SHOT,

    /** Some directions are on disk, not all of them. Going back to the sweep. */
    PARTLY_SHOT,

    /** Every direction is on disk and there is no sphere. Processing, not shooting. */
    SHOT_NOT_PROCESSED,

    /** The `processed` marker is there: the EXR was written and renamed into place. */
    PROCESSED;

    companion object {
        /**
         * @param hasSession whether the directory holds a session header - without
         *   one there is no plan, so nothing can be said about completeness
         * @param hasDone whether the `processed` marker is there. It is written
         *   last, after the EXR is renamed and the working files are deleted, so
         *   it outranks whatever the mask says: a direction re-shot and never used
         *   still leaves a good sphere, and offering to process it again would
         *   overwrite that sphere with the same one.
         * @param shot one flag per planned direction, true when all of its rungs
         *   are on disk
         */
        @JvmStatic
        fun of(hasSession: Boolean, hasDone: Boolean, shot: BooleanArray): CaptureStage {
            if (!hasSession) return NOTHING_SHOT
            if (hasDone) return PROCESSED
            // An empty plan is not vacuously complete. The session header is
            // written before the first frame lands, so for a moment a capture has
            // a plan of zero directions, and reading that as "everything is shot"
            // puts a Continue processing button on a directory with nothing in it.
            if (shot.isEmpty()) return NOTHING_SHOT
            var any = false
            for (s in shot) if (s) { any = true; break }
            if (!any) return NOTHING_SHOT
            for (s in shot) if (!s) return PARTLY_SHOT
            return SHOT_NOT_PROCESSED
        }
    }
}
