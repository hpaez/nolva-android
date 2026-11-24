package app.nolva.ui.trash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nolva.R
import app.nolva.data.Document
import app.nolva.ui.theme.NolvaTheme
import java.time.Instant

@Composable
fun TrashRoute(viewModel: TrashViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.refresh() }
    TrashScreen(
        uiState = uiState,
        onBack = onBack,
        onRestore = viewModel::restore,
        onDeleteForever = viewModel::deleteForever,
        onEmptyTrash = viewModel::emptyTrash,
        onMessageShown = viewModel::messageShown,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
    uiState: TrashUiState,
    onBack: () -> Unit,
    onRestore: (documentId: String) -> Unit,
    onDeleteForever: (documentId: String) -> Unit,
    onEmptyTrash: () -> Unit,
    onMessageShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var deletingId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmEmpty by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val messageText = when {
        uiState.restoredTitle != null -> stringResource(R.string.document_restored, uiState.restoredTitle)
        uiState.message != null -> stringResource(uiState.message.textRes())
        else -> null
    }
    LaunchedEffect(messageText) {
        if (messageText != null) {
            snackbarHostState.showSnackbar(messageText)
            onMessageShown()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.trash)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    TextButton(onClick = { confirmEmpty = true }, enabled = uiState.documents.isNotEmpty()) {
                        Text(stringResource(R.string.empty_trash))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        when {
            uiState.isLoading -> Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            uiState.documents.isEmpty() -> Text(
                text = stringResource(R.string.trash_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = NolvaTheme.colors.inkMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(innerPadding)
                    .padding(32.dp),
            )

            else -> {
                val now = remember(uiState.documents) { Instant.now() }
                LazyColumn(
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = innerPadding.calculateTopPadding() + 8.dp,
                        bottom = innerPadding.calculateBottomPadding() + 16.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Text(
                            text = stringResource(R.string.trash_explanation),
                            style = MaterialTheme.typography.bodySmall,
                            color = NolvaTheme.colors.inkMuted,
                        )
                    }
                    items(uiState.documents, key = { it.id }) { document ->
                        TrashedDocument(
                            document = document,
                            daysLeft = daysLeft(document, now),
                            onRestore = { onRestore(document.id) },
                            onDelete = { deletingId = document.id },
                        )
                    }
                }
            }
        }
    }

    uiState.documents.find { it.id == deletingId }?.let { document ->
        ConfirmDialog(
            title = stringResource(R.string.delete_document_title),
            body = stringResource(R.string.delete_document_body, document.title),
            confirmLabel = stringResource(R.string.delete),
            onConfirm = {
                deletingId = null
                onDeleteForever(document.id)
            },
            onDismiss = { deletingId = null },
        )
    }
    if (confirmEmpty) {
        ConfirmDialog(
            title = stringResource(R.string.empty_trash_title),
            body = pluralStringResource(R.plurals.empty_trash_body, uiState.documents.size, uiState.documents.size),
            confirmLabel = stringResource(R.string.empty_trash),
            onConfirm = {
                confirmEmpty = false
                onEmptyTrash()
            },
            onDismiss = { confirmEmpty = false },
        )
    }
}

@Composable
private fun TrashedDocument(document: Document, daysLeft: Int, onRestore: () -> Unit, onDelete: () -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 4.dp)) {
            Text(
                text = document.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = pluralStringResource(R.plurals.trash_days_left, daysLeft, daysLeft),
                style = MaterialTheme.typography.bodySmall,
                color = NolvaTheme.colors.inkMuted,
            )
            Row(modifier = Modifier.align(Alignment.End)) {
                TextButton(onClick = onDelete) {
                    Text(stringResource(R.string.delete_forever), color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onRestore) {
                    Text(stringResource(R.string.restore))
                }
            }
        }
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

private fun TrashMessage.textRes(): Int = when (this) {
    TrashMessage.LoadFailed -> R.string.error_load_trash
    TrashMessage.RestoreFailed -> R.string.error_restore_document
    TrashMessage.DeleteFailed -> R.string.error_delete_document
    TrashMessage.EmptyFailed -> R.string.error_empty_trash
}
