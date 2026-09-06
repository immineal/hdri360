package com.immineal.hdri360.core.image

/**
 * A sensor's raw mosaic, turned into linear samples in [0, 1].
 *
 * This is the step that decides what "linear" means for the whole app: subtract
 * the per-channel black level, divide by the range up to white, apply the lens
 * shading the camera reported, and clamp. Everything downstream - the merge, the
 * radiance scale, the file - is built on it being right.
 *
 * It used to live in the Android layer, because it starts from a camera `Image`
 * and an `Image` only exists on a phone. So the one piece of arithmetic between
 * the sensor and everything else had no test, and that is exactly where a fault
 * sat undetected long enough to make every capture on the phone impossible to
 * finish: eight seconds a frame, on the camera thread. See [ShadingMap.Sampler].
 *
 * Splitting the buffer copy from the arithmetic buys the other half of that fix.
 * The copy has to happen on the camera thread, while the image is still alive,
 * and it is a memcpy. The arithmetic does not have to happen there at all.
 */
object RawPlane {

    /**
     * @param shorts the plane's 16-bit samples, as copied out of the image
     * @param rowStrideShorts samples per row, which is not the frame's width
     * @param subsample a power of two; whole 2x2 blocks are taken so that the
     *   mosaic phase survives - a frame subsampled off phase is not a Bayer
     *   image any more, it is four interleaved wrong ones, and nothing
     *   downstream would notice
     * @param black the four per-CFA-position black levels, in the order
     *   `(y and 1) * 2 + (x and 1)`
     * @param shading the camera's own lens shading correction, or null to leave
     *   the frame as the sensor saw it
     */
    @JvmStatic
    fun convert(shorts: ShortArray, rowStrideShorts: Int, width: Int, height: Int,
                subsample: Int, black: DoubleArray, white: Double,
                pattern: CfaPattern, shading: ShadingMap?): ImageF {
        if (subsample < 1 || (subsample and (subsample - 1)) != 0)
            throw IllegalArgumentException("subsample must be a positive power of two")
        if (width <= 0 || height <= 0)
            throw IllegalArgumentException("a frame needs positive dimensions")
        if (black.size < 4) throw IllegalArgumentException("four black levels, one per CFA position")
        if (rowStrideShorts < width)
            throw IllegalArgumentException("a row stride shorter than the frame")
        if (shorts.size < rowStrideShorts.toLong() * height)
            throw IllegalArgumentException(
                "a buffer of ${shorts.size} cannot hold ${width}x$height at stride $rowStrideShorts")

        val outW = (width / (2 * subsample)) * 2
        val outH = (height / (2 * subsample)) * 2
        if (outW <= 0 || outH <= 0) throw IllegalArgumentException("subsampling leaves no image")
        val out = ImageF(outW, outH, 1)

        // Tabulated once: the shading sampler needs the same mapping, and it
        // takes two multiplies out of three million iterations.
        val sensorX = IntArray(outW) { (it / 2) * 2 * subsample + (it and 1) }
        val sensorY = IntArray(outH) { (it / 2) * 2 * subsample + (it and 1) }
        val sampler = shading?.samplerFor(pattern, sensorX, sensorY, width, height)

        for (y in 0 until outH) {
            val sy = sensorY[y]
            val rowBase = sy * rowStrideShorts
            val blackBase = (sy and 1) * 2
            val outBase = y * outW
            for (x in 0 until outW) {
                val sx = sensorX[x]
                val raw = shorts[rowBase + sx].toInt() and 0xFFFF
                val b = black[blackBase + (sx and 1)]
                out.data[outBase + x] = ((raw - b) / Math.max(1.0, white - b)).toFloat()
            }
            sampler?.applyRow(y, out.data, outBase, outW)
            // Clamped after the gain, not before: a corner sample at 0.99 with a
            // gain of five is a clipped highlight, and clamping first would hide
            // that it ever left the range.
            for (x in 0 until outW) {
                val v = out.data[outBase + x]
                if (v < 0f) out.data[outBase + x] = 0f
                else if (v > 1f) out.data[outBase + x] = 1f
            }
        }
        return out
    }
}
