package org.example.project.perf

/**
 * Phone counters. The Dalvik heap is the one that matters on Android (it is what an OOM kills the process
 * over), and it is what `Runtime` reports; the native heap is deliberately not summed in
 * — mixing the two produces a number that matches neither the profiler nor the OOM.
 */
actual fun readPerfPlatformStats(): PerfPlatformStats {
    val runtime = Runtime.getRuntime()
    return PerfPlatformStats(
        heapUsedBytes = runtime.totalMemory() - runtime.freeMemory(),
        heapCommittedBytes = runtime.totalMemory(),
        heapMaxBytes = runtime.maxMemory(),
        // ART exposes no cumulative GC count/time to the process; the profiler is the tool for that, so
        // report "unavailable" rather than a fabricated zero.
        gcCount = -1L,
        gcTimeMillis = -1L,
        processCpuLoad = -1.0,
        threadCount = runCatching { Thread.activeCount() }.getOrDefault(-1),
        cpuCores = runtime.availableProcessors(),
    )
}

actual fun forceGcAndReadHeapBytes(): Long {
    val runtime = Runtime.getRuntime()
    runtime.gc()
    runCatching { Thread.sleep(60) }
    runtime.gc()
    runCatching { Thread.sleep(60) }
    return runtime.totalMemory() - runtime.freeMemory()
}
