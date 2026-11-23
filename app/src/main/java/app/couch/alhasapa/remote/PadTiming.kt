package app.couch.alhasapa.remote

/**
 * How the phone's touchpad reaches the TV, in numbers, for the report (the paw felt slow on the TV's home in 1.9.3, and nothing said
 * where the time went). For each stroke of the finger: how far apart the moves arrived, how long each was held up on the way, and how
 * long it then waited for the TV's main thread.
 *
 * "Held up on the way" needs the time the phone stamped on the move (from 1.9.4). The two devices' clocks have nothing to do with each
 * other, so the quickest move of the stroke is taken as "no hold-up" and the others are measured against it. A home network that keeps a
 * sleeping TV's packets back and hands them over in bunches (Wi-Fi power saving) shows up here as moves that are a tenth of a second or
 * more late.
 *
 * No Android in here; times are in milliseconds from any steady clock.
 */
class PadTiming {
    private val gaps = ArrayList<Long>()
    private val transit = ArrayList<Long>()
    private val waits = ArrayList<Long>()
    private var lastArrival = NONE
    private var first = NONE
    private var moves = 0

    /** A move arrived at [at]; the phone sent it at [sent] by its own clock (0 when the phone's app is too old to say). */
    @Synchronized
    fun arrived(at: Long, sent: Long) {
        if (first == NONE) first = at
        if (lastArrival != NONE && gaps.size < MAX) gaps += (at - lastArrival).coerceAtLeast(0)
        lastArrival = at
        moves++
        if (sent > 0 && transit.size < MAX) transit += at - sent
    }

    /** A move waited [ms] between arriving and the main thread acting on it. */
    @Synchronized
    fun waited(ms: Long) { if (waits.size < MAX) waits += ms.coerceAtLeast(0) }

    /** The stroke so far in one line, and a clean slate; null when there were too few moves to say anything. */
    @Synchronized
    fun summary(): String? {
        val n = moves
        val text = if (n < MIN_MOVES) null else buildString {
            val seconds = (lastArrival - first) / 1000.0
            append("touchpad: $n moves in ${"%.1f".format(java.util.Locale.US, seconds)} s")
            if (gaps.isNotEmpty()) {
                val g = gaps.sorted()
                append("; apart: half within ${at(g, 0.5)} ms, 9 in 10 within ${at(g, 0.9)} ms, the longest ${g.last()} ms")
            }
            if (transit.size >= MIN_MOVES) {
                val quickest = transit.min()
                val late = transit.map { it - quickest }.sorted()
                append("; held up on the way: half under ${at(late, 0.5)} ms, 9 in 10 under ${at(late, 0.9)} ms, the worst ${late.last()} ms")
                val bad = late.count { it >= LATE_MS }
                if (bad > 0) append(" ($bad of ${late.size} by $LATE_MS ms or more)")
            } else append("; the phone's app does not say when it sent them")
            if (waits.isNotEmpty()) {
                val w = waits.sorted()
                append("; then waited for the TV: half under ${at(w, 0.5)} ms, the longest ${w.last()} ms")
            }
        }
        gaps.clear(); transit.clear(); waits.clear(); lastArrival = NONE; first = NONE; moves = 0
        return text
    }

    private fun at(sorted: List<Long>, q: Double): Long = sorted[((sorted.size - 1) * q).toInt()]

    companion object {
        private const val NONE = Long.MIN_VALUE
        private const val MAX = 4_000

        /** Fewer moves than this is a nudge, not a stroke. */
        const val MIN_MOVES = 8

        /** A move this late is felt. */
        const val LATE_MS = 50L
    }
}
