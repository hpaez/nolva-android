package app.nolva.ui.annotate

import androidx.compose.ui.geometry.Size
import app.nolva.data.CornerDetection
import app.nolva.data.Corners
import app.nolva.data.DocumentException
import app.nolva.data.NormalizedPoint
import app.nolva.data.Page
import app.nolva.data.PageAnnotation
import app.nolva.data.PageAnnotator
import app.nolva.data.PageCropper
import app.nolva.data.PageFilter
import app.nolva.data.PageRendering
import app.nolva.data.Signature
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

@OptIn(ExperimentalCoroutinesApi::class)
class AnnotationEditorViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val page = Page("p0", "doc", 0, File("/p0.jpg"), Instant.EPOCH)

    private class FakeCropper(private val page: Page) : PageCropper {
        override suspend fun getPage(pageId: String): Page = page
        override suspend fun detectCorners(pageId: String): CornerDetection? = error("unused")
        override suspend fun cropPage(pageId: String, corners: Corners): Page = error("unused")
        override suspend fun applyFilter(pageId: String, filter: PageFilter): Page = error("unused")
        override suspend fun rotatePage(pageId: String, clockwise: Boolean): Page = error("unused")
        override suspend fun setRendering(pageId: String, rendering: PageRendering): Page = error("unused")
    }

    private class FakeAnnotator(
        var stored: List<PageAnnotation> = emptyList(),
        val signatures: MutableList<Signature> = mutableListOf(),
        var failSave: Boolean = false,
    ) : PageAnnotator {
        var saves = 0

        override suspend fun getAnnotations(pageId: String): List<PageAnnotation> = stored

        override suspend fun setAnnotations(pageId: String, annotations: List<PageAnnotation>) {
            if (failSave) throw DocumentException.Storage("disk full")
            saves++
            stored = annotations
        }

        override suspend fun listSignatures(): List<Signature> = signatures.toList()

        override suspend fun saveSignature(strokes: List<List<NormalizedPoint>>, aspectRatio: Float): Signature {
            if (signatures.size >= 10) throw DocumentException.InvalidInput("full")
            return Signature("s${signatures.size}", strokes, aspectRatio).also { signatures += it }
        }

        override suspend fun deleteSignature(signatureId: String) {
            signatures.removeAll { it.id == signatureId }
        }
    }

    private fun viewModel(annotator: FakeAnnotator = FakeAnnotator()) =
        AnnotationEditorViewModel("p0", FakeCropper(page), annotator)

    private val image = Size(1000f, 1400f)

    private fun p(x: Float, y: Float) = NormalizedPoint(x, y)

    @Test
    fun `loads the page and its annotations`() = runTest(dispatcher) {
        val existing = PageAnnotation.Text("Hola", 0.1f, 0.1f, TEXT_SIZE, 0)
        val viewModel = viewModel(FakeAnnotator(stored = listOf(existing)))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(page, state.page)
        assertEquals(listOf(existing), state.annotations)
        assertFalse(state.isDirty)
    }

    @Test
    fun `draws with the chosen tool, colour and width`() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.selectColor(PEN_COLORS[2])
        viewModel.selectPenWidth(PenWidth.Thick)
        viewModel.addStroke(listOf(p(0.1f, 0.1f), p(0.2f, 0.2f)))
        viewModel.selectTool(AnnotationTool.Highlighter)
        viewModel.selectColor(HIGHLIGHT_COLORS[1])
        viewModel.addHighlight(p(0.8f, 0.3f), p(0.2f, 0.25f)) // dragged right to left
        viewModel.addHighlight(p(0.5f, 0.5f), p(0.5f, 0.5f)) // a tap: ignored
        viewModel.addText("Visto bueno  ", p(0.1f, 0.9f))
        viewModel.addText("   ", p(0.1f, 0.9f)) // blank: ignored

        assertEquals(
            listOf(
                PageAnnotation.Ink(listOf(listOf(p(0.1f, 0.1f), p(0.2f, 0.2f))), PEN_COLORS[2], PenWidth.Thick.value),
                PageAnnotation.Highlight(0.2f, 0.25f, 0.8f, 0.3f, HIGHLIGHT_COLORS[1]),
                PageAnnotation.Text("Visto bueno", 0.1f, 0.9f, TEXT_SIZE, PEN_COLORS[2]),
            ),
            viewModel.uiState.value.annotations,
        )
        assertTrue(viewModel.uiState.value.isDirty)
    }

    @Test
    fun `undo, redo and the eraser work one annotation at a time`() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.addStroke(listOf(p(0.1f, 0.5f), p(0.9f, 0.5f)))
        viewModel.addStroke(listOf(p(0.5f, 0.1f)))

        viewModel.eraseAt(p(0.5f, 0.1f), image, tolerance = 10f)
        assertEquals(1, viewModel.uiState.value.annotations.size)
        viewModel.eraseAt(p(0.5f, 0.9f), image, tolerance = 10f) // nothing there
        assertEquals(1, viewModel.uiState.value.annotations.size)

        viewModel.undo()
        assertEquals(2, viewModel.uiState.value.annotations.size)
        viewModel.undo()
        viewModel.undo()
        assertTrue(viewModel.uiState.value.annotations.isEmpty())
        assertFalse(viewModel.uiState.value.canUndo)
        assertFalse("back where it started", viewModel.uiState.value.isDirty)

        viewModel.redo()
        assertEquals(1, viewModel.uiState.value.annotations.size)
        viewModel.addStroke(listOf(p(0.3f, 0.3f)))
        assertFalse("a new edit drops what was undone", viewModel.uiState.value.canRedo)
    }

    @Test
    fun `saves everything at once and closes`() = runTest(dispatcher) {
        val annotator = FakeAnnotator()
        val viewModel = viewModel(annotator)
        advanceUntilIdle()
        viewModel.addStroke(listOf(p(0.1f, 0.1f)))
        viewModel.addText("Ok", p(0.2f, 0.2f))

        viewModel.save()
        advanceUntilIdle()

        assertEquals(1, annotator.saves)
        assertEquals(viewModel.uiState.value.annotations, annotator.stored)
        assertTrue(viewModel.uiState.value.isSaved)
    }

    @Test
    fun `closing without changes saves nothing`() = runTest(dispatcher) {
        val annotator = FakeAnnotator()
        val viewModel = viewModel(annotator)
        advanceUntilIdle()

        viewModel.save()
        advanceUntilIdle()

        assertEquals(0, annotator.saves)
        assertTrue(viewModel.uiState.value.isSaved)
    }

    @Test
    fun `a failed save keeps the edits to try again`() = runTest(dispatcher) {
        val annotator = FakeAnnotator(failSave = true)
        val viewModel = viewModel(annotator)
        advanceUntilIdle()
        viewModel.addStroke(listOf(p(0.1f, 0.1f)))

        viewModel.save()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(AnnotationEditorMessage.SaveFailed, state.message)
        assertFalse(state.isSaved)
        assertTrue(state.isDirty)
        assertEquals(1, state.annotations.size)
    }

    @Test
    fun `signatures are saved, chosen and placed with a tap`() = runTest(dispatcher) {
        val annotator = FakeAnnotator()
        val viewModel = viewModel(annotator)
        advanceUntilIdle()

        viewModel.selectTool(AnnotationTool.Signature)
        viewModel.placeSignature(p(0.5f, 0.5f), image)
        assertEquals(AnnotationEditorMessage.PlaceSignatureFirst, viewModel.uiState.value.message)
        viewModel.messageShown()

        val drawn = NormalizedSignature(listOf(listOf(p(0f, 1f), p(1f, 0f))), aspectRatio = 3f)
        viewModel.saveSignature(drawn)
        advanceUntilIdle()
        val saved = annotator.signatures.single()
        assertEquals(saved, viewModel.uiState.value.chosenSignature)

        viewModel.placeSignature(p(0.5f, 0.8f), image)
        val ink = viewModel.uiState.value.annotations.single() as PageAnnotation.Ink
        assertEquals(1, ink.strokes.size)
        assertEquals(viewModel.uiState.value.penColor, ink.color)

        viewModel.deleteSignature(saved)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.signatures.isEmpty())
        assertEquals(null, viewModel.uiState.value.chosenSignature)
        assertEquals("placed signatures stay", 1, viewModel.uiState.value.annotations.size)
    }

    @Test
    fun `says when there are too many signatures`() = runTest(dispatcher) {
        val full = MutableList(10) { Signature("s$it", listOf(listOf(p(0f, 0f))), 1f) }
        val viewModel = viewModel(FakeAnnotator(signatures = full))
        advanceUntilIdle()

        viewModel.saveSignature(NormalizedSignature(listOf(listOf(p(0f, 0f))), 1f))
        advanceUntilIdle()

        assertEquals(AnnotationEditorMessage.TooManySignatures, viewModel.uiState.value.message)
    }
}
