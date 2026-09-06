package com.immineal.hdri360.core.image

/**
 * The camera's own lens shading correction, as a coarse grid of per-channel
 * gains to be interpolated over the frame.
 *
 * A phone lens loses well over a stop at the corners, and the camera measures
 * that at the factory and reports the correction. The app applies it as it
 * converts each RAW frame, so the frames it stitches are already flat - which
 * matters twice over: a radiance map with a dark ring in every frame is not a
 * measurement, and the feature matcher works hardest at the frame edges, which
 * is exactly where the loss is.
 *
 * In the core, and not in the Android layer where it started, because a capture
 * bundle has to be re-processable off the phone. Without the map recorded, the
 * DNGs are raw and the desktop path stitches frames whose corners are a stop
 * darker than the ones the phone stitched - which on a real capture solved 55
 * pairs in four pieces where the phone solved 62 in one, and produced a sphere
 * forty degrees away in heading. An instrument that cannot reproduce what it
 * measures is not much of an instrument.
 */
class ShadingMap(
    /**
     * Gains for each grid node, four per node, in the order Android reports
     * them: R, Gr, Gb, B - whatever the sensor's own CFA phase happens to be.
     */
    gains: DoubleArray,
    @JvmField val columns: Int,
    @JvmField val rows: Int
) {
    @JvmField val gains: DoubleArray = gains.copyOf()

    init {
        if (columns < 2 || rows < 2)
            throw IllegalArgumentException("a shading map needs at least a 2x2 grid")
        if (gains.size != columns * rows * 4)
            throw IllegalArgumentException(
                "a ${columns}x$rows map needs ${columns * rows * 4} gains, got ${gains.size}")
    }

    /**
     * The gain at one sensor pixel, bilinear between grid nodes.
     *
     * [width] and [height] are the frame the map is being stretched over, which
     * is not the grid's own size: the grid is a dozen nodes across and the frame
     * is four thousand pixels.
     */
    fun gainAt(pattern: CfaPattern, x: Int, y: Int, width: Int, height: Int): Double {
        val fx = (x / Math.max(1, width - 1).toDouble()) * (columns - 1)
        val fy = (y / Math.max(1, height - 1).toDouble()) * (rows - 1)
        return bilinear(planeOf(pattern, x, y), fx, fy)
    }

    /**
     * Which of the four gains applies at a pixel.
     *
     * The map's channels are R, Gr, Gb, B regardless of how the CFA is laid out,
     * so the phase has to be read off the pattern rather than assumed: Gr is the
     * green that shares a row with red, Gb the one that shares a row with blue.
     * Getting these two the wrong way round is invisible in a flat field and
     * shows up as a faint checkerboard everywhere else.
     */
    fun planeOf(pattern: CfaPattern, x: Int, y: Int): Int {
        val colour = pattern.colorAt(x, y)
        if (colour == 0) return 0
        if (colour == 2) return 3
        val redRow = pattern.colorAt(x + 1, y) == 0 ||
            pattern.colorAt(if (x - 1 < 0) x + 1 else x - 1, y) == 0
        return if (redRow) 1 else 2
    }

    private fun bilinear(plane: Int, fx: Double, fy: Double): Double {
        var x0 = Math.floor(fx).toInt()
        var y0 = Math.floor(fy).toInt()
        val x1 = Math.min(columns - 1, x0 + 1)
        val y1 = Math.min(rows - 1, y0 + 1)
        x0 = Math.max(0, Math.min(columns - 1, x0))
        y0 = Math.max(0, Math.min(rows - 1, y0))
        val tx = fx - x0
        val ty = fy - y0
        val g00 = gains[(y0 * columns + x0) * 4 + plane]
        val g10 = gains[(y0 * columns + x1) * 4 + plane]
        val g01 = gains[(y1 * columns + x0) * 4 + plane]
        val g11 = gains[(y1 * columns + x1) * 4 + plane]
        val top = g00 + (g10 - g00) * tx
        val bot = g01 + (g11 - g01) * tx
        return top + (bot - top) * ty
    }

    /**
     * A sampler for one fixed output geometry, which is where this correction has
     * to be applied from.
     *
     * [gainAt] is the readable definition and stays the reference. It is also, per
     * pixel, two floating point divisions, a floor, three nested calls and four
     * array reads - and a phone frame is three million pixels. Measured on a
     * Pixel 9a, from the app's own log:
     *
     *     convert breakdown: buffer reads 109 ms, shading lookups 8042 ms
     *
     * Eight seconds a frame, on the camera thread - which is the thread the next
     * frame of the burst has to arrive on. Four rungs took thirty-two seconds,
     * the burst outlived its twelve second timeout, and not one capture on that
     * phone could finish. It went unseen until the day the shading map was first
     * recorded at all, because until then this branch never ran.
     *
     * None of that arithmetic needed to be per pixel. A frame's geometry is fixed
     * before the loop starts, so the grid indices, the interpolation weights and
     * the CFA plane are all knowable in advance; what is left inside the loop is
     * four array reads and three multiply-adds.
     *
     * [sensorX] and [sensorY] map an output column and row to the sensor pixel it
     * was read from - which is not the identity, because the converter takes whole
     * 2x2 blocks so that the CFA phase survives subsampling.
     */
    fun samplerFor(pattern: CfaPattern, sensorX: IntArray, sensorY: IntArray,
                   sensorWidth: Int, sensorHeight: Int): Sampler =
        Sampler(this, pattern, sensorX, sensorY, sensorWidth, sensorHeight)

    /** See [samplerFor]. Immutable once built, and safe to share across threads. */
    class Sampler internal constructor(
        map: ShadingMap,
        pattern: CfaPattern,
        sensorX: IntArray,
        sensorY: IntArray,
        sensorWidth: Int,
        sensorHeight: Int
    ) {
        private val gains = map.gains
        private val columns = map.columns
        @JvmField val width = sensorX.size
        @JvmField val height = sensorY.size

        private val x0 = IntArray(width)
        private val x1 = IntArray(width)
        private val tx = DoubleArray(width)
        private val y0 = IntArray(height)
        private val y1 = IntArray(height)
        private val ty = DoubleArray(height)

        /**
         * Which of the four gains applies, by the parities of the sensor
         * coordinate: `(sy and 1) shl 1 or (sx and 1)`.
         *
         * Four entries, because that is all [planeOf] depends on - it reads the
         * CFA, and a CFA repeats every two pixels. Recomputing it three million
         * times was three million calls to work out one of four answers.
         */
        private val planes = IntArray(4)

        init {
            for (i in 0 until width) {
                val f = (sensorX[i] / Math.max(1, sensorWidth - 1).toDouble()) * (columns - 1)
                var a = Math.floor(f).toInt()
                val b = Math.min(columns - 1, a + 1)
                a = Math.max(0, Math.min(columns - 1, a))
                x0[i] = a
                x1[i] = b
                tx[i] = f - a
            }
            for (j in 0 until height) {
                val f = (sensorY[j] / Math.max(1, sensorHeight - 1).toDouble()) * (map.rows - 1)
                var a = Math.floor(f).toInt()
                val b = Math.min(map.rows - 1, a + 1)
                a = Math.max(0, Math.min(map.rows - 1, a))
                y0[j] = a
                y1[j] = b
                ty[j] = f - a
            }
            // Probed at real coordinates rather than at 0 and 1, so that a
            // sensorX array which happens to start on an odd pixel is still
            // asked about the phase it actually has.
            for (k in 0 until 4) {
                val sx = (k and 1)
                val sy = (k shr 1)
                planes[k] = map.planeOf(pattern, sx, sy)
            }
        }

        /** The gain at one output pixel. No division, no floor, no call. */
        fun gain(x: Int, y: Int): Double {
            val p = planes[((sensorYParity(y)) shl 1) or sensorXParity(x)]
            return at(x, y, p)
        }

        /**
         * Multiplies [count] samples of one output row by their own gains,
         * starting at [offset]. The row is the unit because the vertical weights
         * are then loaded once instead of per pixel.
         */
        fun applyRow(y: Int, data: FloatArray, offset: Int, count: Int) {
            val pEven = planes[(sensorYParity(y) shl 1) or 0]
            val pOdd = planes[(sensorYParity(y) shl 1) or 1]
            var i = 0
            while (i < count) {
                val p = if ((xParity[i]) == 0) pEven else pOdd
                data[offset + i] = (data[offset + i] * at(i, y, p)).toFloat()
                i++
            }
        }

        private val xParity = IntArray(width)
        private val yParity = IntArray(height)

        init {
            for (i in 0 until width) xParity[i] = sensorX[i] and 1
            for (j in 0 until height) yParity[j] = sensorY[j] and 1
        }

        private fun sensorXParity(x: Int): Int = xParity[x]
        private fun sensorYParity(y: Int): Int = yParity[y]

        private fun at(x: Int, y: Int, plane: Int): Double {
            val ax = x0[x]
            val bx = x1[x]
            val ay = y0[y]
            val by = y1[y]
            val fx = tx[x]
            val fy = ty[y]
            val rowA = ay * columns
            val rowB = by * columns
            val g00 = gains[(rowA + ax) * 4 + plane]
            val g10 = gains[(rowA + bx) * 4 + plane]
            val g01 = gains[(rowB + ax) * 4 + plane]
            val g11 = gains[(rowB + bx) * 4 + plane]
            val top = g00 + (g10 - g00) * fx
            val bot = g01 + (g11 - g01) * fx
            return top + (bot - top) * fy
        }
    }

    /**
     * The correction as it applies to *demosaiced* radiance: three channels, no
     * ceiling, applied once.
     *
     * This is where the shading gain belongs, and putting it anywhere else has
     * cost this app twice. Applied to sensor fractions and clamped into [0, 1] -
     * which is what it used to do - it invents saturation and destroys signal:
     * with a map peaking at a gain of 5.03, a corner sample above `0.98 / 5.03 =
     * 0.195` came out at the saturation threshold, and a direction is declared
     * "burnt out" at a tenth of a percent of the frame. A window was re-shot for
     * a highlight that had never been lost - the detail was still there in the
     * finished sphere at minus three and a half stops.
     *
     * Saturation is a property of the sensor well. This is a multiplicative
     * correction, it commutes with the exposure scaling, and radiance has no
     * ceiling to clamp against. So it goes where the colour matrix goes: on the
     * merged radiance, once, after the merge has already weighted each rung by
     * how saturated the *sensor* was.
     *
     * The map's four planes are R, Gr, Gb, B. A demosaiced green came from both
     * green sites, so it is corrected by their mean.
     */
    fun rgbSamplerFor(width: Int, height: Int): RgbSampler = RgbSampler(this, width, height)

    /** See [rgbSamplerFor]. */
    class RgbSampler internal constructor(map: ShadingMap, width: Int, height: Int) {
        @JvmField val width = width
        @JvmField val height = height
        /** Three gains per pixel column, and per row, would be a big table; this
         *  is separable, so the weights are per axis and the gain is assembled. */
        private val gains = map.gains
        private val columns = map.columns
        private val x0 = IntArray(width)
        private val x1 = IntArray(width)
        private val tx = DoubleArray(width)
        private val y0 = IntArray(height)
        private val y1 = IntArray(height)
        private val ty = DoubleArray(height)

        init {
            if (width <= 0 || height <= 0)
                throw IllegalArgumentException("a frame needs positive dimensions")
            for (i in 0 until width) {
                val f = (i / Math.max(1, width - 1).toDouble()) * (columns - 1)
                var a = Math.floor(f).toInt()
                val b = Math.min(columns - 1, a + 1)
                a = Math.max(0, Math.min(columns - 1, a))
                x0[i] = a; x1[i] = b; tx[i] = f - a
            }
            for (j in 0 until height) {
                val f = (j / Math.max(1, height - 1).toDouble()) * (map.rows - 1)
                var a = Math.floor(f).toInt()
                val b = Math.min(map.rows - 1, a + 1)
                a = Math.max(0, Math.min(map.rows - 1, a))
                y0[j] = a; y1[j] = b; ty[j] = f - a
            }
        }

        /** Multiplies every sample of [radiance] by its own channel's gain. */
        fun apply(radiance: ImageF) {
            if (radiance.channels != 3)
                throw IllegalArgumentException(
                    "merged radiance is three channels, got ${radiance.channels}")
            if (radiance.width != width || radiance.height != height)
                throw IllegalArgumentException(
                    "this sampler is for ${width}x$height, got " +
                    "${radiance.width}x${radiance.height}")
            val d = radiance.data
            for (y in 0 until height) {
                val base = y * width * 3
                for (x in 0 until width) {
                    val i = base + x * 3
                    d[i] = (d[i] * plane(x, y, 0)).toFloat()
                    d[i + 1] = (d[i + 1] *
                        ((plane(x, y, 1) + plane(x, y, 2)) * 0.5)).toFloat()
                    d[i + 2] = (d[i + 2] * plane(x, y, 3)).toFloat()
                }
            }
        }

        /** The gain of one map plane at one pixel. */
        fun plane(x: Int, y: Int, plane: Int): Double {
            val ax = x0[x]
            val bx = x1[x]
            val ay = y0[y]
            val by = y1[y]
            val fx = tx[x]
            val fy = ty[y]
            val rowA = ay * columns
            val rowB = by * columns
            val g00 = gains[(rowA + ax) * 4 + plane]
            val g10 = gains[(rowA + bx) * 4 + plane]
            val g01 = gains[(rowB + ax) * 4 + plane]
            val g11 = gains[(rowB + bx) * 4 + plane]
            val top = g00 + (g10 - g00) * fx
            val bot = g01 + (g11 - g01) * fx
            return top + (bot - top) * fy
        }
    }

    /** Largest gain anywhere, which is the corner loss this is correcting. */
    fun peakGain(): Double {
        var m = 0.0
        for (g in gains) if (g > m) m = g
        return m
    }

    override fun toString(): String = String.format(java.util.Locale.US,
        "shading[%dx%d grid, peak gain %.2f]", columns, rows, peakGain())
}
