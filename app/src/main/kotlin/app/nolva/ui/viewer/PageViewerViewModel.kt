package app.nolva.ui.viewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nolva.data.DocumentRepository
import app.nolva.data.Page
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PageViewerUiState(
    val isLoading: Boolean = true,
    val pages: List<Page> = emptyList(),
    val loadFailed: Boolean = false,
)

class PageViewerViewModel(
    private val documentId: String,
    private val repository: DocumentRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PageViewerUiState())
    val uiState: StateFlow<PageViewerUiState> = _uiState.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = try {
                PageViewerUiState(isLoading = false, pages = repository.listPages(documentId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PageViewerUiState(isLoading = false, loadFailed = true)
            }
        }
    }
}
