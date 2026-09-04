package com.immineal.hdri360.core.pano

import com.immineal.hdri360.core.math.Mat3
import com.immineal.hdri360.core.math.Quat
import com.immineal.hdri360.core.math.Vec3

/**
 * Device orientation to camera pose.
 *
 * Three frames meet here and none of them agree:
 *
 *  - Android's world frame: +X east, +Y magnetic north, +Z up.
 *  - The device frame: +X right of the screen, +Y up the screen, +Z out of it.
 *  - This pipeline's world frame: +Y up, +Z the reference heading, +X = Y x Z.
 *
 * Plus the camera sensor, which is mounted at some multiple of 90 degrees to the
 * device. Every one of these is a chance to ship a mirrored or upside-down
 * panorama, so the conversion lives in one place with the derivation written
 * down rather than being spread across the capture code.
 *
 * Display rotation deliberately plays no part: it changes how the preview is
 * drawn, not how the sensor is bolted to the phone, and the pipeline works on
 * sensor-frame images.
 */
object OrientationMath {

    /** Android world (east, north, up) into this pipeline's world (X, up, heading). */
    private val ANDROID_TO_WORLD = Mat3(doubleArrayOf(
        -1.0, 0.0, 0.0,
        0.0, 0.0, 1.0,
        0.0, 1.0, 0.0))

    /** Decodes TYPE_ROTATION_VECTOR, which may or may not carry the scalar part. */
    @JvmStatic
    fun quaternionFromRotationVector(values: FloatArray?): Quat {
        if (values == null || values.size < 3)
            throw IllegalArgumentException("rotation vector needs at least three components")
        val x = values[0].toDouble()
        val y = values[1].toDouble()
        val z = values[2].toDouble()
        val w: Double
        if (values.size >= 4) {
            w = values[3].toDouble()
        } else {
            val t = 1.0 - (x * x + y * y + z * z)
            w = if (t > 0) Math.sqrt(t) else 0.0
        }
        return Quat(w, x, y, z).normalized()
    }

    /**
     * Camera-to-world rotation.
     *
     * @param deviceRotation device-to-Android-world, from the rotation vector
     * @param sensorOrientationDeg CameraCharacteristics.SENSOR_ORIENTATION
     */
    @JvmStatic
    fun cameraToWorld(deviceRotation: Quat, sensorOrientationDeg: Int, frontFacing: Boolean): Mat3 =
        ANDROID_TO_WORLD.mul(deviceRotation.toMat3())
            .mul(cameraToDevice(sensorOrientationDeg, frontFacing))
            .orthonormalized()

    /**
     * Camera axes expressed in device coordinates.
     *
     * SENSOR_ORIENTATION is defined as the clockwise rotation needed to make the
     * captured image upright in the device's natural orientation. Turning the
     * raw image clockwise by t therefore gives the picture, so the raw image's
     * own axes are found by turning the picture's axes back:
     *
     *   image right (1, 0) -> ( cos t,  sin t) in picture coordinates
     *   image down  (0, 1) -> (-sin t,  cos t)
     *
     * and the picture is drawn on the screen, whose axes in the device frame are
     * right = (1, 0, 0) and down = (0, -1, 0). Substituting:
     *
     *   right = ( cos t, -sin t, 0), down = (-sin t, -cos t, 0)
     *
     * with cross product (0, 0, -1) for every t - the back camera always looks
     * out of the back of the phone, as it should.
     *
     * The sign matters and is not cosmetic: taking t for -t leaves every length,
     * every determinant and the optical axis itself unchanged, and rolls the
     * live pose 180 degrees about that axis on any phone whose camera is mounted
     * at 90 or 270. The guidance then leads the person away from the target in
     * both screen axes at once.
     */
    @JvmStatic
    fun cameraToDevice(sensorOrientationDeg: Int, frontFacing: Boolean): Mat3 {
        val t = Math.toRadians(sensorOrientationDeg.toDouble())
        val c = Math.cos(t)
        val s = Math.sin(t)
        var right = Vec3(c, -s, 0.0)
        val down = Vec3(-s, -c, 0.0)
        if (frontFacing) {
            // The front camera looks the other way; keep the triad right-handed.
            val forward = Vec3(0.0, 0.0, 1.0)
            right = down.cross(forward)
            return Mat3.fromColumns(right, down, forward)
        }
        val forward = right.cross(down)       // (0, 0, -1)
        return Mat3.fromColumns(right, down, forward)
    }

    /** True when the device is still enough that a long exposure will not smear. */
    @JvmStatic
    fun isStable(gyroRadPerSec: FloatArray?, thresholdRadPerSec: Double): Boolean {
        if (gyroRadPerSec == null || gyroRadPerSec.size < 3) return false
        val n = Math.sqrt(
            (gyroRadPerSec[0] * gyroRadPerSec[0] +
             gyroRadPerSec[1] * gyroRadPerSec[1] +
             gyroRadPerSec[2] * gyroRadPerSec[2]).toDouble())
        return n < thresholdRadPerSec
    }
}
