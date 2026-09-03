package com.immineal.hdri360.tools

import com.immineal.hdri360.core.camera.Intrinsics
import com.immineal.hdri360.core.hdr.Exposure
import com.immineal.hdri360.core.hdr.ToneMapper
import com.immineal.hdri360.core.image.ImageF
import com.immineal.hdri360.core.image.ImageOps
import com.immineal.hdri360.core.hdr.RadianceScale
import com.immineal.hdri360.core.io.ExrWriter
import com.immineal.hdri360.core.io.Json
import com.immineal.hdri360.core.io.RadianceHdrWriter
import com.immineal.hdri360.core.pipeline.HdriPipeline
import java.awt.image.BufferedImage
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Locale
import javax.imageio.ImageIO

/**
 * Desktop harness: re-stitches a folder of ordinary photographs into an HDRI
 * using exactly the same core the Android app runs.
 *
 * Deliberately NOT part of the app module - it uses ImageIO, which Android does
 * not have. Its only purpose is to exercise the core on real photographs.
 */
object RestitchTool {

    const val SRGB_A = 0.055

    @JvmStatic
    fun main(args: Array<String>) {
        val manifestPath = args[0]
        val outDir = args[1]
        val workingWidth = if (args.size > 2) args[2].toInt() else 750
        val panoWidth = if (args.size > 3) args[3].toInt() else 2048
        val baseIso = 50

        val text = String(Files.readAllBytes(Paths.get(manifestPath)), StandardCharsets.UTF_8)
        val manifest = Json.parse(text)
        val n = manifest.size()
        println("frames: $n   working width: $workingWidth   panorama: $panoWidth")

        val inputs = ArrayList<HdriPipeline.FrameInput>()
        val names = ArrayList<String>()
        val relExposure = DoubleArray(n)
        val t0 = System.nanoTime()
        for (i in 0 until n) {
            val row = manifest.at(i)
            val path = row["path"].asString()
            val t = row["exposure"].asDouble()
            val iso = row["iso"].asDouble().toInt()
            val focal = row["focal"].asDouble()
            val focal35 = row["focal35"].asDouble()

            val bi = ImageIO.read(File(path))
            val linear = toLinear(bi)
            val small = resizeTo(linear, workingWidth)

            // Physical sensor size implied by the 35 mm equivalent focal length.
            val cropFactor = focal35 / focal
            val diag35 = Math.hypot(36.0, 24.0)
            val sensorDiag = diag35 / cropFactor
            val aspect = bi.width / bi.height.toDouble()
            val sensorH = sensorDiag / Math.hypot(aspect, 1.0)
            val sensorW = sensorH * aspect
            val k = Intrinsics.fromSensor(small.width, small.height, sensorW, sensorH, focal)

            relExposure[i] = t * iso / baseIso.toDouble()
            val bracket = ArrayList<Exposure>()
            bracket.add(Exposure(small, relExposure[i], iso / baseIso.toDouble()))
            inputs.add(HdriPipeline.FrameInput(bracket, k, null, File(path).name))
            names.add(File(path).name)
            if (i == 0) System.out.printf(Locale.US,
                "  intrinsics: %s  (sensor %.2f x %.2f mm)%n", k, sensorW, sensorH)
        }
        System.out.printf(Locale.US, "decoded in %.1f s%n", (System.nanoTime() - t0) / 1e9)

        val opt = HdriPipeline.Options()
        opt.panoramaWidth = panoWidth
        opt.featureWorkingWidth = Math.min(600, workingWidth)
        opt.maxFeaturesPerFrame = 600
        opt.fastThreshold = 0.02
        opt.ransacThresholdDeg = 0.5
        opt.minPairMatches = 12
        opt.minPairInliers = 14
        opt.featherPx = Math.max(8.0, workingWidth * 0.06)
        opt.seed = 7
        // Unset leaves the pipeline default; "0" and "1" force it either way.
        System.getenv("HDRI360_SOLVE_K1")?.let { opt.solveDistortion = it == "1" }
        // Ordinary photographs have been through the phone's own pipeline: the
        // camera chose the exposure and applied a tone curve, so the numbers that
        // would go into the photometric arithmetic are not what they claim. The
        // result is linear and self-consistent, and it is not in cd/m2.
        opt.radianceScale = RadianceScale.relative(
            "re-stitched from processed images: exposure and tone curve were the camera's")

        val last = longArrayOf(System.nanoTime())
        val progress = HdriPipeline.Progress { stage, fraction ->
            val now = System.nanoTime()
            if (fraction >= 1.0 || now - last[0] > 3_000_000_000L) {
                last[0] = now
                System.out.printf(Locale.US, "  %-10s %5.1f%%%n", stage, fraction * 100)
            }
        }

        val t1 = System.nanoTime()
        val res = HdriPipeline.process(inputs, opt, progress)
        val seconds = (System.nanoTime() - t1) / 1e9

        System.out.printf(Locale.US, "%nstitched in %.1f s%n", seconds)
        var placed = 0
        for (b in res.placed) if (b) placed++
        println("placed frames  : $placed / $n")
        println("solved pairs   : " + res.pairs.size)
        System.out.printf(Locale.US, "BA residual    : %.4f deg%n", res.baRmsDeg)
        System.out.printf(Locale.US, "lens k1        : %.5f%s%n", res.k1,
            if (opt.solveDistortion) " (solved)" else " (assumed)")
        System.out.printf(Locale.US, "coverage       : %.1f%% of the sphere%n",
            res.coveredFraction * 100)
        println("radiance scale : " + res.radianceScale)

        println("\nper frame:")
        for (i in 0 until n) {
            var edges = 0
            var inl = 0
            for (p in res.pairs)
                if (p.a == i || p.b == i) { edges++; inl += p.inliers }
            System.out.printf(Locale.US, "  %-22s EV rel %8.5f  gain %5.3f  %2d links %4d inliers %s%n",
                names[i], relExposure[i], res.gains[i], edges, inl,
                if (res.placed[i]) "" else "  UNPLACED")
        }

        // Residual disagreement between frames after the photometric solve: how
        // consistent the exposure normalisation really turned out to be.
        System.out.printf(Locale.US, "%ngain spread    : %.3f .. %.3f (%.2f stops)%n",
            min(res.gains), max(res.gains),
            Math.log(max(res.gains) / min(res.gains)) / Math.log(2.0))

        Files.createDirectories(Paths.get(outDir))
        FileOutputStream("$outDir/panorama.exr").use { o ->
            ExrWriter.write(o, res.panorama, ExrWriter.Compression.ZIPS)
        }
        FileOutputStream("$outDir/panorama.hdr").use { o ->
            RadianceHdrWriter.write(o, res.panorama)
        }
        writePng(res.panorama, "$outDir/preview.png")
        writeCoveragePng(res.coverage, res.panorama.width, res.panorama.height,
            "$outDir/coverage.png")

        val report = Json.Obj()
            .put("frames", n.toLong()).put("placed", placed.toLong())
            .put("pairs", res.pairs.size.toLong())
            .put("baResidualDeg", res.baRmsDeg).put("coveredFraction", res.coveredFraction)
            .put("panoramaWidth", res.panorama.width.toLong()).put("seconds", seconds)
            .put("k1", res.k1)
            .put("absoluteLuminance", res.radianceScale.absolute)
            .put("radianceScaleBasis", res.radianceScale.basis)
        val poses = Json.Arr()
        for (i in 0 until n) {
            poses.add(Json.Obj().put("name", names[i])
                .put("relativeExposure", relExposure[i])
                .put("gain", res.gains[i])
                .put("placed", res.placed[i])
                .put("rotation", res.rotations[i].data()))
        }
        report.put("poses", poses)
        Files.write(Paths.get("$outDir/report.json"),
            report.toString().toByteArray(StandardCharsets.UTF_8))

        val range = radianceRange(res)
        System.out.printf(Locale.US, "radiance range : %.4g .. %.4g  (%.1f stops)%n",
            range[0], range[1], Math.log(range[1] / range[0]) / Math.log(2.0))
        println("wrote $outDir/panorama.exr, panorama.hdr, preview.png, coverage.png")
    }

