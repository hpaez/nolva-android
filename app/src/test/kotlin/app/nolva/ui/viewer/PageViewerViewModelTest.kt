package app.nolva.ui.viewer

import app.nolva.data.Document
import app.nolva.data.DocumentException
import app.nolva.data.DocumentRepository
import app.nolva.data.CornerDetection
import app.nolva.data.Corners
import app.nolva.data.Page
import app.nolva.data.PageCropper
import app.nolva.data.PageFilter
import app.nolva.data.PageText
import app.nolva.data.PageTextStore
import app.nolva.data.RecognizedLine
import app.nolva.data.TextMatch
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

    private class FakeTextStore(private val texts: Map<String, PageText> = emptyMap()) : PageTextStore {
        override suspend fun pagesNeedingText(limit: Int): List<Page> = emptyList()
        override suspend fun savePageText(pageId: String, image: File, lines: List<RecognizedLine>): PageText =
            error("unused")
        override suspend fun getPageText(pageId: String): PageText? = texts[pageId]
        override suspend fun searchText(query: String): List<TextMatch> = emptyList()
    }

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun viewModel(
        pages: List<Page>,
        cropper: PageCropper = FakeCropper(),
        failure: Exception? = null,
        texts: Map<String, PageText> = emptyMap(),
    ) = PageViewerViewModel(
        documentId = "doc",
        title = "Factura 3/2026",
        repository = FakeRepository(pages, failure),
        cropper = cropper,
        textStore = FakeTextStore(texts),
        exportDir = tempFolder.root.resolve("exports"),
        ioDispatcher = dispatcher,
    )

    @Test
    fun `shows recognized text, or says it is pending`() = runTest(dispatcher) {
        val texts = mapOf("p1" to PageText("p1", "Total: 5.990", emptyList()))
        val viewModel = viewModel(listOf(page("p0", 0), page("p1", 1)), texts = texts)
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.showText("p1")
        advanceUntilIdle()
        assertEquals(ShownText(2, "Total: 5.990"), viewModel.uiState.value.shownText)
        viewModel.textDismissed()
        assertEquals(null, viewModel.uiState.value.shownText)

        viewModel.showText("p0")
        advanceUntilIdle()
        assertEquals(PageViewerMessage.TextPending, viewModel.uiState.value.message)
        assertEquals(null, viewModel.uiState.value.shownText)
    }

    @Test
    fun `shares a copy of the page named after the document`() = runTest(dispatcher) {
        val image = tempFolder.newFile("processed.jpg").apply { writeText("jpeg") }
        val pages = listOf(page("p0", 0), page("p1", 1).copy(processedFile = image))
        val viewModel = viewModel(pages)
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.sharePage("p1")
        advanceUntilIdle()

        val shared = viewModel.uiState.value.pendingShare!!
        assertEquals("Factura 3_2026 - 2.jpg", shared.name)
        assertEquals(tempFolder.root.resolve("exports"), shared.parentFile)
        assertEquals("jpeg", shared.readText())
        viewModel.shareHandled()
        assertEquals(null, viewModel.uiState.value.pendingShare)
    }

    @Test
    fun `reports share failures`() = runTest(dispatcher) {
        val viewModel = viewModel(listOf(page("p0", 0)))  // its file doesn't exist
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.sharePage("p0")
        advanceUntilIdle()

        assertEquals(PageViewerMessage.ShareFailed, viewModel.uiState.value.message)
        assertEquals(null, viewModel.uiState.value.pendingShare)
    }

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
        assertEquals(PageViewerMessage.FilterFailed, state.message)
        assertEquals(PageFilter.Original, state.pages.single().filter)
        assertEquals(null, state.filteringPageId)
        viewModel.messageShown()
        assertEquals(null, viewModel.uiState.value.message)
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
        val viewModel = viewModel(emptyList(), failure = DocumentException.Storage("io"))
        viewModel.refresh()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.loadFailed)
    }

    private companion object {
        fun page(id: String, position: Int = 0) = Page(id, "doc", position, File("/$id.jpg"), Instant.EPOCH)
    }
}
