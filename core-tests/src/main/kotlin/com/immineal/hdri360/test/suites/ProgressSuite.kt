package com.immineal.hdri360.test.suites

import com.immineal.hdri360.core.pipeline.StageProgress
import com.immineal.hdri360.core.pipeline.TimeRemaining
import com.immineal.hdri360.core.pipeline.WorkCorrection
import com.immineal.hdri360.test.TestCase
import com.immineal.hdri360.test.TestKit

/**
 * One bar, one journey.
 *
 * A progress bar exists to answer "how much longer", and the two in this app
 * answered it four and two times respectively. The pipeline reports a fraction
 * that runs 0 to 1 **within each stage** - merging, features, matching, aligning,
 * blending - and the service was passing each of those straight through as the
 * overall figure. So the bar filled and reset four times over eight minutes,
 * and the estimate beside it reset with it. The capture screen did the same
 * across two phases: the sweep filled it to a hundred percent and then the
 * capture started it again from nothing.
 *
 * Reported by the owner as "the progress bar doesn't work very well", which is
 * a generous way to put it.
 */
class ProgressSuite : TestCase {
    override fun name(): String = "progress"

    override fun run(t: TestKit) {
        aBarThatOnlyEverGoesForward(t)
        stagesItHasNeverHeardOf(t)
        sharesThatAreNotFractions(t)
        anEstimateThatLearnsFromWhatActuallyHappened(t)
        aCountdownThatWatchesTheJobItIsCountingDown(t)
    }


    /**
     * The estimate has to learn, because the benchmark it starts from is a guess
     * about a different problem.
     *
     * `WorkEstimator.calibrate` times a merge of a 192x144 image, a render and a
     * decode, at startup, once. That is a reasonable way to tell a fast phone
     * from a slow one and a poor way to predict three million pixel frames: cache
     * behaviour, memory bandwidth and thermal limits all differ at scale.
     * Measured on a real 7 direction job at 4K, from the app's own log:
     *
     *     from an estimate of 64 s (merge 28, align 9, render 26, write 1)
     *     ... 11.7 stops in 20.2 s
     *
     * Predicted 64 seconds, took 20. So the size picker quoted three times too
     * long before the person chose, and the bar crawled to a third and jumped.
     *
     * A single correction factor, learned from what the last runs actually cost,
     * is enough to fix that - and it has to be a *multiplicative* factor learned
     * geometrically, because being twice as fast and twice as slow are the same
     * size of mistake.
     */
    private fun anEstimateThatLearnsFromWhatActuallyHappened(t: TestKit) {
        // Knowing nothing, it changes nothing. An app on its first run must quote
        // the benchmark, not a number somebody made up.
        val fresh = WorkCorrection.unlearned()
        t.eq(1.0, fresh.factor, "with nothing learned the estimate is passed through")
        t.eq(64.0, fresh.applyTo(64.0), "unchanged, to the second")

        // One real run, and it moves most of the way there rather than all of it:
        // a single job can be slow for reasons that are not the phone.
        val once = fresh.after(predicted = 64.0, actual = 20.2)
        t.lessThan(once.applyTo(64.0), 64.0, "an over-prediction is corrected downwards")
        t.greaterThan(once.applyTo(64.0), 20.2, "but not all the way on one sample")

        // Repeated evidence converges on the truth.
        var c = WorkCorrection.unlearned()
        for (i in 0 until 12) c = c.after(64.0, 20.2)
        t.near(20.2, c.applyTo(64.0), 1.0,
            "a dozen consistent runs and the estimate is the measurement")

        // It works the other way too - a phone slower than its benchmark.
        var slow = WorkCorrection.unlearned()
        for (i in 0 until 12) slow = slow.after(60.0, 180.0)
        t.near(180.0, slow.applyTo(60.0), 8.0, "an under-prediction is corrected upwards")

        // One absurd run cannot poison it. A job that was paused, throttled, or
        // interrupted by something else on the phone is not evidence about the
        // phone.
        val poisoned = c.after(64.0, 6400.0)
        t.lessThan(poisoned.applyTo(64.0), c.applyTo(64.0) * 4.0,
            "a hundredfold outlier moves the estimate by at most a bounded step")
        t.greaterThan(poisoned.applyTo(64.0), c.applyTo(64.0),
            "though it does move it: it might be true")

        // Nonsense in, nothing learned. These are the shapes a caller really can
        // hand over: a job that failed instantly, or one whose estimate was
        // missing.
        for (bad in arrayOf(
                doubleArrayOf(0.0, 20.0), doubleArrayOf(64.0, 0.0),
                doubleArrayOf(-5.0, 20.0), doubleArrayOf(64.0, -1.0),
                doubleArrayOf(Double.NaN, 20.0), doubleArrayOf(64.0, Double.NaN),
                doubleArrayOf(Double.POSITIVE_INFINITY, 20.0))) {
            val same = c.after(bad[0], bad[1])
            t.eq(c.factor, same.factor,
                "nothing is learned from predicted=${bad[0]} actual=${bad[1]}")
        }

        // A job too short to time says nothing either: at a second or two the
        // measurement is mostly startup.
        t.eq(c.factor, c.after(1.0, 0.4).factor,
            "a job that took under a second teaches nothing about a job that takes minutes")

        // The factor survives a round trip through text, because it has to live
        // in a file between runs.
        val written = c.factor
        val read = WorkCorrection.of(written)
        t.eq(c.factor, read.factor, "a stored factor reads back as itself")
        t.eq(1.0, WorkCorrection.of(Double.NaN).factor, "and rubbish in the file is ignored")
        t.eq(1.0, WorkCorrection.of(0.0).factor, "as is a zero")
        t.check(WorkCorrection.of(1e9).factor <= 100.0, "and a wild value is bounded")
        t.check(WorkCorrection.of(1e-9).factor >= 0.01, "in both directions")
    }


