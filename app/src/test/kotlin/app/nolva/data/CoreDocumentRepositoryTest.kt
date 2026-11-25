package app.nolva.data

import app.nolva.core.CleanupInfo
import app.nolva.core.DocumentInfo
import app.nolva.core.DocxExportInfo
import app.nolva.core.DocxOptions
import app.nolva.core.FolderInfo
import app.nolva.core.NolvaCoreInterface
import app.nolva.core.NolvaException
import app.nolva.core.PageCorners
import app.nolva.core.PageFilter as CorePageFilter
import app.nolva.core.PageAnnotation as CoreAnnotation
import app.nolva.core.PageInfo
import app.nolva.core.SignatureInfo
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
                trashedAtMs = null,
                folderId = null,
                sizeBytes = 0u,
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

        val folders = mutableListOf<FolderInfo>()
        var moved: Pair<List<String>, String?>? = null

        override fun createDocumentInFolder(title: String, folderId: String?): DocumentInfo =
            createDocument(title).copy(folderId = folderId).also { documents[documents.lastIndex] = it }

        override fun createFolder(name: String): FolderInfo =
            FolderInfo("folder-${folders.size}", name, createdAtMs = 1, updatedAtMs = 2, documentCount = 0u)
                .also { folders += it }

        override fun listFolders(): List<FolderInfo> = folders

        override fun renameFolder(folderId: String, name: String): FolderInfo =
            folders.first { it.id == folderId }.copy(name = name)

        override fun deleteFolder(folderId: String) {
            folders.removeAll { it.id == folderId }
        }

        override fun moveDocuments(documentIds: List<String>, folderId: String?): UInt {
            moved = documentIds to folderId
            return documentIds.size.toUInt()
        }

        override fun moveDocumentToTrash(documentId: String) {
            calls += "trash $documentId"
        }

        override fun restoreDocument(documentId: String): DocumentInfo = documents.first()

        override fun listTrashedDocuments(): List<DocumentInfo> =
            documents.map { it.copy(trashedAtMs = 7_000) }

        override fun emptyTrash(): UInt = 2u

        override fun purgeExpiredTrash(): UInt = 1u

        override fun duplicateDocument(documentId: String, title: String): DocumentInfo =
            createDocument(title)

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
                trashedAtMs = null,
                folderId = null,
                sizeBytes = 0u,
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

        var annotations: List<CoreAnnotation> = emptyList()

        override fun getPageAnnotations(pageId: String): List<CoreAnnotation> = annotations

        override fun setPageAnnotations(pageId: String, annotations: List<CoreAnnotation>) {
            this.annotations = annotations
        }

        val signatures = mutableListOf<SignatureInfo>()

        override fun saveSignature(strokes: List<List<CorePoint>>, aspectRatio: Float): SignatureInfo =
            SignatureInfo("sig-${signatures.size}", strokes, aspectRatio, createdAtMs = 1).also { signatures += it }

        override fun listSignatures(): List<SignatureInfo> = signatures.toList()

        override fun deleteSignature(signatureId: String) {
            signatures.removeAll { it.id == signatureId }
        }

        var rotatedClockwise: Boolean? = null

        override fun rotatePage(pageId: String, clockwise: Boolean): PageInfo {
            rotatedClockwise = clockwise
            return pageInfo("doc-1").copy(processedPath = "/data/nolva/doc-1/rotated.jpg", rotationDegrees = 90u)
        }

        var renderedWith: Triple<PageCorners?, CorePageFilter, UInt>? = null

        override fun setPageRendering(
            pageId: String,
            corners: PageCorners?,
            filter: CorePageFilter,
            rotationDegrees: UInt,
        ): PageInfo {
            renderedWith = Triple(corners, filter, rotationDegrees)
            return pageInfo("doc-1").copy(corners = corners, filter = filter, rotationDegrees = rotationDegrees)
        }

        private fun pageInfo(documentId: String) = PageInfo(
            id = "page-0",
            documentId = documentId,
            position = 3u,
            originalPath = "/data/nolva/$documentId/original.jpg",
            processedPath = null,
            corners = null,
            filter = CorePageFilter.ORIGINAL,
            rotationDegrees = 0u,
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
    fun `maps folders and document placement`() = runTest(dispatcher) {
        val core = FakeCore()
        val repository = CoreDocumentRepository(dispatcher) { core }

        val folder = repository.createFolder("Cuentas")
        val inside = repository.createDocumentIn("Luz", folder.id)

        assertEquals(Folder("folder-0", "Cuentas", Instant.ofEpochMilli(1), Instant.ofEpochMilli(2), 0), folder)
        assertEquals(folder.id, inside.folderId)
        assertEquals("Hogar", repository.renameFolder(folder.id, "Hogar").name)
        assertEquals(2, repository.moveDocuments(listOf("a", "b"), null))
        assertEquals(listOf("a", "b") to null, core.moved)
        repository.deleteFolder(folder.id)
        assertEquals(emptyList<Folder>(), repository.listFolders())
    }

    @Test
    fun `maps the trash`() = runTest(dispatcher) {
        val core = FakeCore()
        val repository = CoreDocumentRepository(dispatcher) { core }
        repository.createDocument("Boleta")

        repository.moveToTrash("doc-0")

        assertTrue("trash doc-0" in core.calls)
        assertEquals(Instant.ofEpochMilli(7_000), repository.listTrash().single().trashedAt)
        assertEquals(null, repository.restore("doc-0").trashedAt)
        assertEquals(2, repository.emptyTrash())
        assertEquals(1, repository.purgeExpired())
        assertEquals("Boleta (copia)", repository.duplicateDocument("doc-0", "Boleta (copia)").title)
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

        val export = repository.exportPdf("doc-1", File("/cache/exports/a.pdf"), PdfSettings(PaperSize.Legal))

        assertEquals(CorePaperSize.LEGAL, core.exportedWith?.paperSize)
        assertEquals(false, core.exportedWith?.pageNumbers)
        repository.exportPdf("doc-1", File("/cache/exports/a.pdf"), PdfSettings(pageNumbers = true))
        assertEquals(true, core.exportedWith?.pageNumbers)
        assertEquals(null, core.exportedWith?.password)
        repository.exportPdf("doc-1", File("/cache/exports/a.pdf"), PdfSettings(password = "ñandú"))
        assertEquals("ñandú", core.exportedWith?.password)
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
    fun `rotates and restores whole renderings`() = runTest(dispatcher) {
        val core = FakeCore()
        val repository = CoreDocumentRepository(dispatcher) { core }

        val turned = repository.rotatePage("page-0", clockwise = false)
        assertEquals(false, core.rotatedClockwise)
        assertEquals(90, turned.rotationDegrees)
        assertEquals(File("/data/nolva/doc-1/rotated.jpg"), turned.displayFile)

        val corners = repository.detectCorners("page-0")!!.corners
        val rendering = PageRendering(corners, PageFilter.Grayscale, 270)
        val restored = repository.setRendering("page-0", rendering)
        assertEquals(Triple(coreCorners, CorePageFilter.GRAYSCALE, 270u), core.renderedWith)
        assertEquals(rendering, restored.rendering)
    }

    @Test
    fun `maps annotations and signatures both ways`() = runTest(dispatcher) {
        val core = FakeCore()
        val repository = CoreDocumentRepository(dispatcher) { core }
        val ink = PageAnnotation.Ink(
            listOf(listOf(NormalizedPoint(0.1f, 0.2f), NormalizedPoint(0.3f, 0.4f))),
            color = 0x1A4D8F,
            width = 0.004f,
        )
        val annotations = listOf(
            ink,
            PageAnnotation.Highlight(0.1f, 0.2f, 0.9f, 0.25f, color = 0xFFEB3B),
            PageAnnotation.Text("Visto bueno", 0.1f, 0.8f, 0.03f, color = 0xC62828),
        )

        repository.setAnnotations("page-0", annotations)

        assertEquals(
            CoreAnnotation.Ink(listOf(listOf(CorePoint(0.1f, 0.2f), CorePoint(0.3f, 0.4f))), 0x1A4D8Fu, 0.004f),
            core.annotations[0],
        )
        assertEquals(annotations, repository.getAnnotations("page-0"))

        val signature = repository.saveSignature(ink.strokes, aspectRatio = 3f)
        assertEquals(Signature("sig-0", ink.strokes, 3f), signature)
        assertEquals(listOf(signature), repository.listSignatures())
        repository.deleteSignature(signature.id)
        assertTrue(repository.listSignatures().isEmpty())
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
