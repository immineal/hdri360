package com.immineal.hdri360.core.pano

import com.immineal.hdri360.core.math.Linalg
import com.immineal.hdri360.core.math.Mat3
import com.immineal.hdri360.core.math.SO3
import com.immineal.hdri360.core.math.Vec3

/**
 * Finds which way is up when nothing else will say.
 *
 * A stitch driven purely by image features knows the frames' orientations
 * relative to each other and nothing about the world: its gauge is whichever
 * frame happened to be processed first. For an HDRI that is not a cosmetic
 * problem - lighting rendered from a panorama whose horizon is tilted puts the
 * sun in the wrong place.
 *
 * The recoverable signal is that people do not roll a phone while sweeping it
 * around: each frame's horizontal axis stays close to the horizontal plane, so
 * gravity is the direction most nearly perpendicular to all of them. That is the
 * smallest eigenvector of the scatter matrix of those axes, and the ratio
 * between the remaining eigenvalues says whether the sweep covered enough
 * headings for the answer to mean anything.
 */
object HorizonEstimator {

    class Result internal constructor(
        /** Estimated world up, in the same gauge as the input poses. */
        @JvmField val up: Vec3,
        /** 0 when the sweep cannot determine the horizon, approaching 1 when it can. */
        @JvmField val confidence: Double,
        /** Root-mean-square tilt of the frames' horizontal axes out of the horizontal plane. */
        @JvmField val rollResidualDeg: Double
    ) {
        /** Minimal rotation taking the estimated up onto +Y. */
        fun levelingRotation(): Mat3 = rotationTakingTo(up, Vec3(0.0, 1.0, 0.0))
    }

    /** Below this the scatter matrix has no usable second direction and the fallback is used. */
    private const val CONFIDENCE_FLOOR = 0.05

    @JvmStatic
    fun estimate(rotations: List<Mat3>?): Result {
        if (rotations == null || rotations.isEmpty())
            throw IllegalArgumentException("no poses to estimate a horizon from")

        val m = DoubleArray(9)
        var meanUp = Vec3.ZERO
        for (R in rotations) {
            val x = R.mul(Vec3(1.0, 0.0, 0.0))       // the frame's horizontal axis, in world
            m[0] += x.x * x.x; m[1] += x.x * x.y; m[2] += x.x * x.z
            m[3] += x.y * x.x; m[4] += x.y * x.y; m[5] += x.y * x.z
            m[6] += x.z * x.x; m[7] += x.z * x.y; m[8] += x.z * x.z
            meanUp = meanUp.add(R.mul(Vec3(0.0, -1.0, 0.0)))   // the frame's own idea of up
        }
        val svd = Linalg.svd3(Mat3(m))
        val s0 = svd.s[0]
        val s1 = svd.s[1]
        val s2 = svd.s[2]
        val confidence = if (s0 > 0) Math.max(0.0, (s1 - s2) / s0) else 0.0

        var up: Vec3
        if (confidence < CONFIDENCE_FLOOR) {
            // Not enough spread in heading: fall back on where the frames themselves
            // think up is, which is right whenever they were held roughly level.
            up = if (meanUp.normSq() > 1e-12) meanUp.normalized() else Vec3(0.0, 1.0, 0.0)
        } else {
            up = svd.v.col(2).normalized()
            if (up.dot(meanUp) < 0) up = up.negate()
        }

        var sumSq = 0.0
        for (R in rotations) {
            val tilt = Math.asin(Math.max(-1.0,
                Math.min(1.0, R.mul(Vec3(1.0, 0.0, 0.0)).dot(up))))
            sumSq += tilt * tilt
        }
        val rollResidual = Math.toDegrees(Math.sqrt(sumSq / rotations.size))
        return Result(up, confidence, rollResidual)
    }

    /** Shortest rotation carrying [from] onto [to]. */
    @JvmStatic
    fun rotationTakingTo(from: Vec3, to: Vec3): Mat3 {
        val a = from.normalized()
        val b = to.normalized()
        val axis = a.cross(b)
        val sin = axis.norm()
        val cos = a.dot(b)
        if (sin < 1e-12) {
            if (cos > 0) return Mat3.IDENTITY
            return SO3.exp(a.anyPerpendicular().scale(Math.PI))   // exactly opposed
        }
        val angle = Math.atan2(sin, cos)
        return SO3.exp(axis.normalized().scale(angle))
    }
}
