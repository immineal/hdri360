package com.immineal.hdri360.ui

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import com.immineal.hdri360.core.image.ImageF
import com.immineal.hdri360.core.io.Half
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * A 360 viewer over the actual radiance.
 *
 * The exposure slider moves a multiplier applied before tone mapping, on
 * half-float texels holding linear values, which is the only way the slider
 * means anything: sliding the exposure on an 8-bit preview shows what the tone
 * mapper already threw away, not what the capture contains.
 */
class PanoramaView(context: Context) : GLSurfaceView(context) {

    private val renderer = PanoramaRenderer()
    private var lastX = 0f
    private var lastY = 0f
    private var pointer = -1

    /**
     * Pinch to zoom, on the field of view rather than on a scale factor, so what
     * changes is how much of the sphere is on screen.
     */
    private val pinch = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                renderer.halfFov = Math.max(MIN_FOV, Math.min(MAX_FOV,
                    renderer.halfFov / d.scaleFactor))
                requestRender()
                return true
            }
        })

    init {
        setEGLContextClientVersion(3)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun show(panorama: ImageF) {
        renderer.pending = panorama
        requestRender()
    }

    /** Stops, in EV. 0 is as captured. */
    var exposureStops: Double
        get() = renderer.exposureStops
        set(v) { renderer.exposureStops = v; requestRender() }

    /**
     * Drag to look, pinch to zoom.
     *
     * The drag follows one named pointer rather than "whichever finger event.x
     * happens to report". Putting a second finger down and lifting the first
     * swaps which pointer that is, and the jump between the two positions was
     * being read as an enormous drag - which is how a pinch threw the view into
     * the part of the sphere that was never shot, where there is nothing to see.
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        pinch.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                pointer = event.getPointerId(event.actionIndex)
                lastX = event.getX(event.actionIndex)
                lastY = event.getY(event.actionIndex)
            }
            MotionEvent.ACTION_MOVE -> {
                if (pinch.isInProgress) {
                    // Re-anchor so the pinch does not also read as a drag.
                    val at = event.findPointerIndex(pointer)
                    if (at >= 0) { lastX = event.getX(at); lastY = event.getY(at) }
                    return true
                }
                val at = event.findPointerIndex(pointer)
                if (at < 0) return true
                val dx = event.getX(at) - lastX
                val dy = event.getY(at) - lastY
                lastX = event.getX(at)
                lastY = event.getY(at)
                // Slower when zoomed in, so the same finger travel is the same
                // distance across the picture however close you are looking.
                val speed = DRAG_SPEED * (renderer.halfFov / DEFAULT_FOV)
                // Plus, not minus. Increasing yaw turns the view toward +X, which
                // is the viewer's left, and dragging right has to bring what was
                // on the left into view - the content follows the finger, as it
                // does with a photograph on a table.
                //
                // It was minus while the ray was mirrored, and the two wrongs
                // cancelled: the drag felt right, so nothing pointed at the
                // mirror. Fixing one without the other would have traded a
                // mirrored sphere for a viewer that fights the hand.
                renderer.yaw += dx * speed
                // Both axes, whether or not the phone is steering. It used to be
                // yaw only while following, on the theory that a dragged pitch
                // would fight the hand - it does not. What it does is shift the
                // horizon, which is exactly what somebody standing in the wrong
                // spot wants, and "Reset view" is right there to undo it.
                renderer.pitch = Math.max(-1.45, Math.min(1.45, renderer.pitch - dy * speed))
                requestRender()
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // Hand the drag to a finger that is still down, at its own
                // position, so nothing moves at the moment of handover.
                if (event.getPointerId(event.actionIndex) == pointer) {
                    val next = if (event.actionIndex == 0) 1 else 0
                    if (next < event.pointerCount) {
                        pointer = event.getPointerId(next)
                        lastX = event.getX(next)
                        lastY = event.getY(next)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pointer = -1
        }
        return true
    }

    /**
     * Look around by turning the phone, or by dragging.
     *
     * The sphere anchors to the room when this is on, because the heading the
     * capture recorded and the heading the sensor reads are the same magnetic
     * north - stand where it was shot, turn, and the room is where the room is.
     * Zoom and drag keep working either way; the drag becomes a heading
     * correction, which is what indoor compass error needs.
     */
    var followDevice: Boolean = false
        set(v) {
            field = v
            if (!v) renderer.devicePose = null
            requestRender()
        }

    /** The screen's pose, row-major, from OrientationMath.screenToWorld. */
    fun onDevicePose(rowMajor: DoubleArray) {
        if (!followDevice) return
        val f = FloatArray(9)
        for (i in 0 until 9) f[i] = rowMajor[i].toFloat()
        renderer.devicePose = f
        requestRender()
    }

    /** Back to the whole view, for when a pinch has left someone lost. */
    fun resetView() {
        renderer.halfFov = DEFAULT_FOV
        renderer.yaw = 0.0
        renderer.pitch = 0.0
        requestRender()
    }

    private companion object {
        const val DRAG_SPEED = 0.0035
        const val DEFAULT_FOV = 0.7          // about 70 degrees across
        const val MIN_FOV = 0.06             // about 7 degrees: a close look
        const val MAX_FOV = 1.55             // about 155 degrees: most of a hemisphere
    }
}

