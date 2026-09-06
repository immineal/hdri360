package com.immineal.hdri360.device

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.immineal.hdri360.core.pano.OrientationMath

/**
 * Where the screen is pointing, for looking around a finished sphere by turning
 * the phone.
 *
 * Deliberately not [OrientationTracker]. That reports the pose of the *sensor*,
 * composed through SENSOR_ORIENTATION, and also watches the gyroscope to decide
 * when a bracket may fire - neither of which a viewer wants. This reports the
 * pose of the screen held up as a window, and nothing else.
 *
 * The pose is in the panorama's own frame, so a sphere follows the room rather
 * than the phone: stand where it was shot, turn, and the window is where the
 * window is. That works because the heading the capture recorded and the heading
 * this reads are the same magnetic north - and fails in the same way, so the
 * viewer keeps a drag to correct the heading by hand.
 */
class ScreenPose(context: Context, private val onPose: (DoubleArray) -> Unit) : SensorEventListener {

    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val rotationVector = sensors?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    /** Whether this phone can do it at all, so the button can be absent rather than dead. */
    fun isAvailable(): Boolean = rotationVector != null

    fun start() {
        val s = sensors ?: return
        val r = rotationVector ?: return
        // Game rate rather than the fastest available: a viewer redraws on every
        // sample, and a hundred a second is a hundred renders a second of an 8K
        // texture for motion no hand produces.
        s.registerListener(this, r, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        sensors?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor?.type != Sensor.TYPE_ROTATION_VECTOR) return
        val q = try {
            OrientationMath.quaternionFromRotationVector(event.values)
        } catch (e: Exception) {
            return
        }
        onPose(OrientationMath.screenToWorld(q).data())
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { }
}
