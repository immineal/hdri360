package com.immineal.hdri360.ui

import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.immineal.hdri360.core.capture.SphereLibrary
import com.immineal.hdri360.device.ScreenPose
import com.immineal.hdri360.core.image.ImageF
import com.immineal.hdri360.core.hdr.ToneMapper
import com.immineal.hdri360.core.io.ExrReader
import com.immineal.hdri360.core.io.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Looking at what was captured, in the space it was captured in.
 *
 * The slider is a real exposure change over linear radiance, not a brightness
 * control over a picture: sweeping it is how you see that the window really did
 * come out of the same file as the shadow under the desk. A viewer that could
 * not do that would be hiding the only thing this app is for.
 */
class ReviewActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dir = intent.getStringExtra(EXTRA_DIR)?.let { File(it) }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize(), color = Color.Black,
                        contentColor = Color(0xFFECECEC)) {
                    if (dir == null || !dir.isDirectory) {
                        Text("That capture is no longer on the phone.", Modifier.padding(24.dp))
                        return@Surface
                    }
                    ReviewScreen(dir, ::export, ::deleteBundle, ::bundleBytes, ::rename)
                }
            }
        }
    }

    /**
     * Copies the EXR somewhere the user can actually reach.
     *
     * The capture lives in the app's own storage so nothing else can half-delete
     * it mid-write; getting it out is an explicit act.
     */
    private fun export(dir: File): String {
        val source = File(dir, "panorama.exr")
        if (!source.isFile) return "There is no panorama in this capture yet"
        return try {
            // Called what the person called it. A folder of files named
            // capture-1788604964095.exr is a folder nobody can use, and the
            // sphere's name is the only thing that says which room it is.
            val library = SphereLibrary(dir.parentFile ?: dir)
            val name = (library.entryFor(dir)?.let { SphereLibrary.fileNameFor(it) }
                ?: dir.name) + ".exr"
            val uri = if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "image/x-exr")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val u = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return "The system would not accept a new file"
                contentResolver.openOutputStream(u)?.use { out -> source.inputStream().use { it.copyTo(out) } }
                contentResolver.update(u, ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }, null, null)
                u
            } else {
                @Suppress("DEPRECATION")
                val downloads = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS)
                val target = File(downloads, name)
                source.copyTo(target, overwrite = true)
                android.net.Uri.fromFile(target)
            }
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "image/x-exr"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Share the sphere"))
            "Saved to Downloads as $name"
        } catch (e: Exception) {
            "Could not export it: ${e.message}"
        }
    }

    /** The RAW bundle is the largest thing here and the first thing worth dropping. */
    /**
     * Lets the source frames go, through the same tested path the library uses.
     *
     * This used to be its own loop over the directory, which meant two places
     * deciding what a capture is allowed to lose and only one of them refusing to
     * strip the frames off a capture that has no sphere yet.
     */
    private fun deleteBundle(dir: File): String {
        val freed = SphereLibrary(dir.parentFile ?: dir).deleteRawFrames(dir)
        return when {
            freed < 0 -> "There is no sphere yet, so these frames are the only copy"
            freed == 0L -> "There is no DNG bundle in this capture"
            else -> "Deleted the DNG bundle, ${freed / (1024 * 1024)} MB"
        }
    }

    /** What the frames still cost, for the button that offers to remove them. */
    private fun bundleBytes(dir: File): Long =
        SphereLibrary(dir.parentFile ?: dir).entryFor(dir)?.rawBytes ?: 0L

    /** Names or clears the name of the sphere being looked at. */
    private fun rename(dir: File, name: String?): Boolean =
        SphereLibrary(dir.parentFile ?: dir).rename(dir, name)

    companion object {
        const val EXTRA_DIR = "dir"
    }
}

