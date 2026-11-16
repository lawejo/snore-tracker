package dk.lawejo.snoretracker

import kotlinx.serialization.Serializable

@Serializable
data class SnoreEvent(
    val startMs: Long,
    val endMs: Long,
    val clip: String?,
    val peakScore: Float,
    val positiveWindows: Int,
)

@Serializable
data class Night(
    val startMs: Long,
    val endMs: Long? = null,
    val heartbeatMs: Long = startMs,
    val events: List<SnoreEvent> = emptyList(),
) {
    /** Time spent actually snoring: positive windows × hop length. */
    val snoreMinutes: Int
        get() = (events.sumOf { it.positiveWindows } * Audio.HOP_MS / 60_000.0).toInt()
}
