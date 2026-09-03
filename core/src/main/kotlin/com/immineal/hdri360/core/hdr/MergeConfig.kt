package com.immineal.hdri360.core.hdr

/** Knobs for the radiance merge. */
class MergeConfig {
    /** Weight begins to roll off here. */
    @JvmField var satLow = 0.90
    /** Weight is zero at and above here; anything this bright may already be clipped. */
    @JvmField var satHigh = 0.99
    /** Below this signal-to-noise ratio a pixel is reported as noise limited. */
    @JvmField var minSnr = 1.0
    @JvmField var noise: NoiseModel = NoiseModel.typicalPhone()
    /** Null means the input is already linear (the RAW path). */
    @JvmField var response: ResponseCurve? = null
    /** Null means no correction. */
    @JvmField var vignette: VignetteModel? = null
}
