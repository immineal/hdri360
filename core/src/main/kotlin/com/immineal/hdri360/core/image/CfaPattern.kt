package com.immineal.hdri360.core.image

/**
 * Colour-filter-array phase, named by the top-left 2x2 block. Camera2 reports
 * this as SENSOR_INFO_COLOR_FILTER_ARRANGEMENT.
 *
 * Ordinals are load-bearing: FrameStore writes the ordinal into its working
 * files, and it matches the Camera2 arrangement constant.
 */
enum class CfaPattern(
    /** Colour index (0=R, 1=G, 2=B) at (0,0) (1,0) (0,1) (1,1) of the 2x2 block. */
    private val c00: Int,
    private val c10: Int,
    private val c01: Int,
    private val c11: Int
) {
    RGGB(0, 1, 1, 2),
    GRBG(1, 0, 2, 1),
    GBRG(1, 2, 0, 1),
    BGGR(2, 1, 1, 0);

    /** 0 = red, 1 = green, 2 = blue. */
    fun colorAt(x: Int, y: Int): Int {
        val oddX = (x and 1) != 0
        val oddY = (y and 1) != 0
        if (!oddX && !oddY) return c00
        if (oddX && !oddY) return c10
        if (!oddX) return c01
        return c11
    }

    companion object {
        @JvmStatic
        fun fromCamera2(arrangement: Int): CfaPattern = when (arrangement) {
            0 -> RGGB
            1 -> GRBG
            2 -> GBRG
            3 -> BGGR
            else -> RGGB // MONO / NIR fall back to a benign pattern
        }
    }
}
