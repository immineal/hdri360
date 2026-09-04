package com.immineal.hdri360.core.pano

import com.immineal.hdri360.core.math.Mat3
import com.immineal.hdri360.core.math.SO3
import com.immineal.hdri360.core.math.Vec3

/** The logic behind the on-screen guidance: what to shoot next, and which way to turn. */
object CaptureGuide {

    /**
     * Index of the closest target still to be shot, or -1 if the sphere is done.
     * Closest rather than next-in-order, so a user who wanders off the intended
     * sweep is not asked to walk all the way back.
     */
    @JvmStatic
    fun nearestPendingTarget(targets: List<CaptureTarget>, shot: BooleanArray?, currentPose: Mat3): Int {
        val forward = currentPose.mul(Vec3(0.0, 0.0, 1.0))
        var best = -1
        var bestAngle = Double.MAX_VALUE
        for (i in targets.indices) {
            if (shot != null && i < shot.size && shot[i]) continue
            val a = forward.angleTo(targets[i].direction)
            if (a < bestAngle) { bestAngle = a; best = i }
        }
        return best
    }

    /** Full pose agreement, roll included: a rolled frame leaves a gap the plan did not budget for. */
    @JvmStatic
    fun withinTolerance(currentPose: Mat3, target: CaptureTarget, toleranceRad: Double): Boolean =
        SO3.angleBetween(currentPose, target.rotation) <= toleranceRad

    /** How far the camera's own axis is from the target direction, roll ignored. */
    @JvmStatic
    fun axisErrorRad(currentPose: Mat3, target: CaptureTarget): Double =
        currentPose.mul(Vec3(0.0, 0.0, 1.0)).angleTo(target.direction)

    /**
     * Aim and roll judged separately, because they are not equally costly to get
     * wrong.
     *
     * Where the camera points decides which part of the sphere is captured, and
     * an error there leaves a hole the plan budgeted no overlap for. Roll only
     * turns the footprint about its own centre: at the plan's overlap a frame can
     * be rolled a good deal further than it can be mis-aimed before anything is
     * actually lost. Holding both to one tight number is what makes a sphere
     * unshootable by hand - the aim is reached, the wrist is a few degrees off,
     * and the shutter never fires.
     */
    @JvmStatic
    fun withinTolerance(currentPose: Mat3, target: CaptureTarget,
                        axisRad: Double, rollRad: Double): Boolean {
        if (axisErrorRad(currentPose, target) > axisRad) return false
        return Math.abs(Math.toRadians(rollErrorDeg(currentPose, target))) <= rollRad
    }

    /**
     * How far to turn, expressed in the camera's own frame so the arrow can point
     * straight at it: {yaw, pitch} in degrees, positive meaning right and up.
     */
    @JvmStatic
    fun guidanceOffsetDeg(currentPose: Mat3, target: CaptureTarget): DoubleArray {
        val cam = currentPose.mulTranspose(target.direction)
        val yaw = Math.toDegrees(Math.atan2(cam.x, cam.z))
        val pitch = -Math.toDegrees(Math.atan2(cam.y, Math.hypot(cam.x, cam.z)))
        return doubleArrayOf(yaw, pitch)
    }

    /** Roll error in degrees, positive meaning the device is rotated clockwise of the target. */
    @JvmStatic
    fun rollErrorDeg(currentPose: Mat3, target: CaptureTarget): Double {
        val relative = currentPose.transpose().mul(target.rotation)
        val log = SO3.log(relative)
        return Math.toDegrees(log.z)
    }

    /** Fraction of the plan already captured. */
    @JvmStatic
    fun progress(shot: BooleanArray?): Double {
        if (shot == null || shot.isEmpty()) return 1.0
        var n = 0
        for (b in shot) if (b) n++
        return n / shot.size.toDouble()
    }
}
