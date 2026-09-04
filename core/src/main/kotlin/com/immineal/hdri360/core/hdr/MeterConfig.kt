package com.immineal.hdri360.core.hdr

/** Thresholds for reading a probe frame. All values are normalised sensor units. */
class MeterConfig {
    /** At or above this a pixel is considered clipped and carries no information. */
    @JvmField var saturationThreshold = 0.98
    /** Below this a pixel is buried in read noise; realistic for a 12-bit raw. */
    @JvmField var noiseFloor = 2e-4
    /** Quantile used for "the dark end of the scene". */
    @JvmField var lowPercentile = 0.01
    /** Quantile used for "the bright end of the scene". */
    @JvmField var highPercentile = 0.999
    /**
     * Clipped fraction above which highlights count as blown, provided the high
     * quantile agrees. Both conditions are needed: a real sensor has stuck pixels
     * that no exposure removes, and counting those alone sends the exposure
     * controller down through its whole range on a scene that is not clipping.
     */
    @JvmField var clipTolerance = 1e-4
    /** Fraction below the noise floor above which shadows count as crushed. */
    @JvmField var blackTolerance = 0.02
    /** Where the auto-exposure controller aims to put the high quantile. */
    @JvmField var aeTarget = 0.60
    @JvmField var aeTargetLow = 0.30
    @JvmField var aeTargetHigh = 0.90
    /** Largest single correction the controller will apply, as a factor. */
    @JvmField var aeMaxStep = 64.0
}
