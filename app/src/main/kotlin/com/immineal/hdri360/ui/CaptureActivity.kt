package com.immineal.hdri360.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Bundle
import android.view.TextureView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.immineal.hdri360.device.CaptureSession
import com.immineal.hdri360.device.CaptureUiState
import com.immineal.hdri360.device.Diagnostics
import com.immineal.hdri360.device.ProcessingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The capture screen.
 *
 * One job: point the phone where the sphere still has a hole, and say plainly
 * what this device is actually able to record. The tier line is not decoration -
 * a capture that could not be driven manually is not a radiance measurement, and
 * the user finds that out here rather than from a file that looks the same
 * either way.
 */
class CaptureActivity : ComponentActivity() {

    private lateinit var session: CaptureSession
    private var previewTexture: SurfaceTexture? = null
    private var pending by mutableStateOf<File?>(null)
    /**
     * What the user asked for, held until there is a surface to preview into.
     *
     * The camera cannot be opened before the TextureView exists: without a real
     * preview target the session configures against a detached one and the user
     * aims a sphere at a black screen.
     */
    private var wanted by mutableStateOf<Pair<String, File?>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A sphere takes minutes of aiming; the screen going out mid-capture would
        // stop the sensors and lose the pose.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        session = CaptureSession(this) { dir -> pending = dir }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                // contentColorFor(Black) is unspecified, so without this every piece
                // of text that does not set its own colour comes out black on black.
                Surface(Modifier.fillMaxSize(), color = Color.Black,
                        contentColor = Color(0xFFECECEC)) {
                    var granted by remember {
                        mutableStateOf(checkSelfPermission(Manifest.permission.CAMERA)
                            == PackageManager.PERMISSION_GRANTED)
                    }
                    val ask = rememberLauncherForPermission { granted = it }
                    val state by session.state.collectAsStateWithLifecycle()
                    val processing by ProcessingService.state.collectAsStateWithLifecycle()

                    val ready = pending
                    when {
                        !granted -> PermissionScreen { ask() }
                        processing.active || processing.finished ->
                            ProcessingScreen(processing, { dir -> openReview(dir) }) {
                                ProcessingService.acknowledge(); pending = null
                            }
                        ready != null -> ReadyScreen(ready) { width ->
                            pending = null
                            ProcessingService.start(this@CaptureActivity, ready, width)
                        }
                        state.phase == CaptureUiState.Phase.IDLE && wanted == null ->
                            StartScreen(state, ::openReview) { lens, resume ->
                                wanted = Pair(lens, resume)
                            }
                        else -> CaptureScreen(state, sensorRotation(state),
                            onSurface = { st -> onPreviewSurface(st) },
                            onFinish = { session.finish() },
                            onSkipScan = { session.finishScan() })
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // The camera belongs to whatever is in front of the user. Holding it in the
        // background is how an app becomes the reason another one cannot open it.
        if (isFinishing) session.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        session.stop()
    }

    private fun onPreviewSurface(st: SurfaceTexture?) {
        previewTexture = st
        val start = wanted ?: return
        if (st == null) return
        wanted = null
        session.start(start.first, st, start.second)
    }

    private fun openReview(dir: File) {
        startActivity(Intent(this, ReviewActivity::class.java)
            .putExtra(ReviewActivity.EXTRA_DIR, dir.absolutePath))
    }

    /** How far the sensor's frame is turned from the way the phone is being held. */
    private fun sensorRotation(state: CaptureUiState): Int {
        val sensor = state.sensorOrientationDeg
        val display = when (if (Build.VERSION.SDK_INT >= 30) display?.rotation ?: 0
                            else @Suppress("DEPRECATION") windowManager.defaultDisplay.rotation) {
            1 -> 90
            2 -> 180
            3 -> 270
            else -> 0
        }
        return ((sensor - display) % 360 + 360) % 360
    }

    @Composable
    private fun rememberLauncherForPermission(onResult: (Boolean) -> Unit): () -> Unit {
        val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(), onResult)
        return { launcher.launch(Manifest.permission.CAMERA) }
    }
}

@Composable
private fun PermissionScreen(onAsk: () -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(28.dp),
        verticalArrangement = Arrangement.Center) {
        Text("360 HDRI Camera", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text("This app needs the camera to photograph the sphere. Nothing leaves the " +
            "phone: there is no network permission in the manifest at all.",
            style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(24.dp))
        Button(onAsk) { Text("Allow the camera") }
    }
}

