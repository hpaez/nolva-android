package app.nolva.ui.crop

import app.nolva.data.CornerDetection
import app.nolva.data.Corners
import app.nolva.data.DocumentException
import app.nolva.data.NormalizedPoint
import app.nolva.data.Page
import app.nolva.data.PageCropper
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private val DETECTED = Corners(
    topLeft = NormalizedPoint(0.1f, 0.1f),
    topRight = NormalizedPoint(0.9f, 0.12f),
    bottomRight = NormalizedPoint(0.88f, 0.9f),
    bottomLeft = NormalizedPoint(0.12f, 0.88f),
)

@OptIn(ExperimentalCoroutinesApi::class)
class CropViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class FakeCropper(
        private val savedCorners: Corners? = null,
        private val detection: CornerDetection? = null,
        private val cropFailure: Exception? = null,
    ) : PageCropper {
        var cropped: Corners? = null

        override suspend fun getPage(pageId: String) =
            Page(pageId, "doc", 0, File("/original.jpg"), Instant.EPOCH, corners = savedCorners)

        override suspend fun detectCorners(pageId: String) = detection

        override suspend fun cropPage(pageId: String, corners: Corners): Page {
            cropFailure?.let { throw it }
            cropped = corners
            return getPage(pageId).copy(corners = corners, processedFile = File("/processed.jpg"))
        }
    }

    @Test
    fun `resumes a previous crop without re-detecting`() = runTest(dispatcher) {
        val viewModel = CropViewModel("p", FakeCropper(savedCorners = DETECTED))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(DETECTED, state.corners)
        assertFalse(state.shouldCheckCorners)
    }

    @Test
    fun `starts from a confident detection`() = runTest(dispatcher) {
        val viewModel = CropViewModel("p", FakeCropper(detection = CornerDetection(DETECTED, 0.9f)))
        advanceUntilIdle()

        assertEquals(DETECTED, viewModel.uiState.value.corners)
        assertFalse(viewModel.uiState.value.shouldCheckCorners)
    }

    @Test
    fun `asks to check the corners when detection is weak or missing`() = runTest(dispatcher) {
        val weak = CropViewModel("p", FakeCropper(detection = CornerDetection(DETECTED, 0.3f)))
        val none = CropViewModel("p", FakeCropper(detection = null))
        advanceUntilIdle()

        assertTrue(weak.uiState.value.shouldCheckCorners)
        assertTrue(none.uiState.value.shouldCheckCorners)
        assertEquals(Corners.FULL, none.uiState.value.corners)
    }

    @Test
    fun `moving a corner clamps it to the image`() = runTest(dispatcher) {
        val viewModel = CropViewModel("p", FakeCropper())
        advanceUntilIdle()

        viewModel.moveCorner(Corner.TopRight, NormalizedPoint(1.4f, -0.2f))

        assertEquals(NormalizedPoint(1f, 0f), viewModel.uiState.value.corners.topRight)
    }

    @Test
    fun `applies the edited corners`() = runTest(dispatcher) {
        val cropper = FakeCropper(detection = CornerDetection(DETECTED, 0.9f))
        val viewModel = CropViewModel("p", cropper)
        advanceUntilIdle()

        viewModel.moveCorner(Corner.BottomLeft, NormalizedPoint(0.2f, 0.8f))
        viewModel.apply()
        advanceUntilIdle()

        assertEquals(NormalizedPoint(0.2f, 0.8f), cropper.cropped?.bottomLeft)
        assertTrue(viewModel.uiState.value.saved)
    }

    @Test
    fun `explains crossed corners instead of closing`() = runTest(dispatcher) {
        val viewModel = CropViewModel("p", FakeCropper(cropFailure = DocumentException.InvalidInput("crossed")))
        advanceUntilIdle()

        viewModel.apply()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.saved)
        assertFalse(state.isSaving)
        assertEquals(CropMessage.InvalidCorners, state.message)
    }
}
