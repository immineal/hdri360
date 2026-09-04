package com.immineal.hdri360.device

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Everything needed to work out what went wrong, in one file the user can send.
 *
 * The app has no network permission and collects nothing, which is the right
 * default and also means there is no way to see a failure that happened on
 * someone else's phone unless they can hand it over deliberately. So the bundle
 * is built on request, written where a person can find it - Downloads - and
 * offered to the share sheet.
 *
 * What goes in is what answers questions: the log, what the device and its
 * cameras are, and each capture's own bookkeeping - which directions were shot,
 * at which exposures, what the solve reported. What stays out is the imagery: the
 * RAW frames are gigabytes and are nobody's business but the owner's, and the
 * only picture included is the preview the app already made.
 */
object Diagnostics {

    private const val MAX_CAPTURES = 5

    /**
     * Builds the bundle in the cache directory.
     *
     * Never throws for a missing piece: a bundle with a hole in it is still worth
     * far more than an error message where a bundle should have been.
     */
    fun build(context: Context): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val out = File(context.cacheDir, "hdri360-diagnostics-$stamp.zip")
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            text(zip, "device.txt", deviceReport(context))
            CaptureLog.file()?.let { copy(zip, "capture.log", it) }
            val root = File(context.filesDir, "captures")
            val dirs = (root.listFiles() ?: emptyArray())
                .filter { it.isDirectory }
                .sortedByDescending { it.lastModified() }
                .take(MAX_CAPTURES)
            text(zip, "captures.txt", captureIndex(root, dirs))
            for (d in dirs) {
                for (name in arrayOf("session.json", "frames.jsonl", "report.json", "preview.jpg")) {
                    val f = File(d, name)
                    if (f.isFile && f.length() < 8L * 1024 * 1024) copy(zip, "${d.name}/$name", f)
                }
            }
        }
        return out
    }

    /**
     * Copies the bundle where the user can reach it without a cable, and returns
     * a content URI for the share sheet.
     *
     * Downloads rather than the app's own storage: an app-private file is exactly
     * the file a person cannot attach to an email.
     */
    fun publish(context: Context, bundle: File): Uri? {
        if (Build.VERSION.SDK_INT >= 29) {
            return try {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, bundle.name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = context.contentResolver
                    .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
                context.contentResolver.openOutputStream(uri)?.use { sink ->
                    FileInputStream(bundle).use { it.copyTo(sink) }
                }
                context.contentResolver.update(uri,
                    ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                uri
            } catch (e: Exception) {
                CaptureLog.warn("could not put the diagnostics bundle in Downloads", e)
                null
            }
        }
        // Below Android 10 there is no MediaStore Downloads to write to without a
        // storage permission this app does not ask for. The external app directory
        // is reachable over USB and by a file manager, which is enough.
        return try {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return null
            val copy = File(dir, bundle.name)
            FileInputStream(bundle).use { input -> copy.outputStream().use { input.copyTo(it) } }
            Uri.fromFile(copy)
        } catch (e: Exception) {
            CaptureLog.warn("could not copy the diagnostics bundle out", e)
            null
        }
    }

    fun shareIntent(uri: Uri): Intent = Intent(Intent.ACTION_SEND)
        .setType("application/zip")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, "360 HDRI Camera diagnostics")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    /** Where the bundle ended up, in words a person can act on. */
    fun describe(uri: Uri?): String = when {
        uri == null -> "could not be saved; the share sheet still has it"
        Build.VERSION.SDK_INT >= 29 -> "saved to Downloads"
        else -> "saved to the app's folder on internal storage"
    }

    // ------------------------------------------------------------------ content

    private fun deviceReport(context: Context): String {
        val b = StringBuilder()
        val rt = Runtime.getRuntime()
        b.append("360 HDRI Camera diagnostics\n")
        b.append("taken ").append(Date()).append('\n')
        b.append("device ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append(" (").append(Build.DEVICE).append(")\n")
        b.append("android ").append(Build.VERSION.RELEASE)
            .append(" API ").append(Build.VERSION.SDK_INT)
            .append(", build ").append(Build.DISPLAY).append('\n')
        b.append("cores ").append(rt.availableProcessors())
            .append(", heap max ").append(rt.maxMemory() shr 20).append(" MB")
            .append(", in use ").append((rt.totalMemory() - rt.freeMemory()) shr 20).append(" MB\n")
        b.append("free space ").append(context.filesDir.usableSpace shr 20).append(" MB\n")
        b.append('\n')
        try {
            val manager = context.getSystemService(android.hardware.camera2.CameraManager::class.java)
            for (lens in CameraProbe.lenses(manager)) {
                b.append("camera ").append(lens.cameraId).append(": ").append(lens).append('\n')
                val c = manager.getCameraCharacteristics(lens.cameraId)
                val report = CameraProbe.reportFor(c)
                b.append("  ").append(report).append('\n')
                // What sits behind a logical camera. The other lenses on a modern
                // phone are here rather than in the top level id list.
                if (Build.VERSION.SDK_INT >= 28) try {
                    for (pid in c.physicalCameraIds) {
                        val pc = manager.getCameraCharacteristics(pid)
                        b.append("    physical ").append(pid).append(": ")
                            .append(CameraProbe.reportFor(pc)).append('\n')
                    }
                } catch (e: Exception) {
                    b.append("    physical cameras unreadable: ").append(e).append('\n')
                }
                for (plan in com.immineal.hdri360.core.capture.StreamLadder.plansFor(report))
                    b.append("    ").append(plan).append('\n')
            }
        } catch (e: Exception) {
            b.append("camera probe failed: ").append(e).append('\n')
        }
        return b.toString()
    }

    private fun captureIndex(root: File, dirs: List<File>): String {
        val b = StringBuilder()
        b.append("captures under ").append(root).append('\n')
        val all = root.listFiles() ?: emptyArray()
        b.append(all.size).append(" in total, ").append(dirs.size).append(" included here\n\n")
        for (d in all.sortedByDescending { it.lastModified() }) {
            b.append(d.name).append("  ").append(Date(d.lastModified())).append('\n')
            var bytes = 0L
            var files = 0
            d.walkTopDown().forEach { if (it.isFile) { bytes += it.length(); files++ } }
            b.append("  ").append(files).append(" files, ").append(bytes shr 20).append(" MB\n")
            for (f in (d.listFiles() ?: emptyArray()).sortedBy { it.name })
                b.append("  ").append(if (f.isDirectory) "dir " else "    ")
                    .append(f.name).append("  ").append(f.length()).append('\n')
        }
        return b.toString()
    }

    private fun text(zip: ZipOutputStream, name: String, content: String) {
        try {
            zip.putNextEntry(ZipEntry(name))
            zip.write(content.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        } catch (e: Exception) {
            CaptureLog.warn("diagnostics: could not write $name", e)
        }
    }

    private fun copy(zip: ZipOutputStream, name: String, file: File) {
        try {
            zip.putNextEntry(ZipEntry(name))
            FileInputStream(file).use { it.copyTo(zip) }
            zip.closeEntry()
        } catch (e: Exception) {
            CaptureLog.warn("diagnostics: could not include $name", e)
        }
    }
}
