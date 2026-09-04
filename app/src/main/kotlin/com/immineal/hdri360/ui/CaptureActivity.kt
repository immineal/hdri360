package com.immineal.hdri360.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immineal.hdri360.core.Parallel
import com.immineal.hdri360.core.camera.Intrinsics
import com.immineal.hdri360.core.hdr.BracketConfig
import com.immineal.hdri360.core.hdr.BracketPlanner
import com.immineal.hdri360.core.hdr.DeviceExposureLimits
import com.immineal.hdri360.core.hdr.Photometry
import com.immineal.hdri360.core.hdr.SceneStats
import com.immineal.hdri360.core.pano.CapturePlan
import com.immineal.hdri360.core.pano.CapturePlanConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * For now this only proves the wiring: that the radiance core runs unchanged on
 * the device, inside an app, and that its answers there are the ones it gives
 * everywhere else. The capture UI replaces it.
 */
class CaptureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val report by produceState("running the core...") {
                        value = withContext(Dispatchers.Default) { selfCheck() }
                    }
                    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                        Text("360 HDRI Camera", style = MaterialTheme.typography.titleLarge)
                        Text(report, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

/**
 * Exercises the parts of the core the app will lean on, and prints numbers that
 * can be checked against the desktop rather than merely "it did not crash".
 */
private fun selfCheck(): String {
    val sb = StringBuilder()
    fun line(s: String) = sb.append(s).append('\n')

    line("cores: ${Runtime.getRuntime().availableProcessors()}, " +
         "worker threads: ${Parallel.threads}")
    line("")

    // The capture plan the guidance overlay will draw.
    val k = Intrinsics.fromHorizontalFov(3000, 4000, 58.7)
    val plan = CapturePlan.forCamera(k, CapturePlanConfig())
    line(String.format(Locale.US, "capture plan  %.1f x %.1f deg -> %d directions",
        k.horizontalFovDeg(), k.verticalFovDeg(), plan.targets.size))

    // The exposure ladder, from the Pixel 9a's own reported limits.
    val limits = DeviceExposureLimits(1.0 / 17554, 16.0, 29, 7276, 29, 1.7, 1.0 / 15.0)
    val scene = listOf(
        SceneStats(1e2, 2e5, 4500.0, 0.0, 0.0, false, false),
        SceneStats(1e-1, 2e1, 1.4, 0.0, 0.0, false, false))
    val bracket = BracketPlanner.plan(scene, limits, BracketConfig())
    line(String.format(Locale.US, "bracket plan  %d rungs, %d shots, %.1f EV span",
        bracket.ladder.size(), bracket.totalShots(), bracket.ladder.evSpan()))

    // The photometric scale, which is only meaningful on the RAW+manual tier.
    line(String.format(Locale.US, "photometry    %.4g cd/m2 per unit at f/1.7, base ISO 29",
        Photometry.luminanceScale(1.7, 29)))
    line(String.format(Locale.US, "              headroom %.4f stops above middle grey",
        Photometry.headroomStops()))
    line("")
    line("These must match the desktop exactly; if they do,")
    line("the core is behaving identically on this device.")
    return sb.toString()
}