    fun radianceRange(res: HdriPipeline.Result): DoubleArray {
        var lo = Double.MAX_VALUE
        var hi = 0.0
        val lum = ImageOps.luminance(res.panorama)
        for (i in lum.data.indices) {
            if (res.coverage[i] <= 0.2) continue
            val v = lum.data[i].toDouble()
            if (v <= 0) continue
            lo = Math.min(lo, v)
            hi = Math.max(hi, v)
        }
        return doubleArrayOf(lo, hi)
    }

    fun min(v: DoubleArray): Double {
        var m = Double.MAX_VALUE
        for (x in v) m = Math.min(m, x)
        return m
    }

    fun max(v: DoubleArray): Double {
        var m = 0.0
        for (x in v) m = Math.max(m, x)
        return m
    }

    /** sRGB electro-optical transfer function. */
    fun toLinear(bi: BufferedImage): ImageF {
        val w = bi.width
        val h = bi.height
        val out = ImageF(w, h, 3)
        val lut = FloatArray(256)
        for (i in 0 until 256) {
            val s = i / 255.0
            lut[i] = (if (s <= 0.04045) s / 12.92
                      else Math.pow((s + SRGB_A) / (1 + SRGB_A), 2.4)).toFloat()
        }
        val row = IntArray(w)
        for (y in 0 until h) {
            bi.getRGB(0, y, w, 1, row, 0, w)
            for (x in 0 until w) {
                val p = row[x]
                val base = (y * w + x) * 3
                out.data[base] = lut[(p shr 16) and 0xFF]
                out.data[base + 1] = lut[(p shr 8) and 0xFF]
                out.data[base + 2] = lut[p and 0xFF]
            }
        }
        return out
    }

