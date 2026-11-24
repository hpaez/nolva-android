package app.nolva.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import app.nolva.di.AppContainer
import app.nolva.ui.capture.CaptureRoute
import app.nolva.ui.capture.CaptureViewModel
import app.nolva.ui.crop.CropRoute
import app.nolva.ui.crop.CropViewModel
import app.nolva.ui.document.DocumentDetailRoute
import app.nolva.ui.document.DocumentDetailViewModel
import app.nolva.ui.documents.DocumentListRoute
import app.nolva.ui.documents.DocumentListViewModel
import app.nolva.ui.viewer.PageViewerRoute
import app.nolva.ui.viewer.PageViewerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable

/** Back stack destinations. Serializable so the stack survives process death. */
@Serializable
data object DocumentListKey : NavKey

@Serializable
data class DocumentDetailKey(val documentId: String, val title: String) : NavKey

@Serializable
data class CaptureKey(val documentId: String) : NavKey

@Serializable
data class PageViewerKey(val documentId: String, val title: String, val initialPage: Int) : NavKey

@Serializable
data class CropKey(val pageId: String) : NavKey

@Composable
fun NolvaNavigation(container: AppContainer, modifier: Modifier = Modifier) {
    val backStack = rememberNavBackStack(DocumentListKey)

    NavDisplay(
        backStack = backStack,
        modifier = modifier,
        onBack = { backStack.removeLastOrNull() },
        // Scope ViewModels and saved UI state to each back stack entry, so a
        // document's ViewModel is cleared when its screen is popped.
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        entryProvider = entryProvider {
            entry<DocumentListKey> {
                DocumentListRoute(
                    viewModel = viewModel(
                        factory = viewModelFactory {
                            initializer { DocumentListViewModel(container.documentRepository, container.documentEditor, container.pageTextStore) }
                        },
                    ),
                    onOpenDocument = { document ->
                        backStack.add(DocumentDetailKey(document.id, document.title))
                    },
                )
            }
            entry<DocumentDetailKey> { key ->
                DocumentDetailRoute(
                    viewModel = viewModel(
                        factory = viewModelFactory {
                            initializer {
                                DocumentDetailViewModel(
                                    documentId = key.documentId,
                                    repository = container.documentRepository,
                                    editor = container.documentEditor,
                                    importer = container.pageImporter,
                                    exporter = container.pdfExporter,
                                    docxExporter = container.docxExporter,
                                    textRecognition = container.textRecognitionRunner,
                                    exportDir = container.exportDir,
                                    ioDispatcher = Dispatchers.IO,
                                )
                            }
                        },
                    ),
                    title = key.title,
                    onBack = { backStack.removeLastOrNull() },
                    onScan = { backStack.add(CaptureKey(key.documentId)) },
                    onOpenPage = { index -> backStack.add(PageViewerKey(key.documentId, key.title, index)) },
                )
            }
            entry<PageViewerKey> { key ->
                PageViewerRoute(
                    viewModel = viewModel(
                        factory = viewModelFactory {
                            initializer {
                                PageViewerViewModel(
                                    documentId = key.documentId,
                                    title = key.title,
                                    repository = container.documentRepository,
                                    cropper = container.pageCropper,
                                    textStore = container.pageTextStore,
                                    exportDir = container.exportDir,
                                    ioDispatcher = Dispatchers.IO,
                                )
                            }
                        },
                    ),
                    initialPage = key.initialPage,
                    onClose = { backStack.removeLastOrNull() },
                    onAdjustCrop = { pageId -> backStack.add(CropKey(pageId)) },
                )
            }
            entry<CropKey> { key ->
                CropRoute(
                    viewModel = viewModel(
                        factory = viewModelFactory {
                            initializer { CropViewModel(key.pageId, container.pageCropper) }
                        },
                    ),
                    onDone = { backStack.removeLastOrNull() },
                )
            }
            entry<CaptureKey> { key ->
                CaptureRoute(
                    viewModel = viewModel(
                        factory = viewModelFactory {
                            initializer {
                                CaptureViewModel(
                                    documentId = key.documentId,
                                    repository = container.documentRepository,
                                    cropper = container.pageCropper,
                                    editor = container.documentEditor,
                                    detector = container.sheetDetector,
                                    captureDir = container.captureDir,
                                    ioDispatcher = Dispatchers.IO,
                                )
                            }
                        },
                    ),
                    onDone = { backStack.removeLastOrNull() },
                )
            }
        },
    )
}
