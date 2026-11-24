package app.nolva.data

import app.nolva.core.CleanupInfo
import app.nolva.core.DocumentInfo
import app.nolva.core.DocxExportInfo
import app.nolva.core.DocxOptions
import app.nolva.core.NolvaCoreInterface
import app.nolva.core.NolvaException
import app.nolva.core.PageCorners
import app.nolva.core.PageFilter as CorePageFilter
import app.nolva.core.PageInfo
import app.nolva.core.PageTextInfo
import app.nolva.core.RecognizedLine as CoreLine
import app.nolva.core.TextSearchHit
import app.nolva.core.PdfExportInfo
import app.nolva.core.PdfOptions
import app.nolva.core.PaperSize as CorePaperSize
import app.nolva.core.CornerDetection as CoreCornerDetection
import app.nolva.core.NormalizedPoint as CorePoint
import java.io.File
import java.time.Instant
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private val coreCorners = PageCorners(
    topLeft = CorePoint(0.1f, 0.2f),
    topRight = CorePoint(0.9f, 0.2f),
    bottomRight = CorePoint(0.9f, 0.8f),
    bottomLeft = CorePoint(0.1f, 0.8f),
)

class CoreDocumentRepositoryTest {

    private class FakeCore : NolvaCoreInterface {
        var failure: NolvaException? = null
        val documents = mutableListOf<DocumentInfo>()

        override fun createDocument(title: String): DocumentInfo {
            failure?.let { throw it }
            return DocumentInfo(
                id = "doc-${documents.size}",
                title = title,
                createdAtMs = 1_000,
                updatedAtMs = 2_000,
                pageCount = 0u,
                coverPath = null,
            ).also { documents += it }
        }

        override fun listDocuments(): List<DocumentInfo> = documents

        var croppedWith: PageCorners? = null

        override fun addPage(documentId: String, imagePath: String): PageInfo = pageInfo(documentId)

        override fun listPages(documentId: String): List<PageInfo> = emptyList()

        override fun getPage(pageId: String): PageInfo = pageInfo("doc-1")

        var savedLines: List<CoreLine>? = null
        var textConflict = false

        override fun pagesNeedingText(limit: UInt): List<PageInfo> = listOf(pageInfo("doc-1"))

        override fun savePageText(pageId: String, imagePath: String, lines: List<CoreLine>): PageTextInfo {
            if (textConflict) throw NolvaException.Conflict("re-cropped")
            savedLines = lines
            return PageTextInfo(pageId, lines.joinToString("\n") { it.text }, lines, recognizedAtMs = 5)
        }

        override fun getPageText(pageId: String): PageTextInfo? = null

        override fun searchText(query: String): List<TextSearchHit> = emptyList()

        var docxOptions: DocxOptions? = null

        override fun exportDocx(documentId: String, outputPath: String, options: DocxOptions): DocxExportInfo {
            docxOptions = options
            return DocxExportInfo(pageCount = 3u, sizeBytes = 2048u, pagesPendingText = 1u)
        }

        override fun removeOrphanFiles(): CleanupInfo = CleanupInfo(filesRemoved = 3u, bytesFreed = 4_096u)

        var exportedWith: PdfOptions? = null

        override fun exportPdf(documentId: String, outputPath: String, options: PdfOptions): PdfExportInfo {
            exportedWith = options
            return PdfExportInfo(pageCount = 2u, sizeBytes = 1234u)
        }

        override fun detectPageCorners(pageId: String): CoreCornerDetection? =
            CoreCornerDetection(corners = coreCorners, confidence = 0.9f)

        override fun cropPage(pageId: String, corners: PageCorners): PageInfo {
            croppedWith = corners
            return pageInfo("doc-1").copy(processedPath = "/data/nolva/doc-1/processed.jpg", corners = corners)
        }

        val calls = mutableListOf<String>()

        override fun renameDocument(documentId: String, title: String): DocumentInfo {
            calls += "rename $documentId $title"
            return DocumentInfo(
                documentId,
                title,
                createdAtMs = 1_000,
                updatedAtMs = 9_000,
                pageCount = 2u,
                coverPath = "/data/nolva/$documentId/cover.jpg",
            )
        }

