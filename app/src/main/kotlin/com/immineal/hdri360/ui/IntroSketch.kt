package com.immineal.hdri360.ui

import android.content.Context
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.immineal.hdri360.core.camera.Intrinsics
import com.immineal.hdri360.core.capture.CaptureController
import com.immineal.hdri360.core.hdr.ToneMapper
import com.immineal.hdri360.core.math.Vec3
import com.immineal.hdri360.core.pano.CapturePlan
import com.immineal.hdri360.core.pano.CapturePlanConfig
import com.immineal.hdri360.core.pano.CaptureTarget
import java.io.File

/**
 * What this app asks of a person, before it asks.
 *
 * A full-sphere capture is an unusual thing to be asked to do: turn all the way
 * round, then point at each of thirty-odd places and hold still. Nothing on the
 * capture screen can explain that while it is happening - the screen is needed
 * for the thing itself - and an explanation nobody has read yet is why the first
 * sweep is the one that goes wrong.
 *
 * Four steps, one sentence each, and each of them **drawn moving** rather than
 * described. The drawing is the explanation; the sentence is a label for it. That
 * is the whole design constraint: a person who reads none of the words should
 * still know what to do, because they watched it happen.
 *
 * Two rules follow from that, and everything below obeys them. The drawings use
 * the capture screen's own visual language - amber for the thing you are pointing
 * at, green for a direction that is finished, a reticle in the middle that goes
 * green only when a bracket would actually fire - so what is learned here is true
 * there. And the geometry comes from the real code rather than being arranged by
 * hand: the sphere is [CapturePlan]'s, the sweep ends at the coverage the
 * controller ends at, the exposure slider runs through [ToneMapper]'s own curve.
 * A sketch that flattered the app would teach the wrong thing.
 *
 * Skippable from the first frame, and reachable afterwards from the question mark
 * in the corner - including during a capture, which is exactly when somebody
 * wonders what the ring is for.
 */
@Composable
fun IntroSketch(onDone: () -> Unit, onSkip: () -> Unit = onDone) {
    var step by remember { mutableStateOf(0) }
    val steps = INTRO_STEPS
    Surface(Modifier.fillMaxSize(), color = BACK, contentColor = INK) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp)
            // Swiping is how people move through four cards, whatever buttons are
            // on them. The threshold is a real distance rather than a velocity so
            // that a hesitant drag still counts, and the last step deliberately
            // does not swipe onward: starting a capture is a thing to mean.
            .pointerInput(steps.size) {
                var travelled = 0f
                val enough = 40.dp.toPx()
                detectHorizontalDragGestures(
                    onDragStart = { travelled = 0f },
                    onDragEnd = {
                        if (travelled <= -enough && step + 1 < steps.size) step++
                        if (travelled >= enough && step > 0) step--
                    },
                    onHorizontalDrag = { _, d -> travelled += d })
            }) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${step + 1} of ${steps.size}",
                    style = MaterialTheme.typography.labelLarge, color = Color(0xFF9A9A9A),
                    modifier = Modifier.fillMaxWidth().weight(1f))
                // Available from the first frame. Somebody who has done this
                // before should not have to sit through it to reach the camera.
                TextButton(onSkip) { Text("Skip") }
            }

            // The clock is read here and handed in. A Canvas body is a DrawScope,
            // not a composable, so an animation read inside it would not
            // recompose - the sketch would be drawn once and sit still.
            val t = introClock(steps[step].periodMs)
            Canvas(Modifier.fillMaxWidth().weight(1f)) {
                steps[step].draw(this, t)
            }

            // Room for two lines whatever this step's sentence is, so the
            // drawing does not jump between steps and the last word is never
            // sitting under the button.
            Box(Modifier.fillMaxWidth().height(76.dp), contentAlignment = Alignment.TopStart) {
                Text(steps[step].line, style = MaterialTheme.typography.titleMedium)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (step > 0) TextButton({ step-- }) { Text("Back") }
                Spacer(Modifier.fillMaxWidth().weight(1f))
                Button({ if (step + 1 < steps.size) step++ else onDone() }) {
                    Text(if (step + 1 < steps.size) "Next" else "Start")
                }
            }
        }
    }
}

/**
 * One looping phase, 0 to 1, at the length the step asks for.
 *
 * Linear on purpose: this is a clock, not a movement. Each sketch shapes its own
 * easing out of it, because a step is several movements with pauses between them
 * and one eased clock can only ever be a single movement. The period is per step
 * because the steps are not the same length of thing - turning a body through a
 * full circle takes seconds or it is not turning, while a bracket is a moment.
 */
@Composable
private fun introClock(periodMs: Int): Float {
    val transition = rememberInfiniteTransition(label = "intro")
    val t by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = periodMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart),
        label = "loop")
    return t
}

private class IntroStep(val line: String, val periodMs: Int, val draw: (DrawScope, Float) -> Unit)

