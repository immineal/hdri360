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
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.immineal.hdri360.core.capture.SphereLibrary
import androidx.compose.ui.res.painterResource
import com.immineal.hdri360.R
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.immineal.hdri360.core.capture.Lens
import com.immineal.hdri360.core.capture.LensChooser
import com.immineal.hdri360.core.pipeline.StageProgress
import com.immineal.hdri360.device.CaptureSession
import com.immineal.hdri360.device.CaptureUiState
import com.immineal.hdri360.device.Diagnostics
import com.immineal.hdri360.device.ProcessingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

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
        // A stitch that was killed rather than finished leaves its progress
        // notification behind; this is the first code that runs afterwards.
        ProcessingService.clearStaleProgress(this)
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

                    // Processing runs as a foreground service and its whole
                    // visible presence is one notification. From Android 13 that
                    // notification needs permission, which this app declared and
                    // never asked for - so on a current phone somebody handed a
                    // sphere over for processing and then had nothing at all to
                    // look at while it ran.
                    //
                    // Asked here rather than at startup, because here is where it
                    // is about to be used and the reason is self-evident. Denial
                    // does not stop the work: the service runs either way and the
                    // screen inside the app still shows progress.
                    var afterNotifyAsk by remember { mutableStateOf<(() -> Unit)?>(null) }
                    val askNotify = androidx.activity.compose.rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission()) { _ ->
                        afterNotifyAsk?.invoke()
                        afterNotifyAsk = null
                    }
                    val state by session.state.collectAsStateWithLifecycle()
                    val processing by ProcessingService.state.collectAsStateWithLifecycle()

                    // Shown before the first capture and whenever the question
                    // mark is tapped. Over everything else rather than as a
                    // screen of its own, so it can be reached from the middle of
                    // a capture - which is exactly when somebody wonders what the
                    // ring is for.
                    var intro by remember { mutableStateOf(!IntroSeen.has(this@CaptureActivity)) }
                    if (intro) {
                        IntroSketch(onDone = {
                            IntroSeen.record(this@CaptureActivity); intro = false
                        })
                        return@Surface
                    }

                    val ready = pending
                    when {
                        !granted -> PermissionScreen { ask() }
                        processing.active || processing.finished -> {
                            // Back leaves a finished job behind rather than the
                            // app. Coming out of the viewer used to land here -
                            // on a full progress bar for work already done - and
                            // going back again closed the app, which is two
                            // surprises in a row for one gesture.
                            //
                            // While a job is still running, Back keeps its
                            // ordinary meaning: the service and its notification
                            // carry on, and there is nothing to acknowledge yet.
                            if (processing.finished) {
                                BackHandler {
                                    ProcessingService.acknowledge(); pending = null
                                }
                            }
                            ProcessingScreen(processing, { dir ->
                                // Seen, so it is no longer news. Without this the
                                // finished screen sits behind the viewer waiting
                                // to be backed into.
                                ProcessingService.acknowledge()
                                pending = null
                                openReview(dir)
                            }) {
                                ProcessingService.acknowledge(); pending = null
                            }
                        }
                        ready != null -> ReadyScreen(ready) { width ->
                            pending = null
                            val begin = {
                                ProcessingService.start(this@CaptureActivity, ready, width)
                            }
                            val needsAsking = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                                    PackageManager.PERMISSION_GRANTED
                            if (needsAsking) {
                                afterNotifyAsk = begin
                                askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                begin()
                            }
                        }
                        state.phase == CaptureUiState.Phase.IDLE && wanted == null ->
                            StartScreen(state, ::openReview, ::openLibrary, { intro = true },
                                ::openAbout, session::discard,
                                // Straight to the size picker, which is where
                                // processing starts from. The capture is already
                                // shot; there is nothing to go back to the camera
                                // for.
                                onProcess = { dir -> pending = dir }) { lens, resume ->
                                wanted = Pair(lens, resume)
                            }
                        else -> {
                            // Back leaves the capture, not the app. Swiping in from
                            // the edge is how a person says "not this after all",
                            // and closing the whole app in answer is a poor
                            // reading of that.
                            BackHandler(true) { wanted = null; session.cancel() }
                            CaptureScreen(state, sensorRotation(state),
                                onSurface = { st -> onPreviewSurface(st) },
                                onFinish = { session.finish() },
                                onSkipScan = { session.finishScan() },
                                onCancel = { wanted = null; session.cancel() })
                        }
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

    private fun openLibrary() {
        startActivity(Intent(this, LibraryActivity::class.java))
    }

    private fun openAbout() {
        startActivity(Intent(this, AboutActivity::class.java))
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
    onLibrary: () -> Unit,
    onHelp: () -> Unit,
    onAbout: () -> Unit,
    onDiscard: (File) -> Unit,
    onProcess: (File) -> Unit,
    onStart: (String, File?) -> Unit
) {
    // The person's own choice if they made one, and otherwise whatever the app
    // currently says the default is. It used to be `remember { state.chosenLens }`,
    // which captures the value at the *first* composition - and the first
    // composition happens before the camera list has been read, so the screen
    // highlighted whichever lens sorted first and never corrected itself. Caught
    // on a screenshot: the untested ultrawide sat there marked as chosen while
    // the log said the app had chosen the main lens.
    var picked by remember { mutableStateOf<String?>(null) }
    val lens = picked ?: state.chosenLens
    val context = LocalContext.current

    // The person's own spheres, off the main thread. `list()` walks every capture
    // directory to total what each one costs, which is not something to do on the
    // thread that draws.
    var recent by remember { mutableStateOf<List<SphereLibrary.Entry>>(emptyList()) }
    LaunchedEffect(state.finished?.path, state.resumable?.path) {
        val root = state.sessionDir?.parentFile ?: state.finished?.parentFile
            ?: state.resumable?.parentFile
        recent = if (root == null) emptyList() else withContext(Dispatchers.IO) {
            runCatching { SphereLibrary(root).list().take(3) }.getOrDefault(emptyList())
        }
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 22.dp, vertical = 18.dp)) {

        // Name, then two ways out of it, on one line. Everything that used to be
        // stacked underneath - a tagline, a link on its own row - was text nobody
        // needs twice.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // The mark instead of the digits it is made of. Same drawing as the
            // launcher icon, from the same source, so the app in the app drawer
            // and the app on screen are recognisably the one thing.
            Image(painterResource(R.drawable.logo_mark), "360 HDRI Camera",
                Modifier.size(46.dp))
            Spacer(Modifier.width(10.dp))
            Text("HDRI Camera", style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f))
            // A question mark, and the only thing in this corner. It is the one
            // door somebody might want on the way in; the licence and the method
            // are not, and they went to the foot of the screen.
            TextButton(onHelp, modifier = Modifier.semantics {
                contentDescription = "How this works, in four steps"
            }) { Text("?", style = MaterialTheme.typography.titleLarge) }
        }

        if (state.lenses.isEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text("No usable camera was found on this device.", color = Color(0xFFFF8A80))
            return@Column
        }

        Spacer(Modifier.height(22.dp))
        val choices = state.lenses.filter { !it.frontFacing }
        for (option in choices) {
            LensRow(option, selected = option.id == lens || choices.size == 1,
                pickable = choices.size > 1) { picked = option.id }
            Spacer(Modifier.height(10.dp))
        }

        // The thing you came for, unmistakable, and above the cards rather than
        // below them: a start screen should not need reading. Generous, because
        // it is pressed with one hand while holding a phone up.
        Spacer(Modifier.height(18.dp))
        Button({ lens?.let { onStart(it, null) } }, Modifier.fillMaxWidth().height(64.dp)) {
            Text("Start a new sphere", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(10.dp))
        // A destination, not a footnote: the library is where every sphere this
        // app has ever made lives, and it was a small grey word at the bottom.
        OutlinedButton(onLibrary, Modifier.fillMaxWidth().height(52.dp)) {
            Text("All spheres", style = MaterialTheme.typography.titleSmall)
        }

        if (state.phase == CaptureUiState.Phase.FAILED) {
            Spacer(Modifier.height(12.dp))
            Text(state.message, color = Color(0xFFFF8A80),
                style = MaterialTheme.typography.bodySmall)
        }

        // Shot in full, never stitched. Since the foreground service went (decision
        // 15) this is what closing the app during processing leaves behind, so it
        // says what happened and offers the door that actually helps.
        state.unprocessed?.let { dir ->
            Spacer(Modifier.height(18.dp))
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Shot, not processed", style = MaterialTheme.typography.titleSmall)
                        Text("every frame is on the phone",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF9A9A9A))
                    }
                    TextButton({ onProcess(dir) }) { Text("Continue processing") }
                }
            }
        }

        state.resumable?.let { dir ->
            Spacer(Modifier.height(18.dp))
            var discarding by remember(dir) { mutableStateOf(false) }
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Unfinished capture", style = MaterialTheme.typography.titleSmall)
                        Text("carries on where it stopped",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF9A9A9A))
                    }
                    TextButton({ lens?.let { onStart(it, dir) } }) { Text("Resume") }
                    // Without this the only ways past an unfinished capture were
                    // to finish it or to leave it there for ever.
                    TextButton({ discarding = true }) {
                        Text("Discard", color = Color(0xFF9A9A9A))
                    }
                }
            }
            if (discarding) {
                AlertDialog(
                    onDismissRequest = { discarding = false },
                    title = { Text("Discard this capture?") },
                    text = {
                        Text("The frames already taken are deleted and the sphere is " +
                             "never made. Nothing else is touched.")
                    },
                    confirmButton = {
                        TextButton({ discarding = false; onDiscard(dir) }) { Text("Discard") }
                    },
                    dismissButton = {
                        TextButton({ discarding = false }) { Text("Keep it") }
                    })
            }
        }

        // The screen's lower half, filled with the person's own pictures rather
        // than with words about them. Three, newest first, each one a tap away -
        // which is also the honest answer to "what has this app done for me": it
        // shows you.
        if (recent.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            for (e in recent) {
                SphereCard(e, context) { onOpen(e.dir) }
                Spacer(Modifier.height(8.dp))
            }
        }

        // The doors nobody needs on the way in, at the foot, unexplained.
        Spacer(Modifier.height(28.dp))
        FooterLinks(onAbout)
        Spacer(Modifier.height(12.dp))
    }
}

