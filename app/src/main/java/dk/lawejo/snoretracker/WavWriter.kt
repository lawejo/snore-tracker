package dk.lawejo.snoretracker

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Writes 16-bit mono PCM to a .wav file. The header is filled in on close. */
class WavWriter(file: File) : Closeable {
    private val raf = RandomAccessFile(file, "rw").apply {
        setLength(0)
        write(ByteArray(HEADER_SIZE))
    }
    private var dataBytes = 0

    val durationMs: Long get() = dataBytes / 2 * 1000L / Audio.SAMPLE_RATE

    fun write(samples: ShortArray, count: Int = samples.size) {
        val buffer = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) buffer.putShort(samples[i])
        raf.write(buffer.array())
        dataBytes += count * 2
    }

    override fun close() {
        val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + dataBytes); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(Audio.SAMPLE_RATE); putInt(Audio.SAMPLE_RATE * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(dataBytes)
        }
        raf.seek(0)
        raf.write(header.array())
        raf.close()
    }

    private companion object {
        const val HEADER_SIZE = 44
    }
}