@androidx.compose.runtime.Composable
private fun ReviewScreen(dir: File, onExport: (File) -> String, onDeleteBundle: (File) -> String,
                         onBundleBytes: (File) -> Long,
                         onRename: (File, String?) -> Boolean) {
    var view by remember { mutableStateOf<PanoramaView?>(null) }
    var stops by remember { mutableStateOf(0f) }
    var loaded by remember { mutableStateOf<ImageF?>(null) }
    var status by remember { mutableStateOf("") }
    var report by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf(SphereLibrary(dir.parentFile ?: dir)
        .entryFor(dir)?.name) }
    var naming by remember { mutableStateOf(false) }
    // Gigabytes must not go on one tap. It was a plain button, and the frames it
    // removes are the only thing that would let this sphere be stitched again.
    var droppingRaw by remember { mutableStateOf(false) }
    // Looking around by turning the phone. Off to begin with: somebody who opened
    // a sphere sitting on a sofa should not have it swing away from them, and the
    // first thing anyone does is drag.
    var follow by remember { mutableStateOf(false) }

    LaunchedEffect(dir) {
        val pair = withContext(Dispatchers.IO) {
            val f = File(dir, "viewer.exr")
            val image = if (f.isFile) try { ExrReader.read(f.readBytes()) } catch (e: Exception) { null }
                        else null
            val text = File(dir, "report.json").takeIf { it.isFile }
                ?.let { try { summarise(it.readText()) } catch (e: Exception) { null } }
            Pair(image, text)
        }
        loaded = pair.first
        report = pair.second
        // Open where the picture is legible, not at "as captured".
        //
        // Zero stops means the radiance numbers go to the tone curve unscaled,
        // and those numbers are in whatever units the scene happened to have -
        // a room whose log-average sits near 1.0 arrives three stops into the
        // curve's shoulder, which is washed out and colourless. The JPEG preview
        // has always been keyed to middle grey; the viewer now opens on the same
        // exposure, and the slider moves from there.
        pair.first?.let { stops = (Math.log(ToneMapper.autoKey(it)) / Math.log(2.0)).toFloat() }
        if (pair.first == null) status = "This capture has no viewable panorama"
    }
    LaunchedEffect(loaded) { loaded?.let { view?.show(it) } }
    LaunchedEffect(stops) { view?.exposureStops = stops.toDouble() }

    // The sensor runs only while it is wanted, and stops when the screen or the
    // switch does. A rotation vector left registered behind a paused activity is
    // a sensor draining a battery for a view nobody is looking at.
    val context = LocalContext.current
    val pose = remember { ScreenPose(context) { m -> view?.onDevicePose(m) } }
    val available = remember { pose.isAvailable() }
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle, follow) {
        val v = view
        v?.followDevice = follow
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> if (follow) pose.start()
                Lifecycle.Event.ON_PAUSE -> pose.stop()
                else -> {}
            }
        }
        // Auto-rotate has to be held still while the phone is the window.
        //
        // The pose this view is driven by is the pose of the *screen*, and
        // deliberately takes no account of display rotation - see
        // OrientationMath.screenToWorld. Which is right, until the launcher
        // swings the whole UI ninety degrees underneath it because the person
        // tilted the phone to look at the sky: the drawing then disagrees with
        // the maths by exactly that quarter turn, and the sphere lurches.
        //
        // Locked rather than pinned to portrait, so somebody who starts looking
        // in landscape keeps landscape.
        val activity = context as? android.app.Activity
        if (follow) {
            activity?.requestedOrientation =
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED
            pose.start()
        } else {
            activity?.requestedOrientation =
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            pose.stop()
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose {
            lifecycle.lifecycle.removeObserver(observer)
            pose.stop()
            activity?.requestedOrientation =
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            AndroidView(factory = { ctx ->
                PanoramaView(ctx).also { v -> view = v; loaded?.let { v.show(it) } }
            }, modifier = Modifier.fillMaxSize())
            if (loaded == null)
                Text(status.ifEmpty { "Loading" }, Modifier.align(Alignment.Center))
        }

        Column(Modifier.fillMaxWidth().padding(16.dp)
            .verticalScroll(rememberScrollState())) {
            // The name goes at the top, because a sphere you are looking at is the
            // moment you know what to call it - and a name asked for while
            // somebody is still standing in the room being captured is a name
            // nobody enters.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name ?: "Unnamed sphere",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (name == null) Color(0xFF9A9A9A) else Color(0xFFECECEC),
                    modifier = Modifier.weight(1f))
                TextButton({ naming = true }) { Text(if (name == null) "Name it" else "Rename") }
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(String.format(Locale.US, "exposure  %+.1f stops", stops),
                    fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                    modifier = Modifier.weight(1f))
                // Only offered where the phone can do it, rather than as a
                // button that does nothing on a device without the sensor.
                if (available)
                    TextButton({ follow = !follow }) {
                        Text(if (follow) "Stop following" else "Turn to look")
                    }
                // Pinching in and losing the horizon is easy, and with a sphere
                // that has holes in it there may be nothing in view to steer by.
                TextButton({ view?.resetView() }) { Text("Reset view") }
            }
            Slider(stops, { stops = it }, valueRange = -12f..12f, steps = 95)
            report?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                    color = Color(0xFFB0B0B0))
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button({ status = onExport(dir) }) { Text("Export EXR") }
                // Re-read after each change rather than held: the button has to
                // disappear once the frames are gone, and it is the only thing on
                // this screen that says how much of the phone this sphere is using.
                val raw = remember(status) { onBundleBytes(dir) }
                if (raw > 0) {
                    OutlinedButton({ droppingRaw = true }) {
                        Text("Delete DNGs (${raw / (1024 * 1024)} MB)")
                    }
                }
            }
            if (status.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(status, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (droppingRaw) {
        AlertDialog(
            onDismissRequest = { droppingRaw = false },
            title = { Text("Delete the raw frames?") },
            text = {
                Text("The sphere, its report and its preview stay. What goes is the " +
                     "DNG bundle it was made from, so it cannot be re-processed at a " +
                     "different size or with a later version of the stitcher.")
            },
            confirmButton = {
                TextButton({ droppingRaw = false; status = onDeleteBundle(dir) }) {
                    Text("Delete the frames")
                }
            },
            dismissButton = { TextButton({ droppingRaw = false }) { Text("Keep them") } })
    }

    if (naming) {
        var typed by remember { mutableStateOf(name ?: "") }
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text(if (name == null) "Name this sphere" else "Rename") },
            text = {
                OutlinedTextField(typed, { typed = it },
                    label = { Text("Where was this?") },
                    placeholder = { Text("Kitchen, morning") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton({
                    if (onRename(dir, typed)) name = SphereLibrary.clean(typed)
                    else status = "That name could not be saved"
                    naming = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton({ naming = false }) { Text("Cancel") } })
    }
}

/** The parts of report.json a person would actually want on screen. */
private fun summarise(json: String): String {
    val o = Json.parse(json)
    val sb = StringBuilder()
    fun line(label: String, value: String) = sb.append(label).append("  ").append(value).append('\n')
    line("size", "${o["width"].asDouble().toInt()} x ${o["height"].asDouble().toInt()}")
    line("frames", "${o["framesPlaced"].asDouble().toInt()} of " +
        "${o["framesTotal"].asDouble().toInt()} placed")
    line("residual", String.format(Locale.US, "%.4f deg", o["bundleResidualDeg"].asDouble()))
    line("range", String.format(Locale.US, "%.1f stops", o["dynamicRangeStops"].asDouble()))
    line("covered", String.format(Locale.US, "%.1f%%", 100 * o["coveredFraction"].asDouble()))
    if (o["absoluteScale"].asBoolean())
        line("peak", String.format(Locale.US, "%.0f cd/m2", o["maxLuminanceCdPerM2"].asDouble()))
    else
        sb.append("scale  relative: ").append(o["radianceBasis"].asString()).append('\n')
    return sb.toString().trim()
}
