package org.example.project.perf

import java.lang.management.ManagementFactory

/**
 * Desktop counters, read through JMX.
 *
 * `com.sun.management.OperatingSystemMXBean` carries the process CPU load but is a `jdk.management`
 * extension, so it is reached **reflectively**: the packaged release jlinks a minimal runtime
 * (`desktopApp/build.gradle.kts` lists only `java.sql`), and a hard reference would turn a missing module
 * into a `NoClassDefFoundError` at startup instead of one unavailable number in a debug overlay.
 */
private val osBean = ManagementFactory.getOperatingSystemMXBean()

/** `getProcessCpuLoad` on the `com.sun` bean, or null when this runtime does not expose it. */
private val processCpuLoadMethod: java.lang.reflect.Method? by lazy {
    runCatching {
        Class.forName("com.sun.management.OperatingSystemMXBean")
            .getMethod("getProcessCpuLoad")
            .takeIf { it.declaringClass.isInstance(osBean) }
    }.getOrNull()
}

actual fun readPerfPlatformStats(): PerfPlatformStats {
    val runtime = Runtime.getRuntime()
    val heapUsed = runtime.totalMemory() - runtime.freeMemory()
    var gcCount = 0L
    var gcMillis = 0L
    runCatching {
        for (bean in ManagementFactory.getGarbageCollectorMXBeans()) {
            // A collector that has never run reports -1 for both; summing that would read as "went backwards".
            if (bean.collectionCount > 0) gcCount += bean.collectionCount
            if (bean.collectionTime > 0) gcMillis += bean.collectionTime
        }
    }
    val cpu = runCatching { (processCpuLoadMethod?.invoke(osBean) as? Double) ?: -1.0 }.getOrDefault(-1.0)
    return PerfPlatformStats(
        heapUsedBytes = heapUsed,
        heapCommittedBytes = runtime.totalMemory(),
        heapMaxBytes = runtime.maxMemory(),
        gcCount = gcCount,
        gcTimeMillis = gcMillis,
        // The bean returns a negative value until it has two samples to compare; keep that as "unavailable"
        // rather than clamping it to 0, which would read as a genuinely idle process.
        processCpuLoad = if (cpu < 0.0) -1.0 else cpu,
        threadCount = runCatching { ManagementFactory.getThreadMXBean().threadCount }.getOrDefault(-1),
        cpuCores = runtime.availableProcessors(),
    )
}

actual fun forceGcAndReadHeapBytes(): Long {
    val runtime = Runtime.getRuntime()
    // Twice, with a pause between: the first collection makes objects finalizable and the second reclaims
    // what that released, so a single call systematically over-reports the retained floor.
    runtime.gc()
    runCatching { Thread.sleep(60) }
    runtime.gc()
    runCatching { Thread.sleep(60) }
    return runtime.totalMemory() - runtime.freeMemory()
}
