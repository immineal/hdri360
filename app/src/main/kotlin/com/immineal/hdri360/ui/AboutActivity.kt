package com.immineal.hdri360.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * What this app is, what it is built on, and what its licence is.
 *
 * Every method named here is one the code actually implements and cites in its
 * own comments - there is no third-party library in the app outside Compose, so
 * a screen of dependency licences would be a screen of one entry. What is worth
 * naming instead is the published work the arithmetic comes from, because
 * somebody who wants to check whether a number out of this app is a real
 * measurement needs to know which definition of that number was used.
 */
class AboutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize(), color = Color.Black,
                        contentColor = Color(0xFFECECEC)) {
                    AboutScreen(versionName())
                }
            }
        }
    }

    private fun versionName(): String = try {
        val p = packageManager.getPackageInfo(packageName, 0)
        val code = if (android.os.Build.VERSION.SDK_INT >= 28) p.longVersionCode
                   else @Suppress("DEPRECATION") p.versionCode.toLong()
        "${p.versionName} ($code)"
    } catch (e: Exception) {
        "unknown version"
    }
}

@Composable
private fun AboutScreen(version: String) {
    Column(Modifier.fillMaxSize().safeDrawingPadding()
        .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {

        Spacer(Modifier.height(16.dp))
        Text("360 HDRI Camera", style = MaterialTheme.typography.titleLarge)
        Text(version, style = MaterialTheme.typography.bodySmall, color = Color(0xFFB0B0B0))

        Section("What it makes")
        Body("A full sphere of linear radiance, written as an OpenEXR in linear " +
             "Rec.709. One unit in the file is 1000 cd/m2. Nothing in the pipeline " +
             "tone maps, gamma encodes or clips: the numbers that come out are the " +
             "numbers that went in, scaled once and recorded in the report beside " +
             "the file.")

        Section("Where the numbers come from")
        Body("Exposures are the camera's own, requested one at a time with the " +
             "auto-exposure, auto-white-balance and noise reduction switched off, " +
             "and read back from the result rather than assumed. Radiance is " +
             "recovered per rung from exposure time, sensitivity and aperture, and " +
             "the rungs are combined by an inverse-variance weighted mean with a " +
             "roll-off at the top of the sensor's range so that a nearly saturated " +
             "sample contributes nearly nothing.")

        Section("The methods it uses")
        Method("HDR merge", "Debevec and Malik, 'Recovering High Dynamic Range " +
            "Radiance Maps from Photographs', SIGGRAPH 1997. The camera response " +
            "recovery is theirs; the merge is the weighted mean over exposure that " +
            "follows from it.",
            "https://www.pauldebevec.com/Research/HDR/")
        Method("Photometric scale", "ISO 12232:2019, saturation-based speed. " +
            "S = 78 / H_sat, which is what turns a sensor fraction into cd/m2 " +
            "rather than into a number that merely looks like one. Named without a " +
            "link: the standard is not published openly.")
        Method("Feature matching", "ORB-style oriented FAST corners with BRIEF " +
            "descriptors, matched by Hamming distance with Lowe's ratio test and a " +
            "cross-check. The ratio test is from Lowe's SIFT work.",
            "https://www.cs.ubc.ca/~lowe/keypoints/")
        Method("Robust fitting", "RANSAC (Fischler and Bolles, 1981) over minimal " +
            "rotation samples, then a Levenberg-Marquardt refinement of the whole " +
            "pose graph.")
        Method("Rotation solving", "The closed-form orthogonal Procrustes solution " +
            "(Kabsch, 1976; Wahba's problem), via a 3x3 SVD, with the determinant " +
            "forced positive so the answer is a rotation and never a reflection.")
        Method("Lens model", "Pinhole intrinsics with one Brown-Conrady radial " +
            "term (Brown, 1971), solved only when the data supports it and otherwise " +
            "left alone.")
        Method("Colour", "The camera's own COLOR_CORRECTION_TRANSFORM and white " +
            "balance gains, composed into one matrix and applied to merged " +
            "radiance - not per rung, which is a magenta sphere. The primaries are " +
            "ITU-R BT.709.", "https://www.itu.int/rec/R-REC-BT.709")
        Method("File format", "OpenEXR, half float, uncompressed scanlines, " +
            "written by this app rather than by a library.",
            "https://openexr.com/")

        Section("Privacy")
        Body("This app collects nothing, sends nothing and has no account. That is " +
             "not a promise about intent, it is a property of the build: there is " +
             "no network permission in the manifest, so it cannot open a connection " +
             "at all. Android will not let it.")
        Body("Captures live in the app's own private storage - the frames, the " +
             "poses, the sphere, the report, the name you gave it and a short " +
             "capture log kept so a capture that goes wrong can be explained. " +
             "Uninstalling deletes all of it, and automatic backup is switched off, " +
             "so none of it is copied to a cloud backup either.")
        Body("No analytics, no crash reporting, no advertising or device " +
             "identifier, no contacts, no location. The direction a frame was shot " +
             "in is a compass and gyroscope reading relative to the room, not a " +
             "position on the earth - there is no location permission.")
        Body("The one thing that leaves is the EXR you export, which goes to your " +
             "Downloads folder because you asked it to. The full text is " +
             "docs/privacy.md in the source.")

        Section("Licence")
        Method("This app", "GNU General Public License, version 3 or later. You " +
            "may use, study, change and share it; if you distribute a changed " +
            "version you have to pass on the same freedoms and the source. The full " +
            "text ships with the source as LICENSE.",
            "https://www.gnu.org/licenses/gpl-3.0.html")
        Body("The spheres you shoot are yours. Nothing in the licence of this " +
             "program reaches the pictures it takes.")

        Section("Third-party code")
        Method("Jetpack Compose and AndroidX", "Apache License 2.0. Nothing else: " +
            "the whole of the capture, merge, stitch and file writing is in this " +
            "repository, which is why the methods above are worth naming at all.",
            "https://www.apache.org/licenses/LICENSE-2.0")

        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(22.dp))
    Text(title, style = MaterialTheme.typography.titleSmall, color = Color(0xFFFFC400))
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = Color(0xFFD0D0D0))
    Spacer(Modifier.height(8.dp))
}

/**
 * One method, with a link where there is one worth giving.
 *
 * Only links this app is sure of. A wrong citation is worse than none - it sends
 * somebody checking the arithmetic to the wrong paper - so where the canonical
 * address is not certain, or the standard is behind a paywall, the work is named
 * precisely and left for the reader to find.
 */
@Composable
private fun Method(name: String, detail: String, url: String? = null) {
    val context = LocalContext.current
    val open = {
        if (url != null) {
            // No network permission is needed to hand a URL to a browser, and
            // none is declared: this app cannot itself reach the network.
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) { /* no browser on this phone */ }
        }
    }
    Card(Modifier.fillMaxWidth().let { if (url != null) it.clickable(onClick = open) else it }) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(name, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                color = Color(0xFFECECEC))
            Text(detail, style = MaterialTheme.typography.bodySmall, color = Color(0xFF9A9A9A))
            if (url != null) {
                Text(url, fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                    color = Color(0xFFFFC400), textDecoration = TextDecoration.Underline)
            }
        }
    }
    Spacer(Modifier.height(8.dp))
}
