package com.immineal.hdri360.test

/** One test group. Implementations must be deterministic. */
interface TestCase {
    fun name(): String
    fun run(t: TestKit)
}
