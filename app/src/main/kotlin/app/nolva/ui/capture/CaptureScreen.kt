package app.nolva.ui.capture

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nolva.R
import app.nolva.data.LumaFrame
import app.nolva.data.Page
import coil3.compose.AsyncImage
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

@Composable
fun CaptureRoute(
    viewModel: CaptureViewModel,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var permissionDenied by rememberSaveable { mutableStateOf(false) }
    val requestPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
        permissionDenied = !granted
    }
    LaunchedEffect(Unit) {
        if (!hasPermission && !permissionDenied) requestPermission.launch(Manifest.permission.CAMERA)
    }

    val imageCapture = remember {
        ImageCapture.Builder()
            // Documents are static: favour detail (for OCR later) over speed.
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            // Same 4:3 as the preview and analysis, so the live guide's
            // coordinates line up with what is on screen and in the photo.
            .setResolutionSelector(FOUR_BY_THREE)
            .build()
    }
    LaunchedEffect(uiState.flash) { imageCapture.flashMode = uiState.flash.toCameraX() }
    var isCapturing by remember { mutableStateOf(false) }
    var hasFlash by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    fun takePicture() {
        if (isCapturing) return
        isCapturing = true
        scope.launch {
            val file = withContext(Dispatchers.IO) { viewModel.newCaptureFile() }
            try {
                imageCapture.takePictureTo(file)
                viewModel.onPictureTaken(file)
            } catch (e: ImageCaptureException) {
                viewModel.onCaptureFailed(file)
            } finally {
                isCapturing = false
            }
        }
    }

    LaunchedEffect(uiState.autoShutter) {
        if (uiState.autoShutter) {
            viewModel.autoShutterHandled()
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            takePicture()
        }
    }

    CaptureScreen(
        uiState = uiState,
        hasPermission = hasPermission,
        permissionDenied = permissionDenied,
        isCapturing = isCapturing,
        hasFlash = hasFlash,
        cameraContent = {
            CameraPreview(
                imageCapture = imageCapture,
                onFrame = viewModel::analyzeFrame,
                onCameraReady = { camera -> hasFlash = camera.cameraInfo.hasFlashUnit() },
                modifier = Modifier.fillMaxSize(),
            )
        },
        onShutter = { takePicture() },
        onToggleAutoCapture = viewModel::toggleAutoCapture,
        onCycleFlash = viewModel::cycleFlash,
        onRequestPermission = { requestPermission.launch(Manifest.permission.CAMERA) },
        onOpenSettings = {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
            )
        },
        onDone = onDone,
        onMessageShown = viewModel::messageShown,
        onDiscard = viewModel::discard,
        modifier = modifier,
    )
}

@Composable
private fun CaptureScreen(
    uiState: CaptureUiState,
    hasPermission: Boolean,
    permissionDenied: Boolean,
    isCapturing: Boolean,
    hasFlash: Boolean,
    cameraContent: @Composable () -> Unit,
    onShutter: () -> Unit,
    onToggleAutoCapture: () -> Unit,
    onCycleFlash: () -> Unit,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onDone: () -> Unit,
    onMessageShown: () -> Unit,
    onDiscard: (pageId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var reviewingId by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val messageText = uiState.message?.let { stringResource(it.textRes()) }
    LaunchedEffect(uiState.message) {
        if (messageText != null) {
            snackbarHostState.showSnackbar(messageText)
            onMessageShown()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        when {
            hasPermission -> {
                cameraContent()
                GuideOverlay(uiState.guide, Modifier.fillMaxSize())
            }
            permissionDenied -> PermissionDenied(onRequestPermission, onOpenSettings)
        }

        IconButton(
            onClick = onDone,
            modifier = Modifier
                .statusBarsPadding()
                .padding(8.dp)
                .align(Alignment.TopStart),
        ) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close), tint = Color.White)
        }

        if (hasPermission) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(8.dp)
                    .align(Alignment.TopEnd),
            ) {
                if (hasFlash) {
                    TextButton(onClick = onCycleFlash) {
                        Text(stringResource(uiState.flash.labelRes()), color = Color.White)
                    }
                }
                TextButton(onClick = onToggleAutoCapture) {
                    Text(
                        stringResource(if (uiState.autoCapture) R.string.auto_capture_on else R.string.auto_capture_off),
                        color = Color.White,
                    )
                }
            }

            Text(
                text = stringResource(
                    when {
                        uiState.guide == null -> R.string.capture_hint_find
                        uiState.autoCapture && uiState.guide.steadiness > 0f -> R.string.capture_hint_hold
                        else -> R.string.capture_hint_found
                    },
                ),
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(top = 64.dp)
                    .align(Alignment.TopCenter)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )

            CaptureControls(
                captured = uiState.captured,
                onReview = { reviewingId = it.id },
                isBusy = isCapturing,
                isSaving = uiState.isSaving,
                onShutter = onShutter,
                onDone = onDone,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding(),
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 120.dp),
        )
    }

    val reviewing = uiState.captured.find { it.id == reviewingId }
    if (reviewing != null) {
        val number = uiState.captured.indexOf(reviewing) + 1
        AlertDialog(
            onDismissRequest = { reviewingId = null },
            title = { Text(stringResource(R.string.page_number, number)) },
            text = {
                AsyncImage(
                    model = reviewing.displayFile,
                    contentDescription = stringResource(R.string.page_number, number),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                )
            },
            confirmButton = {
                TextButton(onClick = { reviewingId = null }) { Text(stringResource(R.string.keep)) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        reviewingId = null
                        onDiscard(reviewing.id)
                    },
                ) {
                    Text(stringResource(R.string.discard), color = MaterialTheme.colorScheme.error)
                }
            },
        )
    }
}

