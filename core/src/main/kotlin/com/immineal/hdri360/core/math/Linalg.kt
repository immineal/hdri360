package com.immineal.hdri360.core.math

/**
 * The handful of dense linear-algebra routines the pipeline needs. Kept in-house
 * because the app ships with no third-party dependencies and the problems are
 * tiny (3x3 SVD, and normal equations of at most a few hundred unknowns).
 */
object Linalg {

    /** Result of a 3x3 singular value decomposition M = U * diag(s) * V^T, s sorted descending. */
    class Svd3 internal constructor(
        @JvmField val u: Mat3,
        @JvmField val s: DoubleArray,
        @JvmField val v: Mat3
    )

    /**
     * One-sided Jacobi SVD. Orthogonalises the columns of M by accumulating plane
     * rotations into V; what is left is U * diag(s). Iterative but converges in a
     * handful of sweeps for 3x3 and is accurate to near machine precision.
     */
    @JvmStatic
    fun svd3(m: Mat3): Svd3 {
        val w = Array(3) { DoubleArray(3) }   // columns of M, w[col][row]
        for (c in 0 until 3)
            for (r in 0 until 3) w[c][r] = m.get(r, c)
        val v = arrayOf(
            doubleArrayOf(1.0, 0.0, 0.0),
            doubleArrayOf(0.0, 1.0, 0.0),
            doubleArrayOf(0.0, 0.0, 1.0)) // v[col][row]

        for (sweep in 0 until 60) {
            var off = 0.0
            for (p in 0 until 2) {
                for (q in p + 1 until 3) {
                    val alpha = dot(w[p], w[p])
                    val beta = dot(w[q], w[q])
                    val gamma = dot(w[p], w[q])
                    off += Math.abs(gamma)
                    if (Math.abs(gamma) < 1e-18 * Math.sqrt(alpha * beta) || gamma == 0.0) continue
                    val zeta = (beta - alpha) / (2.0 * gamma)
                    val sign = if (zeta >= 0) 1.0 else -1.0
                    val tan = sign / (Math.abs(zeta) + Math.sqrt(1.0 + zeta * zeta))
                    val cos = 1.0 / Math.sqrt(1.0 + tan * tan)
                    val sin = cos * tan
                    rotateCols(w, p, q, cos, sin)
                    rotateCols(v, p, q, cos, sin)
                }
            }
            if (off < 1e-17) break
        }

        val s = DoubleArray(3)
        for (c in 0 until 3) s[c] = Math.sqrt(dot(w[c], w[c]))

        val order = arrayOf(0, 1, 2)
        java.util.Arrays.sort(order) { a, b -> java.lang.Double.compare(s[b], s[a]) }

        val sSorted = DoubleArray(3)
        val uCols = arrayOfNulls<DoubleArray>(3)
        val vCols = arrayOfNulls<DoubleArray>(3)
        val scale = Math.max(s[order[0]], 1e-300)
        for (k in 0 until 3) {
            val c = order[k]
            sSorted[k] = s[c]
            vCols[k] = v[c].copyOf()
            if (s[c] > 1e-14 * scale) {
                val col = DoubleArray(3)
                for (r in 0 until 3) col[r] = w[c][r] / s[c]
                uCols[k] = col
            } else {
                uCols[k] = null // filled in below by orthogonal completion
            }
        }
        completeOrthonormal(uCols)
        // Keep U and V right-handed together so U S V^T still reproduces M.
        var U = colsToMat(uCols)
        val V = colsToMat(vCols)
        val recon = U.mul(Mat3.diag(sSorted[0], sSorted[1], sSorted[2])).mul(V.transpose())
        if (recon.sub(m).maxAbs() > 1e-9 * Math.max(1.0, m.maxAbs())) {
            // A degenerate column got the wrong sign; flip the null direction.
            for (k in 0 until 3) {
                if (sSorted[k] <= 1e-14 * scale) {
                    val col = uCols[k]!!
                    for (r in 0 until 3) col[r] = -col[r]
                }
            }
            U = colsToMat(uCols)
        }
        return Svd3(U, sSorted, V)
    }

