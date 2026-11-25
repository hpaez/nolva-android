package app.nolva.ui.document

import app.nolva.data.Document
import app.nolva.data.DocumentEditor
import app.nolva.data.DocumentException
import app.nolva.data.DocumentRepository
import app.nolva.data.Page
import app.nolva.data.PageImporter
import app.nolva.data.PaperSize
import app.nolva.data.DOCX_MIME_TYPE
import app.nolva.data.DocxExport
import app.nolva.data.DocxExporter
import app.nolva.data.PageText
import app.nolva.data.PageTextStore
import app.nolva.data.PdfExport
import app.nolva.data.ExportPreferences
import app.nolva.data.PdfExporter
import app.nolva.data.PdfSettings
import app.nolva.data.RecognizedLine
import app.nolva.data.TextMatch
import app.nolva.data.TextRecognitionRunner
import app.nolva.data.TextRecognizer
import app.nolva.data.TextRect
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class DocumentDetailViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class FakeRepository(private val pages: List<Page> = emptyList()) : DocumentRepository {
        override suspend fun listDocuments(): List<Document> = emptyList()
        override suspend fun createDocument(title: String): Document = error("unused")
        override suspend fun addPage(documentId: String, image: File): Page = error("unused")
        override suspend fun listPages(documentId: String): List<Page> = pages
    }

    /** Keeps an ordered list of pages, like the core. */
    private class FakeEditor(ids: List<String> = emptyList()) : DocumentEditor {
        val order = ids.toMutableList()
        var failure: Exception? = null

        override suspend fun renameDocument(documentId: String, title: String): Document = error("unused")
        override suspend fun deleteDocument(documentId: String) = error("unused")
        override suspend fun duplicateDocument(documentId: String, title: String): Document = error("unused")

        override suspend fun deletePage(pageId: String) {
            failure?.let { throw it }
            order.remove(pageId)
        }

        override suspend fun movePage(pageId: String, toPosition: Int): List<Page> {
            failure?.let { throw it }
            order.remove(pageId)
            order.add(toPosition.coerceIn(0, order.size), pageId)
            return order.mapIndexed { index, id -> page(id, index) }
        }
    }

    /** Imports succeed with increasing positions, except URIs listed in [failures]. */
    private class FakeImporter(private val failures: Map<String, Exception> = emptyMap()) : PageImporter {
        val imported = mutableListOf<String>()

        override suspend fun import(documentId: String, uri: String): Page {
            failures[uri]?.let { throw it }
            imported += uri
            return page(id = uri, position = imported.size - 1)
        }
    }

    /** Writes a tiny file where asked, like the core does. */
    private class FakeExporter(private val failure: Exception? = null) : PdfExporter {
        val exported = mutableListOf<Pair<File, PdfSettings>>()

        override suspend fun exportPdf(documentId: String, output: File, settings: PdfSettings): PdfExport {
            failure?.let { throw it }
            output.writeText("%PDF")
            exported += output to settings
            return PdfExport(output, pageCount = 1, sizeBytes = 4)
        }
    }

    @get:Rule
    val tempFolder = TemporaryFolder()

    /** Pages of "doc" stay pending until recognized; recognition yields one line each. */
    private class FakeTextStore(pending: List<Page> = emptyList()) : PageTextStore {
        val pending = pending.toMutableList()

        override suspend fun pagesNeedingText(limit: Int): List<Page> = pending.take(limit)
        override suspend fun savePageText(pageId: String, image: File, lines: List<RecognizedLine>): PageText {
            pending.removeAll { it.id == pageId }
            return PageText(pageId, "", lines)
        }
        override suspend fun getPageText(pageId: String): PageText? = null
        override suspend fun searchText(query: String): List<TextMatch> = emptyList()
    }

    /** Reports as pending text whatever the store still has pending for "doc". */
    private class FakeDocxExporter(private val store: FakeTextStore) : DocxExporter {
        val exported = mutableListOf<Pair<File, Boolean>>()

        override suspend fun exportDocx(documentId: String, output: File, includeImages: Boolean): DocxExport {
            exported += output to includeImages
            val pending = store.pending.count { it.documentId == documentId }
            return DocxExport(output, pageCount = 2, sizeBytes = 9, pagesPendingText = pending)
        }
    }

    private fun viewModel(
        repository: DocumentRepository,
        importer: PageImporter,
        exporter: PdfExporter = FakeExporter(),
        editor: DocumentEditor = FakeEditor(),
        textStore: FakeTextStore = FakeTextStore(),
        recognizer: TextRecognizer = TextRecognizer { listOf(RecognizedLine(0, "x", TextRect(0f, 0f, 1f, 0.1f))) },
        docxExporter: DocxExporter = FakeDocxExporter(textStore),
        exportPreferences: FakeExportPreferences = FakeExportPreferences(),
    ) = DocumentDetailViewModel(
        "doc",
        repository,
        editor,
        importer,
        exporter,
        docxExporter,
        TextRecognitionRunner(textStore, recognizer),
        exportPreferences,
        tempFolder.root.resolve("exports"),
        dispatcher,
    )

    @Test
    fun `word export reads pending pages of this document first`() = runTest(dispatcher) {
        val other = page("z", 0).copy(documentId = "other")
        val store = FakeTextStore(listOf(page("a", 0), other))
        val docx = FakeDocxExporter(store)
        val viewModel = viewModel(FakeRepository(listOf(page("a", 0))), FakeImporter(), textStore = store, docxExporter = docx)

        viewModel.exportDocx("Carta 1/2", includeImages = true)
        advanceUntilIdle()

        assertEquals(listOf("z"), store.pending.map { it.id })
        val (file, images) = docx.exported.single()
        assertEquals("Carta 1_2.docx" to true, file.name to images)
        val share = viewModel.uiState.value.pendingShare!!
        assertEquals(DOCX_MIME_TYPE, share.mimeType)
        assertEquals(null, viewModel.uiState.value.message)
    }

    @Test
    fun `word export still runs when recognition fails, and says what is missing`() = runTest(dispatcher) {
        val store = FakeTextStore(listOf(page("a", 0), page("b", 1)))
        val viewModel = viewModel(
            FakeRepository(),
            FakeImporter(),
            textStore = store,
            recognizer = { throw IllegalStateException("ML Kit unavailable") },
        )

        viewModel.exportDocx("Carta", includeImages = false)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(DocumentDetailMessage.DocxMissingText, state.message)
        assertEquals(2, state.pagesMissingText)
        assertFalse(state.isExporting)
    }

    @Test
    fun `deleting a page renumbers the rest`() = runTest(dispatcher) {
        val pages = listOf(page("a", 0), page("b", 1), page("c", 2))
        val viewModel = viewModel(FakeRepository(pages), FakeImporter(), editor = FakeEditor(listOf("a", "b", "c")))
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.deletePage("a")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("b", "c"), state.pages.map { it.id })
        assertEquals(listOf(0, 1), state.pages.map { it.position })
    }

    @Test
    fun `moves apply in the order they were requested`() = runTest(dispatcher) {
        val pages = listOf(page("a", 0), page("b", 1), page("c", 2))
        val viewModel = viewModel(FakeRepository(pages), FakeImporter(), editor = FakeEditor(listOf("a", "b", "c")))
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.movePage("c", 1)
        viewModel.movePage("c", 0)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("c", "a", "b"), state.pages.map { it.id })
        assertEquals(listOf(0, 1, 2), state.pages.map { it.position })
    }

    @Test
    fun `failed edits keep the pages and report`() = runTest(dispatcher) {
        val pages = listOf(page("a", 0), page("b", 1))
        val editor = FakeEditor(listOf("a", "b")).apply { failure = DocumentException.Storage("disk") }
        val viewModel = viewModel(FakeRepository(pages), FakeImporter(), editor = editor)
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.deletePage("a")
        advanceUntilIdle()
        assertEquals(DocumentDetailMessage.DeletePageFailed, viewModel.uiState.value.message)
        viewModel.messageShown()

        viewModel.movePage("b", 0)
        advanceUntilIdle()
        val state = viewModel.uiState.value
        assertEquals(DocumentDetailMessage.MovePageFailed, state.message)
        assertEquals(listOf("a", "b"), state.pages.map { it.id })
    }

    @Test
    fun `exports a pdf named after the document and queues it for sharing`() = runTest(dispatcher) {
        val exporter = FakeExporter()
        val viewModel = viewModel(FakeRepository(listOf(page("a", 0))), FakeImporter(), exporter)
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.exportPdf("Factura 3/2026", PaperSize.Legal)
        assertEquals(true, viewModel.uiState.value.isExporting)
        advanceUntilIdle()

        val share = viewModel.uiState.value.pendingShare!!
        assertEquals("Factura 3_2026.pdf", share.file.name)
        assertEquals(PdfSettings(PaperSize.Legal, pageNumbers = false), exporter.exported.single().second)
        assertFalse(viewModel.uiState.value.isExporting)

        viewModel.shareHandled()
        assertEquals(null, viewModel.uiState.value.pendingShare)
    }

    private class FakeExportPreferences(var stored: Boolean = false) : ExportPreferences {
        override suspend fun pageNumbers(): Boolean = stored
        override suspend fun setPageNumbers(enabled: Boolean) {
            stored = enabled
        }
    }

    @Test
    fun `page numbers are off by default, remembered and used in pdfs`() = runTest(dispatcher) {
        val exporter = FakeExporter()
        val prefs = FakeExportPreferences()
        val viewModel = viewModel(
            FakeRepository(listOf(page("a", 0))),
            FakeImporter(),
            exporter,
            exportPreferences = prefs,
        )
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.pageNumbers)

        viewModel.setPageNumbers(true)
        advanceUntilIdle()
        assertTrue(prefs.stored)
        viewModel.exportPdf("Recibo", PaperSize.FitImage)
        advanceUntilIdle()
        assertEquals(PdfSettings(PaperSize.FitImage, pageNumbers = true), exporter.exported.single().second)

        // Another document starts with the remembered choice.
        val next = viewModel(FakeRepository(), FakeImporter(), exportPreferences = prefs)
        advanceUntilIdle()
        assertTrue(next.uiState.value.pageNumbers)
    }

    @Test
    fun `reports export failures`() = runTest(dispatcher) {
        val viewModel = viewModel(FakeRepository(), FakeImporter(), FakeExporter(DocumentException.InvalidInput("empty")))
        viewModel.exportPdf("Vacío", PaperSize.A4)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(DocumentDetailMessage.ExportFailed, state.message)
        assertEquals(null, state.pendingShare)
    }

    @Test
    fun `loads existing pages`() = runTest(dispatcher) {
        val viewModel = viewModel(FakeRepository(listOf(page("a", 0))), FakeImporter())
        viewModel.refresh()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(listOf("a"), state.pages.map { it.id })
    }

    @Test
    fun `imports images in the order they were picked`() = runTest(dispatcher) {
        val importer = FakeImporter()
        val viewModel = viewModel(FakeRepository(), importer)
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.importImages(listOf("content://1", "content://2", "content://3"))
        assertEquals(true, viewModel.uiState.value.isImporting)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isImporting)
        assertEquals(listOf("content://1", "content://2", "content://3"), state.pages.map { it.id })
    }

    @Test
    fun `a failed image does not stop the rest`() = runTest(dispatcher) {
        val importer = FakeImporter(failures = mapOf("content://bad" to DocumentException.Unsupported("gif")))
        val viewModel = viewModel(FakeRepository(), importer)
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.importImages(listOf("content://bad", "content://good"))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("content://good"), state.pages.map { it.id })
        assertEquals(DocumentDetailMessage.UnsupportedImage, state.message)
        assertFalse(state.isImporting)
    }

    private companion object {
        fun page(id: String, position: Int) = Page(
            id = id,
            documentId = "doc",
            position = position,
            originalFile = File("/pages/$id.jpg"),
            createdAt = Instant.EPOCH,
        )
    }
}
