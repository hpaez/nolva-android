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
import app.nolva.ui.document.DocumentDetailRoute
import app.nolva.ui.document.DocumentDetailViewModel
import app.nolva.ui.documents.DocumentListRoute
import app.nolva.ui.documents.DocumentListViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable

/** Back stack destinations. Serializable so the stack survives process death. */
@Serializable
data object DocumentListKey : NavKey

@Serializable
data class DocumentDetailKey(val documentId: String, val title: String) : NavKey

@Serializable
data class CaptureKey(val documentId: String) : NavKey

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
                            initializer { DocumentListViewModel(container.documentRepository) }
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
                                    importer = container.pageImporter,
                                )
                            }
                        },
                    ),
                    title = key.title,
                    onBack = { backStack.removeLastOrNull() },
                    onScan = { backStack.add(CaptureKey(key.documentId)) },
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