    /**
     * The countdown has to watch the job, not only the estimate it started with.
     *
     * "About N minutes left" was `estimate * (1 - fraction)` and nothing else. So
     * a wrong estimate is wrong for the whole job: at three times too long it
     * counts down from a minute to twenty seconds while twenty seconds is all it
     * ever needed, and at half the true cost it reaches "nearly there" and stays
     * there. [WorkCorrection] fixes the *next* job; this fixes the one running.
     *
     * A job that is a third done has already measured itself better than any
     * benchmark can, so that is where the countdown stops trusting the estimate
     * and starts trusting the clock.
     */
    private fun aCountdownThatWatchesTheJobItIsCountingDown(t: TestKit) {
        // At the start there is nothing but the estimate.
        t.near(65.0, TimeRemaining.seconds(65.0, 0.0, 0.0), 1e-9,
            "before anything has happened, the estimate is all there is")
        t.near(60.0, TimeRemaining.seconds(65.0, 5.0, 0.0), 1e-9,
            "and five seconds in with no progress reported, five seconds less")

        // The real case, measured on the phone: predicted 65 s, actually 21.
        // A third of the way through - 7 s in - the countdown should already
        // have noticed.
        val early = TimeRemaining.seconds(65.0, 7.0, 1.0 / 3.0)
        t.near(21.0 - 7.0, early, 1.0,
            "a third of the way into a 21 second job, about 14 seconds are left")
        t.lessThan(early, 65.0 * (1 - 1.0 / 3.0),
            "which is a great deal less than the estimate would have claimed")

        // And the other way: a job that is running slower than predicted must
        // grow its estimate rather than sit at zero.
        val slow = TimeRemaining.seconds(60.0, 90.0, 0.5)
        t.near(90.0, slow, 2.0, "half way through after 90 s means about 90 s to go")
        t.greaterThan(slow, 0.0, "a job past its estimate still has time left")

        // Between nothing and a third it blends, so the number does not lurch the
        // moment the first progress arrives.
        val tenth = TimeRemaining.seconds(65.0, 2.1, 0.1)
        val third = TimeRemaining.seconds(65.0, 7.0, 1.0 / 3.0)
        t.lessThan(third, tenth, "trust in the clock grows with the evidence")
        t.lessThan(tenth, 65.0, "and even a tenth of the way in it has learned something")

        // Never negative, whatever arrives.
        for (f in doubleArrayOf(0.0, 0.5, 1.0, 1.5)) {
            for (e in doubleArrayOf(0.0, 10.0, 1000.0)) {
                val left = TimeRemaining.seconds(65.0, e, f)
                t.check(left >= 0.0, "never negative at fraction $f after $e s, got $left")
            }
        }
        t.eq(0.0, TimeRemaining.seconds(65.0, 30.0, 1.0), "finished is nothing left")

        // Nonsense in, the best of what is left rather than a crash.
        t.eq(0.0, TimeRemaining.seconds(Double.NaN, Double.NaN, Double.NaN),
            "all-nonsense gives nothing rather than throwing")
        t.near(20.0, TimeRemaining.seconds(0.0, 10.0, 1.0 / 3.0), 1.0,
            "with no estimate at all, the clock alone still answers")
        t.eq(0.0, TimeRemaining.seconds(-5.0, -5.0, 0.5), "and negative inputs give nothing")
    }

    private fun stages() = listOf(
        StageProgress.Stage("merging", 3.0),
        StageProgress.Stage("features", 1.0),
        StageProgress.Stage("matching", 2.0),
        StageProgress.Stage("blending", 1.0))

