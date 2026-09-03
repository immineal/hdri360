package com.immineal.hdri360.core.io

/**
 * IEEE 754 binary16 conversion.
 *
 * OpenEXR's native pixel type. Half gives 11 bits of mantissa across a 5-bit
 * exponent, which is about 0.05% relative accuracy over 30 stops - far more than
 * a camera sensor delivers, at half the file size of float.
 */
object Half {

    /** @return the 16 bits of the half representation, in the low half of the int. */
    @JvmStatic
    fun fromFloat(value: Float): Short {
        val bits = java.lang.Float.floatToRawIntBits(value)
        val sign = (bits ushr 16) and 0x8000
        val exp = (bits ushr 23) and 0xFF
        var mant = bits and 0x7FFFFF

        if (exp == 0xFF) {                       // Inf or NaN
            if (mant == 0) return (sign or 0x7C00).toShort()
            return (sign or 0x7C00 or (mant ushr 13) or 1).toShort()   // keep it a NaN
        }
        val unbiased = exp - 127 + 15
        if (unbiased >= 0x1F) return (sign or 0x7C00).toShort()        // overflow to infinity
        if (unbiased <= 0) {
            if (unbiased < -10) return sign.toShort()                  // underflow to zero
            // Subnormal: shift the implicit leading one back in.
            mant = mant or 0x800000
            val shift = 14 - unbiased
            val half = mant ushr shift
            val round = (mant ushr (shift - 1)) and 1
            return (sign or (half + round)).toShort()
        }
        var half = (unbiased shl 10) or (mant ushr 13)
        // Round to nearest, ties to even.
        val lost = mant and 0x1FFF
        if (lost > 0x1000 || (lost == 0x1000 && (half and 1) != 0)) half++
        return (sign or half).toShort()
    }

    @JvmStatic
    fun toFloat(h: Short): Float {
        val bits = h.toInt() and 0xFFFF
        val sign = (bits and 0x8000) shl 16
        val exp = (bits ushr 10) and 0x1F
        var mant = bits and 0x3FF

        if (exp == 0) {
            if (mant == 0) return java.lang.Float.intBitsToFloat(sign)
            // Subnormal: normalise it.
            var shift = 0
            while ((mant and 0x400) == 0) { mant = mant shl 1; shift++ }
            mant = mant and 0x3FF
            val e = 127 - 15 - shift
            return java.lang.Float.intBitsToFloat(sign or (e shl 23) or (mant shl 13))
        }
        if (exp == 0x1F) {
            return java.lang.Float.intBitsToFloat(sign or 0x7F800000 or (mant shl 13))
        }
        return java.lang.Float.intBitsToFloat(sign or ((exp - 15 + 127) shl 23) or (mant shl 13))
    }
}
