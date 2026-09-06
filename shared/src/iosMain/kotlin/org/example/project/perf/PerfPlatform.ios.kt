package org.example.project.perf

/**
 * iOS answers none of these from Kotlin/Native without a `mach_task_basic_info` interop shim, and the
 * overlay is a desktop/Android debug surface. Reporting "unavailable" keeps the shared code compiling for
 * the iOS target (`compileCommonMainKotlinMetadata` is the portability gate) without inventing numbers.
 */
actual fun readPerfPlatformStats(): PerfPlatformStats = PerfPlatformStats.Unavailable

actual fun forceGcAndReadHeapBytes(): Long = -1L
