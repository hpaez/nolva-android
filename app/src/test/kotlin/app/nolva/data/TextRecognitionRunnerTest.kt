package app.nolva.data

import java.io.File
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class TextRecognitionRunnerTest {

    private fun page(id: String) = Page(
        id = id,
        documentId = "doc",
        position = 0,
        originalFile = File("/$id.jpg"),
        createdAt = Instant.EPOCH,
    )

    private val line = RecognizedLine(0, "hola", TextRect(0f, 0f, 1f, 0.1f))

    /** Pages stay pending until saved; [conflicts] are rejected once, [failing] always. */
    private class FakeStore(
        pages: List<Page>,
        private val conflicts: MutableSet<String> = mutableSetOf(),
        private val failing: Set<String> = emptySet(),
    ) : PageTextStore {
        val pending = pages.toMutableList()
        val saved = mutableMapOf<String, List<RecognizedLine>>()

        override suspend fun pagesNeedingText(limit: Int): List<Page> = pending.take(limit)

        override suspend fun savePageText(pageId: String, image: File, lines: List<RecognizedLine>): PageText {
            if (pageId in failing) throw DocumentException.NotFound(pageId)
            if (conflicts.remove(pageId)) throw DocumentException.Conflict(pageId)
            pending.removeAll { it.id == pageId }
            saved[pageId] = lines
            return PageText(pageId, "", lines)
        }

        override suspend fun getPageText(pageId: String): PageText? = null
        override suspend fun searchText(query: String): List<TextMatch> = emptyList()
    }

    @Test
    fun `recognizes every pending page in batches`() = runTest {
        val store = FakeStore((1..7).map { page("p$it") })
        val runner = TextRecognitionRunner(store, { listOf(line) }, batchSize = 3)

        assertEquals(7, runner.run())
        assertEquals((1..7).map { "p$it" }.toSet(), store.saved.keys)
    }

    @Test
    fun `unreadable images are stored without text`() = runTest {
        val store = FakeStore(listOf(page("bad"), page("ok")))
        val runner = TextRecognitionRunner(store, { image ->
            if (image.name == "bad.jpg") throw DocumentException.Unsupported("decode") else listOf(line)
        })

        assertEquals(2, runner.run())
        assertEquals(emptyList<RecognizedLine>(), store.saved["bad"])
    }

    @Test
    fun `a page that can't be saved is tried once per run`() = runTest {
        val store = FakeStore(listOf(page("gone"), page("ok")), failing = setOf("gone"))
        var recognitions = 0
        val runner = TextRecognitionRunner(store, { recognitions++; listOf(line) }, batchSize = 1)

        assertEquals(1, runner.run())
        assertEquals(2, recognitions)
        assertEquals(setOf("ok"), store.saved.keys)
    }

    @Test
    fun `a conflicting page waits for the next run`() = runTest {
        val store = FakeStore(listOf(page("p1")), conflicts = mutableSetOf("p1"))
        val runner = TextRecognitionRunner(store, { listOf(line) })

        assertEquals(0, runner.run())
        assertEquals(1, runner.run())
    }

    @Test
    fun `stops when asked`() = runTest {
        val store = FakeStore((1..5).map { page("p$it") })
        var calls = 0
        val runner = TextRecognitionRunner(store, { calls++; listOf(line) })

        runner.run(shouldStop = { calls >= 2 })

        assertEquals(2, store.saved.size)
    }
}
