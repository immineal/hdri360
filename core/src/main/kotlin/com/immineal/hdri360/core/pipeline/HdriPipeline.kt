package com.immineal.hdri360.core.pipeline

import com.immineal.hdri360.core.Parallel
import com.immineal.hdri360.core.camera.Intrinsics
import com.immineal.hdri360.core.hdr.Exposure
import com.immineal.hdri360.core.hdr.HdrMerger
import com.immineal.hdri360.core.hdr.MergeConfig
import com.immineal.hdri360.core.hdr.MergeResult
import com.immineal.hdri360.core.hdr.RadianceScale
import com.immineal.hdri360.core.image.ImageF
import com.immineal.hdri360.core.image.ImageOps
import com.immineal.hdri360.core.math.Mat3
import com.immineal.hdri360.core.math.Vec3
import com.immineal.hdri360.core.pano.BriefMatcher
import com.immineal.hdri360.core.pano.FastCornerDetector
import com.immineal.hdri360.core.pano.FeatureSet
import com.immineal.hdri360.core.pano.FrameSource
import com.immineal.hdri360.core.pano.HorizonEstimator
import com.immineal.hdri360.core.pano.PanoramaRenderer
import com.immineal.hdri360.core.pano.PhotometricAligner
import com.immineal.hdri360.core.pano.RotationBundleAdjuster
import com.immineal.hdri360.core.pano.RotationSolver
import java.util.Arrays
import java.util.Collections
import java.util.Random

/**
 * Brackets in, equirectangular HDRI out.
 *
 * The order matters and each step exists for a reason:
 *
 *  1. Merge each bracket to linear radiance. Everything after this point works on
 *     radiance, never on encoded pixels.
 *  2. Detect and describe features on a log-normalised copy of each frame, so one
 *     threshold behaves the same in the sky and under the trees.
 *  3. Solve every overlapping pair for a relative rotation with RANSAC. Two
 *     correspondences are a minimal sample for a pure rotation, which is what
 *     makes this cheap enough to run on every pair.
 *  4. Chain the strongest pairs into a spanning tree to get an initial pose for
 *     every frame - or fall back on the device's orientation prior where the
 *     imagery gives nothing to hold on to.
 *  5. Refine all poses at once with a robust bundle adjustment, so the loop
 *     closes instead of dumping its accumulated drift into the last seam.
 *  6. Solve one brightness scale per frame from the overlaps, then composite.
 */
object HdriPipeline {

    /**
     * One capture direction: its bracket, its optics, and optionally what the gyro said.
     *
     * The bracket can be held in memory or read on demand. On a phone the second
     * is the only workable choice: a full sphere is dozens of directions of
     * several exposures each, and holding them all at once runs out of heap long
     * before the capture is finished. [deferred] lets the merge read each bracket
     * as it reaches it and release the frames immediately afterwards, so what has
     * to fit is one bracket per worker rather than the whole capture.
     */
    class FrameInput private constructor(
        @JvmField val intrinsics: Intrinsics,
        @JvmField val priorRotation: Mat3?,
        @JvmField val label: String,
        private val held: List<Exposure>?,
        private val open: (() -> List<Exposure>)?
    ) {
        /** A bracket already in memory. */
        constructor(bracket: List<Exposure>, intrinsics: Intrinsics,
                    priorRotation: Mat3?, label: String)
                : this(intrinsics, priorRotation, label, bracket, null) {
            if (bracket.isEmpty()) throw IllegalArgumentException("empty bracket")
        }

        /** True when the frames are resident rather than read on demand. */
        val resident: Boolean get() = held != null

        /**
         * Yields the frames. For a deferred input this is where the read happens,
         * and the caller is expected to drop the result as soon as it has merged.
         */
        internal fun openBracket(): List<Exposure> {
            val b = held ?: open!!.invoke()
            if (b.isEmpty()) throw IllegalArgumentException("empty bracket: " + label)
            return b
        }

        companion object {
            /**
             * A bracket read only when the merge reaches it.
             *
             * [open] is called once, from a worker thread, and must be safe to call
             * concurrently with the same function for other directions.
             */
            @JvmStatic
            fun deferred(intrinsics: Intrinsics, priorRotation: Mat3?, label: String,
                         open: () -> List<Exposure>): FrameInput =
                FrameInput(intrinsics, priorRotation, label, null, open)
        }
    }

