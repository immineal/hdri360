package com.immineal.hdri360.core.pano

import com.immineal.hdri360.core.math.Linalg
import com.immineal.hdri360.core.math.Mat3
import com.immineal.hdri360.core.math.SO3
import com.immineal.hdri360.core.math.Vec3

/**
 * Global refinement of every frame's orientation at once.
 *
 * Pairwise solves alone accumulate drift around a full sphere: the loop does not
 * close, and the error lands wherever the last seam happens to fall. This
 * distributes the residual over all frames simultaneously by minimising, for
 * every correspondence, the angle between the two world bearings it implies.
 *
 * Parameterisation is a left-multiplied increment per frame, R <- exp(w) R,
 * which keeps every iterate exactly on the rotation manifold - no
 * re-orthogonalisation drift, no gimbal degeneracies. Frame 0 is held fixed to
 * pin the arbitrary global orientation. A Huber kernel keeps surviving
 * mismatches from dragging the whole sphere, and an optional prior lets device
 * orientation data anchor frames that are short of correspondences.
 */
object RotationBundleAdjuster {

    /** One point seen in two frames, as unit bearings in each frame's own camera coordinates. */
    class Correspondence(
        @JvmField val frameA: Int,
        @JvmField val frameB: Int,
        bearingA: Vec3,
        bearingB: Vec3,
        @JvmField val weight: Double
    ) {
        @JvmField val bearingA: Vec3 = bearingA.normalized()
        @JvmField val bearingB: Vec3 = bearingB.normalized()
    }

    class Options {
        @JvmField var maxIterations = 60
        /** Residual beyond which a correspondence is down-weighted. Infinite disables robustness. */
        @JvmField var huberRad = Math.toRadians(0.5)
        /** Strength of the orientation prior, in the same units as a correspondence residual. */
        @JvmField var priorWeight = 0.0
        @JvmField var fixFirst = true
        @JvmField var convergenceRad = 1e-10
    }

    class Result internal constructor(
        @JvmField val rotations: Array<Mat3>,
        @JvmField val costHistory: DoubleArray,
        @JvmField val iterations: Int,
        @JvmField val rmsErrorRad: Double
    )

    @JvmStatic
    fun solve(initial: Array<Mat3>?, obs: List<Correspondence>?,
              priors: Array<Mat3>?, opt: Options): Result {
        if (initial == null || initial.isEmpty()) throw IllegalArgumentException("no frames")
        val n = initial.size
        if (priors != null && priors.size != n) throw IllegalArgumentException("prior count mismatch")
        val hasPriors = priors != null && opt.priorWeight > 0
        val hasObs = obs != null && obs.isNotEmpty()

        var R = Array(n) { initial[it] }
        if (!hasObs && !hasPriors) return Result(R, doubleArrayOf(0.0), 0, 0.0)

        val first = if (opt.fixFirst) 1 else 0
        val dim = 3 * (n - first)
        if (dim == 0)
            return Result(R, doubleArrayOf(cost(R, obs, priors, opt, hasPriors)), 0, 0.0)

        var lambda = 1e-6
        var current = cost(R, obs, priors, opt, hasPriors)
        val history = DoubleArray(opt.maxIterations + 1)
        history[0] = current
        var accepted = 0

        for (iter in 0 until opt.maxIterations) {
            val h = Array(dim) { DoubleArray(dim) }
            val g = DoubleArray(dim)

            if (hasObs) {
                for (c in obs!!) {
                    val u = R[c.frameA].mul(c.bearingA)
                    val v = R[c.frameB].mul(c.bearingB)
                    val t1 = u.anyPerpendicular()
                    val t2 = u.cross(t1)
                    val r1 = t1.dot(v)
                    val r2 = t2.dot(v)
                    val norm = Math.hypot(r1, r2)
                    val w = c.weight * huber(norm, opt.huberRad)
                    if (w <= 0) continue
                    // d r_k / d w_j =  v x t_k ; d r_k / d w_i = -(v x t_k)
                    val j1 = v.cross(t1)
                    val j2 = v.cross(t2)
                    addResidual(h, g, first, c.frameA, c.frameB, j1, r1, w)
                    addResidual(h, g, first, c.frameA, c.frameB, j2, r2, w)
                }
            }
            if (hasPriors) {
                val pw = opt.priorWeight
                for (i in first until n) {
                    // World-frame orientation error; to first order its Jacobian is the identity.
                    val e = SO3.log(R[i].mul(priors!![i].transpose()))
                    val base = 3 * (i - first)
                    val ev = doubleArrayOf(e.x, e.y, e.z)
                    for (a in 0 until 3) {
                        g[base + a] += pw * pw * ev[a]
                        h[base + a][base + a] += pw * pw
                    }
                }
            }

            var step: DoubleArray? = null
            var bestCost = current
            var bestR: Array<Mat3>? = null
            for (attempt in 0 until 12) {
                step = Linalg.solveSpdDamped(h, negate(g), lambda)
                if (step == null) { lambda *= 10; continue }
                val candidate = apply(R, step, first)
                val c2 = cost(candidate, obs, priors, opt, hasPriors)
                if (c2 <= current) { bestCost = c2; bestR = candidate; break }
                lambda *= 10
            }
            if (bestR == null) break                       // no downhill step exists

            var magnitude = 0.0
            for (s in step!!) magnitude = Math.max(magnitude, Math.abs(s))
            R = bestR
            val improvement = current - bestCost
            current = bestCost
            history[++accepted] = current
            lambda = Math.max(1e-12, lambda / 5)
            if (magnitude < opt.convergenceRad || improvement < 1e-18) break
        }

        val trimmed = DoubleArray(accepted + 1)
        System.arraycopy(history, 0, trimmed, 0, accepted + 1)
        return Result(R, trimmed, accepted, rms(R, obs))
    }

