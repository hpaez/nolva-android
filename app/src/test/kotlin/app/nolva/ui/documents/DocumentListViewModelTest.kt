package app.nolva.ui.documents

import app.nolva.data.Document
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

    @Test
    fun `loads documents on start`() = runTest(dispatcher) {
        val repository = FakeRepository().apply { documents += document("a", "Lease") }

        val viewModel = DocumentListViewModel(repository)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(listOf("Lease"), state.documents.map { it.title })
    }

    @Test
    fun `reports load failures`() = runTest(dispatcher) {
        val repository = FakeRepository().apply { listFailure = DocumentException.Storage("disk") }

        val viewModel = DocumentListViewModel(repository)
        advanceUntilIdle()

        assertEquals(DocumentListMessage.LoadFailed, viewModel.uiState.value.message)
    }

    @Test
    fun `new documents appear first`() = runTest(dispatcher) {
        val repository = FakeRepository().apply { documents += document("a", "Old") }
        val viewModel = DocumentListViewModel(repository)
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
        val viewModel = DocumentListViewModel(repository)
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
