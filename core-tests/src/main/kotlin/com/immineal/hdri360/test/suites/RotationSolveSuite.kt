package com.immineal.hdri360.test.suites

import com.immineal.hdri360.core.math.Mat3
import com.immineal.hdri360.core.math.SO3
import com.immineal.hdri360.core.math.Vec3
import com.immineal.hdri360.core.pano.CaptureTarget
import com.immineal.hdri360.core.pano.RotationAverage
import com.immineal.hdri360.core.pano.RotationSolver
import com.immineal.hdri360.test.TestCase
import com.immineal.hdri360.test.TestKit
import java.util.Random

/** Closed-form and robust estimation of the rotation between two bearing sets. */
class RotationSolveSuite : TestCase {
    override fun name(): String = "rotation-solve"

    override fun run(t: TestKit) {
        theSphereIsLevelledByEveryFrameNotOne(t)
        val r = t.rng(9001)

        // --- exact recovery -----------------------------------------------
        for (trial in 0 until 40) {
            val truth = SO3.exp(randomVec(r).scale(r.nextDouble() * 2.5))
            val from = ArrayList<Vec3>()
            val to = ArrayList<Vec3>()
            for (i in 0 until 8) {
                val a = randomVec(r)
                from.add(a)
                to.add(truth.mul(a))
            }
            val est = RotationSolver.kabsch(from, to)
            t.lessThan(Math.toDegrees(SO3.angleBetween(truth, est!!)), 1e-9,
                "Kabsch is exact on clean data")
        }

        // Two non-parallel correspondences already determine the rotation.
        val truth2 = SO3.exp(Vec3(0.3, -0.9, 0.15))
        val f2 = ArrayList<Vec3>()
        val t2 = ArrayList<Vec3>()
        for (v in arrayOf(Vec3(1.0, 0.2, 0.3).normalized(), Vec3(-0.4, 1.0, 0.1).normalized())) {
            f2.add(v)
            t2.add(truth2.mul(v))
        }
        t.lessThan(Math.toDegrees(SO3.angleBetween(truth2, RotationSolver.kabsch(f2, t2)!!)), 1e-8,
            "two correspondences determine the rotation")

        // Degenerate inputs are refused rather than returning a plausible-looking wrong answer.
        t.check(RotationSolver.kabsch(ArrayList(), ArrayList()) == null,
            "no correspondences yields null")
        val one = ArrayList(listOf(Vec3(0.0, 0.0, 1.0)))
        t.check(RotationSolver.kabsch(one, one) == null, "a single correspondence is not enough")
        val parallel = ArrayList<Vec3>()
        val parallelTo = ArrayList<Vec3>()
        for (i in 0 until 5) {
            parallel.add(Vec3(0.0, 0.0, 1.0))
            parallelTo.add(Vec3(0.0, 0.0, 1.0))
        }
        t.check(RotationSolver.kabsch(parallel, parallelTo) == null,
            "parallel correspondences cannot fix the roll and are refused")

        // The estimate must be a proper rotation, never a reflection.
        val mirrorFrom = ArrayList<Vec3>()
        val mirrorTo = ArrayList<Vec3>()
        for (i in 0 until 6) {
            val a = randomVec(r)
            mirrorFrom.add(a)
            mirrorTo.add(Vec3(a.x, a.y, -a.z))      // a reflection, not a rotation
        }
        val refl = RotationSolver.kabsch(mirrorFrom, mirrorTo)!!
        t.near(1.0, refl.det(), 1e-9, "the solver never returns a reflection")

        // --- noise ---------------------------------------------------------
        val truth3 = SO3.exp(Vec3(0.1, 0.5, -0.2))
        val nf = ArrayList<Vec3>()
        val nt = ArrayList<Vec3>()
        for (i in 0 until 200) {
            val a = randomVec(r)
            nf.add(a)
            nt.add(truth3.mul(a).add(randomVec(r).scale(0.002)).normalized())
        }
        val noisyErr = Math.toDegrees(SO3.angleBetween(truth3, RotationSolver.kabsch(nf, nt)!!))
        t.lessThan(noisyErr, 0.05, "Kabsch averages down bearing noise")
        t.note("Kabsch error with 0.11 degree bearing noise: " + TestKit.fmt(noisyErr) + " degrees")

        // --- RANSAC with heavy contamination ---------------------------------
        val truth4 = SO3.exp(Vec3(-0.25, 0.8, 0.05))
        val cf = ArrayList<Vec3>()
        val ct = ArrayList<Vec3>()
        var outliers = 0
        for (i in 0 until 300) {
            val a = randomVec(r)
            cf.add(a)
            if (r.nextDouble() < 0.5) {         // half the matches are garbage
                ct.add(randomVec(r))
                outliers++
            } else {
                ct.add(truth4.mul(a).add(randomVec(r).scale(0.001)).normalized())
            }
        }
        val res = RotationSolver.ransac(cf, ct, Math.toRadians(0.5), 500, 42)
        t.check(res != null, "RANSAC returns a result")
        t.lessThan(Math.toDegrees(SO3.angleBetween(truth4, res!!.rotation)), 0.2,
            "RANSAC recovers the rotation despite 50% outliers")
        t.greaterThan(res.inlierCount.toDouble(), (300 - outliers) * 0.85,
            "most true inliers are recovered")
        t.lessThan(res.inlierCount.toDouble(), 300 - outliers * 0.85,
            "outliers are not swept in as inliers")
        t.eq(300L, res.inliers.size.toLong(), "the inlier mask covers every correspondence")
        t.note("RANSAC with " + outliers + "/300 outliers: " + res.inlierCount + " inliers, error " +
                TestKit.fmt(Math.toDegrees(SO3.angleBetween(truth4, res.rotation))) + " degrees")

        // Deterministic for a given seed; unusable input returns null rather than nonsense.
        val again = RotationSolver.ransac(cf, ct, Math.toRadians(0.5), 500, 42)
        t.eq(res.inlierCount.toLong(), again!!.inlierCount.toLong(),
            "RANSAC is deterministic for a fixed seed")
        t.check(RotationSolver.ransac(one, one, 0.01, 100, 1) == null,
            "RANSAC refuses degenerate input")

        // All-outlier input must not be dressed up as a confident answer.
        val junkA = ArrayList<Vec3>()
        val junkB = ArrayList<Vec3>()
        for (i in 0 until 100) { junkA.add(randomVec(r)); junkB.add(randomVec(r)) }
        val junk = RotationSolver.ransac(junkA, junkB, Math.toRadians(0.5), 300, 7)
        t.lessThan((if (junk == null) 0 else junk.inlierCount).toDouble(), 12.0,
            "random correspondences produce almost no inliers")
    }

