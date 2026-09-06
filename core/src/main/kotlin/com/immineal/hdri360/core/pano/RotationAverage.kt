package com.immineal.hdri360.core.pano

import com.immineal.hdri360.core.math.Linalg
import com.immineal.hdri360.core.math.Mat3

/**
 * The one global rotation that best takes a solved sphere onto what was
 * independently known about it.
 *
 * ## Why this exists
 *
 * Pairwise correspondences fix a sphere's shape and say nothing at all about its
 * orientation: rotate every frame by the same amount and every match is still a
 * match. So the global orientation has to come from somewhere else, and the
 * bundle adjuster's answer was to fix the first frame - which put the whole
 * sphere's idea of up on whatever one recorded device pose said.
 *
 * A device pose is an accelerometer estimate taken while somebody holds a phone
 * at arm's length. On a real 34-direction capture the root frame's prior was
 * tilted **11.0 degrees** from the consensus of all thirty-four, and the tilt
 * implied by individual frames ranged from 0 to 18.6 degrees. Which frame won
 * the spanning tree decided how level the sphere came out.
 *
 * Confirmed twice on that capture: the sun in the finished panorama sat 7 to 12
 * degrees below where the almanac puts it for the time and place, and the
 * priors' own consensus said 11.0. Two independent measurements, one number.
 *
 * For an HDRI a tilt is not cosmetic. It is light arriving from the wrong
 * elevation for as long as the file exists.
 */
object RotationAverage {

    /**
     * The rotation G minimising the total disagreement between `G * solved[i]`
     * and `reference[i]` over the frames marked in [placed].
     *
     * Orthogonal Procrustes: the sum of `reference * solved^T` is the matrix
     * whose nearest rotation is the answer, and the nearest rotation is its
     * polar factor - taken here from the singular value decomposition, with the
     * determinant forced positive so the result is a rotation and never a
     * reflection. A reflection would fit some inputs slightly better and would
     * mirror the sphere, which is the one outcome worse than a tilted one.
     *
     * @return null when nothing is placed, rather than an identity that would
     *   look like a confident answer.
     */
    @JvmStatic
    fun align(solved: Array<Mat3>, reference: Array<Mat3>, placed: BooleanArray): Mat3? {
        val n = Math.min(solved.size, Math.min(reference.size, placed.size))
        var used = 0
        var sum = Mat3(DoubleArray(9))
        for (i in 0 until n) {
            if (!placed[i]) continue
            sum = sum.add(reference[i].mul(solved[i].transpose()))
            used++
        }
        if (used == 0) return null
        val svd = Linalg.svd3(sum)
        // U * V^T is the polar factor. If it comes out with a negative
        // determinant the middle term flips the least significant axis, which is
        // the standard correction and the only one that keeps it a rotation.
        var g = svd.u.mul(svd.v.transpose())
        if (g.det() < 0) {
            val flip = Mat3(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, -1.0))
            g = svd.u.mul(flip).mul(svd.v.transpose())
        }
        return g.orthonormalized()
    }
}
