package com.immineal.hdri360.device

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.immineal.hdri360.core.Parallel
import com.immineal.hdri360.core.capture.FrameStore
import com.immineal.hdri360.core.hdr.ToneMapper
import com.immineal.hdri360.core.io.ExrWriter
import com.immineal.hdri360.core.pipeline.Calibration
import com.immineal.hdri360.core.pipeline.FrameSpool
import com.immineal.hdri360.core.pipeline.HdriPipeline
import com.immineal.hdri360.core.pipeline.OutputWriter
import com.immineal.hdri360.core.pipeline.ResolutionOption
import com.immineal.hdri360.core.pipeline.StoredCapture
import com.immineal.hdri360.core.pipeline.WorkEstimator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Builds the sphere, in the foreground, with a real estimate attached.
 *
 * A foreground service rather than a background thread because this runs for
 * minutes on a hot phone with the screen off, and anything less gets killed
 * partway through - which on the predecessor meant losing the whole capture,
 * since nothing was written until the end. Here the frames are already safely on
 * disk, so the worst case is that the processing has to be started again.
 */
class ProcessingService : Service() {

    /** Everything the screen and the notification need. */
    class State(
        @JvmField val active: Boolean = false,
        @JvmField val stage: String = "",
        @JvmField val fraction: Double = 0.0,
        @JvmField val remainingText: String = "",
        @JvmField val finished: Boolean = false,
        @JvmField val directory: File? = null,
        @JvmField val error: String? = null
    )

    private val running = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val path = intent?.getStringExtra(EXTRA_DIR)
        val width = intent?.getIntExtra(EXTRA_WIDTH, 8192) ?: 8192
        if (path == null) { stopSelf(); return START_NOT_STICKY }
        if (!running.compareAndSet(false, true)) return START_NOT_STICKY

