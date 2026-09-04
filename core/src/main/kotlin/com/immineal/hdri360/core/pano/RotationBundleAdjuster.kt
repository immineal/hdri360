package com.immineal.hdri360.core.pano

import com.immineal.hdri360.core.camera.Intrinsics
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

    /**
     * One point seen in two frames, as unit bearings in each frame's own camera
     * coordinates.
     *
     * [pixelA] / [pixelB] are the observation the bearings were unprojected from.
     * They are only needed when the solver is also estimating lens distortion:
     * a bearing already has the distortion model baked into it, so k1 cannot be
     * refined without going back to the pixels it came from.
     */
    class Correspondence @JvmOverloads constructor(
        @JvmField val frameA: Int,
        @JvmField val frameB: Int,
        bearingA: Vec3,
        bearingB: Vec3,
        @JvmField val weight: Double,
        @JvmField val pixelA: DoubleArray? = null,
        @JvmField val pixelB: DoubleArray? = null
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

        /**
         * Estimate one radial distortion coefficient shared by every frame.
         *
         * Shared because it is one lens: solving a k1 per frame would let each
         * absorb its own reprojection error and mean nothing. Requires
         * [distortionIntrinsics] and correspondences carrying pixel coordinates.
         */
        @JvmField var solveDistortion = false
        /** The camera each frame was unprojected with, needed to re-unproject as k1 moves. */
        @JvmField var distortionIntrinsics: Array<Intrinsics>? = null
        @JvmField var k1Initial = 0.0
        /** Hard bound; phone lenses sit well inside this and a runaway k1 is always a fit artefact. */
        @JvmField var k1Limit = 0.4
        /**
         * Huber threshold for a first pass when distortion is being solved.
         *
         * Starting from k1 = 0, an uncorrected lens produces angular residuals of
         * several degrees - many times the nominal threshold - so the robust
         * kernel would classify almost every correspondence as an outlier and
         * remove the very gradient the distortion solve needs. One relaxed pass
         * gets k1 into the right neighbourhood, then the nominal threshold does
         * its real job of rejecting genuine mismatches.
         */
        @JvmField var distortionWarmupHuberDeg = 12.0
        /**
         * Smallest relative residual improvement that justifies keeping a recovered k1.
         *
         * A free parameter can always shave a little off the residual by fitting
         * noise, and a spurious k1 costs real accuracy: on distortion-free data,
         * accepting one unconditionally measurably worsened recovered pose. Real
         * lens distortion improves the fit by orders of magnitude more than this,
         * so the test separates the two cases cleanly rather than trading one
         * failure mode for the other.
         */
        @JvmField var distortionMinGain = 0.01
        /**
         * Smallest image displacement, in degrees, that a recovered k1 must
         * actually account for before it is believed.
         *
         * A residual improvement is not evidence that the lens was measured. What
         * makes k1 observable is seeing the same scene point at *different* image
         * radii in the two frames: distortion moves a point by k1*r^3, so a
         * correspondence whose two radii agree carries no information about k1 at
         * all, and a whole capture of those - every frame shot from one aim, which
         * is exactly what a bench test produces - leaves the coefficient free to
         * absorb noise. It does, and the fit improves, and the number is
         * meaningless.
         *
         * So the coefficient is also asked what it claims to have measured:
         * |k1| times the correspondences' actual radial leverage, as an angle. A
         * real lens on a real sphere lands near a degree; the degenerate case
         * lands three orders of magnitude below it, which is why the threshold
         * does not need to be delicate.
         */
        @JvmField var distortionMinSignalDeg = 0.05
    }

    class Result internal constructor(
        @JvmField val rotations: Array<Mat3>,
        @JvmField val costHistory: DoubleArray,
        @JvmField val iterations: Int,
        @JvmField val rmsErrorRad: Double,
        /** Recovered radial distortion, or the initial value when it was not solved for. */
        @JvmField val k1: Double = 0.0
    )

    @JvmStatic
    fun solve(initial: Array<Mat3>?, obs: List<Correspondence>?,
              priors: Array<Mat3>?, opt: Options): Result {
        if (initial == null || initial.isEmpty()) throw IllegalArgumentException("no frames")
        val n = initial.size
        if (priors != null && priors.size != n) throw IllegalArgumentException("prior count mismatch")
        val hasPriors = priors != null && opt.priorWeight > 0
        val hasObs = obs != null && obs.isNotEmpty()

        val baseIntrinsics = opt.distortionIntrinsics
        val solveK1 = opt.solveDistortion && hasObs && baseIntrinsics != null
        if (opt.solveDistortion && baseIntrinsics != null && baseIntrinsics.size != n)
            throw IllegalArgumentException("distortion intrinsics count mismatch")
        if (solveK1 && obs.none { it.pixelA != null && it.pixelB != null })
            throw IllegalArgumentException(
                "solveDistortion needs correspondences carrying pixel coordinates")

        var R = Array(n) { initial[it] }
        var k1 = if (solveK1) opt.k1Initial else 0.0
        if (!hasObs && !hasPriors) return Result(R, doubleArrayOf(0.0), 0, 0.0, k1)

        val first = if (opt.fixFirst) 1 else 0
        val poseDim = 3 * (n - first)
        val dim = poseDim + (if (solveK1) 1 else 0)
        if (dim == 0)
            return Result(R, doubleArrayOf(cost(R, obs, priors, opt, hasPriors, k1,
                baseIntrinsics, solveK1, opt.huberRad)), 0, 0.0, k1)

        if (!solveK1)
            return optimise(R, 0.0, obs, priors, opt, hasPriors, first, poseDim, poseDim,
                false, baseIntrinsics, opt.huberRad)

        // Both arms run the same graduated schedule - a relaxed kernel first, then
        // the nominal one (see Options.distortionWarmupHuberDeg). Comparing a
        // graduated fit against a single-pass one would not be a comparison of
        // models at all: the schedule alone changes the residual by more than the
        // acceptance threshold, and k1 would be credited for it.
        val warmHuber = if (opt.distortionWarmupHuberDeg > 0)
            Math.toRadians(opt.distortionWarmupHuberDeg) else opt.huberRad

        val refWarm = optimise(R, 0.0, obs, priors, opt, hasPriors, first, poseDim, poseDim,
            false, baseIntrinsics, warmHuber)
        val reference = optimise(refWarm.rotations, 0.0, obs, priors, opt, hasPriors,
            first, poseDim, poseDim, false, baseIntrinsics, opt.huberRad)

        val warm = optimise(R, k1, obs, priors, opt, hasPriors, first, poseDim, dim,
            true, baseIntrinsics, warmHuber)
        val fitted = optimise(warm.rotations, warm.k1, obs, priors, opt, hasPriors,
            first, poseDim, dim, true, baseIntrinsics, opt.huberRad)
        val withK1 = Result(fitted.rotations, fitted.costHistory, fitted.iterations,
            rms(fitted.rotations, obs, camerasFor(fitted.k1, baseIntrinsics, true)), fitted.k1)

        // Keep the coefficient only if it earned its place against an otherwise
        // identically-fitted model, *and* if the data could have measured it at
        // all. The two gates catch different failures: the first rejects a k1 that
        // does not help, the second a k1 that helps for the wrong reason.
        val improved = reference.rmsErrorRad - withK1.rmsErrorRad
        if (improved <= reference.rmsErrorRad * opt.distortionMinGain) return reference
        val signalRad = Math.abs(withK1.k1) * distortionLeverage(obs, baseIntrinsics)
        if (signalRad < Math.toRadians(opt.distortionMinSignalDeg)) return reference
        return withK1
    }

    /**
     * How much radial separation the correspondences actually offer, in normalised
     * image units per unit of k1.
     *
     * Brown-Conrady moves a point at normalised radius r out to r(1 + k1 r^2), so
     * what a matched pair says about k1 is the difference of its two radial
     * displacements, |rA^3 - rB^3|. Averaged over the correspondences that is the
     * angular signal one unit of k1 would produce - and, multiplied by a recovered
     * k1, the angle that coefficient claims to have measured.
     *
     * Zero for a capture whose frames all share an aim: every point lands at the
     * same radius in both views, the distortion cancels exactly, and no amount of
     * fitting can recover what the geometry did not encode.
     */
    @JvmStatic
    fun distortionLeverage(obs: List<Correspondence>?, base: Array<Intrinsics>?): Double {
        if (obs == null || base == null) return 0.0
        var sum = 0.0
        var n = 0
        for (o in obs) {
            val pa = o.pixelA ?: continue
            val pb = o.pixelB ?: continue
            if (o.frameA >= base.size || o.frameB >= base.size) continue
            val ra = normalisedRadius(base[o.frameA], pa)
            val rb = normalisedRadius(base[o.frameB], pb)
            sum += Math.abs(ra * ra * ra - rb * rb * rb)
            n++
        }
        return if (n == 0) 0.0 else sum / n
    }

    private fun normalisedRadius(k: Intrinsics, pixel: DoubleArray): Double =
        Math.hypot((pixel[0] - k.cx) / k.fx, (pixel[1] - k.cy) / k.fy)

    private fun optimise(initialR: Array<Mat3>, initialK1: Double,
                         obs: List<Correspondence>?, priors: Array<Mat3>?, opt: Options,
                         hasPriors: Boolean, first: Int, poseDim: Int, dim: Int,
                         solveK1: Boolean, baseIntrinsics: Array<Intrinsics>?,
                         huberRad: Double): Result {
        val hasObs = obs != null && obs.isNotEmpty()
        val n = initialR.size
        var R = initialR
        var k1 = initialK1
        var lambda = 1e-6
        var current = cost(R, obs, priors, opt, hasPriors, k1, baseIntrinsics, solveK1, huberRad)
        val history = DoubleArray(opt.maxIterations + 1)
        history[0] = current
        var accepted = 0

        for (iter in 0 until opt.maxIterations) {
            val h = Array(dim) { DoubleArray(dim) }
            val g = DoubleArray(dim)
            val cam = camerasFor(k1, baseIntrinsics, solveK1)

            if (hasObs) {
                // Differencing step for the distortion Jacobian. k1 enters the
                // residual through a Newton-inverted radial polynomial, so a
                // central difference is both simpler and better conditioned than
                // propagating an analytic derivative through that inverse.
                val hk = 1e-6
                val camPlus = if (solveK1) camerasFor(k1 + hk, baseIntrinsics, true) else null
                val camMinus = if (solveK1) camerasFor(k1 - hk, baseIntrinsics, true) else null

                for (c in obs) {
                    val ba = bearingA(c, cam)
                    val bb = bearingB(c, cam)
                    val u = R[c.frameA].mul(ba)
                    val v = R[c.frameB].mul(bb)
                    val t1 = u.anyPerpendicular()
                    val t2 = u.cross(t1)
                    val r1 = t1.dot(v)
                    val r2 = t2.dot(v)
                    val norm = Math.hypot(r1, r2)
                    val w = c.weight * huber(norm, huberRad)
                    if (w <= 0) continue
                    // d r_k / d w_j =  v x t_k ; d r_k / d w_i = -(v x t_k)
                    val j1 = v.cross(t1)
                    val j2 = v.cross(t2)
                    addResidual(h, g, first, c.frameA, c.frameB, j1, r1, w)
                    addResidual(h, g, first, c.frameA, c.frameB, j2, r2, w)

                    if (solveK1) {
                        // The tangent basis is held at the linearisation point, exactly
                        // as it is for the rotation blocks above.
                        val up = R[c.frameA].mul(bearingA(c, camPlus!!))
                        val vp = R[c.frameB].mul(bearingB(c, camPlus))
                        val um = R[c.frameA].mul(bearingA(c, camMinus!!))
                        val vm = R[c.frameB].mul(bearingB(c, camMinus))
                        // r_k = t_k . v, and t_k . u == 0 by construction, so the
                        // residual is really t_k . (v - u): both endpoints move with
                        // k1 and a change that moves them together must not register.
                        val e1 = ((t1.dot(vp) - t1.dot(up)) - (t1.dot(vm) - t1.dot(um))) / (2 * hk)
                        val e2 = ((t2.dot(vp) - t2.dot(up)) - (t2.dot(vm) - t2.dot(um))) / (2 * hk)
                        addDistortionResidual(h, g, first, poseDim, c.frameA, c.frameB, j1, e1, r1, w)
                        addDistortionResidual(h, g, first, poseDim, c.frameA, c.frameB, j2, e2, r2, w)
                    }
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
            var bestK1 = k1
            for (attempt in 0 until 12) {
                step = Linalg.solveSpdDamped(h, negate(g), lambda)
                if (step == null) { lambda *= 10; continue }
                val candidate = apply(R, step, first)
                val candidateK1 = if (solveK1)
                    clamp(k1 + step[poseDim], -opt.k1Limit, opt.k1Limit) else k1
                val c2 = cost(candidate, obs, priors, opt, hasPriors, candidateK1,
                    baseIntrinsics, solveK1, huberRad)
                if (c2 <= current) { bestCost = c2; bestR = candidate; bestK1 = candidateK1; break }
                lambda *= 10
            }
            if (bestR == null) break                       // no downhill step exists

            var magnitude = 0.0
            for (s2 in step!!) magnitude = Math.max(magnitude, Math.abs(s2))
            R = bestR
            k1 = bestK1
            val improvement = current - bestCost
            current = bestCost
            history[++accepted] = current
            lambda = Math.max(1e-12, lambda / 5)
            if (magnitude < opt.convergenceRad || improvement < 1e-18) break
        }

        val trimmed = DoubleArray(accepted + 1)
        System.arraycopy(history, 0, trimmed, 0, accepted + 1)
        return Result(R, trimmed, accepted,
            rms(R, obs, camerasFor(k1, baseIntrinsics, solveK1)), k1)
    }

    /** The camera model implied by the current k1, or null when distortion is fixed. */
    private fun camerasFor(k1: Double, base: Array<Intrinsics>?, solve: Boolean): Array<Intrinsics>? {
        if (!solve || base == null) return null
        return Array(base.size) { base[it].withDistortion(k1, 0.0, 0.0) }
    }

    private fun bearingA(c: Correspondence, cam: Array<Intrinsics>?): Vec3 {
        val p = c.pixelA
        if (cam == null || p == null) return c.bearingA
        return cam[c.frameA].unproject(p[0], p[1])
    }

    private fun bearingB(c: Correspondence, cam: Array<Intrinsics>?): Vec3 {
        val p = c.pixelB
        if (cam == null || p == null) return c.bearingB
        return cam[c.frameB].unproject(p[0], p[1])
    }

    private fun clamp(v: Double, lo: Double, hi: Double): Double =
        if (v < lo) lo else (if (v > hi) hi else v)

    /**
     * Adds the shared-distortion column of one residual row.
     *
     * k1 couples to every pose, so its cross terms against both frames' rotation
     * blocks have to go in as well or the normal matrix is inconsistent and the
     * step is not the one that minimises the linearised cost.
     */
    private fun addDistortionResidual(h: Array<DoubleArray>, g: DoubleArray, first: Int,
                                      poseDim: Int, frameA: Int, frameB: Int,
                                      jac: Vec3, dk: Double, residual: Double, w: Double) {
        val k = poseDim
        g[k] += w * dk * residual
        h[k][k] += w * dk * dk
        val ia = 3 * (frameA - first)
        val ib = 3 * (frameB - first)
        val rowA = doubleArrayOf(-jac.x, -jac.y, -jac.z)
        val rowB = doubleArrayOf(jac.x, jac.y, jac.z)
        for (a in 0 until 3) {
            if (frameA >= first) {
                h[ia + a][k] += w * rowA[a] * dk
                h[k][ia + a] += w * rowA[a] * dk
            }
            if (frameB >= first) {
                h[ib + a][k] += w * rowB[a] * dk
                h[k][ib + a] += w * rowB[a] * dk
            }
        }
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
                     opt: Options, hasPriors: Boolean, k1: Double,
                     baseIntrinsics: Array<Intrinsics>?, solveK1: Boolean,
                     huberRad: Double): Double {
        var c = 0.0
        val cam = camerasFor(k1, baseIntrinsics, solveK1)
        if (obs != null) {
            for (o in obs) {
                val a = R[o.frameA].mul(bearingA(o, cam))
                    .angleTo(R[o.frameB].mul(bearingB(o, cam)))
                c += o.weight * huberCost(a, huberRad)
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

    private fun rms(R: Array<Mat3>, obs: List<Correspondence>?,
                    cam: Array<Intrinsics>?): Double {
        if (obs == null || obs.isEmpty()) return 0.0
        var s = 0.0
        for (o in obs) {
            val a = R[o.frameA].mul(bearingA(o, cam))
                .angleTo(R[o.frameB].mul(bearingB(o, cam)))
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
