package app.nolva.ui.documents

import app.nolva.data.Document
import app.nolva.data.DocumentEditor
import app.nolva.data.DocumentException
import app.nolva.data.DocumentRepository
import app.nolva.data.Page
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

        override suspend fun deletePage(pageId: String) = error("unused")

        override suspend fun movePage(pageId: String, toPosition: Int): List<Page> = error("unused")
    }

    private fun viewModel(repository: FakeRepository, editor: DocumentEditor = FakeEditor(repository)) =
        DocumentListViewModel(repository, editor)

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
    fun `deleted documents leave the list`() = runTest(dispatcher) {
        val repository = FakeRepository().apply { documents += listOf(document("a", "Keep"), document("b", "Drop")) }
        val viewModel = viewModel(repository)
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.deleteDocument("b")
        advanceUntilIdle()

        assertEquals(listOf("Keep"), viewModel.uiState.value.documents.map { it.title })
    }

    @Test
    fun `deleting a document that is already gone still removes it`() = runTest(dispatcher) {
        val repository = FakeRepository().apply { documents += document("a", "Ghost") }
        val editor = FakeEditor(repository).apply { failure = DocumentException.NotFound("a") }
        val viewModel = viewModel(repository, editor)
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
        val editor = FakeEditor(repository).apply { failure = DocumentException.Storage("disk") }
        val viewModel = viewModel(repository, editor)
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
