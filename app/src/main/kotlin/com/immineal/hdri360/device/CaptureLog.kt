package com.immineal.hdri360.device

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A local record of what the app actually did, and of anything that killed it.
 *
 * Nothing is sent anywhere - there is no network permission in the manifest at
 * all - so this is the only way a problem on someone else's phone can ever be
 * looked at: they export the file and send it themselves. It is also the only
 * way to see, after the fact, what the metering decided and what exposures a
 * bracket was actually taken at, which is the first question about any capture
 * that came out wrong.
 */
object CaptureLog {

    private const val MAX_BYTES = 512L * 1024
    private const val TAG = "Hdri360"

    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = Any()
    @Volatile private var file: File? = null

    fun start(context: Context) {
        synchronized(lock) {
            if (file != null) return
            val f = File(context.filesDir, "capture.log")
            file = f
            rotateIfLarge(f)
        }
        write("---- ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} " +
              "(API ${Build.VERSION.SDK_INT}), ${Runtime.getRuntime().availableProcessors()} cores")
        installCrashHandler()
    }

    fun log(message: String) {
        Log.i(TAG, message)
        write(message)
    }

    fun warn(message: String, cause: Throwable? = null) {
        Log.w(TAG, message, cause)
        write("WARN  $message" + (cause?.let { "\n" + stackOf(it) } ?: ""))
    }

    fun file(): File? = file

    /** Everything recorded so far, for the user to attach to a message themselves. */
    fun text(): String = try { file?.takeIf { it.isFile }?.readText() ?: "" } catch (e: Exception) { "" }

    fun clear() {
        synchronized(lock) { file?.delete() }
    }

    private fun write(line: String) {
        val f = file ?: return
        synchronized(lock) {
            try {
                java.io.FileWriter(f, true).use { it.append(stamp.format(Date()))
                    .append("  ").append(line).append('\n') }
            } catch (e: Exception) {
                // A log that throws is worse than no log.
            }
        }
    }

    /**
     * Keeps the last half megabyte rather than growing without bound. A capture
     * writes a line per bracket, so this is many sessions' worth.
     */
    private fun rotateIfLarge(f: File) {
        try {
            if (f.length() > MAX_BYTES) {
                val keep = f.readText().takeLast((MAX_BYTES / 2).toInt())
                f.writeText("---- earlier lines dropped ----\n" + keep.substringAfter('\n'))
            }
        } catch (e: Exception) {
            f.delete()
        }
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            write("CRASH on ${thread.name}\n${stackOf(error)}")
            previous?.uncaughtException(thread, error)
        }
    }

    private fun stackOf(t: Throwable): String {
        val w = StringWriter()
        t.printStackTrace(PrintWriter(w))
        return w.toString()
    }
}