// The capture screen's palette, name for name with SphereOverlay: amber is the
// thing wanting your hands, green is a thing that is finished, and the two
// whites are the difference between "not reached yet" and "reticle, doing
// nothing". Changing one here without changing it there would make the sketch a
// lie about the screen it is teaching.
private val BACK = Color(0xFF0B0B0B)
private val DIM = Color(0x33FFFFFF)
private val PENDING = Color(0x66FFFFFF)
private val IDLE = Color(0x88FFFFFF)
private val LIVE = Color(0xFFFFC400)
private val GOT = Color(0xFF39C36B)
private val INK = Color(0xFFECECEC)

private val INTRO_STEPS = listOf(
    IntroStep("Turn slowly all the way round, then look up and down. It is reading the light.",
        11000) { d, t -> d.sweep(t) },
    IntroStep("Then bring each amber ring into the circle and hold still. It fires by itself.",
        5600) { d, t -> d.aim(t) },
    IntroStep("Leave it to build the sphere. That part needs no hands.",
        6400) { d, t -> d.assemble(t) },
    IntroStep("Then look around it, and move the exposure through the whole range.",
        7200) { d, t -> d.viewer(t) })

// ------------------------------------------------------------------ the drawings
//
// Every sketch lays itself out from both dimensions of the canvas it is handed.
// That canvas is tall and narrow - about 360 by 630 dp on a phone - so a drawing
// scaled by one number, whichever number, either runs off the width or sits
// small in the middle of a lot of empty height. What should span the width spans
// it; what should span the height spans that.

/** Ease in and out. A hand moving between two aim points does this, roughly. */
private fun smooth(u: Float): Float {
    val x = u.coerceIn(0f, 1f)
    return x * x * (3 - 2 * x)
}

/** [smooth], stretched over one segment of the loop and flat outside it. */
private fun ramp(t: Float, from: Float, to: Float): Float = smooth((t - from) / (to - from))

private fun Color.fade(f: Float): Color = copy(alpha = alpha * f.coerceIn(0f, 1f))

private fun grey(v: Float): Color = Color(v, v, v, 1f)

/**
 * The seam at the end of a loop, for the two sketches that finish something.
 *
 * The sweep and the processing both end in a state - metered, built - which the
 * next turn of the loop has to be back out of. Cutting produces a snap that
 * reads as a glitch rather than as a repeat, so the last and first moments dip
 * through the background instead. The two sketches that genuinely cycle do not
 * call this: they are built so their last frame is their first.
 */
private fun DrawScope.loopSeam(t: Float) {
    val edge = 0.05f
    val a = when {
        t > 1f - edge -> (t - (1f - edge)) / edge
        t < edge -> 1f - t / edge
        else -> 0f
    }
    if (a > 0f) drawRect(BACK.copy(alpha = smooth(a)))
}

/**
 * The sphere of directions this app actually asks for.
 *
 * Built by [CapturePlan] from a pinhole about the shape of a phone's main
 * camera, quarter turned the way a phone is held - which comes out as the 34
 * directions in four rings and two poles that a capture on this device really
 * has. Drawing a hand-arranged ring instead would have been easier and would
 * have started lying the first time the overlap fraction moved.
 */
private val SKETCH_CAMERA = Intrinsics.pinhole(4000, 3000, 3022.0, 3022.0)

private val SPHERE: List<CaptureTarget> by lazy {
    CapturePlan.forCamera(SKETCH_CAMERA, CapturePlanConfig(), 90.0).targets
}

/**
 * How wide a bite of the sphere one frame meters, as a cone half-angle.
 *
 * The real test is whether the direction falls inside a rectangular frame; a
 * circle the size of the frame's mean half-angle is the honest round
 * approximation of that, and it is what decides which dots the sweep lights up.
 */
private val METER_HALF_ANGLE_DEG =
    (SKETCH_CAMERA.horizontalFovDeg() + SKETCH_CAMERA.verticalFovDeg()) / 4

/**
 * Which ring of the plan a direction belongs to.
 *
 * Rounded, not compared: a target's pitch comes back out of a rotation, so two
 * frames of the same ring can differ in the last bit of a double and a straight
 * grouping would invent a ring per frame.
 */
private fun ring(target: CaptureTarget): Long = Math.round(target.pitchDeg * 1000)

/**
 * The sphere seen from a little above, orthographically.
 *
 * Orthographic because what has to be legible is which directions are covered,
 * and a perspective ball trades that for a bulge. The elevation is what makes it
 * read as a ball at all: seen edge on, the rings collapse into lines.
 */
private class Globe(val cx: Float, val cy: Float, val r: Float) {
    private val cos = Math.cos(Math.toRadians(ELEVATION)).toFloat()
    private val sin = Math.sin(Math.toRadians(ELEVATION)).toFloat()

    fun at(d: Vec3): Offset = at(d, 1f)

