package app.nolva.ui.documents

import android.content.res.Configuration
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nolva.R
import app.nolva.data.Document
import app.nolva.data.DocumentSort
import app.nolva.data.Folder
import app.nolva.data.ListLayout
import app.nolva.data.MAX_TITLE_LENGTH
import app.nolva.data.PdfImportProgress
import app.nolva.data.TextMatch
import app.nolva.ui.theme.NolvaTheme
import app.nolva.ui.theme.WordmarkStyle
import coil3.compose.AsyncImage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun DocumentListRoute(
    viewModel: DocumentListViewModel,
    onOpenDocument: (Document) -> Unit,
    onOpenTrash: () -> Unit,
    onOpenFolder: (Folder) -> Unit,
    modifier: Modifier = Modifier,
    /** Name of the folder shown; `null` at the top level. */
    folderName: String? = null,
    /** Back navigation; `null` at the top level, which has none. */
    onBack: (() -> Unit)? = null,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // Reload whenever this screen is shown again (e.g. back from a document
    // whose page count changed).
    LaunchedEffect(viewModel) { viewModel.refresh() }
    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.importPdf(it.toString()) }
    }
    LaunchedEffect(uiState.openDocument) {
        val document = uiState.openDocument ?: return@LaunchedEffect
        viewModel.documentOpened()
        onOpenDocument(document)
    }
    DocumentListScreen(
        uiState = uiState,
        onCreateDocument = viewModel::createDocument,
        onOpenDocument = onOpenDocument,
        onMessageShown = viewModel::messageShown,
        onRenameDocument = viewModel::renameDocument,
        onDeleteDocument = viewModel::deleteDocument,
        onSearch = viewModel::search,
        onImportPdf = { pickPdf.launch(arrayOf("application/pdf")) },
        onUndoTrash = viewModel::undoTrash,
        onTrashNoticeShown = viewModel::trashNoticeShown,
        onDuplicateDocument = viewModel::duplicateDocument,
        onOpenTrash = onOpenTrash,
        folderName = folderName,
        onBack = onBack,
        onOpenFolder = onOpenFolder,
        onCreateFolder = viewModel::createFolder,
        onRenameFolder = viewModel::renameFolder,
        onDeleteFolder = viewModel::deleteFolder,
        onMoveDocument = viewModel::moveDocument,
        onToggleSelection = viewModel::toggleSelection,
        onSelectAll = viewModel::selectAll,
        onClearSelection = viewModel::clearSelection,
        onMoveSelected = viewModel::moveSelected,
        onDeleteSelected = viewModel::deleteSelected,
        onSort = viewModel::setSort,
        onLayout = viewModel::setLayout,
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
    onRenameDocument: (documentId: String, title: String) -> Unit = { _, _ -> },
    onDeleteDocument: (documentId: String) -> Unit = {},
    onSearch: (query: String) -> Unit = {},
    onImportPdf: () -> Unit = {},
    onUndoTrash: () -> Unit = {},
    onTrashNoticeShown: () -> Unit = {},
    onDuplicateDocument: (documentId: String, title: String) -> Unit = { _, _ -> },
    onOpenTrash: () -> Unit = {},
    folderName: String? = null,
    onBack: (() -> Unit)? = null,
    onOpenFolder: (Folder) -> Unit = {},
    onCreateFolder: (name: String, thenMove: List<String>) -> Unit = { _, _ -> },
    onRenameFolder: (folderId: String, name: String) -> Unit = { _, _ -> },
    onDeleteFolder: (folderId: String) -> Unit = {},
    onMoveDocument: (documentId: String, folderId: String?) -> Unit = { _, _ -> },
    onToggleSelection: (documentId: String) -> Unit = {},
    onSelectAll: () -> Unit = {},
    onClearSelection: () -> Unit = {},
    onMoveSelected: (folderId: String?) -> Unit = {},
    onDeleteSelected: () -> Unit = {},
    onSort: (DocumentSort) -> Unit = {},
    onLayout: (ListLayout) -> Unit = {},
) {
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    // Ids rather than documents so the dialogs survive configuration changes.
    var renamingId by rememberSaveable { mutableStateOf<String?>(null) }
    var movingId by rememberSaveable { mutableStateOf<String?>(null) }
    var showNewFolder by rememberSaveable { mutableStateOf(false) }
    /** Documents to move into the folder being created, if it was started from a move dialog. */
    var newFolderFor by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var movingSelection by rememberSaveable { mutableStateOf(false) }
    var showSort by rememberSaveable { mutableStateOf(false) }

    // While selecting, Back ends the selection instead of leaving the screen.
    BackHandler(enabled = uiState.isSelecting, onBack = onClearSelection)
    var renamingFolderId by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingFolderId by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Deleting is undoable, so it needs no confirmation: the notice offers "Undo".
    val trashed = uiState.trashedDocuments
    val trashedText = when (trashed.size) {
        0 -> null
        1 -> stringResource(R.string.moved_to_trash, trashed.single().title)
        else -> pluralStringResource(R.plurals.moved_to_trash_many, trashed.size, trashed.size)
    }
    val undoLabel = stringResource(R.string.undo)
    LaunchedEffect(trashed) {
        if (trashedText != null) {
            val result = snackbarHostState.showSnackbar(
                message = trashedText,
                actionLabel = undoLabel,
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) onUndoTrash() else onTrashNoticeShown()
        }
    }
    val resources = LocalResources.current
    val copyTitle = { title: String -> resources.getString(R.string.copy_title, title).take(MAX_TITLE_LENGTH) }

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
            Column {
                if (uiState.isSelecting) {
                    SelectionBar(
                        count = uiState.selectedIds.size,
                        onClose = onClearSelection,
                        onMove = { movingSelection = true },
                        onDelete = onDeleteSelected,
                        onSelectAll = onSelectAll,
                    )
                } else TopAppBar(
                    title = {
                        if (folderName == null) {
                            Text(
                                text = stringResource(R.string.wordmark),
                                style = WordmarkStyle.copy(fontSize = MaterialTheme.typography.headlineSmall.fontSize),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            Text(folderName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    navigationIcon = {
                        if (onBack != null) {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                            }
                        }
                    },
                    actions = {
                        TextButton(onClick = onImportPdf, enabled = uiState.pdfImport == null) {
                            Text(stringResource(R.string.import_pdf))
                        }
                        ListMenu(
                            showNewFolder = uiState.folderId == null,
                            layout = uiState.settings.layout,
                            onNewFolder = {
                                newFolderFor = emptyList()
                                showNewFolder = true
                            },
                            onSort = { showSort = true },
                            onToggleLayout = {
                                onLayout(if (uiState.settings.layout == ListLayout.List) ListLayout.Grid else ListLayout.List)
                            },
                            onOpenTrash = onOpenTrash,
                        )
                    },
                )
                uiState.pdfImport?.let { progress ->
                    PdfImportBar(progress)
                }
            }
        },
        floatingActionButton = {
            if (!uiState.isSelecting) ExtendedFloatingActionButton(
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

            uiState.isEmpty && uiState.folderId == null -> EmptyState(Modifier.padding(innerPadding))

            uiState.isEmpty -> Text(
                text = stringResource(R.string.folder_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = NolvaTheme.colors.inkMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(innerPadding)
                    .padding(32.dp),
            )

            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = innerPadding.calculateTopPadding()),
            ) {
                SearchField(
                    query = uiState.query,
                    onQueryChange = onSearch,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                )
                if (uiState.visibleDocuments.isEmpty() && uiState.visibleFolders.isEmpty()) {
                    NoResults(uiState.query)
                } else {
                    DocumentList(
                        layout = uiState.settings.layout,
                        sort = uiState.settings.sort,
                        folders = uiState.visibleFolders,
                        documents = uiState.visibleDocuments,
                        // Only while searching; stale matches never show on the full list.
                        matches = if (uiState.query.isBlank()) emptyMap() else uiState.contentMatches,
                        selectedIds = uiState.selectedIds,
                        contentPadding = PaddingValues(bottom = innerPadding.calculateBottomPadding()),
                        actions = DocumentActions(
                            onOpenFolder = onOpenFolder,
                            onRenameFolder = { renamingFolderId = it.id },
                            onDeleteFolder = { deletingFolderId = it.id },
                            onOpen = onOpenDocument,
                            onToggleSelection = { onToggleSelection(it.id) },
                            onRename = { renamingId = it.id },
                            onMove = { movingId = it.id },
                            onDuplicate = { onDuplicateDocument(it.id, copyTitle(it.title)) },
                            onDelete = { onDeleteDocument(it.id) },
                        ),
                    )
                }
            }
        }
    }

    if (showCreateDialog) {
        TitleDialog(
            heading = stringResource(R.string.new_document),
            initialTitle = stringResource(R.string.default_document_title, formatDate(Instant.now())),
            confirmLabel = stringResource(R.string.create),
            onConfirm = { title ->
                showCreateDialog = false
                onCreateDocument(title)
            },
            onDismiss = { showCreateDialog = false },
        )
    }

    uiState.documents.find { it.id == movingId }?.let { document ->
        MoveDialog(
            title = stringResource(R.string.move_to_folder),
            current = FolderChoice.of(document),
            folders = uiState.folders,
            onMove = { folderId ->
                movingId = null
                if (folderId != document.folderId) onMoveDocument(document.id, folderId)
            },
            onNewFolder = {
                movingId = null
                newFolderFor = listOf(document.id)
                showNewFolder = true
            },
            onDismiss = { movingId = null },
        )
    }

    if (movingSelection && uiState.isSelecting) {
        val count = uiState.selectedIds.size
        MoveDialog(
            title = pluralStringResource(R.plurals.move_selected, count, count),
            current = null,
            folders = uiState.folders,
            onMove = { folderId ->
                movingSelection = false
                onMoveSelected(folderId)
            },
            onNewFolder = {
                movingSelection = false
                newFolderFor = uiState.selectedIds.toList()
                showNewFolder = true
            },
            onDismiss = { movingSelection = false },
        )
    }

    if (showSort) {
        SortDialog(
            current = uiState.settings.sort,
            onPick = { sort ->
                showSort = false
                onSort(sort)
            },
            onDismiss = { showSort = false },
        )
    }

    if (showNewFolder) {
        TitleDialog(
            heading = stringResource(R.string.new_folder),
            initialTitle = "",
            confirmLabel = stringResource(R.string.create),
            onConfirm = { name ->
                showNewFolder = false
                onCreateFolder(name, newFolderFor)
                newFolderFor = emptyList()
            },
            onDismiss = {
                showNewFolder = false
                newFolderFor = emptyList()
            },
        )
    }

    uiState.folders.find { it.id == renamingFolderId }?.let { folder ->
        TitleDialog(
            heading = stringResource(R.string.rename_folder),
            initialTitle = folder.name,
            confirmLabel = stringResource(R.string.save),
            onConfirm = { name ->
                renamingFolderId = null
                if (name != folder.name) onRenameFolder(folder.id, name)
            },
            onDismiss = { renamingFolderId = null },
        )
    }

    uiState.folders.find { it.id == deletingFolderId }?.let { folder ->
        AlertDialog(
            onDismissRequest = { deletingFolderId = null },
            title = { Text(stringResource(R.string.delete_folder_title, folder.name)) },
            text = { Text(stringResource(R.string.delete_folder_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deletingFolderId = null
                        onDeleteFolder(folder.id)
                    },
                ) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingFolderId = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    uiState.documents.find { it.id == renamingId }?.let { document ->
        TitleDialog(
            heading = stringResource(R.string.rename_document),
            initialTitle = document.title,
            confirmLabel = stringResource(R.string.save),
            onConfirm = { title ->
                renamingId = null
                if (title != document.title) onRenameDocument(document.id, title)
            },
            onDismiss = { renamingId = null },
        )
    }
}

/** Folders, then documents, as a list or a grid. */
@Composable
private fun DocumentList(
    layout: ListLayout,
    sort: DocumentSort,
    folders: List<Folder>,
    documents: List<Document>,
    matches: Map<String, TextMatch>,
    selectedIds: Set<String>,
    contentPadding: PaddingValues,
    actions: DocumentActions,
) {
    val selecting = selectedIds.isNotEmpty()
    val padding = PaddingValues(
        start = 16.dp,
        end = 16.dp,
        top = contentPadding.calculateTopPadding() + 8.dp,
        // Leave room so the FAB never covers the last item.
        bottom = contentPadding.calculateBottomPadding() + 88.dp,
    )
    val spacing = Arrangement.spacedBy(12.dp)
    when (layout) {
        ListLayout.List -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding,
            verticalArrangement = spacing,
        ) {
            items(folders, key = { "folder-${it.id}" }) { folder ->
                FolderRow(folder, enabled = !selecting, actions = actions)
            }
            items(documents, key = { it.id }) { document ->
                DocumentRow(document, matches[document.id], sort, document.id in selectedIds, selecting, actions)
            }
        }

        ListLayout.Grid -> LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 150.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding,
            verticalArrangement = spacing,
            horizontalArrangement = spacing,
        ) {
            items(folders, key = { "folder-${it.id}" }) { folder ->
                FolderRow(folder, enabled = !selecting, actions = actions)
            }
            items(documents, key = { it.id }) { document ->
                DocumentTile(document, matches[document.id], sort, document.id in selectedIds, selecting, actions)
            }
        }
    }
}

/** What can be done to the folders and documents on screen. */
private class DocumentActions(
    val onOpenFolder: (Folder) -> Unit,
    val onRenameFolder: (Folder) -> Unit,
    val onDeleteFolder: (Folder) -> Unit,
    val onOpen: (Document) -> Unit,
    val onToggleSelection: (Document) -> Unit,
    val onRename: (Document) -> Unit,
    val onMove: (Document) -> Unit,
    val onDuplicate: (Document) -> Unit,
    val onDelete: (Document) -> Unit,
)

/** A folder, tinted so it stands apart from the documents. Disabled while selecting documents. */
@Composable
private fun FolderRow(folder: Folder, enabled: Boolean, actions: DocumentActions) {
    Card(
        onClick = { actions.onOpenFolder(folder) },
        enabled = enabled,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp, top = 14.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = folder.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = pluralStringResource(R.plurals.folder_document_count, folder.documentCount, folder.documentCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            if (enabled) {
                Box {
                    var expanded by remember { mutableStateOf(false) }
                    IconButton(onClick = { expanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_options))
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.rename)) },
                            onClick = {
                                expanded = false
                                actions.onRenameFolder(folder)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                            onClick = {
                                expanded = false
                                actions.onDeleteFolder(folder)
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Top bar while documents are selected: count, close, and the bulk actions. */
@Composable
private fun SelectionBar(
    count: Int,
    onClose: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onSelectAll: () -> Unit,
) {
    TopAppBar(
        title = { Text(pluralStringResource(R.plurals.selected_count, count, count)) },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.clear_selection))
            }
        },
        actions = {
            TextButton(onClick = onSelectAll) { Text(stringResource(R.string.select_all)) }
            TextButton(onClick = onMove) { Text(stringResource(R.string.move)) }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    )
}

/** Overflow menu of the top bar. */
@Composable
private fun ListMenu(
    showNewFolder: Boolean,
    layout: ListLayout,
    onNewFolder: () -> Unit,
    onSort: () -> Unit,
    onToggleLayout: () -> Unit,
    onOpenTrash: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    fun pick(action: () -> Unit) {
        expanded = false
        action()
    }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_options))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (showNewFolder) {
                DropdownMenuItem(text = { Text(stringResource(R.string.new_folder)) }, onClick = { pick(onNewFolder) })
            }
            DropdownMenuItem(text = { Text(stringResource(R.string.sort_by)) }, onClick = { pick(onSort) })
            DropdownMenuItem(
                text = {
                    Text(stringResource(if (layout == ListLayout.List) R.string.view_as_grid else R.string.view_as_list))
                },
                onClick = { pick(onToggleLayout) },
            )
            DropdownMenuItem(text = { Text(stringResource(R.string.trash)) }, onClick = { pick(onOpenTrash) })
        }
    }
}