@Composable
private fun StartScreen(
    state: CaptureUiState,
    onOpen: (File) -> Unit,
    onStart: (String, File?) -> Unit
) {
    var lens by remember { mutableStateOf(state.chosenLens) }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp)
        .verticalScroll(rememberScrollState())) {
        Text("360 HDRI Camera", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))
        Text("A full sphere in linear radiance, bracketed automatically.",
            style = MaterialTheme.typography.bodyMedium, color = Color(0xFFB0B0B0))
        Spacer(Modifier.height(20.dp))

        if (state.lenses.isEmpty()) {
            Text("No usable camera was found on this device.", color = Color(0xFFFF8A80))
            return@Column
        }

        Text("Lens", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        for (option in state.lenses.filter { !it.frontFacing }) {
            val selected = option.cameraId == lens
            OutlinedButton(
                onClick = { lens = option.cameraId },
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                colors = if (selected)
                    ButtonDefaults.outlinedButtonColors(containerColor = Color(0x2239C36B))
                else ButtonDefaults.outlinedButtonColors()
            ) {
                Text(option.toString(), modifier = Modifier.fillMaxWidth())
            }
        }

        state.resumable?.let { dir ->
            Spacer(Modifier.height(20.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("An unfinished capture is waiting",
                        style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text("It will carry on from where it stopped, on the same exposure " +
                        "ladder, so both halves land on one radiance scale.",
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(10.dp))
                    Button({ lens?.let { onStart(it, dir) } }) { Text("Resume it") }
                }
            }
        }

        state.finished?.let { dir ->
            Spacer(Modifier.height(20.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("The last sphere is ready",
                        style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text("Look around it, move the exposure through the whole range, " +
                        "and export the EXR from there.",
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(10.dp))
                    Button({ onOpen(dir) }) { Text("Open it") }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Button({ lens?.let { onStart(it, null) } }, Modifier.fillMaxWidth()) {
            Text("Start a new sphere")
        }
        if (state.phase == CaptureUiState.Phase.FAILED) {
            Spacer(Modifier.height(16.dp))
            Text(state.message, color = Color(0xFFFF8A80),
                style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(28.dp))
        DiagnosticsRow()
    }
}

/**
 * The only way anything about a failure ever leaves this phone.
 *
 * There is no network permission and no telemetry, which is the right default
 * and also means a capture that went wrong somewhere else is invisible unless
 * the person holding it decides otherwise. So the report is built on request,
 * put in Downloads where they can find it, and handed to the share sheet - the
 * log, what the device and its cameras are, and each capture's own bookkeeping.
 * No frames, no imagery beyond the preview the app already made.
 */
@Composable
private fun DiagnosticsRow() {
    val context = LocalContext.current
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = {
                if (busy) return@OutlinedButton
                busy = true
                status = null
                val main = android.os.Handler(android.os.Looper.getMainLooper())
                Thread({
                    var uri: android.net.Uri? = null
                    val text = try {
                        val bundle = Diagnostics.build(context)
                        uri = Diagnostics.publish(context, bundle)
                        bundle.name + " - " + Diagnostics.describe(uri)
                    } catch (e: Exception) {
                        "could not build a report: " + (e.message ?: e.javaClass.simpleName)
                    }
                    val share = uri
                    main.post {
                        status = text
                        busy = false
                        if (share != null) {
                            try {
                                context.startActivity(Intent.createChooser(
                                    Diagnostics.shareIntent(share), "Send the report"))
                            } catch (e: Exception) {
                                status = text + " (nothing on this phone can send it)"
                            }
                        }
                    }
                }, "hdri-diagnostics").start()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (busy) "Collecting..." else "Save a diagnostics report")
        }
        Spacer(Modifier.height(6.dp))
        Text(status ?: "The log, the device and what each capture did. No photographs, " +
            "and nothing is sent anywhere unless you send it.",
            style = MaterialTheme.typography.bodySmall, color = Color(0xFF9A9A9A))
    }
}

/** How far the phone is rolled from the pose the current target wants. */
private fun rollOf(state: CaptureUiState): Double? {
    val pose = state.pose ?: return null
    val snap = state.snapshot ?: return null
    val i = snap.currentTarget
    if (i < 0 || i >= state.targets.size) return null
    return com.immineal.hdri360.core.pano.CaptureGuide.rollErrorDeg(pose, state.targets[i])
}

@Composable
private fun CaptureScreen(state: CaptureUiState, rotationDeg: Int,
                          onSurface: (SurfaceTexture?) -> Unit,
                          onFinish: () -> Unit, onSkipScan: () -> Unit) {
    val snap = state.snapshot
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // The camera hands over frames in the sensor's own orientation, which on
        // almost every phone is a quarter turn from the way the phone is held. So
        // the preview is laid out landscape, at the sensor's aspect ratio, and
        // then turned - rather than stretched into a portrait box, which is what
        // made the picture disagree with the guidance drawn on top of it.
        val density = LocalDensity.current
        val viewW = with(density) { maxWidth.toPx() }
        val viewH = with(density) { maxHeight.toPx() }
        val k = state.intrinsics
        val aspect = if (k != null && k.height > 0) k.width.toFloat() / k.height else 4f / 3f
        val quarterTurned = (((rotationDeg % 360) + 360) % 360) / 90 % 2 == 1
        // Cover the screen without distorting: pick the short side from whichever
        // way round the frame ends up, then let the long side follow the aspect.
        val shortPx = if (quarterTurned) Math.max(viewW, viewH / aspect)
                      else Math.max(viewH, viewW / aspect)
        val longPx = shortPx * aspect
        // The same frame as it ends up on the screen, after the quarter turn the
        // sensor's mounting implies. This is what the overlay draws onto and what
        // the preview is scaled to, so they are the same picture by construction.
        val shownW = if (quarterTurned) shortPx else longPx
        val shownH = if (quarterTurned) longPx else shortPx

        // The view fills the screen and the frame is placed inside it by the
        // TextureView's own transform, rather than by laying the view out large
        // and turning it.
        //
        // Two reasons, both found the hard way. A TextureView is composited by
        // the hardware renderer rather than drawn into the canvas it is given, so
        // wrapping it in a rotating layer can leave the preview blank. And
        // Modifier.size is coerced by the parent's constraints: asking for a
        // frame wider than the screen quietly got a narrower one, while the
        // markers were still being placed with the width that had been asked for.
        // The picture and the guidance were then drawn to two different scales,
        // which is what makes the dots drift away from what they are pointing at
        // as you look further from the middle of the screen.
        AndroidView(factory = { ctx ->
            TextureView(ctx).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) =
                        onSurface(st)
                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) { }
                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                        onSurface(null); return true
                    }
                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) { }
                }
            }
        }, modifier = Modifier.fillMaxSize(), update = { view ->
            if (viewW > 0 && viewH > 0) {
                // Only the shape, never the turn.
                //
                // The camera hands a SurfaceTexture its buffers already turned
                // upright for the device's natural orientation, and this screen is
                // locked to portrait - so there is no turn left to make. Making it
                // anyway put the picture through the quarter turn twice: ninety
                // degrees round from the room, and stretched by 1.78 because the
                // frame's long axis was then being scaled to the short side.
                //
                // What is left is the shape. The buffer arrives stretched to fill
                // the view, and the overlay places its markers on a picture
                // shownW by shownH, so the picture is scaled to exactly that.
                val m = android.graphics.Matrix()
                m.postScale(shownW / viewW, shownH / viewH, viewW / 2, viewH / 2)
                view.setTransform(m)
            }
        })

        SphereOverlay(
            targets = state.targets,
            shot = snap?.shot,
            abandoned = snap?.abandoned,
            current = snap?.currentTarget ?: -1,
            pose = state.pose,
            intrinsics = state.intrinsics,
            aligned = snap?.aligned ?: false,
            steady = snap?.steady ?: false,
            modifier = Modifier.fillMaxSize(),
            rotationDeg = rotationDeg,
            frameWidthPx = longPx,
            frameHeightPx = shortPx,
            rollErrorDeg = rollOf(state))

        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth()
            .background(Color(0xAA000000)).safeDrawingPadding().padding(12.dp)) {
            Text(state.message, style = MaterialTheme.typography.titleSmall)
            if (state.tierNote.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(state.tierNote, style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFB0B0B0))
            }
            state.warning?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFFFFC400))
            }
            // What to actually do. "Sweep the scene so it can be metered" is a
            // label for someone who already knows; the sweep is an unusual thing
            // to ask of a person and the phase it belongs to has no other clue in
            // it. Said once, at the top, in the phase it applies to.
            val how = when (state.phase) {
                CaptureUiState.Phase.SCANNING -> if (state.waitingForHighlights)
                    "Still looking for the brightest part of the room - a window, a " +
                    "lamp, the sky. Point at it for a moment. Until it has been read " +
                    "without saturating, the top of the exposure ladder would be a " +
                    "guess and the highlights would burn out."
                    else
                    "Turn slowly all the way round, then look up at the ceiling and " +
                    "down at the floor. This finds the brightest and darkest of the " +
                    "room and sets one exposure ladder for the whole sphere. It ends " +
                    "by itself."
                CaptureUiState.Phase.CAPTURING ->
                    "Bring the yellow ring into the circle and hold still. The line " +
                    "across the circle shows how level you are; near the top and " +
                    "bottom of the sphere it stops mattering."
                else -> null
            }
            how?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFFD0D0D0))
            }
        }

        val target = snap?.currentTarget?.takeIf { it >= 0 && it < state.targets.size }
            ?.let { state.targets[it] }
        Text(aimText(state.pose, target, rotationDeg),
            modifier = Modifier.align(Alignment.Center).padding(top = 96.dp),
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .background(Color(0xAA000000)).safeDrawingPadding().padding(14.dp)) {
            if (snap != null) {
                val progress = if (state.phase == CaptureUiState.Phase.SCANNING)
                    snap.scanCoverage.toFloat() else snap.progress.toFloat()
                LinearProgressIndicator({ progress.coerceIn(0f, 1f) },
                    Modifier.fillMaxWidth().height(4.dp))
                Spacer(Modifier.height(8.dp))
                Text(
                    if (state.phase == CaptureUiState.Phase.SCANNING)
                        "metered ${Math.round(snap.scanCoverage * 100)}% of the sphere"
                    else {
                        val lost = snap.abandoned.count { it }
                        "${snap.directionsShot} of ${snap.shot.size} directions, " +
                        "${snap.framesTaken} frames" +
                        if (lost > 0) ", $lost could not be shot" else ""
                    },
                    style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.phase == CaptureUiState.Phase.SCANNING)
                    OutlinedButton(onSkipScan) { Text("Done sweeping") }
                if (state.phase == CaptureUiState.Phase.CAPTURING)
                    OutlinedButton(onFinish) { Text("Finish here") }
            }
        }
    }
}

