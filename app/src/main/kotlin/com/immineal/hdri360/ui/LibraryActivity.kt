package com.immineal.hdri360.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immineal.hdri360.core.capture.SphereLibrary
import java.io.File
import java.util.Date
import java.util.Locale

/**
 * Every sphere on the phone, with whatever the person called it.
 *
 * Before this the app could offer exactly one capture - the most recent - so a
 * sphere shot yesterday was on the device and unreachable, and a phone with six
 * rooms on it had six directories called `capture-1788604964095`.
 *
 * The file and naming rules live in [SphereLibrary], in the core, where they are
 * tested on a bare JVM. What is here is presentation, and two presentation
 * decisions are load-bearing:
 *
 *  - **An unnamed sphere shows when it was shot**, formatted in the device's own
 *    locale and clock. The name is left genuinely empty rather than pre-filled
 *    with that date, because a library where every row looks named is a library
 *    you have to read carefully to find the one you actually named.
 *  - **Each row says what is wrong with its sphere**, if anything: highlights
 *    that are a bound rather than a measurement, directions the solve never
 *    reached, radiance still in the camera's own primaries. Those are the three
 *    things that decide whether a sphere is worth keeping, and finding them out
 *    by opening each one in turn is how they get ignored.
 */
class LibraryActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = File(filesDir, "captures")
        val library = SphereLibrary(root)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize(), color = Color.Black,
                        contentColor = Color(0xFFECECEC)) {
                    LibraryScreen(library, this,
                        onOpen = { dir ->
                            startActivity(Intent(this, ReviewActivity::class.java)
                                .putExtra(ReviewActivity.EXTRA_DIR, dir.absolutePath))
                        })
                }
            }
        }
    }
}

@Composable
private fun LibraryScreen(library: SphereLibrary, context: Context, onOpen: (File) -> Unit) {
    // Re-read on every change rather than mutating a list in place: the
    // processing service writes into these directories from outside this screen,
    // and a list that is a cache of the filesystem is a list that goes stale.
    var revision by remember { mutableStateOf(0) }
    val entries = remember(revision) { library.list() }
    var renaming by remember { mutableStateOf<SphereLibrary.Entry?>(null) }
    var deleting by remember { mutableStateOf<SphereLibrary.Entry?>(null) }
    var freeing by remember { mutableStateOf<SphereLibrary.Entry?>(null) }
    var freeingAll by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(12.dp))
        Text("Spheres", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(2.dp))
        Text(if (entries.isEmpty()) "Nothing captured yet"
             else "${entries.size} on this phone, ${sizeOf(entries.sumOf { it.bytesOnDisk })} in all",
            style = MaterialTheme.typography.bodySmall, color = Color(0xFFB0B0B0))
        // The number worth acting on, and only when there is something to act on.
        // Raw frames are almost the whole of what a library costs - gigabytes
        // against tens of megabytes of sphere - and they are the only part that
        // can go without losing anything that cannot be made again.
        val reclaimable = entries.sumOf { it.rawBytes }
        if (reclaimable > 0) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${sizeOf(reclaimable)} of that is raw frames, which the spheres no " +
                     "longer need", style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFE0B15E), modifier = Modifier.weight(1f))
                TextButton({ freeingAll = true }) { Text("Free it") }
            }
        }
        Spacer(Modifier.height(12.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(entries, key = { it.dir.name }) { e ->
                SphereRow(e, context,
                    onOpen = { onOpen(e.dir) },
                    onRename = { renaming = e },
                    onFreeFrames = { freeing = e },
                    onDelete = { deleting = e })
            }
        }
    }

    renaming?.let { e ->
        RenameDialog(e,
            onDismiss = { renaming = null },
            onConfirm = { name ->
                library.rename(e.dir, name)
                renaming = null
                revision++
            })
    }

    if (freeingAll) {
        val doomed = entries.filter { it.hasRawFrames }
        val bytes = doomed.sumOf { it.rawBytes }
        val count = doomed.size
        AlertDialog(
            onDismissRequest = { freeingAll = false },
            title = { Text("Delete the frames of $count spheres?") },
            text = {
                Text("Frees ${sizeOf(bytes)}. Every sphere, report and preview stays " +
                     "exactly as it is; what goes is the DNGs they were made from, so " +
                     "none of them can be re-processed afterwards. There is no undo.")
            },
            confirmButton = {
                TextButton({
                    for (e in doomed) library.deleteRawFrames(e.dir)
                    freeingAll = false
                    revision++
                }) { Text("Free ${sizeOf(bytes)}") }
            },
            dismissButton = { TextButton({ freeingAll = false }) { Text("Keep them") } })
    }

    freeing?.let { e ->
        AlertDialog(
            onDismissRequest = { freeing = null },
            title = { Text("Delete the raw frames?") },
            text = {
                Text("Frees ${sizeOf(e.rawBytes)}. The sphere, its report and its " +
                     "preview stay; what goes is the ${sizeOf(e.rawBytes)} of DNGs it " +
                     "was made from, so it cannot be re-processed at a different " +
                     "size or with a later version of the stitcher.")
            },
            confirmButton = {
                TextButton({
                    library.deleteRawFrames(e.dir)
                    freeing = null
                    revision++
                }) { Text("Delete the frames") }
            },
            dismissButton = { TextButton({ freeing = null }) { Text("Keep them") } })
    }

    deleting?.let { e ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete this sphere?") },
            text = {
                Text("${titleOf(e, context)} goes for good, " +
                     (if (e.hasRawFrames) "with its raw frames - " else "- ") +
                     "${sizeOf(e.bytesOnDisk)}. Export the EXR first if you want to keep it.")
            },
            confirmButton = {
                TextButton({
                    library.delete(e.dir)
                    deleting = null
                    revision++
                }) { Text("Delete") }
            },
            dismissButton = { TextButton({ deleting = null }) { Text("Keep it") } })
    }
}

