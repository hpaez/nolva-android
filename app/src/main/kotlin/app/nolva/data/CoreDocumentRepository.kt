package app.nolva.data

import app.nolva.core.DocumentInfo
import app.nolva.core.NolvaCoreInterface
import app.nolva.core.NolvaException
import app.nolva.core.PageCorners
import app.nolva.core.PageFilter as CorePageFilter
import app.nolva.core.PageInfo
import app.nolva.core.PdfOptions
import app.nolva.core.PaperSize as CorePaperSize
import app.nolva.core.NormalizedPoint as CorePoint
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * [DocumentRepository], [DocumentEditor], [PageCropper], [PdfExporter] and
 * [OrphanFileCleaner] backed by the Rust core.
 *
 * Core calls are synchronous and may block on disk I/O, so every call runs on
 * [ioDispatcher]. The core itself is created lazily on that dispatcher too:
 * opening it loads the native library and the SQLite database, which must not
 * happen on the main thread.
 */
class CoreDocumentRepository(
    private val ioDispatcher: CoroutineDispatcher,
    coreFactory: () -> NolvaCoreInterface,
) : DocumentRepository, DocumentEditor, PageCropper, PdfExporter, OrphanFileCleaner {

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

    override suspend fun renameDocument(documentId: String, title: String): Document = call {
        core.renameDocument(documentId, title).toDocument()
    }

    override suspend fun deleteDocument(documentId: String) = call {
        core.deleteDocument(documentId)
    }

    override suspend fun deletePage(pageId: String) = call {
        core.deletePage(pageId)
    }

    override suspend fun movePage(pageId: String, toPosition: Int): List<Page> = call {
        core.movePage(pageId, toPosition.coerceAtLeast(0).toUInt()).map { it.toPage() }
    }

    override suspend fun exportPdf(documentId: String, output: File, paperSize: PaperSize): PdfExport = call {
        val options = PdfOptions(paperSize = paperSize.toCore(), marginMm = 0f)
        val info = core.exportPdf(documentId, output.absolutePath, options)
        PdfExport(output, info.pageCount.toInt(), info.sizeBytes.toLong())
    }

    override suspend fun removeOrphanFiles(): CleanupResult = call {
        val info = core.removeOrphanFiles()
        CleanupResult(info.filesRemoved.toInt(), info.bytesFreed.toLong())
    }

    override suspend fun getPage(pageId: String): Page = call {
        core.getPage(pageId).toPage()
    }

    override suspend fun detectCorners(pageId: String): CornerDetection? = call {
        core.detectPageCorners(pageId)?.let { CornerDetection(it.corners.toCorners(), it.confidence) }
    }

    override suspend fun cropPage(pageId: String, corners: Corners): Page = call {
        core.cropPage(pageId, corners.toPageCorners()).toPage()
    }

    override suspend fun applyFilter(pageId: String, filter: PageFilter): Page = call {
        core.setPageFilter(pageId, filter.toCore()).toPage()
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
    coverFile = coverPath?.let(::File),
)

private fun PageInfo.toPage() = Page(
    id = id,
    documentId = documentId,
    position = position.toInt(),
    originalFile = File(originalPath),
    createdAt = Instant.ofEpochMilli(createdAtMs),
    processedFile = processedPath?.let(::File),
    corners = corners?.toCorners(),
    filter = filter.toPageFilter(),
)

private fun PageFilter.toCore() = when (this) {
    PageFilter.Original -> CorePageFilter.ORIGINAL
    PageFilter.Grayscale -> CorePageFilter.GRAYSCALE
    PageFilter.BlackAndWhite -> CorePageFilter.BLACK_AND_WHITE
    PageFilter.Enhanced -> CorePageFilter.ENHANCED
}

private fun CorePageFilter.toPageFilter() = when (this) {
    CorePageFilter.ORIGINAL -> PageFilter.Original
    CorePageFilter.GRAYSCALE -> PageFilter.Grayscale
    CorePageFilter.BLACK_AND_WHITE -> PageFilter.BlackAndWhite
    CorePageFilter.ENHANCED -> PageFilter.Enhanced
}

private fun PaperSize.toCore() = when (this) {
    PaperSize.A4 -> CorePaperSize.A4
    PaperSize.Letter -> CorePaperSize.LETTER
    PaperSize.Legal -> CorePaperSize.LEGAL
    PaperSize.FitImage -> CorePaperSize.FIT_IMAGE
}

internal fun CorePoint.toPoint() = NormalizedPoint(x, y)

private fun NormalizedPoint.toCorePoint() = CorePoint(x, y)

internal fun PageCorners.toCorners() = Corners(
    topLeft = topLeft.toPoint(),
    topRight = topRight.toPoint(),
    bottomRight = bottomRight.toPoint(),
    bottomLeft = bottomLeft.toPoint(),
)

private fun Corners.toPageCorners() = PageCorners(
    topLeft = topLeft.toCorePoint(),
    topRight = topRight.toCorePoint(),
    bottomRight = bottomRight.toCorePoint(),
    bottomLeft = bottomLeft.toCorePoint(),
)

private fun NolvaException.toDocumentException(): DocumentException = when (this) {
    is NolvaException.InvalidArgument -> DocumentException.InvalidInput(reason, this)
    is NolvaException.NotFound -> DocumentException.NotFound(reason, this)
    is NolvaException.Unsupported -> DocumentException.Unsupported(reason, this)
    is NolvaException.Storage -> DocumentException.Storage(reason, this)
}
