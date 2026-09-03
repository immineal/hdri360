package com.immineal.hdri360.test

/**
 * Are java.lang.Math's transcendentals bit-identical between this machine and
 * the phone?
 *
 * Only StrictMath is required to be; Math is allowed 1-2 ulp and may use
 * whatever the platform provides. If they differ, a comparison sitting exactly
 * on a boundary can fall the other way, and the suite's assertion count - which
 * is partly data-driven - can shift by one without anything being wrong.
 */
object MathParity {
    @JvmStatic
    fun main(args: Array<String>) {
        var mathDiffers = 0
        var strictDiffers = 0
        val sb = StringBuilder()
        for (i in 0 until 400) {
            val x = 0.01 + i * 0.017
            for ((name, m, s) in listOf(
                Triple("pow", Math.pow(10.0, x), StrictMath.pow(10.0, x)),
                Triple("exp", Math.exp(x), StrictMath.exp(x)),
                Triple("log", Math.log(x), StrictMath.log(x)),
                Triple("sin", Math.sin(x), StrictMath.sin(x)),
                Triple("atan2", Math.atan2(x, 1.7), StrictMath.atan2(x, 1.7)))) {
                if (java.lang.Double.doubleToRawLongBits(m) !=
                    java.lang.Double.doubleToRawLongBits(s)) {
                    mathDiffers++
                    if (mathDiffers <= 3) sb.append("  $name($x): Math=$m Strict=$s\n")
                }
            }
        }
        println("Math vs StrictMath on this platform: $mathDiffers of 2000 differ")
        print(sb)
        // A fingerprint of Math's own results, to compare between platforms.
        var h = 1125899906842597L
        for (i in 0 until 400) {
            val x = 0.01 + i * 0.017
            for (v in doubleArrayOf(Math.pow(10.0, x), Math.exp(x), Math.log(x),
                                    Math.sin(x), Math.atan2(x, 1.7))) {
                h = 31 * h + java.lang.Double.doubleToRawLongBits(v)
            }
        }
        println("Math fingerprint:       $h")
        var hs = 1125899906842597L
        for (i in 0 until 400) {
            val x = 0.01 + i * 0.017
            for (v in doubleArrayOf(StrictMath.pow(10.0, x), StrictMath.exp(x),
                                    StrictMath.log(x), StrictMath.sin(x),
                                    StrictMath.atan2(x, 1.7))) {
                hs = 31 * hs + java.lang.Double.doubleToRawLongBits(v)
            }
        }
        println("StrictMath fingerprint: $hs")

        // What would cross-platform determinism cost? These are the functions the
        // hot loops actually call: Equirect.direction is sin/cos per output pixel
        // per frame, angleTo is atan2, and the detection image is a log per pixel.
        val n = 3_000_000
        for (round in 0 until 2) {
            var acc = 0.0
            var t0 = System.nanoTime()
            for (i in 0 until n) {
                val x = 0.001 + i * 1e-7
                acc += Math.sin(x) + Math.cos(x) + Math.atan2(x, 1.3) + Math.log(x)
            }
            val fast = (System.nanoTime() - t0) / 1e9
            t0 = System.nanoTime()
            for (i in 0 until n) {
                val x = 0.001 + i * 1e-7
                acc += StrictMath.sin(x) + StrictMath.cos(x) +
                       StrictMath.atan2(x, 1.3) + StrictMath.log(x)
            }
            val strict = (System.nanoTime() - t0) / 1e9
            if (round == 1) println(
                "sin+cos+atan2+log x %d:  Math %.3f s   StrictMath %.3f s   (%.2fx)  [%g]"
                    .format(n, fast, strict, strict / fast, acc))
        }
    }
}