    class Options {
        @JvmField var panoramaWidth = 4096
        @JvmField var featureWorkingWidth = 640
        @JvmField var maxFeaturesPerFrame = 500
        @JvmField var fastThreshold = 0.02
        @JvmField var ransacThresholdDeg = 0.4
        @JvmField var ransacIterations = 1000
        @JvmField var minPairMatches = 12
        @JvmField var minPairInliers = 12
        @JvmField var baHuberDeg = 0.5
        /** Weight on the device orientation prior; 0 ignores it once features are available. */
        @JvmField var priorWeight = 0.0
        @JvmField var featherPx = 60.0
        @JvmField var solvePhotometric = true
        @JvmField var photometricSamplesPerPair = 80
        @JvmField var cosinePower = 0.0
        /** Signal-to-noise ratio above which a merged pixel is fully trusted by the blender. */
        @JvmField var confidenceSnrReference = 30.0
        /**
         * When no orientation prior is supplied, recover the horizon from the frames
         * themselves and level the result. A tilted HDRI lights a scene from the
         * wrong direction, so this is on by default.
         */
        @JvmField var levelHorizon = true
        /**
         * Recover one radial distortion coefficient shared by every frame.
         *
         * Nothing else in the pipeline estimates lens geometry, so without this
         * the bundle adjustment has to express real barrel distortion as pose
         * error - which it does, biasing every pose and smearing the seams it is
         * supposed to be tightening.
         *
         * On by default because the app's own capture path is RAW, which is not
         * distortion-corrected by anything. Note that processed JPEGs usually are
         * already rectified by the phone's imaging pipeline - re-stitching Pixel
         * HDR+ output recovers k1 of about 0.002, i.e. nothing, which is the
         * correct answer for an input that has already been corrected.
         */
        @JvmField var solveDistortion = true
        /**
         * Width of the grid the compositing seam is solved on. See SeamFinder:
         * this is what stops a moving subject or a parallax-shifted near object
         * being averaged into a ghost.
         *
         * -1 sizes it from the output; 0 disables seams and blends by weight
         * alone, which is what the pipeline did before and is worth keeping for
         * comparison. Solving at a quarter of the output width is plenty: a seam
         * is a boundary between regions, and the data does not determine its
         * position to the pixel.
         */
        @JvmField var seamWidth = -1
        @JvmField var seamFeather = 2.5
        /**
         * How many brackets may be open at once. 0 uses every worker thread.
         *
         * Only matters for deferred inputs, where it is the difference between
         * peak memory being one bracket and being one per core. On a device that
         * is the knob worth having.
         */
        @JvmField var mergeConcurrency = 0
        @JvmField var merge = MergeConfig()
        /**
         * What the output radiance means. Supplied by the caller because only it
         * knows whether the capture was a genuine measurement - the pipeline sees
         * pixels and exposures, not which capability tier produced them.
         */
        @JvmField var radianceScale: RadianceScale =
            RadianceScale.relative("no photometric calibration supplied")
        @JvmField var seed = 12345L
    }

    class PairResult internal constructor(
        @JvmField val a: Int,
        @JvmField val b: Int,
        @JvmField val matches: Int,
        @JvmField val inliers: Int,
        /** maps frame a's world bearings to frame b's */
        @JvmField val relative: Mat3
    )

    class Result internal constructor(
        @JvmField val panorama: ImageF,
        @JvmField val coverage: FloatArray,
        @JvmField val rotations: Array<Mat3>,
        @JvmField val gains: DoubleArray,
        @JvmField val placed: BooleanArray,
        @JvmField val pairs: List<PairResult>,
        @JvmField val frames: List<FrameSource>,
        @JvmField val baRmsDeg: Double,
        @JvmField val coveredFraction: Double,
        @JvmField val merges: List<MergeResult>,
        /** Confidence of the recovered horizon, or -1 if levelling was not attempted. */
        @JvmField val horizonConfidence: Double,
        /** Recovered shared radial distortion, 0 when it was not solved for. */
        @JvmField val k1: Double,
        /** What the panorama's numbers mean; see RadianceScale. */
        @JvmField val radianceScale: RadianceScale,
        /**
         * The frames actually composited, gains applied - the unplaced ones dropped.
         */
        @JvmField val renderable: List<FrameSource>,
        /**
         * Which frame owns which part of the sphere, or null when seams are off.
         *
         * Carried out of the pipeline because the full-resolution output is written
         * in strips by a separate pass, and every strip has to make the same
         * decision. Solving it again per strip would disagree at the joins; solving
         * it again at full resolution would cost far more and decide nothing extra.
         * It is sampled in normalised coordinates, so one solve serves any output
         * size.
         */
        @JvmField val seamMap: PanoramaRenderer.SeamMap?
    )

