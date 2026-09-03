package com.immineal.hdri360.test.suites

import com.immineal.hdri360.core.camera.Intrinsics
import com.immineal.hdri360.core.hdr.Exposure
import com.immineal.hdri360.core.image.ImageF
import com.immineal.hdri360.core.image.ImageOps
import com.immineal.hdri360.core.math.Mat3
import com.immineal.hdri360.core.math.SO3
import com.immineal.hdri360.core.math.Vec3
import com.immineal.hdri360.core.pano.CaptureTarget
import com.immineal.hdri360.core.pano.Equirect
import com.immineal.hdri360.core.pipeline.HdriPipeline
import com.immineal.hdri360.test.TestCase
import com.immineal.hdri360.test.TestKit
import java.util.Collections
import java.util.Random

/**
 * The whole thing, end to end, against a synthetic environment whose radiance is
 * known exactly.
 *
 * The scene deliberately spans about 17 stops with a small very bright source in
 * it, and the frames are handed to the pipeline with no orientation prior at
 * all - the hardest realistic case, and the one that applies when re-stitching
 * an ordinary folder of photographs.
 */
class PipelineSuite : TestCase {
    override fun name(): String = "pipeline"

    override fun run(t: TestKit) {
        val r = t.rng(20260903)
        val ew = 1024
        val eh = 512
        val env = environment(ew, eh, r)

        val k = Intrinsics.fromHorizontalFov(240, 180, 62.0)
        val ladder = doubleArrayOf(1.0 / 12000, 1.0 / 2000, 1.0 / 300, 1.0 / 50)

        val truth = ArrayList<Mat3>()
        for (pitch in doubleArrayOf(-14.0, 14.0)) {
            var yaw = -75.0
            while (yaw <= 75.1) {
                truth.add(CaptureTarget.lookingAt(CaptureTarget.directionFor(yaw, pitch)).rotation)
                yaw += 30
            }
        }

        val inputs = ArrayList<HdriPipeline.FrameInput>()
        for (R in truth) {
            val radiance = renderView(env, ew, eh, k, R)
            val bracket = ArrayList<Exposure>()
            for (e in ladder) {
                val frame = ImageF(k.width, k.height, 3)
                for (i in frame.data.indices) {
                    val clean = Math.min(1.0, radiance.data[i] * e)
                    val sigma = Math.sqrt(1e-4 * clean + 4e-6)
                    frame.data[i] = Math.max(0.0,
                        Math.min(1.0, clean + r.nextGaussian() * sigma)).toFloat()
                }
                bracket.add(Exposure(frame, e, 1.0))
            }
            inputs.add(HdriPipeline.FrameInput(bracket, k, null, "f" + inputs.size))
        }

        val opt = HdriPipeline.Options()
        opt.panoramaWidth = 1024
        opt.featureWorkingWidth = 240
        opt.featherPx = 25.0
        opt.seed = 99

        val t0 = System.nanoTime()
        val res = HdriPipeline.process(inputs, opt, null)
        val seconds = (System.nanoTime() - t0) / 1e9
        t.note("pipeline: " + inputs.size + " frames x " + ladder.size + " exposures in " +
                TestKit.fmt(seconds) + " s")

        // --- every frame found its place ------------------------------------
        t.eq(truth.size.toLong(), res.rotations.size.toLong(), "one pose per input frame")
        var placed = 0
        for (b in res.placed) if (b) placed++
        t.eq(truth.size.toLong(), placed.toLong(), "every frame was connected to the panorama")
        t.greaterThan(res.pairs.size.toDouble(), (truth.size - 1).toDouble(),
            "the overlap graph has more than a bare chain")

        // --- poses are right, up to the unavoidable global rotation ------------
        val align = truth[0].mul(res.rotations[0].transpose())
        var worstPose = 0.0
        for (i in truth.indices)
            worstPose = Math.max(worstPose,
                Math.toDegrees(SO3.angleBetween(truth[i], align.mul(res.rotations[i]))))
        t.lessThan(worstPose, 0.30, "recovered orientations are within a third of a degree")
        t.note("worst pose error: " + TestKit.fmt(worstPose) + " degrees, BA residual " +
                TestKit.fmt(res.baRmsDeg) + " degrees")
        t.lessThan(res.baRmsDeg, 0.3, "the bundle adjustment residual is small")

        // --- the radiance itself ------------------------------------------------
        // Compare in log space: an HDRI is judged by ratios, not by absolute levels,
        // and the pipeline's photometric gauge is arbitrary anyway.
        t.eq(opt.panoramaWidth.toLong(), res.panorama.width.toLong(),
            "panorama has the requested width")
        t.greaterThan(res.coveredFraction, 0.20, "a substantial part of the sphere is filled")

        val envLuminance = ImageOps.luminance(env)
        val scale = radianceScale(envLuminance, res, align, ew, eh)
        var sumSq = 0.0
        var n = 0
        var worstStop = 0.0
        val stopErrors = ArrayList<Double>()
        for (y in 0 until res.panorama.height) {
            for (x in 0 until res.panorama.width) {
                val i = y * res.panorama.width + x
                if (res.coverage[i] < 0.95) continue
                // The panorama lives in the pipeline's own gauge; `align` carries it
                // into the gauge the ground truth was generated in.
                val world = align.mul(Equirect.direction(x.toDouble(), y.toDouble(),
                    res.panorama.width, res.panorama.height))
                val p = Equirect.pixel(world, ew, eh)
                val want = envLuminance.sampleBilinear(p[0], p[1], 0).toDouble()
                val got = luminanceAt(res.panorama, i) * scale
                if (!(want > 1e-4) || !(got > 1e-9)) continue
                val stops = Math.log(got / want) / Math.log(2.0)
                sumSq += stops * stops
                worstStop = Math.max(worstStop, Math.abs(stops))
                stopErrors.add(Math.abs(stops))
                n++
            }
        }
        t.greaterThan(n.toDouble(), 5000.0, "enough pixels to judge the reconstruction")
        val rmsStops = Math.sqrt(sumSq / n)
        Collections.sort(stopErrors)
        val p999 = stopErrors[Math.min((stopErrors.size - 1).toDouble(),
            stopErrors.size * 0.999).toInt()]
        t.lessThan(rmsStops, 0.10, "reconstructed radiance is within a tenth of a stop RMS")
        t.lessThan(p999, 0.40, "99.9% of pixels are within four tenths of a stop")
        // The remaining handful sit on the rim of the synthetic sun, where the
        // radiance changes by about a stop per pixel: a tenth of a degree of
        // residual pose error is worth roughly that much there, so the extreme
        // is bounded rather than pinned.
        t.lessThan(worstStop, 2.0, "even the worst pixel stays within two stops")
        t.note("radiance error over " + n + " pixels: " + TestKit.fmt(rmsStops) +
                " stops RMS, 99.9th pct " + TestKit.fmt(p999) +
                ", worst " + TestKit.fmt(worstStop) + " stops")

        // --- the dynamic range survived -------------------------------------------
        // Measure captured and true range over exactly the same pixels, so this
        // tests the reconstruction rather than the choice of test scene.
        var maxRadiance = 0.0
        var minRadiance = Double.MAX_VALUE
        var envMax = 0.0
        var envMin = Double.MAX_VALUE
        for (y in 0 until res.panorama.height) {
            for (x in 0 until res.panorama.width) {
                val i = y * res.panorama.width + x
                if (res.coverage[i] < 0.95) continue
                val got = luminanceAt(res.panorama, i) * scale
                val world = align.mul(Equirect.direction(x.toDouble(), y.toDouble(),
                    res.panorama.width, res.panorama.height))
                val p = Equirect.pixel(world, ew, eh)
                val v = envLuminance.sampleBilinear(p[0], p[1], 0).toDouble()
                if (!(v > 0) || !(got > 1e-9)) continue
                maxRadiance = Math.max(maxRadiance, got)
                minRadiance = Math.min(minRadiance, got)
                envMax = Math.max(envMax, v)
                envMin = Math.min(envMin, v)
            }
        }
        val capturedStops = Math.log(maxRadiance / minRadiance) / Math.log(2.0)
        val truthStops = Math.log(envMax / envMin) / Math.log(2.0)
        t.greaterThan(truthStops, 10.0, "the test scene really is high dynamic range")
        t.near(truthStops, capturedStops, 0.5,
            "the reconstruction neither loses nor invents dynamic range")
        t.note("dynamic range: " + TestKit.fmt(capturedStops) + " stops captured vs " +
                TestKit.fmt(truthStops) + " stops present")

        // --- gains are sane ----------------------------------------------------------
        for (g in res.gains) t.check(g > 0.5 && g < 2.0,
            "photometric gains stay near unity for one exposure series")

        // --- with orientation priors and no usable overlap ------------------------------
        val priorOnly = ArrayList<HdriPipeline.FrameInput>()
        for (i in 0 until 3) {
            val blank = ImageF(k.width, k.height, 3)
            blank.fill(0.2f)
            val bracket = ArrayList<Exposure>()
            bracket.add(Exposure(blank, 1.0 / 500, 1.0))
            priorOnly.add(HdriPipeline.FrameInput(bracket, k, truth[i], "p$i"))
        }
        val po = HdriPipeline.Options()
        po.panoramaWidth = 256
        po.priorWeight = 1.0
        val pres = HdriPipeline.process(priorOnly, po, null)
        for (i in 0 until 3)
            t.lessThan(Math.toDegrees(SO3.angleBetween(truth[i], pres.rotations[i])), 1e-6,
                "a featureless frame falls back on its orientation prior")

        // --- validation ------------------------------------------------------------------
        t.throwsException({ HdriPipeline.process(ArrayList(), opt, null) },
            "processing nothing is an error")
    }

