package com.immineal.hdri360.test.suites

import com.immineal.hdri360.core.image.ImageF
import com.immineal.hdri360.core.io.ExrReader
import com.immineal.hdri360.core.io.ExrWriter
import com.immineal.hdri360.core.io.Half
import com.immineal.hdri360.core.io.Json
import com.immineal.hdri360.core.io.RadianceHdrReader
import com.immineal.hdri360.core.io.RadianceHdrWriter
import com.immineal.hdri360.test.TestCase
import com.immineal.hdri360.test.TestKit
import java.io.ByteArrayOutputStream

/** File formats. An HDRI nobody's renderer can open is not an HDRI. */
class WriterSuite : TestCase {
    override fun name(): String = "writers"

    override fun run(t: TestKit) {
        nothingFiniteBecomesInfinite(t)
        val r = t.rng(24680)

        // --- half float ----------------------------------------------------
        t.eq(0x0000L, (Half.fromFloat(0f).toInt() and 0xFFFF).toLong(), "zero")
        t.eq(0x8000L, (Half.fromFloat(-0f).toInt() and 0xFFFF).toLong(), "negative zero")
        t.eq(0x3C00L, (Half.fromFloat(1f).toInt() and 0xFFFF).toLong(), "one")
        t.eq(0xC000L, (Half.fromFloat(-2f).toInt() and 0xFFFF).toLong(), "minus two")
        t.eq(0x7C00L, (Half.fromFloat(Float.POSITIVE_INFINITY).toInt() and 0xFFFF).toLong(), "infinity")
        t.check((Half.fromFloat(Float.NaN).toInt() and 0x7C00) == 0x7C00 &&
                (Half.fromFloat(Float.NaN).toInt() and 0x03FF) != 0, "NaN stays NaN")
        t.near(1.0, Half.toFloat(Half.fromFloat(1f)).toDouble(), 1e-9, "one round trips")
        t.near(65504.0, Half.toFloat(Half.fromFloat(65504f)).toDouble(), 1e-9,
            "the largest half round trips")
        t.check(Half.toFloat(Half.fromFloat(1e30f)).isInfinite(), "overflow saturates to infinity")
        t.near(0.0, Half.toFloat(Half.fromFloat(1e-30f)).toDouble(), 1e-9, "underflow flushes to zero")
        var worstHalf = 0.0
        for (i in 0 until 20000) {
            val v = (Math.pow(10.0, r.nextDouble() * 8 - 4) * (if (r.nextBoolean()) 1 else -1)).toFloat()
            val back = Half.toFloat(Half.fromFloat(v))
            worstHalf = Math.max(worstHalf, Math.abs(back - v).toDouble() / Math.abs(v).toDouble())
        }
        t.lessThan(worstHalf, 6e-4, "half precision is within its 11-bit mantissa")
        t.note("worst half round-trip error: " + TestKit.fmt(worstHalf * 100) + "%")

        // --- OpenEXR --------------------------------------------------------
        val w = 67
        val h = 43                       // deliberately not a round number
        val img = ImageF(w, h, 3)
        for (y in 0 until h)
            for (x in 0 until w) {
                img.set(x, y, 0, (0.001 * (x + 1) * (y + 1)).toFloat())
                img.set(x, y, 1, (10.0 * Math.sin(x * 0.3) * Math.sin(y * 0.2) + 12).toFloat())
                img.set(x, y, 2, (1e4 * Math.exp(
                    -((x - 30.0) * (x - 30.0) + (y - 20.0) * (y - 20.0)) / 50.0)).toFloat())
            }

        for (comp in ExrWriter.Compression.values()) {
            val out = ByteArrayOutputStream()
            ExrWriter.write(out, img, comp)
            val bytes = out.toByteArray()
            t.greaterThan(bytes.size.toDouble(), 100.0, "EXR ($comp) produced a file")
            t.eq(0x76L, (bytes[0].toInt() and 0xFF).toLong(), "EXR magic byte 0")
            t.eq(0x2fL, (bytes[1].toInt() and 0xFF).toLong(), "EXR magic byte 1")
            t.eq(0x31L, (bytes[2].toInt() and 0xFF).toLong(), "EXR magic byte 2")
            t.eq(0x01L, (bytes[3].toInt() and 0xFF).toLong(), "EXR magic byte 3")

            val back = ExrReader.read(bytes)
            t.eq(w.toLong(), back.width.toLong(), "EXR width survives $comp")
            t.eq(h.toLong(), back.height.toLong(), "EXR height survives $comp")
            t.eq(3L, back.channels.toLong(), "EXR channel count survives $comp")
            var worst = 0.0
            for (i in img.data.indices) {
                // quantisation is expected
                val want = Half.toFloat(Half.fromFloat(img.data[i]))
                worst = Math.max(worst, Math.abs(back.data[i] - want).toDouble())
            }
            t.lessThan(worst, 1e-6, "EXR pixels survive $comp exactly at half precision")
        }
        val zipped = ByteArrayOutputStream()
        ExrWriter.write(zipped, img, ExrWriter.Compression.ZIPS)
        val plain = ByteArrayOutputStream()
        ExrWriter.write(plain, img, ExrWriter.Compression.NONE)
        t.lessThan(zipped.size().toDouble(), plain.size().toDouble(),
            "ZIPS actually compresses this image")
        t.note("EXR " + w + "x" + h + ": " + plain.size() + " bytes raw, " + zipped.size() + " zipped")

        // --- Radiance .hdr ----------------------------------------------------
        val hdrOut = ByteArrayOutputStream()
        RadianceHdrWriter.write(hdrOut, img)
        val hdrBytes = hdrOut.toByteArray()
        t.check(String(hdrBytes, 0, 10, Charsets.US_ASCII).startsWith("#?RADIANCE"),
            "Radiance signature")
        val hdrBack = RadianceHdrReader.read(hdrBytes)
        t.eq(w.toLong(), hdrBack.width.toLong(), "HDR width survives")
        t.eq(h.toLong(), hdrBack.height.toLong(), "HDR height survives")
        var worstHdr = 0.0
        var i = 0
        while (i < img.data.size) {
            val mx = Math.max(img.data[i], Math.max(img.data[i + 1], img.data[i + 2])).toDouble()
            if (mx >= 1e-6) {
                for (c in 0 until 3) {
                    // RGBE shares one exponent across the three channels, so accuracy is
                    // relative to the brightest of them, not to each channel.
                    worstHdr = Math.max(worstHdr,
                        Math.abs(hdrBack.data[i + c] - img.data[i + c]).toDouble() / mx)
                }
            }
            i += 3
        }
        t.lessThan(worstHdr, 0.005, "RGBE round trip is within its shared-exponent precision")
        t.note("worst Radiance RGBE error: " + TestKit.fmt(worstHdr * 100) + "% of the pixel maximum")

        // A tiny image must still write a legal file (RLE has a minimum width).
        val tiny = ImageF(3, 2, 3)
        tiny.fill(0.5f)
        val tinyOut = ByteArrayOutputStream()
        RadianceHdrWriter.write(tinyOut, tiny)
        val tinyBack = RadianceHdrReader.read(tinyOut.toByteArray())
        t.eq(3L, tinyBack.width.toLong(), "a 3-pixel-wide HDR round trips")
        t.near(0.5, tinyBack.data[0].toDouble(), 0.005, "its values survive")

        // --- JSON -----------------------------------------------------------------
        val o = Json.Obj()
            .put("name", "capture \"one\"\n")
            .put("frames", 42L)
            .put("exposure", 0.0125)
            .put("raw", true)
            .put("rotation", doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))
        val arr = Json.Arr()
        arr.add(Json.Obj().put("iso", 50L).put("t", 1.0 / 320))
        arr.add(Json.Obj().put("iso", 400L).put("t", 1.0 / 15))
        o.put("shots", arr)
        val text = o.toString()
        val parsed = Json.parse(text)
        t.eq("capture \"one\"\n", parsed["name"].asString(), "strings survive escaping")
        t.eq(42L, parsed["frames"].asDouble().toLong(), "integers survive")
        t.near(0.0125, parsed["exposure"].asDouble(), 1e-12, "doubles survive")
        t.check(parsed["raw"].asBoolean(), "booleans survive")
        t.eq(9L, parsed["rotation"].size().toLong(), "arrays survive")
        t.near(1.0, parsed["rotation"].at(4).asDouble(), 1e-12, "array contents survive")
        t.eq(2L, parsed["shots"].size().toLong(), "nested arrays survive")
        t.near(1.0 / 15, parsed["shots"].at(1)["t"].asDouble(), 1e-12, "nested objects survive")
        t.throwsException({ Json.parse("{\"a\": }") }, "malformed JSON is rejected")
        t.throwsException({ Json.parse("{\"a\": 1") }, "truncated JSON is rejected")
    }

    /**
     * Nothing finite may become infinite on the way into the file.
     *
     * The output is half float, whose largest finite value is 65504, and the
     * conversion overflowed to infinity above it - correct as an IEEE conversion
     * and wrong as a way to write a radiance map. An offline re-stitch of a real
     * capture put forty infinite pixels in a panorama, all of them the sun, and
     * an infinity in an environment map is not a bright pixel: it is a renderer
     * that produces NaN for everything the sun touches.
     *
     * Clamped, and counted so the report can say it happened rather than the
     * value quietly changing. Above 65504 kilocandela is 65 million cd/m2, which
     * is forty times a clear sky at the sun's own disc - so in practice this only
     * ever fires on something already beyond what the capture could measure.
     */
    private fun nothingFiniteBecomesInfinite(t: TestKit) {
        t.near(65504.0, Half.MAX_FINITE.toDouble(), 0.0,
            "the largest finite half is the one the clamp is built on")
        t.check(java.lang.Float.isInfinite(Half.toFloat(Half.fromFloat(1e30f))),
            "the raw conversion still overflows, because that is what it is for")

        val im = ImageF(4, 1, 3)
        // A pixel beyond half's reach, an infinity, a NaN, and one ordinary value.
        im.data[0] = 1e30f;  im.data[1] = 70000f;      im.data[2] = 65505f
        im.data[3] = Float.POSITIVE_INFINITY
        im.data[4] = Float.NEGATIVE_INFINITY
        im.data[5] = Float.NaN
        im.data[6] = 12.5f;  im.data[7] = 0.25f;       im.data[8] = 0f
        im.data[9] = -1e30f; im.data[10] = 1000f;      im.data[11] = -3f

        val bytes = java.io.ByteArrayOutputStream()
        ExrWriter.write(bytes, im, ExrWriter.Compression.ZIPS)
        val back = ExrReader.read(bytes.toByteArray())

        for (i in back.data.indices)
            t.check(!java.lang.Float.isInfinite(back.data[i]) && !back.data[i].isNaN(),
                "no pixel in the file is infinite or NaN")

        t.near(Half.MAX_FINITE.toDouble(), back.data[0].toDouble(), 0.0,
            "a value beyond half's reach is written at its largest finite value")
        t.near(Half.MAX_FINITE.toDouble(), back.data[3].toDouble(), 0.0,
            "and so is an infinity, rather than being passed on")
        t.near(-Half.MAX_FINITE.toDouble(), back.data[4].toDouble(), 0.0,
            "in both directions")
        t.near(0.0, back.data[5].toDouble(), 0.0,
            "a NaN is written as nothing, which is what it means")
        t.near(12.5, back.data[6].toDouble(), 0.0, "an ordinary value is untouched")
        t.near(0.25, back.data[7].toDouble(), 0.0, "and so is a small one")
    }
}