@Composable
private fun CaptureControls(
    captured: List<Page>,
    onReview: (Page) -> Unit,
    isBusy: Boolean,
    isSaving: Boolean,
    onShutter: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.5f))
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            captured.lastOrNull()?.let { last ->
                // The last page as stored, already auto-cropped: tap to check it.
                AsyncImage(
                    model = last.displayFile,
                    contentDescription = stringResource(R.string.review_last_page),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(width = 42.dp, height = 56.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .border(1.dp, Color.White, RoundedCornerShape(4.dp))
                        .clickable(role = Role.Button) { onReview(last) },
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(
                text = pluralStringResource(R.plurals.pages_captured, captured.size, captured.size),
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
            )
        }

        ShutterButton(enabled = !isBusy, onClick = onShutter)

        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            if (isSaving) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                TextButton(onClick = onDone) {
                    Text(stringResource(R.string.done), color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun ShutterButton(enabled: Boolean, onClick: () -> Unit) {
    val description = stringResource(R.string.take_photo)
    Box(
        modifier = Modifier
            .size(72.dp)
            .border(4.dp, Color.White, CircleShape)
            .padding(6.dp)
            .background(
                if (enabled) MaterialTheme.colorScheme.primary else Color.Gray,
                CircleShape,
            )
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClickLabel = description,
                onClick = onClick,
            ),
    )
}

@Composable
private fun PermissionDenied(onRequestPermission: () -> Unit, onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.camera_permission_title),
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.camera_permission_body),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.8f),
            textAlign = TextAlign.Center,
        )
        Button(onClick = onRequestPermission) { Text(stringResource(R.string.grant_permission)) }
        OutlinedButton(onClick = onOpenSettings) { Text(stringResource(R.string.open_settings), color = Color.White) }
    }
}

/** 4:3, the sensor's native shape, for every use case so their coordinates agree. */
private val FOUR_BY_THREE: ResolutionSelector = ResolutionSelector.Builder()
    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
    .build()

/** Small frames are plenty for finding the sheet and keep analysis cheap. */
private val ANALYSIS_RESOLUTION: ResolutionSelector = ResolutionSelector.Builder()
    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
    .setResolutionStrategy(
        ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
    )
    .build()

/** At most this often a frame is analysed (about 8 per second). */
private const val ANALYSIS_INTERVAL_MS = 120L

/**
 * Live back-camera preview bound to the current lifecycle, sharing
 * [imageCapture], with sampled frames sent to [onFrame] on a background
 * thread.
 */
