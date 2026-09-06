package com.immineal.hdri360.core.capture

import com.immineal.hdri360.core.camera.Intrinsics
import com.immineal.hdri360.core.pano.CapturePlan
import com.immineal.hdri360.core.pano.CapturePlanConfig
import java.util.Locale

/**
 * One lens the person can choose between, whether or not the system lists it as
 * a camera.
 *
 * A modern phone does not present its lenses as cameras. On a Pixel 9a
 * `cameraIdList` returns 0 and 1; camera 0 reports LOGICAL_MULTI_CAMERA and
 * lists physical ids 2 and 3, and physical 3 is a 1.84 mm module with a 104
 * degree field of view, RAW and a manual sensor. Enumerating only the listed
 * cameras therefore misses the best lens on the phone for this job.
 *
 * Platform-free, so the ordering and the choice can be exercised against the
 * numbers a real device reported rather than by tapping at one.
 */
class Lens(
    /**
     * Stable identity for reports, logs and stored sessions.
     *
     * A physical camera is `<logical>:<physical>`, because a physical id is only
     * meaningful next to the camera it hides behind - and a capture that recorded
     * "3" and came back on a phone where 3 means something else would be a
     * capture resumed on the wrong lens.
     */
    @JvmField val id: String,
    /** The camera to open. For a physical lens, its logical parent. */
    @JvmField val openId: String,
    /**
     * The physical camera to bind the streams to, or null for the logical camera
     * itself.
     *
     * Not an id to open. A physical camera behind a logical one is generally not
     * openable on its own; it is reached by opening the parent and naming the
     * physical id on each output configuration.
     */
    @JvmField val physicalId: String?,
    @JvmField val frontFacing: Boolean,
    @JvmField val focalLengthMm: Double,
    @JvmField val horizontalFovDeg: Double,
    @JvmField val verticalFovDeg: Double,
    /**
     * The best this lens can honestly do. Carried rather than derived from a
     * label later, so nothing downstream has to re-decide it - and so a lens
     * that cannot measure radiance cannot be mistaken for one that can.
     */
    @JvmField val tier: CaptureTier,
    @JvmField val sensor: SensorSize
) {
    val isPhysical: Boolean get() = physicalId != null
    val measuresRadiance: Boolean get() = tier.measuresRadiance

    override fun toString(): String = String.format(Locale.US,
        "%s %.2f mm %.1f deg %s%s", id, focalLengthMm, horizontalFovDeg, tier,
        if (isPhysical) " (physical)" else "")
}

/**
 * Which lenses to offer, in what order, and which to start on.
 *
 * The rules are three of the decisions taken for this app, and each of them
 * reverses something the code did before:
 *
 *  - **Every camera is enumerated**, the ones behind a logical camera included.
 *  - **A lens without RAW is still offered**, with its tier stated, rather than
 *    hidden for not being a measurement.
 *  - **The default is the widest lens that can shoot RAW**, not the longest.
 */
object LensChooser {

    /**
     * The lenses worth putting in front of somebody, best first.
     *
     * Two things happen here. Duplicates are collapsed: a logical camera and one
     * of its own physicals are frequently the same lens, because a logical camera
     * *is* the main module with the others bolted on behind it - on the 9a,
     * physical 2 has camera 0's focal length, field of view and sensor. Offering
     * both puts two identical rows in front of the person, one of which needs a
     * physical stream binding for no reason.
     *
     * Then the order, which is the same rule as [default] so that the list opens
     * on the lens it is about to use: away from the person first, then the ones
     * that can measure radiance, then the widest.
     */
    @JvmStatic
    fun offer(all: List<Lens>): List<Lens> {
        if (all.isEmpty()) return emptyList()
        val kept = ArrayList<Lens>(all.size)
        for (l in all) {
            // A physical camera that matches a listed camera is that camera. Keep
            // the one that opens without a binding.
            if (l.isPhysical && all.any { !it.isPhysical && sameLens(it, l) }) continue
            kept.add(l)
        }
        kept.sortWith(compareBy<Lens> { it.frontFacing }
            .thenByDescending { it.measuresRadiance }
            .thenByDescending { it.horizontalFovDeg }
            .thenBy { it.id })
        return kept
    }

