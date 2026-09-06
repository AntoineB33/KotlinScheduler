package org.example.project.perf

/**
 * The browser exposes no per-process heap or CPU to script (`performance.memory` is Chrome-only, coarsened
 * against timing attacks, and absent under cross-origin isolation), so the web build reports "unavailable"
 * and the frame meter carries the whole picture there.
 */
actual fun readPerfPlatformStats(): PerfPlatformStats = PerfPlatformStats.Unavailable

actual fun forceGcAndReadHeapBytes(): Long = -1L
