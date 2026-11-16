package dk.lawejo.snoretracker

/**
 * Turns per-window yes/no snore decisions into snore events.
 * An event opens after [minPositives] positive windows within [openWindowMs],
 * and closes after [closeAfterMs] without a positive window (so short pauses are merged).
 */
class EventDetector(
    private val minPositives: Int = 3,
    private val openWindowMs: Long = 60_000,
    private val closeAfterMs: Long = 10_000,
) {
    sealed interface Change
    data class Opened(val startMs: Long) : Change
    data class Closed(val startMs: Long, val endMs: Long, val positives: Int, val peak: Float) : Change

    private class Open(val startMs: Long, var lastPositiveMs: Long, var positives: Int, var peak: Float)

    private val recent = ArrayDeque<Pair<Long, Float>>()
    private var open: Open? = null

    fun onWindow(timeMs: Long, positive: Boolean, score: Float): Change? {
        open?.let { o ->
            if (positive) {
                o.lastPositiveMs = timeMs
                o.positives++
                o.peak = maxOf(o.peak, score)
            } else if (timeMs - o.lastPositiveMs >= closeAfterMs) {
                return close(timeMs)
            }
            return null
        }

        if (!positive) return null
        recent.addLast(timeMs to score)
        while (timeMs - recent.first().first > openWindowMs) recent.removeFirst()
        if (recent.size < minPositives) return null

        open = Open(recent.first().first, timeMs, recent.size, recent.maxOf { it.second })
        recent.clear()
        return Opened(open!!.startMs)
    }

    /** Closes a still-open event, e.g. when the night is stopped. */
    fun flush(timeMs: Long): Closed? = if (open != null) close(timeMs) else null

    private fun close(timeMs: Long): Closed {
        val o = open!!
        open = null
        return Closed(o.startMs, timeMs, o.positives, o.peak)
    }
}
