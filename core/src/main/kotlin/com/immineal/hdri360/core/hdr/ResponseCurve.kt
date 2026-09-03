package com.immineal.hdri360.core.hdr

/**
 * Maps an encoded pixel value in [0,1] to relative linear radiance.
 *
 * Only the non-RAW path needs this. It is stored as a monotone lookup table
 * rather than a parametric gamma because the tone curve a phone bakes into its
 * JPEGs is not a gamma and not the same at both ends.
 */
class ResponseCurve private constructor(private val lut: DoubleArray) {

    init {
        if (lut.size < 2) throw IllegalArgumentException("response LUT too short")
    }

    /** Encoding function to invert; must be monotone increasing on [0,1]. */
    fun interface Encode { fun apply(linear: Double): Double }

    fun size(): Int = lut.size

    fun lut(): DoubleArray = lut.copyOf()

    fun toLinear(encoded: Double): Double {
        val x = if (encoded <= 0) 0.0 else (if (encoded >= 1) 1.0 else encoded)
        val t = x * (lut.size - 1)
        val i = t.toInt()
        if (i >= lut.size - 1) return lut[lut.size - 1]
        val f = t - i
        return lut[i] + (lut[i + 1] - lut[i]) * f
    }

    fun isIdentity(): Boolean = lut.size == 2 && lut[0] == 0.0 && lut[1] == 1.0

    companion object {
        @JvmStatic
        fun linear() = ResponseCurve(doubleArrayOf(0.0, 1.0))

        @JvmStatic
        fun fromLut(lut: DoubleArray) = ResponseCurve(lut.copyOf())

        @JvmStatic
        fun fromFunction(n: Int, encode: Encode): ResponseCurve {
            val lut = DoubleArray(n)
            for (i in 0 until n) {
                val target = i / (n - 1).toDouble()
                var lo = 0.0
                var hi = 1.0
                for (it in 0 until 60) {
                    val mid = 0.5 * (lo + hi)
                    if (encode.apply(mid) < target) lo = mid else hi = mid
                }
                lut[i] = 0.5 * (lo + hi)
            }
            return ResponseCurve(lut)
        }
    }
}
