package app.nolva.ui.document

import android.content.res.Configuration
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nolva.R
import app.nolva.data.Page
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
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK),
    ) { uris -> viewModel.importImages(uris.map { it.toString() }) }

    DocumentDetailScreen(
        title = title,
        uiState = uiState,
        onBack = onBack,
        onAddFromGallery = {
            pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        onMessageShown = viewModel::messageShown,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentDetailScreen(
    title: String,
    uiState: DocumentDetailUiState,
    onBack: () -> Unit,
    onAddFromGallery: () -> Unit,
    onMessageShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
                )
                if (uiState.isImporting) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddFromGallery,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Text(stringResource(R.string.add_from_gallery))
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

            else -> PageGrid(pages = uiState.pages, contentPadding = innerPadding)
        }
    }
}

@Composable
private fun PageGrid(pages: List<Page>, contentPadding: PaddingValues) {
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
        items(pages, key = { it.id }) { page -> PageThumbnail(page) }
    }
}

@Composable
private fun PageThumbnail(page: Page) {
    val pageNumber = page.position + 1
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            // Coil downsamples to the displayed size, so full-resolution
            // originals never get decoded into memory here.
            AsyncImage(
                model = page.originalFile,
                contentDescription = stringResource(R.string.page_number, pageNumber),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f),
            )
        }
        Text(
            text = stringResource(R.string.page_number, pageNumber),
            style = MaterialTheme.typography.labelMedium,
            color = NolvaTheme.colors.inkMuted,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
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
            onAddFromGallery = {},
            onMessageShown = {},
        )
    }
}
