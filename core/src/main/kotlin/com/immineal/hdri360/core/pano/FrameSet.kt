package com.immineal.hdri360.core.pano

import com.immineal.hdri360.core.camera.Intrinsics
import com.immineal.hdri360.core.math.Mat3

/**
 * Where a frame points and what shape it is, without its pixels.
 *
 * The renderer needs all of this to decide whether a frame can see a direction
 * at all, and that decision rejects most frames for most of the sphere. Being
 * able to make it without the megabytes attached is what lets the composite be
 * built from frames that are not all in memory at once.
 */
class FrameOptics(
    @JvmField val intrinsics: Intrinsics,
    /** Camera-to-world rotation. */
    @JvmField val rotation: Mat3,
    @JvmField val gain: Double,
    @JvmField val channels: Int
) {
    val width: Int get() = intrinsics.width
    val height: Int get() = intrinsics.height
}

/**
 * The frames a composite is built from, opened one at a time.
 *
 * This exists because of an arithmetic fact about phones. A thirty-two direction
 * sphere at three megapixels a frame is a gigabyte and a half of merged
 * radiance, and a phone gives the app half a gigabyte of heap. The old answer
 * was to shrink every frame until the whole sphere fitted, which on a real
 * capture meant working at an eighth of the sensor - throwing away three
 * quarters of the resolution in each axis to hold data that is only ever read
 * one frame at a time.
 *
 * Rendering a frame at a time instead makes the peak cost the *largest* frame
 * rather than the sum of them, and the sphere's size stops mattering. That is
 * the whole idea; the rest is bookkeeping.
 *
 * Implementations must be safe to call [open] on from one thread at a time. The
 * renderer never holds two frames open at once.
 */
interface FrameSet {

    val size: Int

    /** Optics and pose of frame [i]. Must be cheap: it is called per frame per band. */
    fun optics(i: Int): FrameOptics

    /** The frame with its pixels. May be an expensive read. */
    fun open(i: Int): FrameSource

    /** Says the caller is finished with frame [i] for now. */
    fun release(i: Int) {}

    companion object {
        /** Frames that are already in memory; [open] hands them straight back. */
        @JvmStatic
        fun of(frames: List<FrameSource>): FrameSet = Resident(frames)

        /**
         * A view of [base] holding only [indices], renumbered from zero.
         *
         * The composite drops frames that never connected, and the seam map
         * indexes whatever it was built from - so the dropping has to produce a
         * set with contiguous indices rather than holes.
         */
        @JvmStatic
        fun select(base: FrameSet, indices: IntArray): FrameSet = Selected(base, indices)
    }

    private class Selected(private val base: FrameSet, private val indices: IntArray) : FrameSet {
        override val size: Int get() = indices.size
        override fun optics(i: Int): FrameOptics = base.optics(indices[i])
        override fun open(i: Int): FrameSource = base.open(indices[i])
        override fun release(i: Int) = base.release(indices[i])
    }

    private class Resident(private val frames: List<FrameSource>) : FrameSet {
        override val size: Int get() = frames.size
        override fun optics(i: Int): FrameOptics {
            val f = frames[i]
            return FrameOptics(f.intrinsics, f.rotation, f.gain, f.radiance.channels)
        }
        override fun open(i: Int): FrameSource = frames[i]
    }
}