    /**
     * The property that matters, and the one that was broken: whatever order and
     * whatever fractions arrive, the number handed to the bar never decreases.
     */
    private fun aBarThatOnlyEverGoesForward(t: TestKit) {
        val p = StageProgress(stages())

        t.eq(0.0, p.overall("merging", 0.0), "it starts at nothing")
        t.near(3.0 / 7.0, p.overall("merging", 1.0), 1e-12,
            "a finished stage has advanced by exactly its own share")
        t.near(3.0 / 7.0 + 0.5 / 7.0, p.overall("features", 0.5), 1e-12,
            "and the next one starts where that one ended, not at zero")
        t.near(1.0, p.overall("blending", 1.0), 1e-12, "the last stage ends at one")

        // The real sequence, and the assertion the old code would have failed on
        // its second stage.
        val fresh = StageProgress(stages())
        var last = -1.0
        for (s in stages()) {
            var f = 0.0
            while (f <= 1.0001) {
                val got = fresh.overall(s.name, f)
                t.check(got >= last - 1e-12,
                    "went backwards at ${s.name} $f: $got after $last")
                t.check(got >= -1e-12 && got <= 1.0 + 1e-12, "stayed in range: $got")
                last = got
                f += 0.05
            }
        }
        t.near(1.0, last, 1e-9, "and arrives at one having never gone back")

        // A stage that reports a fraction lower than one already seen - which a
        // pipeline reporting from several threads really does - does not drag the
        // bar back down.
        val jittery = StageProgress(stages())
        jittery.overall("matching", 0.8)
        val back = jittery.overall("matching", 0.2)
        t.check(back >= jittery.overall("matching", 0.0) - 1e-12,
            "a late low reading from the same stage does not rewind the bar")
        t.near(3.0 / 7.0 + 1.0 / 7.0 + 0.8 * 2.0 / 7.0, back, 1e-12,
            "it holds the highest it has reached")

        // Nor does a stage arriving out of order, which is what a retry looks
        // like from here.
        val backwards = StageProgress(stages())
        val atMatching = backwards.overall("matching", 1.0)
        val atMerging = backwards.overall("merging", 0.1)
        t.check(atMerging >= atMatching - 1e-12,
            "and an earlier stage arriving late does not rewind it either")
    }

    /** A stage nobody weighted must not break the bar or stall it. */
    private fun stagesItHasNeverHeardOf(t: TestKit) {
        val p = StageProgress(stages())
        val before = p.overall("merging", 1.0)
        val during = p.overall("polishing the lens", 0.5)
        t.check(during >= before - 1e-12, "an unknown stage holds where the bar was")
        t.check(during <= 1.0, "and never exceeds one")
        t.near(1.0, p.overall("blending", 1.0), 1e-12, "the known stages still finish it")

        // No stages at all is a bar that has nothing to say, not a crash.
        val empty = StageProgress(emptyList())
        t.eq(0.0, empty.overall("merging", 0.5), "with no stages there is no progress to report")
        t.eq(0.0, empty.overall("", 1.0), "and it stays there rather than throwing")
    }

    /**
     * Shares are relative weights, not fractions: whoever writes them down should
     * be able to say "merging is three times features" without doing arithmetic
     * to make them sum to one.
     */
    private fun sharesThatAreNotFractions(t: TestKit) {
        val big = StageProgress(listOf(
            StageProgress.Stage("a", 300.0),
            StageProgress.Stage("b", 100.0)))
        t.near(0.75, big.overall("a", 1.0), 1e-12, "weights of 300 and 100 are three quarters")
        t.near(1.0, big.overall("b", 1.0), 1e-12, "and one")

        // A fraction outside [0, 1] is a bug upstream; it must not become a bar
        // outside [0, 1] here.
        val p = StageProgress(stages())
        t.eq(0.0, p.overall("merging", -5.0), "a negative fraction is nothing, not negative")
        t.near(3.0 / 7.0, p.overall("merging", 7.0), 1e-12,
            "and one past the end is that stage finished, not the bar overrun")

        // A zero or negative weight is a stage that costs nothing, which is
        // allowed: it should not divide by zero or swallow the bar.
        val zero = StageProgress(listOf(
            StageProgress.Stage("a", 0.0),
            StageProgress.Stage("b", 1.0)))
        t.eq(0.0, zero.overall("a", 1.0), "a weightless stage advances nothing")
        t.near(1.0, zero.overall("b", 1.0), 1e-12, "and the rest still reaches one")
        val allZero = StageProgress(listOf(StageProgress.Stage("a", 0.0)))
        t.eq(0.0, allZero.overall("a", 1.0), "nothing weighted anywhere is simply no progress")
    }
}
