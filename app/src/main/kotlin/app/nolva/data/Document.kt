package app.nolva.data

import java.io.File
import java.time.Instant

/**
 * App-level models. The UI depends on these, never on the UniFFI-generated
 * types, so changes in the core's FFI surface stay contained in the data layer.
 */
data class Document(
    val id: String,
    val title: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val pageCount: Int,
)

data class Page(
    val id: String,
    val documentId: String,
    /** Zero-based position within the document. */
    val position: Int,
    /** Untouched original image, managed by the core. Never modify it. */
    val originalFile: File,
    val createdAt: Instant,
    /** Cropped, perspective-corrected image, once the page has been cropped. */
    val processedFile: File? = null,
    /** Corners used for [processedFile], to reopen the crop editor. */
    val corners: Corners? = null,
    /** Filter applied on top of the crop. */
    val filter: PageFilter = PageFilter.Original,
) {
    /** What to show the user: the processed image when there is one. */
    val displayFile: File get() = processedFile ?: originalFile
}

/** Look applied to a page on top of its crop; always rendered from the original. */
enum class PageFilter {
    Original,
    Grayscale,

    /** Pure black ink on white paper: best for text. */
    BlackAndWhite,

    /** Evens out lighting and shadows, keeping colors. */
    Enhanced,
}

/** A point in normalized coordinates (`0..1`) of the original image as displayed. */
data class NormalizedPoint(val x: Float, val y: Float)

/** A page outline, clockwise from the top left. */
data class Corners(
    val topLeft: NormalizedPoint,
    val topRight: NormalizedPoint,
    val bottomRight: NormalizedPoint,
    val bottomLeft: NormalizedPoint,
) {
    companion object {
        /** The whole image: the starting point when no sheet is detected. */
        val FULL = Corners(
            topLeft = NormalizedPoint(0f, 0f),
            topRight = NormalizedPoint(1f, 0f),
            bottomRight = NormalizedPoint(1f, 1f),
            bottomLeft = NormalizedPoint(0f, 1f),
        )
    }
}

/** A sheet found automatically in a photo. */
data class CornerDetection(
    val corners: Corners,
    /** `0..1`; low values mean the user should check the corners. */
    val confidence: Float,
)

/** Failures surfaced by [DocumentRepository], independent of the core's FFI errors. */
sealed class DocumentException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class InvalidInput(message: String, cause: Throwable? = null) : DocumentException(message, cause)
    class NotFound(message: String, cause: Throwable? = null) : DocumentException(message, cause)
    class Unsupported(message: String, cause: Throwable? = null) : DocumentException(message, cause)
    class Storage(message: String, cause: Throwable? = null) : DocumentException(message, cause)
}

/** Maximum title length, mirroring the core's validation (MAX_TITLE_CHARS). */
const val MAX_TITLE_LENGTH = 200
