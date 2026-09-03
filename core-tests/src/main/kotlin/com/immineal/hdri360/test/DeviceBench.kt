package com.immineal.hdri360.test

import com.immineal.hdri360.core.camera.Intrinsics
import com.immineal.hdri360.core.hdr.Exposure
import com.immineal.hdri360.core.image.ImageF
import com.immineal.hdri360.core.image.ImageOps
import com.immineal.hdri360.core.math.Mat3
import com.immineal.hdri360.core.math.Vec3
import com.immineal.hdri360.core.pano.CaptureTarget
import com.immineal.hdri360.core.pano.Equirect
import com.immineal.hdri360.core.pipeline.HdriPipeline
import java.util.Locale
import java.util.Random

/**
 * What the pipeline actually costs on the phone.
 *
 * Runs on the device's own runtime with no Android dependencies at all, so it
 * measures the core's compute rather than anything about an app around it.
 * Synthesises a capture rather than reading files: the point is the arithmetic,
 * and file IO would only add noise.
 *
 *   CLASSPATH=suite.dex app_process64 / com.immineal.hdri360.test.DeviceBench [frames] [width] [pano]
 */
object DeviceBench {

    @JvmStatic
    fun main(args: Array<String>) {
        val wantFrames = if (args.isNotEmpty()) args[0].toInt() else 16
        val frameWidth = if (args.size > 1) args[1].toInt() else 500
        val panoWidth = if (args.size > 2) args[2].toInt() else 1024
        val rungs = if (args.size > 3) args[3].toInt() else 3
        if (args.size > 4) com.immineal.hdri360.core.Parallel.threads = args[4].toInt()

        println(String.format(Locale.US,
            "%d frames x %d exposures, %d px working, %d px panorama, %d threads",
            wantFrames, rungs, frameWidth, panoWidth,
            com.immineal.hdri360.core.Parallel.threads))

        val r = Random(20260904)
        val frameHeight = frameWidth * 3 / 4
        val k = Intrinsics.fromHorizontalFov(frameWidth, frameHeight, 62.0)

        // Two rings with a guaranteed 40% overlap, which is what the capture plan
        // aims for. Taking an arbitrary slice of a real plan instead gives frames
        // that do not overlap, and then the benchmark measures a pipeline that
        // never had a chance to match anything.
        val hfov = k.horizontalFovDeg()
        val vfov = k.verticalFovDeg()
        val perRing = Math.ceil(360.0 / (hfov * 0.6)).toInt()
        val poses = ArrayList<Mat3>()
        var ring = 0
        while (poses.size < wantFrames) {
            val pitch = (if (ring % 2 == 0) 1 else -1) * ((ring + 1) / 2) * vfov * 0.6
            for (j in 0 until perRing) {
                if (poses.size >= wantFrames) break
                val yaw = -180.0 + 360.0 * j / perRing
                poses.add(CaptureTarget.lookingAt(CaptureTarget.directionFor(yaw, pitch)).rotation)
            }
            ring++
        }

        val ladder = DoubleArray(rungs) { 1.0 / 4000 * Math.pow(8.0, it.toDouble()) }
        var t0 = System.nanoTime()
        val inputs = ArrayList<HdriPipeline.FrameInput>()
        for (R in poses) {
            val radiance = renderView(k, R)
            val bracket = ArrayList<Exposure>()
            for (e in ladder) {
                val f = ImageF(k.width, k.height, 3)
                for (p in f.data.indices) {
                    val clean = Math.min(1.0, radiance.data[p] * e)
                    val sigma = Math.sqrt(1e-4 * clean + 4e-6)
                    f.data[p] = Math.max(0.0, Math.min(1.0, clean + r.nextGaussian() * sigma)).toFloat()
                }
                bracket.add(Exposure(f, e, 1.0))
            }
            inputs.add(HdriPipeline.FrameInput(bracket, k, null, "f" + inputs.size))
        }
        println(String.format(Locale.US, "  synthesised in %.2f s", (System.nanoTime() - t0) / 1e9))

        val opt = HdriPipeline.Options()
        opt.panoramaWidth = panoWidth
        opt.featureWorkingWidth = Math.min(600, frameWidth)
        opt.seed = 99
        if (args.size > 5) opt.seamWidth = args[5].toInt()

        // Time each stage from the progress callback: the pipeline reports a stage
        // name as it enters it, so the gaps between first reports are the costs.
        val stageStart = HashMap<String, Long>()
        val stageEnd = HashMap<String, Long>()
        val order = ArrayList<String>()
        val progress = HdriPipeline.Progress { name, _ ->
            val now = System.nanoTime()
            if (!stageStart.containsKey(name)) { stageStart[name] = now; order.add(name) }
            stageEnd[name] = now
        }

        t0 = System.nanoTime()
        val res = HdriPipeline.process(inputs, opt, progress)
        val total = (System.nanoTime() - t0) / 1e9

        var placed = 0
        for (b in res.placed) if (b) placed++
        println(String.format(Locale.US,
            "  placed %d/%d, %d pairs, BA %.4f deg, coverage %.1f%%, k1 %.5f",
            placed, inputs.size, res.pairs.size, res.baRmsDeg, res.coveredFraction * 100, res.k1))

        println("  stage breakdown:")
        for (j in order.indices) {
            val name = order[j]
            val end = if (j + 1 < order.size) stageStart[order[j + 1]]!! else stageEnd[name]!!
            val secs = (end - stageStart[name]!!) / 1e9
            println(String.format(Locale.US, "    %-10s %6.2f s  %4.1f%%",
                name, secs, 100 * secs / total))
        }
        println(String.format(Locale.US, "  TOTAL      %6.2f s", total))
    }

