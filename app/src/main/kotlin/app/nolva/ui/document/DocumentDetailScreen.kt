package app.nolva.ui.document

import android.content.ClipData
import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nolva.R
import app.nolva.data.Page
import app.nolva.data.PaperSize
import app.nolva.ui.theme.NolvaTheme
import coil3.compose.AsyncImage
import java.io.File
import java.time.Instant

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
        sharePdf(context, export.file, title)
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
        onDeletePage = viewModel::deletePage,
        onMovePage = viewModel::movePage,
        modifier = modifier,
    )
}

/**
 * Opens the system share sheet for [pdf]. The file is exposed through the
 * app's FileProvider with a temporary read grant for the chosen app only.
 */
private fun sharePdf(context: Context, pdf: File, title: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", pdf)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TITLE, pdf.name)
        putExtra(Intent.EXTRA_SUBJECT, title)
        clipData = ClipData.newRawUri(pdf.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, null))
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
    onDeletePage: (pageId: String) -> Unit = {},
    onMovePage: (pageId: String, toPosition: Int) -> Unit = { _, _ -> },
) {
    var showPaperMenu by remember { mutableStateOf(false) }
    var deletingPageId by rememberSaveable { mutableStateOf<String?>(null) }
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
                                onClick = { showPaperMenu = true },
                                enabled = uiState.pages.isNotEmpty() && !uiState.isExporting,
                            ) {
                                Text(stringResource(R.string.export_pdf))
                            }
                            DropdownMenu(expanded = showPaperMenu, onDismissRequest = { showPaperMenu = false }) {
                                PaperSize.entries.forEach { size ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(size.labelRes())) },
                                        onClick = {
                                            showPaperMenu = false
                                            onExportPdf(size)
                                        },
                                    )
                                }
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
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 140.dp),
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
        itemsIndexed(pages, key = { _, page -> page.id }) { index, page ->
            PageThumbnail(
                page = page,
                canMoveEarlier = editable && index > 0,
                canMoveLater = editable && index < pages.lastIndex,
                editable = editable,
                onClick = { onOpenPage(index) },
                onMoveEarlier = { onMovePage(page.id, index - 1) },
                onMoveLater = { onMovePage(page.id, index + 1) },
                onDelete = { onDeletePage(page.id) },
            )
        }
    }
}

@Composable
private fun PageThumbnail(
    page: Page,
    canMoveEarlier: Boolean,
    canMoveLater: Boolean,
    editable: Boolean,
    onClick: () -> Unit,
    onMoveEarlier: () -> Unit,
    onMoveLater: () -> Unit,
    onDelete: () -> Unit,
) {
    val pageNumber = page.position + 1
    var showMenu by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
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
    DocumentDetailMessage.ExportFailed -> R.string.error_export_pdf
    DocumentDetailMessage.DeletePageFailed -> R.string.error_delete_page
    DocumentDetailMessage.MovePageFailed -> R.string.error_move_page
}

private fun PaperSize.labelRes(): Int = when (this) {
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