    fun interface Progress {
        fun stage(name: String, fraction: Double)
    }

    @JvmStatic
    fun process(inputs: List<FrameInput>?, opt: Options, progress: Progress?): Result {
        if (inputs == null || inputs.isEmpty())
            throw IllegalArgumentException("no frames to process")
        val n = inputs.size
        report(progress, "merging", 0.0)

        // 1. Merge brackets.
        val mergedArr = arrayOfNulls<MergeResult>(n)
        val mergeDone = java.util.concurrent.atomic.AtomicInteger()
        val mergeThreads = if (opt.mergeConcurrency > 0) opt.mergeConcurrency else Parallel.threads
        val restoreThreads = Parallel.threads
        try {
            Parallel.threads = mergeThreads
            Parallel.forEach(n) { i ->
                // Scoped so a deferred bracket becomes collectable the moment it has
                // been merged, rather than at the end of the stage.
                mergedArr[i] = HdrMerger.merge(inputs[i].openBracket(), opt.merge)
                report(progress, "merging", mergeDone.incrementAndGet() / n.toDouble())
            }
        } finally {
            Parallel.threads = restoreThreads
        }
        val merges = ArrayList<MergeResult>(n)
        for (i in 0 until n) merges.add(mergedArr[i]!!)

        // 2. Features.
        report(progress, "features", 0.0)
        val features = arrayOfNulls<FeatureSet>(n)
        val workingIntrinsics = arrayOfNulls<Intrinsics>(n)
        val featureDone = java.util.concurrent.atomic.AtomicInteger()
        Parallel.forEach(n) { i ->
            val det = DetectionImage.build(merges[i].radiance, opt.featureWorkingWidth)
            val fc = FastCornerDetector.Config()
            fc.threshold = opt.fastThreshold
            fc.maxFeatures = opt.maxFeaturesPerFrame
            features[i] = FeatureSet.describe(det.image, FastCornerDetector.detect(det.image, fc))
            workingIntrinsics[i] = inputs[i].intrinsics.scaled(det.scale)
            report(progress, "features", featureDone.incrementAndGet() / n.toDouble())
        }

        // 3. Pairwise rotations.
        report(progress, "matching", 0.0)
        val mc = BriefMatcher.Config()
        val totalPairs = n * (n - 1) / 2

        // Enumerate the pairs first, keeping each one's sequential position. The
        // RANSAC seed is derived from that position, so the result of a pair does
        // not depend on when it happens to be scheduled.
        val pairI = IntArray(totalPairs)
        val pairJ = IntArray(totalPairs)
        run {
            var k = 0
            for (i in 0 until n) for (j in i + 1 until n) { pairI[k] = i; pairJ[k] = j; k++ }
        }

        val solvedPair = arrayOfNulls<PairResult>(totalPairs)
        val pairCorr = arrayOfNulls<List<RotationBundleAdjuster.Correspondence>>(totalPairs)
        val matchDone = java.util.concurrent.atomic.AtomicInteger()

        Parallel.forEach(totalPairs) { k ->
            val i = pairI[k]
            val j = pairJ[k]
            report(progress, "matching",
                matchDone.incrementAndGet() / Math.max(1, totalPairs).toDouble())
            val matches = BriefMatcher.match(features[i]!!, features[j]!!, mc)
            if (matches.size >= opt.minPairMatches) {
                val from = ArrayList<Vec3>(matches.size)
                val to = ArrayList<Vec3>(matches.size)
                val pixelsFrom = ArrayList<DoubleArray>(matches.size)
                val pixelsTo = ArrayList<DoubleArray>(matches.size)
                for (m in matches) {
                    val pa = features[i]!!.keypoints[m.a]
                    val pb = features[j]!!.keypoints[m.b]
                    from.add(workingIntrinsics[i]!!.unproject(pa.x.toDouble(), pa.y.toDouble()))
                    to.add(workingIntrinsics[j]!!.unproject(pb.x.toDouble(), pb.y.toDouble()))
                    pixelsFrom.add(doubleArrayOf(pa.x.toDouble(), pa.y.toDouble()))
                    pixelsTo.add(doubleArrayOf(pb.x.toDouble(), pb.y.toDouble()))
                }
                val ransac = RotationSolver.ransac(from, to,
                    Math.toRadians(opt.ransacThresholdDeg), opt.ransacIterations,
                    opt.seed + 31L * (k + 1))
                if (ransac != null && ransac.inlierCount >= opt.minPairInliers) {
                    solvedPair[k] = PairResult(i, j, matches.size, ransac.inlierCount,
                        ransac.rotation)
                    val cs = ArrayList<RotationBundleAdjuster.Correspondence>()
                    for (m in from.indices)
                        if (ransac.inliers[m])
                            cs.add(RotationBundleAdjuster.Correspondence(
                                i, j, from[m], to[m], 1.0,
                                if (opt.solveDistortion) pixelsFrom[m] else null,
                                if (opt.solveDistortion) pixelsTo[m] else null))
                    pairCorr[k] = cs
                }
            }
        }

        // Assembled in pair order, never in completion order: the spanning tree and
        // the bundle adjustment both depend on it.
        val pairs = ArrayList<PairResult>()
        val correspondences = ArrayList<RotationBundleAdjuster.Correspondence>()
        for (k in 0 until totalPairs) {
            val pr = solvedPair[k] ?: continue
            pairs.add(pr)
            pairCorr[k]?.let { correspondences.addAll(it) }
        }

        // 4. Initial poses from a maximum spanning tree over the pair graph.
        report(progress, "aligning", 0.0)
        val rotationsInit = arrayOfNulls<Mat3>(n)
        val placed = BooleanArray(n)
        initialiseRotations(inputs, pairs, rotationsInit, placed)
        var rotations = Array(n) { rotationsInit[it]!! }

        // 5. Global refinement.
        val bo = RotationBundleAdjuster.Options()
        bo.huberRad = Math.toRadians(opt.baHuberDeg)
        bo.priorWeight = opt.priorWeight
        bo.fixFirst = true
        // Distortion is estimated at the working resolution the features were
        // detected at, but radial coefficients live in normalised image
        // coordinates, so the value carries straight over to full resolution.
        bo.solveDistortion = opt.solveDistortion && correspondences.isNotEmpty()
        if (bo.solveDistortion) bo.distortionIntrinsics = Array(n) { workingIntrinsics[it]!! }
        val priors = collectPriors(inputs)
        var baRms = 0.0
        var k1 = 0.0
        if (correspondences.isNotEmpty() || (priors != null && opt.priorWeight > 0)) {
            val ba = RotationBundleAdjuster.solve(rotations, correspondences, priors, bo)
            rotations = ba.rotations
            baRms = Math.toDegrees(ba.rmsErrorRad)
            k1 = ba.k1
        }
        // Without a prior the gauge is whatever frame came first; recover gravity
        // from the frames' own horizontal axes and level the panorama on it.
        var horizonConfidence = -1.0
        if (opt.levelHorizon && (priors == null || opt.priorWeight <= 0)) {
            val placedRotations = ArrayList<Mat3>()
            for (i in 0 until n) if (placed[i]) placedRotations.add(rotations[i])
            if (placedRotations.isNotEmpty()) {
                val horizon = HorizonEstimator.estimate(placedRotations)
                horizonConfidence = horizon.confidence
                if (horizon.confidence >= 0.1) {
                    val level = horizon.levelingRotation()
                    for (i in 0 until n) rotations[i] = level.mul(rotations[i]).orthonormalized()
                }
            }
        }
        report(progress, "aligning", 1.0)

        // 6. Photometric alignment and compositing.
        report(progress, "blending", 0.0)
        val frames = ArrayList<FrameSource>(n)
        for (i in 0 until n) {
            val optics = if (k1 != 0.0) inputs[i].intrinsics.withDistortion(k1, 0.0, 0.0)
                         else inputs[i].intrinsics
            frames.add(FrameSource(merges[i].radiance, optics,
                rotations[i], confidenceOf(merges[i], opt.confidenceSnrReference), 1.0))
        }
        var gains = DoubleArray(n)
        Arrays.fill(gains, 1.0)
        if (opt.solvePhotometric && pairs.isNotEmpty()) {
            val samples = samplePairs(frames, pairs, opt)
            if (samples.isNotEmpty()) {
                gains = PhotometricAligner.solveGainsRobust(n, samples, 1e-4, 4)
                for (i in 0 until n) frames[i] = frames[i].withGain(gains[i])
            }
        }

        // Frames that never connected would be composited at an arbitrary pose,
        // which is worse than leaving a hole: drop them unless a prior placed them.
        var renderable = ArrayList<FrameSource>()
        for (i in 0 until n) if (placed[i]) renderable.add(frames[i])
        if (renderable.isEmpty()) renderable = frames

        val rc = PanoramaRenderer.Config()
        rc.width = opt.panoramaWidth
        rc.featherPx = opt.featherPx
        rc.cosinePower = opt.cosinePower
        rc.seamWidth = if (opt.seamWidth >= 0) opt.seamWidth
                       else autoSeamWidth(opt.panoramaWidth)
        rc.seamFeather = opt.seamFeather
        val seamMap = PanoramaRenderer.buildSeamMap(renderable, rc)
        val rendered = PanoramaRenderer.renderRows(renderable, rc, 0,
            com.immineal.hdri360.core.pano.Equirect.heightFor(rc.width), seamMap)
        report(progress, "blending", 1.0)

        return Result(rendered.panorama, rendered.coverage, rotations, gains, placed,
            pairs, frames, baRms, rendered.coveredFraction(), merges, horizonConfidence, k1,
            opt.radianceScale, renderable, seamMap)
    }