    private fun completeOrthonormal(cols: Array<DoubleArray?>) {
        // Gram-Schmidt any missing columns against the present ones.
        for (k in 0 until 3) {
            if (cols[k] != null) continue
            var best: DoubleArray? = null
            var bestNorm = -1.0
            for (seed in arrayOf(
                doubleArrayOf(1.0, 0.0, 0.0),
                doubleArrayOf(0.0, 1.0, 0.0),
                doubleArrayOf(0.0, 0.0, 1.0))) {
                val c = seed.copyOf()
                for (j in 0 until 3) {
                    val cj = cols[j] ?: continue
                    val d = dot(c, cj)
                    for (r in 0 until 3) c[r] -= d * cj[r]
                }
                val n = Math.sqrt(dot(c, c))
                if (n > bestNorm) { bestNorm = n; best = c }
            }
            val b = best!!
            for (r in 0 until 3) b[r] /= bestNorm
            cols[k] = b
        }
    }

    private fun colsToMat(cols: Array<DoubleArray?>): Mat3 {
        val c0 = cols[0]!!; val c1 = cols[1]!!; val c2 = cols[2]!!
        return Mat3(doubleArrayOf(
            c0[0], c1[0], c2[0],
            c0[1], c1[1], c2[1],
            c0[2], c1[2], c2[2]))
    }

    private fun rotateCols(cols: Array<DoubleArray>, p: Int, q: Int, cos: Double, sin: Double) {
        for (r in 0 until 3) {
            val a = cols[p][r]; val b = cols[q][r]
            cols[p][r] = cos * a - sin * b
            cols[q][r] = sin * a + cos * b
        }
    }

    private fun dot(a: DoubleArray, b: DoubleArray): Double =
        a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    /**
     * Solves A x = b for symmetric positive-definite A by Cholesky factorisation.
     * @return the solution, or null if A is not positive definite (caller should damp).
     */
    @JvmStatic
    fun solveSpd(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        val l = Array(n) { DoubleArray(n) }
        for (i in 0 until n) {
            for (j in 0..i) {
                var sum = a[i][j]
                for (k in 0 until j) sum -= l[i][k] * l[j][k]
                if (i == j) {
                    if (!(sum > 1e-300)) return null
                    l[i][i] = Math.sqrt(sum)
                } else {
                    l[i][j] = sum / l[j][j]
                }
            }
        }
        val y = DoubleArray(n)
        for (i in 0 until n) {
            var sum = b[i]
            for (k in 0 until i) sum -= l[i][k] * y[k]
            y[i] = sum / l[i][i]
        }
        val x = DoubleArray(n)
        for (i in n - 1 downTo 0) {
            var sum = y[i]
            for (k in i + 1 until n) sum -= l[k][i] * x[k]
            x[i] = sum / l[i][i]
        }
        for (v in x) if (!isFinite(v)) return null
        return x
    }

    /**
     * Levenberg-style damped solve: (A + lambda * diag(A) + eps I) x = b, retrying with
     * progressively heavier damping instead of failing.
     */
    @JvmStatic
    fun solveSpdDamped(a: Array<DoubleArray>, b: DoubleArray, lambda: Double): DoubleArray? {
        var lam = lambda
        val n = b.size
        var meanDiag = 0.0
        for (i in 0 until n) meanDiag += Math.abs(a[i][i])
        meanDiag = Math.max(meanDiag / n, 1e-12)
        for (attempt in 0 until 40) {
            val d = Array(n) { i -> a[i].copyOf() }
            for (i in 0 until n) d[i][i] += lam * (Math.abs(a[i][i]) + meanDiag)
            val x = solveSpd(d, b)
            if (x != null) return x
            lam *= 10
        }
        return null
    }

    private fun isFinite(v: Double): Boolean = !v.isNaN() && !v.isInfinite()
}
