package app.nolva.ui.viewer

import app.nolva.data.Document
import app.nolva.data.DocumentException
import app.nolva.data.DocumentRepository
import app.nolva.data.CornerDetection
import app.nolva.data.Corners
import app.nolva.data.Page
import app.nolva.data.PageCropper
import app.nolva.data.PageFilter
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

    private class FakeCropper(private val failure: Exception? = null) : PageCropper {
        val applied = mutableListOf<Pair<String, PageFilter>>()

        override suspend fun getPage(pageId: String): Page = error("unused")
        override suspend fun detectCorners(pageId: String): CornerDetection? = error("unused")
        override suspend fun cropPage(pageId: String, corners: Corners): Page = error("unused")

        override suspend fun applyFilter(pageId: String, filter: PageFilter): Page {
            failure?.let { throw it }
            applied += pageId to filter
            return page(pageId).copy(filter = filter, processedFile = File("/$pageId-$filter.jpg"))
        }
    }

    private fun viewModel(pages: List<Page>, cropper: PageCropper = FakeCropper()) =
        PageViewerViewModel("doc", FakeRepository(pages), cropper)

    @Test
    fun `applying a filter replaces only that page`() = runTest(dispatcher) {
        val cropper = FakeCropper()
        val viewModel = viewModel(List(2) { page("p$it", it) }, cropper)
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.applyFilter("p1", PageFilter.BlackAndWhite)
        assertEquals("p1", viewModel.uiState.value.filteringPageId)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(null, state.filteringPageId)
        assertEquals(listOf(PageFilter.Original, PageFilter.BlackAndWhite), state.pages.map { it.filter })
        assertEquals(File("/p1-BlackAndWhite.jpg"), state.pages[1].displayFile)
    }

    @Test
    fun `ignores the current filter and requests while another runs`() = runTest(dispatcher) {
        val cropper = FakeCropper()
        val viewModel = viewModel(List(2) { page("p$it", it) }, cropper)
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.applyFilter("p0", PageFilter.Original)
        viewModel.applyFilter("p0", PageFilter.Grayscale)
        viewModel.applyFilter("p1", PageFilter.Enhanced)
        advanceUntilIdle()

        assertEquals(listOf("p0" to PageFilter.Grayscale), cropper.applied)
    }

    @Test
    fun `reports filter failures and keeps the page`() = runTest(dispatcher) {
        val viewModel = viewModel(listOf(page("p0", 0)), FakeCropper(DocumentException.Storage("disk")))
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.applyFilter("p0", PageFilter.Enhanced)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.filterFailed)
        assertEquals(PageFilter.Original, state.pages.single().filter)
        assertEquals(null, state.filteringPageId)
        viewModel.messageShown()
        assertFalse(viewModel.uiState.value.filterFailed)
    }

    @Test
    fun `loads the document pages`() = runTest(dispatcher) {
        val pages = List(3) { Page("p$it", "doc", it, File("/p$it.jpg"), Instant.EPOCH) }
        val viewModel = viewModel(pages)
        viewModel.refresh()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(pages, state.pages)
    }

    @Test
    fun `reports load failures`() = runTest(dispatcher) {
        val viewModel =
            PageViewerViewModel("doc", FakeRepository(emptyList(), DocumentException.Storage("io")), FakeCropper())
        viewModel.refresh()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.loadFailed)
    }

    private companion object {
        fun page(id: String, position: Int = 0) = Page(id, "doc", position, File("/$id.jpg"), Instant.EPOCH)
    }
}