    /** A point [scale] of the way out along [d], projected. */
    fun at(d: Vec3, scale: Float): Offset = Offset(
        cx + r * scale * d.x.toFloat(),
        cy - r * scale * (d.y.toFloat() * cos - d.z.toFloat() * sin))

    /** Towards the viewer: positive on the near side of the ball. */
    fun depth(d: Vec3): Float = d.y.toFloat() * sin + d.z.toFloat() * cos

    companion object { const val ELEVATION = 24.0 }
}

/** The three states a direction can be in, and the marks the capture screen uses. */
private enum class Mark { PENDING, CURRENT, DONE }

/**
 * One direction, drawn the way [SphereOverlay] draws it.
 *
 * Same three marks and the same colours, so "amber is the one I am pointing at,
 * green is one that is done" is learned here and still true there. [alpha] is
 * for depth on the far side of the globe, nothing else.
 */
private fun DrawScope.mark(at: Offset, r: Float, state: Mark, alpha: Float = 1f) {
    val w = Math.max(2.5f, r * 0.18f)
    when (state) {
        Mark.PENDING -> drawCircle(PENDING.fade(alpha), r, at, style = Stroke(width = w))
        Mark.DONE -> {
            drawCircle(GOT.fade(0.35f * alpha), r, at)
            drawCircle(GOT.fade(alpha), r, at, style = Stroke(width = w))
        }
        Mark.CURRENT -> {
            drawCircle(LIVE.fade(0.18f * alpha), r * 0.7f, at)
            drawCircle(LIVE.fade(alpha), r, at, style = Stroke(width = w * 1.3f))
        }
    }
}

/**
 * The phone, drawn small, with the camera end pointing where it is aimed.
 *
 * At the centre of the globe because that is where the person stands: the sphere
 * is around them, not in front of them.
 */
