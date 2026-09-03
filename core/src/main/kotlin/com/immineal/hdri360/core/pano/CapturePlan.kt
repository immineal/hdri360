package com.immineal.hdri360.core.pano

import com.immineal.hdri360.core.camera.Intrinsics
import com.immineal.hdri360.core.math.Vec3
import java.util.Collections

/**
 * Where to point the camera, in the order to point it.
 *
 * Rings of constant pitch, with the azimuth step widened by 1/cos(pitch) so the
 * angular overlap stays constant all the way to the poles, plus dedicated zenith
 * and nadir frames. The order sweeps monotonically from nadir to zenith and
 * serpentines within each ring, so the user makes one continuous motion instead
 * of hopping across the sphere - which matters for handheld work, where every
 * large reorientation is another chance to move the entrance pupil.
 */
class CapturePlan private constructor(targets: List<CaptureTarget>) {
    @JvmField val targets: List<CaptureTarget> = Collections.unmodifiableList(targets)

    fun covers(worldDir: Vec3, k: Intrinsics): Boolean {
        for (t in targets)
            if (k.isVisible(t.rotation.mulTranspose(worldDir))) return true
        return false
    }

    fun frameCount(worldDir: Vec3, k: Intrinsics): Int {
        var n = 0
        for (t in targets)
            if (k.isVisible(t.rotation.mulTranspose(worldDir))) n++
        return n
    }

    /**
     * How many other frames share a meaningful part of frame [i]'s view.
     * Measured by sampling the frame rather than by comparing optical axes, so
     * "meaningful" means actual shared image area.
     */
    fun neighbourCount(i: Int, k: Intrinsics): Int = neighbourCount(i, k, 0.05, 12)

    fun neighbourCount(i: Int, k: Intrinsics, minSharedFraction: Double, grid: Int): Int {
        val a = targets[i]
        val samples = ArrayList<Vec3>()
        for (gy in 0 until grid)
            for (gx in 0 until grid) {
                val u = (gx + 0.5) * k.width / grid - 0.5
                val v = (gy + 0.5) * k.height / grid - 0.5
                samples.add(a.rotation.mul(k.unproject(u, v)))
            }
        var n = 0
        for (j in targets.indices) {
            if (j == i) continue
            var shared = 0
            for (d in samples) if (k.isVisible(targets[j].rotation.mulTranspose(d))) shared++
            if (shared >= minSharedFraction * samples.size) n++
        }
        return n
    }

    /** Total frames, including every bracket exposure, for a given plan. */
    fun frameTotal(bracketsPerTarget: Array<IntArray>): Int {
        var n = 0
        for (b in bracketsPerTarget) n += b.size
        return n
    }

    companion object {
        @JvmStatic
        fun forCamera(k: Intrinsics, cfg: CapturePlanConfig): CapturePlan {
            val hfov = k.horizontalFovDeg()
            val vfov = k.verticalFovDeg()
            val keep = Math.max(0.05, 1.0 - cfg.overlapFraction)
            val vStep = vfov * keep

            // Rings only need to reach the latitude from which a frame's own vertical
            // extent already swallows the pole; dedicated pole frames cover the rest.
            val maxRingPitch = Math.max(0.0, 90 - vfov / 2)
            val pitches = ArrayList<Double>()
            if (maxRingPitch < 1e-9) {
                pitches.add(0.0)
            } else {
                val steps = Math.max(1, Math.ceil((2 * maxRingPitch) / vStep - 1e-9).toInt())
                for (i in 0..steps)
                    pitches.add(-maxRingPitch + (2 * maxRingPitch) * i / steps.toDouble())
            }

            val out = ArrayList<CaptureTarget>()
            if (cfg.includePoles) out.add(CaptureTarget.lookingAt(Vec3(0.0, -1.0, 0.0)))

            var reverse = false
            for (pitch in pitches) {
                val cos = Math.max(0.15, Math.cos(Math.toRadians(pitch)))
                val azStep = hfov * keep / cos
                val count = Math.max(1, Math.ceil(360.0 / azStep - 1e-9).toInt())
                val ring = ArrayList<CaptureTarget>()
                for (i in 0 until count) {
                    val yaw = -180.0 + 360.0 * i / count
                    ring.add(CaptureTarget.lookingAt(CaptureTarget.directionFor(yaw, pitch)))
                }
                if (reverse) Collections.reverse(ring)
                reverse = !reverse
                out.addAll(ring)
            }
            if (cfg.includePoles) out.add(CaptureTarget.lookingAt(Vec3(0.0, 1.0, 0.0)))
            return CapturePlan(out)
        }
    }
}