        override fun deleteDocument(documentId: String) {
            calls += "deleteDocument $documentId"
        }

        override fun deletePage(pageId: String) {
            failure?.let { throw it }
            calls += "deletePage $pageId"
        }

        override fun movePage(pageId: String, toPosition: UInt): List<PageInfo> {
            calls += "move $pageId $toPosition"
            return listOf(pageInfo("doc-1").copy(id = pageId, position = 0u))
        }

        var filteredWith: CorePageFilter? = null

        override fun setPageFilter(pageId: String, filter: CorePageFilter): PageInfo {
            filteredWith = filter
            return pageInfo("doc-1").copy(processedPath = "/data/nolva/doc-1/filtered.jpg", filter = filter)
        }

        private fun pageInfo(documentId: String) = PageInfo(
            id = "page-0",
            documentId = documentId,
            position = 3u,
            originalPath = "/data/nolva/$documentId/original.jpg",
            processedPath = null,
            corners = null,
            filter = CorePageFilter.ORIGINAL,
            createdAtMs = 3_000,
        )
    }

    private val dispatcher = StandardTestDispatcher()

    @Test
    fun `maps core documents to app models`() = runTest(dispatcher) {
        val repository = CoreDocumentRepository(dispatcher) { FakeCore() }

        val created = repository.createDocument("Contract")

        assertEquals("Contract", created.title)
        assertEquals(Instant.ofEpochMilli(1_000), created.createdAt)
        assertEquals(Instant.ofEpochMilli(2_000), created.updatedAt)
        assertEquals(null, created.coverFile)
        assertEquals(listOf(created), repository.listDocuments())
    }

    @Test
    fun `saves recognized text and maps conflicts`() = runTest(dispatcher) {
        val core = FakeCore()
        val repository = CoreDocumentRepository(dispatcher) { core }
        val line = RecognizedLine(block = 1, text = "Total", bounds = TextRect(0.1f, 0.2f, 0.3f, 0.25f))

        val saved = repository.savePageText("p1", File("/data/p1.jpg"), listOf(line))

        assertEquals(PageText("p1", "Total", listOf(line)), saved)
        assertEquals(1u, core.savedLines!!.single().block)
        core.textConflict = true
        try {
            repository.savePageText("p1", File("/data/p1.jpg"), listOf(line))
            fail("expected a conflict")
        } catch (e: DocumentException.Conflict) {
            // expected
        }
    }

    @Test
    fun `new page images schedule text recognition`() = runTest(dispatcher) {
        var scheduled = 0
        val repository = CoreDocumentRepository(dispatcher, onPageImagesChanged = { scheduled++ }) { FakeCore() }

        repository.addPage("doc-1", File("/tmp/scan.jpg"))
        repository.listPages("doc-1")

        assertEquals(1, scheduled)
    }

    @Test
    fun `exports word files`() = runTest(dispatcher) {
        val core = FakeCore()
        val repository = CoreDocumentRepository(dispatcher) { core }

        val export = repository.exportDocx("doc-1", File("/tmp/out.docx"), includeImages = true)

        assertEquals(DocxExport(File("/tmp/out.docx"), 3, 2048, 1), export)
        assertEquals(DocxOptions(includeImages = true), core.docxOptions)
    }

    @Test
    fun `maps orphan cleanup results`() = runTest(dispatcher) {
        val repository = CoreDocumentRepository(dispatcher) { FakeCore() }

        assertEquals(CleanupResult(3, 4_096), repository.removeOrphanFiles())
    }

    @Test
    fun `maps pages including position and file`() = runTest(dispatcher) {
        val repository = CoreDocumentRepository(dispatcher) { FakeCore() }

        val page = repository.addPage("doc-1", File("/tmp/scan.jpg"))

        assertEquals(3, page.position)
        assertEquals(File("/data/nolva/doc-1/original.jpg"), page.originalFile)
    }

