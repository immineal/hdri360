package com.immineal.hdri360.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * The small preview a finished sphere leaves behind, decoded small and once.
 *
 * Shared by the library and the start screen because both want the same picture
 * at the same size, and a library of a dozen 2048 wide previews decoded at full
 * size is tens of megabytes of bitmap for something shown at 64dp.
 */
internal object Thumbnails {

    fun decode(f: File?): Bitmap? {
        if (f == null || !f.isFile) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, bounds)
            var scale = 1
            while (bounds.outWidth / (scale * 2) >= 256) scale *= 2
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = scale })
        } catch (e: Exception) {
            null
        }
    }
}