    private fun randomVec(r: Random): Vec3 =
        Vec3(r.nextGaussian(), r.nextGaussian(), r.nextGaussian()).normalized()

    /**
     * Which way is up must come from every frame, not from whichever one the
     * spanning tree happened to start at.
     *
     * The bundle adjuster fixes the first frame, so the whole sphere's
     * orientation was whatever that one frame's recorded device pose said - and a
     * device pose is an accelerometer estimate taken while somebody was holding a
     * phone at arm's length. On a real 34-direction capture the root frame's
     * prior was tilted 11.0 degrees from the consensus of all thirty-four, and
     * the spread across frames was 0 to 18.6 degrees: which frame won the
     * spanning tree decided how level the sphere came out.
     *
     * Confirmed twice over on that capture, which is why it can be believed. The
     * sun in the finished panorama sat 7 to 12 degrees below where the almanac
     * puts it for the time and place; the priors' own consensus said the gauge
     * was tilted 11.0 degrees. Two independent measurements, one number.
     *
     * For an HDRI a tilt is not cosmetic: it is the light arriving from the wrong
     * elevation for the rest of the file's life.
     */
    private fun theSphereIsLevelledByEveryFrameNotOne(t: TestKit) {
        val r = t.rng(6041)
        val n = 24
        // The truth: a ring of poses looking outward, all properly upright.
        val truth = Array(n) { i ->
            CaptureTarget.lookingAt(
                CaptureTarget.directionFor(-180.0 + 360.0 * i / n, 12.0 * Math.sin(i * 0.7))).rotation
        }
        // The priors: the truth plus the wobble a hand-held gravity estimate has,
        // except frame 0 - the one the solve will fix - which is well out.
        val rootError = SO3.exp(Vec3(0.0, 0.0, Math.toRadians(15.0)))
        val priors = Array(n) { i ->
            if (i == 0) truth[0].mul(rootError)
            else truth[i].mul(SO3.exp(Vec3(
                Math.toRadians(1.5 * r.nextGaussian()),
                Math.toRadians(1.5 * r.nextGaussian()),
                Math.toRadians(1.5 * r.nextGaussian()))))
        }
        // The solve: internally perfect, gauged on frame 0's prior - which is what
        // fixing the first frame produces.
        val gauge = priors[0].mul(truth[0].transpose())
        val solved = Array(n) { i -> gauge.mul(truth[i]).orthonormalized() }

        val up = Vec3(0.0, 1.0, 0.0)
        fun tiltOf(poses: Array<Mat3>): Double {
            // How far the solution's idea of up is from the truth's, read off the
            // rotation that maps one to the other.
            val g = RotationAverage.align(poses, truth, BooleanArray(n) { true })
                ?: return Double.MAX_VALUE
            return Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, up.dot(g.mul(up))))))
        }

        val before = tiltOf(solved)
        t.near(15.0, before, 0.5,
            "gauged on one frame, the sphere is tilted by exactly that frame's error")

        val g = RotationAverage.align(solved, priors, BooleanArray(n) { true })
        if (g == null) { t.fail("aligning to the priors must produce a rotation"); return }
        t.near(1.0, g.det(), 1e-9, "and it must be a rotation, not a reflection")
        val levelled = Array(n) { i -> g.mul(solved[i]).orthonormalized() }
        val after = tiltOf(levelled)
        t.lessThan(after, 2.0,
            "aligned to every prior, it is level to within the wobble of one reading")
        t.lessThan(after, before / 4.0, "which is a great deal better than one frame's")
        t.note(String.format(java.util.Locale.US,
            "levelling: %.2f deg tilt from one frame, %.2f deg from all %d", before, after, n))

        // The sphere's shape must survive untouched: this fixes where the sphere
        // points, not how its frames sit against each other.
        //
        // Compared as the angle between optical axes, not as the relative
        // rotation matrix. One global rotation *conjugates* a relative pose -
        // G R_i (G R_j)^T = G (R_i R_j^T) G^T - so the matrix changes while every
        // angle in the sphere is preserved, which is what "the shape is untouched"
        // actually means.
        val axis = Vec3(0.0, 0.0, 1.0)
        var worstShape = 0.0
        for (i in 0 until n)
            for (j in i + 1 until n) {
                val a = solved[i].mul(axis).angleTo(solved[j].mul(axis))
                val b = levelled[i].mul(axis).angleTo(levelled[j].mul(axis))
                worstShape = Math.max(worstShape, Math.toDegrees(Math.abs(a - b)))
            }
        t.near(0.0, worstShape, 1e-9,
            "every angle between two directions in the sphere is exactly what it was")

        // Frames that were never placed carry no information about up.
        val placed = BooleanArray(n) { it != 3 && it != 11 }
        t.check(RotationAverage.align(solved, priors, placed) != null,
            "unplaced frames are simply left out")
        t.check(RotationAverage.align(solved, priors, BooleanArray(n)) == null,
            "and with nothing placed there is no answer, rather than a made-up one")
    }
}