    private fun luminanceAt(img: ImageF, pixel: Int): Double {
        val c = img.channels
        if (c < 3) return img.data[pixel * c].toDouble()
        return (ImageOps.LUMA_R * img.data[pixel * c] +
                ImageOps.LUMA_G * img.data[pixel * c + 1] +
                ImageOps.LUMA_B * img.data[pixel * c + 2]).toDouble()
    }

    /** Median ratio between reconstruction and truth, since the radiance gauge is arbitrary. */
    private fun radianceScale(lum: ImageF, res: HdriPipeline.Result, align: Mat3,
                              ew: Int, eh: Int): Double {
        val ratios = ArrayList<Double>()
        var y = 0
        while (y < res.panorama.height) {
            var x = 0
            while (x < res.panorama.width) {
                val i = y * res.panorama.width + x
                if (res.coverage[i] >= 0.95) {
                    val world = align.mul(Equirect.direction(x.toDouble(), y.toDouble(),
                        res.panorama.width, res.panorama.height))
                    val p = Equirect.pixel(world, ew, eh)
                    val want = lum.sampleBilinear(p[0], p[1], 0).toDouble()
                    val got = luminanceAt(res.panorama, i)
                    if (want > 1e-3 && got > 1e-9) ratios.add(want / got)
                }
                x += 3
            }
            y += 3
        }
        if (ratios.isEmpty()) return 1.0
        Collections.sort(ratios)
        return ratios[ratios.size / 2]
    }

