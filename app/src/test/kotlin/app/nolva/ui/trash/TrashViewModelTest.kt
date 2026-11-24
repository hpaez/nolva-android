package app.nolva.ui.trash

import app.nolva.data.Document
import app.nolva.data.DocumentEditor
import app.nolva.data.DocumentException
import app.nolva.data.DocumentTrash
import app.nolva.data.Page
import java.time.Duration
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
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TrashViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class FakeTrash(documents: List<Document>) : DocumentTrash {
        val trashed = documents.toMutableList()
        var failure: Exception? = null

        override suspend fun moveToTrash(documentId: String) = error("unused")
        override suspend fun restore(documentId: String): Document {
            failure?.let { throw it }
            return trashed.first { it.id == documentId }.also { trashed.remove(it) }.copy(trashedAt = null)
        }
        override suspend fun listTrash(): List<Document> = trashed.toList()
        override suspend fun emptyTrash(): Int {
            failure?.let { throw it }
            return trashed.size.also { trashed.clear() }
        }
        override suspend fun purgeExpired(): Int = error("unused")
    }

    private class FakeEditor(private val trash: FakeTrash) : DocumentEditor {
        override suspend fun deleteDocument(documentId: String) {
            trash.trashed.removeAll { it.id == documentId }
        }
        override suspend fun renameDocument(documentId: String, title: String): Document = error("unused")
        override suspend fun duplicateDocument(documentId: String, title: String): Document = error("unused")
        override suspend fun deletePage(pageId: String) = error("unused")
        override suspend fun movePage(pageId: String, toPosition: Int): List<Page> = error("unused")
    }

    private fun trashed(id: String, title: String) =
        Document(id, title, Instant.EPOCH, Instant.EPOCH, pageCount = 1, trashedAt = Instant.EPOCH)

    private fun viewModel(trash: FakeTrash) = TrashViewModel(trash, FakeEditor(trash))

    @Test
    fun `restores and deletes documents`() = runTest(dispatcher) {
        val trash = FakeTrash(listOf(trashed("a", "Boleta"), trashed("b", "Carta")))
        val viewModel = viewModel(trash)
        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(listOf("a", "b"), viewModel.uiState.value.documents.map { it.id })

        viewModel.restore("a")
        advanceUntilIdle()
        assertEquals("Boleta", viewModel.uiState.value.restoredTitle)
        viewModel.messageShown()

        viewModel.deleteForever("b")
        advanceUntilIdle()
        assertEquals(emptyList<Document>(), viewModel.uiState.value.documents)
        assertEquals(emptyList<Document>(), trash.trashed)
    }

    @Test
    fun `empties the trash, and reloads what is left if it fails`() = runTest(dispatcher) {
        val trash = FakeTrash(listOf(trashed("a", "Boleta")))
        val viewModel = viewModel(trash)
        viewModel.refresh()
        advanceUntilIdle()

        trash.failure = DocumentException.Storage("disk")
        viewModel.emptyTrash()
        advanceUntilIdle()
        assertEquals(TrashMessage.EmptyFailed, viewModel.uiState.value.message)
        assertEquals(1, viewModel.uiState.value.documents.size)

        trash.failure = null
        viewModel.emptyTrash()
        advanceUntilIdle()
        assertEquals(emptyList<Document>(), viewModel.uiState.value.documents)
    }

    @Test
    fun `counts the days left, rounding up`() {
        val trashedAt = Instant.parse("2026-11-01T10:00:00Z")
        val document = trashed("a", "x").copy(trashedAt = trashedAt)
        assertEquals(30, daysLeft(document, trashedAt))
        assertEquals(30, daysLeft(document, trashedAt + Duration.ofHours(1)))
        assertEquals(1, daysLeft(document, trashedAt + Duration.ofDays(29) + Duration.ofHours(23)))
        assertEquals(0, daysLeft(document, trashedAt + Duration.ofDays(30)))
        assertEquals(0, daysLeft(document, trashedAt + Duration.ofDays(45)))
    }
}
