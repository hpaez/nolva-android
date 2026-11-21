package app.nolva.ui.viewer

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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PageViewerViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class FakeRepository(private val pages: List<Page>, private val failure: Exception? = null) :
        DocumentRepository {
        override suspend fun listPages(documentId: String): List<Page> {
            failure?.let { throw it }
            return pages
        }

        override suspend fun listDocuments(): List<Document> = emptyList()
        override suspend fun createDocument(title: String): Document = error("unused")
        override suspend fun addPage(documentId: String, image: File): Page = error("unused")
    }

    @Test
    fun `loads the document pages`() = runTest(dispatcher) {
        val pages = List(3) { Page("p$it", "doc", it, File("/p$it.jpg"), Instant.EPOCH) }
        val viewModel = PageViewerViewModel("doc", FakeRepository(pages))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(pages, state.pages)
    }

    @Test
    fun `reports load failures`() = runTest(dispatcher) {
        val viewModel = PageViewerViewModel("doc", FakeRepository(emptyList(), DocumentException.Storage("io")))
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.loadFailed)
    }
}