    /** Textured, high-dynamic-range environment: features to track and a sun to clip on. */
    private fun environment(w: Int, h: Int, r: Random): ImageF {
        val noise = ImageF(w, h, 1)
        for (i in noise.data.indices) noise.data[i] = r.nextDouble().toFloat()
        val texture = ImageOps.gaussianBlur(noise, 1.4)
        val lo = ImageOps.min(texture)
        val hi = ImageOps.max(texture)

        val env = ImageF(w, h, 3)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val tex = (texture.data[y * w + x] - lo) / Math.max(1e-6f, hi - lo)
                val lat = Math.PI / 2 - ((y + 0.5) / h) * Math.PI
                // sky brighter than ground
                val base = 20.0 * Math.pow(10.0, 1.2 * Math.sin(lat))
                val detail = 0.25 + 1.5 * tex * tex
                val sun = 5e3 * Math.exp(-(sq(x - w * 0.62) + sq(y - h * 0.34)) / 30.0)
                val value = base * detail + sun
                env.set(x, y, 0, (value * 1.05).toFloat())
                env.set(x, y, 1, value.toFloat())
                env.set(x, y, 2, (value * 0.92).toFloat())
            }
        }
        return env
    }

    private fun sq(v: Double): Double = v * v

    private fun renderView(env: ImageF, ew: Int, eh: Int, k: Intrinsics, rotation: Mat3): ImageF {
        val out = ImageF(k.width, k.height, 3)
        for (y in 0 until k.height)
            for (x in 0 until k.width) {
                val d = rotation.mul(k.unproject(x.toDouble(), y.toDouble()))
                val p = Equirect.pixel(d, ew, eh)
                for (c in 0 until 3) out.set(x, y, c, env.sampleBilinear(p[0], p[1], c))
            }
        return out
    }
}
