package app.nolva.di

import android.content.Context
import app.nolva.core.CoreConfig
import app.nolva.core.NolvaCore
import app.nolva.data.ContentPageImporter
import app.nolva.data.CoreDocumentRepository
import app.nolva.data.CoreSheetDetector
import app.nolva.data.DocumentEditor
import app.nolva.data.DocumentRepository
import app.nolva.data.PageCropper
import app.nolva.data.PageImporter
import app.nolva.data.PdfExporter
import app.nolva.data.SheetDetector
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manual dependency container, one per process. Kept deliberately simple;
 * swap for Hilt/Koin only if the graph grows enough to justify it.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    private val coreRepository: CoreDocumentRepository by lazy {
        CoreDocumentRepository(ioDispatcher = Dispatchers.IO) {
            NolvaCore(CoreConfig(dataDir = File(appContext.filesDir, "nolva").absolutePath))
        }
    }

    val documentRepository: DocumentRepository get() = coreRepository

    val documentEditor: DocumentEditor get() = coreRepository

    val pageCropper: PageCropper get() = coreRepository

    val pdfExporter: PdfExporter get() = coreRepository

    /** Shareable exports; exposed to other apps through the FileProvider. */
    val exportDir: File get() = File(appContext.cacheDir, "exports")

    val pageImporter: PageImporter by lazy {
        ContentPageImporter(
            contentResolver = appContext.contentResolver,
            cacheDir = appContext.cacheDir,
            repository = documentRepository,
            ioDispatcher = Dispatchers.IO,
        )
    }

    /** Live sheet detection for the camera guide; stateless, so shared. */
    val sheetDetector: SheetDetector = CoreSheetDetector()

    /** Scratch space for camera captures before the core imports them. */
    val captureDir: File get() = File(appContext.cacheDir, "captures")

    /** Version of the native core; also a cheap check that it loads. */
    suspend fun coreVersion(): String = withContext(Dispatchers.IO) {
        app.nolva.core.coreVersion()
    }
}
