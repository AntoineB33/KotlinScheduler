package org.example.project.perf

/**
 * The runtime's own counters, read straight from the platform — the half of a performance picture the app
 * cannot instrument for itself.
 *
 * Every field is `-1` when the platform cannot answer it, never 0: "this JVM has done no GC" and "this
 * target has no GC counters" are different facts and the report must not print the second as the first.
 */
data class PerfPlatformStats(
    /** Bytes of heap in use. */
    val heapUsedBytes: Long,
    /** Bytes the runtime has reserved from the OS (used + free). */
    val heapCommittedBytes: Long,
    /** Ceiling the heap may grow to. */
    val heapMaxBytes: Long,
    /** Completed garbage collections since start, all collectors summed. */
    val gcCount: Long,
    /** Milliseconds spent collecting since start, all collectors summed. */
    val gcTimeMillis: Long,
    /** This process's share of total CPU, 0..1 across all cores (so 2.0 is impossible; 1.0 is every core). */
    val processCpuLoad: Double,
    /** Live threads in the process. A number that only grows is a leak of coroutine dispatchers or timers. */
    val threadCount: Int,
    /** Cores available to the runtime — what [processCpuLoad] is a fraction of. */
    val cpuCores: Int,
) {
    companion object {
        /** The reading a target that answers nothing returns. */
        val Unavailable: PerfPlatformStats =
            PerfPlatformStats(-1L, -1L, -1L, -1L, -1L, -1.0, -1, -1)
    }
}

/** This instant's platform counters. Must be cheap — the overlay calls it about once a second. */
expect fun readPerfPlatformStats(): PerfPlatformStats

/**
 * Requests a collection and returns the heap in use afterwards, or -1 where that is not possible.
 *
 * This is the ONLY honest heap-leak reading. `heapUsedBytes` sawtooths with allocation, so a rising raw
 * heap proves nothing; what a leak looks like is the floor after a collection rising sample over sample.
 * Called only from the explicit "GC + measure" button and from [PerfLeakWatch]'s slow sampler, never on a
 * hot path — a forced collection is itself a multi-millisecond stall.
 */
expect fun forceGcAndReadHeapBytes(): Long
