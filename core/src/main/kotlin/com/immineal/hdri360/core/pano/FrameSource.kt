package com.immineal.hdri360.core.pano

import com.immineal.hdri360.core.camera.Intrinsics
import com.immineal.hdri360.core.image.ImageF
import com.immineal.hdri360.core.math.Mat3

/** One merged frame ready to be projected onto the sphere. */
class FrameSource(
    /** Linear radiance, already merged from its bracket. */
    @JvmField val radiance: ImageF,
    @JvmField val intrinsics: Intrinsics,
    /** Camera-to-world rotation. */
    @JvmField val rotation: Mat3,
    /** Optional per-pixel confidence from the merge; null means uniform. */
    @JvmField val confidence: FloatArray?,
    /** Photometric scale from the global alignment. */
    @JvmField val gain: Double
) {
    init {
        if (radiance.width != intrinsics.width || radiance.height != intrinsics.height)
            throw IllegalArgumentException("image size does not match its intrinsics")
        if (confidence != null && confidence.size != radiance.width * radiance.height)
            throw IllegalArgumentException("confidence map size mismatch")
        if (!(gain > 0)) throw IllegalArgumentException("gain must be positive")
    }

    fun withRotation(r: Mat3) = FrameSource(radiance, intrinsics, r, confidence, gain)

    fun withGain(g: Double) = FrameSource(radiance, intrinsics, rotation, confidence, g)
}
