package dk.lawejo.snoretracker

import kotlin.math.log10
import kotlin.math.min
import kotlin.math.sqrt

object Audio {
    const val SAMPLE_RATE = 16_000
    const val WINDOW = 15_600 // 0.975 s, YAMNet's input length
    const val HOP = WINDOW / 2 // 50 % overlap
    const val HOP_MS = HOP * 1000L / SAMPLE_RATE

    fun dbfs(samples: ShortArray, count: Int): Float {
        var sum = 0.0
        for (i in 0 until count) sum += samples[i].toDouble() * samples[i]
        val rms = sqrt(sum / count.coerceAtLeast(1)) / 32768.0
        return (20 * log10(rms.coerceAtLeast(1e-9))).toFloat()
    }
}

/** Keeps the last [capacity] samples so a clip can include the seconds before a snore was detected. */
class RingBuffer(capacity: Int) {
    private val data = ShortArray(capacity)
    private var pos = 0
    var size = 0
        private set

    fun write(src: ShortArray, count: Int) {
        for (i in 0 until count) {
            data[pos] = src[i]
            pos = (pos + 1) % data.size
        }
        size = min(size + count, data.size)
    }

    fun latest(count: Int): ShortArray {
        val n = min(count, size)
        var idx = (pos - n + data.size) % data.size
        return ShortArray(n) { data[idx].also { idx = (idx + 1) % data.size } }
    }
}

/** Background level of the room: 10th percentile of the last minute of block levels. */
class NoiseFloor(private val capacity: Int = (60_000 / Audio.HOP_MS).toInt()) {
    private val values = ArrayDeque<Float>()

    fun add(db: Float): Float {
        values.addLast(db)
        if (values.size > capacity) values.removeFirst()
        val sorted = values.sorted()
        return sorted[sorted.size / 10]
    }
}