    private fun addResidual(h: Array<DoubleArray>, g: DoubleArray, first: Int,
                            frameA: Int, frameB: Int, jac: Vec3, residual: Double, w: Double) {
        // Row of the Jacobian: -jac on frame A's block, +jac on frame B's.
        val ia = 3 * (frameA - first)
        val ib = 3 * (frameB - first)
        val row = DoubleArray(6)
        row[0] = -jac.x; row[1] = -jac.y; row[2] = -jac.z
        row[3] = jac.x; row[4] = jac.y; row[5] = jac.z
        val idx = IntArray(6)
        for (a in 0 until 3) {
            idx[a] = if (frameA < first) -1 else ia + a
            idx[3 + a] = if (frameB < first) -1 else ib + a
        }
        for (a in 0 until 6) {
            if (idx[a] < 0) continue
            g[idx[a]] += w * row[a] * residual
            for (b in 0 until 6) {
                if (idx[b] < 0) continue
                h[idx[a]][idx[b]] += w * row[a] * row[b]
            }
        }
    }

    private fun apply(R: Array<Mat3>, step: DoubleArray, first: Int): Array<Mat3> {
        val out = Array(R.size) { R[it] }
        for (i in first until R.size) {
            val b = 3 * (i - first)
            val w = Vec3(step[b], step[b + 1], step[b + 2])
            out[i] = SO3.exp(w).mul(R[i]).orthonormalized()
        }
        return out
    }

    private fun cost(R: Array<Mat3>, obs: List<Correspondence>?, priors: Array<Mat3>?,
                     opt: Options, hasPriors: Boolean): Double {
        var c = 0.0
        if (obs != null) {
            for (o in obs) {
                val a = R[o.frameA].mul(o.bearingA).angleTo(R[o.frameB].mul(o.bearingB))
                c += o.weight * huberCost(a, opt.huberRad)
            }
        }
        if (hasPriors) {
            for (i in R.indices) {
                val e = SO3.log(R[i].mul(priors!![i].transpose())).normSq()
                c += opt.priorWeight * opt.priorWeight * e
            }
        }
        return c
    }

    private fun rms(R: Array<Mat3>, obs: List<Correspondence>?): Double {
        if (obs == null || obs.isEmpty()) return 0.0
        var s = 0.0
        for (o in obs) {
            val a = R[o.frameA].mul(o.bearingA).angleTo(R[o.frameB].mul(o.bearingB))
            s += a * a
        }
        return Math.sqrt(s / obs.size)
    }

    /** IRLS weight for the Huber kernel. */
    private fun huber(norm: Double, delta: Double): Double {
        if (!(delta > 0)) return 1.0
        if (delta.isInfinite() || norm <= delta) return 1.0
        return delta / norm
    }

    private fun huberCost(norm: Double, delta: Double): Double {
        if (!(delta > 0) || delta.isInfinite() || norm <= delta) return norm * norm
        return delta * (2 * norm - delta)
    }

    private fun negate(v: DoubleArray): DoubleArray {
        val o = DoubleArray(v.size)
        for (i in v.indices) o[i] = -v[i]
        return o
    }
}
