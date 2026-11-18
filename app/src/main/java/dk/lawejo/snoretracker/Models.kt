package dk.lawejo.snoretracker

import kotlinx.serialization.Serializable

@Serializable
data class SnoreEvent(
    val startMs: Long,
    val endMs: Long,
    val clip: String?,
    val peakScore: Float,
    val positiveWindows: Int,
    /** Marked the next day as something other than snoring. */
    val notSnore: Boolean = false,
)

@Serializable
data class Night(
    val startMs: Long,
    val endMs: Long? = null,
    val heartbeatMs: Long = startMs,
    val events: List<SnoreEvent> = emptyList(),
) {
    /** Events not marked as something other than snoring. */
    val snoreEvents: List<SnoreEvent>
        get() = events.filterNot { it.notSnore }

    /** Time spent actually snoring: positive windows × hop length. */
    val snoreMinutes: Int
        get() = (snoreEvents.sumOf { it.positiveWindows } * Audio.HOP_MS / 60_000.0).toInt()
}
