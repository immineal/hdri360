package com.immineal.hdri360.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.immineal.hdri360.core.camera.Intrinsics
import com.immineal.hdri360.core.math.Mat3
import com.immineal.hdri360.core.pano.CaptureGuide
import com.immineal.hdri360.core.pano.CaptureTarget

/**
 * Where to point the phone next, drawn over the live preview.
 *
 * Targets in front of the camera are projected through the same camera model the
 * stitcher will use, so what the user is asked to line up is exactly what the
 * pipeline expects to receive. Targets behind the camera cannot be projected at
 * all - the model folds - so they become an arrow at the edge pointing the
 * shorter way round rather than a dot in a meaningless place.
 */
@Composable
fun SphereOverlay(
    targets: List<CaptureTarget>,
    shot: BooleanArray?,
    /** Directions the metering sweep has reached, for the sweep's own display. */
    metered: BooleanArray? = null,
    /** True during the sweep, when what matters is where you have not looked. */
    scanning: Boolean = false,
    abandoned: BooleanArray?,
    current: Int,
    pose: Mat3?,
    intrinsics: Intrinsics?,
    aligned: Boolean,
    steady: Boolean,
    modifier: Modifier = Modifier,
    /** Clockwise degrees to rotate projected coordinates into the view's frame. */
    rotationDeg: Int = 0,
    /**
     * The preview's laid-out size in view pixels, before it is rotated - exactly
     * what the preview surface was given. Markers are placed through the same
     * transform, so they land on the thing they are pointing at rather than near
     * it.
     */
    frameWidthPx: Float = 0f,
    frameHeightPx: Float = 0f,
    /** Degrees the phone is rolled from the target's pose, or null when unknown. */
    rollErrorDeg: Double? = null
) {
    Canvas(modifier = modifier) {
        if (pose == null || intrinsics == null || targets.isEmpty()) return@Canvas
        val map = ViewMap(intrinsics, size, rotationDeg, frameWidthPx, frameHeightPx)

        for (i in targets.indices) {
            // During the sweep the dots are a map of where the meter has been, so
            // finishing it is a matter of filling in the empty ones rather than
            // of guessing which way to turn.
            val done = if (scanning) metered != null && i < metered.size && metered[i]
                       else shot != null && i < shot.size && shot[i]
            val gaveUp = !scanning && abandoned != null && i < abandoned.size && abandoned[i]
            val isCurrent = !scanning && i == current
            // Into the camera's own frame: the pose is camera-to-world.
            val dirCam = pose.mulTranspose(targets[i].direction)
            val p = if (dirCam.z > 1e-6) intrinsics.project(dirCam) else null
            if (p != null) {
                val at = map.toView(p[0], p[1])
                if (at != null) {
                    drawTarget(at, done, gaveUp, isCurrent, aligned && isCurrent, steady)
                    continue
                }
            }
            if (isCurrent) drawEdgeArrow(dirCam.x, dirCam.y, dirCam.z, rotationDeg)
        }

        // The reticle. Green only when a bracket could actually fire right now,
        // so it means "hold still", not "roughly there".
        val ready = aligned && steady
        val centre = Offset(size.width / 2, size.height / 2)
        val radius = size.minDimension * 0.055f
        drawCircle(
            color = if (ready) OK else if (aligned) NEAR else IDLE,
            radius = radius,
            center = centre,
            style = Stroke(width = if (ready) 6f else 3f))

        // How far the phone is rolled from the pose the plan wants. Drawn rather
        // than worded, because "turn it left" depends on which way round you think
        // you are holding it and a line does not.
        if (rollErrorDeg != null && Math.abs(rollErrorDeg) > 1.5) {
            val a = Math.toRadians(rollErrorDeg)
            val ux = Math.cos(a).toFloat()
            val uy = Math.sin(a).toFloat()
            val reach = radius * 2.4f
            val level = if (Math.abs(rollErrorDeg) <= 8) NEAR else MISSED
            drawLine(IDLE, Offset(centre.x - reach, centre.y), Offset(centre.x + reach, centre.y),
                strokeWidth = 2f)
            drawLine(level,
                Offset(centre.x - ux * reach, centre.y - uy * reach),
                Offset(centre.x + ux * reach, centre.y + uy * reach), strokeWidth = 5f)
        }
    }
}

private val DONE = Color(0xFF39C36B)
private val PENDING = Color(0x66FFFFFF)
private val CURRENT = Color(0xFFFFC400)
private val OK = Color(0xFF39C36B)
private val NEAR = Color(0xFFFFC400)
private val IDLE = Color(0x88FFFFFF)
private val MISSED = Color(0xFFFF6E5A)

private fun DrawScope.drawTarget(at: Offset, done: Boolean, gaveUp: Boolean, current: Boolean,
                                 aligned: Boolean, steady: Boolean) {
    val r = size.minDimension * (if (current) 0.035f else 0.018f)
    when {
        // Shown, and shown as a hole rather than quietly counted as finished: a
        // direction that could not be shot is a gap in the sphere, and the user is
        // the only one who can go back and fill it.
        gaveUp -> {
            drawLine(MISSED, Offset(at.x - r, at.y - r), Offset(at.x + r, at.y + r), strokeWidth = 3f)
            drawLine(MISSED, Offset(at.x - r, at.y + r), Offset(at.x + r, at.y - r), strokeWidth = 3f)
        }
        done -> {
            drawCircle(DONE.copy(alpha = 0.35f), r, at)
            drawCircle(DONE, r, at, style = Stroke(width = 2f))
        }
        current -> {
            drawCircle(if (aligned && steady) OK else CURRENT, r, at,
                style = Stroke(width = 4f))
            drawCircle(CURRENT.copy(alpha = 0.18f), r * 0.7f, at)
        }
        else -> drawCircle(PENDING, r, at, style = Stroke(width = 2f))
    }
}

