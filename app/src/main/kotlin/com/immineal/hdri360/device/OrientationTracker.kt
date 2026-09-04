package com.immineal.hdri360.device

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.immineal.hdri360.core.math.Mat3
import com.immineal.hdri360.core.pano.OrientationMath

/**
 * Device orientation as a camera-to-world rotation the pipeline can use directly.
 *
 * The rotation vector is a fused estimate - gyro for responsiveness,
 * accelerometer and magnetometer against drift - which makes it good enough to
 * seed a bundle adjustment and nowhere near good enough to replace one: it is
 * typically a degree or two out, and a degree is a visible seam.
 *
 * Stillness is reported raw, sample by sample. The dwell that turns a run of
 * still samples into permission to fire lives in CaptureController, where it can
 * be tested; a phone swept past a target passes through zero angular rate on the
 * way, and a single sub-threshold sample is what makes that read as steady.
 */
class OrientationTracker(
    context: Context,
    private val sensorOrientationDeg: Int,
    private val frontFacing: Boolean,
    private val listener: Listener
) : SensorEventListener {

    fun interface Listener {
        /** [nowNs] is the sensor's own clock, which is what the controller times against. */
        fun onOrientation(cameraToWorld: Mat3, stableNow: Boolean, nowNs: Long)
    }

    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val rotationVector = sensors?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val gyroscope = sensors?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    /** Angular rate below which a hand counts as still, in radians per second. */
    @JvmField @Volatile var stabilityThreshold = 0.035

    @Volatile private var latest: Mat3 = Mat3.IDENTITY
    @Volatile private var stable = false
    @Volatile private var fix = false

    fun isAvailable(): Boolean = rotationVector != null
    fun hasFix(): Boolean = fix
    fun currentPose(): Mat3 = latest

    /**
     * Why a capture cannot be guided, or null if it can. Said plainly and up
     * front rather than discovered as a sphere that never fills in.
     */
    fun unavailableReason(): String? = when {
        sensors == null -> "This device reports no sensors"
        rotationVector == null ->
            "This device has no rotation sensor, so a guided sphere cannot be aimed"
        else -> null
    }

    fun start() {
        val s = sensors ?: return
        rotationVector?.let { s.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gyroscope?.let { s.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        sensors?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                stable = OrientationMath.isStable(event.values, stabilityThreshold)
                return
            }
            Sensor.TYPE_ROTATION_VECTOR -> {
                val q = OrientationMath.quaternionFromRotationVector(event.values)
                latest = OrientationMath.cameraToWorld(q, sensorOrientationDeg, frontFacing)
                fix = true
                // With no gyroscope at all, assume the user is holding still rather
                // than refusing to ever fire the shutter.
                val steady = if (gyroscope == null) true else stable
                listener.onOrientation(latest, steady, event.timestamp)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) { }
}
