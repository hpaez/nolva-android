package app.nolva.data

import java.io.File
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StorageMaintenanceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val now = 10 * StorageMaintenance.SCRATCH_MAX_AGE_MS

    private fun file(dir: File, name: String, ageMs: Long, bytes: Int = 5): File =
        File(dir.apply { mkdirs() }, name).apply {
            writeBytes(ByteArray(bytes))
            setLastModified(now - ageMs)
        }

    @Test
    fun `removes orphan pages and old scratch files, keeping recent ones`() = runTest(dispatcher) {
        val captures = tempFolder.root.resolve("captures")
        val exports = tempFolder.root.resolve("exports")
        val oldCapture = file(captures, "capture-1.jpg", ageMs = 2 * StorageMaintenance.SCRATCH_MAX_AGE_MS, bytes = 7)
        val freshCapture = file(captures, "capture-2.jpg", ageMs = 60_000)
        val oldExport = file(exports, "Factura.pdf", ageMs = StorageMaintenance.SCRATCH_MAX_AGE_MS + 1, bytes = 3)
        File(exports, "nested").mkdirs()

        val maintenance = StorageMaintenance(
            cleaner = { CleanupResult(2, 100) },
            scratchDirs = listOf(captures, exports, tempFolder.root.resolve("missing")),
            ioDispatcher = dispatcher,
            now = { now },
        )

        assertEquals(CleanupResult(4, 110), maintenance.run())
        assertFalse(oldCapture.exists())
        assertFalse(oldExport.exists())
        assertTrue(freshCapture.exists())
        assertTrue(File(exports, "nested").isDirectory)
    }

    @Test
    fun `purges expired trash, and goes on if that fails`() = runTest(dispatcher) {
        var purges = 0
        val maintenance = StorageMaintenance(
            cleaner = { CleanupResult(1, 1) },
            scratchDirs = emptyList(),
            ioDispatcher = dispatcher,
            now = { now },
            purgeExpiredTrash = {
                purges++
                throw DocumentException.Storage("disk")
            },
        )

        assertEquals(CleanupResult(1, 1), maintenance.run())
        assertEquals(1, purges)
    }

    @Test
    fun `a failing core cleanup still clears scratch files`() = runTest(dispatcher) {
        val imports = tempFolder.root.resolve("imports")
        val old = file(imports, "page-1.jpg", ageMs = 2 * StorageMaintenance.SCRATCH_MAX_AGE_MS)

        val maintenance = StorageMaintenance(
            cleaner = { throw DocumentException.Storage("disk") },
            scratchDirs = listOf(imports),
            ioDispatcher = dispatcher,
            now = { now },
        )

        assertEquals(CleanupResult(1, 5), maintenance.run())
        assertFalse(old.exists())
    }
}