/**
 * One finished sphere: its own picture, what it is called, and when.
 *
 * A thumbnail says more per pixel than a sentence about what a viewer is for,
 * and the date is the thing that tells two spheres of the same room apart.
 */
@Composable
private fun SphereCard(e: SphereLibrary.Entry, context: android.content.Context,
                       onOpen: () -> Unit) {
    val bmp = remember(e.dir.path) { Thumbnails.decode(e.thumbnail) }
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(104.dp, 58.dp).clip(RoundedCornerShape(5.dp))
                .background(Color(0xFF202020)), contentAlignment = Alignment.Center) {
                if (bmp != null) {
                    Image(bmp.asImageBitmap(), "Preview of " + (e.name ?: "this sphere"),
                        Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Text("no preview", fontSize = 9.sp, color = Color(0xFF808080))
                }
            }
            Spacer(Modifier.width(12.dp))
            val d = java.util.Date(e.capturedAtMillis)
            val when_ = android.text.format.DateFormat.getMediumDateFormat(context).format(d) +
                ", " + android.text.format.DateFormat.getTimeFormat(context).format(d)
            Column(Modifier.weight(1f)) {
                // An unnamed sphere is titled by its date, so repeating the date
                // underneath would be the row saying nothing twice.
                Text(e.name ?: when_, style = MaterialTheme.typography.titleSmall)
                if (e.name != null) {
                    Text(when_, style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF9A9A9A))
                }
            }
            Text("Open", style = MaterialTheme.typography.labelLarge,
                color = Color(0xFFFFC400), modifier = Modifier.padding(end = 8.dp))
        }
    }
}

