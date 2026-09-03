package com.immineal.hdri360.core.pano

/** How densely to tile the sphere. */
class CapturePlanConfig {
    /**
     * Fraction of each frame that should be shared with its neighbour. Below
     * about 0.25 the stitcher runs out of correspondences near the frame edges,
     * which is exactly where lens distortion is least well known.
     */
    @JvmField var overlapFraction = 0.35
    /** Shoot dedicated zenith and nadir frames rather than relying on ring corners. */
    @JvmField var includePoles = true
}
