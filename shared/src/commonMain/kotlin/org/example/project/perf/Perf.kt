package org.example.project.perf

import kotlin.time.TimeSource

/**
 * In-app performance recorder — the measuring half of the "is the app doing unwanted work?" question.
 *
 * Three kinds of instrument, all read out together by [snapshot]:
 *
 *  * **Sections** ([measure]) — a named span of work, with call count, total and worst-case duration. Wrap
 *    a derivation, a fill, a save. The overlay diffs two snapshots, so what it shows is *per second*: a
 *    section costing 4 ms that runs 30x/s is 120 ms/s out of a 1000 ms budget, and that ratio is the number
 *    that decides whether the app is smooth.
 *  * **Counters** ([count]) — how often something happened at all. Recompositions above everything else:
 *    the CLAUDE.md hot-path rule is about *frequency* as much as cost, and a counter is how you catch a
 *    derivation that is correct, cheap, and running two hundred times a second for no reason.
 *  * **Gauges** ([gauge]) — the size of a live collection (panels, sessions, history units, cached maps).
 *    Their **slope over time** is the leak signal: a gauge that only ever grows is a leak whatever the heap
 *    says, because the heap is noisy and a monotone count is not.
 *
 * ### Cost when off
 * [enabled] is set once at startup ([org.example.project.DebugFlags.PERF]) and never changed. Every entry
 * point is `inline` and returns before touching anything when it is off, so a release build pays one
 * static boolean read per instrumented site and allocates nothing. **Never gate a side effect on this** —
 * `Perf.measure` must wrap work the app does anyway.
 *
 * ### Thread safety
 * Recording happens from the UI thread, from `Dispatchers.Default` (the far-week fill) and from the
 * engine's own scope. Rather than lock a hot path, the registry is **copy-on-write** (a name is added by
 * replacing the whole map — rare, only the first time a name is seen) and the accumulators are
 * **fixed-size `LongArray`s** indexed by that name's slot. A race can therefore lose an update; it can
 * never corrupt a structure or spin. That is the right trade for a profiler: a dropped sample out of
 * thousands moves no conclusion, while a `HashMap` resize race under two threads would hang the very app
 * it is measuring.
 */
object Perf {

    /** Slots for named instruments. Overflow is dropped (and reported by [droppedNames]), never grown. */
    const val MAX_SLOTS: Int = 512

    /**
     * Whether anything is recorded. Set once at startup from the platform entry point, before `App`
     * composes, and never written again — so every reader sees a stable value and no site needs a barrier.
     */
    var enabled: Boolean = false

    /** Names that did not fit in [MAX_SLOTS] — an instrumentation bug, surfaced in the report. */
    var droppedNames: Set<String> = emptySet()
        private set

    private val slotNames = arrayOfNulls<String>(MAX_SLOTS)
    private val slotKind = arrayOfNulls<PerfKind>(MAX_SLOTS)
    private val calls = LongArray(MAX_SLOTS)
    private val totalNanos = LongArray(MAX_SLOTS)
    private val maxNanos = LongArray(MAX_SLOTS)
    private val gaugeValue = LongArray(MAX_SLOTS)
    private val gaugeMax = LongArray(MAX_SLOTS)
    private var used = 0

    /**
     * name -> slot. Replaced wholesale on every new name (copy-on-write), so a reader always holds a
     * complete immutable map and registration needs no lock.
     */
    private var slots: Map<String, Int> = emptyMap()

    /** Frame intervals, wrapping. Sized for ~8 s at 60 Hz so a stutter stays in the window. */
    private val frameNanos = LongArray(512)
    private var frameWrite = 0
    private var frameCount = 0L
    private var startMark = TimeSource.Monotonic.markNow()

    /**
     * The slot for [name], registering it on first sight. Public because [measure] is `inline`; a caller on
     * a very hot path may cache the result in a `val` to skip the map lookup, but a lookup is tens of
     * nanoseconds against sections costing microseconds, so most callers should not bother.
     * Returns -1 when the table is full — every recording entry point ignores that slot.
     */
    fun slot(name: String, kind: PerfKind): Int {
        slots[name]?.let { return it }
        if (used >= MAX_SLOTS) {
            droppedNames = droppedNames + name
            return -1
        }
        val index = used
        slotNames[index] = name
        slotKind[index] = kind
        used = index + 1
        slots = slots + (name to index)
        return index
    }

    /** Records one completed section. Public for [measure]'s sake; prefer [measure]. */
    fun record(slot: Int, nanos: Long) {
        if (slot < 0) return
        calls[slot] = calls[slot] + 1
        totalNanos[slot] = totalNanos[slot] + nanos
        if (nanos > maxNanos[slot]) maxNanos[slot] = nanos
    }

    /**
     * Times [block] under [name]. Returns exactly what [block] returns and rethrows whatever it throws — an
     * instrumented call must be substitutable for the call it wraps, including on the failure path, which is
     * why the recording sits in a `finally`.
     */
    inline fun <T> measure(name: String, block: () -> T): T {
        if (!enabled) return block()
        val slot = slot(name, PerfKind.Section)
        val mark = TimeSource.Monotonic.markNow()
        try {
            return block()
        } finally {
            record(slot, mark.elapsedNow().inWholeNanoseconds)
        }
    }

    /** One occurrence of [name] — a recomposition, a dispatch, a re-plan, a save. */
    fun count(name: String) {
        if (!enabled) return
        val slot = slot(name, PerfKind.Counter)
        if (slot < 0) return
        calls[slot] = calls[slot] + 1
    }