/**
 * One lens, as facts rather than as a sentence.
 *
 * The field of view is the number somebody chooses by, so it leads; the count of
 * directions is what it costs them, so it follows. "Ultrawide, 104 degrees, 21
 * directions, RAW, untested on this phone" is all of that too, and it is a line
 * of prose in a place where three words would do.
 */
@Composable
private fun LensRow(lens: Lens, selected: Boolean, pickable: Boolean, onPick: () -> Unit) {
    val edge = if (selected) Color(0xFF39C36B) else Color(0xFF3A3A3A)
    Row(Modifier.fillMaxWidth()
        .clip(RoundedCornerShape(10.dp))
        .background(if (selected) Color(0x1839C36B) else Color(0xFF161616))
        .border(BorderStroke(if (selected) 2.dp else 1.dp, edge), RoundedCornerShape(10.dp))
        .let { if (pickable) it.clickable(onClick = onPick) else it }
        .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(String.format(Locale.US, "%.0f°", lens.horizontalFovDeg),
            style = MaterialTheme.typography.titleLarge,
            color = if (selected) Color(0xFFECECEC) else Color(0xFFB0B0B0),
            modifier = Modifier.width(72.dp))
        Column(Modifier.weight(1f)) {
            Text(LensChooser.directionsFor(lens).toString() + " directions",
                style = MaterialTheme.typography.bodyMedium)
            Text(if (lens.measuresRadiance) "RAW · a radiance measurement"
                 else "no RAW · relative values",
                style = MaterialTheme.typography.bodySmall, color = Color(0xFF9A9A9A))
        }
        // No verdict here. "untested" beside the count of directions read as a
        // property of the lens, and on most phones most things are untested -
        // which makes it noise in the one place somebody is choosing. What is
        // actually known about the physical stream is in the log, in About, and
        // in the fact that the app does not start on it.
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
private fun FooterLinks(onAbout: () -> Unit) {
    // The status line goes *under* the row and not inside it. It used to live in
    // the diagnostics link's own column, so the moment a report was saved the
    // column grew wide and shoved "About" off to the side.
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onAbout) {
                Text("About", style = MaterialTheme.typography.labelLarge,
                    color = Color(0xFF808080))
            }
            Text("·", color = Color(0xFF505050))
            DiagnosticsButton(busy, { busy = it }, { status = it })
        }
        DiagnosticsStatus(status)
    }
}

