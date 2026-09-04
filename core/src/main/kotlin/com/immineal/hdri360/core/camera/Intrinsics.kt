package com.immineal.hdri360.core.camera

import com.immineal.hdri360.core.math.Vec3
import java.util.Locale

/**
 * Pinhole camera with Brown-Conrady radial distortion.
 *
 * Camera frame: +X right, +Y down, +Z along the optical axis (OpenCV convention).
 * Pixel coordinates are fractional pixel indices, so an image w pixels wide spans
 * [-0.5, w-0.5] and its centre sits at (w-1)/2.
 *
 * Tangential terms are deliberately omitted: phone modules are well centred, the
 * gyro-seeded bundle adjustment absorbs what little decentring remains, and
 * leaving them out keeps the inverse mapping a well-behaved 1-D Newton solve.
 */
class Intrinsics(
    @JvmField val width: Int,
    @JvmField val height: Int,
    @JvmField val fx: Double,
    @JvmField val fy: Double,
    @JvmField val cx: Double,
    @JvmField val cy: Double,
    @JvmField val k1: Double,
    @JvmField val k2: Double,
    @JvmField val k3: Double
) {
    init {
        if (width <= 0 || height <= 0) throw IllegalArgumentException("bad image size")
        if (!(fx > 0) || !(fy > 0)) throw IllegalArgumentException("bad focal length")
    }

    fun withDistortion(k1: Double, k2: Double, k3: Double) =
        Intrinsics(width, height, fx, fy, cx, cy, k1, k2, k3)

    fun hasDistortion(): Boolean = k1 != 0.0 || k2 != 0.0 || k3 != 0.0

    /** Rescale to a different working resolution, preserving the field of view. */
    fun scaled(s: Double): Intrinsics {
        val w = Math.max(1, Math.round(width * s).toInt())
        val h = Math.max(1, Math.round(height * s).toInt())
        return Intrinsics(w, h, fx * s, fy * s,
            (cx + 0.5) * s - 0.5, (cy + 0.5) * s - 0.5, k1, k2, k3)
    }

    /**
     * Project a bearing given in camera coordinates.
     * @return {u, v} or null if the point is not strictly in front of the camera.
     */
    fun project(dirCam: Vec3): DoubleArray? {
        if (!(dirCam.z > 1e-12)) return null
        val x = dirCam.x / dirCam.z
        val y = dirCam.y / dirCam.z
        val r2 = x * x + y * y
        // Past the radius where the radial polynomial stops increasing, the model
        // folds over: with a negative k1 a bearing far outside the real field of
        // view maps back *inside* the frame, and every consumer here - the
        // renderer, the visibility test, the matcher - would take it for a genuine
        // observation. It is also exactly where unproject's Newton iteration stops
        // having a unique root, so refusing is the only consistent answer.
        if (hasDistortion() && radialDerivative(r2) <= 0) return null
        val f = radialFactor(r2)
        return doubleArrayOf(fx * x * f + cx, fy * y * f + cy)
    }

    /**
     * [project] without the allocation: writes {u, v} into [out] and returns
     * whether the bearing projects at all.
     *
     * Same expressions in the same order as the allocating version - a renderer
     * that walks a frame at a time calls this once per output pixel per frame,
     * and a DoubleArray each time is the difference between a render and a
     * garbage collection.
     */
    fun project(dx: Double, dy: Double, dz: Double, out: DoubleArray): Boolean {
        if (!(dz > 1e-12)) return false
        val x = dx / dz
        val y = dy / dz
        val r2 = x * x + y * y
        if (hasDistortion() && radialDerivative(r2) <= 0) return false
        val f = radialFactor(r2)
        out[0] = fx * x * f + cx
        out[1] = fy * y * f + cy
        return true
    }

    /** Unit bearing in camera coordinates for a pixel. */
    fun unproject(u: Double, v: Double): Vec3 {
        val xd = (u - cx) / fx
        val yd = (v - cy) / fy
        val rd = Math.hypot(xd, yd)
        if (rd < 1e-15 || !hasDistortion()) return Vec3(xd, yd, 1.0).normalized()
        val ru = solveUndistortedRadius(rd)
        val s = ru / rd
        return Vec3(xd * s, yd * s, 1.0).normalized()
    }

    private fun radialFactor(r2: Double): Double = 1.0 + r2 * (k1 + r2 * (k2 + r2 * k3))

    /** d(distorted radius)/d(true radius); positive exactly where the model is invertible. */
    private fun radialDerivative(r2: Double): Double =
        1.0 + r2 * (3 * k1 + r2 * (5 * k2 + 7 * k3 * r2))

    /**
     * Invert rd = ru * (1 + k1 ru^2 + k2 ru^4 + k3 ru^6) by Newton iteration.
     * Quadratic convergence; 20 iterations is far more than the ~5 actually needed.
     */
    private fun solveUndistortedRadius(rd: Double): Double {
        var ru = rd
        for (i in 0 until 20) {
            val r2 = ru * ru
            val f = 1.0 + r2 * (k1 + r2 * (k2 + r2 * k3))
            val g = ru * f - rd
            val dg = 1.0 + r2 * (3 * k1 + r2 * (5 * k2 + 7 * k3 * r2))
            if (Math.abs(dg) < 1e-12) break
            val step = g / dg
            ru -= step
            if (Math.abs(step) < 1e-15) break
        }
        return ru
    }

    /** Nominal horizontal field of view in degrees (principal point assumed central). */
    fun horizontalFovDeg(): Double = 2 * Math.toDegrees(Math.atan((width / 2.0) / fx))

    fun verticalFovDeg(): Double = 2 * Math.toDegrees(Math.atan((height / 2.0) / fy))

    fun diagonalFovDeg(): Double =
        2 * Math.toDegrees(Math.atan(Math.hypot(width / (2.0 * fx), height / (2.0 * fy))))

    /** Half the diagonal FOV: the cone half-angle the frame can possibly see. */
    fun maxAngleFromAxisRad(): Double =
        Math.atan(Math.hypot(width / (2.0 * fx), height / (2.0 * fy)))

    /** True if the bearing falls inside the imaging rectangle. */
    fun isVisible(dirCam: Vec3): Boolean {
        val p = project(dirCam) ?: return false
        return p[0] >= -0.5 - 1e-9 && p[0] <= width - 0.5 + 1e-9 &&
               p[1] >= -0.5 - 1e-9 && p[1] <= height - 0.5 + 1e-9
    }

    override fun toString(): String = String.format(Locale.US,
        "Intrinsics[%dx%d f=(%.2f,%.2f) c=(%.2f,%.2f) k=(%.4f,%.4f,%.4f) hfov=%.2f]",
        width, height, fx, fy, cx, cy, k1, k2, k3, horizontalFovDeg())

    companion object {
        @JvmStatic
        fun pinhole(width: Int, height: Int, fx: Double, fy: Double) =
            Intrinsics(width, height, fx, fy, (width - 1) / 2.0, (height - 1) / 2.0, 0.0, 0.0, 0.0)

        /** From the physical numbers CameraCharacteristics reports. */
        @JvmStatic
        fun fromSensor(width: Int, height: Int, sensorWidthMm: Double,
                       sensorHeightMm: Double, focalLengthMm: Double): Intrinsics {
            val fx = width * focalLengthMm / sensorWidthMm
            val fy = height * focalLengthMm / sensorHeightMm
            return pinhole(width, height, fx, fy)
        }

        /** Square-pixel model from a stated horizontal field of view. */
        @JvmStatic
        fun fromHorizontalFov(width: Int, height: Int, fovDeg: Double): Intrinsics {
            val f = (width / 2.0) / Math.tan(Math.toRadians(fovDeg) / 2.0)
            return pinhole(width, height, f, f)
        }
    }
}