@Composable
private fun SphereRow(e: SphereLibrary.Entry, context: Context,
                      onOpen: () -> Unit, onRename: () -> Unit,
                      onFreeFrames: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Thumbnail(e, titleOf(e, context))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(titleOf(e, context), style = MaterialTheme.typography.titleSmall)
                // An unnamed sphere is titled by its date, so repeating the date
                // underneath would be the row saying nothing twice.
                if (e.name != null) {
                    Text(dateOf(e, context), style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFB0B0B0))
                }
                Spacer(Modifier.height(3.dp))
                Text(factsOf(e), fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                    color = Color(0xFF9A9A9A))
                cautionOf(e)?.let {
                    Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                        color = Color(0xFFE0B15E))
                }
            }
            Box {
                TextButton({ menu = true },
                    modifier = Modifier.semantics {
                        contentDescription = "More actions for ${titleOf(e, context)}"
                    }) { Text("...") }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Rename") },
                        onClick = { menu = false; onRename() })
                    // Absent rather than disabled when there is nothing to free:
                    // a greyed-out row is a question the person has to answer
                    // before they can ignore it.
                    if (e.hasRawFrames) {
                        DropdownMenuItem(
                            text = { Text("Delete raw frames (${sizeOf(e.rawBytes)})") },
                            onClick = { menu = false; onFreeFrames() })
                    }
                    DropdownMenuItem(text = { Text("Delete") },
                        onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

@Composable
private fun Thumbnail(e: SphereLibrary.Entry, label: String) {
    // Decoded small and once. A library of a dozen 2048 wide previews decoded at
    // full size is tens of megabytes of bitmap for pictures shown at 64dp.
    val bmp = remember(e.dir.path, e.bytesOnDisk) { Thumbnails.decode(e.thumbnail) }
    Box(Modifier.size(96.dp, 54.dp).clip(RoundedCornerShape(4.dp))
        .background(Color(0xFF202020)), contentAlignment = Alignment.Center) {
        if (bmp != null) {
            // Named for a screen reader. The row's own title is what this picture
            // is of, and "image" would be all a person hears otherwise.
            Image(bmp.asImageBitmap(), "Preview of $label", Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop)
        } else {
            Text("no preview", fontSize = 9.sp, color = Color(0xFF808080))
        }
    }
}

@Composable
private fun RenameDialog(e: SphereLibrary.Entry, onDismiss: () -> Unit,
                         onConfirm: (String?) -> Unit) {
    var text by remember { mutableStateOf(e.name ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (e.name == null) "Name this sphere" else "Rename") },
        text = {
            Column {
                OutlinedTextField(text, { text = it },
                    label = { Text("Where was this?") },
                    placeholder = { Text("Kitchen, morning") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onConfirm(text) }),
                    modifier = Modifier.fillMaxWidth())
                if (e.name != null) {
                    Spacer(Modifier.height(6.dp))
                    Text("Clearing it puts the sphere back to being listed by its date.",
                        style = MaterialTheme.typography.bodySmall, color = Color(0xFFB0B0B0))
                }
            }
        },
        confirmButton = { TextButton({ onConfirm(text) }) { Text("Save") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

// ---------------------------------------------------------------- presentation

/** The name, or when it was shot - never a blank line. */
private fun titleOf(e: SphereLibrary.Entry, context: Context): String =
    e.name ?: dateOf(e, context)

private fun dateOf(e: SphereLibrary.Entry, context: Context): String {
    val d = Date(e.capturedAtMillis)
    val date = android.text.format.DateFormat.getMediumDateFormat(context).format(d)
    val time = android.text.format.DateFormat.getTimeFormat(context).format(d)
    return "$date, $time"
}

private fun factsOf(e: SphereLibrary.Entry): String {
    // Said either way round, because both answers are worth having: that the
    // frames are still there is where the space went, and that they are gone is
    // why this sphere can no longer be re-processed.
    val frames = if (e.hasRawFrames) "  raw ${sizeOf(e.rawBytes)}" else "  no raw frames"
    val s = e.summary ?: return sizeOf(e.bytesOnDisk) + frames
    return String.format(Locale.US, "%d dirs  %.1f stops  %s%s",
        s.directions, s.dynamicRangeStops, sizeOf(e.bytesOnDisk), frames)
}

/**
 * The one line that decides whether a sphere is worth keeping.
 *
 * Only shown when there is something to say. A row that always carries a warning
 * colour is a row whose warning colour means nothing.
 */
private fun cautionOf(e: SphereLibrary.Entry): String? {
    val s = e.summary ?: return null
    val notes = ArrayList<String>(4)
    if (s.highlightsAreLowerBound) notes.add("top is a bound")
    if (s.directionsOnPriorAlone > 0) notes.add("${s.directionsOnPriorAlone} on the prior")
    // Three states, not two. A capture made before the colour matrix was carried
    // through has no colorSpace in its report at all, and "camera RGB" would be a
    // claim about it that nothing on disk supports - even though it happens to be
    // true. What is knowable is that nobody wrote it down.
    when (s.colorSpace) {
        "linear-rec709" -> {}
        "camera-rgb" -> notes.add("camera RGB, not Rec.709")
        else -> notes.add("colour space not recorded")
    }
    if (s.directionsTotal > s.directions) notes.add("${s.directionsTotal - s.directions} unplaced")
    return if (notes.isEmpty()) null else notes.joinToString("  ·  ")
}

private fun sizeOf(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(Locale.US, "%d MB", bytes shr 20)
    else -> String.format(Locale.US, "%d kB", bytes shr 10)
}

