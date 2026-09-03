package com.immineal.hdri360.core.hdr

import com.immineal.hdri360.core.image.ImageF

/** Merged radiance plus an honest account of where the data ran out. */
class MergeResult(
    @JvmField val radiance: ImageF,
    /** One byte per pixel (not per channel), OR-ed across channels. */
    @JvmField val flags: ByteArray,
    /** Sum of merge weights per pixel; a rough confidence map for the blender. */
    @JvmField val weight: FloatArray
) {

    fun saturatedFraction(): Double {
        var n = 0
        for (f in flags) if ((f.toInt() and FLAG_SATURATED) != 0) n++
        return n / flags.size.toDouble()
    }

    fun noiseLimitedFraction(): Double {
        var n = 0
        for (f in flags) if ((f.toInt() and FLAG_NOISE_LIMITED) != 0) n++
        return n / flags.size.toDouble()
    }

    companion object {
        const val FLAG_SATURATED = 1
        const val FLAG_NOISE_LIMITED = 2
    }
}
