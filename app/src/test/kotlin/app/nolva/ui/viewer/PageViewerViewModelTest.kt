package app.nolva.ui.viewer

import app.nolva.data.Document
import app.nolva.data.DocumentException
import app.nolva.data.DocumentRepository
import app.nolva.data.CornerDetection
import app.nolva.data.Corners
import app.nolva.data.NormalizedPoint
import app.nolva.data.Page
import app.nolva.data.PageCropper
import app.nolva.data.PageFilter
import app.nolva.data.PageRendering
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

    private class FakeRepository(var pages: List<Page>, private val failure: Exception? = null) :
        DocumentRepository {
        override suspend fun listPages(documentId: String): List<Page> {
            failure?.let { throw it }
            return pages
        }

        override suspend fun listDocuments(): List<Document> = emptyList()
        override suspend fun createDocument(title: String): Document = error("unused")
        override suspend fun addPage(documentId: String, image: File): Page = error("unused")
    }

    /** Keeps each page's rendering; renders number [failOnRender] (0-based) and later ones fail. */
    private class FakeCropper(
        private val failure: Exception? = null,
        var failOnRender: Int = Int.MAX_VALUE,
    ) : PageCropper {
        val pages = mutableMapOf<String, Page>()
        val applied = mutableListOf<Pair<String, PageFilter>>()
        private var renders = 0

        override suspend fun getPage(pageId: String): Page = error("unused")
        override suspend fun detectCorners(pageId: String): CornerDetection? = error("unused")
        override suspend fun cropPage(pageId: String, corners: Corners): Page = error("unused")

        override suspend fun applyFilter(pageId: String, filter: PageFilter): Page = render(pageId) {
            applied += pageId to filter
            it.copy(filter = filter)
        }

        override suspend fun rotatePage(pageId: String, clockwise: Boolean): Page = render(pageId) {
            it.copy(rotationDegrees = (it.rotationDegrees + if (clockwise) 90 else 270) % 360)
        }

        override suspend fun setRendering(pageId: String, rendering: PageRendering): Page = render(pageId) {
            it.copy(corners = rendering.corners, filter = rendering.filter, rotationDegrees = rendering.rotationDegrees)
        }

        private fun render(pageId: String, change: (Page) -> Page): Page {
            failure?.let { throw it }
            if (renders++ >= failOnRender) throw DocumentException.Storage("disk full")
            val updated = change(pages[pageId] ?: page(pageId)).let {
                val suffix = if (it.rotationDegrees == 0) "" else "-${it.rotationDegrees}"
                it.copy(processedFile = File("/$pageId-${it.filter}$suffix.jpg"))
            }
            pages[pageId] = updated
            return updated
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
        cropper: FakeCropper = FakeCropper(),
        failure: Exception? = null,
        texts: Map<String, PageText> = emptyMap(),
        repository: FakeRepository = FakeRepository(pages, failure),
    ) = PageViewerViewModel(
        documentId = "doc",
        title = "Factura 3/2026",
        repository = repository.also { cropper.pages.putAll(pages.associateBy { it.id }) },
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
        assertEquals("p1", viewModel.uiState.value.renderingPageId)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(null, state.renderingPageId)
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
        assertEquals(null, state.renderingPageId)
        viewModel.messageShown()
        assertEquals(null, viewModel.uiState.value.message)
    }

    @Test
    fun `rotates both ways and undoes and redoes`() = runTest(dispatcher) {
        val viewModel = viewModel(listOf(page("p0", 0)))
        viewModel.refresh()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.canUndo)

        viewModel.rotate("p0", clockwise = true)
        assertEquals("p0", viewModel.uiState.value.renderingPageId)
        advanceUntilIdle()
        viewModel.rotate("p0", clockwise = true)
        advanceUntilIdle()
        assertEquals(180, viewModel.uiState.value.pages.single().rotationDegrees)

        viewModel.undo()
        advanceUntilIdle()
        assertEquals(90, viewModel.uiState.value.pages.single().rotationDegrees)
        assertTrue(viewModel.uiState.value.canRedo)

        viewModel.undo()
        advanceUntilIdle()
        var state = viewModel.uiState.value
        assertEquals(0, state.pages.single().rotationDegrees)
        assertFalse(state.canUndo)

        viewModel.redo()
        advanceUntilIdle()
        state = viewModel.uiState.value
        assertEquals(90, state.pages.single().rotationDegrees)
        assertTrue(state.canUndo)
        assertTrue(state.canRedo)

        viewModel.rotate("p0", clockwise = false)
        advanceUntilIdle()
        state = viewModel.uiState.value
        assertEquals(0, state.pages.single().rotationDegrees)
        assertFalse("a new edit drops what was undone", state.canRedo)
    }

    @Test
    fun `filters and rotations undo together, keeping each other`() = runTest(dispatcher) {
        val viewModel = viewModel(listOf(page("p0", 0)))
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.applyFilter("p0", PageFilter.Grayscale)
        advanceUntilIdle()
        viewModel.rotate("p0", clockwise = true)
        advanceUntilIdle()
        viewModel.undo()
        advanceUntilIdle()

        val page = viewModel.uiState.value.pages.single()
        assertEquals(PageRendering(filter = PageFilter.Grayscale), page.rendering)
    }

    @Test
    fun `applies a filter to every page as one undoable edit`() = runTest(dispatcher) {
        val pages = listOf(page("p0", 0), page("p1", 1).copy(filter = PageFilter.Enhanced), page("p2", 2))
        val cropper = FakeCropper()
        val viewModel = viewModel(pages, cropper)
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.applyFilterToAll(PageFilter.Enhanced)
        assertEquals(BulkProgress(0, 2), viewModel.uiState.value.bulkProgress)
        viewModel.applyFilter("p0", PageFilter.Grayscale)  // ignored while busy
        advanceUntilIdle()

        var state = viewModel.uiState.value
        assertEquals(null, state.bulkProgress)
        assertEquals(List(3) { PageFilter.Enhanced }, state.pages.map { it.filter })
        assertEquals(listOf("p0" to PageFilter.Enhanced, "p2" to PageFilter.Enhanced), cropper.applied)

        viewModel.undo()
        advanceUntilIdle()
        state = viewModel.uiState.value
        assertEquals(
            listOf(PageFilter.Original, PageFilter.Enhanced, PageFilter.Original),
            state.pages.map { it.filter },
        )
        assertFalse(state.canUndo)
    }

    @Test
    fun `a failure while filtering every page keeps what was done`() = runTest(dispatcher) {
        val cropper = FakeCropper(failOnRender = 1)
        val viewModel = viewModel(List(3) { page("p$it", it) }, cropper)
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.applyFilterToAll(PageFilter.BlackAndWhite)
        advanceUntilIdle()

        var state = viewModel.uiState.value
        assertEquals(PageViewerMessage.FilterFailed, state.message)
        assertEquals(
            listOf(PageFilter.BlackAndWhite, PageFilter.Original, PageFilter.Original),
            state.pages.map { it.filter },
        )
        assertTrue(state.canUndo)
        assertFalse(state.isBusy)

        // A failed undo stays undoable, and works once rendering does.
        viewModel.undo()
        advanceUntilIdle()
        assertEquals(PageViewerMessage.UndoFailed, viewModel.uiState.value.message)
        assertTrue(viewModel.uiState.value.canUndo)
        cropper.failOnRender = Int.MAX_VALUE
        viewModel.undo()
        advanceUntilIdle()
        state = viewModel.uiState.value
        assertEquals(PageFilter.Original, state.pages.first().filter)
        assertFalse(state.canUndo)
    }

    @Test
    fun `reports rotation failures and keeps the page`() = runTest(dispatcher) {
        val viewModel = viewModel(listOf(page("p0", 0)), FakeCropper(DocumentException.Storage("disk")))
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.rotate("p0", clockwise = true)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(PageViewerMessage.RotateFailed, state.message)
        assertEquals(0, state.pages.single().rotationDegrees)
        assertFalse(state.canUndo)
    }

    @Test
    fun `crops made in the editor can be undone, other changes drop the history`() = runTest(dispatcher) {
        val corners = Corners(
            NormalizedPoint(0.1f, 0.1f),
            NormalizedPoint(0.9f, 0.1f),
            NormalizedPoint(0.9f, 0.9f),
            NormalizedPoint(0.1f, 0.9f),
        )
        val pages = listOf(page("p0", 0), page("p1", 1))
        val repository = FakeRepository(pages)
        val viewModel = viewModel(pages, repository = repository)
        viewModel.refresh()
        advanceUntilIdle()

        // Back from the crop editor with p1 cropped.
        repository.pages = listOf(pages[0], pages[1].copy(corners = corners, processedFile = File("/c.jpg")))
        viewModel.refresh()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.canUndo)
        viewModel.undo()
        advanceUntilIdle()
        assertEquals(null, viewModel.uiState.value.pages[1].corners)

        // A page deleted elsewhere: the history no longer applies.
        repository.pages = listOf(pages[0])
        viewModel.refresh()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.canUndo)
        assertFalse(viewModel.uiState.value.canRedo)
    }

    @Test
    fun `keeps a bounded history`() = runTest(dispatcher) {
        val viewModel = viewModel(listOf(page("p0", 0)))
        viewModel.refresh()
        advanceUntilIdle()

        repeat(MAX_HISTORY + 5) {
            viewModel.rotate("p0", clockwise = true)
            advanceUntilIdle()
        }
        repeat(MAX_HISTORY) {
            viewModel.undo()
            advanceUntilIdle()
        }

        assertFalse(viewModel.uiState.value.canUndo)
        // 35 turns, 30 undone: 5 quarter turns remain.
        assertEquals(90, viewModel.uiState.value.pages.single().rotationDegrees)
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
