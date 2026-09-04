package com.immineal.hdri360.ui

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.view.MotionEvent
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

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = event.x; lastY = event.y }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                val dy = event.y - lastY
                lastX = event.x
                lastY = event.y
                renderer.yaw -= dx * DRAG_SPEED
                // Stop short of the poles: looking straight up loses the horizon
                // reference and the drag becomes impossible to reason about.
                renderer.pitch = Math.max(-1.45, Math.min(1.45, renderer.pitch - dy * DRAG_SPEED))
                requestRender()
            }
        }
        return true
    }

    private companion object {
        const val DRAG_SPEED = 0.0035
    }
}

private class PanoramaRenderer : GLSurfaceView.Renderer {

    @Volatile var pending: ImageF? = null
    @Volatile var exposureStops = 0.0
    @Volatile var yaw = 0.0
    @Volatile var pitch = 0.0

    private var program = 0
    private var texture = 0
    private var aspect = 1f
    private var uView = 0
    private var uExposure = 0
    private var uAspect = 0
    private var quad: FloatBuffer? = null

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = link(VERTEX, FRAGMENT)
        uView = GLES30.glGetUniformLocation(program, "uView")
        uExposure = GLES30.glGetUniformLocation(program, "uExposure")
        uAspect = GLES30.glGetUniformLocation(program, "uAspect")
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
        val cp = Math.cos(pitch).toFloat(); val sp = Math.sin(pitch).toFloat()
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
         */
        private const val FRAGMENT = """#version 300 es
precision highp float;
in vec2 vNdc;
uniform sampler2D uPano;
uniform mat3 uView;
uniform float uExposure;
uniform float uAspect;
out vec4 fragColor;
const float PI = 3.14159265359;
void main() {
    float t = 0.7;                                   // about 70 degrees across
    vec3 dirCam = normalize(vec3(vNdc.x * t * uAspect, vNdc.y * t, 1.0));
    vec3 d = normalize(uView * dirCam);
    float lon = atan(-d.x, d.z);
    float lat = asin(clamp(d.y, -1.0, 1.0));
    vec2 uv = vec2((lon + PI) / (2.0 * PI), (PI * 0.5 - lat) / PI);
    vec3 c = texture(uPano, uv).rgb * uExposure;
    c = c / (c + vec3(1.0));                         // Reinhard, so nothing ever clips to white
    fragColor = vec4(pow(max(c, vec3(0.0)), vec3(1.0 / 2.2)), 1.0);
}
"""
    }
}
