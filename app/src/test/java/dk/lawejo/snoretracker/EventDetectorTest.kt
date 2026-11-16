package dk.lawejo.snoretracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventDetectorTest {

    @Test
    fun `two positives are not enough to open an event`() {
        val d = EventDetector()
        assertNull(d.onWindow(0, true, 0.5f))
        assertNull(d.onWindow(1_000, true, 0.5f))
        assertNull(d.flush(2_000))
    }

    @Test
    fun `three positives within a minute open an event that closes after 10 s of silence`() {
        val d = EventDetector()
        d.onWindow(0, true, 0.4f)
        d.onWindow(20_000, true, 0.6f)
        assertEquals(EventDetector.Opened(0), d.onWindow(40_000, true, 0.5f))
        assertNull(d.onWindow(45_000, false, 0f))
        val closed = d.onWindow(50_000, false, 0f)
        assertEquals(EventDetector.Closed(0, 50_000, 3, 0.6f), closed)
    }

    @Test
    fun `positives spread over more than a minute do not open an event`() {
        val d = EventDetector()
        d.onWindow(0, true, 0.5f)
        d.onWindow(40_000, true, 0.5f)
        assertNull(d.onWindow(70_000, true, 0.5f))
    }

    @Test
    fun `short pauses are merged into one event`() {
        val d = EventDetector()
        repeat(3) { d.onWindow(it * 500L, true, 0.5f) }
        assertNull(d.onWindow(8_000, false, 0f))
        assertNull(d.onWindow(9_000, true, 0.5f))
        assertNull(d.onWindow(18_000, false, 0f))
        val closed = d.onWindow(19_000, false, 0f) as EventDetector.Closed
        assertEquals(4, closed.positives)
    }

    @Test
    fun `ring buffer returns the newest samples in order`() {
        val ring = RingBuffer(4)
        ring.write(shortArrayOf(1, 2, 3, 4, 5, 6), 6)
        assertTrue(shortArrayOf(4, 5, 6).contentEquals(ring.latest(3)))
    }
}
