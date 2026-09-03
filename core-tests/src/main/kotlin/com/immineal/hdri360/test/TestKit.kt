package com.immineal.hdri360.test

import java.util.Locale
import java.util.Random

/**
 * Minimal, dependency-free assertion + reporting harness.
 *
 * Exists because the HDRI core must be verifiable without an Android device,
 * without JUnit on the classpath, and without a network connection.
 *
 * Two things in here are load-bearing and must not be "modernised":
 *  - [rng] hands back java.util.Random, not kotlin.random.Random. Java's is a
 *    48-bit LCG and its nextGaussian consumes a variable number of draws; swap
 *    it and every measured diagnostic in the suite moves.
 *  - [fmt] pins Locale.US, or a comma decimal separator leaks into the notes.
 */
class TestKit {
    private val failuresList = ArrayList<String>()
    private var currentTest = "<none>"
    private var checksCount = 0
    private var testsRunCount = 0
    private var testsFailedCount = 0
    private var failuresAtTestStart = 0
    private val log = StringBuilder()

    /** Fixed-seed RNG so every run is byte-identical. */
    fun rng(seed: Long): Random = Random(seed)

    internal fun beginTest(name: String) {
        currentTest = name
        failuresAtTestStart = failuresList.size
        testsRunCount++
    }

    internal fun endTest() {
        if (failuresList.size > failuresAtTestStart) testsFailedCount++
    }

    fun note(msg: String) {
        log.append("      ").append(msg).append('\n')
    }

    /** Records a failure. Deliberately does not count as a check. */
    fun fail(msg: String) {
        failuresList.add("$currentTest: $msg")
    }

    fun check(cond: Boolean, msg: String) {
        checksCount++
        if (!cond) fail(msg)
    }

    fun eq(expected: Long, actual: Long, msg: String) {
        checksCount++
        if (expected != actual) fail("$msg (expected $expected, got $actual)")
    }

    fun eq(expected: Any?, actual: Any?, msg: String) {
        checksCount++
        val ok = if (expected == null) actual == null else expected == actual
        if (!ok) fail("$msg (expected $expected, got $actual)")
    }

    /** Absolute tolerance comparison. */
    fun near(expected: Double, actual: Double, tol: Double, msg: String) {
        checksCount++
        if (actual.isNaN() || Math.abs(expected - actual) > tol) {
            fail("$msg (expected ${fmt(expected)} +/- ${fmt(tol)}, got ${fmt(actual)})")
        }
    }

    /** Relative tolerance comparison; falls back to absolute near zero. */
    fun nearRel(expected: Double, actual: Double, relTol: Double, msg: String) {
        checksCount++
        val tol = Math.max(1e-12, Math.abs(expected) * relTol)
        if (actual.isNaN() || Math.abs(expected - actual) > tol) {
            fail("$msg (expected ${fmt(expected)} +/- ${relTol * 100}%, got ${fmt(actual)})")
        }
    }

    fun lessThan(actual: Double, bound: Double, msg: String) {
        checksCount++
        if (!(actual < bound)) fail("$msg (needed < ${fmt(bound)}, got ${fmt(actual)})")
    }

    fun greaterThan(actual: Double, bound: Double, msg: String) {
        checksCount++
        if (!(actual > bound)) fail("$msg (needed > ${fmt(bound)}, got ${fmt(actual)})")
    }

    fun arrayNear(expected: DoubleArray, actual: DoubleArray, tol: Double, msg: String) {
        checksCount++
        if (expected.size != actual.size) {
            fail("$msg (length ${expected.size} vs ${actual.size})")
            return
        }
        for (i in expected.indices) {
            if (actual[i].isNaN() || Math.abs(expected[i] - actual[i]) > tol) {
                fail("$msg at [$i] (expected ${fmt(expected[i])}, got ${fmt(actual[i])})")
                return
            }
        }
    }

    /**
     * Catches RuntimeException only, exactly as the Java harness did. Kotlin has
     * no checked exceptions, so suites that expect an IOException must still
     * wrap and rethrow it as a RuntimeException or they would pass vacuously.
     */
    fun throwsException(r: Runnable, msg: String) {
        checksCount++
        try {
            r.run()
            fail("$msg (expected an exception, none thrown)")
        } catch (expected: RuntimeException) {
            // ok
        }
    }

    fun checks(): Int = checksCount
    fun testsRun(): Int = testsRunCount
    fun testsFailed(): Int = testsFailedCount
    fun failures(): List<String> = failuresList
    fun logText(): String = log.toString()

    companion object {
        @JvmStatic
        fun fmt(v: Double): String {
            if (v == Math.rint(v) && Math.abs(v) < 1e9) return v.toLong().toString()
            return String.format(Locale.US, "%.6g", v)
        }
    }
}
