package app.nolva.ui.capture

import app.nolva.data.CornerDetection
import app.nolva.data.Corners
import app.nolva.data.Document
import app.nolva.data.DocumentEditor
import app.nolva.data.DocumentException
import app.nolva.data.DocumentRepository
import app.nolva.data.LumaFrame
import app.nolva.data.Page
import app.nolva.data.PageCropper
import app.nolva.data.PageFilter
import app.nolva.data.PageRendering
import app.nolva.data.SheetDetector
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class CaptureViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class FakeRepository(private val failure: Exception? = null) : DocumentRepository {
        val added = mutableListOf<String>()

        override suspend fun addPage(documentId: String, image: File): Page {
            failure?.let { throw it }
            check(image.exists()) { "the core must receive an existing file" }
            added += image.name
            return Page("p${added.size}", documentId, added.size - 1, image, Instant.EPOCH)
        }

        override suspend fun listDocuments(): List<Document> = emptyList()
        override suspend fun createDocument(title: String): Document = error("unused")
        override suspend fun listPages(documentId: String): List<Page> = emptyList()
    }

    /** Detects with the given confidence (or nothing) and records crops. */
    private class FakeCropper(private val confidence: Float?, private val failCrop: Boolean = false) : PageCropper {
        val cropped = mutableListOf<String>()

        override suspend fun getPage(pageId: String): Page = error("unused")

        override suspend fun detectCorners(pageId: String): CornerDetection? =
            confidence?.let { CornerDetection(Corners.FULL, it) }

        override suspend fun cropPage(pageId: String, corners: Corners): Page {
            if (failCrop) throw DocumentException.InvalidInput("crossed corners")
            cropped += pageId
            return Page(pageId, "doc", 0, File("/o.jpg"), Instant.EPOCH, processedFile = File("/p.jpg"))
        }

        override suspend fun applyFilter(pageId: String, filter: PageFilter): Page = error("unused")
        override suspend fun rotatePage(pageId: String, clockwise: Boolean): Page = error("unused")
        override suspend fun setRendering(pageId: String, rendering: PageRendering): Page = error("unused")
    }

    /** Deletes pages, or fails with [failure]. */
    private class FakeEditor(private val failure: Exception? = null) : DocumentEditor {
        val deleted = mutableListOf<String>()

        override suspend fun deletePage(pageId: String) {
            failure?.let { throw it }
            deleted += pageId
        }

        override suspend fun renameDocument(documentId: String, title: String): Document = error("unused")
        override suspend fun deleteDocument(documentId: String) = error("unused")
        override suspend fun duplicateDocument(documentId: String, title: String): Document = error("unused")
        override suspend fun movePage(pageId: String, toPosition: Int): List<Page> = error("unused")
    }

    private fun viewModel(
        repository: DocumentRepository,
        cropper: PageCropper = FakeCropper(confidence = null),
        editor: DocumentEditor = FakeEditor(),
        detector: SheetDetector = SheetDetector { null },
    ) = CaptureViewModel("doc", repository, cropper, editor, detector, tempFolder.newFolder(), dispatcher)

    private fun frame() = LumaFrame(width = 640, height = 480, rowStride = 640, luma = ByteArray(640 * 480), rotationDegrees = 90)

    @Test
    fun `a sheet held still requests a photo until handled`() = runTest(dispatcher) {
        val sheet = CornerDetection(Corners.FULL, 0.95f)
        val viewModel = viewModel(FakeRepository(), detector = { sheet })

        for (t in 0..10) viewModel.analyzeFrame(frame(), nowMs = t * 100L)

        val state = viewModel.uiState.value
        assertTrue(state.autoShutter)
        assertEquals(0.75f, state.guide!!.frameAspect, 0.001f)
        viewModel.autoShutterHandled()
        assertFalse(viewModel.uiState.value.autoShutter)
    }

    @Test
    fun `with auto capture off the guide shows but no photo is requested`() = runTest(dispatcher) {
        val viewModel = viewModel(FakeRepository(), detector = { CornerDetection(Corners.FULL, 0.95f) })
        viewModel.toggleAutoCapture()

        for (t in 0..20) viewModel.analyzeFrame(frame(), nowMs = t * 100L)

        assertFalse(viewModel.uiState.value.autoShutter)
        assertNotNull(viewModel.uiState.value.guide)
    }

    @Test
    fun `detector failures just hide the guide`() = runTest(dispatcher) {
        val viewModel = viewModel(FakeRepository(), detector = { error("native crash") })
        viewModel.analyzeFrame(frame(), nowMs = 0)
        assertNull(viewModel.uiState.value.guide)
    }

    @Test
    fun `flash cycles through off, auto and on`() = runTest(dispatcher) {
        val viewModel = viewModel(FakeRepository())
        assertEquals(FlashMode.Auto, viewModel.uiState.value.flash)
        viewModel.cycleFlash()
        assertEquals(FlashMode.On, viewModel.uiState.value.flash)
        viewModel.cycleFlash()
        assertEquals(FlashMode.Off, viewModel.uiState.value.flash)
    }

    @Test
    fun `keeps the captured pages as stored, cropped when possible`() = runTest(dispatcher) {
        val viewModel = viewModel(FakeRepository(), FakeCropper(confidence = 0.95f))

        viewModel.onPictureTaken(viewModel.newCaptureFile().apply { writeText("jpeg") })
        advanceUntilIdle()

        val page = viewModel.uiState.value.captured.single()
        assertEquals("p1", page.id)
        assertEquals(File("/p.jpg"), page.displayFile)
    }

    @Test
    fun `discarding removes the page from the document and the session`() = runTest(dispatcher) {
        val editor = FakeEditor()
        val viewModel = viewModel(FakeRepository(), editor = editor)
        repeat(2) { viewModel.onPictureTaken(viewModel.newCaptureFile().apply { writeText("jpeg") }) }
        advanceUntilIdle()

        viewModel.discard("p2")
        advanceUntilIdle()

        assertEquals(listOf("p2"), editor.deleted)
        assertEquals(listOf("p1"), viewModel.uiState.value.captured.map { it.id })
        assertEquals(1, viewModel.uiState.value.capturedCount)
    }

    @Test
    fun `failed discards keep the page and report`() = runTest(dispatcher) {
        val viewModel = viewModel(FakeRepository(), editor = FakeEditor(DocumentException.Storage("disk")))
        viewModel.onPictureTaken(viewModel.newCaptureFile().apply { writeText("jpeg") })
        advanceUntilIdle()

        viewModel.discard("p1")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1, state.capturedCount)
        assertEquals(CaptureMessage.DiscardFailed, state.message)
    }

    @Test
    fun `crops automatically when the sheet is detected confidently`() = runTest(dispatcher) {
        val cropper = FakeCropper(confidence = 0.95f)
        val viewModel = viewModel(FakeRepository(), cropper)

        viewModel.onPictureTaken(viewModel.newCaptureFile().apply { writeText("jpeg") })
        advanceUntilIdle()

        assertEquals(listOf("p1"), cropper.cropped)
        assertEquals(1, viewModel.uiState.value.capturedCount)
    }

    @Test
    fun `keeps the original when detection is unsure or cropping fails`() = runTest(dispatcher) {
        for (cropper in listOf(FakeCropper(confidence = 0.3f), FakeCropper(confidence = 0.9f, failCrop = true))) {
            val viewModel = viewModel(FakeRepository(), cropper)

            viewModel.onPictureTaken(viewModel.newCaptureFile().apply { writeText("jpeg") })
            advanceUntilIdle()

            assertEquals(emptyList<String>(), cropper.cropped)
            // The page is saved regardless: auto-cropping is best effort.
            assertEquals(1, viewModel.uiState.value.capturedCount)
            assertEquals(null, viewModel.uiState.value.message)
        }
    }

    @Test
    fun `stores captures as pages and deletes the temp files`() = runTest(dispatcher) {
        val repository = FakeRepository()
        val viewModel = viewModel(repository)

        val first = viewModel.newCaptureFile().apply { writeText("jpeg") }
        val second = viewModel.newCaptureFile().apply { writeText("jpeg") }
        viewModel.onPictureTaken(first)
        viewModel.onPictureTaken(second)
        assertEquals(2, viewModel.uiState.value.pendingSaves)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(2, state.capturedCount)
        assertFalse(state.isSaving)
        assertEquals(2, repository.added.size)
        assertFalse(first.exists())
        assertFalse(second.exists())
    }

    @Test
    fun `failed saves are reported and still clean up`() = runTest(dispatcher) {
        val viewModel = viewModel(FakeRepository(failure = DocumentException.Storage("disk full")))

        val file = viewModel.newCaptureFile().apply { writeText("jpeg") }
        viewModel.onPictureTaken(file)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(0, state.capturedCount)
        assertEquals(CaptureMessage.SaveFailed, state.message)
        assertFalse(file.exists())
    }

    @Test
    fun `capture failures are reported`() = runTest(dispatcher) {
        val viewModel = viewModel(FakeRepository())

        viewModel.onCaptureFailed(file = null)

        assertEquals(CaptureMessage.CaptureFailed, viewModel.uiState.value.message)
    }
}
