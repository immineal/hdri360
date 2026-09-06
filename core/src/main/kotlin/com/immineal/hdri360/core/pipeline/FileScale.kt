package com.immineal.hdri360.core.pipeline

import com.immineal.hdri360.core.hdr.RadianceScale
import com.immineal.hdri360.core.image.ImageF
import com.immineal.hdri360.core.image.ImageOps
import java.util.Locale

/**
 * What the numbers in the written file mean, and what to multiply the pipeline's
 * radiance by to get them.
 *
 * ## The bug this exists to fix
 *
 * The radiance scale used to be computed, put in the report, and never applied
 * to a pixel. The file was therefore in the pipeline's own arbitrary unit - a
 * sensor fraction over a relative exposure - and the factor from that to cd/m2
 * is `78 N^2 / (q * baseIso)`, which depends on the **lens**. Two spheres of the
 * same room, one from each of a phone's back cameras, came out at 11.96 and
 * 11.62 cd/m2 per unit while both reported an absolute scale. Three percent
 * apart on that particular phone; on one whose lenses differ more in aperture or
 * base ISO, far more. A file whose brightness depends on which lens took it is
 * not on an absolute scale in any useful sense, whatever the report says.
 *
 * It also made every file about 86 times too bright to simply open. Dropped into
 * a renderer as a world texture at strength 1.0, an ordinary sunny garden with a
 * mean of 143 read as 143, where real environment maps carry about 1.7.
 *
 * ## What replaces it
 *
 * **Kilocandela per square metre when the capture earned an absolute scale**:
 * 1.0 in the file means 1000 cd/m2. Lens-independent, one constant to document,
 * and it puts ordinary outdoor scenes between 1 and 50 - which is where every
 * environment map in the wild lives, so a renderer at strength 1.0 is right
 * without anybody adjusting anything.
 *
 * **Normalised to a median of 1.0 when it did not.** A capture the camera drove
 * itself has no cd/m2 to convert into, so there is nothing to be faithful to;
 * what is left is to make the file openable and to say plainly that the numbers
 * are a normalisation and not a measurement.
 */
class FileScale(
    /** Multiply pipeline radiance by this to get what goes in the file. */
    @JvmField val factor: Double,
    /**
     * What one unit in the file is worth in cd/m2, or zero when the capture has
     * no absolute scale and no conversion exists.
     */
    @JvmField val cdPerM2PerUnit: Double,
    /** Where the number came from, for the report and for anyone doubting it. */
    @JvmField val basis: String
) {
    val absolute: Boolean get() = cdPerM2PerUnit > 0

    fun apply(radiance: Double): Double = radiance * factor

    /** In place, for a strip on its way to the file. */
    fun applyTo(image: ImageF) {
        if (Math.abs(factor - 1.0) < 1e-12) return
        val f = factor.toFloat()
        val d = image.data
        for (i in d.indices) d[i] *= f
    }

    /** A copy in file units, for the viewer and the preview. */
    fun scaled(image: ImageF): ImageF {
        val out = image.copy()
        applyTo(out)
        return out
    }

    override fun toString(): String =
        if (absolute) String.format(Locale.US, "kcd/m2 [x %.6g; 1 unit = %.0f cd/m2]",
            factor, cdPerM2PerUnit)
        else String.format(Locale.US, "normalised [x %.6g; no units]", factor)

    companion object {
        /**
         * What one file unit means when the capture is a measurement.
         *
         * A thousand rather than one, so that ordinary scenes land near 1.0. It
         * is a convention and not a physical constant, which is why it is named,
         * documented and written into the report rather than left in a comment.
         */
        const val CD_PER_M2_PER_UNIT = 1000.0

        /** Leaves the radiance exactly as the pipeline produced it. */
        @JvmStatic
        val IDENTITY = FileScale(1.0, 0.0, "the pipeline's own units, unscaled")

        /**
         * The scale for a finished sphere.
         *
         * [panorama] is only needed for a capture with no absolute scale, where
         * the normalisation has to come from the sphere itself. The pipeline's own
         * small preview render is the right thing to pass: a median is a median at
         * any resolution, and the full-size sphere does not exist as one array.
         */
        @JvmStatic
        fun of(scale: RadianceScale, panorama: ImageF?): FileScale {
            if (scale.absolute) {
                val f = scale.cdPerM2PerUnit / CD_PER_M2_PER_UNIT
                if (!(f > 0) || !f.isFinite()) return IDENTITY
                return FileScale(f, CD_PER_M2_PER_UNIT, String.format(Locale.US,
                    "kilocandela per square metre: one unit is %.0f cd/m2, from %s",
                    CD_PER_M2_PER_UNIT, scale.basis))
            }
            val median = medianLuminance(panorama)
            // Nothing to normalise against is not a licence to invent one. An
            // all-black sphere, or none at all, is left as it is.
            if (!(median > 0) || !median.isFinite())
                return FileScale(1.0, 0.0,
                    "normalised: nothing in the sphere to normalise against, so left unscaled - " +
                    scale.basis)
            return FileScale(1.0 / median, 0.0,
                "normalised so the sphere's own median is one; not a measurement - " + scale.basis)
        }

        private fun medianLuminance(panorama: ImageF?): Double {
            if (panorama == null) return 0.0
            val n = panorama.width * panorama.height
            if (n <= 0) return 0.0
            val c = panorama.channels
            val v = FloatArray(n)
            for (i in 0 until n) {
                val b = i * c
                v[i] = if (c >= 3)
                    ImageOps.LUMA_R * panorama.data[b] + ImageOps.LUMA_G * panorama.data[b + 1] +
                    ImageOps.LUMA_B * panorama.data[b + 2]
                else panorama.data[b]
            }
            java.util.Arrays.sort(v)
            // Over the pixels that saw anything. Half a sphere of holes would
            // otherwise put the median at zero and normalise by nothing.
            var first = 0
            while (first < n && v[first] <= 0f) first++
            if (first >= n) return 0.0
            // The upper of the two middle values for an even count, rather than
            // their mean. It is a normalisation constant, so which of the two is
            // immaterial - but only if it is stated, because a test that assumes
            // the other one is a test that fails for no reason.
            val m = first + (n - first) / 2
            return v[m].toDouble()
        }
    }
}
