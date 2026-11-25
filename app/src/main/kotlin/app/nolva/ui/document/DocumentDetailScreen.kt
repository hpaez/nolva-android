package app.nolva.ui.document

import android.content.res.Configuration
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nolva.R
import app.nolva.data.Page
import app.nolva.data.PaperSize
import app.nolva.ui.share.shareFile
import app.nolva.ui.theme.NolvaTheme
import coil3.compose.AsyncImage
import java.io.File
import java.time.Instant
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState

/** Most images the user can pick in one go from the Photo Picker. */
private const val MAX_PICK = 20

@Composable
fun DocumentDetailRoute(
    viewModel: DocumentDetailViewModel,
    title: String,
    onBack: () -> Unit,
    onScan: () -> Unit,
    onOpenPage: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // Reload whenever this screen is shown again (e.g. back from the camera).
    LaunchedEffect(viewModel) { viewModel.refresh() }
    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK),
    ) { uris -> viewModel.importImages(uris.map { it.toString() }) }

    val context = LocalContext.current
    LaunchedEffect(uiState.pendingShare) {
        val export = uiState.pendingShare ?: return@LaunchedEffect
        shareFile(context, export.file, mimeType = export.mimeType, subject = title)
        viewModel.shareHandled()
    }

    DocumentDetailScreen(
        title = title,
        uiState = uiState,
        onBack = onBack,
        onScan = onScan,
        onOpenPage = onOpenPage,
        onAddFromGallery = {
            pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        onMessageShown = viewModel::messageShown,
        onExportPdf = { paperSize -> viewModel.exportPdf(title, paperSize) },
        onExportProtectedPdf = { paperSize, password -> viewModel.exportPdf(title, paperSize, password) },
        onPageNumbersChange = viewModel::setPageNumbers,
        onExportDocx = { includeImages -> viewModel.exportDocx(title, includeImages) },
        onDeletePage = viewModel::deletePage,
        onMovePage = viewModel::movePage,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentDetailScreen(
    title: String,
    uiState: DocumentDetailUiState,
    onBack: () -> Unit,
    onScan: () -> Unit,
    onAddFromGallery: () -> Unit,
    onMessageShown: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenPage: (Int) -> Unit = {},
    onExportPdf: (PaperSize) -> Unit = {},
    onExportProtectedPdf: (PaperSize, password: String) -> Unit = { _, _ -> },
    onPageNumbersChange: (Boolean) -> Unit = {},
    onExportDocx: (includeImages: Boolean) -> Unit = {},
    onDeletePage: (pageId: String) -> Unit = {},
    onMovePage: (pageId: String, toPosition: Int) -> Unit = { _, _ -> },
) {
    var showExportMenu by remember { mutableStateOf(false) }
    var askingPassword by rememberSaveable { mutableStateOf(false) }
    var deletingPageId by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val messageText = uiState.message?.let {
        if (it == DocumentDetailMessage.DocxMissingText) {
            pluralStringResource(R.plurals.docx_missing_text, uiState.pagesMissingText, uiState.pagesMissingText)
        } else {
            stringResource(it.textRes())
        }
    }
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
                TopAppBar(
                    title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    },
                    actions = {
                        TextButton(onClick = onAddFromGallery) {
                            Text(stringResource(R.string.gallery))
                        }
                        Box {
                            TextButton(
                                onClick = { showExportMenu = true },
                                enabled = uiState.pages.isNotEmpty() && !uiState.isExporting,
                            ) {
                                Text(stringResource(R.string.export))
                            }
                            DropdownMenu(expanded = showExportMenu, onDismissRequest = { showExportMenu = false }) {
                                PaperSize.entries.forEach { size ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.export_pdf_size, stringResource(size.labelRes()))) },
                                        onClick = {
                                            showExportMenu = false
                                            onExportPdf(size)
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.export_pdf_password)) },
                                    onClick = {
                                        showExportMenu = false
                                        askingPassword = true
                                    },
                                )
                                // A setting, not an export: toggling keeps the menu open.
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.pdf_page_numbers)) },
                                    trailingIcon = { Checkbox(checked = uiState.pageNumbers, onCheckedChange = null) },
                                    onClick = { onPageNumbersChange(!uiState.pageNumbers) },
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.export_docx)) },
                                    onClick = {
                                        showExportMenu = false
                                        onExportDocx(false)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.export_docx_with_images)) },
                                    onClick = {
                                        showExportMenu = false
                                        onExportDocx(true)
                                    },
                                )
                            }
                        }
                    },
                )
                if (uiState.isImporting || uiState.isExporting) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onScan,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Text(stringResource(R.string.scan))
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

            uiState.pages.isEmpty() && !uiState.isImporting -> EmptyPages(Modifier.padding(innerPadding))

            else -> PageGrid(
                pages = uiState.pages,
                contentPadding = innerPadding,
                // Imports append pages while running; editing at the same
                // time could leave the grid out of step with storage.
                editable = !uiState.isImporting,
                onOpenPage = onOpenPage,
                onMovePage = onMovePage,
                onDeletePage = { deletingPageId = it },
            )
        }
    }

    if (askingPassword) {
        PdfPasswordDialog(
            onDismiss = { askingPassword = false },
            onExport = { paperSize, password ->
                askingPassword = false
                onExportProtectedPdf(paperSize, password)
            },
        )
    }

    uiState.pages.find { it.id == deletingPageId }?.let { page ->
        AlertDialog(
            onDismissRequest = { deletingPageId = null },
            title = { Text(stringResource(R.string.delete_page_title, page.position + 1)) },
            text = { Text(stringResource(R.string.delete_page_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deletingPageId = null
                        onDeletePage(page.id)
                    },
                ) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingPageId = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun PageGrid(
    pages: List<Page>,
    contentPadding: PaddingValues,
    editable: Boolean,
    onOpenPage: (Int) -> Unit,
    onMovePage: (pageId: String, toPosition: Int) -> Unit,
    onDeletePage: (pageId: String) -> Unit,
) {
    // Order shown while dragging. Moves are only sent to the core when the
    // drag ends, so a long drag across many pages is a single write; the
    // core's answer then becomes the new `pages` and resets this.
    var order by remember(pages) { mutableStateOf(pages) }
    val gridState = rememberLazyGridState()
    val reorderState = rememberReorderableLazyGridState(gridState) { from, to ->
        order = order.toMutableList().apply { add(to.index, removeAt(from.index)) }
    }
    val haptics = LocalHapticFeedback.current

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 140.dp),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 88.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(order, key = { it.id }) { page ->
            val index = order.indexOf(page)
            ReorderableItem(reorderState, key = page.id) { isDragging ->
                PageThumbnail(
                    page = page,
                    number = index + 1,
                    canMoveEarlier = editable && index > 0,
                    canMoveLater = editable && index < order.lastIndex,
                    editable = editable,
                    isDragging = isDragging,
                    onClick = { onOpenPage(index) },
                    onMoveEarlier = { onMovePage(page.id, index - 1) },
                    onMoveLater = { onMovePage(page.id, index + 1) },
                    onDelete = { onDeletePage(page.id) },
                    // Long press anywhere on the thumbnail picks the page up.
                    dragModifier = Modifier.longPressDraggableHandle(
                        enabled = editable,
                        onDragStarted = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                        onDragStopped = {
                            val target = order.indexOfFirst { it.id == page.id }
                            if (target >= 0 && target != page.position) onMovePage(page.id, target)
                        },
                    ),
                )
            }
        }
    }
}

@Composable
private fun PageThumbnail(
    page: Page,
    /** 1-based number in the order currently shown, which changes while dragging. */
    number: Int,
    canMoveEarlier: Boolean,
    canMoveLater: Boolean,
    editable: Boolean,
    isDragging: Boolean,
    onClick: () -> Unit,
    onMoveEarlier: () -> Unit,
    onMoveLater: () -> Unit,
    onDelete: () -> Unit,
    dragModifier: Modifier = Modifier,
) {
    val pageNumber = number
    var showMenu by remember { mutableStateOf(false) }
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.graphicsLayer {
            // Lift the page being dragged above the others.
            val scale = if (isDragging) 1.05f else 1f
            scaleX = scale
            scaleY = scale
            shadowElevation = if (isDragging) 12.dp.toPx() else 0f
        },
    ) {
        OutlinedCard(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .then(dragModifier),
        ) {
            // Coil downsamples to the displayed size, so full-resolution
            // originals never get decoded into memory here.
            AsyncImage(
                model = page.displayFile,
                contentDescription = stringResource(R.string.page_number, pageNumber),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.page_number, pageNumber),
                style = MaterialTheme.typography.labelMedium,
                color = NolvaTheme.colors.inkMuted,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp),
            )
            Box {
                IconButton(onClick = { showMenu = true }, enabled = editable) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.page_options, pageNumber))
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.move_page_earlier)) },
                        enabled = canMoveEarlier,
                        onClick = {
                            showMenu = false
                            onMoveEarlier()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.move_page_later)) },
                        enabled = canMoveLater,
                        onClick = {
                            showMenu = false
                            onMoveLater()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                        onClick = {
                            showMenu = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyPages(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.no_pages_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.no_pages_body),
            style = MaterialTheme.typography.bodyMedium,
            color = NolvaTheme.colors.inkMuted,
            textAlign = TextAlign.Center,
        )
    }
}