    /**
     * The environment as a function of direction rather than a stored image.
     *
     * Storing one large enough to out-resolve the frames rendered from it needs
     * hundreds of megabytes, which the device will not give a command-line
     * process - and a blurry environment would make the benchmark measure
     * matching that had nothing to match. Evaluating it per sample sidesteps
     * both: detail at every scale, and no buffer at all.
     */
    private fun radianceAt(d: Vec3): Double {
        val lat = Math.asin(Math.max(-1.0, Math.min(1.0, d.y)))
        val lon = Math.atan2(-d.x, d.z)
        // Value noise from a hash, not sums of sin*cos products. Those are smooth
        // and full of saddles; FAST wants corners, and a scene built from smooth
        // undulation gives it almost nothing to detect.
        var tex = 0.0
        var amp = 1.0
        var scale = 12.0
        var norm = 0.0
        for (octave in 0 until 5) {
            tex += amp * valueNoise(lon * scale, lat * scale, octave)
            norm += amp
            amp *= 0.6
            scale *= 2.3
        }
        tex /= norm
        val base = 20.0 * Math.pow(10.0, 1.2 * Math.sin(lat))
        val detail = 0.25 + 1.5 * tex * tex
        // One small very bright source, so the scene is genuinely high range.
        val toSun = d.dot(Vec3(0.42, 0.55, 0.72).normalized())
        val sun = 5e3 * Math.exp(-Math.pow(Math.acos(Math.min(1.0, toSun)) / 0.04, 2.0))
        return base * detail + sun
    }

    private fun renderView(k: Intrinsics, rotation: Mat3): ImageF {
        val out = ImageF(k.width, k.height, 3)
        for (y in 0 until k.height) for (x in 0 until k.width) {
            val d = rotation.mul(k.unproject(x.toDouble(), y.toDouble()))
            val v = radianceAt(d)
            out.set(x, y, 0, (v * 1.05).toFloat())
            out.set(x, y, 1, v.toFloat())
            out.set(x, y, 2, (v * 0.92).toFloat())
        }
        return out
    }

    /** Interpolated hash noise: cheap, deterministic, and full of corners. */
    private fun valueNoise(x: Double, y: Double, seed: Int): Double {
        val xi = Math.floor(x).toInt()
        val yi = Math.floor(y).toInt()
        val fx = x - xi
        val fy = y - yi
        // Smoothstep between lattice values, so the field is continuous while its
        // structure stays sharp enough for a corner detector to bite on.
        val sx = fx * fx * (3 - 2 * fx)
        val sy = fy * fy * (3 - 2 * fy)
        val a = hash(xi, yi, seed)
        val b = hash(xi + 1, yi, seed)
        val c = hash(xi, yi + 1, seed)
        val d = hash(xi + 1, yi + 1, seed)
        val top = a + (b - a) * sx
        val bot = c + (d - c) * sx
        return top + (bot - top) * sy
    }

    private fun hash(x: Int, y: Int, seed: Int): Double {
        var h = x * 374761393 + y * 668265263 + seed * 2147483647
        h = (h xor (h ushr 13)) * 1274126177
        return ((h xor (h ushr 16)) and 0x7FFFFF) / 8388607.0
    }
}
