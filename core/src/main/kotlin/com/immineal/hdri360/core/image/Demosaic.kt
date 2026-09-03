package com.immineal.hdri360.core.image

/**
 * Malvar-He-Cutler gradient-corrected linear demosaicing.
 *
 * Chosen over plain bilinear because it costs the same order of arithmetic but
 * reproduces linear intensity ramps exactly and leaves far less colour fringing
 * on the high-contrast edges (window frames, foliage against sky) that dominate
 * an HDRI. All five kernels sum to 1, so a flat field stays flat.
 */
object Demosaic {

    @JvmStatic
    fun malvarHeCutler(src: BayerImage): ImageF {
        val w = src.width
        val h = src.height
        val out = ImageF(w, h, 3)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = src.colorAt(x, y)
                val meas = src.get(x, y)
                val r: Float; val g: Float; val b: Float
                if (c == 1) {
                    g = meas
                    // Which of R/B lies along the row depends on the CFA phase.
                    // colorAt is purely parity-based, so x+1 is safe at the border.
                    val redAlongRow = src.colorAt(x + 1, y) == 0
                    val alongRow = rbAtGreen(src, x, y, true)
                    val alongCol = rbAtGreen(src, x, y, false)
                    if (redAlongRow) { r = alongRow; b = alongCol }
                    else { b = alongRow; r = alongCol }
                } else if (c == 0) {
                    r = meas
                    g = greenAtRB(src, x, y)
                    b = oppositeAtRB(src, x, y)
                } else {
                    b = meas
                    g = greenAtRB(src, x, y)
                    r = oppositeAtRB(src, x, y)
                }
                out.set(x, y, 0, r)
                out.set(x, y, 1, g)
                out.set(x, y, 2, b)
            }
        }
        return out
    }

    /** Green estimated at a red or blue site. Kernel weights sum to 8. */
    private fun greenAtRB(s: BayerImage, x: Int, y: Int): Float {
        val v = 4 * p(s, x, y) +
                2 * (p(s, x - 1, y) + p(s, x + 1, y) + p(s, x, y - 1) + p(s, x, y + 1)) -
                (p(s, x - 2, y) + p(s, x + 2, y) + p(s, x, y - 2) + p(s, x, y + 2))
        return (v / 8.0).toFloat()
    }

    /**
     * Red at a green site in a red row (or blue at a green site in a blue row).
     * [alongRow] picks which of the two directions carries the wanted colour.
     */
    private fun rbAtGreen(s: BayerImage, x: Int, y: Int, alongRow: Boolean): Float {
        val centre = 5 * p(s, x, y)
        val near: Double
        val far: Double
        if (alongRow) {
            near = 4 * (p(s, x - 1, y) + p(s, x + 1, y))
            far = 0.5 * (p(s, x, y - 2) + p(s, x, y + 2)) - (p(s, x - 2, y) + p(s, x + 2, y))
        } else {
            near = 4 * (p(s, x, y - 1) + p(s, x, y + 1))
            far = 0.5 * (p(s, x - 2, y) + p(s, x + 2, y)) - (p(s, x, y - 2) + p(s, x, y + 2))
        }
        val diag = -(p(s, x - 1, y - 1) + p(s, x + 1, y - 1) + p(s, x - 1, y + 1) + p(s, x + 1, y + 1))
        return ((centre + near + far + diag) / 8.0).toFloat()
    }

    /** Blue at a red site, or red at a blue site. Kernel weights sum to 8. */
    private fun oppositeAtRB(s: BayerImage, x: Int, y: Int): Float {
        val v = 6 * p(s, x, y) +
                2 * (p(s, x - 1, y - 1) + p(s, x + 1, y - 1) + p(s, x - 1, y + 1) + p(s, x + 1, y + 1)) -
                1.5 * (p(s, x - 2, y) + p(s, x + 2, y) + p(s, x, y - 2) + p(s, x, y + 2))
        return (v / 8.0).toFloat()
    }

    /** Mirrored border access keeps the CFA phase intact (reflection by 2). */
    private fun p(s: BayerImage, x0: Int, y0: Int): Double {
        var x = x0
        var y = y0
        if (x < 0) x = -x
        if (y < 0) y = -y
        if (x >= s.width) x = 2 * s.width - 2 - x
        if (y >= s.height) y = 2 * s.height - 2 - y
        x = ImageOps.clamp(x, 0, s.width - 1)
        y = ImageOps.clamp(y, 0, s.height - 1)
        return s.get(x, y).toDouble()
    }
}
