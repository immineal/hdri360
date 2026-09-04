package com.immineal.hdri360.core.pano

import com.immineal.hdri360.core.math.Linalg
import com.immineal.hdri360.core.math.Mat3
import com.immineal.hdri360.core.math.Vec3
import java.util.Collections
import java.util.Random

/**
 * Rotation between two sets of bearings: the closed-form Procrustes solution,
 * and a RANSAC wrapper for real match lists.
 *
 * A panorama frame pair is related by a pure rotation, so two correspondences
 * are already a minimal sample - which makes RANSAC here extremely cheap and
 * extremely tolerant: even a 50% outlier rate is cleaned up in a few hundred
 * trials.
 */
object RotationSolver {

    class Ransac internal constructor(
        @JvmField val rotation: Mat3,
        @JvmField val inliers: BooleanArray,
        @JvmField val inlierCount: Int,
        @JvmField val medianErrorRad: Double
    )

    /**
     * Rotation R minimising sum |R*from_i - to_i|^2 (Kabsch/Wahba).
     * @return null if the correspondences cannot determine a rotation.
     */
    @JvmStatic
    fun kabsch(from: List<Vec3>?, to: List<Vec3>?): Mat3? {
        if (from == null || to == null || from.size != to.size || from.size < 2) return null
        val acc = DoubleArray(9)
        for (i in from.indices) {
            val a = from[i].normalized()
            val b = to[i].normalized()
            acc[0] += b.x * a.x; acc[1] += b.x * a.y; acc[2] += b.x * a.z
            acc[3] += b.y * a.x; acc[4] += b.y * a.y; acc[5] += b.y * a.z
            acc[6] += b.z * a.x; acc[7] += b.z * a.y; acc[8] += b.z * a.z
        }
        val h = Mat3(acc)
        val svd = Linalg.svd3(h)
        // Rank one means every correspondence points the same way: the rotation
        // about that shared axis is unconstrained, so there is no unique answer.
        if (svd.s[1] <= 1e-9 * Math.max(1e-12, svd.s[0])) return null
        var r = svd.u.mul(svd.v.transpose())
        if (r.det() < 0) r = svd.u.mul(Mat3.diag(1.0, 1.0, -1.0)).mul(svd.v.transpose())
        return r.orthonormalized()
    }

    @JvmStatic
    fun ransac(from: List<Vec3>?, to: List<Vec3>?, thresholdRad: Double,
               iterations: Int, seed: Long): Ransac? {
        if (from == null || to == null || from.size != to.size || from.size < 2) return null
        val n = from.size
        val rng = Random(seed)
        var best: Mat3? = null
        var bestCount = 0

        for (it in 0 until iterations) {
            val i = rng.nextInt(n)
            val j = rng.nextInt(n)
            if (i == j) continue
            val sf = ArrayList<Vec3>(2)
            val st = ArrayList<Vec3>(2)
            sf.add(from[i]); sf.add(from[j])
            st.add(to[i]); st.add(to[j])
            val candidate = kabsch(sf, st) ?: continue
            var count = 0
            for (k in 0 until n)
                if (candidate.mul(from[k]).angleTo(to[k]) < thresholdRad) count++
            if (count > bestCount) { bestCount = count; best = candidate }
        }
        if (best == null) return null

        // Refit on the consensus set, then recount - a two-point sample fixes the
        // model but a least-squares fit on all inliers is what makes it accurate.
        for (pass in 0 until 3) {
            val inF = ArrayList<Vec3>()
            val inT = ArrayList<Vec3>()
            for (k in 0 until n)
                if (best!!.mul(from[k]).angleTo(to[k]) < thresholdRad) { inF.add(from[k]); inT.add(to[k]) }
            if (inF.size < 2) break
            val refined = kabsch(inF, inT) ?: break
            best = refined
        }

        val inliers = BooleanArray(n)
        val errs = ArrayList<Double>()
        var count = 0
        for (k in 0 until n) {
            val e = best.mul(from[k]).angleTo(to[k])
            inliers[k] = e < thresholdRad
            if (inliers[k]) { count++; errs.add(e) }
        }
        Collections.sort(errs)
        val median = if (errs.isEmpty()) Double.NaN else errs[errs.size / 2]
        return Ransac(best, inliers, count, median)
    }
}
