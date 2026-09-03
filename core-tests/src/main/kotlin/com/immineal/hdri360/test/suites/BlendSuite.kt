package com.immineal.hdri360.test.suites

import com.immineal.hdri360.core.camera.Intrinsics
import com.immineal.hdri360.core.image.ImageF
import com.immineal.hdri360.core.math.Mat3
import com.immineal.hdri360.core.math.Vec3
import com.immineal.hdri360.core.pano.CaptureTarget
import com.immineal.hdri360.core.pano.Equirect
import com.immineal.hdri360.core.pano.FrameSource
import com.immineal.hdri360.core.pano.PanoramaRenderer
import com.immineal.hdri360.test.TestCase
import com.immineal.hdri360.test.TestKit
import java.util.Arrays

/** Projecting frames onto the sphere and blending them into one radiance map. */
class BlendSuite : TestCase {
    override fun name(): String = "blend"

    override fun run(t: TestKit) {
        val k = Intrinsics.fromHorizontalFov(200, 150, 60.0)
        val cfg = PanoramaRenderer.Config()
        cfg.width = 512
        cfg.featherPx = 20.0

        // --- a partition of unity ------------------------------------------
        // Two overlapping frames of a uniform world must produce a uniform sphere:
        // any weighting error shows up immediately as a bright or dark seam.
        val uniform = ArrayList<FrameSource>()
        for (yaw in doubleArrayOf(-25.0, 25.0)) {
            val img = ImageF(k.width, k.height, 3)
            img.fill(2.5f)
            uniform.add(FrameSource(img, k,
                CaptureTarget.lookingAt(CaptureTarget.directionFor(yaw, 0.0)).rotation, null, 1.0))
        }
        val flat = PanoramaRenderer.render(uniform, cfg)
        t.eq(cfg.width.toLong(), flat.panorama.width.toLong(), "panorama width")
        t.eq((cfg.width / 2).toLong(), flat.panorama.height.toLong(), "panorama is 2:1")
        t.eq(3L, flat.panorama.channels.toLong(), "panorama is RGB")

        var worst = 0.0
        var covered = 0
        for (i in flat.coverage.indices) {
            if (flat.coverage[i] <= 0) continue
            covered++
            for (c in 0 until 3)
                worst = Math.max(worst, Math.abs(flat.panorama.data[i * 3 + c] - 2.5) / 2.5)
        }
        t.greaterThan(covered.toDouble(), 1000.0, "a meaningful area is covered")
        t.lessThan(worst, 1e-5, "blending two frames of a uniform world leaves no seam")
        t.note("worst uniformity error across the blend: " + TestKit.fmt(worst))

        // --- uncovered regions are marked, not invented -----------------------
        var zero = 0
        for (i in flat.coverage.indices) {
            if (flat.coverage[i] > 0) continue
            zero++
            for (c in 0 until 3)
                t.near(0.0, flat.panorama.data[i * 3 + c].toDouble(), 1e-9,
                    "uncovered pixels stay at zero")
        }
        t.greaterThan(zero.toDouble(), 1000.0, "most of the sphere is correctly reported as uncovered")
        t.near(1.0, zero / flat.coverage.size.toDouble() + covered / flat.coverage.size.toDouble(),
            1e-12, "every pixel is either covered or not")

        // --- geometry: what goes in comes back out -----------------------------
        // Render a known environment into camera views, then stitch those views
        // back into an environment and compare. This exercises projection,
        // sampling and blending together against ground truth.
        val ew = 512
        val eh = 256
        val env = smoothEnvironment(ew, eh)
        val vk = Intrinsics.fromHorizontalFov(320, 240, 70.0)
        val views = ArrayList<FrameSource>()
        val rots = ArrayList<Mat3>()
        var yaw = -180.0
        while (yaw < 180) {
            rots.add(CaptureTarget.lookingAt(CaptureTarget.directionFor(yaw, 0.0)).rotation)
            yaw += 45
        }
        for (R in rots) views.add(FrameSource(renderView(env, ew, eh, vk, R), vk, R, null, 1.0))

        val back = PanoramaRenderer.Config()
        back.width = ew
        back.featherPx = 25.0
        val rebuilt = PanoramaRenderer.render(views, back)

        var sumSq = 0.0
        var sumRef = 0.0
        var n = 0
        for (y in 0 until eh)
            for (x in 0 until ew) {
                val i = y * ew + x
                if (rebuilt.coverage[i] < 0.9) continue
                val want = env.get(x, y, 0).toDouble()
                val got = rebuilt.panorama.data[i * rebuilt.panorama.channels].toDouble()
                sumSq += (got - want) * (got - want)
                sumRef += want * want
                n++
            }
        t.greaterThan(n.toDouble(), 20000.0, "the equatorial band is reconstructed")
        val nrmse = Math.sqrt(sumSq / n) / Math.sqrt(sumRef / n)
        t.lessThan(nrmse, 0.02, "a round trip through camera views reconstructs the environment")
        t.note("round-trip NRMSE over " + n + " covered pixels: " + TestKit.fmt(nrmse * 100) + "%")

        // --- feathering is smooth, not a hard cut --------------------------------
        // Two frames disagreeing about the same region must produce a gradual
        // transition rather than a visible edge.
        val disagreeing = ArrayList<FrameSource>()
        for (f in 0 until 2) {
            val img = ImageF(k.width, k.height, 1)
            img.fill(if (f == 0) 1.0f else 2.0f)
            val y2 = if (f == 0) -20.0 else 20.0
            disagreeing.add(FrameSource(img, k,
                CaptureTarget.lookingAt(CaptureTarget.directionFor(y2, 0.0)).rotation, null, 1.0))
        }
        val seam = PanoramaRenderer.render(disagreeing, cfg)
        val row = seam.panorama.height / 2
        var maxJump = 0.0
        var prev = Double.NaN
        for (x in 0 until seam.panorama.width) {
            val i = row * seam.panorama.width + x
            if (seam.coverage[i] <= 0) { prev = Double.NaN; continue }
            val v = seam.panorama.data[i].toDouble()
            t.check(v >= 1.0 - 1e-6 && v <= 2.0 + 1e-6, "blended value stays between the two inputs")
            if (!prev.isNaN()) maxJump = Math.max(maxJump, Math.abs(v - prev))
            prev = v
        }
        t.lessThan(maxJump, 0.15, "the transition between disagreeing frames is gradual")
        t.note("largest step across the feathered seam: " + TestKit.fmt(maxJump))

        // --- per-frame gain is applied ---------------------------------------------
        val gained = ArrayList<FrameSource>()
        val one = ImageF(k.width, k.height, 1)
        one.fill(1.0f)
        gained.add(FrameSource(one, k, Mat3.IDENTITY, null, 3.0))
        val g = PanoramaRenderer.render(gained, cfg)
        val centre = (g.panorama.height / 2) * g.panorama.width + g.panorama.width / 2
        t.near(3.0, g.panorama.data[centre].toDouble(), 1e-5,
            "the per-frame gain multiplies the radiance")

        // --- confidence weighting -----------------------------------------------------
        val weighted = ArrayList<FrameSource>()
        for (f in 0 until 2) {
            val img = ImageF(k.width, k.height, 1)
            img.fill(if (f == 0) 1.0f else 5.0f)
            val conf = FloatArray(k.width * k.height)
            Arrays.fill(conf, if (f == 0) 1.0f else 0.0001f)
            weighted.add(FrameSource(img, k, Mat3.IDENTITY, conf, 1.0))
        }
        val w = PanoramaRenderer.render(weighted, cfg)
        t.near(1.0, w.panorama.data[centre].toDouble(), 0.01,
            "a low-confidence frame barely contributes")

        // --- validation ------------------------------------------------------------------
        t.throwsException({ PanoramaRenderer.render(ArrayList(), cfg) },
            "rendering nothing is an error")
        val odd = PanoramaRenderer.Config()
        odd.width = 511
        t.throwsException({ PanoramaRenderer.render(uniform, odd) },
            "an odd canvas width is an error")
    }

    /** Low-frequency, band-limited environment so resampling error stays measurable. */
    private fun smoothEnvironment(w: Int, h: Int): ImageF {
        val env = ImageF(w, h, 1)
        for (y in 0 until h)
            for (x in 0 until w) {
                val u = 2 * Math.PI * x / w
                val v = Math.PI * y / h
                val value = 1.5 + Math.sin(2 * u) * 0.4 + Math.cos(3 * v) * 0.3 +
                        Math.sin(u + 2 * v) * 0.25 + Math.cos(4 * u - v) * 0.15
                env.set(x, y, 0, value.toFloat())
            }
        return env
    }

    private fun renderView(env: ImageF, ew: Int, eh: Int, k: Intrinsics, rotation: Mat3): ImageF {
        val out = ImageF(k.width, k.height, 1)
        for (y in 0 until k.height)
            for (x in 0 until k.width) {
                val d = rotation.mul(k.unproject(x.toDouble(), y.toDouble()))
                val p = Equirect.pixel(d, ew, eh)
                out.set(x, y, 0, env.sampleBilinear(p[0], p[1], 0))
            }
        return out
    }
}
