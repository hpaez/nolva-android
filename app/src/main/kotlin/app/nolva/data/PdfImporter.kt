package app.nolva.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** How far a PDF import has got. */
data class PdfImportProgress(val pagesDone: Int, val pageCount: Int)

/** Turns a PDF into a new document, one page per PDF page. Main-safe. */
fun interface PdfImporter {
    /**
     * Imports the PDF at [uri] (a `content://` URI as a string) as a new
     * document named after the file. Its pages then get their text
     * recognized like scanned ones.
     *
     * @throws DocumentException.Unsupported if the file can't be opened
     *   (damaged, or protected with a password).
     * @throws DocumentException.InvalidInput if it has no pages or more
     *   than [ContentPdfImporter.MAX_PAGES].
     */
    suspend fun import(uri: String, onProgress: (PdfImportProgress) -> Unit): Document
}

/**
 * [PdfImporter] using Android's [PdfRenderer]: each page is drawn on white
 * at [RENDER_DPI] and stored as a JPEG page. If anything fails halfway, the
 * new document is deleted, so imports are all or nothing.
 */
class ContentPdfImporter(
    private val contentResolver: ContentResolver,
    private val cacheDir: File,
    private val repository: DocumentRepository,
    private val editor: DocumentEditor,
    private val ioDispatcher: CoroutineDispatcher,
    /** Title when the file has no usable name. */
    private val fallbackTitle: String,
) : PdfImporter {

    override suspend fun import(uri: String, onProgress: (PdfImportProgress) -> Unit): Document =
        withContext(ioDispatcher) {
            val source = Uri.parse(uri)
            val title = documentTitle(displayName(source), fallbackTitle)
            val descriptor = contentResolver.openFileDescriptor(source, "r")
                ?: throw DocumentException.NotFound("cannot open $uri")
            descriptor.use {
                val renderer = try {
                    PdfRenderer(descriptor)
                } catch (e: SecurityException) {
                    throw DocumentException.Unsupported("password-protected PDF", e)
                } catch (e: IOException) {
                    throw DocumentException.Unsupported("not a readable PDF", e)
                }
                try {
                    val count = renderer.pageCount
                    if (count == 0 || count > MAX_PAGES) {
                        throw DocumentException.InvalidInput("PDF has $count pages")
                    }
                    onProgress(PdfImportProgress(0, count))
                    val document = repository.createDocument(title)
                    try {
                        val dir = File(cacheDir, "imports").apply { mkdirs() }
                        for (index in 0 until count) {
                            ensureActive()
                            val image = File.createTempFile("pdf-", ".jpg", dir)
                            try {
                                renderPage(renderer, index, image)
                                repository.addPage(document.id, image)
                            } finally {
                                image.delete()
                            }
                            onProgress(PdfImportProgress(index + 1, count))
                        }
                    } catch (e: Throwable) {
                        withContext(NonCancellable) {
                            try {
                                editor.deleteDocument(document.id)
                            } catch (cleanup: Exception) {
                                e.addSuppressed(cleanup)
                            }
                        }
                        throw e
                    }
                    document.copy(pageCount = count)
                } finally {
                    renderer.close()
                }
            }
        }

    private fun displayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    } catch (e: Exception) {
        null // Only the title depends on it.
    }

    private fun renderPage(renderer: PdfRenderer, index: Int, output: File) {
        val page = renderer.openPage(index)
        try {
            val (width, height) = renderSize(page.width, page.height)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                // PDF pages are transparent where nothing is drawn.
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                val written = output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
                if (!written) throw IOException("JPEG encoding failed")
            } finally {
                bitmap.recycle()
            }
        } finally {
            page.close()
        }
    }

    companion object {
        /** Larger PDFs are rejected: each page becomes a full image. */
        const val MAX_PAGES = 200

        /** Sharp enough for OCR and printing; an A4 page is about 1650 × 2340 px. */
        const val RENDER_DPI = 200

        private val CONTROL_CHARS = Regex("""\p{Cntrl}""")
        private val DIRECTION_MARKS = Regex("""[\u200E\u200F\u202A-\u202E\u2066-\u2069]""")

        /** Cap for huge page sizes (posters, plans), to bound memory. */
        const val MAX_SIDE = 3500

        private const val JPEG_QUALITY = 90

        /** Pixel size of a page of [widthPt] × [heightPt] points at [RENDER_DPI], capped at [MAX_SIDE]. */
        internal fun renderSize(widthPt: Int, heightPt: Int): Pair<Int, Int> {
            val scale = RENDER_DPI / 72f
            var width = widthPt * scale
            var height = heightPt * scale
            val longest = max(width, height)
            if (longest > MAX_SIDE) {
                width *= MAX_SIDE / longest
                height *= MAX_SIDE / longest
            }
            return width.roundToInt().coerceAtLeast(1) to height.roundToInt().coerceAtLeast(1)
        }

        /** The file name without `.pdf`, trimmed to a valid title, or [fallback]. */
        internal fun documentTitle(fileName: String?, fallback: String): String {
            // The name comes from another app: drop control characters (the
            // core rejects them) and direction overrides (they disguise names).
            val base = fileName.orEmpty().replace(CONTROL_CHARS, " ").replace(DIRECTION_MARKS, "").trim().let {
                if (it.endsWith(".pdf", ignoreCase = true)) it.dropLast(4) else it
            }
            return base.trim().take(MAX_TITLE_LENGTH).trim().ifEmpty { fallback }
        }
    }
}