    /**
     * Chains pairwise rotations outward from the best-connected frame, always
     * taking the strongest remaining edge. Growing along high-inlier edges first
     * keeps a single bad pair from dragging a whole branch out of place before
     * the bundle adjustment ever sees it.
     */
    private fun initialiseRotations(inputs: List<FrameInput>, pairs: List<PairResult>,
                                    rotations: Array<Mat3?>, placed: BooleanArray) {
        val n = rotations.size
        val sorted = ArrayList(pairs)
        sorted.sortWith { p, q -> Integer.compare(q.inliers, p.inliers) }

        var root = 0
        if (sorted.isNotEmpty()) root = sorted[0].a
        rotations[root] = inputs[root].priorRotation ?: Mat3.IDENTITY
        placed[root] = true

        var grew = true
        while (grew) {
            grew = false
            for (p in sorted) {
                // The pair solve maps bearings in a's camera frame to b's:
                // b_cam = relative * a_cam, so R_b = R_a * relative^T.
                if (placed[p.a] && !placed[p.b]) {
                    rotations[p.b] = rotations[p.a]!!.mul(p.relative.transpose()).orthonormalized()
                    placed[p.b] = true
                    grew = true
                } else if (placed[p.b] && !placed[p.a]) {
                    rotations[p.a] = rotations[p.b]!!.mul(p.relative).orthonormalized()
                    placed[p.a] = true
                    grew = true
                }
            }
        }

        for (i in 0 until n) {
            if (rotations[i] != null) continue
            val prior = inputs[i].priorRotation
            rotations[i] = prior ?: Mat3.IDENTITY
            if (prior != null) placed[i] = true
        }
        // Put the gauge on frame 0 so results are reproducible and comparable.
        if (rotations[0] == null) rotations[0] = Mat3.IDENTITY
    }

