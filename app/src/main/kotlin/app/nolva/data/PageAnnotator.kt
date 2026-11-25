package app.nolva.data

/**
 * Something drawn over a page: never part of the scan, always on top of it.
 *
 * Coordinates are normalized (`0..1`, origin top left) to the page image as
 * displayed ([Page.displayFile]). Stroke widths and text sizes are fractions
 * of the image's shorter side. Colours are `0xRRGGBB`.
 */
sealed interface PageAnnotation {
    /** Pen strokes: handwriting or a placed signature. */
    data class Ink(val strokes: List<List<NormalizedPoint>>, val color: Int, val width: Float) : PageAnnotation

    /** A translucent marker over a rectangle; the scan shows through. */
    data class Highlight(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val color: Int,
    ) : PageAnnotation

    /** Typed text; its first line's top left is at ([x], [y]). */
    data class Text(val text: String, val x: Float, val y: Float, val size: Float, val color: Int) : PageAnnotation
}

/**
 * A saved handwritten signature. Its strokes are in its own box (`0..1` on
 * both axes), [aspectRatio] (width / height) wide.
 */
data class Signature(val id: String, val strokes: List<List<NormalizedPoint>>, val aspectRatio: Float)

/** Annotations and saved signatures. Main-safe. */
interface PageAnnotator {
    /** The page's annotations, bottom to top. */
    suspend fun getAnnotations(pageId: String): List<PageAnnotation>

    /**
     * Replaces all of the page's annotations.
     *
     * @throws DocumentException.InvalidInput if one can't be drawn, or there
     *   are too many (500 per page, 50 000 points).
     */
    suspend fun setAnnotations(pageId: String, annotations: List<PageAnnotation>)

    /** Saved signatures, oldest first. */
    suspend fun listSignatures(): List<Signature>

    /** @throws DocumentException.InvalidInput if 10 are saved already. */
    suspend fun saveSignature(strokes: List<List<NormalizedPoint>>, aspectRatio: Float): Signature

    /** Pages it was placed on keep it. */
    suspend fun deleteSignature(signatureId: String)
}
