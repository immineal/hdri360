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
import com.immineal.hdri360.core.pano.FrameOptics
import com.immineal.hdri360.core.pano.FrameSet
import com.immineal.hdri360.core.pano.FrameSource
import com.immineal.hdri360.core.pano.HorizonEstimator
import com.immineal.hdri360.core.pano.PanoramaRenderer
import com.immineal.hdri360.core.pano.PhotometricAligner
import com.immineal.hdri360.core.pano.RotationBundleAdjuster
import com.immineal.hdri360.core.pano.RotationSolver
import java.util.Arrays
import java.util.Locale
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
        /**
         * Corners per frame once the search is guided by the orientation prior.
         *
         * Five hundred is what an unguided contest can afford: every point is
         * compared against every point in the other frame, so the cost is
         * quadratic and so is the chance of a decoy winning. With the prior
         * narrowing each search to a handful of candidates, both of those go
         * away, and the extra points are what turn a sparse overlap into a
         * solvable one. On a real 34 direction sphere, at 500 the pose graph came
         * apart into 21 pieces; at 3000 it holds together in a few.
         */
        @JvmField var maxFeaturesGuided = 3000
        @JvmField var fastThreshold = 0.02
        /**
         * Corners to look for in each frame before accepting what the threshold
         * gives.
         *
         * One threshold cannot serve a whole sphere. The bright, cluttered
         * directions saturate the cap at 0.02 while a wall, a ceiling or a patch
         * of sky yields a handful or none at all - and a frame with no features
         * is tied to its neighbours by nothing, so it is placed on the
         * orientation prior and lands wherever the phone's compass thought it
         * was. Frames that fall short have the threshold halved until they reach
         * this many corners or the floor is hit; frames that already have enough
         * are left exactly as they were.
         */
        @JvmField var featureTargetCount = 150
        @JvmField var fastThresholdFloor = 0.002
        @JvmField var ransacThresholdDeg = 0.4
        @JvmField var ransacIterations = 1000
        /**
         * Use the orientation prior to decide which pairs are worth trying and
         * which matches within them are believable.
         *
         * Every pair is otherwise matched against every other, with nothing but
         * the descriptors to say whether two frames even point the same way. On a
         * real sphere that is mostly noise: of 561 pairs from 34 directions, 440
         * produced twelve or more descriptor matches and 397 of those had three
         * or fewer that any rotation could reconcile. Frames pointing in opposite
         * directions were being offered as candidates, and the genuine
         * neighbours had to win a contest against them.
         *
         * The phone already knows roughly where it was pointing for each frame,
         * to a degree or two. Two frames whose axes are further apart than their
         * own field of view cannot overlap whatever the descriptors say, and a
         * match that disagrees with the prior by more than the prior's own error
         * is not the same point seen twice.
         */
        @JvmField var usePriorForPairs = true
        /** Slack on top of the frame's own field of view before a pair is skipped. */
        @JvmField var priorOverlapMarginDeg = 12.0
        /** How far a match may disagree with the prior and still be believed. */
        @JvmField var priorMatchToleranceDeg = 12.0
        /**
         * How alike two descriptors must be to be believed when the search was
         * guided, in differing bits out of 256.
         *
         * Stricter than the unguided bar, and it has to be. The ratio test earns
         * its keep by comparing the best match against the runner up, and in a
         * neighbourhood of five candidates the runner up is usually terrible - so
         * the test passes everything and stops discriminating. Its work has to be
         * done by an absolute standard instead, or the prior quietly decides the
         * answer it was only supposed to be narrowing the search for.
         */
        @JvmField var guidedMaxDistance = 64
        /**
         * Let the prior pick the candidates, not just narrow the pairs.
         *
         * Off by default: it finds more matches on real data and it also lets the
         * prior's own error into the answer, which the end to end test catches by
         * planting a two degree gyro error and watching the solve fail to beat
         * it. Narrowing which pairs are tried is safe - it only ever removes
         * pairs that cannot overlap - and is what makes a high feature count
         * affordable, which turns out to be the larger effect anyway.
         */
        @JvmField var guidedMatching = false
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
         * Where merged directions are kept while the sphere is solved.
         *
         * Null holds them in memory, which only works when the whole sphere fits -
         * on a phone, thirty-two directions of merged radiance does not. A
         * [FrameSpool] parks them in a directory instead and the composite reads
         * them back one at a time; the numbers that come out are the same either
         * way, which the suite checks rather than assumes.
         */
        @JvmField var mergedFrames: MergedFrames? = null
        /**
         * What the output radiance means. Supplied by the caller because only it
         * knows whether the capture was a genuine measurement - the pipeline sees
         * pixels and exposures, not which capability tier produced them.
         */
        @JvmField var radianceScale: RadianceScale =
            RadianceScale.relative("no photometric calibration supplied")
        @JvmField var seed = 12345L
    }

    /** Corners found and pairs attempted, kept so a bad solve can be explained. */
    class MatchStats internal constructor(
        /** Corners described in each frame, in frame order. */
        @JvmField val featuresPerFrame: IntArray,
        @JvmField val pairsAttempted: Int,
        /** Pairs whose descriptors matched at all. */
        @JvmField val pairsWithAnyMatch: Int,
        /** Pairs that cleared the minimum match count and reached RANSAC. */
        @JvmField val pairsWithEnoughMatches: Int,
        /** Pairs a rotation was found for. The rest died at one of the two gates. */
        @JvmField val pairsSolved: Int,
        /** Connected components of the pose graph. One means every frame is tied in. */
        @JvmField val components: Int,
        /** Frames in the largest connected component. */
        @JvmField val largestComponent: Int,
        /**
         * How many geometrically consistent matches each attempted pair had,
         * bucketed. A pile at zero means the descriptors are matching noise; a
         * pile just under the acceptance bar means the bar is the problem.
         */
        @JvmField val inlierHistogram: IntArray
    ) {
        /** "0-3: 210  4-7: 88 ..." over the buckets that are not empty. */
        fun histogramLine(): String {
            val b = StringBuilder()
            for (i in inlierHistogram.indices) {
                if (inlierHistogram[i] == 0) continue
                val lo = i * 4
                b.append(lo).append('-').append(lo + 3).append(": ")
                    .append(inlierHistogram[i]).append("  ")
            }
            return b.toString().trim()
        }

        override fun toString(): String {
            val f = featuresPerFrame.sorted()
            val median = if (f.isEmpty()) 0 else f[f.size / 2]
            return String.format(Locale.US,
                "features %d..%d median %d; %d pairs tried, %d matched, %d reached ransac, " +
                "%d solved; graph in %d piece(s), largest %d",
                f.firstOrNull() ?: 0, f.lastOrNull() ?: 0, median,
                pairsAttempted, pairsWithAnyMatch, pairsWithEnoughMatches, pairsSolved,
                components, largestComponent) +
                "\n  inliers per attempted pair: " + histogramLine()
        }
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
        /**
         * Why the pose graph is the shape it is.
         *
         * A pair count on its own cannot be acted on. Whether the frames had no
         * corners to describe, or plenty that would not match, or plenty of
         * matches that no rotation could reconcile, are three different faults
         * with three different fixes - and the difference is one histogram wide.
         */
        @JvmField val matching: MatchStats,
        @JvmField val baRmsDeg: Double,
        @JvmField val coveredFraction: Double,
        /** Confidence of the recovered horizon, or -1 if levelling was not attempted. */
        @JvmField val horizonConfidence: Double,
        /** Recovered shared radial distortion, 0 when it was not solved for. */
        @JvmField val k1: Double,
        /** What the panorama's numbers mean; see RadianceScale. */
        @JvmField val radianceScale: RadianceScale,
        /**
         * The frames actually composited, gains applied - the unplaced ones dropped.
         *
         * A set rather than a list because on a phone they are not all in memory:
         * whoever writes the full-resolution output walks it the same way the
         * pipeline's own render did, opening one frame at a time.
         */
        @JvmField val renderable: FrameSet,
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

        // 1. Merge each bracket to radiance, and describe it while it is in hand.
        //
        // Detection lives in this loop rather than in one of its own because of
        // where the memory goes: a merged direction is tens of megabytes and a
        // feature set is a few kilobytes, so describing a frame before letting go
        // of it means the sphere never has to be read twice - and with the frames
        // spooled to disk, reading it twice is a real cost rather than a free one.
        val store = opt.mergedFrames ?: ResidentFrames(n)
        if (store.size != n)
            throw IllegalArgumentException("the frame store holds " + store.size +
                " frames but " + n + " were given")
        val features = arrayOfNulls<FeatureSet>(n)
        val workingIntrinsics = arrayOfNulls<Intrinsics>(n)
        val mergeDone = java.util.concurrent.atomic.AtomicInteger()
        val mergeThreads = if (opt.mergeConcurrency > 0) opt.mergeConcurrency else Parallel.threads
        val restoreThreads = Parallel.threads
        try {
            Parallel.threads = mergeThreads
            // Every frame must have a prior for the search to be guided, since a
            // frame without one still has to win an unguided contest.
            val guidedMatching = opt.usePriorForPairs && inputs.all { it.priorRotation != null }
            Parallel.forEach(n) { i ->
                // Scoped so a deferred bracket becomes collectable the moment it has
                // been merged, rather than at the end of the stage.
                val merged = HdrMerger.merge(inputs[i].openBracket(), opt.merge)
                val det = DetectionImage.build(merged.radiance, opt.featureWorkingWidth)
                val fc = FastCornerDetector.Config()
                fc.threshold = opt.fastThreshold
                fc.maxFeatures = if (guidedMatching) opt.maxFeaturesGuided
                                 else opt.maxFeaturesPerFrame
                var corners = FastCornerDetector.detect(det.image, fc)
                while (corners.size < opt.featureTargetCount &&
                       fc.threshold > opt.fastThresholdFloor) {
                    fc.threshold = Math.max(opt.fastThresholdFloor, fc.threshold / 2)
                    corners = FastCornerDetector.detect(det.image, fc)
                }
                features[i] = FeatureSet.describe(det.image, corners)
                workingIntrinsics[i] = inputs[i].intrinsics.scaled(det.scale)
                store.put(i, merged.radiance,
                    confidenceOf(merged, opt.confidenceSnrReference),
                    FrameOptics(inputs[i].intrinsics, Mat3.IDENTITY, 1.0,
                        merged.radiance.channels))
                report(progress, "merging", mergeDone.incrementAndGet() / n.toDouble())
            }
        } finally {
            Parallel.threads = restoreThreads
        }
        report(progress, "features", 1.0)

        // 2. Pairwise rotations.
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

        val inlierBuckets = IntArray(26)
        val anyMatch = java.util.concurrent.atomic.AtomicInteger()
        val enoughMatches = java.util.concurrent.atomic.AtomicInteger()
        val solvedPair = arrayOfNulls<PairResult>(totalPairs)
        val pairCorr = arrayOfNulls<List<RotationBundleAdjuster.Correspondence>>(totalPairs)
        val matchDone = java.util.concurrent.atomic.AtomicInteger()

        Parallel.forEach(totalPairs) { k ->
            val i = pairI[k]
            val j = pairJ[k]
            report(progress, "matching",
                matchDone.incrementAndGet() / Math.max(1, totalPairs).toDouble())
            if (!couldOverlap(inputs, i, j, opt)) return@forEach
            val ri0 = inputs[i].priorRotation
            val rj0 = inputs[j].priorRotation
            val guided = opt.guidedMatching && ri0 != null && rj0 != null
            val matches = if (!guided) BriefMatcher.match(features[i]!!, features[j]!!, mc)
            else {
                // Where the prior says each of i's points lands in j's pixels.
                val fa = features[i]!!
                val kj = workingIntrinsics[j]!!
                val ki = workingIntrinsics[i]!!
                val px = DoubleArray(fa.size())
                val py = DoubleArray(fa.size())
                for (q in 0 until fa.size()) {
                    val kp = fa.keypoints[q]
                    val world = ri0!!.mul(ki.unproject(kp.x.toDouble(), kp.y.toDouble()))
                    val p = kj.project(rj0!!.mulTranspose(world))
                    if (p == null) { px[q] = Double.NaN; py[q] = Double.NaN }
                    else { px[q] = p[0]; py[q] = p[1] }
                }
                // The search radius is the prior's own error, in pixels at this
                // frame's scale: anything further away is not the same point.
                val radius = Math.tan(Math.toRadians(opt.priorMatchToleranceDeg)) * kj.fx
                val gc = BriefMatcher.Config()
                gc.ratio = mc.ratio
                gc.crossCheck = mc.crossCheck
                gc.maxDistance = opt.guidedMaxDistance
                BriefMatcher.matchNear(fa, features[j]!!, px, py, radius, gc)
            }
            if (matches.isNotEmpty()) anyMatch.incrementAndGet()
            if (matches.size >= opt.minPairMatches) {
                enoughMatches.incrementAndGet()
                val from = ArrayList<Vec3>(matches.size)
                val to = ArrayList<Vec3>(matches.size)
                val pixelsFrom = ArrayList<DoubleArray>(matches.size)
                val pixelsTo = ArrayList<DoubleArray>(matches.size)
                val ri = inputs[i].priorRotation
                val rj = inputs[j].priorRotation
                val believable = if (opt.usePriorForPairs && ri != null && rj != null)
                    Math.toRadians(opt.priorMatchToleranceDeg) else Double.MAX_VALUE
                for (m in matches) {
                    val pa = features[i]!!.keypoints[m.a]
                    val pb = features[j]!!.keypoints[m.b]
                    val ba = workingIntrinsics[i]!!.unproject(pa.x.toDouble(), pa.y.toDouble())
                    val bb = workingIntrinsics[j]!!.unproject(pb.x.toDouble(), pb.y.toDouble())
                    // The prior says where a's bearing should land in b's frame.
                    // A match that disagrees by more than the prior's own error is
                    // two different points, not one seen twice.
                    if (believable < Double.MAX_VALUE) {
                        val expected = rj!!.mulTranspose(ri!!.mul(ba))
                        if (expected.angleTo(bb) > believable) continue
                    }
                    from.add(ba)
                    to.add(bb)
                    pixelsFrom.add(doubleArrayOf(pa.x.toDouble(), pa.y.toDouble()))
                    pixelsTo.add(doubleArrayOf(pb.x.toDouble(), pb.y.toDouble()))
                }
                if (from.size < opt.minPairMatches) return@forEach
                val ransac = RotationSolver.ransac(from, to,
                    Math.toRadians(opt.ransacThresholdDeg), opt.ransacIterations,
                    opt.seed + 31L * (k + 1))
                if (ransac != null) {
                    val b = Math.min(inlierBuckets.size - 1, ransac.inlierCount / 4)
                    synchronized(inlierBuckets) { inlierBuckets[b]++ }
                }
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

        // 3. Initial poses from a maximum spanning tree over the pair graph.
        report(progress, "aligning", 0.0)
        val rotationsInit = arrayOfNulls<Mat3>(n)
        val placed = BooleanArray(n)
        initialiseRotations(inputs, pairs, rotationsInit, placed)
        var rotations = Array(n) { rotationsInit[it]!! }

        // 4. Global refinement.
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

        // 5. Photometric alignment and compositing.
        report(progress, "blending", 0.0)
        val channels = store.optics(0).channels
        for (i in 0 until n) {
            val optics = if (k1 != 0.0) inputs[i].intrinsics.withDistortion(k1, 0.0, 0.0)
                         else inputs[i].intrinsics
            store.setOptics(i, FrameOptics(optics, rotations[i], 1.0, channels))
        }
        var gains = DoubleArray(n)
        Arrays.fill(gains, 1.0)
        if (opt.solvePhotometric && pairs.isNotEmpty()) {
            val samples = samplePairs(store, pairs, opt)
            if (samples.isNotEmpty()) gains = PhotometricAligner.solveGainsRobust(n, samples, 1e-4, 4)
        }
        for (i in 0 until n)
            store.setOptics(i, FrameOptics(store.optics(i).intrinsics, rotations[i],
                gains[i], channels))

        // Frames that never connected would be composited at an arbitrary pose,
        // which is worse than leaving a hole: drop them unless a prior placed them.
        var keep = IntArray(0)
        run {
            var count = 0
            for (i in 0 until n) if (placed[i]) count++
            keep = IntArray(if (count > 0) count else n)
            var w = 0
            for (i in 0 until n) if (placed[i] || count == 0) keep[w++] = i
        }
        val renderable = FrameSet.select(store, keep)

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
            pairs, matchStats(n, features, pairs, totalPairs, anyMatch.get(),
                enoughMatches.get(), inlierBuckets),
            baRms, rendered.coveredFraction(), horizonConfidence, k1,
            opt.radianceScale, renderable, seamMap)
    }

    /**
     * Chains pairwise rotations outward from the best-connected frame, always
     * taking the strongest remaining edge. Growing along high-inlier edges first
     * keeps a single bad pair from dragging a whole branch out of place before
     * the bundle adjustment ever sees it.
     */
    /** Union find over the solved pairs, so "connected" is a fact and not a hope. */
    private fun matchStats(n: Int, features: Array<FeatureSet?>, solvedPairs: List<PairResult>,
                           attempted: Int, anyMatch: Int, enough: Int,
                           inlierBuckets: IntArray): MatchStats {
        val parent = IntArray(n) { it }
        fun find(a: Int): Int {
            var x = a
            while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }
            return x
        }
        var solved = 0
        for (p in solvedPairs) {
            solved++
            val ra = find(p.a)
            val rb = find(p.b)
            if (ra != rb) parent[ra] = rb
        }
        val sizes = HashMap<Int, Int>()
        for (i in 0 until n) sizes[find(i)] = (sizes[find(i)] ?: 0) + 1
        return MatchStats(IntArray(n) { features[it]?.keypoints?.size ?: 0 },
            attempted, anyMatch, enough, solved,
            sizes.size, sizes.values.maxOrNull() ?: 0, inlierBuckets)
    }

    /**
     * Whether two frames can see any of the same sky, according to the priors.
     *
     * True whenever either prior is missing: a pair that might overlap and was
     * never tried is a hole, and holes are worse than wasted work.
     */
    private fun couldOverlap(inputs: List<FrameInput>, i: Int, j: Int, opt: Options): Boolean {
        if (!opt.usePriorForPairs) return true
        val a = inputs[i].priorRotation ?: return true
        val b = inputs[j].priorRotation ?: return true
        val axis = Vec3(0.0, 0.0, 1.0)
        val apart = Math.toDegrees(a.mul(axis).angleTo(b.mul(axis)))
        val reach = 0.5 * (inputs[i].intrinsics.horizontalFovDeg() +
                           inputs[i].intrinsics.verticalFovDeg())
        return apart <= reach + opt.priorOverlapMarginDeg
    }

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

    /**
     * Radiance samples in the overlap of each solved pair, for the gain solve.
     *
     * Positions first, pixels second. Which points get sampled is decided from
     * the geometry and the random sequence alone, so the whole sample set is
     * known before a single frame is opened - and the frames can then be read one
     * at a time, in order, instead of two at a time in pair order. On a sphere
     * that is the difference between holding two directions and holding all of
     * them.
     *
     * A sample whose radiance turns out to be zero in either frame is dropped
     * afterwards rather than replaced, because replacing it would make the choice
     * of positions depend on the pixels, which is exactly what has to be avoided.
     */
    private fun samplePairs(frames: FrameSet, pairs: List<PairResult>,
                            opt: Options): List<PhotometricAligner.Sample> {
        val rng = Random(opt.seed)
        val sampleA = ArrayList<Int>()
        val sampleB = ArrayList<Int>()
        val posA = ArrayList<DoubleArray>()
        val posB = ArrayList<DoubleArray>()
        for (p in pairs) {
            val a = frames.optics(p.a)
            val b = frames.optics(p.b)
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
                sampleA.add(p.a)
                sampleB.add(p.b)
                posA.add(doubleArrayOf(u, v))
                posB.add(q)
                taken++
            }
        }

        // One pass over the frames, filling in both ends of every sample.
        val valueA = DoubleArray(sampleA.size)
        val valueB = DoubleArray(sampleA.size)
        for (fi in 0 until frames.size) {
            var needed = false
            for (s in sampleA.indices) if (sampleA[s] == fi || sampleB[s] == fi) { needed = true; break }
            if (!needed) continue
            val f = frames.open(fi)
            for (s in sampleA.indices) {
                if (sampleA[s] == fi) valueA[s] = luminance(f.radiance, posA[s][0], posA[s][1])
                if (sampleB[s] == fi) valueB[s] = luminance(f.radiance, posB[s][0], posB[s][1])
            }
            frames.release(fi)
        }

        val out = ArrayList<PhotometricAligner.Sample>(sampleA.size)
        for (s in sampleA.indices) {
            if (!(valueA[s] > 1e-6) || !(valueB[s] > 1e-6)) continue
            out.add(PhotometricAligner.Sample(sampleA[s], sampleB[s], valueA[s], valueB[s], 1.0))
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
