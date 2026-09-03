package com.immineal.hdri360.test.suites

import com.immineal.hdri360.core.camera.Intrinsics
import com.immineal.hdri360.core.image.ImageF
import com.immineal.hdri360.core.image.ImageOps
import com.immineal.hdri360.core.math.Mat3
import com.immineal.hdri360.core.math.SO3
import com.immineal.hdri360.core.math.Vec3
import com.immineal.hdri360.core.pano.BriefMatcher
import com.immineal.hdri360.core.pano.CaptureTarget
import com.immineal.hdri360.core.pano.Equirect
import com.immineal.hdri360.core.pano.FastCornerDetector
import com.immineal.hdri360.core.pano.FeatureSet
import com.immineal.hdri360.core.pano.Keypoint
import com.immineal.hdri360.test.TestCase
import com.immineal.hdri360.test.TestKit
import java.util.Random

/**
 * Corner detection, description and matching.
 *
 * Without gyro data - which is exactly the situation when re-stitching a folder
 * of ordinary photographs - these correspondences are the only thing the pose
 * solver has to work with, so the bar is a measured inlier rate against known
 * ground-truth geometry, not "does it find some features".
 */
class FeatureSuite : TestCase {
    override fun name(): String = "features"

    override fun run(t: TestKit) {
        val r = t.rng(60613)

        // --- detector on a synthetic square -------------------------------
        val square = ImageF(160, 120, 1)
        square.fill(0.8f)
        for (y in 40 until 80)
            for (x in 50 until 110) square.set(x, y, 0, 0.15f)
        val dc = FastCornerDetector.Config()
        dc.threshold = 0.15
        dc.border = 10
        val corners = FastCornerDetector.detect(square, dc)
        t.greaterThan(corners.size.toDouble(), 3.0, "the four corners of a square are found")
        val wanted = arrayOf(
            doubleArrayOf(50.0, 40.0), doubleArrayOf(109.0, 40.0),
            doubleArrayOf(50.0, 79.0), doubleArrayOf(109.0, 79.0))
        for (c in wanted) {
            var best = Double.MAX_VALUE
            for (kp in corners) best = Math.min(best, Math.hypot(kp.x - c[0], kp.y - c[1]))
            t.lessThan(best, 2.5, "a detection sits on corner (" + c[0] + "," + c[1] + ")")
        }
        for (kp in corners) {
            var nearACorner = false
            for (c in wanted) if (Math.hypot(kp.x - c[0], kp.y - c[1]) < 6) nearACorner = true
            t.check(nearACorner, "no spurious corner at (" + kp.x + "," + kp.y + ")")
        }

        // --- no corners on flat or on a pure gradient -----------------------
        val flat = ImageF(120, 90, 1)
        flat.fill(0.5f)
        t.eq(0L, FastCornerDetector.detect(flat, dc).size.toLong(), "a flat field has no corners")
        val ramp = ImageF(120, 90, 1)
        for (y in 0 until 90)
            for (x in 0 until 120) ramp.set(x, y, 0, (x / 200.0).toFloat())
        t.eq(0L, FastCornerDetector.detect(ramp, dc).size.toLong(), "a linear gradient has no corners")

        // --- feature budget is respected -------------------------------------
        val noise = noiseImage(400, 300, r, 1.3)
        val budget = FastCornerDetector.Config()
        budget.threshold = 0.02
        budget.maxFeatures = 150
        val capped = FastCornerDetector.detect(noise, budget)
        t.lessThan(capped.size.toDouble(), 151.0, "the feature budget is respected")
        t.greaterThan(capped.size.toDouble(), 100.0, "a textured image fills the budget")
        for (i in 1 until capped.size)
            t.check(capped[i - 1].score >= capped[i].score - 1e-6f,
                "features are returned strongest first")

        // --- descriptors -------------------------------------------------------
        val fs = FeatureSet.describe(noise, capped)
        t.eq(capped.size.toLong(), fs.size().toLong(), "one descriptor per keypoint")
        t.eq(4L, fs.descriptors[0].size.toLong(), "descriptors are 256 bits")
        t.eq(0L, BriefMatcher.hamming(fs.descriptors[0], fs.descriptors[0]).toLong(),
            "a descriptor matches itself exactly")
        val cross = BriefMatcher.hamming(fs.descriptors[0], fs.descriptors[1])
        t.greaterThan(cross.toDouble(), 30.0, "different patches give clearly different descriptors")

        // Descriptors must survive image rotation, since a handheld sweep rolls the camera.
        val rotated = rotateImage(noise, Math.toRadians(25.0))
        val rotKps = FastCornerDetector.detect(rotated, budget)
        val rotFs = FeatureSet.describe(rotated, rotKps)
        val matchedUnderRoll = countGeometricMatches(noise, fs, rotFs, Math.toRadians(25.0))
        t.greaterThan(matchedUnderRoll.toDouble(), 25.0,
            "descriptors still match after a 25 degree roll")
        t.note("matches surviving a 25 degree roll: $matchedUnderRoll")

        // --- end to end against known geometry ----------------------------------
        // Two views of the same textured environment, related by a known rotation.
        val ew = 1024
        val eh = 512
        val env = noiseImage(ew, eh, r, 1.2)
        val k = Intrinsics.fromHorizontalFov(384, 288, 55.0)
        val ra = CaptureTarget.lookingAt(Vec3(0.0, 0.0, 1.0)).rotation
        // ~16 degrees of pan plus a little tilt/roll
        val rb = SO3.exp(Vec3(0.02, 0.28, -0.03)).mul(ra)

        val viewA = renderView(env, ew, eh, k, ra)
        val viewB = renderView(env, ew, eh, k, rb)

        val fc = FastCornerDetector.Config()
        fc.threshold = 0.02
        fc.maxFeatures = 500
        val fa = FeatureSet.describe(viewA, FastCornerDetector.detect(viewA, fc))
        val fb = FeatureSet.describe(viewB, FastCornerDetector.detect(viewB, fc))
        t.greaterThan(fa.size().toDouble(), 150.0, "plenty of features in view A")
        t.greaterThan(fb.size().toDouble(), 150.0, "plenty of features in view B")

        val matches = BriefMatcher.match(fa, fb, BriefMatcher.Config())
        t.greaterThan(matches.size.toDouble(), 40.0,
            "the matcher finds a workable number of correspondences")

        var good = 0
        for (m in matches) {
            val pa = fa.keypoints[m.a]
            val pb = fb.keypoints[m.b]
            val world = ra.mul(k.unproject(pa.x.toDouble(), pa.y.toDouble()))
            val pred = k.project(rb.mulTranspose(world))
            if (pred != null && Math.hypot(pred[0] - pb.x, pred[1] - pb.y) < 3.0) good++
        }
        val inlierRate = good / matches.size.toDouble()
        t.greaterThan(inlierRate, 0.60, "at least 60% of raw matches are geometrically correct")
        t.note("raw matches " + matches.size + ", inlier rate " + TestKit.fmt(inlierRate * 100) + "%")

        // The ratio test must actually be doing work.
        val loose = BriefMatcher.Config()
        loose.ratio = 1.0
        loose.crossCheck = false
        val raw = BriefMatcher.match(fa, fb, loose)
        t.greaterThan(raw.size.toDouble(), matches.size.toDouble(),
            "filtering removes some candidate matches")
    }

