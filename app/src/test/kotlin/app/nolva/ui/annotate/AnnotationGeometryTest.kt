package app.nolva.ui.annotate

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import app.nolva.data.NormalizedPoint
import app.nolva.data.PageAnnotation
import app.nolva.data.Signature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationGeometryTest {

    private val page = Size(1000f, 2000f)

    private fun assertNear(expected: Float, actual: Float) = assertEquals(expected, actual, 1e-4f)

    @Test
    fun `signatures keep their shape where they are placed`() {
        // A 4:1 signature: 40% of the page width, a quarter as tall.
        val signature = Signature("s", listOf(listOf(NormalizedPoint(0f, 0f), NormalizedPoint(1f, 1f))), 4f)

        val ink = placeSignature(signature, NormalizedPoint(0.5f, 0.5f), page, 0x1A4D8F, 0.004f)

        val (a, b) = ink.strokes.single()
        assertNear(0.3f, a.x)
        assertNear(0.7f, b.x)
        // 400 px wide → 100 px tall, on a 2000 px page: 5% of its height.
        assertNear(0.05f, b.y - a.y)
        assertEquals(0x1A4D8F, ink.color)
    }

    @Test
    fun `signatures placed at an edge stay on the page`() {
        val signature = Signature("s", listOf(listOf(NormalizedPoint(0f, 0f), NormalizedPoint(1f, 1f))), 2f)

        val ink = placeSignature(signature, NormalizedPoint(0.99f, 0.99f), page, 0, 0.004f)

        val points = ink.strokes.flatten()
        assertTrue(points.all { it.x in 0f..1f && it.y in 0f..1f })
        assertNear(1f, points.maxOf { it.x })
        assertNear(1f, points.maxOf { it.y })
    }

    @Test
    fun `tall signatures are limited by the page height`() {
        val signature = Signature("s", listOf(listOf(NormalizedPoint(0f, 0f), NormalizedPoint(1f, 1f))), 0.5f)

        val ink = placeSignature(signature, NormalizedPoint(0.5f, 0.5f), Size(1000f, 1000f), 0, 0.004f)

        val (a, b) = ink.strokes.single()
        assertNear(0.25f, b.y - a.y)
        assertNear(0.125f, b.x - a.x)
    }

    @Test
    fun `drawn signatures are fitted into their own box`() {
        val drawn = listOf(
            listOf(Offset(100f, 50f), Offset(300f, 150f)),
            listOf(Offset(500f, 100f)),
        )

        val signature = normalizeSignature(drawn)!!

        assertNear(4f, signature.aspectRatio) // 400 × 100
        assertEquals(NormalizedPoint(0f, 0f), signature.strokes[0][0])
        assertEquals(NormalizedPoint(1f, 0.5f), signature.strokes[1][0])
        assertNull(normalizeSignature(emptyList()))
    }

    @Test
    fun `a straight line still gets a sane box`() {
        val signature = normalizeSignature(listOf(listOf(Offset(0f, 10f), Offset(200f, 10f))))!!

        assertNear(10f, signature.aspectRatio)
        assertTrue(signature.strokes.flatten().all { it.y == 0.5f })
    }

    @Test
    fun `the eraser hits the topmost annotation under the finger`() {
        val line = PageAnnotation.Ink(
            listOf(listOf(NormalizedPoint(0.1f, 0.5f), NormalizedPoint(0.9f, 0.5f))),
            color = 0,
            width = 0.002f,
        )
        val marker = PageAnnotation.Highlight(0.4f, 0.45f, 0.6f, 0.55f, color = 0xFFEB3B)
        val note = PageAnnotation.Text("Hola", x = 0.1f, y = 0.1f, size = 0.05f, color = 0)
        val annotations = listOf(line, marker, note)

        // Where the marker covers the line, the marker is on top.
        assertEquals(1, hitTest(annotations, NormalizedPoint(0.5f, 0.5f), page, tolerance = 10f))
        // Near the line, within tolerance (8 px away on a 2000 px page).
        assertEquals(0, hitTest(annotations, NormalizedPoint(0.2f, 0.504f), page, tolerance = 10f))
        // Inside the text's box: 4 letters × 0.55 × 50 px wide.
        assertEquals(2, hitTest(annotations, NormalizedPoint(0.15f, 0.12f), page, tolerance = 10f))
        assertNull(hitTest(annotations, NormalizedPoint(0.9f, 0.9f), page, tolerance = 10f))
    }
}
