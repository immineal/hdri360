package com.immineal.hdri360.core.pano

import com.immineal.hdri360.core.math.Mat3
import com.immineal.hdri360.core.math.Vec3
import java.util.Locale

/** One frame the user is asked to shoot: where to point, and the pose that implies. */
class CaptureTarget(
    @JvmField val direction: Vec3,
    @JvmField val yawDeg: Double,
    @JvmField val pitchDeg: Double,
    /** Camera-to-world rotation. Columns are the world directions of camera X, Y, Z. */
    @JvmField val rotation: Mat3
) {

    override fun toString(): String =
        String.format(Locale.US, "target[yaw %.1f, pitch %.1f]", yawDeg, pitchDeg)

    companion object {
        /**
         * Upright pose looking along [forward]: the camera's down axis is as
         * close to world-down as the heading allows, and the result is right-handed
         * so the panorama comes out unmirrored.
         */
        @JvmStatic
        fun lookingAt(forward: Vec3): CaptureTarget {
            val f = forward.normalized()
            val up = Vec3(0.0, 1.0, 0.0)
            val ref = if (Math.abs(f.dot(up)) > 0.999) Vec3(0.0, 0.0, 1.0) else up
            // Component of world-down perpendicular to the optical axis.
            val down = ref.negate().sub(f.scale(ref.negate().dot(f))).normalized()
            val right = down.cross(f)                     // X = Y x Z keeps the triad right-handed
            val R = Mat3.fromColumns(right, down, f).orthonormalized()
            val lat = Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0, f.y))))
            val lon = Math.toDegrees(Math.atan2(-f.x, f.z))
            return CaptureTarget(f, lon, lat, R)
        }

        /** Direction for a yaw (right-positive, degrees) and pitch (up-positive, degrees). */
        @JvmStatic
        fun directionFor(yawDeg: Double, pitchDeg: Double): Vec3 {
            val lon = Math.toRadians(yawDeg)
            val lat = Math.toRadians(pitchDeg)
            val c = Math.cos(lat)
            return Vec3(-Math.sin(lon) * c, Math.sin(lat), Math.cos(lon) * c)
        }
    }
}