/**
 * The target is behind the camera, so point at it instead.
 *
 * The direction is taken from the bearing's own x and y, which stay meaningful
 * when z goes negative even though the projection does not.
 */
private fun DrawScope.drawEdgeArrow(x: Double, y: Double, z: Double, rotationDeg: Int) {
    var dx = x
    var dy = y
    if (z < 0) { dx = -dx; dy = -dy }        // shorter way round, not through the floor
    val len = Math.hypot(dx, dy)
    if (len < 1e-9) return
    val a = Math.toRadians(rotationDeg.toDouble())
    val cos = Math.cos(a)
    val sin = Math.sin(a)
    val ux = ((dx / len) * cos - (dy / len) * sin).toFloat()
    val uy = ((dx / len) * sin + (dy / len) * cos).toFloat()
    val centre = Offset(size.width / 2, size.height / 2)
    val reach = size.minDimension * 0.36f
    val tip = Offset(centre.x + ux * reach, centre.y + uy * reach)
    val back = Offset(centre.x + ux * reach * 0.72f, centre.y + uy * reach * 0.72f)
    val side = Offset(-uy, ux) * (size.minDimension * 0.03f)
    drawLine(CURRENT, back, tip, strokeWidth = 6f)
    drawLine(CURRENT, tip, back + side, strokeWidth = 6f)
    drawLine(CURRENT, tip, back - side, strokeWidth = 6f)
}

/**
 * Capture pixels into view pixels.
 *
 * The preview is laid out in the sensor's own orientation and then turned, since
 * a phone's sensor is mounted a quarter turn from the way the phone is held. The
 * markers go through that same turn - which is the whole point of doing it here
 * rather than by eye. Rotating one and not the other is what makes the guidance
 * read as inverted: the arrow says right, the dot is below, and the user is
 * being asked to trust two things that disagree.
 */
private class ViewMap(
    private val k: Intrinsics,
    private val size: Size,
    rotationDeg: Int,
    frameWidthPx: Float,
    frameHeightPx: Float
) {
    private val radians = Math.toRadians(rotationDeg.toDouble())
    private val cos = Math.cos(radians)
    private val sin = Math.sin(radians)
    // Fall back to covering the view in the sensor's own orientation when the
    // caller has not said how the preview was laid out.
    private val fw = if (frameWidthPx > 0) frameWidthPx.toDouble() else size.width.toDouble()
    private val fh = if (frameHeightPx > 0) frameHeightPx.toDouble() else size.height.toDouble()

    fun toView(px: Double, py: Double): Offset? {
        // Normalised and centred, so the layout is a scale and the mounting is a
        // rotation, in that order - the same two steps the preview surface takes.
        val nx = (px - (k.width - 1) / 2.0) / k.width
        val ny = (py - (k.height - 1) / 2.0) / k.height
        val lx = nx * fw
        val ly = ny * fh
        val x = (size.width / 2 + lx * cos - ly * sin).toFloat()
        val y = (size.height / 2 + lx * sin + ly * cos).toFloat()
        if (!x.isFinite() || !y.isFinite()) return null
        val margin = size.minDimension * 0.5f
        if (x < -margin || y < -margin || x > size.width + margin || y > size.height + margin)
            return null
        return Offset(x, y)
    }
}

/**
 * How far off the current target is, worded in the frame the person is holding.
 *
 * The pose is in the sensor's own frame, so the raw offsets are the sensor's
 * right and up - which on a phone held in portrait, with a sensor mounted at 90
 * degrees, are the screen's down and right. Telling someone to move right while
 * the marker is below them is worse than telling them nothing.
 */
fun aimText(pose: Mat3?, target: CaptureTarget?, rotationDeg: Int = 0): String {
    if (pose == null || target == null) return ""
    val off = CaptureGuide.guidanceOffsetDeg(pose, target)
    // In sensor image terms: x is right, y is down, so up is negative y.
    val a = Math.toRadians(rotationDeg.toDouble())
    val cos = Math.cos(a)
    val sin = Math.sin(a)
    val sx = off[0] * cos - (-off[1]) * sin
    val sy = off[0] * sin + (-off[1]) * cos
    val parts = ArrayList<String>(2)
    if (Math.abs(sx) >= 3) parts.add(if (sx > 0) "right ${Math.round(Math.abs(sx))}°"
                                     else "left ${Math.round(Math.abs(sx))}°")
    if (Math.abs(sy) >= 3) parts.add(if (sy > 0) "down ${Math.round(Math.abs(sy))}°"
                                     else "up ${Math.round(Math.abs(sy))}°")
    return if (parts.isEmpty()) "hold still" else parts.joinToString("  ")
}
