package com.immineal.hdri360.device

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.LensShadingMap
import android.media.Image
import com.immineal.hdri360.core.image.CfaPattern
import com.immineal.hdri360.core.image.ImageF
import java.nio.ByteOrder

/**
 * Camera frames into the linear float images the pipeline expects.
 *
 * Nothing here gamma-encodes, tone maps, sharpens or white-balances beyond a
 * per-channel gain. Linearity is the product: a pixel out of the RAW path is a
 * plain fraction of full well, which is exactly the quantity the merge's
 * exposure division assumes it is dividing.
 */
object FrameConverters {

    /**
     * A RAW_SENSOR frame as a normalised linear Bayer plane.
     *
     * Black level comes off first, per CFA position, and what is left is scaled
     * by the white level. [subsample] must be a power of two and is applied in
     * whole 2x2 blocks so the mosaic phase survives - a frame subsampled off
     * phase is not a Bayer image any more, it is four interleaved wrong ones.
     */
    @JvmStatic
    @JvmOverloads
    fun rawPlane(image: Image, c: CameraCharacteristics, result: TotalCaptureResult,
                 subsample: Int, applyShading: Boolean = true): ImageF {
        if (subsample < 1 || (subsample and (subsample - 1)) != 0)
            throw IllegalArgumentException("subsample must be a power of two")
        val plane = image.planes[0]
        val shorts = plane.buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
        val rowStrideShorts = plane.rowStride / 2

        val outW = (image.width / (2 * subsample)) * 2
        val outH = (image.height / (2 * subsample)) * 2
        if (outW <= 0 || outH <= 0) throw IllegalArgumentException("subsampling leaves no image")
        val out = ImageF(outW, outH, 1)

        val black = blackLevelOf(c, result)
        val white = (c.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023).toDouble()
        val shading = if (applyShading) shadingOf(c, result, image.width, image.height) else null
        val pattern = CameraProbe.cfaOf(c)

        for (y in 0 until outH) {
            // Whole blocks in, whole blocks out: the 2x2 phase is preserved.
            val sy = (y / 2) * 2 * subsample + (y and 1)
            for (x in 0 until outW) {
                val sx = (x / 2) * 2 * subsample + (x and 1)
                val raw = shorts.get(sy * rowStrideShorts + sx).toInt() and 0xFFFF
                val b = black[(sy and 1) * 2 + (sx and 1)]
                var v = (raw - b) / Math.max(1.0, white - b)
                if (shading != null) v *= shading.gainAt(pattern, sx, sy)
                out.data[y * outW + x] = Math.max(0.0, Math.min(1.0, v)).toFloat()
            }
        }
        return out
    }

    /** Luma only, normalised. Cheap enough to run on every metering frame. */
    @JvmStatic
    fun luma(image: Image, subsample: Int): ImageF {
        val s = Math.max(1, subsample)
        val w = Math.max(1, image.width / s)
        val h = Math.max(1, image.height / s)
        val y = image.planes[0]
        val buf = y.buffer
        val rowStride = y.rowStride
        val pixelStride = y.pixelStride
        val out = ImageF(w, h, 1)
        for (j in 0 until h) {
            val base = j * s * rowStride
            for (i in 0 until w) {
                val v = buf.get(base + i * s * pixelStride).toInt() and 0xFF
                out.data[j * w + i] = v / 255.0f
            }
        }
        return out
    }

    /**
     * Full colour, BT.601 full range - still in whatever tone curve the camera
     * applied, which is why anything downstream of this has to recover the
     * response from the bracket before treating it as radiance.
     */
    @JvmStatic
    fun rgb(image: Image, subsample: Int): ImageF {
        val s = Math.max(1, subsample)
        val w = Math.max(1, image.width / s)
        val h = Math.max(1, image.height / s)
        val p = image.planes
        val yBuf = p[0].buffer; val uBuf = p[1].buffer; val vBuf = p[2].buffer
        val yRow = p[0].rowStride; val yPix = p[0].pixelStride
        val uRow = p[1].rowStride; val uPix = p[1].pixelStride
        val vRow = p[2].rowStride; val vPix = p[2].pixelStride
        val out = ImageF(w, h, 3)
        for (j in 0 until h) {
            val sy = j * s
            for (i in 0 until w) {
                val sx = i * s
                val yy = (yBuf.get(sy * yRow + sx * yPix).toInt() and 0xFF).toFloat()
                val u = ((uBuf.get((sy / 2) * uRow + (sx / 2) * uPix).toInt() and 0xFF) - 128).toFloat()
                val v = ((vBuf.get((sy / 2) * vRow + (sx / 2) * vPix).toInt() and 0xFF) - 128).toFloat()
                val base = (j * w + i) * 3
                out.data[base] = clamp((yy + 1.402f * v) / 255f)
                out.data[base + 1] = clamp((yy - 0.344136f * u - 0.714136f * v) / 255f)
                out.data[base + 2] = clamp((yy + 1.772f * u) / 255f)
            }
        }
        return out
    }

