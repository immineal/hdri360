package com.immineal.hdri360.device

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.LensShadingMap
import android.media.Image
import com.immineal.hdri360.core.image.CfaPattern
import com.immineal.hdri360.core.image.ImageF
import com.immineal.hdri360.core.image.RawPlane
import com.immineal.hdri360.core.image.ShadingMap
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
    /**
     * Everything about a RAW frame that outlives the camera's own buffer.
     *
     * The `Image` has to be closed promptly - there are eight buffers and a burst
     * of five - and until it is closed the camera cannot fill the next one. So the
     * camera thread does the one thing that has to happen there, a bulk copy of
     * the plane, and hands this on. The arithmetic that follows is in
     * [RawPlane.convert], in the core, off this thread and under test.
     */
    class RawFrame(
        @JvmField val shorts: ShortArray,
        @JvmField val rowStrideShorts: Int,
        @JvmField val width: Int,
        @JvmField val height: Int,
        @JvmField val black: DoubleArray,
        @JvmField val white: Double,
        @JvmField val pattern: CfaPattern,
        @JvmField val shading: ShadingMap?
    ) {
        fun convert(subsample: Int): ImageF = RawPlane.convert(
            shorts, rowStrideShorts, width, height, subsample, black, white, pattern, shading)
    }

    /**
     * Copies a RAW plane out of the camera's buffer, so the image can be closed.
     *
     * A bulk `get` and not three million indexed ones: the strided per-sample
     * read of a direct buffer measured 109 ms a frame on a Pixel 9a, and this is
     * a memcpy.
     */
    @JvmStatic
    fun rawFrameOf(image: Image, c: CameraCharacteristics, result: TotalCaptureResult,
                   applyShading: Boolean = true): RawFrame {
        val plane = image.planes[0]
        val buf = plane.buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
        val shorts = ShortArray(buf.remaining())
        buf.get(shorts)
        return RawFrame(shorts, plane.rowStride / 2, image.width, image.height,
            blackLevelOf(c, result),
            (c.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023).toDouble(),
            CameraProbe.cfaOf(c),
            if (applyShading) shadingOf(result) else null)
    }

    @JvmStatic
    @JvmOverloads
    fun rawPlane(image: Image, c: CameraCharacteristics, result: TotalCaptureResult,
                 subsample: Int, applyShading: Boolean = true): ImageF =
        rawFrameOf(image, c, result, applyShading).convert(subsample)


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
    /**
     * The camera's lens shading correction, in the core's own type.
     *
     * Public because the map has to be *recorded* as well as applied: it is a
     * factory measurement the camera reports at capture time and nowhere else,
     * and the DNG bundle is raw - so a capture whose map was not written down
     * cannot be reproduced off the phone.
     */
    @JvmStatic
    fun shadingOf(result: TotalCaptureResult): ShadingMap? {
        val map: LensShadingMap = result.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)
            ?: return null
        val mw = map.columnCount
        val mh = map.rowCount
        if (mw < 2 || mh < 2) return null
        val gains = FloatArray(mw * mh * 4)
        map.copyGainFactors(gains, 0)
        return try { ShadingMap(DoubleArray(gains.size) { gains[it].toDouble() }, mw, mh) }
               catch (e: Exception) { null }
    }
}
