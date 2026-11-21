package app.nolva.di

import android.content.Context
import app.nolva.core.CoreConfig
import app.nolva.core.NolvaCore
import app.nolva.data.ContentPageImporter
import app.nolva.data.CoreDocumentRepository
import app.nolva.data.DocumentRepository
import app.nolva.data.PageImporter
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manual dependency container, one per process. Kept deliberately simple;
 * swap for Hilt/Koin only if the graph grows enough to justify it.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val documentRepository: DocumentRepository by lazy {
        CoreDocumentRepository(ioDispatcher = Dispatchers.IO) {
            NolvaCore(CoreConfig(dataDir = File(appContext.filesDir, "nolva").absolutePath))
        }
    }

    val pageImporter: PageImporter by lazy {
        ContentPageImporter(
            contentResolver = appContext.contentResolver,
            cacheDir = appContext.cacheDir,
            repository = documentRepository,
            ioDispatcher = Dispatchers.IO,
        )
    }

    /** Scratch space for camera captures before the core imports them. */
    val captureDir: File get() = File(appContext.cacheDir, "captures")

    /** Version of the native core; also a cheap check that it loads. */
    suspend fun coreVersion(): String = withContext(Dispatchers.IO) {
        app.nolva.core.coreVersion()
    }
}