private class PanoramaRenderer : GLSurfaceView.Renderer {

    @Volatile var pending: ImageF? = null
    @Volatile var exposureStops = 0.0
    @Volatile var halfFov = 0.7
    @Volatile var yaw = 0.0
    @Volatile var pitch = 0.0

    /**
     * The screen's own pose, when the sphere is being looked at by turning the
     * phone rather than by dragging.
     *
     * Null means the drag is the only thing steering. When it is set, the drag's
     * yaw becomes a heading *offset* on top of it - useful because the panorama's
     * north is only as good as the compass was during the capture, and indoors
     * that can be tens of degrees out. Pitch then comes from the phone alone:
     * offering two sources of pitch at once is a view that fights the hand.
     */
    @Volatile var devicePose: FloatArray? = null

    private var program = 0
    private var texture = 0
    private var aspect = 1f
    private var uView = 0
    private var uExposure = 0
    private var uAspect = 0
    private var uFov = 0
    private var quad: FloatBuffer? = null

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = link(VERTEX, FRAGMENT)
        uView = GLES30.glGetUniformLocation(program, "uView")
        uExposure = GLES30.glGetUniformLocation(program, "uExposure")
        uAspect = GLES30.glGetUniformLocation(program, "uAspect")
        uFov = GLES30.glGetUniformLocation(program, "uFov")
        quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
            position(0)
        }
        texture = 0
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        aspect = if (height > 0) width.toFloat() / height else 1f
    }

    override fun onDrawFrame(gl: GL10?) {
        pending?.let { upload(it); pending = null }
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        if (texture == 0) return

        GLES30.glUseProgram(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uPano"), 0)
        GLES30.glUniform1f(uExposure, Math.pow(2.0, exposureStops).toFloat())
        GLES30.glUniform1f(uAspect, aspect)
        GLES30.glUniform1f(uFov, halfFov.toFloat())
        GLES30.glUniformMatrix3fv(uView, 1, false, viewMatrix(), 0)

        val pos = GLES30.glGetAttribLocation(program, "aPos")
        GLES30.glEnableVertexAttribArray(pos)
        GLES30.glVertexAttribPointer(pos, 2, GLES30.GL_FLOAT, false, 0, quad)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(pos)
    }

    /** Column-major, as OpenGL wants it. Yaw about up, then pitch about right. */
    private fun viewMatrix(): FloatArray {
        val cy = Math.cos(yaw).toFloat(); val sy = Math.sin(yaw).toFloat()
        val cpi = Math.cos(pitch).toFloat(); val spi = Math.sin(pitch).toFloat()
        val pose = devicePose
        if (pose != null && pose.size == 9) {
            // Ry(yaw) * screenToWorld * Rx(pitch).
            //
            // The two drags do different jobs and are applied on different sides
            // on purpose. Yaw goes on the *world* side, so dragging left and
            // right turns the room around the viewer - it is a correction to the
            // sphere's own heading, which indoors is frequently what needs
            // correcting. Pitch goes on the *camera* side, so dragging up and
            // down looks further up or down from wherever the phone happens to
            // point: it shifts the horizon and it stays "up relative to the
            // phone" as the phone turns. On the world side it would roll the
            // horizon whenever you looked sideways.
            val out = FloatArray(9)
            // Rx(pitch) columns, in the camera frame.
            val rx = floatArrayOf(
                1f, 0f, 0f,
                0f, cpi, spi,
                0f, -spi, cpi)
            for (c in 0 until 3) {
                // Column c of pose * Rx(pitch): combine the pose's columns by
                // the c-th column of Rx.
                val a = rx[c * 3]
                val b = rx[c * 3 + 1]
                val d = rx[c * 3 + 2]
                val x = pose[0] * a + pose[1] * b + pose[2] * d
                val y = pose[3] * a + pose[4] * b + pose[5] * d
                val z = pose[6] * a + pose[7] * b + pose[8] * d
                // Then Ry(yaw) on the world side, column-major out.
                out[c * 3] = cy * x + sy * z
                out[c * 3 + 1] = y
                out[c * 3 + 2] = -sy * x + cy * z
            }
            return out
        }
        val cp = cpi; val sp = spi
        // R = Ry(yaw) * Rx(pitch)
        return floatArrayOf(
            cy, 0f, -sy,
            sy * sp, cp, cy * sp,
            sy * cp, -sp, cy * cp)
    }

    /**
     * Half float, not eight bit. The panorama routinely spans ten stops or more,
     * and an 8-bit upload would decide the exposure at load time - which is the
     * one thing the viewer exists not to do.
     */
    private fun upload(image: ImageF) {
        if (texture != 0) GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        texture = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        // Longitude wraps and latitude does not, which is exactly what these say.
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T,
            GLES30.GL_CLAMP_TO_EDGE)

        val n = image.width * image.height * 3
        val buf: ShortBuffer = ByteBuffer.allocateDirect(n * 2)
            .order(ByteOrder.nativeOrder()).asShortBuffer()
        val src = image.data
        if (image.channels == 3) {
            for (i in 0 until n) buf.put(Half.fromFloat(src[i]))
        } else {
            for (p in 0 until image.width * image.height) {
                val v = Half.fromFloat(src[p * image.channels])
                buf.put(v); buf.put(v); buf.put(v)
            }
        }
        buf.position(0)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGB16F,
            image.width, image.height, 0, GLES30.GL_RGB, GLES30.GL_HALF_FLOAT, buf)
    }

    private fun link(vs: String, fs: String): Int {
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, compile(GLES30.GL_VERTEX_SHADER, vs))
        GLES30.glAttachShader(p, compile(GLES30.GL_FRAGMENT_SHADER, fs))
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw IllegalStateException(GLES30.glGetProgramInfoLog(p))
        return p
    }

    private fun compile(type: Int, source: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, source)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) throw IllegalStateException(GLES30.glGetShaderInfoLog(s))
        return s
    }

    companion object {
        private const val VERTEX = """#version 300 es
in vec2 aPos;
out vec2 vNdc;
void main() {
    vNdc = aPos;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

        /**
         * The equirectangular convention here is the pipeline's own, inverted:
         * direction = (-sin(lon) cos(lat), sin(lat), cos(lon) cos(lat)), so the
         * longitude comes back as atan(-x, z) and a viewer built on the usual
         * atan(x, z) would show the whole sphere mirrored.
         *
         * The other half of that, which shipped wrong: the world's +X is the
         * viewer's *left*, because the frame is right-handed with +Y up and +Z
         * the heading, so X = Y x Z points away from the hand you point with.
         * Building the ray with screen-right as +X therefore mapped the right of
         * the screen onto the left of the room, and looking around showed the
         * mirror image of a file that was itself correct.
         *
         * Nothing in the suite could catch it, because every test compared the
         * projection with its own inverse and the two agreed. What settled it was
         * a garden: reading the finished panorama left to right is what you see
         * turning to your right where you stood, which the equirect suite now
         * states as a fact rather than leaving implied.
         */
        private const val FRAGMENT = """#version 300 es
precision highp float;
in vec2 vNdc;
uniform sampler2D uPano;
uniform mat3 uView;
uniform float uExposure;
uniform float uAspect;
uniform float uFov;
out vec4 fragColor;
const float PI = 3.14159265359;
void main() {
    float t = uFov;
    // Negated, because the world's +X is the viewer's left. See above.
    vec3 dirCam = normalize(vec3(-vNdc.x * t * uAspect, vNdc.y * t, 1.0));
    vec3 d = normalize(uView * dirCam);
    float lon = atan(-d.x, d.z);
    float lat = asin(clamp(d.y, -1.0, 1.0));
    vec2 uv = vec2((lon + PI) / (2.0 * PI), (PI * 0.5 - lat) / PI);
    vec3 r = texture(uPano, uv).rgb;
    // Nothing was shot here. Drawn as absent rather than as black, because a
    // black screen reads as a broken viewer and a hole in the sphere is a fact
    // about the capture that the person needs to see.
    if (r.r <= 0.0 && r.g <= 0.0 && r.b <= 0.0) {
        float g = max(step(0.985, fract(uv.x * 64.0)), step(0.985, fract(uv.y * 32.0)));
        fragColor = vec4(vec3(0.055 + 0.045 * g), 1.0);
        return;
    }
    vec3 c = max(r, vec3(0.0)) * uExposure;
    // The same filmic curve the JPEG preview is made with, so the picture on
    // screen and the picture in the file are the same picture.
    c = clamp((c * (2.51 * c + 0.03)) / (c * (2.43 * c + 0.59) + 0.14), 0.0, 1.0);
    fragColor = vec4(pow(c, vec3(1.0 / 2.2)), 1.0);
}
"""
    }
}
