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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
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
                    ReviewScreen(dir, ::export, ::deleteBundle)
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
            val name = "${dir.name}.exr"
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
    private fun deleteBundle(dir: File): String {
        val raw = File(dir, "raw")
        if (!raw.isDirectory) return "There is no DNG bundle in this capture"
        var freed = 0L
        raw.listFiles()?.forEach { freed += it.length(); it.delete() }
        raw.delete()
        return "Deleted the DNG bundle, ${freed / (1024 * 1024)} MB"
    }

    companion object {
        const val EXTRA_DIR = "dir"
    }
}

@androidx.compose.runtime.Composable
private fun ReviewScreen(dir: File, onExport: (File) -> String, onDeleteBundle: (File) -> String) {
    var view by remember { mutableStateOf<PanoramaView?>(null) }
    var stops by remember { mutableStateOf(0f) }
    var loaded by remember { mutableStateOf<ImageF?>(null) }
    var status by remember { mutableStateOf("") }
    var report by remember { mutableStateOf<String?>(null) }

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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(String.format(Locale.US, "exposure  %+.1f stops", stops),
                    fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                    modifier = Modifier.weight(1f))
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
                OutlinedButton({ status = onDeleteBundle(dir) }) { Text("Delete DNGs") }
            }
            if (status.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(status, style = MaterialTheme.typography.bodySmall)
            }
        }
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