    /** Counts descriptor matches that agree with a known in-plane rotation about the image centre. */
    private fun countGeometricMatches(ref: ImageF, a: FeatureSet, b: FeatureSet, angle: Double): Int {
        val ms = BriefMatcher.match(a, b, BriefMatcher.Config())
        val cx = (ref.width - 1) / 2.0
        val cy = (ref.height - 1) / 2.0
        var good = 0
        for (m in ms) {
            val pa = a.keypoints[m.a]
            val pb = b.keypoints[m.b]
            val dx = pa.x - cx
            val dy = pa.y - cy
            val ex = cx + dx * Math.cos(angle) - dy * Math.sin(angle)
            val ey = cy + dx * Math.sin(angle) + dy * Math.cos(angle)
            if (Math.hypot(ex - pb.x, ey - pb.y) < 3.0) good++
        }
        return good
    }

    companion object {
        @JvmStatic
        fun noiseImage(w: Int, h: Int, r: Random, sigma: Double): ImageF {
            val n = ImageF(w, h, 1)
            for (i in n.data.indices) n.data[i] = r.nextDouble().toFloat()
            val blurred = ImageOps.gaussianBlur(n, sigma)
            val lo = ImageOps.min(blurred)
            val hi = ImageOps.max(blurred)
            for (i in blurred.data.indices)
                blurred.data[i] = (blurred.data[i] - lo) / Math.max(1e-6f, hi - lo)
            return blurred
        }

        /** Rotates about the image centre with bilinear sampling. */
        @JvmStatic
        fun rotateImage(src: ImageF, angle: Double): ImageF {
            val out = src.sameShape()
            val cx = (src.width - 1) / 2.0
            val cy = (src.height - 1) / 2.0
            for (y in 0 until src.height)
                for (x in 0 until src.width) {
                    val dx = x - cx
                    val dy = y - cy
                    val sx = cx + dx * Math.cos(-angle) - dy * Math.sin(-angle)
                    val sy = cy + dx * Math.sin(-angle) + dy * Math.cos(-angle)
                    out.set(x, y, 0, src.sampleBilinear(sx, sy, 0))
                }
            return out
        }

        /** Renders a pinhole view of an equirectangular environment. */
        @JvmStatic
        fun renderView(env: ImageF, ew: Int, eh: Int, k: Intrinsics, rotation: Mat3): ImageF {
            val out = ImageF(k.width, k.height, 1)
            for (y in 0 until k.height)
                for (x in 0 until k.width) {
                    val d = rotation.mul(k.unproject(x.toDouble(), y.toDouble()))
                    val p = Equirect.pixel(d, ew, eh)
                    out.set(x, y, 0, env.sampleBilinear(p[0], p[1], 0))
                }
            return out
        }
    }
}
