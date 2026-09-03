package com.immineal.hdri360.test.suites

import com.immineal.hdri360.core.camera.Intrinsics
import com.immineal.hdri360.core.math.Vec3
import com.immineal.hdri360.test.TestCase
import com.immineal.hdri360.test.TestKit

/** Pinhole + Brown radial distortion, and field-of-view bookkeeping. */
class CameraModelSuite : TestCase {
    override fun name(): String = "camera-model"

    override fun run(t: TestKit) {
        val r = t.rng(99)

        val k = Intrinsics.pinhole(4000, 3000, 3200.0, 3200.0)
        // Pixel coordinates are fractional pixel *indices*: pixel i is centred at i,
        // so an image of w pixels spans [-0.5, w-0.5] and its centre is at (w-1)/2.
        t.near(1999.5, k.cx, 1e-9, "default principal point x is the image centre")
        t.near(1499.5, k.cy, 1e-9, "default principal point y is the image centre")

        // Optical axis maps to the principal point.
        val px = k.project(Vec3(0.0, 0.0, 1.0))
        t.check(px != null, "the optical axis projects")
        t.near(k.cx, px!![0], 1e-9, "axis projects to cx")
        t.near(k.cy, px[1], 1e-9, "axis projects to cy")

        // Anything at or behind the plane Z=0 must be rejected, not wrapped around.
        t.check(k.project(Vec3(0.0, 0.0, -1.0)) == null, "points behind the camera do not project")
        t.check(k.project(Vec3(1.0, 0.0, 0.0)) == null, "points on the image plane do not project")

        // FOV: half-width 2000 px at f=3200 -> 2*atan(2000/3200)
        t.near(2 * Math.toDegrees(Math.atan(2000.0 / 3200.0)), k.horizontalFovDeg(), 1e-9,
            "horizontal FOV")
        t.near(2 * Math.toDegrees(Math.atan(1500.0 / 3200.0)), k.verticalFovDeg(), 1e-9,
            "vertical FOV")
        t.greaterThan(k.diagonalFovDeg(), k.horizontalFovDeg(), "diagonal FOV exceeds horizontal")

        // Construction from physical sensor data (what CameraCharacteristics reports).
        val fromSensor = Intrinsics.fromSensor(4000, 3000, 5.6, 4.2, 4.3)
        t.near(4000 * 4.3 / 5.6, fromSensor.fx, 1e-6, "fx from focal length and sensor width")
        t.near(3000 * 4.3 / 4.2, fromSensor.fy, 1e-6, "fy from focal length and sensor height")

        // Construction from a stated FOV.
        val fromFov = Intrinsics.fromHorizontalFov(1920, 1080, 65.0)
        t.near(65.0, fromFov.horizontalFovDeg(), 1e-9, "FOV round trips through fromHorizontalFov")

        // --- project / unproject round trip, undistorted -------------------
        for (i in 0 until 500) {
            val u = r.nextDouble() * (k.width - 1)
            val v = r.nextDouble() * (k.height - 1)
            val dir = k.unproject(u, v)
            t.near(1.0, dir.norm(), 1e-12, "unproject returns a unit bearing")
            t.greaterThan(dir.z, 0.0, "unprojected bearing points forward")
            val back = k.project(dir)!!
            t.near(u, back[0], 1e-6, "project(unproject(u)) == u")
            t.near(v, back[1], 1e-6, "project(unproject(v)) == v")
        }

        // --- with distortion ------------------------------------------------
        val d = Intrinsics(4000, 3000, 3200.0, 3200.0, 2000.0, 1500.0, -0.12, 0.03, -0.004)
        t.check(d.hasDistortion(), "distortion flag set")
        val centre = d.project(Vec3(0.0, 0.0, 1.0))!!
        t.near(d.cx, centre[0], 1e-9, "distortion does not move the principal point")
        t.near(d.cy, centre[1], 1e-9, "distortion does not move the principal point (y)")
        var maxErr = 0.0
        for (i in 0 until 500) {
            val u = 20 + r.nextDouble() * (d.width - 41)
            val v = 20 + r.nextDouble() * (d.height - 41)
            val dir = d.unproject(u, v)
            val back = d.project(dir)!!
            maxErr = Math.max(maxErr, Math.hypot(back[0] - u, back[1] - v))
        }
        t.lessThan(maxErr, 1e-4, "distorted project/unproject round trips to sub-thousandth-pixel")
        t.note("worst distorted round-trip error: " + TestKit.fmt(maxErr) + " px")

        // Barrel distortion (negative k1) must pull the corners inward.
        val sameButUndistorted = Intrinsics(4000, 3000, 3200.0, 3200.0, 2000.0, 1500.0, 0.0, 0.0, 0.0)
        val cornerUndist = sameButUndistorted.project(Vec3(0.6, 0.45, 1.0))!!
        val cornerDist = d.project(Vec3(0.6, 0.45, 1.0))!!
        val rUndist = Math.hypot(cornerUndist[0] - 2000, cornerUndist[1] - 1500)
        val rDist = Math.hypot(cornerDist[0] - 2000, cornerDist[1] - 1500)
        t.lessThan(rDist, rUndist, "negative k1 is barrel distortion")

        // --- scaling for a downsampled working resolution -------------------
        val halfRes = k.scaled(0.5)
        t.eq(2000L, halfRes.width.toLong(), "scaled width")
        t.near(k.horizontalFovDeg(), halfRes.horizontalFovDeg(), 1e-9, "scaling preserves FOV")
        val dir = k.unproject(1234.0, 987.0)
        val dirHalf = halfRes.unproject(1234 * 0.5 - 0.25, 987 * 0.5 - 0.25)
        t.lessThan(dir.angleTo(dirHalf), 1e-9, "scaled intrinsics see the same bearing")

        // --- FOV cone used by capture planning ------------------------------
        t.near(k.diagonalFovDeg() / 2.0, Math.toDegrees(k.maxAngleFromAxisRad()), 1e-6,
            "max off-axis angle is half the diagonal FOV")
        t.check(k.isVisible(k.unproject(0.0, 0.0)), "corner bearing is visible")
        t.check(!k.isVisible(Vec3(0.0, 0.0, -1.0)), "backwards bearing is not visible")
    }
}
