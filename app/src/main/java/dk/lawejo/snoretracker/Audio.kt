package dk.lawejo.snoretracker

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.sin
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

    /**
     * Energy below 500 Hz relative to 1.7–2.5 kHz, in dB. Snoring is mostly bass;
     * whistling nose breathing has a tone around 2 kHz. Logged only, to test as a filter later.
     */
    fun bassRatio(samples: ShortArray): Float {
        val n = 16_384
        val re = DoubleArray(n) { if (it < samples.size) samples[it].toDouble() else 0.0 }
        val im = DoubleArray(n)
        fft(re, im)
        fun band(from: Int, to: Int) = (from * n / SAMPLE_RATE until to * n / SAMPLE_RATE).sumOf { re[it] * re[it] + im[it] * im[it] }
        return (10 * log10(band(50, 500).coerceAtLeast(1e-9) / band(1_700, 2_500).coerceAtLeast(1e-9))).toFloat()
    }

    /** In-place radix-2 FFT; the size must be a power of two. */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { re[i] = re[j].also { re[j] = re[i] }; im[i] = im[j].also { im[j] = im[i] } }
        }
        var len = 2
        while (len <= n) {
            val angle = -2 * PI / len
            for (start in 0 until n step len) {
                for (k in 0 until len / 2) {
                    val wr = cos(angle * k); val wi = sin(angle * k)
                    val a = start + k; val b = a + len / 2
                    val xr = re[b] * wr - im[b] * wi; val xi = re[b] * wi + im[b] * wr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                }
            }
            len = len shl 1
        }
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