    @Test
    fun `translates core errors into DocumentException`() = runTest(dispatcher) {
        val coreError = NolvaException.InvalidArgument("title is empty")
        val core = FakeCore().apply { failure = coreError }
        val repository = CoreDocumentRepository(dispatcher) { core }

        try {
            repository.createDocument(" ")
            fail("expected DocumentException.InvalidInput")
        } catch (e: DocumentException.InvalidInput) {
            assertEquals("title is empty", e.message)
            // Search the whole chain: in debug mode kotlinx.coroutines may
            // copy exceptions crossing withContext (stack-trace recovery),
            // wrapping the original as the cause.
            val causes = generateSequence<Throwable>(e) { it.cause }
            assertTrue("core error must be kept as a cause", causes.any { it === coreError })
        }
    }

    @Test
    fun `maps detection and crops in both directions`() = runTest(dispatcher) {
        val core = FakeCore()
        val repository = CoreDocumentRepository(dispatcher) { core }

        val detection = repository.detectCorners("page-0")!!
        assertEquals(0.9f, detection.confidence)
        assertEquals(NormalizedPoint(0.1f, 0.2f), detection.corners.topLeft)

        val page = repository.cropPage("page-0", detection.corners)
        assertEquals(coreCorners, core.croppedWith)
        assertEquals(File("/data/nolva/doc-1/processed.jpg"), page.processedFile)
        assertEquals(page.processedFile, page.displayFile)
        assertEquals(detection.corners, page.corners)
    }

    @Test
    fun `maps pdf export options and results`() = runTest(dispatcher) {
        val core = FakeCore()
        val repository = CoreDocumentRepository(dispatcher) { core }

        val export = repository.exportPdf("doc-1", File("/cache/exports/a.pdf"), PaperSize.Legal)

        assertEquals(CorePaperSize.LEGAL, core.exportedWith?.paperSize)
        assertEquals(PdfExport(File("/cache/exports/a.pdf"), pageCount = 2, sizeBytes = 1234), export)
    }

    @Test
    fun `forwards edits to the core`() = runTest(dispatcher) {
        val core = FakeCore()
        val repository = CoreDocumentRepository(dispatcher) { core }

        val renamed = repository.renameDocument("doc-1", "Signed")
        assertEquals("Signed", renamed.title)
        assertEquals(Instant.ofEpochMilli(9_000), renamed.updatedAt)
        assertEquals(File("/data/nolva/doc-1/cover.jpg"), renamed.coverFile)

        repository.deleteDocument("doc-1")
        repository.deletePage("page-3")
        val order = repository.movePage("page-3", -1)

        assertEquals(listOf("page-3"), order.map { it.id })
        assertEquals(
            listOf("rename doc-1 Signed", "deleteDocument doc-1", "deletePage page-3", "move page-3 0"),
            core.calls,
        )
    }

    @Test
    fun `edit errors are translated too`() = runTest(dispatcher) {
        val core = FakeCore().apply { failure = NolvaException.NotFound("page") }
        val repository = CoreDocumentRepository(dispatcher) { core }

        try {
            repository.deletePage("gone")
            fail("expected DocumentException.NotFound")
        } catch (e: DocumentException.NotFound) {
            assertEquals("page", e.message)
        }
    }

    @Test
    fun `maps filters in both directions`() = runTest(dispatcher) {
        val core = FakeCore()
        val repository = CoreDocumentRepository(dispatcher) { core }

        assertEquals(PageFilter.Original, repository.getPage("page-0").filter)
        for (filter in PageFilter.entries) {
            val page = repository.applyFilter("page-0", filter)
            assertEquals(filter, page.filter)
            assertEquals(File("/data/nolva/doc-1/filtered.jpg"), page.displayFile)
        }
        assertEquals(CorePageFilter.ENHANCED, core.filteredWith)
    }

    @Test
    fun `creates the core lazily on first use`() = runTest(dispatcher) {
        var created = false
        val repository = CoreDocumentRepository(dispatcher) { created = true; FakeCore() }

        assertTrue("core must not be opened eagerly", !created)
        repository.listDocuments()
        assertTrue(created)
    }
}