/**
 * The estimate, before the user commits to it.
 *
 * Measured on this phone rather than assumed: the same sphere is a couple of
 * minutes on one device and the better part of an hour on another, so the only
 * useful number is one this machine produced.
 */
@Composable
private fun ReadyScreen(dir: File, onChoose: (Int) -> Unit) {
    var options by remember {
        mutableStateOf<List<com.immineal.hdri360.core.pipeline.ResolutionOption>?>(null)
    }
    var recommended by remember { mutableStateOf(0) }
    LaunchedEffect(dir) {
        options = withContext(Dispatchers.Default) { ProcessingService.optionsFor(dir) }
        recommended = withContext(Dispatchers.Default) { ProcessingService.recommendedWidth(dir) }
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center) {
        Text("The sphere is captured", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text("Every frame is already on the phone. Pick an output size; the times " +
            "below were measured on this device just now.",
            style = MaterialTheme.typography.bodyMedium, color = Color(0xFFB0B0B0))
        Spacer(Modifier.height(20.dp))
        val list = options
        if (list == null) {
            Text("Timing this phone...", style = MaterialTheme.typography.bodyMedium)
        } else if (list.isEmpty()) {
            Text("No direction was completely shot, so there is nothing to stitch.",
                color = Color(0xFFFF8A80))
        } else {
            for (o in list) {
                val best = o.width == recommended
                OutlinedButton({ onChoose(o.width) },
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = if (best)
                        ButtonDefaults.outlinedButtonColors(containerColor = Color(0x2239C36B))
                    else ButtonDefaults.outlinedButtonColors()) {
                    Column(Modifier.fillMaxWidth()) {
                        Text("${o.label}  ${o.width} x ${o.height}" +
                            if (best) "   matches the capture" else "",
                            style = MaterialTheme.typography.titleSmall)
                        Text(o.estimate.humanText(), style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFB0B0B0))
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("Above the matched size the render resamples detail that was never " +
                "captured; below it, detail that was gets thrown away.",
                style = MaterialTheme.typography.bodySmall, color = Color(0xFF9A9A9A))
        }
    }
}

@Composable
private fun ProcessingScreen(p: ProcessingService.State, onReview: (File) -> Unit,
                             onDismiss: () -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center) {
        Text(if (p.finished) "Finished" else "Building the sphere",
            style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(14.dp))
        Text(p.stage, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator({ p.fraction.toFloat().coerceIn(0f, 1f) },
            Modifier.fillMaxWidth().height(5.dp))
        Spacer(Modifier.height(10.dp))
        if (!p.finished && p.remainingText.isNotEmpty())
            Text(p.remainingText, style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFB0B0B0))
        if (p.error != null) {
            Spacer(Modifier.height(12.dp))
            Text(p.error, color = Color(0xFFFF8A80), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(6.dp))
            Text("The frames are still on the phone; nothing was lost. Processing can be " +
                "started again from the capture screen.",
                style = MaterialTheme.typography.bodySmall, color = Color(0xFFB0B0B0))
            Spacer(Modifier.height(14.dp))
            DiagnosticsRow()
        }
        if (p.finished) {
            Spacer(Modifier.height(22.dp))
            p.directory?.let { d ->
                if (p.error == null)
                    Button({ onReview(d) }, Modifier.fillMaxWidth()) { Text("Look at it") }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onDismiss, Modifier.fillMaxWidth()) { Text("Back to capture") }
        }
    }
}
