package com.immineal.hdri360.core

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Spreads independent work across cores.
 *
 * The whole radiance path was written single-threaded, and on a phone that
 * leaves seven of eight cores idle through the expensive stages: merging each
 * bracket, describing each frame, and comparing each pair of frames are all
 * independent of one another.
 *
 * The hard requirement is that using more cores must not change a single number.
 * That is met by construction rather than by hope: every unit of work writes
 * only to its own slot in a pre-sized array, and anything order-dependent - the
 * pair list, the correspondence list, the RANSAC seeds - is assembled afterwards
 * in the sequential order, not in completion order. Setting [threads] to 1 has to
 * produce byte-identical output to any other setting, and the suite checks it.
 */
object Parallel {

    /**
     * Cores to use. 1 runs everything inline, with no pool and no threads, which
     * is both the fallback and the reference the parallel path is checked against.
     */
    @JvmStatic
    @Volatile
    var threads: Int = Runtime.getRuntime().availableProcessors()

    // Daemon threads, so a pool that is still alive can never keep a process from
    // exiting - this is a library, and the caller's lifecycle is not ours to hold.
    private val pool by lazy {
        Executors.newCachedThreadPool { r ->
            Thread(r, "hdri-worker").apply { isDaemon = true }
        }
    }

    /**
     * Runs [body] for every index in `0 until count`, in some order, on up to
     * [threads] threads. Returns once all of them have finished.
     *
     * An exception in any unit is rethrown from here, after the others have been
     * given the chance to stop.
     */
    @JvmStatic
    fun forEach(count: Int, body: (Int) -> Unit) {
        if (count <= 0) return
        val t = Math.min(Math.max(1, threads), count)
        if (t == 1) {
            for (i in 0 until count) body(i)
            return
        }
        val next = AtomicInteger(0)
        val failure = AtomicReference<Throwable?>(null)
        val done = java.util.concurrent.CountDownLatch(t)
        for (w in 0 until t) {
            pool.execute {
                try {
                    while (failure.get() == null) {
                        val i = next.getAndIncrement()
                        if (i >= count) break
                        body(i)
                    }
                } catch (e: Throwable) {
                    failure.compareAndSet(null, e)
                } finally {
                    done.countDown()
                }
            }
        }
        done.await()
        failure.get()?.let { throw it }
    }

    /**
     * Splits `0 until count` into contiguous blocks and runs [body] on each.
     *
     * For work whose cost per item is uniform and whose memory access is
     * sequential - image rows, mostly - blocks beat per-item dispatch: the
     * scheduling is free and each thread walks its own stretch of memory.
     */
    @JvmOverloads
    @JvmStatic
    fun forRanges(count: Int, minToSplit: Int = 1, body: (Int, Int) -> Unit) {
        if (count <= 0) return
        // Splitting work that is already cheap makes it slower: the dispatch, the
        // latch and the cache traffic cost more than the arithmetic saved. Measured
        // on the seam grid, where a few local passes over a 256x128 field ran
        // twice as slow spread across cores as they did on one.
        val t = if (count < minToSplit) 1 else Math.min(Math.max(1, threads), count)
        if (t == 1) {
            body(0, count)
            return
        }
        val blocks = Math.min(count, t * 4)          // a little over-subscription evens out stragglers
        val size = (count + blocks - 1) / blocks
        forEach(blocks) { b ->
            val from = b * size
            val to = Math.min(count, from + size)
            if (from < to) body(from, to)
        }
    }
}