    /**
     * The current size of a live collection. Only the **latest** value and the high-water mark are kept —
     * the leak signal is the trend across snapshots, which [PerfLeakWatch] takes, not a history here.
     */
    fun gauge(name: String, value: Long) {
        if (!enabled) return
        val slot = slot(name, PerfKind.Gauge)
        if (slot < 0) return
        gaugeValue[slot] = value
        if (value > gaugeMax[slot]) gaugeMax[slot] = value
    }

    /** One rendered frame, [nanos] since the previous one. Fed by `PerfFrameMeter`. */
    fun frame(nanos: Long) {
        if (!enabled) return
        frameNanos[frameWrite] = nanos
        frameWrite = (frameWrite + 1) % frameNanos.size
        frameCount++
    }

    /** Clears every accumulator, so a measurement can start from a known point (the overlay's Reset). */
    fun reset() {
        for (i in 0 until used) {
            calls[i] = 0L
            totalNanos[i] = 0L
            maxNanos[i] = 0L
            gaugeMax[i] = gaugeValue[i]
        }
        for (i in frameNanos.indices) frameNanos[i] = 0L
        frameWrite = 0
        frameCount = 0L
        startMark = TimeSource.Monotonic.markNow()
    }

    /** An immutable reading of every instrument plus the platform's own counters. */
    fun snapshot(): PerfSnapshot {
        val sections = ArrayList<PerfStat>(used)
        val counters = ArrayList<PerfStat>(used)
        val gauges = ArrayList<PerfGauge>(used)
        for (i in 0 until used) {
            val name = slotNames[i] ?: continue
            when (slotKind[i]) {
                PerfKind.Section -> sections += PerfStat(name, calls[i], totalNanos[i], maxNanos[i])
                PerfKind.Counter -> counters += PerfStat(name, calls[i], 0L, 0L)
                PerfKind.Gauge -> gauges += PerfGauge(name, gaugeValue[i], gaugeMax[i])
                null -> Unit
            }
        }
        return PerfSnapshot(
            uptimeNanos = startMark.elapsedNow().inWholeNanoseconds,
            sections = sections,
            counters = counters,
            gauges = gauges,
            frames = frameStats(),
            platform = readPerfPlatformStats(),
        )
    }

    private fun frameStats(): PerfFrameStats {
        val filled = if (frameCount >= frameNanos.size) frameNanos.size else frameWrite
        if (filled == 0) return PerfFrameStats(0L, 0, 0L, 0L, 0L, 0)
        val sorted = LongArray(filled) { frameNanos[it] }
        sorted.sort()
        var sum = 0L
        var jank = 0
        for (v in sorted) {
            sum += v
            // "Jank" = an interval longer than two 60 Hz frames. A dropped frame doubles the interval, so
            // this counts visible hitches rather than merely slow frames.
            if (v > JANK_THRESHOLD_NANOS) jank++
        }
        return PerfFrameStats(
            totalFrames = frameCount,
            sampled = filled,
            meanNanos = sum / filled,
            p50Nanos = sorted[filled / 2],
            p95Nanos = sorted[((filled - 1) * 95) / 100],
            jankFrames = jank,
        )
    }

    /** Two 60 Hz frames. Above this the user sees a hitch, not a slow frame. */
    const val JANK_THRESHOLD_NANOS: Long = 33_000_000L
}

/** What an instrument measures — decides how [Perf.snapshot] groups it and how the report formats it. */
enum class PerfKind { Section, Counter, Gauge }

/** One section's or counter's accumulated cost. [totalNanos]/[maxNanos] are 0 for a counter. */
data class PerfStat(
    val name: String,
    val calls: Long,
    val totalNanos: Long,
    val maxNanos: Long,
)

/** One live-collection size and its high-water mark. */
data class PerfGauge(val name: String, val value: Long, val max: Long)

/** Frame-interval statistics over the recorder's rolling window. */
data class PerfFrameStats(
    val totalFrames: Long,
    /** How many intervals the percentiles were computed from (the window is bounded). */
    val sampled: Int,
    val meanNanos: Long,
    val p50Nanos: Long,
    val p95Nanos: Long,
    /** Intervals longer than two 60 Hz frames — visible hitches, not merely slow frames. */
    val jankFrames: Int,
) {
    /** Frames per second implied by the median interval — the number a user calls smooth or not. */
    val fps: Double get() = if (p50Nanos <= 0L) 0.0 else 1_000_000_000.0 / p50Nanos
}

/** Everything read at one instant. Diff two of them for per-second rates (see [since]). */
data class PerfSnapshot(
    val uptimeNanos: Long,
    val sections: List<PerfStat>,
    val counters: List<PerfStat>,
    val gauges: List<PerfGauge>,
    val frames: PerfFrameStats,
    val platform: PerfPlatformStats,
) {
    /**
     * This snapshot minus [previous] — what happened *between* the two readings. Rates, not totals, answer
     * "is the app doing unwanted work": a section's total only ever grows, while its cost per second is
     * flat when the app is idle, and being flat is exactly the property under test.
     *
     * [maxNanos] is deliberately NOT differenced — a worst case cannot be subtracted, and the worst frame
     * of the whole run is the one worth knowing about.
     */
    fun since(previous: PerfSnapshot?): PerfSnapshot {
        if (previous == null) return this
        val before = previous.sections.associateBy { it.name }
        val beforeCounters = previous.counters.associateBy { it.name }
        return copy(
            uptimeNanos = uptimeNanos - previous.uptimeNanos,
            sections = sections.map { stat ->
                val old = before[stat.name]
                if (old == null) {
                    stat
                } else {
                    PerfStat(stat.name, stat.calls - old.calls, stat.totalNanos - old.totalNanos, stat.maxNanos)
                }
            },
            counters = counters.map { stat ->
                val old = beforeCounters[stat.name]
                if (old == null) stat else PerfStat(stat.name, stat.calls - old.calls, 0L, 0L)
            },
        )
    }
}
