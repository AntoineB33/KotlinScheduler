package org.example.project.perf

/**
 * The slow half of the recorder: a bounded series of readings taken minutes apart, whose **slope** is the
 * leak signal.
 *
 * A leak is not a big number, it is a rising one, and none of the instantaneous readings can show that. So
 * this keeps [MAX_SAMPLES] samples of the post-GC heap floor and of every gauge, and reports the
 * least-squares slope per hour for each. What makes a slope trustworthy:
 *
 *  * **Post-GC, not raw.** The raw heap sawtooths between collections; its peak says how fast the app
 *    allocates, not what it retains. Only the floor after a forced collection is a retention measurement,
 *    which is why sampling is deliberately expensive and deliberately rare.
 *  * **Gauges beat the heap.** A collection whose size only grows is a leak with no ambiguity, while a heap
 *    slope of a few MB/h can be a cache filling up to its bound. When both agree the diagnosis is the
 *    gauge's name; when only the heap rises, the leak is in something no gauge covers yet — add one.
 *  * **Sampling is not free and says so.** Each sample forces two collections and sleeps between them, so
 *    it is a visible stall. It is off unless [org.example.project.DebugFlags.PERF] is on, runs on the
 *    interval the overlay chooses, and the frame meter will show its own stalls — which is correct, they
 *    are real.
 */
object PerfLeakWatch {

    /** ~2 h of history at the default 5-minute interval — long enough for a slow leak to show a trend. */
    const val MAX_SAMPLES: Int = 24

    /** How long between samples by default. Each one forces a GC, so this is not a knob to turn down far. */
    const val DEFAULT_INTERVAL_MILLIS: Long = 5 * 60 * 1000L

    /** One reading: the retained heap floor plus every gauge, at a wall-clock instant. */
    data class Sample(
        val atMillis: Long,
        /** Heap in use after a forced collection, or -1 where the platform cannot force one. */
        val retainedHeapBytes: Long,
        val gauges: Map<String, Long>,
    )

    /** A quantity's trend across the samples: its first and last value and the fitted slope. */
    data class Trend(
        val name: String,
        val first: Long,
        val last: Long,
        /** Least-squares slope, in units per hour. Positive means growing. */
        val perHour: Double,
        val samples: Int,
    )

    private val samples = ArrayDeque<Sample>()

    /** Every sample taken so far, oldest first. */
    fun samples(): List<Sample> = samples.toList()

    /**
     * Takes one reading: forces a collection, records the heap floor and the current gauges.
     * Returns the sample, or null when the recorder is off.
     */
    fun sample(nowMillis: Long): Sample? {
        if (!Perf.enabled) return null
        val gauges = Perf.snapshot().gauges.associate { it.name to it.value }
        val sample = Sample(nowMillis, forceGcAndReadHeapBytes(), gauges)
        samples.addLast(sample)
        while (samples.size > MAX_SAMPLES) samples.removeFirst()
        return sample
    }

    fun clear() = samples.clear()

    /**
     * The trend of every measured quantity, worst (fastest-growing) first — the retained heap under the
     * name `heap`, then one entry per gauge.
     *
     * Fewer than two samples means no trend exists yet; an empty list is the honest answer, not a zero.
     */
    fun trends(): List<Trend> {
        val taken = samples.toList()
        if (taken.size < 2) return emptyList()
        val heap = taken.filter { it.retainedHeapBytes >= 0 }
        val out = mutableListOf<Trend>()
        if (heap.size >= 2) {
            out += trend("heap", heap.map { it.atMillis to it.retainedHeapBytes })
        }
        taken.flatMap { it.gauges.keys }.distinct().forEach { name ->
            val points = taken.mapNotNull { s -> s.gauges[name]?.let { s.atMillis to it } }
            if (points.size >= 2) out += trend(name, points)
        }
        return out.sortedByDescending { it.perHour }
    }

    /**
     * Least squares over [points] (`millis to value`), in units per hour.
     *
     * A plain (last − first) / elapsed would let one GC-timing outlier at either end decide the verdict;
     * the fit uses every sample, so a genuinely flat series with noise reads as flat.
     */
    private fun trend(name: String, points: List<Pair<Long, Long>>): Trend {
        val n = points.size
        val t0 = points.first().first
        // Hours since the first sample, so the slope comes out per hour with no later conversion.
        val xs = points.map { (it.first - t0) / 3_600_000.0 }
        val ys = points.map { it.second.toDouble() }
        val meanX = xs.average()
        val meanY = ys.average()
        var num = 0.0
        var den = 0.0
        for (i in 0 until n) {
            val dx = xs[i] - meanX
            num += dx * (ys[i] - meanY)
            den += dx * dx
        }
        // Every sample at the same instant (a clock that did not move) has no slope to fit — report flat.
        val slope = if (den == 0.0) 0.0 else num / den
        return Trend(name, points.first().second, points.last().second, slope, n)
    }
}
