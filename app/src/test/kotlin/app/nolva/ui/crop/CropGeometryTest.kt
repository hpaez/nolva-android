package app.nolva.ui.crop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import app.nolva.data.NormalizedPoint
import org.junit.Assert.assertEquals
import org.junit.Test

class CropGeometryTest {

    @Test
    fun `portrait photo in a wide container is centred horizontally`() {
        // 3000×4000 photo fitted into 1000×1000: 750×1000, 125 px bars on each side.
        val rect = fittedRect(Size(3000f, 4000f), Size(1000f, 1000f))
        assertEquals(Rect(Offset(125f, 0f), Size(750f, 1000f)), rect)
    }

    @Test
    fun `landscape photo in a tall container is centred vertically`() {
        val rect = fittedRect(Size(4000f, 3000f), Size(1000f, 2000f))
        assertEquals(Rect(Offset(0f, 625f), Size(1000f, 750f)), rect)
    }

    @Test
    fun `screen and normalized coordinates round trip`() {
        val rect = Rect(Offset(125f, 0f), Size(750f, 1000f))
        val point = NormalizedPoint(0.2f, 0.75f)

        val screen = toScreen(point, rect)

        assertEquals(Offset(275f, 750f), screen)
        assertEquals(point, toNormalized(screen, rect))
    }

    @Test
    fun `touches outside the photo clamp to its edge`() {
        val rect = Rect(Offset(125f, 0f), Size(750f, 1000f))
        assertEquals(NormalizedPoint(0f, 1f), toNormalized(Offset(10f, 1200f), rect))
    }
}
