package app.nolva.data

import app.nolva.core.DocumentInfo
import app.nolva.core.NolvaCoreInterface
import app.nolva.core.NolvaException
import app.nolva.core.PageCorners
import app.nolva.core.PageInfo
import app.nolva.core.CornerDetection as CoreCornerDetection
import app.nolva.core.NormalizedPoint as CorePoint
import java.io.File
import java.time.Instant
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private val coreCorners = PageCorners(
    topLeft = CorePoint(0.1f, 0.2f),
    topRight = CorePoint(0.9f, 0.2f),
    bottomRight = CorePoint(0.9f, 0.8f),
    bottomLeft = CorePoint(0.1f, 0.8f),
)

class CoreDocumentRepositoryTest {

    private class FakeCore : NolvaCoreInterface {
        var failure: NolvaException? = null
        val documents = mutableListOf<DocumentInfo>()

        override fun createDocument(title: String): DocumentInfo {
            failure?.let { throw it }
            return DocumentInfo(
                id = "doc-${documents.size}",
                title = title,
                createdAtMs = 1_000,
                updatedAtMs = 2_000,
                pageCount = 0u,
            ).also { documents += it }
        }

        override fun listDocuments(): List<DocumentInfo> = documents

        var croppedWith: PageCorners? = null

        override fun addPage(documentId: String, imagePath: String): PageInfo = pageInfo(documentId)

        override fun listPages(documentId: String): List<PageInfo> = emptyList()

        override fun getPage(pageId: String): PageInfo = pageInfo("doc-1")

        override fun detectPageCorners(pageId: String): CoreCornerDetection? =
            CoreCornerDetection(corners = coreCorners, confidence = 0.9f)

        override fun cropPage(pageId: String, corners: PageCorners): PageInfo {
            croppedWith = corners
            return pageInfo("doc-1").copy(processedPath = "/data/nolva/doc-1/processed.jpg", corners = corners)
        }

        private fun pageInfo(documentId: String) = PageInfo(
            id = "page-0",
            documentId = documentId,
            position = 3u,
            originalPath = "/data/nolva/$documentId/original.jpg",
            processedPath = null,
            corners = null,
            createdAtMs = 3_000,
        )
    }

    private val dispatcher = StandardTestDispatcher()

    @Test
    fun `maps core documents to app models`() = runTest(dispatcher) {
        val repository = CoreDocumentRepository(dispatcher) { FakeCore() }

        val created = repository.createDocument("Contract")

        assertEquals("Contract", created.title)
        assertEquals(Instant.ofEpochMilli(1_000), created.createdAt)
        assertEquals(Instant.ofEpochMilli(2_000), created.updatedAt)
        assertEquals(listOf(created), repository.listDocuments())
    }

    @Test
    fun `maps pages including position and file`() = runTest(dispatcher) {
        val repository = CoreDocumentRepository(dispatcher) { FakeCore() }

        val page = repository.addPage("doc-1", File("/tmp/scan.jpg"))

        assertEquals(3, page.position)
        assertEquals(File("/data/nolva/doc-1/original.jpg"), page.originalFile)
    }

    @Test
    fun `translates core errors into DocumentException`() = runTest(dispatcher) {
        val coreError = NolvaException.InvalidArgument("title is empty")
        val core = FakeCore().apply { failure = coreError }
        val repository = CoreDocumentRepository(dispatcher) { core }

        try {
            repository.createDocument(" ")
            fail("expected DocumentException.InvalidInput")
        } catch (e: DocumentException.InvalidInput) {
            assertEquals("title is empty", e.message)
            // Search the whole chain: in debug mode kotlinx.coroutines may
            // copy exceptions crossing withContext (stack-trace recovery),
            // wrapping the original as the cause.
            val causes = generateSequence<Throwable>(e) { it.cause }
            assertTrue("core error must be kept as a cause", causes.any { it === coreError })
        }
    }

    @Test
    fun `maps detection and crops in both directions`() = runTest(dispatcher) {
        val core = FakeCore()
        val repository = CoreDocumentRepository(dispatcher) { core }

        val detection = repository.detectCorners("page-0")!!
        assertEquals(0.9f, detection.confidence)
        assertEquals(NormalizedPoint(0.1f, 0.2f), detection.corners.topLeft)

        val page = repository.cropPage("page-0", detection.corners)
        assertEquals(coreCorners, core.croppedWith)
        assertEquals(File("/data/nolva/doc-1/processed.jpg"), page.processedFile)
        assertEquals(page.processedFile, page.displayFile)
        assertEquals(detection.corners, page.corners)
    }

    @Test
    fun `creates the core lazily on first use`() = runTest(dispatcher) {
        var created = false
        val repository = CoreDocumentRepository(dispatcher) { created = true; FakeCore() }

        assertTrue("core must not be opened eagerly", !created)
        repository.listDocuments()
        assertTrue(created)
    }
}
