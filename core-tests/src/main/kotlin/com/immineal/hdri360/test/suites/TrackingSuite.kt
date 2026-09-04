package com.immineal.hdri360.test.suites

import com.immineal.hdri360.core.camera.Intrinsics
import com.immineal.hdri360.core.image.ImageF
import com.immineal.hdri360.core.math.Mat3
import com.immineal.hdri360.core.math.SO3
import com.immineal.hdri360.core.math.Vec3
import com.immineal.hdri360.core.pano.VisualTracker
import com.immineal.hdri360.test.TestCase
import com.immineal.hdri360.test.TestKit
import java.util.Random

/**
 * What the camera can tell the app about how it moved.
 *
 * Two claims, and they are different claims. That the rotation between two views
 * is recoverable from the views themselves, far more tightly than a gyro manages
 * - which is what stops the markers wandering off the thing they point at. And
 * that a camera which has *moved* rather than turned says so, in the residual
 * left after the best rotation, with the direction it moved in.
 *
 * The scene is a cloud of points at assorted depths, rendered as dots. Depth is
 * the whole point: a rotation moves near and far points identically and a
 * translation does not, so a test whose points all sit at one distance would pass
 * with the parallax term deleted.
 */
class TrackingSuite : TestCase {

    override fun name(): String = "tracking"

    override fun run(t: TestKit) {
        rotationFromThePicturesAlone(t)
        aPhoneThatTurnsLeavesNothingOver(t)
        featurelessFramesSayNothingRatherThanGuessing(t)
    }

    // ---------------------------------------------------------------- fixtures

    private val k = Intrinsics.fromHorizontalFov(320, 240, 65.0)

    /** World points at a spread of depths, in front of the camera's start pose. */
    private fun cloud(seed: Long, n: Int = 260): List<Vec3> {
        val r = Random(seed)
        val out = ArrayList<Vec3>(n)
        while (out.size < n) {
            // A patch of directions the camera can see, at depths from 0.6 m to 8 m.
            val x = (r.nextDouble() * 2 - 1) * 0.85
            val y = (r.nextDouble() * 2 - 1) * 0.65
            val z = 0.6 + r.nextDouble() * 7.4
            out.add(Vec3(x * z, y * z, z))
        }
        return out
    }

    /**
     * The cloud as the camera at [pose]/[centre] sees it: a dot per visible point.
     *
     * Dots rather than a texture, because what is being tested is geometry. Each
     * is drawn with a soft edge so the corner detector has something to lock onto
     * and the descriptor has a neighbourhood that is not a step.
     */
    private fun render(points: List<Vec3>, rotation: Mat3, centre: Vec3): ImageF {
        val im = ImageF(320, 240, 1)
        java.util.Arrays.fill(im.data, 0.12f)
        for (p in points) {
            val local = rotation.mulTranspose(p.sub(centre))
            if (local.z < 0.2) continue
            val uv = k.project(local) ?: continue
            val cx = uv[0]
            val cy = uv[1]
            if (cx < 3 || cy < 3 || cx > im.width - 4 || cy > im.height - 4) continue
            // Deterministic brightness per point, so a dot looks the same from
            // both views and the descriptor has something to match on.
            val bright = 0.35f + 0.6f * (((p.hashCode() ushr 3) and 0xFF) / 255.0f)
            for (dy in -2..2) for (dx in -2..2) {
                val d = Math.hypot(dx.toDouble(), dy.toDouble())
                if (d > 2.5) continue
                val w = (1.0 - d / 2.5).toFloat()
                val ix = (cx + dx).toInt()
                val iy = (cy + dy).toInt()
                val at = iy * im.width + ix
                im.data[at] = Math.max(im.data[at], 0.12f + bright * w)
            }
        }
        return im
    }

    // ------------------------------------------------------------------- tests

    /**
     * A pure turn about a known axis, recovered from two pictures of it.
     *
     * The bar is the one that matters in use: better than the rotation vector,
     * which is a degree or two out and wanders. Tenths of a degree, from the
     * pictures, is what keeps a marker on the thing it is pointing at.
     */
    private fun rotationFromThePicturesAlone(t: TestKit) {
        val points = cloud(11)
        var worst = 0.0
        for (deg in doubleArrayOf(0.4, 1.5, 4.0, 8.0)) {
            val truth = SO3.exp(Vec3(0.0, Math.toRadians(deg), 0.0))
            val tracker = VisualTracker(k)
            t.check(tracker.track(render(points, Mat3.IDENTITY, Vec3.ZERO)) == null,
                "the first frame has nothing to compare against")
            val m = tracker.track(render(points, truth, Vec3.ZERO))
            t.check(m != null, "a turn of $deg degrees is tracked")
            // The tracker reports old-frame bearings mapped into the new frame,
            // which is the inverse of the camera's own turn.
            val err = Math.toDegrees(SO3.log(m!!.rotation.mul(truth)).norm())
            worst = Math.max(worst, err)
            t.lessThan(err, 0.2, "the turn of $deg degrees is recovered to a fifth of a degree")
            t.greaterThan(m.inliers.toDouble(), 30.0, "on plenty of inliers at $deg degrees")
        }
        t.note("visual rotation: worst error " + TestKit.fmt(worst) +
                " degrees over turns of 0.4 to 8 degrees")
    }

    /**
     * Turning on the spot is what a panorama is, and it must look like nothing
     * but a turn. This is also the baseline the translation signal will be
     * measured against, so it is worth pinning before there is one.
     */
    private fun aPhoneThatTurnsLeavesNothingOver(t: TestKit) {
        val points = cloud(23)
        val tracker = VisualTracker(k)
        tracker.track(render(points, Mat3.IDENTITY, Vec3.ZERO))
        var worst = 0.0
        for (deg in doubleArrayOf(2.0, 5.0, 9.0)) {
            val m = tracker.track(render(points,
                SO3.exp(Vec3(0.0, Math.toRadians(deg), 0.0)), Vec3.ZERO))
            t.check(m != null, "the turn at $deg degrees is tracked")
            worst = Math.max(worst, Math.toDegrees(m!!.residualRad))
        }
        t.lessThan(worst, 0.25,
            "a phone turning on the spot leaves under a quarter degree unexplained")
        t.note("pure rotation: worst leftover " + TestKit.fmt(worst) + " degrees")
    }

    /** A blank wall carries no information. Saying so is the correct answer. */
    private fun featurelessFramesSayNothingRatherThanGuessing(t: TestKit) {
        val flat = ImageF(320, 240, 1)
        java.util.Arrays.fill(flat.data, 0.4f)
        val tracker = VisualTracker(k)
        t.check(tracker.track(flat) == null, "the first blank frame reports nothing")
        t.check(tracker.track(flat) == null, "and so does the second, rather than a guess")

        // And it recovers once there is something to look at again.
        val points = cloud(59)
        tracker.reset()
        tracker.track(render(points, Mat3.IDENTITY, Vec3.ZERO))
        val m = tracker.track(render(points, SO3.exp(Vec3(0.0, 0.05, 0.0)), Vec3.ZERO))
        t.check(m != null, "tracking resumes when the scene has texture again")
    }
}