private fun DrawScope.phone(centre: Offset, length: Float, towards: Offset) {
    val dx = towards.x - centre.x
    val dy = towards.y - centre.y
    val a = if (Math.hypot(dx.toDouble(), dy.toDouble()) < 1.0) -90f
            else Math.toDegrees(Math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
    val w = length * 0.52f
    rotate(a + 90f, centre) {
        drawRoundRect(INK, Offset(centre.x - w / 2, centre.y - length / 2),
            Size(w, length), CornerRadius(w * 0.22f),
            style = Stroke(width = Math.max(3f, w * 0.12f)))
        // The lens end. Which way a phone is pointing is the one thing this glyph
        // has to say, and a rounded rectangle on its own does not say it.
        drawCircle(INK, w * 0.16f, Offset(centre.x, centre.y - length * 0.30f))
    }
}

/** A progress bar in the app's own shape: dim track, green fill, tick at the mark. */
private fun DrawScope.bar(left: Float, right: Float, y: Float, filled: Float, tick: Float = -1f) {
    val h = Math.max(6f, size.width * 0.009f)
    drawRoundRect(DIM, Offset(left, y - h / 2), Size(right - left, h), CornerRadius(h / 2))
    if (filled > 0f)
        drawRoundRect(GOT, Offset(left, y - h / 2), Size((right - left) * filled, h),
            CornerRadius(h / 2))
    if (tick >= 0f) {
        val x = left + (right - left) * tick
        drawLine(INK, Offset(x, y - h * 2.2f), Offset(x, y + h * 2.2f), strokeWidth = 3f)
    }
}

/**
 * The phone's own screen, for the two steps that are teaching a screen.
 *
 * Drawn as a frame rather than as the whole canvas so that what drifts inside it
 * reads as the view moving, and so that the reticle is visibly *in the middle of
 * the screen* - which is the entire instruction in step two. It takes the full
 * width and only the height it needs: a frame shaped like a phone would leave
 * the sketch a thin column of dots in a tall empty box.
 */
private class Screen(val left: Float, val top: Float, val w: Float, val h: Float) {
    val cx: Float get() = left + w / 2
    val cy: Float get() = top + h / 2
    val right: Float get() = left + w
    val bottom: Float get() = top + h
}

private fun DrawScope.screen(heightFraction: Float): Screen {
    val w = size.width * 0.98f
    val h = size.height * heightFraction
    return Screen((size.width - w) / 2, (size.height - h) / 2, w, h)
}

private fun DrawScope.screenFrame(s: Screen) {
    drawRoundRect(DIM, Offset(s.left, s.top), Size(s.w, s.h),
        CornerRadius(s.w * 0.06f), style = Stroke(width = 3f))
}

// ---------------------------------------------------------------- 1. the sweep

/**
 * Where the phone is looking during the sweep, as a direction.
 *
 * A turn at eye level, then up and round again, then down and round again: three
 * passes, because that is what covering a sphere with a fifty degree frame
 * actually takes, and why the app settles for 85 per cent of it rather than all
 * of it. Every leg is eased, and the legs are long: the point of the sentence is
 * *slowly*, and a sweep that whips round the room teaches the opposite.
 */
private fun sweepLook(t: Float): Vec3 {
    val yaw = 400.0 * ramp(t, 0.02f, 0.30f) +
              380.0 * ramp(t, 0.38f, 0.60f) +
              380.0 * ramp(t, 0.70f, 1f)
    val pitch = 46.0 * ramp(t, 0.30f, 0.38f) - 92.0 * ramp(t, 0.60f, 0.70f)
    return CaptureTarget.directionFor(yaw, pitch)
}

/** The coverage the sweep really ends at, read from the controller's own setting. */
private val SWEEP_COVERAGE = CaptureController.Config().scanCoverageComplete

/**
 * The instant the sweep is over, found rather than chosen.
 *
 * [CaptureController] closes the sweep once that fraction of the directions has
 * been metered, so this walks the same path with the same cone and stops where
 * the count crosses. A hand-picked moment would have gone stale the first time
 * the threshold or the plan moved, and what it shows is worth showing: the sweep
 * ends in the middle of a turn, with the floor still half unlit, because that is
 * what eighty-five per cent looks like.
 */
private val SWEEP_END: Float by lazy {
    val need = Math.ceil(SWEEP_COVERAGE * SPHERE.size).toInt()
    val seen = BooleanArray(SPHERE.size)
    var n = 0
    var s = 0
    while (s <= SWEEP_SAMPLES) {
        val at = s / SWEEP_SAMPLES.toFloat()
        n += meter(sweepLook(at), seen)
        if (n >= need) return@lazy at
        s++
    }
    1f
}

private const val SWEEP_SAMPLES = 240

/** Marks everything the frame at [look] can see, and returns how many were new. */
private fun meter(look: Vec3, seen: BooleanArray): Int {
    val cos = Math.cos(Math.toRadians(METER_HALF_ANGLE_DEG))
    var fresh = 0
    for (i in SPHERE.indices) {
        if (seen[i]) continue
        if (look.dot(SPHERE[i].direction) > cos) { seen[i] = true; fresh++ }
    }
    return fresh
}

private fun DrawScope.sweep(t: Float) {
    val until = Math.min(t, SWEEP_END)
    val seen = BooleanArray(SPHERE.size)
    var covered = 0
    val steps = Math.max(1, Math.round(SWEEP_SAMPLES * until))
    var s = 0
    while (s <= steps) {
        covered += meter(sweepLook(until * s / steps), seen)
        s++
    }

    val r = Math.min(size.width * 0.47f, size.height * 0.33f)
    val globe = Globe(size.width / 2, size.height * 0.40f, r)
    val look = sweepLook(until)
    // Once the threshold is crossed nothing is live any more, so the meter's
    // footprint goes out. It is the difference between an animation that stopped
    // and an app that stopped it.
    val live = 1f - ramp(t, SWEEP_END + 0.02f, SWEEP_END + 0.10f)

    // The ball itself, faintly: a silhouette and the horizon. Without them the
    // dots are a scatter, and the far ones look like near ones dimmed for no
    // reason.
    val flat = Math.sin(Math.toRadians(Globe.ELEVATION)).toFloat()
    drawCircle(DIM.fade(0.45f), r, Offset(globe.cx, globe.cy), style = Stroke(width = 2f))
    drawOval(DIM.fade(0.45f), Offset(globe.cx - r, globe.cy - r * flat),
        Size(r * 2, r * 2 * flat), style = Stroke(width = 2f))

    // The meter's footprint, drawn between the far dots and the near ones so it
    // sits *on* the ball. It is a spherical cap, and a cap seen flat is an
    // ellipse squashed along the direction it points - so that is what this is,
    // rather than a circle that would only be right when the phone happens to
    // point at the viewer.
    val cap = Math.toRadians(METER_HALF_ANGLE_DEG)
    val depth = globe.depth(look)
    fun drawCap(near: Boolean) {
        if ((depth > 0) != near || live <= 0f) return
        val at = globe.at(look, Math.cos(cap).toFloat())
        val across = r * Math.sin(cap).toFloat()
        // Pointing across the line of sight, a cap really does foreshorten to a
        // line - and drawn that way it reads as a stray scratch rather than as a
        // patch on a ball, so it keeps a fifth of its width.
        val along = Math.max(across * Math.abs(depth), across * 0.2f)
        val reach = Math.max(1f, Math.hypot((at.x - globe.cx).toDouble(),
            (at.y - globe.cy).toDouble()).toFloat())
        val ux = (at.x - globe.cx) / reach
        val uy = (at.y - globe.cy) / reach
        val a = Math.toDegrees(Math.atan2(uy.toDouble(), ux.toDouble())).toFloat()
        rotate(a, at) {
            drawOval(LIVE.fade(live * if (near) 0.07f else 0.03f),
                Offset(at.x - along, at.y - across), Size(along * 2, across * 2))
            drawOval(LIVE.fade(live * if (near) 0.75f else 0.2f),
                Offset(at.x - along, at.y - across), Size(along * 2, across * 2),
                style = Stroke(width = 2.5f))
        }
        // Two lines from the phone to the edges of the footprint: what the camera
        // can see from where the person is standing, which is why those dots and
        // not the others light up.
        if (near) for (side in intArrayOf(-1, 1))
            drawLine(LIVE.fade(live * 0.22f), Offset(globe.cx, globe.cy),
                Offset(at.x - uy * across * side, at.y + ux * across * side),
                strokeWidth = 2f)
    }

    val dotR = r * 0.055f
    for (pass in 0..1) {
        // Far side first and dimmer, so the ball has a back.
        for (i in SPHERE.indices) {
            val d = SPHERE[i].direction
            val near = globe.depth(d) > 0
            if (near != (pass == 1)) continue
            mark(globe.at(d), dotR, if (seen[i]) Mark.DONE else Mark.PENDING,
                if (near) 1f else 0.22f)
        }
        if (pass == 0) drawCap(near = false)
    }
    drawCap(near = true)
    phone(Offset(globe.cx, globe.cy), r * 0.28f, globe.at(look))

    // Coverage, with the mark it stops at. The bar is the answer to "how much
    // longer": it fills, it reaches the tick, and the sweep is over without
    // anybody having decided it is.
    bar(0f, size.width, size.height * 0.87f, covered / SPHERE.size.toFloat(),
        tick = SWEEP_COVERAGE.toFloat())
    loopSeam(t)
}

// -------------------------------------------------------------- 2. the capture

/**
 * Aiming, holding still, and the bracket going off by itself.
 *
 * The screen shows the ring of directions being worked along, drifting because
 * the phone is turning - which is the honest way round: on the real screen the
 * reticle never moves and the sphere does. An earlier sketch drew a line from the
 * target to the reticle, and a line between two things reads as a tether holding
 * them apart rather than as an instruction to bring them together.
 *
 * Two directions per loop, so the loop is a repeat rather than a restart: after
 * two whole steps of the ring the picture is the picture it started as.
 *
 * The sequence is the controller's own rule and not a timeline. The reticle is
 * white while the target is outside it, amber once it is inside, and green only
 * when the phone has also come to rest - because green means a bracket will fire
 * now. Then the frames land one at a time into the ring while nothing moves at
 * all, which is the whole of what "hold still" means and the one thing people
 * get wrong.
 */
private fun DrawScope.aim(t: Float) {
    val s = screen(0.80f)
    screenFrame(s)

    val turns = Math.floor(t * 2.0).toInt()                       // two targets per loop
    val half = (t * 2 - turns).coerceIn(0f, 1f)
    // The turn onto the target: away, decelerate a little past it, and one small
    // correction back. That is what a hand does, and it is a single smooth move -
    // a shake would be more literal and reads as a fault in the drawing.
    val x = half / 0.44f
    val settled = 1.09f * smooth(x / 0.72f) - 0.09f * smooth((x - 0.72f) / 0.28f)

    // Five rings' worth of sphere, spread wider than one frame really holds. At
    // the plan's true spacing a frame contains one dot and a lot of nothing,
    // which teaches nothing about working through a sphere. What is kept is the
    // part that means something: rings evenly spaced in pitch, the polar ones
    // holding fewer and wider-spaced frames, all of them bowing away from the
    // middle towards the edges of the frame, and every one of them moving by the
    // same angle when the phone turns.
    //
    // The spacings are whole multiples of one turn of the phone on purpose. The
    // loop drifts by exactly two of them, so at the end of it every ring is one
    // or two frames further along and the picture is the picture it started as -
    // which is the difference between a loop that repeats and a loop that jumps.
    val fx = (s.w / 2) / Math.tan(Math.toRadians(46.0)).toFloat()
    val rowGap = s.h * 0.21f
    val step = 22f
    val reticle = s.w * 0.085f
    val dotR = s.w * 0.045f
    // How far the phone has turned since the loop began, in degrees. Continuous
    // across the two targets: resetting it at the halfway point would drag every
    // ring whose frames are not spaced like this one back to where it started.
    val drift = (turns + settled) * step

    fun ringStep(rowsUp: Int): Float = if (Math.abs(rowsUp) >= 2) step * 2 else step

    // Past seventy degrees off axis a direction is behind the shoulder rather
    // than on the screen, and the tangent has begun to fold it back inside the
    // frame - the same fold SphereOverlay refuses to project through. The bow is
    // taken at a fraction of its true strength for the same reason: at full
    // strength a polar frame swings in from the corner of the screen, which reads
    // as dots appearing out of nowhere.
    fun place(dAz: Float, rowsUp: Int): Offset? {
        if (Math.abs(dAz) > 70f) return null
        val az = Math.toRadians(dAz.toDouble())
        val bow = 1 + 0.16f * (1 / Math.cos(az).toFloat() - 1)
        return Offset(s.cx + fx * Math.tan(az).toFloat(), s.cy - rowGap * rowsUp * bow)
    }

    clipRect(s.left, s.top, s.right, s.bottom) {
        // The rings above and below. Below is finished and above is not: the plan
        // works upwards from the floor, so which side is green says which way the
        // work goes and how much of it is left.
        for (row in intArrayOf(-2, -1, 1, 2)) {
            val spacing = ringStep(row)
            val nearest = Math.round(drift / spacing)
            for (j in nearest - 2..nearest + 2) {
                val at = place(j * spacing - drift, row) ?: continue
                mark(at, dotR * 0.72f, if (row < 0) Mark.DONE else Mark.PENDING, 0.75f)
            }
        }

        // The ring being worked. One dot is the target and arrives in the middle;
        // the ones it has passed are green, the ones ahead of it are not.
        val current = turns + 1
        for (j in current - 3..current + 2) {
            val at = place(j * step - drift, 0) ?: continue
            val fired = j == current && half > 0.88f
            mark(at, if (j == current) dotR else dotR * 0.8f,
                when {
                    j < current || fired -> Mark.DONE
                    j == current -> Mark.CURRENT
                    else -> Mark.PENDING
                })
        }
    }

    // The reticle, and its three states, which are the controller's three states.
    val target = place((turns + 1) * step - drift, 0) ?: Offset(s.cx, s.cy)
    val aligned = Math.hypot((target.x - s.cx).toDouble(), (target.y - s.cy).toDouble()) < reticle
    val ready = aligned && x >= 1f
    val centre = Offset(s.cx, s.cy)
    drawCircle(if (ready) GOT else if (aligned) LIVE else IDLE, reticle, centre,
        style = Stroke(width = if (ready) 6f else 3f))

    // The bracket, landing a frame at a time, and only once the phone has been
    // still for a moment. Stepped rather than swept: these are frames arriving,
    // and a smooth arc would be a guess at how long they take.
    if (ready && half > 0.52f) {
        val rungs = 5
        val done = Math.min(rungs, ((half - 0.52f) / 0.36f * rungs).toInt() + 1) / rungs.toFloat()
        val ringR = reticle * 1.45f
        drawCircle(GOT.fade(0.20f), ringR, centre, style = Stroke(width = 5f))
        drawArc(GOT, -90f, 360f * done, false,
            topLeft = Offset(centre.x - ringR, centre.y - ringR),
            size = Size(ringR * 2, ringR * 2), style = Stroke(width = 5f))
    }
}

// ----------------------------------------------------------- 3. the processing

/**
 * The equirectangular cell each direction is responsible for.
 *
 * One rectangle per direction, sized from its own ring: the polar rings hold
 * fewer frames so their cells are wider, and a pole is a strip across the whole
 * width. Drawing the panorama as an even grid would have hidden the one thing
 * the shape of an equirectangular map tells you.
 */
private val CELLS: List<FloatArray> by lazy {
    val pitches = SPHERE.map { ring(it) / 1000.0 }.distinct().sorted()
    val perRing = SPHERE.groupingBy { ring(it) }.eachCount()
    SPHERE.map { target ->
        val at = pitches.indexOf(ring(target) / 1000.0)
        val lo = if (at == 0) -90.0 else (pitches[at - 1] + target.pitchDeg) / 2
        val hi = if (at == pitches.size - 1) 90.0 else (pitches[at + 1] + target.pitchDeg) / 2
        val halfAz = 180.0 / (perRing[ring(target)] ?: 1)
        floatArrayOf(
            ((target.yawDeg - halfAz + 180) / 360).toFloat(),
            ((90 - hi) / 180).toFloat(),
            ((target.yawDeg + halfAz + 180) / 360).toFloat(),
            ((90 - lo) / 180).toFloat())
    }
}

/**
 * Roughly how bright the scene is in a given direction, in equirectangular
 * coordinates: [v] runs from the zenith at 0 to the floor at 1.
 *
 * The coarse version of the same picture the finished panorama is drawn as, so
 * the tiles land the colour their part of the scene turns out to be and the last
 * moment is a sharpening rather than a substitution.
 */
private fun sceneShade(u: Float, v: Float): Float {
    val sun = Math.hypot((u - 0.62) * 2.4, v - 0.30).toFloat()
    return when {
        sun < 0.07f -> 1f
        v < 0.46f -> 0.30f + 0.34f * (v / 0.46f)
        v < 0.53f -> 0.26f
        else -> 0.23f - 0.14f * ((v - 0.53f) / 0.47f)
    }
}

/** The finished panorama, drawn as a picture rather than as the cells it came from. */
private fun DrawScope.stitched(left: Float, top: Float, w: Float, h: Float, alpha: Float) {
    if (alpha <= 0f) return
    val skyTo = top + h * 0.46f
    val groundFrom = top + h * 0.53f
    drawRect(Brush.verticalGradient(listOf(grey(0.30f), grey(0.64f)), skyTo, top),
        Offset(left, top), Size(w, skyTo - top), alpha = alpha)
    drawRect(grey(0.26f), Offset(left, skyTo), Size(w, groundFrom - skyTo), alpha = alpha)
    drawRect(Brush.verticalGradient(listOf(grey(0.23f), grey(0.09f)), groundFrom, top + h),
        Offset(left, groundFrom), Size(w, top + h - groundFrom), alpha = alpha)
    // A skyline, so the join between sky and ground is a place rather than a
    // seam, and one sun where the bright cell was.
    val roofs = floatArrayOf(0.04f, 0.13f, 0.21f, 0.34f, 0.47f, 0.71f, 0.80f, 0.90f)
    val highs = floatArrayOf(0.06f, 0.10f, 0.04f, 0.08f, 0.05f, 0.09f, 0.05f, 0.07f)
    for (i in roofs.indices)
        drawRect(grey(0.19f), Offset(left + w * roofs[i], skyTo - h * highs[i]),
            Size(w * 0.075f, h * highs[i] + 1f), alpha = alpha)
    drawCircle(Brush.radialGradient(listOf(grey(1f), grey(1f).copy(alpha = 0f)),
            Offset(left + w * 0.62f, top + h * 0.30f), h * 0.16f),
        h * 0.16f, Offset(left + w * 0.62f, top + h * 0.30f), alpha = alpha)
}

/**
 * Stitching, drawn as what it is: every direction that was shot moving into its
 * place in one flat picture, which then sharpens into the picture.
 *
 * Minutes of this go by with the phone in somebody's hand, so the drawing has to
 * say "this is work, and none of it is yours". Nothing here is amber. In this
 * palette amber is the colour of a thing that wants your hands, and the truthful
 * thing to show about processing is that there is no such thing on the screen.
 */
private fun DrawScope.assemble(t: Float) {
    val r = Math.min(size.width * 0.28f, size.height * 0.18f)
    val globe = Globe(size.width / 2, size.height * 0.19f, r)
    val w = size.width
    val left = 0f
    val h = w / 2
    val top = size.height * 0.46f

    val n = SPHERE.size
    val departed = ramp(t, 0.04f, 0.56f)
    drawCircle(DIM.fade(0.4f * (1f - departed)), r, Offset(globe.cx, globe.cy),
        style = Stroke(width = 2f))
    drawRect(DIM.fade(0.6f), Offset(left, top), Size(w, h), style = Stroke(width = 2f))

    var landed = 0
    val dotR = Math.max(r * 0.075f, 7f)
    for (i in SPHERE.indices) {
        // In the plan's own order, which starts at the floor and works up, so the
        // picture fills the way the capture was shot.
        val start = 0.04f + 0.50f * (i / (n - 1f))
        val u = ramp(t, start, start + 0.22f)
        val cell = CELLS[i]
        val to = Offset(left + w * (cell[0] + cell[2]) / 2, top + h * (cell[1] + cell[3]) / 2)
        if (u >= 1f) {
            landed++
            val tone = grey(sceneShade((cell[0] + cell[2]) / 2, (cell[1] + cell[3]) / 2))
            // Cells are drawn a pixel over their bounds. Exact edges leave hairline
            // gaps between neighbours - a stitched panorama with a grid of black
            // lines through it is the one thing this drawing must not look like -
            // and cells that straddle the back of the sphere are drawn as the two
            // pieces they are, because that seam is a place too.
            for (x0 in floatArrayOf(cell[0], cell[0] - 1f, cell[0] + 1f)) {
                val a = Math.max(0f, x0)
                val b = Math.min(1f, x0 + (cell[2] - cell[0]))
                if (b > a) drawRect(tone,
                    Offset(left + w * a - 1f, top + h * cell[1] - 1f),
                    Size(w * (b - a) + 2f, h * (cell[3] - cell[1]) + 2f))
            }
        } else if (u > 0f) {
            val from = globe.at(SPHERE[i].direction)
            val e = smooth(u)
            drawCircle(GOT.fade(0.9f), dotR * (1f - 0.3f * e),
                Offset(from.x + (to.x - from.x) * e, from.y + (to.y - from.y) * e))
        } else {
            drawCircle(GOT.fade(if (globe.depth(SPHERE[i].direction) > 0) 0.85f else 0.3f),
                dotR, globe.at(SPHERE[i].direction))
        }
    }

    val blend = ramp(t, 0.78f, 0.88f)
    clipRect(left, top, left + w, top + h) { stitched(left, top, w, h, blend) }
    bar(left, left + w, top + h + size.height * 0.12f,
        Math.min(1f, 0.9f * landed / n + 0.1f * blend))
    loopSeam(t)
}

// ----------------------------------------------------------------- 4. the result

/**
 * The scene in the viewer, kept as radiance rather than as greys.
 *
 * Numbers, not colours, because the point of step four is that the file holds
 * radiance and the slider is a real exposure over it. Every patch below goes
 * through [ToneMapper.filmic] at the exposure the slider is at - the same curve
 * the app's own viewer uses - so the window flattening to nothing at one end and
 * the table appearing out of the dark at the other are the file behaving, not a
 * drawing pretending. Six stops is where the interesting part of a room with a
 * window in it lives; the real slider will go twice as far in both directions.
 */
private const val EV_LOW = -2f
private const val EV_HIGH = 4f

private fun shade(radiance: Double, stops: Float): Color {
    val v = Math.pow(ToneMapper.filmic(radiance * Math.pow(2.0, stops.toDouble())), 1 / 2.2)
    return grey(v.toFloat())
}

private fun DrawScope.viewer(t: Float) {
    val s = screen(0.94f)
    screenFrame(s)
    // Both ends of the sweep are where the lesson is, so it dwells at both: a
    // cosine turns round slowly and never snaps back to the start.
    val stops = EV_LOW + (EV_HIGH - EV_LOW) * (0.5f - 0.5f * Math.cos(2 * Math.PI * t).toFloat())
    val pan = Math.sin(2 * Math.PI * t).toFloat()

    val inset = 8f
    val viewTop = s.top + inset
    val viewH = s.h * 0.80f
    val viewLeft = s.left + inset
    val viewW = s.w - inset * 2

    fun patch(x0: Float, y0: Float, x1: Float, y1: Float, radiance: Double, drift: Float = 0f) {
        val dx = drift * pan * viewW * 0.04f
        drawRect(shade(radiance, stops),
            Offset(viewLeft + viewW * x0 + dx, viewTop + viewH * y0),
            Size(viewW * (x1 - x0), viewH * (y1 - y0)))
    }

    fun disc(x: Float, y: Float, r: Float, radiance: Double) {
        drawCircle(shade(radiance, stops), viewW * r,
            Offset(viewLeft + viewW * (x + pan * 0.04f), viewTop + viewH * y))
    }

    clipRect(viewLeft, viewTop, viewLeft + viewW, viewTop + viewH) {
        patch(0f, 0f, 1f, 1f, 0.10)                          // wall
        patch(0f, 0.60f, 1f, 1f, 0.05)                       // floor

        // The window, which is most of the picture. All of what is in it is gone
        // at the top of the slider and all of it is there at the bottom, and that
        // is the entire reason this app exists.
        patch(0.34f, 0.08f, 0.98f, 0.52f, 0.12, drift = 1f)          // frame
        patch(0.36f, 0.10f, 0.96f, 0.34f, 4.0, drift = 1f)           // sky, high
        patch(0.36f, 0.34f, 0.96f, 0.43f, 2.2, drift = 1f)           // sky, low
        patch(0.36f, 0.43f, 0.96f, 0.50f, 0.45, drift = 1f)          // hills
        disc(0.48f, 0.20f, 0.11f, 6.0)                               // its glow
        disc(0.48f, 0.20f, 0.048f, 250.0)                            // the sun
        patch(0.64f, 0.10f, 0.665f, 0.50f, 0.12, drift = 1f)         // the bars
        patch(0.36f, 0.29f, 0.96f, 0.315f, 0.12, drift = 1f)

        // And the other end of the range: a table standing in an unlit part of
        // the room, black until the exposure comes up.
        patch(0.04f, 0.62f, 0.40f, 0.665f, 0.014, drift = 1f)        // top
        patch(0.07f, 0.665f, 0.10f, 0.88f, 0.014, drift = 1f)        // legs
        patch(0.34f, 0.665f, 0.37f, 0.88f, 0.014, drift = 1f)
    }

    // The slider, where the viewer keeps it: under the picture, spanning the
    // range, and moving because somebody is moving it.
    val trackY = s.top + s.h * 0.90f
    val x0 = s.left + s.w * 0.10f
    val x1 = s.right - s.w * 0.10f
    val at = x0 + (x1 - x0) * (stops - EV_LOW) / (EV_HIGH - EV_LOW)
    drawLine(DIM, Offset(x0, trackY), Offset(x1, trackY), strokeWidth = 6f)
    drawLine(INK.fade(0.55f), Offset(x0, trackY), Offset(at, trackY), strokeWidth = 6f)
    drawCircle(INK, s.w * 0.028f, Offset(at, trackY))
}

/**
 * Whether the sketch has been seen, kept as a file next to the captures.
 *
 * A file rather than a preference because everything else this app remembers is
 * a file, and because clearing it is then something a person can actually do.
 */
object IntroSeen {
    private const val NAME = "intro-seen"

    @JvmStatic
    fun has(context: Context): Boolean = File(context.filesDir, NAME).isFile

    @JvmStatic
    fun record(context: Context) {
        try { File(context.filesDir, NAME).writeText("seen") } catch (e: Exception) { }
    }
}
