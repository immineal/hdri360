package com.immineal.hdri360.test.suites

import com.immineal.hdri360.core.camera.Intrinsics
import com.immineal.hdri360.core.math.Mat3
import com.immineal.hdri360.core.math.Vec3
import com.immineal.hdri360.core.pano.Equirect
import com.immineal.hdri360.test.TestCase
import com.immineal.hdri360.test.TestKit

/** The equirectangular parameterisation the HDRI is written in. */
class EquirectSuite : TestCase {
    override fun name(): String = "equirect"

    override fun run(t: TestKit) {
        whichWayIsRight(t)
        val w = 2048
        val h = 1024
        val r = t.rng(31337)

        t.eq(1024L, Equirect.heightFor(2048).toLong(), "equirect is 2:1")
        t.throwsException({ Equirect.direction(0.0, 0.0, 100, 100) }, "a non-2:1 canvas is rejected")

        // --- anchors -------------------------------------------------------
        val centre = Equirect.direction((w - 1) / 2.0, (h - 1) / 2.0, w, h)
        t.lessThan(centre.angleTo(Vec3(0.0, 0.0, 1.0)), 1e-6, "the image centre looks along +Z")
        val top = Equirect.direction(w / 2.0, -0.5, w, h)
        t.lessThan(top.angleTo(Vec3(0.0, 1.0, 0.0)), 1e-6, "the top row is the zenith")
        val bottom = Equirect.direction(w / 2.0, h - 0.5, w, h)
        t.lessThan(bottom.angleTo(Vec3(0.0, -1.0, 0.0)), 1e-6, "the bottom row is the nadir")
        val left = Equirect.direction(-0.5, (h - 1) / 2.0, w, h)
        t.lessThan(left.angleTo(Vec3(0.0, 0.0, -1.0)), 1e-6, "the left edge is due back")
        // Longitude increases to the viewer's right. With a right-handed, Y-up world
        // whose +Z is the reference heading, "right" is -X (up x forward points the
        // other way), so a quarter-turn right lands on -X. Getting this backwards
        // mirrors the whole panorama, which is why it is pinned here and again in
        // the capture-plan suite via an actual camera rotation.
        val quarter = Equirect.direction(w * 0.75 - 0.5, (h - 1) / 2.0, w, h)
        t.lessThan(quarter.angleTo(Vec3(-1.0, 0.0, 0.0)), 1e-6, "a quarter turn to the right is -X")

        // --- round trip ------------------------------------------------------
        var worst = 0.0
        for (i in 0 until 5000) {
            val u = r.nextDouble() * w - 0.5
            val v = r.nextDouble() * (h - 1)
            val d = Equirect.direction(u, v, w, h)
            t.near(1.0, d.norm(), 1e-12, "direction is a unit vector")
            val p = Equirect.pixel(d, w, h)
            var du = Math.abs(p[0] - u)
            if (du > w / 2.0) du = w - du                    // longitude wraps
            worst = Math.max(worst, Math.max(du, Math.abs(p[1] - v)))
        }
        t.lessThan(worst, 1e-6, "pixel/direction round trip")
        t.note("worst equirect round-trip error " + TestKit.fmt(worst) + " px")

        // --- horizontal wrap -------------------------------------------------
        val a = Equirect.direction(-0.5, 400.0, w, h)
        val b = Equirect.direction(w - 0.5, 400.0, w, h)
        t.lessThan(a.angleTo(b), 1e-9, "the canvas wraps seamlessly in longitude")

        // --- solid angle ------------------------------------------------------
        var total = 0.0
        for (y in 0 until h) total += Equirect.rowSolidAngle(y, w, h) * w
        t.nearRel(4 * Math.PI, total, 1e-6, "row solid angles sum to the whole sphere")
        t.greaterThan(Equirect.rowSolidAngle(h / 2, w, h), Equirect.rowSolidAngle(0, w, h),
            "pixels shrink toward the poles")

        // --- the mapping is an isometry in longitude at the equator ------------
        val p1 = Equirect.direction(1000.0, (h - 1) / 2.0, w, h)
        val p2 = Equirect.direction(1001.0, (h - 1) / 2.0, w, h)
        t.nearRel(2 * Math.PI / w, p1.angleTo(p2), 1e-6,
            "one pixel at the equator is 2*pi/width radians")
    }

