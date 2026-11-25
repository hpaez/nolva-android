package app.nolva.ui.documents

import app.nolva.data.Document
import app.nolva.data.DocumentEditor
import app.nolva.data.DocumentException
import app.nolva.data.DocumentRepository
import app.nolva.data.DocumentSort
import app.nolva.data.DocumentTrash
import app.nolva.data.Folder
import app.nolva.data.FolderStore
import app.nolva.data.ListLayout
import app.nolva.data.ListPreferences
import app.nolva.data.ListSettings
import app.nolva.data.Page
import app.nolva.data.PageText
import app.nolva.data.PageTextStore
import app.nolva.data.PdfImportProgress
import app.nolva.data.PdfImporter
import app.nolva.data.RecognizedLine
import app.nolva.data.TextMatch
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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

        override suspend fun duplicateDocument(documentId: String, title: String): Document {
            failure?.let { throw it }
            return document("copy-of-$documentId", title).also { repository.documents.add(0, it) }
        }

        override suspend fun deletePage(pageId: String) = error("unused")

        override suspend fun movePage(pageId: String, toPosition: Int): List<Page> = error("unused")
    }

    /** Folders kept in memory; moves update the repository's documents. */
    private class FakeFolderStore(private val repository: FakeRepository) : FolderStore {
        val folders = mutableListOf<Folder>()
        var failure: Exception? = null

        private fun counted(folder: Folder) =
            folder.copy(documentCount = repository.documents.count { it.folderId == folder.id })

        override suspend fun listFolders(): List<Folder> = folders.map(::counted)

        override suspend fun createFolder(name: String): Folder {
            failure?.let { throw it }
            return Folder("f${folders.size}", name, Instant.EPOCH, Instant.EPOCH, 0).also { folders += it }
        }

        override suspend fun renameFolder(folderId: String, name: String): Folder {
            failure?.let { throw it }
            val index = folders.indexOfFirst { it.id == folderId }
            return folders[index].copy(name = name).also { folders[index] = it }
        }

        override suspend fun deleteFolder(folderId: String) {
            failure?.let { throw it }
            folders.removeAll { it.id == folderId }
        }

        override suspend fun moveDocuments(documentIds: List<String>, folderId: String?): Int {
            failure?.let { throw it }
            repository.documents.replaceAll { if (it.id in documentIds) it.copy(folderId = folderId) else it }
            return documentIds.size
        }

        override suspend fun createDocumentIn(title: String, folderId: String?): Document =
            document("in-$folderId", title).copy(folderId = folderId).also { repository.documents.add(0, it) }
    }

    /** Moves documents between the repository and its own trashed list. */
    private class FakeTrash(private val repository: FakeRepository) : DocumentTrash {
        var failure: Exception? = null
        val trashed = mutableListOf<Document>()

        override suspend fun moveToTrash(documentId: String) {
            failure?.let { throw it }
            val document = repository.documents.first { it.id == documentId }
            repository.documents.remove(document)
            trashed += document
        }

        override suspend fun restore(documentId: String): Document {
            failure?.let { throw it }
            val document = trashed.first { it.id == documentId }
            trashed.remove(document)
            repository.documents += document
            return document
        }

        override suspend fun listTrash(): List<Document> = trashed.toList()
        override suspend fun emptyTrash(): Int = error("unused")
        override suspend fun purgeExpired(): Int = error("unused")
    }

    /** Returns [matches] for any query and records the queries it got. */
    private class FakeTextStore(var matches: List<TextMatch> = emptyList()) : PageTextStore {
        val queries = mutableListOf<String>()

        override suspend fun searchText(query: String): List<TextMatch> {
            queries += query
            return matches
        }

        override suspend fun pagesNeedingText(limit: Int): List<Page> = emptyList()
        override suspend fun savePageText(pageId: String, image: File, lines: List<RecognizedLine>): PageText =
            error("unused")
        override suspend fun getPageText(pageId: String): PageText? = null
    }

    private fun viewModel(
        repository: FakeRepository,
        editor: DocumentEditor = FakeEditor(repository),
        textStore: PageTextStore = FakeTextStore(),
        pdfImporter: PdfImporter = PdfImporter { _, _ -> error("unused") },
        trash: DocumentTrash = FakeTrash(repository),
        folderStore: FolderStore = FakeFolderStore(repository),
        folderId: String? = null,
        preferences: ListPreferences = FakePreferences(),
    ) = DocumentListViewModel(folderId, repository, editor, textStore, pdfImporter, trash, folderStore, preferences)

    private class FakePreferences(var settings: ListSettings = ListSettings()) : ListPreferences {
        override suspend fun load(): ListSettings = settings
        override suspend fun save(settings: ListSettings) {
            this.settings = settings
        }
    }

    private fun doc(id: String, title: String, updated: Long, created: Long = updated, pages: Int = 1, size: Long = 0) =
        document(id, title).copy(
            updatedAt = Instant.ofEpochMilli(updated),
            createdAt = Instant.ofEpochMilli(created),
            pageCount = pages,
            sizeBytes = size,
        )

    @Test
    fun `sorts by each order and remembers the choice`() = runTest(dispatcher) {
        val repository = FakeRepository().apply {
            documents += listOf(
                doc("a", "Árbol", updated = 3, created = 1, pages = 2, size = 50),
                doc("b", "boleta", updated = 2, created = 3, pages = 9, size = 10),
                doc("c", "Contrato", updated = 1, created = 2, pages = 1, size = 900),
            )
        }
        val preferences = FakePreferences(ListSettings(sort = DocumentSort.Name))
        val viewModel = viewModel(repository, preferences = preferences)
        viewModel.refresh()
        advanceUntilIdle()
        val order = { viewModel.uiState.value.visibleDocuments.map { it.id } }

        assertEquals(listOf("a", "b", "c"), order())
        val expected = mapOf(
            DocumentSort.Updated to listOf("a", "b", "c"),
            DocumentSort.Created to listOf("b", "c", "a"),
            DocumentSort.Size to listOf("c", "a", "b"),
            DocumentSort.Pages to listOf("b", "a", "c"),
        )
        for ((sort, ids) in expected) {
            viewModel.setSort(sort)
            assertEquals(sort.name, ids, order())
        }
        viewModel.setLayout(ListLayout.Grid)
        advanceUntilIdle()
        assertEquals(ListSettings(DocumentSort.Pages, ListLayout.Grid), preferences.settings)
    }

    @Test
    fun `selects documents and acts on them together`() = runTest(dispatcher) {
        val repository = FakeRepository().apply {
            documents += listOf(doc("a", "A", 3), doc("b", "B", 2), doc("c", "C", 1).copy(folderId = "f0"))
        }
        val folders = FakeFolderStore(repository).apply {
            folders += Folder("f0", "Cuentas", Instant.EPOCH, Instant.EPOCH, 1)
        }
        val trash = FakeTrash(repository)
        val viewModel = viewModel(repository, trash = trash, folderStore = folders)
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.toggleSelection("a")
        viewModel.toggleSelection("b")
        viewModel.toggleSelection("a")
        assertEquals(setOf("b"), viewModel.uiState.value.selectedIds)
        viewModel.selectAll()
        assertEquals("only what's on screen", setOf("a", "b"), viewModel.uiState.value.selectedIds)

        viewModel.moveSelected("f0")
        advanceUntilIdle()
        var state = viewModel.uiState.value
        assertEquals(emptySet<String>(), state.selectedIds)
        assertEquals(emptyList<Document>(), state.visibleDocuments)
        assertEquals(3, state.folders.single().documentCount)

        val inside = viewModel(repository, trash = trash, folderStore = folders, folderId = "f0")
        inside.refresh()
        advanceUntilIdle()
        inside.selectAll()
        inside.deleteSelected()
        advanceUntilIdle()
        state = inside.uiState.value
        assertEquals(3, state.trashedDocuments.size)
        assertEquals(emptyList<Document>(), state.visibleDocuments)

        inside.undoTrash()
        advanceUntilIdle()
        assertEquals(listOf("a", "b", "c"), inside.uiState.value.visibleDocuments.map { it.id })
    }

    @Test
    fun `new folders from a bulk move take every selected document`() = runTest(dispatcher) {
        val repository = FakeRepository().apply { documents += listOf(doc("a", "A", 2), doc("b", "B", 1)) }
        val viewModel = viewModel(repository)
        viewModel.refresh()
        advanceUntilIdle()
        viewModel.selectAll()

        viewModel.createFolder("Nueva", thenMove = viewModel.uiState.value.selectedIds.toList())
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("f0", "f0"), state.documents.map { it.folderId })
        assertEquals(emptySet<String>(), state.selectedIds)
    }

    @Test
    fun `the top level shows folders and loose documents, a folder only its own`() = runTest(dispatcher) {
        val repository = FakeRepository().apply {
            documents += listOf(document("a", "Suelto"), document("b", "Luz").copy(folderId = "f0"))
        }
        val folders = FakeFolderStore(repository).apply {
            folders += Folder("f0", "Cuentas", Instant.EPOCH, Instant.EPOCH, 0)
        }
        val top = viewModel(repository, folderStore = folders)
        val inside = viewModel(repository, folderStore = folders, folderId = "f0")
        top.refresh()
        inside.refresh()
        advanceUntilIdle()

        assertEquals(listOf("a"), top.uiState.value.visibleDocuments.map { it.id })
        assertEquals(listOf("Cuentas"), top.uiState.value.visibleFolders.map { it.name })
        assertEquals(1, top.uiState.value.visibleFolders.single().documentCount)
        assertEquals(listOf("b"), inside.uiState.value.visibleDocuments.map { it.id })
        assertEquals(emptyList<Folder>(), inside.uiState.value.visibleFolders)

        // Searching from the top level looks inside folders too; inside a folder, only there.
        top.search("luz")
        inside.search("suelto")
        advanceUntilIdle()
        assertEquals(listOf("b"), top.uiState.value.visibleDocuments.map { it.id })
        assertEquals(emptyList<Document>(), inside.uiState.value.visibleDocuments)
    }

    @Test
    fun `documents move between folders and counts follow`() = runTest(dispatcher) {
        val repository = FakeRepository().apply { documents += document("a", "Luz") }
        val viewModel = viewModel(repository)
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.createFolder("Cuentas", thenMove = listOf("a"))
        advanceUntilIdle()

        var state = viewModel.uiState.value
        assertEquals("f0", state.documents.single().folderId)
        assertEquals(1, state.folders.single().documentCount)
        assertEquals(emptyList<Document>(), state.visibleDocuments)

        viewModel.moveDocument("a", null)
        advanceUntilIdle()
        state = viewModel.uiState.value
        assertEquals(null, state.documents.single().folderId)
        assertEquals(0, state.folders.single().documentCount)
    }

    @Test
    fun `deleting a folder brings its documents back to the top level`() = runTest(dispatcher) {
        val repository = FakeRepository().apply { documents += document("a", "Luz").copy(folderId = "f0") }
        val folders = FakeFolderStore(repository).apply {
            folders += Folder("f0", "Cuentas", Instant.EPOCH, Instant.EPOCH, 1)
        }
        val viewModel = viewModel(repository, folderStore = folders)
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.renameFolder("f0", "Hogar")
        advanceUntilIdle()
        assertEquals("Hogar", viewModel.uiState.value.folders.single().name)

        viewModel.deleteFolder("f0")
        advanceUntilIdle()
        val state = viewModel.uiState.value
        assertEquals(emptyList<Folder>(), state.folders)
        assertEquals(listOf("a"), state.visibleDocuments.map { it.id })
    }

    @Test
    fun `documents created inside a folder stay there`() = runTest(dispatcher) {
        val repository = FakeRepository()
        val viewModel = viewModel(repository, folderId = "f0")
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.createDocument("Agua")
        advanceUntilIdle()

        assertEquals(listOf("f0"), viewModel.uiState.value.visibleDocuments.map { it.folderId })
    }

    @Test
    fun `folder failures report, and invalid names say so`() = runTest(dispatcher) {
        val repository = FakeRepository()
        val folders = FakeFolderStore(repository)
        val viewModel = viewModel(repository, folderStore = folders)

        folders.failure = DocumentException.InvalidInput("blank")
        viewModel.createFolder("  ")
        advanceUntilIdle()
        assertEquals(DocumentListMessage.InvalidTitle, viewModel.uiState.value.message)

        viewModel.messageShown()
        folders.failure = DocumentException.Storage("disk")
        viewModel.moveDocument("a", "f0")
        advanceUntilIdle()
        assertEquals(DocumentListMessage.MoveFailed, viewModel.uiState.value.message)
    }

    @Test
    fun `imported pdfs show progress, join the list and open`() = runTest(dispatcher) {
        val repository = FakeRepository().apply { documents += document("a", "Old") }
        val seen = mutableListOf<PdfImportProgress?>()
        lateinit var viewModel: DocumentListViewModel
        val importer = PdfImporter { _, onProgress ->
            onProgress(PdfImportProgress(0, 2))
            seen += viewModel.uiState.value.pdfImport
            onProgress(PdfImportProgress(2, 2))
            document("pdf", "Contrato").copy(pageCount = 2)
        }
        viewModel = viewModel(repository, pdfImporter = importer)
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.importPdf("content://downloads/1")
        assertEquals(PdfImportProgress(0, 0), viewModel.uiState.value.pdfImport)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf(PdfImportProgress(0, 2)), seen)
        assertEquals(null, state.pdfImport)
        assertEquals(listOf("pdf", "a"), state.documents.map { it.id })
        assertEquals("pdf", state.openDocument?.id)
        viewModel.documentOpened()
        assertEquals(null, viewModel.uiState.value.openDocument)
    }

    @Test
    fun `pdf import failures explain what went wrong`() = runTest(dispatcher) {
        val cases = mapOf(
            DocumentException.Unsupported("password") to DocumentListMessage.PdfUnreadable,
            DocumentException.InvalidInput("0 pages") to DocumentListMessage.PdfTooLarge,
            DocumentException.Storage("disk full") to DocumentListMessage.PdfImportFailed,
        )
        for ((error, expected) in cases) {
            val viewModel = viewModel(FakeRepository(), pdfImporter = { _, _ -> throw error })
            viewModel.importPdf("content://x")
            advanceUntilIdle()

            assertEquals(expected, viewModel.uiState.value.message)
            assertEquals(null, viewModel.uiState.value.pdfImport)
            assertEquals(null, viewModel.uiState.value.openDocument)
        }
    }

    private fun match(documentId: String, page: Int) = TextMatch(documentId, "$documentId-p$page", page, "…total $page…")

    @Test
    fun `search finds documents by title or by page text`() = runTest(dispatcher) {
        val repository = FakeRepository().apply {
            documents += listOf(document("a", "Factura luz"), document("b", "Contrato"), document("c", "Otro"))
        }
        val text = FakeTextStore(listOf(match("b", 2), match("b", 0), match("a", 1)))
        val viewModel = viewModel(repository, textStore = text)
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.search("fact")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("a", "b"), state.visibleDocuments.map { it.id })
        assertEquals("best match per document", 2, state.contentMatches.getValue("b").pagePosition)
        assertEquals(listOf("fact"), text.queries)
    }

    @Test
    fun `typing waits for a pause and only the last query runs`() = runTest(dispatcher) {
        val repository = FakeRepository().apply { documents += document("a", "A") }
        val text = FakeTextStore(listOf(match("a", 0)))
        val viewModel = viewModel(repository, textStore = text)
        viewModel.refresh()
        advanceUntilIdle()
        text.queries.clear()

        viewModel.search("b")
        advanceTimeBy(SEARCH_DEBOUNCE_MS / 2)
        viewModel.search("bo")
        advanceTimeBy(SEARCH_DEBOUNCE_MS / 2)
        viewModel.search("bol")
        advanceUntilIdle()

        assertEquals(listOf("bol"), text.queries)
        viewModel.search("")
        assertEquals(emptyMap<String, TextMatch>(), viewModel.uiState.value.contentMatches)
        assertEquals(listOf("a"), viewModel.uiState.value.visibleDocuments.map { it.id })
    }

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
    fun `deleted documents go to the trash and can come back`() = runTest(dispatcher) {
        val older = document("a", "Older").copy(updatedAt = Instant.ofEpochMilli(1))
        val newer = document("b", "Newer").copy(updatedAt = Instant.ofEpochMilli(2))
        val repository = FakeRepository().apply { documents += listOf(newer, older) }
        val trash = FakeTrash(repository)
        val viewModel = viewModel(repository, trash = trash)
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.deleteDocument("b")
        advanceUntilIdle()

        assertEquals(listOf("Older"), viewModel.uiState.value.documents.map { it.title })
        assertEquals(listOf("b"), trash.trashed.map { it.id })
        assertEquals(listOf("b"), viewModel.uiState.value.trashedDocuments.map { it.id })

        viewModel.undoTrash()
        assertEquals(emptyList<Document>(), viewModel.uiState.value.trashedDocuments)
        advanceUntilIdle()

        // Back in its place in the list as shown (sorted by last edit).
        assertEquals(listOf("Newer", "Older"), viewModel.uiState.value.visibleDocuments.map { it.title })
        assertEquals(emptyList<Document>(), trash.trashed)
    }

    @Test
    fun `the trash notice can be dismissed`() = runTest(dispatcher) {
        val repository = FakeRepository().apply { documents += document("a", "Lease") }
        val viewModel = viewModel(repository)
        viewModel.refresh()
        advanceUntilIdle()
        viewModel.deleteDocument("a")
        advanceUntilIdle()

        viewModel.trashNoticeShown()
        viewModel.undoTrash()
        advanceUntilIdle()

        assertEquals(emptyList<Document>(), viewModel.uiState.value.documents)
    }

    @Test
    fun `duplicates go to the top of the list`() = runTest(dispatcher) {
        val repository = FakeRepository().apply { documents += document("a", "Lease") }
        val viewModel = viewModel(repository)
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.duplicateDocument("a", "Lease (copia)")
        advanceUntilIdle()

        assertEquals(listOf("Lease (copia)", "Lease"), viewModel.uiState.value.documents.map { it.title })
    }

    @Test
    fun `failed duplicates report`() = runTest(dispatcher) {
        val repository = FakeRepository().apply { documents += document("a", "Lease") }
        val editor = FakeEditor(repository).apply { failure = DocumentException.Storage("disk") }
        val viewModel = viewModel(repository, editor)
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.duplicateDocument("a", "Copy")
        advanceUntilIdle()

        assertEquals(DocumentListMessage.DuplicateFailed, viewModel.uiState.value.message)
        assertEquals(1, viewModel.uiState.value.documents.size)
    }

    @Test
    fun `deleting a document that is already gone still removes it`() = runTest(dispatcher) {
        val repository = FakeRepository().apply { documents += document("a", "Ghost") }
        val trash = FakeTrash(repository).apply { failure = DocumentException.NotFound("a") }
        val viewModel = viewModel(repository, trash = trash)
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
        val trash = FakeTrash(repository).apply { failure = DocumentException.Storage("disk") }
        val viewModel = viewModel(repository, trash = trash)
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
