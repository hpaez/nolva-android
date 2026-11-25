package app.nolva.ui.annotate

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import app.nolva.data.NormalizedPoint
import app.nolva.data.PageAnnotation
import app.nolva.data.Signature
import kotlin.math.hypot
import kotlin.math.max

/** A placed signature's width, as a share of the page image's width… */
private const val SIGNATURE_WIDTH = 0.4f

/** …unless that would make it taller than this share of the image's height. */
private const val SIGNATURE_MAX_HEIGHT = 0.25f

/** Approximate glyph width of sans-serif text, as a share of the font size. */
private const val GLYPH_WIDTH = 0.55f

/** Line spacing of text annotations, as a share of the font size (as in PDFs). */
internal const val LINE_HEIGHT = 1.2f

/**
 * [signature] as pen strokes centred on [center] of an image of [image]
 * size, keeping its shape and inside the image.
 */
internal fun placeSignature(
    signature: Signature,
    center: NormalizedPoint,
    image: Size,
    color: Int,
    width: Float,
): PageAnnotation.Ink {
    var boxWidth = image.width * SIGNATURE_WIDTH
    var boxHeight = boxWidth / signature.aspectRatio
    if (boxHeight > image.height * SIGNATURE_MAX_HEIGHT) {
        boxHeight = image.height * SIGNATURE_MAX_HEIGHT
        boxWidth = boxHeight * signature.aspectRatio
    }
    val left = (center.x * image.width - boxWidth / 2).coerceIn(0f, max(0f, image.width - boxWidth))
    val top = (center.y * image.height - boxHeight / 2).coerceIn(0f, max(0f, image.height - boxHeight))
    val strokes = signature.strokes.map { stroke ->
        stroke.map { p ->
            NormalizedPoint((left + p.x * boxWidth) / image.width, (top + p.y * boxHeight) / image.height)
        }
    }
    return PageAnnotation.Ink(strokes, color, width)
}

/** Strokes drawn on the signature pad, fitted into their own box. */
internal data class NormalizedSignature(val strokes: List<List<NormalizedPoint>>, val aspectRatio: Float)

/**
 * Fits [strokes] (pad pixels) into a `0..1` box, keeping their shape, or
 * `null` if nothing was drawn. A single straight line still gets some
 * thickness, so it doesn't become infinitely wide.
 */
internal fun normalizeSignature(strokes: List<List<Offset>>): NormalizedSignature? {
    val points = strokes.flatten()
    if (points.isEmpty()) return null
    val minX = points.minOf { it.x }
    val minY = points.minOf { it.y }
    val spanX = points.maxOf { it.x } - minX
    val spanY = points.maxOf { it.y } - minY
    val floor = max(max(spanX, spanY) * 0.1f, 1f)
    val width = max(spanX, floor)
    val height = max(spanY, floor)
    // Centre the drawing in its box when one side was padded.
    val offsetX = (width - spanX) / 2
    val offsetY = (height - spanY) / 2
    val normalized = strokes.filter { it.isNotEmpty() }.map { stroke ->
        stroke.map { NormalizedPoint((it.x - minX + offsetX) / width, (it.y - minY + offsetY) / height) }
    }
    return NormalizedSignature(normalized, width / height)
}

/**
 * Index of the topmost annotation under [at] on an image of [image] size, or
 * `null`. [tolerance] (image pixels) makes thin strokes easy to hit.
 */
internal fun hitTest(
    annotations: List<PageAnnotation>,
    at: NormalizedPoint,
    image: Size,
    tolerance: Float,
): Int? {
    val unit = minOf(image.width, image.height)
    val touch = Offset(at.x * image.width, at.y * image.height)
    fun px(p: NormalizedPoint) = Offset(p.x * image.width, p.y * image.height)
    return annotations.indices.reversed().firstOrNull { index ->
        when (val a = annotations[index]) {
            is PageAnnotation.Ink -> {
                val reach = max(a.width * unit / 2, tolerance)
                a.strokes.any { stroke ->
                    if (stroke.size == 1) {
                        (px(stroke[0]) - touch).getDistance() <= reach
                    } else {
                        stroke.zipWithNext().any { (p, q) -> distanceToSegment(touch, px(p), px(q)) <= reach }
                    }
                }
            }
            is PageAnnotation.Highlight ->
                touch.x in (a.left * image.width - tolerance)..(a.right * image.width + tolerance) &&
                    touch.y in (a.top * image.height - tolerance)..(a.bottom * image.height + tolerance)
            is PageAnnotation.Text -> {
                val font = a.size * unit
                val lines = a.text.lines()
                val width = lines.maxOf { it.length } * GLYPH_WIDTH * font
                val height = lines.size * LINE_HEIGHT * font
                val left = a.x * image.width
                val top = a.y * image.height
                touch.x in (left - tolerance)..(left + width + tolerance) &&
                    touch.y in (top - tolerance)..(top + height + tolerance)
            }
        }
    }
}

private fun distanceToSegment(p: Offset, a: Offset, b: Offset): Float {
    val ab = b - a
    val lengthSquared = ab.x * ab.x + ab.y * ab.y
    if (lengthSquared == 0f) return (p - a).getDistance()
    val t = (((p.x - a.x) * ab.x + (p.y - a.y) * ab.y) / lengthSquared).coerceIn(0f, 1f)
    val closest = Offset(a.x + t * ab.x, a.y + t * ab.y)
    return hypot(p.x - closest.x, p.y - closest.y)
}
