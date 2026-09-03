package com.immineal.hdri360.core.pipeline

import com.immineal.hdri360.core.image.ImageF
import com.immineal.hdri360.core.image.ImageOps

/**
 * Builds the image the corner detector actually looks at.
 *
 * Merged radiance is linear and spans many orders of magnitude, so a fixed
 * intensity threshold on it would fire only in the sky and never in the shadows.
 * Taking the log and normalising against the frame's own percentiles gives a
 * perceptually even field where one threshold means the same thing everywhere -
 * which is precisely the property FAST assumes.
 */
class DetectionImage private constructor(
    @JvmField val image: ImageF,
    /** Scale from the original frame to this one, for mapping keypoints back. */
    @JvmField val scale: Double
) {
    companion object {
        @JvmStatic
        fun build(radiance: ImageF, maxWidth: Int): DetectionImage {
            val lum = if (radiance.channels >= 3) ImageOps.luminance(radiance) else radiance
            var small = lum
            var scale = 1.0
            while (small.width > maxWidth && small.width >= 2 && small.height >= 2) {
                small = ImageOps.downsample2x(small)
                scale *= 0.5
            }

            val log = small.sameShape()
            for (i in small.data.indices)
                log.data[i] = Math.log(Math.max(1e-8, small.data[i].toDouble())).toFloat()

            val lo = ImageOps.percentile(log, 0, 0.02)
            val hi = ImageOps.percentile(log, 0, 0.98)
            val span = Math.max(1e-3f, hi - lo)
            val out = log.sameShape()
            for (i in log.data.indices)
                out.data[i] = Math.max(0f, Math.min(1f, (log.data[i] - lo) / span))
            return DetectionImage(out, scale)
        }
    }
}