    /** Per-channel gains from the result, greens averaged since the demosaic merges them. */
    @JvmStatic
    fun neutralGainsOf(result: CaptureResult): FloatArray? {
        val v = result.get(CaptureResult.COLOR_CORRECTION_GAINS) ?: return null
        return floatArrayOf(v.red, 0.5f * (v.greenEven + v.greenOdd), v.blue)
    }

    private fun clamp(v: Float): Float = if (v < 0f) 0f else if (v > 1f) 1f else v

    /**
     * Black level, preferring what this exposure actually measured.
     *
     * A per-frame dynamic black level is measured from the sensor's shielded
     * pixels at this ISO and this temperature. The static pattern is a nominal
     * figure, and at a long exposure on a hot phone it can be several counts out
     * - which at the bottom of the bracket is where the shadow radiance lives.
     */
    private fun blackLevelOf(c: CameraCharacteristics, result: TotalCaptureResult): DoubleArray {
        val dynamic = result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
        if (dynamic != null && dynamic.size >= 4)
            return DoubleArray(4) { dynamic[it].toDouble() }
        return CameraProbe.blackLevelOf(c)
    }

    /**
     * The camera's own measured lens shading, which beats fitting a radial
     * polynomial: it is per-channel, it captures decentring a symmetric model
     * cannot, and it has already been computed.
     */
    private fun shadingOf(c: CameraCharacteristics, result: TotalCaptureResult,
                          width: Int, height: Int): Shading? {
        val map: LensShadingMap = result.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)
            ?: return null
        val mw = map.columnCount
        val mh = map.rowCount
        if (mw < 2 || mh < 2) return null
        val gains = FloatArray(mw * mh * 4)
        map.copyGainFactors(gains, 0)
        return Shading(gains, mw, mh, width, height)
    }

    private class Shading(
        private val gains: FloatArray,
        private val mw: Int,
        private val mh: Int,
        private val width: Int,
        private val height: Int
    ) {
        fun gainAt(pattern: CfaPattern, x: Int, y: Int): Double {
            val fx = (x / Math.max(1, width - 1).toDouble()) * (mw - 1)
            val fy = (y / Math.max(1, height - 1).toDouble()) * (mh - 1)
            return bilinear(planeOf(pattern, x, y), fx, fy)
        }

        /** Shading maps are ordered R, Gr, Gb, B whatever the CFA phase happens to be. */
        private fun planeOf(pattern: CfaPattern, x: Int, y: Int): Int {
            val colour = pattern.colorAt(x, y)
            if (colour == 0) return 0
            if (colour == 2) return 3
            // Two greens: the one sharing a row with red is Gr.
            val redRow = pattern.colorAt(x + 1, y) == 0 ||
                pattern.colorAt(if (x - 1 < 0) x + 1 else x - 1, y) == 0
            return if (redRow) 1 else 2
        }

        private fun bilinear(plane: Int, fx: Double, fy: Double): Double {
            var x0 = Math.floor(fx).toInt()
            var y0 = Math.floor(fy).toInt()
            val x1 = Math.min(mw - 1, x0 + 1)
            val y1 = Math.min(mh - 1, y0 + 1)
            x0 = Math.max(0, Math.min(mw - 1, x0))
            y0 = Math.max(0, Math.min(mh - 1, y0))
            val tx = fx - x0
            val ty = fy - y0
            val g00 = gains[(y0 * mw + x0) * 4 + plane]
            val g10 = gains[(y0 * mw + x1) * 4 + plane]
            val g01 = gains[(y1 * mw + x0) * 4 + plane]
            val g11 = gains[(y1 * mw + x1) * 4 + plane]
            val top = g00 + (g10 - g00) * tx
            val bot = g01 + (g11 - g01) * tx
            return top + (bot - top) * ty
        }
    }
}