/** Picks the order of the documents. */
@Composable
private fun SortDialog(current: DocumentSort, onPick: (DocumentSort) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sort_by)) },
        text = {
            Column {
                DocumentSort.entries.forEach { sort ->
                    MoveOption(stringResource(sort.labelRes()), selected = sort == current) { onPick(sort) }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

private fun DocumentSort.labelRes(): Int = when (this) {
    DocumentSort.Updated -> R.string.sort_updated
    DocumentSort.Created -> R.string.sort_created
    DocumentSort.Name -> R.string.sort_name
    DocumentSort.Size -> R.string.sort_size
    DocumentSort.Pages -> R.string.sort_pages
}

/**
 * Where to move documents: the top level, a folder, or a new folder.
 * [current] is marked when moving one document; `null` marks nothing.
 */
@Composable
private fun MoveDialog(
    title: String,
    current: FolderChoice?,
    folders: List<Folder>,
    onMove: (folderId: String?) -> Unit,
    onNewFolder: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                MoveOption(stringResource(R.string.top_level), selected = current == FolderChoice.TopLevel) { onMove(null) }
                folders.forEach { folder ->
                    MoveOption(folder.name, selected = current == FolderChoice.In(folder.id)) { onMove(folder.id) }
                }
                TextButton(onClick = onNewFolder) { Text(stringResource(R.string.new_folder_ellipsis)) }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** Where a document currently is, for marking it in [MoveDialog]. */
private sealed interface FolderChoice {
    data object TopLevel : FolderChoice

    data class In(val folderId: String) : FolderChoice

    companion object {
        fun of(document: Document): FolderChoice = document.folderId?.let(::In) ?: TopLevel
    }
}

@Composable
private fun MoveOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A document in the list layout. */
@Composable
private fun DocumentRow(
    document: Document,
    match: TextMatch?,
    sort: DocumentSort,
    selected: Boolean,
    selecting: Boolean,
    actions: DocumentActions,
) {
    val shape = CardDefaults.outlinedShape
    OutlinedCard(
        shape = shape,
        border = selectionBorder(selected),
        modifier = Modifier
            .fillMaxWidth()
            .selectableDocument(document, selected, selecting, actions, shape, documentClickLabels()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Cover(
                document,
                selected,
                Modifier
                    .padding(start = 12.dp, top = 12.dp, bottom = 12.dp)
                    .size(width = 48.dp, height = 64.dp),
            )
            DocumentSummary(document, match, sort, Modifier.weight(1f).padding(start = 12.dp, top = 16.dp, bottom = 16.dp))
            if (!selecting) DocumentMenu(document, actions)
        }
    }
}

/** A document in the grid layout: a large cover with the title under it. */
@Composable
private fun DocumentTile(
    document: Document,
    match: TextMatch?,
    sort: DocumentSort,
    selected: Boolean,
    selecting: Boolean,
    actions: DocumentActions,
) {
    val shape = CardDefaults.outlinedShape
    OutlinedCard(
        shape = shape,
        border = selectionBorder(selected),
        modifier = Modifier
            .fillMaxWidth()
            .selectableDocument(document, selected, selecting, actions, shape, documentClickLabels()),
    ) {
        Cover(
            document,
            selected,
            Modifier
                .padding(8.dp)
                .fillMaxWidth()
                .aspectRatio(3f / 4f),
        )
        Row(verticalAlignment = Alignment.Top) {
            DocumentSummary(document, match, sort, Modifier.weight(1f).padding(start = 12.dp, bottom = 12.dp))
            if (!selecting) DocumentMenu(document, actions)
        }
    }
}

/** What tapping and long-pressing a document do, for TalkBack. */
private data class DocumentClickLabels(val open: String, val select: String)

@Composable
private fun documentClickLabels() =
    DocumentClickLabels(open = stringResource(R.string.open_document), select = stringResource(R.string.select))

/**
 * Tap opens the document, or toggles it while selecting; long press starts
 * (or extends) a selection. TalkBack announces both actions by name.
 */
private fun Modifier.selectableDocument(
    document: Document,
    selected: Boolean,
    selecting: Boolean,
    actions: DocumentActions,
    shape: Shape,
    labels: DocumentClickLabels,
): Modifier = semantics { this.selected = selected }
    .clip(shape)
    .combinedClickable(
        onClickLabel = if (selecting) labels.select else labels.open,
        onLongClickLabel = labels.select,
        onClick = { if (selecting) actions.onToggleSelection(document) else actions.onOpen(document) },
        onLongClick = { actions.onToggleSelection(document) },
    )

@Composable
private fun selectionBorder(selected: Boolean): BorderStroke =
    if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else CardDefaults.outlinedCardBorder()

/** First page of the document, or an empty sheet while it has no pages; checked when selected. */
@Composable
private fun Cover(document: Document, selected: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        document.coverFile?.let { cover ->
            // Coil downsamples to the shown size; full pages never load here.
            AsyncImage(
                model = cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (selected) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
            ) {
                Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}

/** Import progress under the top bar: indeterminate until the page count is known. */
@Composable
private fun PdfImportBar(progress: PdfImportProgress) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            text = if (progress.pageCount == 0) {
                stringResource(R.string.importing_pdf)
            } else {
                stringResource(R.string.importing_pdf_pages, progress.pagesDone, progress.pageCount)
            },
            style = MaterialTheme.typography.labelMedium,
            color = NolvaTheme.colors.inkMuted,
        )
        if (progress.pageCount == 0) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(
                progress = { progress.pagesDone.toFloat() / progress.pageCount },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val keyboard = LocalSoftwareKeyboardController.current
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier,
        placeholder = { Text(stringResource(R.string.search_documents)) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.clear_search))
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
    )
}

@Composable
private fun NoResults(query: String) {
    Text(
        text = stringResource(R.string.no_search_results, query.trim()),
        style = MaterialTheme.typography.bodyMedium,
        color = NolvaTheme.colors.inkMuted,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
    )
}

@Composable
private fun DocumentMenu(document: Document, actions: DocumentActions) {
    var expanded by remember { mutableStateOf(false) }
    fun pick(action: (Document) -> Unit) {
        expanded = false
        action(document)
    }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_options))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, onClick = { pick(actions.onRename) })
            DropdownMenuItem(text = { Text(stringResource(R.string.move_to_folder)) }, onClick = { pick(actions.onMove) })
            DropdownMenuItem(text = { Text(stringResource(R.string.duplicate)) }, onClick = { pick(actions.onDuplicate) })
            DropdownMenuItem(text = { Text(stringResource(R.string.select)) }, onClick = { pick(actions.onToggleSelection) })
            DropdownMenuItem(
                text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                onClick = { pick(actions.onDelete) },
            )
        }
    }
}

