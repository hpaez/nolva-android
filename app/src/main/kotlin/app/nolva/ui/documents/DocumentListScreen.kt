package app.nolva.ui.documents

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nolva.R
import app.nolva.data.Document
import app.nolva.data.MAX_TITLE_LENGTH
import app.nolva.ui.theme.NolvaTheme
import app.nolva.ui.theme.WordmarkStyle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun DocumentListRoute(
    viewModel: DocumentListViewModel,
    onOpenDocument: (Document) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // Reload whenever this screen is shown again (e.g. back from a document
    // whose page count changed).
    LaunchedEffect(viewModel) { viewModel.refresh() }
    DocumentListScreen(
        uiState = uiState,
        onCreateDocument = viewModel::createDocument,
        onOpenDocument = onOpenDocument,
        onMessageShown = viewModel::messageShown,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentListScreen(
    uiState: DocumentListUiState,
    onCreateDocument: (String) -> Unit,
    onOpenDocument: (Document) -> Unit,
    onMessageShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    val messageText = uiState.message?.let { stringResource(it.textRes()) }
    LaunchedEffect(uiState.message) {
        if (messageText != null) {
            snackbarHostState.showSnackbar(messageText)
            onMessageShown()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.wordmark),
                        style = WordmarkStyle.copy(fontSize = MaterialTheme.typography.headlineSmall.fontSize),
                        color = MaterialTheme.colorScheme.primary,
                    )
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showCreateDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Text(stringResource(R.string.new_document))
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        when {
            uiState.isLoading -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            uiState.documents.isEmpty() -> EmptyState(Modifier.padding(innerPadding))

            else -> DocumentList(
                documents = uiState.documents,
                contentPadding = innerPadding,
                onOpenDocument = onOpenDocument,
            )
        }
    }

    if (showCreateDialog) {
        CreateDocumentDialog(
            onConfirm = { title ->
                showCreateDialog = false
                onCreateDocument(title)
            },
            onDismiss = { showCreateDialog = false },
        )
    }
}

@Composable
private fun DocumentList(
    documents: List<Document>,
    contentPadding: PaddingValues,
    onOpenDocument: (Document) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            // Leave room so the FAB never covers the last item.
            bottom = contentPadding.calculateBottomPadding() + 88.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(documents, key = { it.id }) { document ->
            DocumentRow(document, onClick = { onOpenDocument(document) })
        }
    }
}

@Composable
private fun DocumentRow(document: Document, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = document.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(
                    R.string.document_subtitle,
                    pluralStringResource(R.plurals.page_count, document.pageCount, document.pageCount),
                    formatDate(document.updatedAt),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = NolvaTheme.colors.inkMuted,
            )
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.empty_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = NolvaTheme.colors.inkMuted,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun CreateDocumentDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    val defaultTitle = stringResource(R.string.default_document_title, formatDate(Instant.now()))
    var title by rememberSaveable { mutableStateOf(defaultTitle) }
    val isValid = title.isNotBlank() && title.trim().length <= MAX_TITLE_LENGTH

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.new_document)) },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it.take(MAX_TITLE_LENGTH) },
                label = { Text(stringResource(R.string.document_title_label)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(title.trim()) }, enabled = isValid) {
                Text(stringResource(R.string.create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

private val dateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

private fun formatDate(instant: Instant): String =
    dateFormatter.withZone(ZoneId.systemDefault()).format(instant)

private fun DocumentListMessage.textRes(): Int = when (this) {
    DocumentListMessage.LoadFailed -> R.string.error_load_documents
    DocumentListMessage.CreateFailed -> R.string.error_create_document
    DocumentListMessage.InvalidTitle -> R.string.error_invalid_title
}

private val previewDocuments = listOf(
    Document("1", "Lease agreement", Instant.parse("2026-11-18T10:00:00Z"), Instant.parse("2026-11-20T10:00:00Z"), 3),
    Document("2", "Electricity bill", Instant.parse("2026-11-10T10:00:00Z"), Instant.parse("2026-11-10T10:00:00Z"), 1),
)

@Preview(name = "List · light", showBackground = true)
@Preview(name = "List · dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun DocumentListPreview() {
    NolvaTheme {
        DocumentListScreen(
            uiState = DocumentListUiState(isLoading = false, documents = previewDocuments),
            onCreateDocument = {},
            onOpenDocument = {},
            onMessageShown = {},
        )
    }
}

@Preview(name = "Empty", showBackground = true)
@Composable
private fun DocumentListEmptyPreview() {
    NolvaTheme {
        DocumentListScreen(
            uiState = DocumentListUiState(isLoading = false),
            onCreateDocument = {},
            onOpenDocument = {},
            onMessageShown = {},
        )
    }
}
