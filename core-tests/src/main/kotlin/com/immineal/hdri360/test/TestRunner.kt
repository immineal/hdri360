package com.immineal.hdri360.test

import com.immineal.hdri360.test.suites.BracketPlannerSuite
import com.immineal.hdri360.test.suites.CameraModelSuite
import com.immineal.hdri360.test.suites.ImageSuite
import com.immineal.hdri360.test.suites.MergeSuite
import com.immineal.hdri360.test.suites.MeteringSuite
import com.immineal.hdri360.test.suites.MathSuite
import com.immineal.hdri360.test.suites.ToneMapSuite
import com.immineal.hdri360.test.suites.VignetteSuite
import com.immineal.hdri360.test.suites.ResponseCurveSuite
import com.immineal.hdri360.test.suites.EquirectSuite
import com.immineal.hdri360.test.suites.CapturePlanSuite
import com.immineal.hdri360.test.suites.OrientationSuite
import com.immineal.hdri360.test.suites.RotationSolveSuite
import com.immineal.hdri360.test.suites.PhotometricSuite
import com.immineal.hdri360.test.suites.BundleAdjustSuite
import com.immineal.hdri360.test.suites.FeatureSuite
import com.immineal.hdri360.test.suites.HorizonSuite
import com.immineal.hdri360.test.suites.BlendSuite
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Locale
import kotlin.system.exitProcess

/**
 * Runs the whole core suite. Exit code 0 on success, 1 on any failure.
 *
 * The Java original resolved suites by name with Class.forName so the tree still
 * ran while a suite was being written. Kotlin uses constructor references
 * instead, but the same "not written yet" behaviour is preserved by allowing a
 * null factory: the suite is reported as MISS and, in strict mode (what CI and
 * `gradle test` use), fails the run. That is what makes the Java -> Kotlin port
 * possible one layer at a time with the suite green the whole way.
 */
object TestRunner {

    /** Registry. Order is roughly bottom-up through the pipeline. */
    val SUITES: List<Pair<String, (() -> TestCase)?>> = listOf(
        "MathSuite" to ::MathSuite,
        "ImageSuite" to ::ImageSuite,
        "CameraModelSuite" to ::CameraModelSuite,
        "MeteringSuite" to ::MeteringSuite,
        "BracketPlannerSuite" to ::BracketPlannerSuite,
        "MergeSuite" to ::MergeSuite,
        "ResponseCurveSuite" to ::ResponseCurveSuite,
        "VignetteSuite" to ::VignetteSuite,
        "CapturePlanSuite" to ::CapturePlanSuite,
        "FeatureSuite" to ::FeatureSuite,
        "RotationSolveSuite" to ::RotationSolveSuite,
        "BundleAdjustSuite" to ::BundleAdjustSuite,
        "EquirectSuite" to ::EquirectSuite,
        "HorizonSuite" to ::HorizonSuite,
        "BlendSuite" to ::BlendSuite,
        "PhotometricSuite" to ::PhotometricSuite,
        "WriterSuite" to null,
        "StreamingSuite" to null,
        "ToneMapSuite" to ::ToneMapSuite,
        "OrientationSuite" to ::OrientationSuite,
        "PipelineSuite" to null,
    )

    /** @return a human-readable report; throws AssertionError if anything failed. */
    @JvmStatic
    fun runAll(verbose: Boolean): String = run(verbose, true)

    @JvmStatic
    fun run(verbose: Boolean, strict: Boolean): String {
        val t = TestKit()
        val out = StringBuilder()
        val missing = ArrayList<String>()
        val t0 = System.nanoTime()
        for ((suiteName, factory) in SUITES) {
            val c: TestCase = try {
                factory?.invoke() ?: throw NoSuchElementException(suiteName)
            } catch (e: Exception) {
                missing.add(suiteName)
                out.append("  MISS  ").append(suiteName).append(" (not compiled)\n")
                continue
            }
            val before = t.failures().size
            val s0 = System.nanoTime()
            t.beginTest(c.name())
            try {
                c.run(t)
            } catch (e: Throwable) {
                t.fail("threw $e")
                if (verbose) {
                    val sw = StringWriter()
                    e.printStackTrace(PrintWriter(sw))
                    out.append(sw)
                }
            }
            t.endTest()
            val ms = (System.nanoTime() - s0) / 1000000L
            val added = t.failures().size - before
            out.append(if (added == 0) "  PASS  " else "  FAIL  ")
                .append(pad(c.name(), 22))
                .append(String.format(Locale.US, "%6d ms", ms))
            if (added != 0) out.append("   ").append(added).append(" failure(s)")
            out.append('\n')
        }
        val totalMs = (System.nanoTime() - t0) / 1000000L
        if (verbose && t.logText().isNotEmpty()) out.append(t.logText())
        out.append('\n').append(t.testsRun()).append(" suites, ")
            .append(t.checks()).append(" assertions, ")
            .append(t.failures().size).append(" failures, ")
            .append(totalMs).append(" ms\n")
        val bad = t.failures().isNotEmpty() || (strict && missing.isNotEmpty())
        if (bad) {
            if (missing.isNotEmpty()) out.append("\nMISSING SUITES: ").append(missing).append('\n')
            if (t.failures().isNotEmpty()) {
                out.append("\nFAILURES:\n")
                for (f in t.failures()) out.append("  - ").append(f).append('\n')
            }
            throw AssertionError(out.toString())
        }
        return out.toString()
    }

    private fun pad(s: String, n: Int): String {
        val b = StringBuilder(s)
        while (b.length < n) b.append(' ')
        return b.toString()
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val a = args.toList()
        val verbose = a.contains("-v")
        val strict = !a.contains("--allow-missing")
        try {
            print(run(verbose, strict))
        } catch (e: AssertionError) {
            print(e.message)
            exitProcess(1)
        }
    }
}