private fun DocumentDetailMessage.textRes(): Int = when (this) {
    DocumentDetailMessage.LoadFailed -> R.string.error_load_pages
    DocumentDetailMessage.ImportFailed -> R.string.error_import_page
    DocumentDetailMessage.UnsupportedImage -> R.string.error_unsupported_image
    DocumentDetailMessage.ExportFailed -> R.string.error_export
    // Shown with its page count instead; see DocumentDetailScreen.
    DocumentDetailMessage.DocxMissingText -> R.string.docx_missing_text_some
    DocumentDetailMessage.DeletePageFailed -> R.string.error_delete_page
    DocumentDetailMessage.MovePageFailed -> R.string.error_move_page
}

internal fun PaperSize.labelRes(): Int = when (this) {
    PaperSize.A4 -> R.string.paper_a4
    PaperSize.Letter -> R.string.paper_letter
    PaperSize.Legal -> R.string.paper_legal
    PaperSize.FitImage -> R.string.paper_fit_image
}

@Preview(name = "Empty", showBackground = true)
@Preview(name = "Empty · dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun DocumentDetailEmptyPreview() {
    NolvaTheme {
        DocumentDetailScreen(
            title = "Lease agreement",
            uiState = DocumentDetailUiState(isLoading = false),
            onBack = {},
            onScan = {},
            onAddFromGallery = {},
            onMessageShown = {},
        )
    }
}

@Preview(name = "Pages", showBackground = true)
@Composable
private fun DocumentDetailPagesPreview() {
    val pages = List(3) { index ->
        Page("p$index", "d", index, File("/nonexistent/$index.jpg"), Instant.EPOCH)
    }
    NolvaTheme {
        DocumentDetailScreen(
            title = "Lease agreement",
            uiState = DocumentDetailUiState(isLoading = false, pages = pages, pendingImports = 1),
            onBack = {},
            onScan = {},
            onAddFromGallery = {},
            onMessageShown = {},
        )
    }
}
