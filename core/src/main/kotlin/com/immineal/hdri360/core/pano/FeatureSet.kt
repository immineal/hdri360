package com.immineal.hdri360.core.pano

import com.immineal.hdri360.core.image.ImageF
import com.immineal.hdri360.core.image.ImageOps
import java.util.Collections
import java.util.Random

/**
 * Keypoints with 256-bit steered BRIEF descriptors.
 *
 * The sampling pattern is rotated by each keypoint's intensity-centroid
 * orientation, which is what lets a handheld sweep - where the camera inevitably
 * rolls between frames - still match.
 */
class FeatureSet private constructor(
    @JvmField val keypoints: List<Keypoint>,
    /** BITS bits per keypoint, packed into 4 longs. */
    @JvmField val descriptors: Array<LongArray>
) {
    fun size(): Int = keypoints.size

    companion object {
        /** Patch half-width for both orientation and description. */
        const val PATCH_RADIUS = 15
        const val BITS = 256

        /** Fixed sampling pattern: Gaussian-distributed point pairs inside the patch. */
        private val PATTERN: Array<FloatArray> = buildPattern()

        private fun buildPattern(): Array<FloatArray> {
            val rng = Random(0xB21EFL)       // fixed so descriptors are portable
            val p = Array(BITS) { FloatArray(4) }
            val sigma = PATCH_RADIUS / 2.6
            for (i in 0 until BITS) {
                for (k in 0 until 4) {
                    val v = rng.nextGaussian() * sigma
                    p[i][k] = Math.max(-PATCH_RADIUS.toDouble(),
                        Math.min(PATCH_RADIUS.toDouble(), v)).toFloat()
                }
            }
            return p
        }

        @JvmStatic
        fun describe(gray: ImageF, keypoints: List<Keypoint>): FeatureSet {
            // BRIEF compares single pixels, so it is noise-sensitive by construction;
            // the customary fix is to describe a smoothed copy of the image.
            val smooth = ImageOps.gaussianBlur(gray, 2.0)
            val oriented = ArrayList<Keypoint>(keypoints.size)
            val desc = Array(keypoints.size) { LongArray(4) }

            for (i in keypoints.indices) {
                val kp = keypoints[i]
                val angle = orientation(smooth, kp.x.toDouble(), kp.y.toDouble()).toFloat()
                oriented.add(kp.withAngle(angle))
                val cos = Math.cos(angle.toDouble())
                val sin = Math.sin(angle.toDouble())
                val bits = desc[i]
                for (bit in 0 until BITS) {
                    val q = PATTERN[bit]
                    val ax = kp.x + q[0] * cos - q[1] * sin
                    val ay = kp.y + q[0] * sin + q[1] * cos
                    val bx = kp.x + q[2] * cos - q[3] * sin
                    val by = kp.y + q[2] * sin + q[3] * cos
                    if (smooth.sampleBilinear(ax, ay, 0) < smooth.sampleBilinear(bx, by, 0))
                        bits[bit shr 6] = bits[bit shr 6] or (1L shl (bit and 63))
                }
            }
            return FeatureSet(Collections.unmodifiableList(oriented), desc)
        }

        /** Intensity-centroid orientation over a disc of PATCH_RADIUS. */
        private fun orientation(img: ImageF, cx: Double, cy: Double): Double {
            var m10 = 0.0
            var m01 = 0.0
            val r = PATCH_RADIUS
            for (dy in -r..r) {
                val span = Math.sqrt((r * r - dy * dy).toDouble()).toInt()
                for (dx in -span..span) {
                    val v = img.sampleBilinear(cx + dx, cy + dy, 0).toDouble()
                    m10 += dx * v
                    m01 += dy * v
                }
            }
            return Math.atan2(m01, m10)
        }
    }
}
