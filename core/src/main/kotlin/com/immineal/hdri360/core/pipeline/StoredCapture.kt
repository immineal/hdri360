package com.immineal.hdri360.core.pipeline

import com.immineal.hdri360.core.capture.CaptureTier
import com.immineal.hdri360.core.capture.FrameRecord
import com.immineal.hdri360.core.capture.FrameStore
import com.immineal.hdri360.core.capture.StoredSession
import com.immineal.hdri360.core.hdr.Exposure
import com.immineal.hdri360.core.hdr.Photometry
import com.immineal.hdri360.core.hdr.RadianceScale
import com.immineal.hdri360.core.image.BayerImage
import com.immineal.hdri360.core.image.CfaPattern
import com.immineal.hdri360.core.image.Demosaic
import com.immineal.hdri360.core.image.ImageF
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
    fun inputs(store: FrameStore, reader: Reader = StoreReader(store)): List<HdriPipeline.FrameInput> {
        val session = store.session
        val out = ArrayList<HdriPipeline.FrameInput>()
        for (target in session.plan.indicesPerTarget.indices) {
            val bracket = recordsFor(store, target) ?: continue
            val label = String.format(Locale.US, "t%03d", target)
            out.add(HdriPipeline.FrameInput.deferred(session.intrinsics, bracket[0].pose, label) {
                exposuresOf(bracket, session, reader)
            })
        }
        return out
    }

    /** The bracket for one direction, read now. */
    @JvmStatic
    @JvmOverloads
    fun openBracketFor(store: FrameStore, target: Int,
                       reader: Reader = StoreReader(store)): List<Exposure> {
        val bracket = recordsFor(store, target)
            ?: throw IllegalArgumentException("direction $target was not completely shot")
        return exposuresOf(bracket, store.session, reader)
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
        return o
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
                            reader: Reader): List<Exposure> {
        val out = ArrayList<Exposure>(bracket.size)
        for (r in bracket) {
            var image = reader.read(r)
            // A single-channel frame with a CFA phase is a mosaic, not a grey
            // image, and the pipeline works in colour from here on.
            if (image.channels == 1 && r.cfaOrdinal >= 0) {
                val pattern = CfaPattern.entries.getOrElse(r.cfaOrdinal) { session.cfa }
                image = Demosaic.malvarHeCutler(
                    BayerImage(image.width, image.height, pattern, image.data))
            }
            out.add(Exposure.of(image, r.settings, session.baseIso))
        }
        return out
    }
}
