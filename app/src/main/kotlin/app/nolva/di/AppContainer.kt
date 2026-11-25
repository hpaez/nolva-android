package app.nolva.di

import android.content.Context
import app.nolva.core.CoreConfig
import app.nolva.core.NolvaCore
import app.nolva.R
import app.nolva.data.ContentPageImporter
import app.nolva.data.ContentPdfImporter
import app.nolva.data.CoreDocumentRepository
import app.nolva.data.CoreSheetDetector
import app.nolva.data.DocumentEditor
import app.nolva.data.DocumentRepository
import app.nolva.data.DocumentTrash
import app.nolva.data.FolderStore
import app.nolva.data.ExportPreferences
import app.nolva.data.ListPreferences
import app.nolva.data.SharedExportPreferences
import app.nolva.data.DocxExporter
import app.nolva.data.PageAnnotator
import app.nolva.data.PageCropper
import app.nolva.data.PageImporter
import app.nolva.data.PdfExporter
import app.nolva.data.PdfImporter
import app.nolva.data.SharedListPreferences
import app.nolva.data.SheetDetector
import app.nolva.data.MlKitTextRecognizer
import app.nolva.data.PageTextStore
import app.nolva.data.StorageMaintenance
import app.nolva.data.TextRecognitionRunner
import app.nolva.work.TextRecognitionWorker
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
        CoreDocumentRepository(
            ioDispatcher = Dispatchers.IO,
            onPageImagesChanged = { TextRecognitionWorker.schedule(appContext) },
        ) {
            NolvaCore(CoreConfig(dataDir = File(appContext.filesDir, "nolva").absolutePath))
        }
    }

    val documentRepository: DocumentRepository get() = coreRepository

    val documentEditor: DocumentEditor get() = coreRepository

    val pageCropper: PageCropper get() = coreRepository

    val pdfExporter: PdfExporter get() = coreRepository

    val docxExporter: DocxExporter get() = coreRepository

    val documentTrash: DocumentTrash get() = coreRepository

    val folderStore: FolderStore get() = coreRepository

    val listPreferences: ListPreferences by lazy { SharedListPreferences(appContext, Dispatchers.IO) }

    val exportPreferences: ExportPreferences by lazy { SharedExportPreferences(appContext, Dispatchers.IO) }

    val pdfImporter: PdfImporter by lazy {
        ContentPdfImporter(
            contentResolver = appContext.contentResolver,
            cacheDir = appContext.cacheDir,
            repository = coreRepository,
            editor = coreRepository,
            ioDispatcher = Dispatchers.IO,
            fallbackTitle = appContext.getString(R.string.imported_pdf_title),
        )
    }

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

    val pageTextStore: PageTextStore get() = coreRepository

    val pageAnnotator: PageAnnotator get() = coreRepository

    val textRecognitionRunner: TextRecognitionRunner by lazy {
        TextRecognitionRunner(store = coreRepository, recognizer = MlKitTextRecognizer(Dispatchers.IO))
    }

    /** Folder where gallery imports are staged; see [ContentPageImporter]. */
    private val importDir: File get() = File(appContext.cacheDir, "imports")

    val storageMaintenance: StorageMaintenance by lazy {
        StorageMaintenance(
            cleaner = coreRepository,
            scratchDirs = listOf(captureDir, importDir, exportDir),
            ioDispatcher = Dispatchers.IO,
            purgeExpiredTrash = coreRepository::purgeExpired,
        )
    }

    /** Version of the native core; also a cheap check that it loads. */
    suspend fun coreVersion(): String = withContext(Dispatchers.IO) {
        app.nolva.core.coreVersion()
    }
}