@Composable
private fun CameraPreview(
    imageCapture: ImageCapture,
    onFrame: (LumaFrame, nowMs: Long) -> Unit,
    onCameraReady: (Camera) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var surfaceRequest by remember { mutableStateOf<SurfaceRequest?>(null) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(analysisExecutor) { onDispose { analysisExecutor.shutdown() } }

    LaunchedEffect(lifecycleOwner, imageCapture) {
        val provider = ProcessCameraProvider.awaitInstance(context)
        val preview = Preview.Builder().setResolutionSelector(FOUR_BY_THREE).build().apply {
            setSurfaceProvider { request -> surfaceRequest = request }
        }
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(ANALYSIS_RESOLUTION)
            // Drop frames that arrive while one is being analysed.
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        var lastAnalysis = 0L
        analysis.setAnalyzer(analysisExecutor) { image ->
            image.use {
                val now = System.currentTimeMillis()
                if (now - lastAnalysis >= ANALYSIS_INTERVAL_MS) {
                    lastAnalysis = now
                    onFrame(it.toLumaFrame(), now)
                }
            }
        }
        val camera = provider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            imageCapture,
            analysis,
        )
        onCameraReady(camera)
        try {
            awaitCancellation()
        } finally {
            analysis.clearAnalyzer()
            provider.unbind(preview, imageCapture, analysis)
        }
    }

    surfaceRequest?.let { request ->
        CameraXViewfinder(surfaceRequest = request, modifier = modifier)
    }
}

/** Copies the frame's luminance plane; the buffer is only valid until the image is closed. */
private fun ImageProxy.toLumaFrame(): LumaFrame {
    val plane = planes[0]
    val buffer = plane.buffer.duplicate().apply { rewind() }
    val luma = ByteArray(buffer.remaining()).also { buffer.get(it) }
    return LumaFrame(width, height, plane.rowStride, luma, imageInfo.rotationDegrees)
}

/**
 * The sheet's outline over the preview. The viewfinder fills the screen and
 * crops the 4:3 frame (like ContentScale.Crop), so the normalized corners
 * are mapped through the same crop. The outline fills in as the sheet is
 * held still for an automatic capture.
 */
@Composable
private fun GuideOverlay(guide: LiveGuide?, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val g = guide ?: return@Canvas
        val boxAspect = size.width / size.height
        val (contentW, contentH) = if (boxAspect > g.frameAspect) {
            size.width to size.width / g.frameAspect
        } else {
            size.height * g.frameAspect to size.height
        }
        val left = (size.width - contentW) / 2f
        val top = (size.height - contentH) / 2f
        val points = with(g.corners) { listOf(topLeft, topRight, bottomRight, bottomLeft) }
            .map { Offset(left + it.x * contentW, top + it.y * contentH) }
        val path = Path().apply {
            moveTo(points[0].x, points[0].y)
            points.drop(1).forEach { lineTo(it.x, it.y) }
            close()
        }
        drawPath(path, color.copy(alpha = 0.12f + 0.38f * g.steadiness))
        drawPath(path, color, style = Stroke(width = 3.dp.toPx()))
    }
}

private fun FlashMode.toCameraX(): Int = when (this) {
    FlashMode.Off -> ImageCapture.FLASH_MODE_OFF
    FlashMode.Auto -> ImageCapture.FLASH_MODE_AUTO
    FlashMode.On -> ImageCapture.FLASH_MODE_ON
}

private fun FlashMode.labelRes(): Int = when (this) {
    FlashMode.Off -> R.string.flash_off
    FlashMode.Auto -> R.string.flash_auto
    FlashMode.On -> R.string.flash_on
}

/** Takes a JPEG into [file], suspending until it is fully written. */
private suspend fun ImageCapture.takePictureTo(file: File) = suspendCancellableCoroutine { continuation ->
    takePicture(
        ImageCapture.OutputFileOptions.Builder(file).build(),
        Dispatchers.IO.asExecutor(),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                continuation.resume(Unit)
            }

            override fun onError(exception: ImageCaptureException) {
                continuation.resumeWithException(exception)
            }
        },
    )
}

private fun CaptureMessage.textRes(): Int = when (this) {
    CaptureMessage.CaptureFailed -> R.string.error_capture
    CaptureMessage.SaveFailed -> R.string.error_import_page
    CaptureMessage.DiscardFailed -> R.string.error_delete_page
}
