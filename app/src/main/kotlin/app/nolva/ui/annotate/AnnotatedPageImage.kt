package app.nolva.ui.annotate

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import app.nolva.data.NormalizedPoint
import app.nolva.data.PageAnnotation
import app.nolva.ui.crop.fittedRect
import coil3.compose.AsyncImage
import java.io.File

/** Opacity of highlights, as in exported PDFs. */
private const val HIGHLIGHT_ALPHA = 0.4f

/**
 * A page image fitted into the available space, with [annotations] drawn
 * over it. [overlay] draws on top (e.g. a stroke in progress) in the same
 * coordinates; [onImageRect] reports where the image landed, in this
 * composable's pixels, and its size in image pixels.
 */
@Composable
fun AnnotatedPageImage(
    image: File,
    annotations: List<PageAnnotation>,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    onImageRect: (rect: Rect, imageSize: Size) -> Unit = { _, _ -> },
    overlay: DrawScope.(rect: Rect) -> Unit = {},
) {
    var imageSize by remember(image) { mutableStateOf<Size?>(null) }
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier = modifier) {
        val container = with(density) { Size(maxWidth.toPx(), maxHeight.toPx()) }
        val rect = imageSize?.let { fittedRect(it, container) }
        AsyncImage(
            model = image,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            // Coil applies EXIF orientation: display dimensions, the space
            // annotations are expressed in.
            onSuccess = { state ->
                val size = Size(state.result.image.width.toFloat(), state.result.image.height.toFloat())
                imageSize = size
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (rect != null) {
            val size = imageSize
            LaunchedEffect(rect, size) {
                if (size != null) onImageRect(rect, size)
            }
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawAnnotations(annotations, rect, textMeasurer)
                overlay(rect)
            }
        }
    }
}

/** Draws [annotations], bottom to top, over an image drawn at [rect]. */
internal fun DrawScope.drawAnnotations(annotations: List<PageAnnotation>, rect: Rect, textMeasurer: TextMeasurer) {
    annotations.forEach { drawAnnotation(it, rect, textMeasurer) }
}

internal fun DrawScope.drawAnnotation(annotation: PageAnnotation, rect: Rect, textMeasurer: TextMeasurer) {
    val unit = minOf(rect.width, rect.height)
    fun at(p: NormalizedPoint) = Offset(rect.left + p.x * rect.width, rect.top + p.y * rect.height)
    when (annotation) {
        is PageAnnotation.Ink -> drawStrokes(
            annotation.strokes.map { stroke -> stroke.map(::at) },
            rgb(annotation.color),
            annotation.width * unit,
        )
        is PageAnnotation.Highlight -> {
            val topLeft = at(NormalizedPoint(annotation.left, annotation.top))
            val bottomRight = at(NormalizedPoint(annotation.right, annotation.bottom))
            // Multiplied, like a real marker: the text under it stays dark.
            drawRect(
                color = rgb(annotation.color),
                topLeft = topLeft,
                size = Size(bottomRight.x - topLeft.x, bottomRight.y - topLeft.y),
                alpha = HIGHLIGHT_ALPHA,
                blendMode = BlendMode.Multiply,
            )
        }
        is PageAnnotation.Text -> {
            val fontPx = annotation.size * unit
            drawText(
                textMeasurer = textMeasurer,
                text = annotation.text,
                topLeft = at(NormalizedPoint(annotation.x, annotation.y)),
                style = TextStyle(
                    color = rgb(annotation.color),
                    fontSize = fontPx.toSp(),
                    lineHeight = (fontPx * LINE_HEIGHT).toSp(),
                    fontFamily = FontFamily.SansSerif,
                ),
            )
        }
    }
}

/** Strokes in screen pixels: round ends, so a single point is a dot. */
internal fun DrawScope.drawStrokes(strokes: List<List<Offset>>, color: Color, width: Float) {
    strokes.forEach { stroke ->
        when (stroke.size) {
            0 -> Unit
            1 -> drawCircle(color, radius = width / 2, center = stroke[0])
            else -> {
                val path = Path().apply {
                    moveTo(stroke[0].x, stroke[0].y)
                    for (i in 1 until stroke.size) lineTo(stroke[i].x, stroke[i].y)
                }
                drawPath(path, color, style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

/** `0xRRGGBB` as an opaque colour. */
internal fun rgb(color: Int): Color = Color(0xFF000000.toInt() or (color and 0xFFFFFF))
