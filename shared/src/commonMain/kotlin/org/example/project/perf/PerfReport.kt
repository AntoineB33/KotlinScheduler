package org.example.project.perf

import org.example.project.scheduler.platform.Diagnostics

/**
 * Formats a [PerfSnapshot] as the text the overlay shows and the dump file holds.
 *
 * The sections are ranked by **cost per second**, not by total or by worst case. A profiler that ranks by
 * total tells you what the app has done since it started, which is dominated by whatever ran first; what
 * decides smoothness is the share of each wall-clock second a thing consumes, and a 1000 ms/s budget makes
 * "12% of one core" legible as a number rather than a ratio the reader has to compute.
 */
object PerfReport {

    /** Anything above this share of a second is called out — a third of the budget on one derivation. */
    private const val HOT_MILLIS_PER_SECOND = 333.0

    /**
     * A human-readable report of [window] (a [PerfSnapshot.since] diff) with [total] for the lifetime
     * columns. [title] names what was measured.
     */
    fun format(title: String, window: PerfSnapshot, total: PerfSnapshot): String = buildString {
        val seconds = window.uptimeNanos / 1_000_000_000.0
        appendLine("== $title ==")
        appendLine("window: ${fmt2(seconds)} s   uptime: ${fmt2(total.uptimeNanos / 1_000_000_000.0)} s")
        appendLine()

        appendLine("-- frames --")
        val f = window.frames
        if (f.sampled == 0) {
            appendLine("no frames sampled (is the frame meter composed?)")
        } else {
            appendLine(
                "fps ${fmt1(f.fps)}   p50 ${fmtMs(f.p50Nanos)}   p95 ${fmtMs(f.p95Nanos)}   " +
                    "mean ${fmtMs(f.meanNanos)}   jank ${f.jankFrames}/${f.sampled}   frames ${f.totalFrames}",
            )
        }
        appendLine()

        appendLine("-- runtime --")
        val p = window.platform
        appendLine(
            "cpu ${if (p.processCpuLoad < 0) "n/a" else fmt1(p.processCpuLoad * 100) + "%"} of " +
                "${p.cpuCores} cores   threads ${p.threadCount}",
        )
        appendLine(
            "heap ${fmtMb(p.heapUsedBytes)} used / ${fmtMb(p.heapCommittedBytes)} committed / " +
                "${fmtMb(p.heapMaxBytes)} max   gc ${p.gcCount} in ${p.gcTimeMillis} ms",
        )
        appendLine()

        appendLine("-- sections, by cost per second of wall clock (budget 1000 ms/s) --")
        val ranked = window.sections
            .filter { it.calls > 0 }
            .map { it to (if (seconds <= 0.0) 0.0 else it.totalNanos / 1_000_000.0 / seconds) }
            .sortedByDescending { it.second }
        if (ranked.isEmpty()) {
            appendLine("(nothing recorded)")
        } else {
            appendLine(pad("name", 44) + pad("ms/s", 10) + pad("calls/s", 10) + pad("mean", 10) + "worst")
            ranked.forEach { (stat, msPerSecond) ->
                val callsPerSecond = if (seconds <= 0.0) 0.0 else stat.calls / seconds
                val mean = if (stat.calls == 0L) 0L else stat.totalNanos / stat.calls
                appendLine(
                    pad((if (msPerSecond >= HOT_MILLIS_PER_SECOND) "! " else "  ") + stat.name, 44) +
                        pad(fmt1(msPerSecond), 10) +
                        pad(fmt1(callsPerSecond), 10) +
                        pad(fmtMs(mean), 10) +
                        fmtMs(stat.maxNanos),
                )
            }
        }
        appendLine()

        appendLine("-- counters, per second --")
        val counters = window.counters.filter { it.calls > 0 }.sortedByDescending { it.calls }
        if (counters.isEmpty()) {
            appendLine("(nothing recorded)")
        } else {
            counters.forEach {
                val perSecond = if (seconds <= 0.0) 0.0 else it.calls / seconds
                appendLine(pad("  " + it.name, 44) + pad(fmt1(perSecond), 10) + "(${it.calls} in window)")
            }
        }
        appendLine()

        appendLine("-- live sizes (gauge: current / high water) --")
        if (total.gauges.isEmpty()) {
            appendLine("(nothing recorded)")
        } else {
            total.gauges.sortedByDescending { it.value }.forEach {
                appendLine(pad("  " + it.name, 44) + pad(it.value.toString(), 12) + it.max.toString())
            }
        }
        appendLine()

        appendLine("-- growth (least-squares slope over the leak samples) --")
        val trends = PerfLeakWatch.trends()
        if (trends.isEmpty()) {
            appendLine("(fewer than two samples — take more, or wait out the sampling interval)")
        } else {
            trends.forEach {
                val unit = if (it.name == "heap") "bytes" else "items"
                appendLine(
                    pad("  " + it.name, 44) +
                        pad(fmt1(it.perHour) + " $unit/h", 24) +
                        "${it.first} -> ${it.last} over ${it.samples} samples",
                )
            }
        }

        if (Perf.droppedNames.isNotEmpty()) {
            appendLine()
            appendLine("!! instrument table full, dropped: ${Perf.droppedNames.joinToString(", ")}")
        }
    }

    /**
     * Writes [text] to the diagnostics timeline, one line per line, tagged so
     * `scripts/collect-diagnostics.bat` merges a profile into the same chronology as the calendar bands —
     * a stutter and the sync/derive that caused it then read as one story instead of two files.
     */
    fun dumpToDiagnostics(text: String) {
        text.lineSequence().forEach { Diagnostics.log("perf| $it") }
    }

    private fun pad(s: String, width: Int) = if (s.length >= width) s + " " else s + " ".repeat(width - s.length)

    private fun fmtMs(nanos: Long) = fmt2(nanos / 1_000_000.0) + "ms"

    private fun fmtMb(bytes: Long) = if (bytes < 0) "n/a" else fmt1(bytes / 1_048_576.0) + "MB"

    /** Fixed-point formatting; `toString()` on a Double is target-dependent and would not line the columns up. */
    private fun fmt1(v: Double): String = fixed(v, 1)

    private fun fmt2(v: Double): String = fixed(v, 2)

    private fun fixed(v: Double, decimals: Int): String {
        if (v.isNaN() || v.isInfinite()) return "n/a"
        var scale = 1L
        repeat(decimals) { scale *= 10 }
        val scaled = kotlin.math.round(v * scale).toLong()
        val negative = scaled < 0
        val abs = if (negative) -scaled else scaled
        val whole = abs / scale
        val frac = (abs % scale).toString().padStart(decimals, '0')
        return (if (negative) "-" else "") + whole + "." + frac
    }
}
