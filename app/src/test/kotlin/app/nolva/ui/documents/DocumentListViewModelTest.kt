package app.nolva.ui.documents

import app.nolva.data.Document
import app.nolva.data.DocumentEditor
import app.nolva.data.DocumentException
import app.nolva.data.DocumentRepository
import app.nolva.data.DocumentTrash
import app.nolva.data.Page
import app.nolva.data.PageText
import app.nolva.data.PageTextStore
import app.nolva.data.PdfImportProgress
import app.nolva.data.PdfImporter
import app.nolva.data.RecognizedLine
import app.nolva.data.TextMatch
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DocumentListViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class FakeRepository : DocumentRepository {
        val documents = mutableListOf<Document>()
        var listFailure: Exception? = null
        var createFailure: Exception? = null

        override suspend fun listDocuments(): List<Document> {
            listFailure?.let { throw it }
            return documents.toList()
        }

        override suspend fun createDocument(title: String): Document {
            createFailure?.let { throw it }
            val doc = document(id = "doc-${documents.size}", title = title)
            documents.add(0, doc)
            return doc
        }

        override suspend fun addPage(documentId: String, image: File): Page = error("unused")

        override suspend fun listPages(documentId: String): List<Page> = emptyList()
    }

    private class FakeEditor(private val repository: FakeRepository) : DocumentEditor {
        var failure: Exception? = null

        override suspend fun renameDocument(documentId: String, title: String): Document {
            failure?.let { throw it }
            val index = repository.documents.indexOfFirst { it.id == documentId }
            return repository.documents[index].copy(title = title).also { repository.documents[index] = it }
        }

        override suspend fun deleteDocument(documentId: String) {
            failure?.let { throw it }
            repository.documents.removeAll { it.id == documentId }
        }

        override suspend fun duplicateDocument(documentId: String, title: String): Document {
            failure?.let { throw it }
            return document("copy-of-$documentId", title).also { repository.documents.add(0, it) }
        }

        override suspend fun deletePage(pageId: String) = error("unused")

        override suspend fun movePage(pageId: String, toPosition: Int): List<Page> = error("unused")
    }

    /** Moves documents between the repository and its own trashed list. */
    private class FakeTrash(private val repository: FakeRepository) : DocumentTrash {
        var failure: Exception? = null
        val trashed = mutableListOf<Document>()

        override suspend fun moveToTrash(documentId: String) {
            failure?.let { throw it }
            val document = repository.documents.first { it.id == documentId }
            repository.documents.remove(document)
            trashed += document
        }

        override suspend fun restore(documentId: String): Document {
            failure?.let { throw it }
            val document = trashed.first { it.id == documentId }
            trashed.remove(document)
            repository.documents += document
            return document
        }

        override suspend fun listTrash(): List<Document> = trashed.toList()
        override suspend fun emptyTrash(): Int = error("unused")
        override suspend fun purgeExpired(): Int = error("unused")
    }

    /** Returns [matches] for any query and records the queries it got. */
    private class FakeTextStore(var matches: List<TextMatch> = emptyList()) : PageTextStore {
        val queries = mutableListOf<String>()

        override suspend fun searchText(query: String): List<TextMatch> {
            queries += query
            return matches
        }

        override suspend fun pagesNeedingText(limit: Int): List<Page> = emptyList()
        override suspend fun savePageText(pageId: String, image: File, lines: List<RecognizedLine>): PageText =
            error("unused")
        override suspend fun getPageText(pageId: String): PageText? = null
    }

    private fun viewModel(
        repository: FakeRepository,
        editor: DocumentEditor = FakeEditor(repository),
        textStore: PageTextStore = FakeTextStore(),
        pdfImporter: PdfImporter = PdfImporter { _, _ -> error("unused") },
        trash: DocumentTrash = FakeTrash(repository),
    ) = DocumentListViewModel(repository, editor, textStore, pdfImporter, trash)

    @Test
    fun `imported pdfs show progress, join the list and open`() = runTest(dispatcher) {
        val repository = FakeRepository().apply { documents += document("a", "Old") }
        val seen = mutableListOf<PdfImportProgress?>()
        lateinit var viewModel: DocumentListViewModel
        val importer = PdfImporter { _, onProgress ->
            onProgress(PdfImportProgress(0, 2))
            seen += viewModel.uiState.value.pdfImport
            onProgress(PdfImportProgress(2, 2))
            document("pdf", "Contrato").copy(pageCount = 2)
        }
        viewModel = viewModel(repository, pdfImporter = importer)
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.importPdf("content://downloads/1")
        assertEquals(PdfImportProgress(0, 0), viewModel.uiState.value.pdfImport)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf(PdfImportProgress(0, 2)), seen)
        assertEquals(null, state.pdfImport)
        assertEquals(listOf("pdf", "a"), state.documents.map { it.id })
        assertEquals("pdf", state.openDocument?.id)
        viewModel.documentOpened()
        assertEquals(null, viewModel.uiState.value.openDocument)
    }

    @Test
    fun `pdf import failures explain what went wrong`() = runTest(dispatcher) {
        val cases = mapOf(
            DocumentException.Unsupported("password") to DocumentListMessage.PdfUnreadable,
            DocumentException.InvalidInput("0 pages") to DocumentListMessage.PdfTooLarge,
            DocumentException.Storage("disk full") to DocumentListMessage.PdfImportFailed,
        )
        for ((error, expected) in cases) {
            val viewModel = viewModel(FakeRepository(), pdfImporter = { _, _ -> throw error })
            viewModel.importPdf("content://x")
            advanceUntilIdle()

            assertEquals(expected, viewModel.uiState.value.message)
            assertEquals(null, viewModel.uiState.value.pdfImport)
            assertEquals(null, viewModel.uiState.value.openDocument)
        }
    }

    private fun match(documentId: String, page: Int) = TextMatch(documentId, "$documentId-p$page", page, "…total $page…")

    @Test
    fun `search finds documents by title or by page text`() = runTest(dispatcher) {
        val repository = FakeRepository().apply {
            documents += listOf(document("a", "Factura luz"), document("b", "Contrato"), document("c", "Otro"))
        }
        val text = FakeTextStore(listOf(match("b", 2), match("b", 0), match("a", 1)))
        val viewModel = viewModel(repository, textStore = text)
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.search("fact")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("a", "b"), state.visibleDocuments.map { it.id })
        assertEquals("best match per document", 2, state.contentMatches.getValue("b").pagePosition)
        assertEquals(listOf("fact"), text.queries)
    }

    @Test
    fun `typing waits for a pause and only the last query runs`() = runTest(dispatcher) {
        val repository = FakeRepository().apply { documents += document("a", "A") }
        val text = FakeTextStore(listOf(match("a", 0)))
        val viewModel = viewModel(repository, textStore = text)
        viewModel.refresh()
        advanceUntilIdle()
        text.queries.clear()

        viewModel.search("b")
        advanceTimeBy(SEARCH_DEBOUNCE_MS / 2)
        viewModel.search("bo")
        advanceTimeBy(SEARCH_DEBOUNCE_MS / 2)
        viewModel.search("bol")
        advanceUntilIdle()

        assertEquals(listOf("bol"), text.queries)
        viewModel.search("")
        assertEquals(emptyMap<String, TextMatch>(), viewModel.uiState.value.contentMatches)
        assertEquals(listOf("a"), viewModel.uiState.value.visibleDocuments.map { it.id })
    }

    @Test
    fun `renamed documents move to the top`() = runTest(dispatcher) {
        val repository = FakeRepository().apply { documents += listOf(document("a", "First"), document("b", "Second")) }
        val viewModel = viewModel(repository)
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.renameDocument("b", "Renamed")
        advanceUntilIdle()

        assertEquals(listOf("Renamed", "First"), viewModel.uiState.value.documents.map { it.title })
    }

    @Test
    fun `rename failures keep the list and report`() = runTest(dispatcher) {
        val repository = FakeRepository().apply { documents += document("a", "Lease") }
        val editor = FakeEditor(repository).apply { failure = DocumentException.Storage("disk") }
        val viewModel = viewModel(repository, editor)
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.renameDocument("a", "New")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("Lease"), state.documents.map { it.title })
        assertEquals(DocumentListMessage.RenameFailed, state.message)
    }

    @Test
    fun `deleted documents go to the trash and can come back`() = runTest(dispatcher) {
        val older = document("a", "Older").copy(updatedAt = Instant.ofEpochMilli(1))
        val newer = document("b", "Newer").copy(updatedAt = Instant.ofEpochMilli(2))
        val repository = FakeRepository().apply { documents += listOf(newer, older) }
        val trash = FakeTrash(repository)
        val viewModel = viewModel(repository, trash = trash)
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.deleteDocument("b")
        advanceUntilIdle()

        assertEquals(listOf("Older"), viewModel.uiState.value.documents.map { it.title })
        assertEquals(listOf("b"), trash.trashed.map { it.id })
        assertEquals("b", viewModel.uiState.value.trashedDocument?.id)

        viewModel.undoTrash()
        assertEquals(null, viewModel.uiState.value.trashedDocument)
        advanceUntilIdle()

        assertEquals(listOf("Newer", "Older"), viewModel.uiState.value.documents.map { it.title })
        assertEquals(emptyList<Document>(), trash.trashed)
    }

    @Test
    fun `the trash notice can be dismissed`() = runTest(dispatcher) {
        val repository = FakeRepository().apply { documents += document("a", "Lease") }
        val viewModel = viewModel(repository)
        viewModel.refresh()
        advanceUntilIdle()
        viewModel.deleteDocument("a")
        advanceUntilIdle()

        viewModel.trashNoticeShown()
        viewModel.undoTrash()
        advanceUntilIdle()

        assertEquals(emptyList<Document>(), viewModel.uiState.value.documents)
    }

    @Test
    fun `duplicates go to the top of the list`() = runTest(dispatcher) {
        val repository = FakeRepository().apply { documents += document("a", "Lease") }
        val viewModel = viewModel(repository)
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.duplicateDocument("a", "Lease (copia)")
        advanceUntilIdle()

        assertEquals(listOf("Lease (copia)", "Lease"), viewModel.uiState.value.documents.map { it.title })
    }

    @Test
    fun `failed duplicates report`() = runTest(dispatcher) {
        val repository = FakeRepository().apply { documents += document("a", "Lease") }
        val editor = FakeEditor(repository).apply { failure = DocumentException.Storage("disk") }
        val viewModel = viewModel(repository, editor)
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.duplicateDocument("a", "Copy")
        advanceUntilIdle()

        assertEquals(DocumentListMessage.DuplicateFailed, viewModel.uiState.value.message)
        assertEquals(1, viewModel.uiState.value.documents.size)
    }

    @Test
    fun `deleting a document that is already gone still removes it`() = runTest(dispatcher) {
        val repository = FakeRepository().apply { documents += document("a", "Ghost") }
        val trash = FakeTrash(repository).apply { failure = DocumentException.NotFound("a") }
        val viewModel = viewModel(repository, trash = trash)
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.deleteDocument("a")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(emptyList<Document>(), state.documents)
        assertEquals(null, state.message)
    }

    @Test
    fun `other delete failures keep the document and report`() = runTest(dispatcher) {
        val repository = FakeRepository().apply { documents += document("a", "Lease") }
        val trash = FakeTrash(repository).apply { failure = DocumentException.Storage("disk") }
        val viewModel = viewModel(repository, trash = trash)
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.deleteDocument("a")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("Lease"), state.documents.map { it.title })
        assertEquals(DocumentListMessage.DeleteFailed, state.message)
    }

    @Test
    fun `search ignores case and accents and keeps the full list`() = runTest(dispatcher) {
        val repository = FakeRepository().apply {
            documents += listOf(document("a", "Factura Única"), document("b", "Contrato"), document("c", "facturación"))
        }
        val viewModel = viewModel(repository)
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.search("  FACTURA ")
        val state = viewModel.uiState.value
        assertEquals(listOf("Factura Única", "facturación"), state.visibleDocuments.map { it.title })
        assertEquals(3, state.documents.size)

        viewModel.search("unica")
        assertEquals(listOf("Factura Única"), viewModel.uiState.value.visibleDocuments.map { it.title })

        viewModel.search("")
        assertEquals(3, viewModel.uiState.value.visibleDocuments.size)
    }

    @Test
    fun `loads documents on refresh`() = runTest(dispatcher) {
        val repository = FakeRepository().apply { documents += document("a", "Lease") }

        val viewModel = viewModel(repository)
        viewModel.refresh()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(listOf("Lease"), state.documents.map { it.title })
    }

    @Test
    fun `reports load failures`() = runTest(dispatcher) {
        val repository = FakeRepository().apply { listFailure = DocumentException.Storage("disk") }

        val viewModel = viewModel(repository)
        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(DocumentListMessage.LoadFailed, viewModel.uiState.value.message)
    }

    @Test
    fun `new documents appear first`() = runTest(dispatcher) {
        val repository = FakeRepository().apply { documents += document("a", "Old") }
        val viewModel = viewModel(repository)
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.createDocument("New")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isCreating)
        assertEquals(listOf("New", "Old"), state.documents.map { it.title })
    }

    @Test
    fun `invalid titles map to a specific message that can be dismissed`() = runTest(dispatcher) {
        val repository = FakeRepository().apply { createFailure = DocumentException.InvalidInput("empty") }
        val viewModel = viewModel(repository)
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.createDocument(" ")
        advanceUntilIdle()
        assertEquals(DocumentListMessage.InvalidTitle, viewModel.uiState.value.message)

        viewModel.messageShown()
        assertEquals(null, viewModel.uiState.value.message)
    }

    private companion object {
        fun document(id: String, title: String) = Document(
            id = id,
            title = title,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
            pageCount = 0,
        )
    }
}