@Composable
private fun DocumentSummary(document: Document, match: TextMatch?, sort: DocumentSort, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = document.title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        // The detail matches the order, so the list explains itself.
        val detail = when (sort) {
            DocumentSort.Size -> Formatter.formatShortFileSize(context, document.sizeBytes)
            DocumentSort.Created -> formatDate(document.createdAt)
            else -> formatDate(document.updatedAt)
        }
        Text(
            text = stringResource(
                R.string.document_subtitle,
                pluralStringResource(R.plurals.page_count, document.pageCount, document.pageCount),
                detail,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = NolvaTheme.colors.inkMuted,
        )
        if (match != null) {
            // Where the search found the text, so it's clear why this shows up.
            Text(
                text = stringResource(R.string.text_match, match.pagePosition + 1, match.snippet),
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
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

/** Asks for a document title; used both to create and to rename. */
@Composable
private fun TitleDialog(
    heading: String,
    initialTitle: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by rememberSaveable { mutableStateOf(initialTitle) }
    val isValid = title.isNotBlank() && title.trim().length <= MAX_TITLE_LENGTH

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(heading) },
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
                Text(confirmLabel)
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
    DocumentListMessage.RenameFailed -> R.string.error_rename_document
    DocumentListMessage.DeleteFailed -> R.string.error_delete_document
    DocumentListMessage.PdfUnreadable -> R.string.error_pdf_unreadable
    DocumentListMessage.PdfTooLarge -> R.string.error_pdf_too_large
    DocumentListMessage.PdfImportFailed -> R.string.error_import_pdf
    DocumentListMessage.RestoreFailed -> R.string.error_restore_document
    DocumentListMessage.DuplicateFailed -> R.string.error_duplicate_document
    DocumentListMessage.FolderFailed -> R.string.error_folder
    DocumentListMessage.MoveFailed -> R.string.error_move_document
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