    private fun collectPriors(inputs: List<FrameInput>): Array<Mat3>? {
        val priors = arrayOfNulls<Mat3>(inputs.size)
        var any = false
        for (i in inputs.indices) {
            priors[i] = inputs[i].priorRotation
            if (priors[i] != null) any = true
        }
        if (!any) return null
        for (i in priors.indices) if (priors[i] == null) priors[i] = Mat3.IDENTITY
        return Array(priors.size) { priors[it]!! }
    }

    /**
     * Per-pixel blending confidence, from the merge's own error estimate.
     *
     * The merge weight sum w is the inverse variance of the estimate, so the
     * *relative* precision of a pixel is E * sqrt(w). Using w directly would be
     * a trap: a bright pixel has more absolute noise and hence a lower w, so a
     * raw-weight confidence quietly distrusts exactly the highlights an HDRI is
     * captured for. Relative precision instead saturates to full confidence for
     * anything decently exposed and falls off only where the data really is
     * thin - deep shadow, or a highlight past the top of the bracket.
     */
    private fun confidenceOf(merge: MergeResult, referenceSnr: Double): FloatArray? {
        val w = merge.weight
        val radiance = merge.radiance
        val pixels = radiance.width * radiance.height
        val out = FloatArray(pixels)
        var any = false
        for (i in 0 until pixels) {
            val e = luminanceOfPixel(radiance, i)
            val snr = e * Math.sqrt(Math.max(0.0, w[i].toDouble()))
            var c = Math.min(1.0, snr / Math.max(1e-6, referenceSnr))
            if ((merge.flags[i].toInt() and MergeResult.FLAG_SATURATED) != 0) c *= 0.15
            out[i] = Math.max(1e-4, c).toFloat()
            if (out[i] > 0.5f) any = true
        }
        return if (any) out else null
    }