    /**
     * The lens to start on: the widest that shoots RAW *and* that the camera will
     * actually deliver frames from.
     *
     * Two rules, in this order, and the second one was learned the hard way.
     *
     * Width beats focal length. The earlier rule took the longest focal length
     * among the RAW lenses because an ultrawide is softer and more distorted, and
     * measured against what actually costs the person something that was the
     * wrong trade: the 9a's ultrawide takes the sphere from 34 directions to 21
     * *and* raises the worst frame's partner count from four to six. Fewer
     * minutes standing in a room, and a better connected pose graph, for some
     * softness at the frame edges that the overlap was already covering. Width
     * does not beat RAW, though - linear radiance is the product.
     *
     * But a whole camera beats a physical stream, because a field of view nobody
     * can capture is worth nothing. Opened as `0:3`, a RAW stream bound to a
     * physical sub-camera, the 9a's ultrawide delivered **two frames out of
     * eighty-four in seventeen seconds** and the capture died with all twenty-one
     * directions abandoned - while the same phone on camera `0`, opening as
     * itself, had produced a full 34 direction bundle of 137 frames in one go.
     * Preview, metering and the entire scan run fine on the physical binding; it
     * is RAW_SENSOR that does not arrive.
     *
     * A preference, not a prohibition: the wider lens is still offered and still
     * choosable, and on a phone that lists its ultrawide as a camera in its own
     * right it wins outright. What this buys is that the first capture somebody
     * takes is one that finishes.
     */
    @JvmStatic
    fun default(lenses: List<Lens>): Lens? {
        if (lenses.isEmpty()) return null
        val offered = if (lenses === offer(lenses)) lenses else offer(lenses)
        val back = offered.filter { !it.frontFacing }
        // A phone with only a front camera has to start somewhere.
        val pool = back.ifEmpty { offered }
        val raw = pool.filter { it.measuresRadiance }
        val usable = raw.ifEmpty { pool }
        // And a phone whose only back lens is physical starts on it rather than
        // on nothing: demoting is not refusing.
        val whole = usable.filter { !it.isPhysical }
        return (whole.ifEmpty { usable }).maxByOrNull { it.horizontalFovDeg }
    }

    /**
     * How many directions a sphere needs on this lens.
     *
     * The number the person is actually spending. A field of view in degrees
     * means nothing to somebody deciding which lens to use; how many times they
     * have to stop, aim and hold still does, and on the 9a the two back lenses
     * differ by thirteen of them.
     */
    @JvmStatic
    @JvmOverloads
    fun directionsFor(lens: Lens, rollDeg: Double = 90.0,
                      cfg: CapturePlanConfig = CapturePlanConfig()): Int {
        val k = Intrinsics.fromHorizontalFov(lens.sensor.width, lens.sensor.height,
            lens.horizontalFovDeg)
        return CapturePlan.forCamera(k, cfg, rollDeg).targets.size
    }

    /**
     * One line a person can choose by: how wide, how many stops it will take, and
     * whether the numbers that come out are a measurement.
     */
    @JvmStatic
    @JvmOverloads
    fun describe(lens: Lens, rollDeg: Double = 90.0): String {
        val what = when {
            lens.frontFacing -> "Front"
            lens.horizontalFovDeg >= 95 -> "Ultrawide"
            lens.horizontalFovDeg >= 60 -> "Wide"
            else -> "Tele"
        }
        val tier = when (lens.tier) {
            CaptureTier.LINEAR_RAW -> "RAW"
            // Said plainly rather than by its tier name: what the person needs to
            // know is that the file will have no absolute scale, not what the
            // enum is called.
            CaptureTier.MANUAL_YUV -> "no RAW, relative values"
            CaptureTier.LOCKED_AUTO -> "camera picks the exposure"
        }
        // A lens that exists only as a stream bound to a physical sub-camera is
        // offered with what is known about it attached. On the phone this was
        // written for, RAW through that binding delivered two frames out of
        // eighty-four while the same sensor opened as a camera of its own
        // delivered every one - and the person shooting it had no way to tell
        // that from their own hands not being steady enough. It stays available,
        // because it may work elsewhere and it is the better lens when it does.
        val caveat = if (lens.isPhysical) ", untested on this phone" else ""
        return String.format(Locale.US, "%s, %.0f degrees, %d directions, %s%s",
            what, lens.horizontalFovDeg, directionsFor(lens, rollDeg), tier, caveat)
    }

    /**
     * Whether two entries describe one lens.
     *
     * Focal length and sensor together, because on the device that forced this
     * the duplicate agreed on both to the digit - and because either alone is
     * shared by lenses that are genuinely different on some phone somewhere.
     */
    private fun sameLens(a: Lens, b: Lens): Boolean =
        a.frontFacing == b.frontFacing &&
        Math.abs(a.focalLengthMm - b.focalLengthMm) < 0.05 &&
        a.sensor.width == b.sensor.width && a.sensor.height == b.sensor.height
}
