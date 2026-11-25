package app.nolva.data

import app.nolva.core.DocumentInfo
import app.nolva.core.DocxOptions
import app.nolva.core.FolderInfo
import app.nolva.core.NolvaCoreInterface
import app.nolva.core.NolvaException
import app.nolva.core.PageCorners
import app.nolva.core.PageFilter as CorePageFilter
import app.nolva.core.PageInfo
import app.nolva.core.PageTextInfo
import app.nolva.core.RecognizedLine as CoreLine
import app.nolva.core.TextRect as CoreRect
import app.nolva.core.PdfOptions
import app.nolva.core.PaperSize as CorePaperSize
import app.nolva.core.NormalizedPoint as CorePoint
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * [DocumentRepository], [DocumentEditor], [PageCropper], [PdfExporter],
 * [OrphanFileCleaner] and [PageTextStore] backed by the Rust core.
 *
 * [onPageImagesChanged] runs after a page gets a new image (added, cropped
 * or filtered), so its text can be recognized again.
 *
 * Core calls are synchronous and may block on disk I/O, so every call runs on
 * [ioDispatcher]. The core itself is created lazily on that dispatcher too:
 * opening it loads the native library and the SQLite database, which must not
 * happen on the main thread.
 */
class CoreDocumentRepository(
    private val ioDispatcher: CoroutineDispatcher,
    private val onPageImagesChanged: () -> Unit = {},
    coreFactory: () -> NolvaCoreInterface,
) : DocumentRepository,
    DocumentEditor,
    PageCropper,
    PdfExporter,
    DocxExporter,
    DocumentTrash,
    FolderStore,
    OrphanFileCleaner,
    PageTextStore {

    private val core by lazy(coreFactory)

    override suspend fun listDocuments(): List<Document> = call {
        core.listDocuments().map { it.toDocument() }
    }

    override suspend fun createDocument(title: String): Document = call {
        core.createDocument(title).toDocument()
    }

    override suspend fun addPage(documentId: String, image: File): Page = call {
        core.addPage(documentId, image.absolutePath).toPage()
    }.also { onPageImagesChanged() }

    override suspend fun listPages(documentId: String): List<Page> = call {
        core.listPages(documentId).map { it.toPage() }
    }

    override suspend fun renameDocument(documentId: String, title: String): Document = call {
        core.renameDocument(documentId, title).toDocument()
    }

    override suspend fun deleteDocument(documentId: String) = call {
        core.deleteDocument(documentId)
    }

    override suspend fun listFolders(): List<Folder> = call {
        core.listFolders().map { it.toFolder() }
    }

    override suspend fun createFolder(name: String): Folder = call {
        core.createFolder(name).toFolder()
    }

    override suspend fun renameFolder(folderId: String, name: String): Folder = call {
        core.renameFolder(folderId, name).toFolder()
    }

    override suspend fun deleteFolder(folderId: String) = call {
        core.deleteFolder(folderId)
    }

    override suspend fun moveDocuments(documentIds: List<String>, folderId: String?): Int = call {
        core.moveDocuments(documentIds, folderId).toInt()
    }

    override suspend fun createDocumentIn(title: String, folderId: String?): Document = call {
        core.createDocumentInFolder(title, folderId).toDocument()
    }

    override suspend fun duplicateDocument(documentId: String, title: String): Document = call {
        core.duplicateDocument(documentId, title).toDocument()
    }

    override suspend fun moveToTrash(documentId: String) = call {
        core.moveDocumentToTrash(documentId)
    }

    override suspend fun restore(documentId: String): Document = call {
        core.restoreDocument(documentId).toDocument()
    }

    override suspend fun listTrash(): List<Document> = call {
        core.listTrashedDocuments().map { it.toDocument() }
    }

    override suspend fun emptyTrash(): Int = call { core.emptyTrash().toInt() }

    override suspend fun purgeExpired(): Int = call { core.purgeExpiredTrash().toInt() }

    override suspend fun deletePage(pageId: String) = call {
        core.deletePage(pageId)
    }

    override suspend fun movePage(pageId: String, toPosition: Int): List<Page> = call {
        core.movePage(pageId, toPosition.coerceAtLeast(0).toUInt()).map { it.toPage() }
    }

    override suspend fun exportPdf(documentId: String, output: File, settings: PdfSettings): PdfExport = call {
        val options = PdfOptions(
            paperSize = settings.paperSize.toCore(),
            marginMm = 0f,
            pageNumbers = settings.pageNumbers,
        )
        val info = core.exportPdf(documentId, output.absolutePath, options)
        PdfExport(output, info.pageCount.toInt(), info.sizeBytes.toLong())
    }

    override suspend fun removeOrphanFiles(): CleanupResult = call {
        val info = core.removeOrphanFiles()
        CleanupResult(info.filesRemoved.toInt(), info.bytesFreed.toLong())
    }

    override suspend fun exportDocx(documentId: String, output: File, includeImages: Boolean): DocxExport = call {
        val info = core.exportDocx(documentId, output.absolutePath, DocxOptions(includeImages = includeImages))
        DocxExport(output, info.pageCount.toInt(), info.sizeBytes.toLong(), info.pagesPendingText.toInt())
    }

    override suspend fun getPage(pageId: String): Page = call {
        core.getPage(pageId).toPage()
    }

    override suspend fun detectCorners(pageId: String): CornerDetection? = call {
        core.detectPageCorners(pageId)?.let { CornerDetection(it.corners.toCorners(), it.confidence) }
    }

    override suspend fun cropPage(pageId: String, corners: Corners): Page = call {
        core.cropPage(pageId, corners.toPageCorners()).toPage()
    }.also { onPageImagesChanged() }

    override suspend fun applyFilter(pageId: String, filter: PageFilter): Page = call {
        core.setPageFilter(pageId, filter.toCore()).toPage()
    }.also { onPageImagesChanged() }

    override suspend fun rotatePage(pageId: String, clockwise: Boolean): Page = call {
        core.rotatePage(pageId, clockwise).toPage()
    }.also { onPageImagesChanged() }

    override suspend fun setRendering(pageId: String, rendering: PageRendering): Page = call {
        core.setPageRendering(
            pageId,
            rendering.corners?.toPageCorners(),
            rendering.filter.toCore(),
            rendering.rotationDegrees.coerceAtLeast(0).toUInt(),
        ).toPage()
    }.also { onPageImagesChanged() }

    override suspend fun pagesNeedingText(limit: Int): List<Page> = call {
        core.pagesNeedingText(limit.coerceAtLeast(0).toUInt()).map { it.toPage() }
    }

    override suspend fun savePageText(pageId: String, image: File, lines: List<RecognizedLine>): PageText = call {
        core.savePageText(pageId, image.absolutePath, lines.map { it.toCore() }).toPageText()
    }

    override suspend fun getPageText(pageId: String): PageText? = call {
        core.getPageText(pageId)?.toPageText()
    }

    override suspend fun searchText(query: String): List<TextMatch> = call {
        core.searchText(query).map { TextMatch(it.documentId, it.pageId, it.pagePosition.toInt(), it.snippet) }
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
    trashedAt = trashedAtMs?.let(Instant::ofEpochMilli),
    folderId = folderId,
    sizeBytes = sizeBytes.toLong(),
)

private fun FolderInfo.toFolder() = Folder(
    id = id,
    name = name,
    createdAt = Instant.ofEpochMilli(createdAtMs),
    updatedAt = Instant.ofEpochMilli(updatedAtMs),
    documentCount = documentCount.toInt(),
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
    rotationDegrees = rotationDegrees.toInt(),
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

private fun RecognizedLine.toCore() = CoreLine(
    block = block.coerceAtLeast(0).toUInt(),
    text = text,
    bounds = CoreRect(bounds.left, bounds.top, bounds.right, bounds.bottom),
)

private fun PageTextInfo.toPageText() = PageText(
    pageId = pageId,
    text = text,
    lines = lines.map { line ->
        RecognizedLine(
            block = line.block.toInt(),
            text = line.text,
            bounds = TextRect(line.bounds.left, line.bounds.top, line.bounds.right, line.bounds.bottom),
        )
    },
)

private fun NolvaException.toDocumentException(): DocumentException = when (this) {
    is NolvaException.InvalidArgument -> DocumentException.InvalidInput(reason, this)
    is NolvaException.NotFound -> DocumentException.NotFound(reason, this)
    is NolvaException.Unsupported -> DocumentException.Unsupported(reason, this)
    is NolvaException.Storage -> DocumentException.Storage(reason, this)
    is NolvaException.Conflict -> DocumentException.Conflict(reason, this)
}
