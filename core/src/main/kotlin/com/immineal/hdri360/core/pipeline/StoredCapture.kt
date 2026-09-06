package com.immineal.hdri360.core.pipeline

import com.immineal.hdri360.core.capture.CaptureTier
import com.immineal.hdri360.core.capture.FrameRecord
import com.immineal.hdri360.core.capture.FrameStore
import com.immineal.hdri360.core.capture.SensorGeometry
import com.immineal.hdri360.core.capture.StoredSession
import com.immineal.hdri360.core.hdr.Exposure
import com.immineal.hdri360.core.hdr.Photometry
import com.immineal.hdri360.core.hdr.RadianceScale
import com.immineal.hdri360.core.image.BayerImage
import com.immineal.hdri360.core.image.CfaPattern
import com.immineal.hdri360.core.image.Demosaic
import com.immineal.hdri360.core.image.ImageF
import com.immineal.hdri360.core.image.ImageOps
import java.util.Locale

/**
 * A capture on disk, presented to the pipeline as the brackets it expects.
 *
 * Every direction is deferred. A sphere is gigabytes of working frames, and
 * building the input list by opening all of them would undo the whole point of
 * the pipeline's lazy brackets: what has to fit in memory is one bracket per
 * worker, not one capture.
 */
object StoredCapture {

    /** How a frame's pixels are obtained. Separated so the deferral is testable. */
    interface Reader {
        fun read(record: FrameRecord): ImageF
    }

    private class StoreReader(private val store: FrameStore) : Reader {
        override fun read(record: FrameRecord): ImageF = store.read(record)
    }

    /**
     * One input per direction that was completely shot, in capture order.
     *
     * A direction missing a rung is left out rather than merged from a shorter
     * ladder: the missing rung is normally the longest one, so what would be lost
     * is precisely the shadow detail the bracket existed to capture.
     */
    @JvmStatic
    @JvmOverloads
    fun inputs(store: FrameStore, reader: Reader = StoreReader(store),
               subsample: Int = 1): List<HdriPipeline.FrameInput> {
        val session = store.session
        val k = SensorGeometry.subsampled(session.intrinsics, subsample)
        val out = ArrayList<HdriPipeline.FrameInput>()
        for (target in session.plan.indicesPerTarget.indices) {
            val bracket = recordsFor(store, target) ?: continue
            val label = String.format(Locale.US, "t%03d", target)
            out.add(HdriPipeline.FrameInput.deferred(k, bracket[0].pose, label) {
                exposuresOf(bracket, session, reader, subsample)
            })
        }
        return out
    }

    /**
     * How much to shrink each frame so that merging one bracket fits in
     * [budgetBytes].
     *
     * What sets the size of the job is no longer the sphere. With merged
     * directions parked on disk, the peak is one bracket: the stored mosaic being
     * read, the colour image it demosaics to, the rungs held while they are
     * combined, and the radiance that comes out. The sphere itself can be any
     * size, which is the point of spooling it.
     *
     * Reducing rather than failing is still the honest trade - the alternative is
     * a capture the user cannot process at all - and what is chosen gets said out
     * loud in the report.
     */
    @JvmStatic
    fun mergingSubsampleFor(framePixels: Long, rungs: Int, budgetBytes: Long): Int {
        if (framePixels <= 0 || rungs <= 0 || budgetBytes <= 0) return 1
        var f = 1
        while (f < 8 && mergePeakBytes(framePixels, rungs, f) > budgetBytes) f *= 2
        return f
    }

    /** Peak bytes one worker needs to merge a bracket reduced by [f]. */
    @JvmStatic
    fun mergePeakBytes(framePixels: Long, rungs: Int, f: Int): Long {
        val working = framePixels / (f.toLong() * f)
        // The stored plane, read whole; the colour image it becomes; the rungs held
        // for the merge; and the radiance plus confidence that come out of it.
        val mosaic = framePixels * 4
        val demosaiced = if (f >= 2) framePixels / 4 * 12 else framePixels * 12
        return mosaic + demosaiced + rungs * working * 12 + working * 16
    }

    /** The bracket for one direction, read now. */
    @JvmStatic
    @JvmOverloads
    fun openBracketFor(store: FrameStore, target: Int,
                       reader: Reader = StoreReader(store), subsample: Int = 1): List<Exposure> {
        val bracket = recordsFor(store, target)
            ?: throw IllegalArgumentException("direction $target was not completely shot")
        return exposuresOf(bracket, store.session, reader, subsample)
    }

    /** Forces a deferred input, for callers that want the frames rather than the pipeline. */
    @JvmStatic
    fun open(input: HdriPipeline.FrameInput): List<Exposure> = input.openBracket()

