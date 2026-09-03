package com.immineal.hdri360.core.hdr

/** Policy for turning scene statistics into an actual shot list. */
class BracketConfig {
    /** Spacing between ladder rungs. 3 EV pairs well with 10-12 stops of usable sensor range. */
    @JvmField var evStep = 3.0
    /** Where the brightest content of a direction should land in its darkest frame. */
    @JvmField var saturationTarget = 0.70
    /** Where the darkest content of a direction should land in its brightest frame. */
    @JvmField var shadowTarget = 0.05
    /** Never shoot fewer than this per direction, even for a flat scene. */
    @JvmField var minPerTarget = 3
    /** Never shoot more than this per direction; capture time is the scarce resource. */
    @JvmField var maxPerTarget = 9
    /** Hard cap on ladder length. */
    @JvmField var maxLadderRungs = 14
}
