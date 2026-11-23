package dk.lawejo.snoretracker

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class AudioTest {

    private fun tone(hz: Double) = ShortArray(Audio.WINDOW) { (8_000 * sin(2 * PI * hz * it / Audio.SAMPLE_RATE)).toInt().toShort() }

    @Test
    fun `bass tone gives a high bass ratio`() = assertTrue(Audio.bassRatio(tone(200.0)) > 20)

    @Test
    fun `2 kHz tone gives a low bass ratio`() = assertTrue(Audio.bassRatio(tone(2_000.0)) < -20)
}
