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

private val UNSAFE_FILE_CHARS = Regex("""[\\/:*?"<>|\p{Cntrl}]""")

/**
 * A file name for a document's PDF: its title with characters that file
 * systems or share targets reject replaced, e.g. `Factura 3/2026` →
 * `Factura 3_2026.pdf`.
 */
fun pdfFileName(title: String): String {
    val base = title.replace(UNSAFE_FILE_CHARS, "_").trim().trimStart('.').take(80).trim()
    return (base.ifEmpty { "document" }) + ".pdf"
}
