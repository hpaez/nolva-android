package app.nolva.data

import java.io.File

/** Paper size of exported PDFs. */
enum class PaperSize {
    A4,
    Letter,

    /** 8.5 × 14 in ("oficio"). */
    Legal,

    /** Each page takes the proportions of its image (good for receipts). */
    FitImage,
}

data class PdfExport(val file: File, val pageCount: Int, val sizeBytes: Long)

/** Exports documents as PDF files. Main-safe. */
fun interface PdfExporter {
    /**
     * Writes the document to [output] (its folder must exist), one page per
     * page, using cropped images when available.
     *
     * @throws DocumentException.InvalidInput if the document has no pages.
     */
    suspend fun exportPdf(documentId: String, output: File, paperSize: PaperSize): PdfExport
}

data class DocxExport(
    val file: File,
    val pageCount: Int,
    val sizeBytes: Long,
    /** Pages exported without text because it isn't recognized yet. */
    val pagesPendingText: Int,
)

/** Exports documents as editable Word files. Main-safe. */
fun interface DocxExporter {
    /**
     * Writes the document to [output] (its folder must exist) as a .docx
     * rebuilt from the pages' recognized text, one Word page per page,
     * followed by the page images when [includeImages].
     */
    suspend fun exportDocx(documentId: String, output: File, includeImages: Boolean): DocxExport
}

/** MIME type of .docx files, for sharing. */
const val DOCX_MIME_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

private val UNSAFE_FILE_CHARS = Regex("""[\\/:*?"<>|\p{Cntrl}]""")

/**
 * [title] made safe as a file name: characters that file systems or share
 * targets reject are replaced, and it is never hidden, empty or too long.
 */
private fun safeBaseName(title: String): String =
    title.replace(UNSAFE_FILE_CHARS, "_").trim().trimStart('.').take(80).trim().ifEmpty { "document" }

/** A document's PDF file name, e.g. `Factura 3/2026` → `Factura 3_2026.pdf`. */
fun pdfFileName(title: String): String = safeBaseName(title) + ".pdf"

/** A document's Word file name, e.g. `Factura 3_2026.docx`. */
fun docxFileName(title: String): String = safeBaseName(title) + ".docx"

/** A shared page's image file name, e.g. `Factura 3_2026 - 2.jpg`. */
fun pageImageFileName(title: String, pageNumber: Int): String = "${safeBaseName(title)} - $pageNumber.jpg"
