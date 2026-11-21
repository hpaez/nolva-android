package app.nolva.ui.document

import app.nolva.data.Document
import app.nolva.data.DocumentException
import app.nolva.data.DocumentRepository
import app.nolva.data.Page
import app.nolva.data.PageImporter
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

    /** Imports succeed with increasing positions, except URIs listed in [failures]. */
    private class FakeImporter(private val failures: Map<String, Exception> = emptyMap()) : PageImporter {
        val imported = mutableListOf<String>()

        override suspend fun import(documentId: String, uri: String): Page {
            failures[uri]?.let { throw it }
            imported += uri
            return page(id = uri, position = imported.size - 1)
        }
    }

    @Test
    fun `loads existing pages`() = runTest(dispatcher) {
        val viewModel = DocumentDetailViewModel("doc", FakeRepository(listOf(page("a", 0))), FakeImporter())
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(listOf("a"), state.pages.map { it.id })
    }

    @Test
    fun `imports images in the order they were picked`() = runTest(dispatcher) {
        val importer = FakeImporter()
        val viewModel = DocumentDetailViewModel("doc", FakeRepository(), importer)
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
        val viewModel = DocumentDetailViewModel("doc", FakeRepository(), importer)
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