    /**
     * What this capture's radiance may be called.
     *
     * Only the top tier earns an absolute scale: linear sensor values at a
     * shutter and ISO the app itself chose. Below that the numbers are a
     * reconstruction, and the scale says so rather than letting an EXR full of
     * plausible floats imply a measurement that was never made.
     */
    @JvmStatic
    fun radianceScaleFor(session: StoredSession): RadianceScale = when (session.tier) {
        CaptureTier.LINEAR_RAW ->
            RadianceScale.absolute(session.apertureN, session.baseIso, Photometry.LENS_FACTOR)
        CaptureTier.MANUAL_YUV -> RadianceScale.relative(
            "the exposures were ours, but the pixels came through the camera's tone curve, " +
            "so the response was recovered from the bracket rather than measured")
        CaptureTier.LOCKED_AUTO -> RadianceScale.relative(
            "this camera would not take manual exposures, so the bracket is what it chose " +
            "and the values are relative to one another only")
    }

    /** Pipeline options that follow from what was actually captured. */
    @JvmStatic
    fun optionsFor(session: StoredSession, panoramaWidth: Int): HdriPipeline.Options {
        val o = HdriPipeline.Options()
        o.panoramaWidth = panoramaWidth
        o.radianceScale = radianceScaleFor(session)
        // Recorded at capture time and applied here, which is what makes a bundle
        // reproducible off the phone at all: the DNGs are raw, so a capture whose
        // map was not written down stitches frames whose corners are a stop dark.
        o.shading = session.shadingMap
        o.colorTransform = colorTransformFor(session)
        return o
    }

    /**
     * The one 3x3 that takes this capture's merged sensor RGB to linear Rec.709,
     * or null when the camera gave nothing to build it from.
     *
     * Two things composed, in the order the camera defines them: the white
     * balance gains, then the sensor-RGB to linear-sRGB matrix. sRGB and Rec.709
     * share primaries and a white point, and differ in a transfer function a
     * linear file does not have.
     *
     * The gains are normalised on green rather than applied outright, because the
     * absolute cd/m2 scale is calibrated against green: scaling all three
     * channels would move the luminance the calibration describes, while scaling
     * relative to green corrects the colour and leaves it alone. The matrix's own
     * rows sum to one, so a neutral survives the whole composition unmoved.
     *
     * Returned as one matrix rather than applied as two steps because it is one
     * pass over the radiance instead of two, and because there is then a single
     * object that answers "what space is this file in".
     */
    @JvmStatic
    fun colorTransformFor(session: StoredSession): DoubleArray? {
        val m = session.colorMatrix
        val gains = session.neutralGains
        if (m == null || m.size < 9) return null
        val gr: Double
        val gb: Double
        if (gains != null && gains.size >= 3 && gains[1] > 1e-9) {
            gr = gains[0] / gains[1]
            gb = gains[2] / gains[1]
        } else {
            gr = 1.0
            gb = 1.0
        }
        // m . diag(gr, 1, gb)
        return doubleArrayOf(
            m[0] * gr, m[1], m[2] * gb,
            m[3] * gr, m[4], m[5] * gb,
            m[6] * gr, m[7], m[8] * gb)
    }

    // ------------------------------------------------------------------ detail

    private fun recordsFor(store: FrameStore, target: Int): List<FrameRecord>? {
        val wanted = store.session.plan.indicesPerTarget[target].size
        if (wanted == 0) return null
        val found = arrayOfNulls<FrameRecord>(wanted)
        for (r in store.records())
            if (r.targetIndex == target && r.bracketIndex in 0 until wanted) found[r.bracketIndex] = r
        val out = ArrayList<FrameRecord>(wanted)
        for (r in found) out.add(r ?: return null)
        return out
    }

    private fun exposuresOf(bracket: List<FrameRecord>, session: StoredSession,
                            reader: Reader, subsample: Int): List<Exposure> {
        val out = ArrayList<Exposure>(bracket.size)
        for (r in bracket) {
            var image = reader.read(r)
            // A single-channel frame with a CFA phase is a mosaic, not a grey
            // image, and the pipeline works in colour from here on.
            var f = subsample
            if (image.channels == 1 && r.cfaOrdinal >= 0) {
                val pattern = CfaPattern.entries.getOrElse(r.cfaOrdinal) { session.cfa }
                val mosaic = BayerImage(image.width, image.height, pattern, image.data)
                // When the frame is going to be halved anyway, take one pixel per
                // CFA block instead of interpolating twelve megapixels to three
                // channels and then throwing three quarters of them away. Every
                // channel is then a photosite that measured that colour, and the
                // interpolation error is not there to be carried forward - at the
                // cost of a half-pixel offset between the colour planes, which is
                // half a pixel of an image that has already been halved.
                if (f >= 2) { image = Demosaic.halfResolution(mosaic); f /= 2 }
                else image = Demosaic.malvarHeCutler(mosaic)
            }
            // Reduced here, one rung at a time, so the full size copy is collectable
            // before the next rung is read rather than after the whole bracket is.
            while (f > 1) { image = ImageOps.downsample2x(image); f /= 2 }
            out.add(Exposure.of(image, r.settings, session.baseIso))
        }
        return out
    }
}