        startForegroundSafely()
        Thread({ run(File(path), width) }, "hdri-processing").apply { isDaemon = false }.start()
        return START_NOT_STICKY
    }

    private fun run(dir: File, width: Int) {
        val started = SystemClock.elapsedRealtime()
        var spool: FrameSpool? = null
        try {
            val store = FrameStore.open(dir)
                ?: throw IllegalStateException("that capture is not readable any more")
            val shape = shapeOf(store)
            val subsample = StoredCapture.mergingSubsampleFor(
                shape.framePixels, shape.rungs, mergeBudget())
            val workingPixels = shape.framePixels / (subsample.toLong() * subsample)
            val heapMb = Runtime.getRuntime().maxMemory() / (1024 * 1024)
            CaptureLog.log("processing ${shape.directions} directions x ${shape.rungs} rungs, " +
                "frames ${shape.framePixels} px, working at 1/$subsample " +
                "(${workingPixels / 1000} kpx/frame), output ${width}x${width / 2}, " +
                "heap $heapMb MB, merge peak " +
                "${StoredCapture.mergePeakBytes(shape.framePixels, shape.rungs, subsample) shr 20} MB")
            val inputs = StoredCapture.inputs(store, subsample = subsample)
            if (inputs.isEmpty())
                throw IllegalStateException("no direction was completely shot, so there is " +
                    "nothing to stitch")

            // Leave a core for the system; a phone that stops responding while it
            // works is a phone the user force-quits.
            Parallel.threads = Math.max(1, Runtime.getRuntime().availableProcessors() - 1)

            // The merged sphere goes to disk rather than into the heap. Thirty-two
            // directions of merged radiance is well over a gigabyte; the alternative
            // was to shrink every frame until the whole sphere fitted, which meant
            // working at an eighth of the sensor to hold data the renderer only ever
            // reads one frame at a time.
            val workDir = File(dir, WORK)
            val needed = FrameSpool.bytesNeeded(inputs.size, workingPixels, 3)
            val free = dir.usableSpace
            CaptureLog.log("parking ${inputs.size} merged frames in $workDir: " +
                "${needed shr 20} MB needed, ${free shr 20} MB free")
            if (free in 1 until needed)
                throw IllegalStateException("this sphere needs ${needed shr 20} MB of working " +
                    "space and there is only ${free shr 20} MB free")
            spool = FrameSpool(workDir, inputs.size)

            val opt = StoredCapture.optionsFor(store.session, PREVIEW_SOLVE_WIDTH)
            opt.mergedFrames = spool
            // Merging a bracket needs the whole bracket resident, so the number of
            // brackets in flight has to be bounded by memory rather than by cores.
            // Unbounded, this is where the phone died.
            opt.mergeConcurrency = mergeWorkers(shape.framePixels, shape.rungs, subsample)
            opt.priorWeight = if (store.records().any { it.pose != null }) 0.5 else 0.0
            opt.levelHorizon = opt.priorWeight == 0.0
            CaptureLog.log("merging ${opt.mergeConcurrency} brackets at a time on " +
                "${Parallel.threads} threads, prior weight ${opt.priorWeight}")

            val estimate = estimateFor(store, width)
            publish(State(true, "Starting", 0.0, remaining(estimate?.seconds ?: 0.0, 0.0)))

            val result = HdriPipeline.process(inputs, opt) { stage, fraction ->
                val f = SOLVE_SHARE * fraction
                publish(State(true, stage, f, remaining(estimate?.seconds ?: 0.0, f)))
                notify(stage, f)
            }

            publish(State(true, "Rendering at ${width}x${width / 2}", SOLVE_SHARE,
                remaining(estimate?.seconds ?: 0.0, SOLVE_SHARE)))

            val exr = File(dir, "panorama.exr")
            val tmp = File(dir, "panorama.exr.part")
            val cfg = OutputWriter.Config()
            cfg.panoramaWidth = width
            cfg.featherPx = opt.featherPx
            cfg.seamFeather = opt.seamFeather
            val stats = BufferedOutputStream(FileOutputStream(tmp), 1 shl 16).use { out ->
                OutputWriter.writeExr(out, result, cfg) { done, total ->
                    val f = SOLVE_SHARE + (1 - SOLVE_SHARE) * (done / total.toDouble())
                    publish(State(true, "Writing the sphere", f,
                        remaining(estimate?.seconds ?: 0.0, f)))
                    notify("Writing the sphere", f)
                }
            }
            if (!tmp.renameTo(exr)) throw IllegalStateException("could not finish writing $exr")
            CaptureLog.log("wrote $exr: $stats")

            writePreview(dir, result)
            val elapsed = (SystemClock.elapsedRealtime() - started) / 1000.0
            CaptureLog.log(String.format(Locale.US,
                "solved %d of %d directions, %d pairs, %.3f deg residual, k1 %.4f, " +
                "horizon %.2f, %.1f stops in %.1f s",
                result.placed.count { it }, result.placed.size, result.pairs.size,
                result.baRmsDeg, result.k1, result.horizonConfidence,
                stats.dynamicRangeStops, elapsed))
            File(dir, "report.json").writeText(
                OutputWriter.report(result, store.session, stats, width, elapsed).toString(),
                StandardCharsets.UTF_8)
            // The working frames are large and the sphere no longer needs them; the
            // DNG bundle is the user's and stays until they say otherwise.
            store.deleteWorkingFiles()
            store.close()
            File(dir, CaptureSession.DONE).writeText(
                String.format(Locale.US, "processed in %.1f s at %d px", elapsed, width))

            publish(State(false, "Done in ${Math.round(elapsed)} s", 1.0, "", true, dir))
            notifyDone(String.format(Locale.US, "%d x %d, %.1f stops, %.0f s",
                width, width / 2, stats.dynamicRangeStops, elapsed), false)
        } catch (e: Throwable) {
            Log.e(TAG, "processing failed", e)
            CaptureLog.error("processing failed", e)
            publish(State(false, "Processing stopped", 0.0, "", true, dir,
                e.message ?: e.javaClass.simpleName))
            notifyDone(e.message ?: e.javaClass.simpleName, true)
        } finally {
            // Scratch, whatever happened. Leaving it behind fills the phone, and
            // the frames it was built from are still on disk to try again from.
            try { spool?.close() } catch (e: Exception) { CaptureLog.warn("spool: " + e) }
            running.set(false)
            stopForegroundCompat()
            stopSelf()
        }
    }

    private fun writePreview(dir: File, result: HdriPipeline.Result) {
        try {
            // The pipeline already rendered the sphere at this size while solving
            // it; rendering it again would be a second full pass over every frame
            // for a picture that is already in hand.
            val small = result.panorama
            // The viewer needs linear radiance, not a picture: its exposure slider
            // is only meaningful over values the tone mapper has not yet decided.
            FileOutputStream(File(dir, "viewer.exr")).use {
                ExrWriter.write(it, small, ExrWriter.Compression.ZIPS)
            }
            val key = ToneMapper.autoKey(small)
            val display = ToneMapper.toDisplay(small, key, 2.2)
            val rgb = ToneMapper.toBytes(display)
            val pixels = IntArray(small.width * small.height)
            for (i in pixels.indices) {
                val r = rgb[i * 3].toInt() and 0xFF
                val g = rgb[i * 3 + 1].toInt() and 0xFF
                val b = rgb[i * 3 + 2].toInt() and 0xFF
                pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            val bmp = Bitmap.createBitmap(pixels, small.width, small.height, Bitmap.Config.ARGB_8888)
            FileOutputStream(File(dir, "preview.jpg")).use {
                bmp.compress(Bitmap.CompressFormat.JPEG, 92, it)
            }
            bmp.recycle()
        } catch (e: Exception) {
            // A missing preview is a cosmetic loss; the EXR is the product.
            Log.w(TAG, "no preview was written", e)
        }
    }

    /** The shape of a capture, as the store actually holds it. */
    private class Shape(val directions: Int, val rungs: Int, val framePixels: Long)

    private fun shapeOf(store: FrameStore): Shape {
        val directions = store.shotMask().count { it }
        val records = store.records()
        val rungs = Math.max(1, records.size / Math.max(1, directions))
        val pixels = records.firstOrNull()?.let { it.width.toLong() * it.height } ?: 1_000_000L
        return Shape(directions, rungs, pixels)
    }

    private fun remaining(total: Double, fraction: Double): String {
        if (total <= 0) return ""
        val left = total * (1 - fraction)
        return when {
            left < 20 -> "nearly there"
            left < 90 -> "about ${Math.round(left)} seconds left"
            else -> "about ${Math.round(left / 60)} minutes left"
        }
    }

    // ------------------------------------------------------------ notification

    private fun startForegroundSafely() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm?.getNotificationChannel(CHANNEL) == null) {
            nm?.createNotificationChannel(NotificationChannel(CHANNEL, "Processing",
                NotificationManager.IMPORTANCE_LOW))
        }
        val n = build("Preparing", 0.0)
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTIFICATION_ID, n)
    }

    /**
     * Takes the progress notification away with the service.
     *
     * Detaching left it on the shade with a full progress bar and no job behind
     * it, which is a notification that lies about the state of the phone. What is
     * worth leaving is a single line saying it finished - and that one dismisses
     * itself.
     */
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(Service.STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
    }

    private fun notify(stage: String, fraction: Double) {
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, build(stage, fraction))
    }

    /** The one line worth leaving behind, which clears itself if nobody looks. */
    private fun notifyDone(text: String, failed: Boolean) {
        val open = Intent(this, com.immineal.hdri360.ui.CaptureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = android.app.PendingIntent.getActivity(this, 0, open,
            android.app.PendingIntent.FLAG_IMMUTABLE or
            android.app.PendingIntent.FLAG_UPDATE_CURRENT)
        val b = Notification.Builder(this, CHANNEL)
            .setContentTitle(if (failed) "The sphere was not finished" else "Sphere finished")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setAutoCancel(true)
            .setOngoing(false)
            .setContentIntent(pending)
        if (Build.VERSION.SDK_INT >= 26)
            b.setTimeoutAfter(if (failed) FAILED_NOTICE_MS else DONE_NOTICE_MS)
        getSystemService(NotificationManager::class.java)?.notify(DONE_NOTIFICATION_ID, b.build())
    }

    private fun build(stage: String, fraction: Double): Notification {
        val b = Notification.Builder(this, CHANNEL)
            .setContentTitle("Building the sphere")
            .setContentText(stage)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setOnlyAlertOnce(true)
            .setOngoing(fraction < 1.0)
        b.setProgress(1000, (fraction * 1000).toInt(), false)
        return b.build()
    }

    companion object {
        private const val TAG = "Hdri360.Processing"
        private const val CHANNEL = "processing"
        private const val NOTIFICATION_ID = 1
        private const val DONE_NOTIFICATION_ID = 2
        /** How long the finished notice lingers before Android clears it. */
        private const val DONE_NOTICE_MS = 60_000L
        private const val FAILED_NOTICE_MS = 10 * 60_000L
        private const val EXTRA_DIR = "dir"
        private const val EXTRA_WIDTH = "width"

        /** Scratch directory for the merged sphere, deleted when the run ends. */
        const val WORK = "work"

        /** Solving and merging run at a working size; the output is rendered after. */
        const val PREVIEW_SOLVE_WIDTH = 2048
        const val PREVIEW_WIDTH = 2048

        /** How much of the wall clock is solve-and-merge rather than render-and-write. */
        private const val SOLVE_SHARE = 0.7

        /**
         * Share of the heap the merged sphere may occupy. The rest is for the
         * brackets being merged into it, the panorama strips, and the room the
         * runtime needs not to spend its life collecting.
         */
        private const val MERGE_HEAP_SHARE = 0.45

        /** What one bracket may cost while it is being merged. */
        private fun mergeBudget(): Long =
            (Runtime.getRuntime().maxMemory() * MERGE_HEAP_SHARE).toLong()

        /**
         * How many brackets may be merged at once.
         *
         * A worker holds the mosaic it is reading, the colour image it becomes, the
         * rungs waiting to be combined and the radiance that comes out. That is the
         * peak, it is per worker, and it does not care how big the sphere is.
         */
        private fun mergeWorkers(framePixels: Long, rungs: Int, subsample: Int): Int {
            val perWorker = Math.max(1L,
                StoredCapture.mergePeakBytes(framePixels, rungs, subsample))
            val cores = Math.max(1, Runtime.getRuntime().availableProcessors() - 1)
            return Math.max(1, Math.min(cores.toLong(), mergeBudget() / perWorker).toInt())
        }

        private val flow = MutableStateFlow(State())
        val state: StateFlow<State> get() = flow

        private fun publish(s: State) { flow.value = s }

        /** Clears a finished run so the capture screen comes back. */
        @JvmStatic
        fun acknowledge() { flow.value = State() }

        @JvmStatic
        @JvmOverloads
        fun start(context: Context, dir: File, width: Int = 8192) {
            val i = Intent(context, ProcessingService::class.java)
                .putExtra(EXTRA_DIR, dir.absolutePath)
                .putExtra(EXTRA_WIDTH, width)
            publish(State(true, "Queued", 0.0))
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i)
            else context.startService(i)
        }

        /**
         * What each output size would cost, measured on this phone.
         *
         * Runs the calibration here rather than shipping constants: the same
         * sphere is minutes on one device and an hour on another, and an estimate
         * borrowed from a different phone is worse than none.
         */
        @JvmStatic
        fun optionsFor(dir: File): List<ResolutionOption> {
            val store = FrameStore.open(dir) ?: return emptyList()
            try {
                val directions = store.shotMask().count { it }
                if (directions == 0) return emptyList()
                val records = store.records()
                val rungs = Math.max(1, records.size / Math.max(1, directions))
                val framePixels = records.firstOrNull()
                    ?.let { it.width.toLong() * it.height } ?: 1_000_000L
                // Estimate the work that will actually be done, which is at the
                // reduced size the memory allows, not the size on disk.
                val f = StoredCapture.mergingSubsampleFor(framePixels, rungs, mergeBudget())
                return WorkEstimator.resolutionOptions(directions, rungs,
                    framePixels / (f.toLong() * f), calibration())
            } finally {
                store.close()
            }
        }

        @Volatile private var cached: Calibration? = null

        private fun calibration(): Calibration {
            cached?.let { return it }
            val c = WorkEstimator.calibrate()
            cached = c
            return c
        }

        private fun estimateFor(store: FrameStore, width: Int) =
            try {
                val directions = store.shotMask().count { it }
                val records = store.records()
                val rungs = Math.max(1, records.size / Math.max(1, directions))
                val framePixels = records.firstOrNull()?.let { it.width.toLong() * it.height }
                    ?: 1_000_000L
                val f = StoredCapture.mergingSubsampleFor(framePixels, rungs, mergeBudget())
                WorkEstimator.estimate(Math.max(1, directions), rungs,
                    framePixels / (f.toLong() * f), width, calibration())
            } catch (e: Exception) {
                null
            }
    }
}
