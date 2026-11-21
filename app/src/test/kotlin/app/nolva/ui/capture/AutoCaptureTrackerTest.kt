package app.nolva.ui.capture

import app.nolva.data.CornerDetection
import app.nolva.data.Corners
import app.nolva.data.NormalizedPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoCaptureTrackerTest {

    private fun sheet(offset: Float = 0f, confidence: Float = 0.95f) = CornerDetection(
        Corners(
            NormalizedPoint(0.1f + offset, 0.1f),
            NormalizedPoint(0.9f + offset, 0.1f),
            NormalizedPoint(0.9f + offset, 0.9f),
            NormalizedPoint(0.1f + offset, 0.9f),
        ),
        confidence,
    )

    private val tracker = AutoCaptureTracker()

    /** Feeds one frame every 100 ms from [startMs]; returns the results. */
    private fun feed(
        detection: (Int) -> CornerDetection?,
        frames: Int,
        startMs: Long = 0,
        auto: Boolean = true,
    ) = (0 until frames).map { i -> tracker.onFrame(detection(i), 0.75f, startMs + i * 100L, auto) }

    @Test
    fun `captures once the sheet is held still for a second`() {
        val results = feed({ sheet() }, frames = 11)

        assertFalse(results.dropLast(1).any { it.capture })
        assertTrue(results.last().capture)
        assertEquals(0.5f, results[5].guide!!.steadiness, 0.01f)
        assertEquals(1f, results.last().guide!!.steadiness, 0.01f)
    }

    @Test
    fun `small jitter still counts as still, real movement restarts`() {
        val jittery = feed({ i -> sheet(offset = if (i % 2 == 0) 0f else 0.01f) }, frames = 11)
        assertTrue(jittery.last().capture)

        val tracker2 = AutoCaptureTracker()
        val moving = (0 until 15).map { i -> tracker2.onFrame(sheet(offset = i * 0.03f), 0.75f, i * 100L, true) }
        assertFalse(moving.any { it.capture })
    }

    @Test
    fun `does not capture the same sheet twice`() {
        feed({ sheet() }, frames = 11)
        val after = feed({ sheet() }, frames = 30, startMs = 1_100)
        assertFalse(after.any { it.capture })
    }

    @Test
    fun `a new sheet after the previous one left is captured`() {
        feed({ sheet() }, frames = 11)
        feed({ null }, frames = 6, startMs = 1_100) // gone for 600 ms
        val next = feed({ sheet() }, frames = 11, startMs = 1_700)
        assertTrue(next.last().capture)
    }

    @Test
    fun `moving the sheet far enough re-arms without leaving the frame`() {
        feed({ sheet() }, frames = 11)
        val moved = feed({ sheet(offset = 0.2f) }, frames = 11, startMs = 1_100)
        assertTrue(moved.last().capture)
    }

    @Test
    fun `rough outlines are shown but never captured`() {
        val results = feed({ sheet(confidence = 0.7f) }, frames = 20)
        assertNotNull(results.last().guide)
        assertFalse(results.any { it.capture })
        assertEquals(0f, results.last().guide!!.steadiness)
    }

    @Test
    fun `auto capture off still shows the guide`() {
        val results = feed({ sheet() }, frames = 20, auto = false)
        assertNotNull(results.last().guide)
        assertFalse(results.any { it.capture })
    }

    @Test
    fun `the guide survives short dropouts and then disappears`() {
        feed({ sheet() }, frames = 3)
        assertNotNull(tracker.onFrame(null, 0.75f, 500, true).guide)
        assertNull(tracker.onFrame(null, 0.75f, 800, true).guide)
    }
}
