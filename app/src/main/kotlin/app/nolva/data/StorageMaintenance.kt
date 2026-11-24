package app.nolva.data

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** What a cleanup freed. */
data class CleanupResult(val filesRemoved: Int, val bytesFreed: Long) {
    operator fun plus(other: CleanupResult) =
        CleanupResult(filesRemoved + other.filesRemoved, bytesFreed + other.bytesFreed)

    companion object {
        val NONE = CleanupResult(0, 0)
    }
}

/** Deletes stored page images that no page uses anymore. Main-safe. */
fun interface OrphanFileCleaner {
    suspend fun removeOrphanFiles(): CleanupResult
}

/**
 * Housekeeping to run once per app start, in the background:
 * - documents in the trash for longer than [DocumentTrash.RETENTION];
 * - page images left behind by failed deletions or interrupted imports
 *   (the core keeps anything from the last hour, so it's safe while the
 *   app is in use);
 * - old temp files in [scratchDirs]: camera captures and gallery imports
 *   that a crash left behind, and PDFs or images already shared.
 *
 * Each step is best effort: a failure is logged by the caller and the next
 * start tries again.
 */
class StorageMaintenance(
    private val cleaner: OrphanFileCleaner,
    private val scratchDirs: List<File>,
    private val ioDispatcher: CoroutineDispatcher,
    private val now: () -> Long = System::currentTimeMillis,
    /** Deletes expired trash; @return how many documents. */
    private val purgeExpiredTrash: suspend () -> Int = { 0 },
) {
    suspend fun run(): CleanupResult {
        try {
            purgeExpiredTrash()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Retried next start.
        }
        val pages = try {
            cleaner.removeOrphanFiles()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CleanupResult.NONE
        }
        return pages + withContext(ioDispatcher) { removeOldScratchFiles() }
    }

    private fun removeOldScratchFiles(): CleanupResult {
        val cutoff = now() - SCRATCH_MAX_AGE_MS
        var result = CleanupResult.NONE
        for (dir in scratchDirs) {
            for (file in dir.listFiles().orEmpty()) {
                if (!file.isFile || file.lastModified() >= cutoff) continue
                val size = file.length()
                if (file.delete()) result += CleanupResult(1, size)
            }
        }
        return result
    }

    companion object {
        /**
         * Scratch files older than this are leftovers. A shared file must
         * outlive the share itself, which can take a while (e.g. a slow upload).
         */
        const val SCRATCH_MAX_AGE_MS = 24 * 60 * 60 * 1000L
    }
}