/**
 * The same offer on its own, for the processing screen.
 *
 * There, something has just gone wrong and the report is the thing being held
 * out; there is no About beside it to be pushed aside.
 */
@Composable
private fun DiagnosticsLink() {
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        DiagnosticsButton(busy, { busy = it }, { status = it })
        DiagnosticsStatus(status)
    }
}

@Composable
private fun DiagnosticsStatus(status: String?) {
    status?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFF9A9A9A),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
    }
}

/**
 * Collects the log, the device and what each capture did, and hands it to
 * whatever on the phone can send it.
 *
 * A text link, because what it contains is a paragraph and the paragraph belongs
 * in About rather than in the way in.
 */
@Composable
private fun DiagnosticsButton(busy: Boolean, onBusy: (Boolean) -> Unit,
                              onStatus: (String?) -> Unit) {
    val context = LocalContext.current
    TextButton(
        onClick = {
            if (busy) return@TextButton
            onBusy(true)
            onStatus(null)
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
                    onStatus(text)
                    onBusy(false)
                    if (share != null) {
                        try {
                            context.startActivity(Intent.createChooser(
                                Diagnostics.shareIntent(share), "Send the report"))
                        } catch (e: Exception) {
                            onStatus(text + " (nothing on this phone can send it)")
                        }
                    }
                }
            }, "hdri-diagnostics").start()
        }
    ) {
        Text(if (busy) "Collecting..." else "Diagnostics report",
            style = MaterialTheme.typography.labelLarge, color = Color(0xFF808080))
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
                          onFinish: () -> Unit, onSkipScan: () -> Unit,
                          onCancel: () -> Unit) {
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
        // The whole frame, not as much of it as fills the screen.
        //
        // Covering the screen crops a 4:3 frame held upright to about a third of
        // its width, so the preview shows a far narrower view than the camera is
        // actually recording - which reads as being zoomed in, and hides the
        // edges of the very frame the person is being asked to aim. Fitting shows
        // what will be captured, with bars where there is nothing to show.
        val shortPx = if (quarterTurned) Math.min(viewW, viewH / aspect)
                      else Math.min(viewH, viewW / aspect)
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
            metered = snap?.metered,
            scanning = state.phase == CaptureUiState.Phase.SCANNING,
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
            rollErrorDeg = rollOf(state),
            extraRungs = snap?.extraRungs,
            burstRungs = snap?.burstRungs ?: 0,
            burstReceived = snap?.burstReceived ?: 0)

        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth()
            .background(Color(0xAA000000)).safeDrawingPadding().padding(12.dp)) {
            Text(state.message, style = MaterialTheme.typography.titleSmall)
            // The tier note is gone from here. It is a fact about the lens, it
            // does not change while a capture runs, and the lens picker already
            // says it on the way in - so on this screen it was a line of standing
            // text competing with the one line that changes.
            state.warning?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFFFFC400))
            }
            // What to actually do. "Sweep the scene so it can be metered" is a
            // label for someone who already knows; the sweep is an unusual thing
            // to ask of a person and the phase it belongs to has no other clue in
            // it.
            //
            // Shown until it has been acted on, not for the whole phase. An
            // instruction that stays after the person has demonstrably understood
            // it is not an instruction any more, it is four lines of the screen
            // spent on nothing - and it was covering the part of the sphere they
            // were being asked to look at.
            //
            // The one exception is the sweep waiting for the bright end: that is
            // not an instruction but a live reason why the sweep has not ended,
            // and it stays while it is true.
            val started = when (state.phase) {
                CaptureUiState.Phase.SCANNING -> (snap?.scanCoverage ?: 0.0) > 0.10
                CaptureUiState.Phase.CAPTURING ->
                    (snap?.directionsShot ?: 0) + (snap?.abandoned?.count { it } ?: 0) > 0
                else -> true
            }
            val how = if (started && !state.waitingForHighlights) null else when (state.phase) {
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
                // One bar, one journey. It used to show the sweep's coverage and
                // then start again from nothing when the capture began, so it
                // filled to a hundred percent and reset in front of the person
                // holding the phone.
                //
                // The two shares are measured, from a real 34 direction capture:
                // the sweep ran 38.1 s (12:42:05.955 to 12:42:44.091) and the
                // capture 238.4 s (12:42:51.985 to 12:46:50.380). A sweep ended
                // early simply jumps the bar forward to its full share, which is
                // honest - that part is over.
                val bar = remember {
                    StageProgress(listOf(
                        StageProgress.Stage("sweep", 38.1),
                        StageProgress.Stage("capture", 238.4)))
                }
                val progress = if (state.phase == CaptureUiState.Phase.SCANNING)
                    bar.overall("sweep", snap.scanCoverage)
                else bar.overall("capture", snap.progress)
                LinearProgressIndicator({ progress.toFloat().coerceIn(0f, 1f) },
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
                // "Done" and "finish" both mean "keep this". Changing your mind
                // needs its own word, and a way out that is not the app's exit.
                TextButton(onCancel) { Text("Cancel") }
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
        Spacer(Modifier.height(12.dp))
        // Said here, before the choice, because the choice is how long the app
        // has to stay open for. See decision 15.
        Text("Keep the app open while it works. The screen may go off, but " +
            "closing the app stops the sphere being built.",
            style = MaterialTheme.typography.bodySmall, color = Color(0xFFFFC400))
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
        if (!p.finished) {
            Spacer(Modifier.height(18.dp))
            // The whole of the promise, in the place where somebody is deciding
            // whether they can put the phone down: what stops it, what survives,
            // and where to pick it up. Decision 15.
            Text("Leave the app open", style = MaterialTheme.typography.titleSmall,
                color = Color(0xFFFFC400))
            Spacer(Modifier.height(4.dp))
            Text("The screen can go off and this carries on. Closing the app stops " +
                "it, and then nothing is lost but the arithmetic: every frame stays " +
                "on the phone, and the start screen offers to continue processing " +
                "from them.",
                style = MaterialTheme.typography.bodySmall, color = Color(0xFFB0B0B0))
        }
        if (p.error != null) {
            Spacer(Modifier.height(12.dp))
            Text(p.error, color = Color(0xFFFF8A80), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(6.dp))
            Text("The frames are still on the phone; nothing was lost. The start screen " +
                "offers to continue processing them.",
                style = MaterialTheme.typography.bodySmall, color = Color(0xFFB0B0B0))
            Spacer(Modifier.height(14.dp))
            DiagnosticsLink()
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