    private fun luminanceOfPixel(img: ImageF, pixel: Int): Double {
        val c = img.channels
        if (c < 3) return img.data[pixel * c].toDouble()
        return (ImageOps.LUMA_R * img.data[pixel * c] +
                ImageOps.LUMA_G * img.data[pixel * c + 1] +
                ImageOps.LUMA_B * img.data[pixel * c + 2]).toDouble()
    }

    /** Radiance samples in the overlap of each solved pair, for the gain solve. */
    private fun samplePairs(frames: List<FrameSource>, pairs: List<PairResult>,
                            opt: Options): List<PhotometricAligner.Sample> {
        val out = ArrayList<PhotometricAligner.Sample>()
        val rng = Random(opt.seed)
        for (p in pairs) {
            val a = frames[p.a]
            val b = frames[p.b]
            var taken = 0
            var attempts = 0
            while (taken < opt.photometricSamplesPerPair &&
                   attempts < opt.photometricSamplesPerPair * 40) {
                attempts++
                // Sample away from the frame edges, where vignetting and distortion bite hardest.
                val u = a.intrinsics.width * (0.2 + 0.6 * rng.nextDouble())
                val v = a.intrinsics.height * (0.2 + 0.6 * rng.nextDouble())
                val world = a.rotation.mul(a.intrinsics.unproject(u, v))
                val cam = b.rotation.mulTranspose(world)
                val q = b.intrinsics.project(cam) ?: continue
                if (q[0] < b.intrinsics.width * 0.15 || q[0] > b.intrinsics.width * 0.85) continue
                if (q[1] < b.intrinsics.height * 0.15 || q[1] > b.intrinsics.height * 0.85) continue

                val va = luminance(a.radiance, u, v)
                val vb = luminance(b.radiance, q[0], q[1])
                if (!(va > 1e-6) || !(vb > 1e-6)) continue
                out.add(PhotometricAligner.Sample(p.a, p.b, va, vb, 1.0))
                taken++
            }
        }
        return out
    }

    private fun luminance(img: ImageF, u: Double, v: Double): Double {
        if (img.channels < 3) return img.sampleBilinear(u, v, 0).toDouble()
        return (ImageOps.LUMA_R * img.sampleBilinear(u, v, 0) +
                ImageOps.LUMA_G * img.sampleBilinear(u, v, 1) +
                ImageOps.LUMA_B * img.sampleBilinear(u, v, 2)).toDouble()
    }

    /** A seam grid a quarter of the output, kept inside sensible bounds and even. */
    @JvmStatic
    fun autoSeamWidth(panoramaWidth: Int): Int {
        val w = Math.max(128, Math.min(1024, panoramaWidth / 4))
        return w - (w % 2)
    }

    /** Progress can now be reported from several threads; serialise it for callers. */
    private fun report(p: Progress?, stage: String, fraction: Double) {
        if (p == null) return
        synchronized(p) { p.stage(stage, fraction) }
    }

    /** Convenience for callers that just want the frames in capture order. */
    @JvmStatic
    fun inputsOf(brackets: List<List<Exposure>>, k: Intrinsics,
                 priors: List<Mat3>?): List<FrameInput> {
        val out = ArrayList<FrameInput>()
        for (i in brackets.indices)
            out.add(FrameInput(brackets[i], k, priors?.get(i), "frame$i"))
        return Collections.unmodifiableList(out)
    }
}
