package com.immineal.hdri360.core.pano

import com.immineal.hdri360.core.math.Vec3

/**
 * Equirectangular (latitude/longitude) parameterisation of the sphere, 2:1.
 *
 * World frame: right-handed, +Y up, +Z the reference heading. Longitude
 * increases to the viewer's right, which in this frame is -X. Latitude runs from
 * +90 degrees on the top row to -90 on the bottom.
 *
 * Pixel coordinates follow the rest of the pipeline: pixel i is centred at i, so
 * the canvas spans [-0.5, w-0.5].
 */
object Equirect {

    @JvmStatic
    fun heightFor(width: Int): Int = width / 2

    private fun checkAspect(width: Int, height: Int) {
        if (width != 2 * height)
            throw IllegalArgumentException(
                "equirectangular canvas must be 2:1, got " + width + "x" + height)
    }

    @JvmStatic
    fun direction(u: Double, v: Double, width: Int, height: Int): Vec3 {
        checkAspect(width, height)
        val lon = ((u + 0.5) / width) * 2 * Math.PI - Math.PI
        val lat = Math.PI / 2 - ((v + 0.5) / height) * Math.PI
        val cosLat = Math.cos(lat)
        return Vec3(-Math.sin(lon) * cosLat, Math.sin(lat), Math.cos(lon) * cosLat)
    }

    /** Latitude of a row, in radians. */
    @JvmStatic
    fun latitudeOf(v: Double, height: Int): Double = Math.PI / 2 - ((v + 0.5) / height) * Math.PI

    /**
     * sin and cos of every column's longitude, interleaved as {sin, cos} pairs.
     *
     * A render that walks one frame at a time visits each output pixel once per
     * frame, and recomputing two transcendentals per visit costs more than the
     * projection itself. The values are exactly what [direction] computes, so a
     * render built on these is bit for bit a render built on that.
     */
    @JvmStatic
    fun longitudeTable(width: Int): DoubleArray {
        val out = DoubleArray(2 * width)
        for (x in 0 until width) {
            val lon = ((x + 0.5) / width) * 2 * Math.PI - Math.PI
            out[2 * x] = Math.sin(lon)
            out[2 * x + 1] = Math.cos(lon)
        }
        return out
    }

    @JvmStatic
    fun pixel(dir: Vec3, width: Int, height: Int): DoubleArray {
        checkAspect(width, height)
        val d = dir.normalized()
        val lon = Math.atan2(-d.x, d.z)
        val lat = Math.asin(Math.max(-1.0, Math.min(1.0, d.y)))
        val u = ((lon + Math.PI) / (2 * Math.PI)) * width - 0.5
        val v = ((Math.PI / 2 - lat) / Math.PI) * height - 0.5
        return doubleArrayOf(u, v)
    }

    /** Solid angle of a single pixel in the given row. Needed for any energy-preserving resampling. */
    @JvmStatic
    fun rowSolidAngle(y: Int, width: Int, height: Int): Double {
        checkAspect(width, height)
        val latTop = Math.PI / 2 - (y / height.toDouble()) * Math.PI
        val latBot = Math.PI / 2 - ((y + 1) / height.toDouble()) * Math.PI
        return (2 * Math.PI / width) * (Math.sin(latTop) - Math.sin(latBot))
    }

    /** Longitude wrapped into [-w/2, w/2) - used when measuring distances across the seam. */
    @JvmStatic
    fun wrapDeltaU(du: Double, width: Int): Double {
        var d = du % width
        if (d > width / 2.0) d -= width
        if (d < -width / 2.0) d += width
        return d
    }
}