    /**
     * Which way round the panorama is, stated rather than left implied.
     *
     * Everything downstream of the camera model is self-consistent, so a mirror
     * cannot be caught by comparing the pipeline with itself - which is exactly
     * how one shipped: the in-app viewer mapped screen-right onto +X and the
     * panorama puts +X on the left, so looking around showed the mirror of the
     * room while the file itself was correct. The suite passed throughout,
     * because every test compared the projection with its own inverse.
     *
     * The only cure is to write the physical fact down. Confirmed against a real
     * garden on 2026-09-05: reading the finished panorama left to right is what
     * you see turning to your **right** where you stood.
     *
     * From that everything else follows, and is asserted here so that no viewer,
     * writer or exporter can quietly disagree with it again.
     */
    private fun whichWayIsRight(t: TestKit) {
        val w = 2048
        val h = 1024

        // The world frame: +Y up, +Z the reference heading, right-handed.
        val up = Vec3(0.0, 1.0, 0.0)
        val forward = Vec3(0.0, 0.0, 1.0)
        // In a right-handed frame with those two, X x Y = Z, so X = Y x Z.
        val x = up.cross(forward)
        t.near(1.0, x.x, 1e-12, "the world's +X follows from Y x Z")
        // And "right", for somebody facing the heading with that up, is up x
        // forward the other way round: forward x up.
        val right = forward.cross(up)
        t.near(-1.0, right.x, 1e-12,
            "so the direction a person calls right is -X, and +X is their left")

        // Now the panorama. The centre column looks along the heading.
        val centre = Equirect.direction((w / 2).toDouble() - 0.5, (h / 2).toDouble() - 0.5, w, h)
        t.near(1.0, centre.z, 1e-6, "the middle of the image is the reference heading")
        t.near(0.0, centre.x, 1e-6, "dead ahead, not off to one side")

        // A quarter of the way to the right of centre is a quarter turn to the
        // right in the world. This is the assertion that the shipped viewer bug
        // would have failed.
        val toTheRight = Equirect.direction(
            (3 * w / 4).toDouble() - 0.5, (h / 2).toDouble() - 0.5, w, h)
        t.greaterThan(toTheRight.dot(right), 0.99,
            "moving right across the panorama turns right in the world")
        val toTheLeft = Equirect.direction(
            (w / 4).toDouble() - 0.5, (h / 2).toDouble() - 0.5, w, h)
        t.lessThan(toTheLeft.dot(right), -0.99, "and moving left turns left")

        // Up is up. Less likely to be got wrong and cheaper to state than to
        // rediscover.
        val above = Equirect.direction((w / 2).toDouble() - 0.5, 1.0, w, h)
        t.greaterThan(above.dot(up), 0.99, "the top of the image is overhead")
        val below = Equirect.direction((w / 2).toDouble() - 0.5, (h - 2).toDouble(), w, h)
        t.lessThan(below.dot(up), -0.99, "and the bottom is underfoot")

        // The same fact from the camera's side, which is where a mirror would
        // actually be introduced: a frame's image-right must land to the right in
        // the panorama. An upright camera looking along the heading has its own
        // +Y pointing down - image coordinates grow downward - so its +X is the
        // person's right.
        val k = Intrinsics.fromHorizontalFov(200, 150, 60.0)
        val camRight = k.unproject(199.0, 74.5)      // right edge, mid height
        t.greaterThan(camRight.x, 0.0, "a camera's image-right is its own +X")
        // Upright, looking along the heading: X_cam = -X_world, Y_cam = -Y_world.
        val cameraToWorld = Mat3.fromColumns(
            Vec3(-1.0, 0.0, 0.0), Vec3(0.0, -1.0, 0.0), Vec3(0.0, 0.0, 1.0))
        t.near(1.0, cameraToWorld.det(), 1e-12,
            "and that really is a rotation, not a reflection")
        val worldRight = cameraToWorld.mul(camRight)
        // The sign is the claim. The magnitude is fixed by the lens: the right
        // edge of a 60 degree frame is 30 degrees off axis, so this is sin(30) to
        // within the half-pixel the edge sample sits inside.
        t.greaterThan(worldRight.dot(right), 0.0,
            "so the right of a frame is the right of the room")
        t.near(Math.sin(Math.toRadians(30.0)), worldRight.dot(right), 0.01,
            "by exactly the angle the lens subtends, which is how far off axis it is")
        val px = Equirect.pixel(worldRight, w, h)
        t.greaterThan(px[0], (w / 2).toDouble(),
            "and lands right of centre in the panorama, which is the whole claim")
    }
}
