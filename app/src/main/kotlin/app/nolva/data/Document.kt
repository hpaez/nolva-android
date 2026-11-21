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