    /** Halve until close, then bilinear the rest of the way. */
    fun resizeTo(src: ImageF, targetWidth: Int): ImageF {
        var cur = src
        while (cur.width / 2 >= targetWidth && cur.width >= 4 && cur.height >= 4)
            cur = ImageOps.downsample2x(cur)
        if (cur.width == targetWidth) return cur
        val th = Math.round(targetWidth * cur.height / cur.width.toDouble()).toInt()
        val out = ImageF(targetWidth, th, cur.channels)
        val sx = cur.width / targetWidth.toDouble()
        val sy = cur.height / th.toDouble()
        for (y in 0 until th)
            for (x in 0 until targetWidth)
                for (c in 0 until cur.channels)
                    out.set(x, y, c, cur.sampleBilinear((x + 0.5) * sx - 0.5, (y + 0.5) * sy - 0.5, c))
        return out
    }

    fun writePng(radiance: ImageF, path: String) {
        val display = ToneMapper.toDisplay(radiance, ToneMapper.autoKey(radiance), 2.2)
        val bi = BufferedImage(display.width, display.height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until display.height)
            for (x in 0 until display.width) {
                val base = (y * display.width + x) * display.channels
                val r = q(display.data[base])
                val g = q(display.data[base + 1])
                val b = q(display.data[base + 2])
                bi.setRGB(x, y, (r shl 16) or (g shl 8) or b)
            }
        ImageIO.write(bi, "png", File(path))
    }

    fun writeCoveragePng(coverage: FloatArray, w: Int, h: Int, path: String) {
        var max = 0f
        for (c in coverage) max = Math.max(max, c)
        val bi = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h)
            for (x in 0 until w) {
                val c = coverage[y * w + x] / Math.max(1e-6f, max)
                val v = q(Math.pow(c.toDouble(), 0.5).toFloat())
                bi.setRGB(x, y, if (coverage[y * w + x] <= 0) 0x400000
                                else ((v shl 16) or (v shl 8) or v))
            }
        ImageIO.write(bi, "png", File(path))
    }

    fun q(v: Float): Int = Math.max(0, Math.min(255, Math.round(v * 255)))
}
