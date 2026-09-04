package com.immineal.hdri360.core.pano

import com.immineal.hdri360.core.camera.Intrinsics
import com.immineal.hdri360.core.image.ImageF
import com.immineal.hdri360.core.math.Mat3
import com.immineal.hdri360.core.math.Vec3
import java.util.Locale

/**
 * What the camera itself says about how it moved.
 *
 * The rotation vector is a fused gyro estimate: responsive, and wrong by a degree
 * or two that wanders. A degree is a visible seam, and it is also the marker
 * sliding off the thing it is pointing at while somebody is trying to hold still
 * on it. The pictures know better - the same scene, seen twice, pins the rotation
 * between the two views far more tightly than any gyro.
 *
 * The second thing the pictures know is whether the phone *moved*, as opposed to
 * turned. A panorama is a set of views from one point; step sideways between
 * frames and near things shift against far things, which no rotation can explain
 * and no stitcher can undo. That failure has a shape: after the best rotation is
 * taken out, what is left points away from the direction of travel. So the
 * residual is not just a number saying "something is wrong" - it says which way
 * the phone went, which is the thing a person can act on.
 *
 * Everything here works on small greyscale frames and the camera model. It knows
 * nothing about Android, and is driven in tests by synthesised motion.
 */
class VisualTracker @JvmOverloads constructor(
    private val intrinsics: Intrinsics,
    private val config: Config = Config()
) {

    class Config {
        @JvmField var maxFeatures = 250
        @JvmField var fastThreshold = 0.02
        /** Halved until a frame yields this many corners, as the stitcher does. */
        @JvmField var featureTargetCount = 80
        @JvmField var fastThresholdFloor = 0.002
        @JvmField var ransacThresholdDeg = 0.6
        @JvmField var ransacIterations = 400
        @JvmField var minInliers = 12
        /**
         * Bearing error, in degrees, below which the leftover motion is noise
         * rather than parallax. Two frames of a still scene from one point agree
         * to well under this once the rotation is out.
         */
        /**
         * How far a match may sit from the fitted rotation and still count as the
         * same point seen twice.
         *
         * The rotation is fitted by RANSAC, whose whole job is to throw out what
         * does not fit - and under a translation the near points are precisely
         * what does not fit. Measuring the leftover motion on the inliers alone
         * therefore measures everything except the parallax, and reports a
         * perfectly steady zero while somebody walks sideways. So the leftover is
         * measured over every match that is still plausibly the same point: a
         * mismatch is wrong by tens of degrees, parallax by a few.
         */
        @JvmField var parallaxGateDeg = 6.0
        @JvmField var seed = 90210L
    }

    /**
     * How the camera moved between two frames.
     *
     * [rotation] takes bearings in the older frame to bearings in the newer one.
     * [residualRad] is what that rotation could not account for, which is where
     * the translation signal will come from once it is earned.
     */
    class Motion(
        @JvmField val rotation: Mat3,
        @JvmField val matches: Int,
        @JvmField val inliers: Int,
        /** Mean bearing residual after the rotation fit, in radians. */
        @JvmField val residualRad: Double,
    ) {
        override fun toString(): String = String.format(Locale.US,
            "motion[%d/%d inliers, residual %.3f deg]",
            inliers, matches, Math.toDegrees(residualRad))
    }

    private var previous: FeatureSet? = null
    private var previousBearings: Array<Vec3>? = null

    /** Forgets the last frame, so the next one starts a fresh pair. */
    fun reset() {
        previous = null
        previousBearings = null
    }

    /**
     * Takes the next frame and reports the motion since the one before it.
     *
     * Returns null for the first frame, and whenever the two frames could not be
     * matched well enough to say anything - which is a real answer, not a
     * failure: a phone swung at a blank wall genuinely carries no information
     * about how it moved.
     */
    fun track(luma: ImageF): Motion? {
        val corners = detect(luma)
        val set = FeatureSet.describe(luma, corners)
        val bearings = Array(set.keypoints.size) { i ->
            val k = set.keypoints[i]
            intrinsics.unproject(k.x.toDouble(), k.y.toDouble())
        }

        val before = previous
        val beforeBearings = previousBearings
        previous = set
        previousBearings = bearings
        if (before == null || beforeBearings == null) return null

        val matches = BriefMatcher.match(before, set, BriefMatcher.Config())
        if (matches.size < config.minInliers) return null

        val from = ArrayList<Vec3>(matches.size)
        val to = ArrayList<Vec3>(matches.size)
        for (m in matches) {
            from.add(beforeBearings[m.a])
            to.add(bearings[m.b])
        }
        val fit = RotationSolver.ransac(from, to,
            Math.toRadians(config.ransacThresholdDeg), config.ransacIterations, config.seed)
            ?: return null
        if (fit.inlierCount < config.minInliers) return null

        // Everything the rotation could plausibly be describing, which is not the
        // same set as the points it was fitted on. See parallaxGateDeg.
        val gate = Math.toRadians(config.parallaxGateDeg)
        var sum = 0.0
        var n = 0
        for (i in from.indices) {
            val e = fit.rotation.mul(from[i]).angleTo(to[i])
            if (e > gate) continue
            sum += e
            n++
        }
        val residual = if (n > 0) sum / n else 0.0
        return Motion(fit.rotation, matches.size, fit.inlierCount, residual)
    }

    private fun detect(luma: ImageF): List<Keypoint> {
        val fc = FastCornerDetector.Config()
        fc.threshold = config.fastThreshold
        fc.maxFeatures = config.maxFeatures
        var corners = FastCornerDetector.detect(luma, fc)
        while (corners.size < config.featureTargetCount && fc.threshold > config.fastThresholdFloor) {
            fc.threshold = Math.max(config.fastThresholdFloor, fc.threshold / 2)
            corners = FastCornerDetector.detect(luma, fc)
        }
        return corners
    }

}
