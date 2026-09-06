package org.example.project.perf

/** Same as the JS target: no per-process heap or CPU is available to a page. */
actual fun readPerfPlatformStats(): PerfPlatformStats = PerfPlatformStats.Unavailable

actual fun forceGcAndReadHeapBytes(): Long = -1L
