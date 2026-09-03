package com.immineal.hdri360.test.suites

import com.immineal.hdri360.core.math.Vec3
import com.immineal.hdri360.core.pano.Equirect
import com.immineal.hdri360.test.TestCase
import com.immineal.hdri360.test.TestKit

/** The equirectangular parameterisation the HDRI is written in. */
class EquirectSuite : TestCase {
    override fun name(): String = "equirect"

    override fun run(t: TestKit) {
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
}
