package app.nolva.ui.capture

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

    private fun viewModel(repository: DocumentRepository) =
        CaptureViewModel("doc", repository, tempFolder.newFolder("captures"), dispatcher)

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
