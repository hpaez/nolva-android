package app.nolva.data

import app.nolva.core.DocumentInfo
import app.nolva.core.NolvaCoreInterface
import app.nolva.core.NolvaException
import app.nolva.core.PageInfo
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * [DocumentRepository] backed by the Rust core.
 *
 * Core calls are synchronous and may block on disk I/O, so every call runs on
 * [ioDispatcher]. The core itself is created lazily on that dispatcher too:
 * opening it loads the native library and the SQLite database, which must not
 * happen on the main thread.
 */
class CoreDocumentRepository(
    private val ioDispatcher: CoroutineDispatcher,
    coreFactory: () -> NolvaCoreInterface,
) : DocumentRepository {

    private val core by lazy(coreFactory)

    override suspend fun listDocuments(): List<Document> = call {
        core.listDocuments().map { it.toDocument() }
    }

    override suspend fun createDocument(title: String): Document = call {
        core.createDocument(title).toDocument()
    }

    override suspend fun addPage(documentId: String, image: File): Page = call {
        core.addPage(documentId, image.absolutePath).toPage()
    }

    override suspend fun listPages(documentId: String): List<Page> = call {
        core.listPages(documentId).map { it.toPage() }
    }

    private suspend fun <T> call(block: () -> T): T = withContext(ioDispatcher) {
        try {
            block()
        } catch (e: NolvaException) {
            throw e.toDocumentException()
        }
    }
}

private fun DocumentInfo.toDocument() = Document(
    id = id,
    title = title,
    createdAt = Instant.ofEpochMilli(createdAtMs),
    updatedAt = Instant.ofEpochMilli(updatedAtMs),
    pageCount = pageCount.toInt(),
)

private fun PageInfo.toPage() = Page(
    id = id,
    documentId = documentId,
    position = position.toInt(),
    originalFile = File(originalPath),
    createdAt = Instant.ofEpochMilli(createdAtMs),
)

private fun NolvaException.toDocumentException(): DocumentException = when (this) {
    is NolvaException.InvalidArgument -> DocumentException.InvalidInput(reason, this)
    is NolvaException.NotFound -> DocumentException.NotFound(reason, this)
    is NolvaException.Unsupported -> DocumentException.Unsupported(reason, this)
    is NolvaException.Storage -> DocumentException.Storage(reason, this)
}
