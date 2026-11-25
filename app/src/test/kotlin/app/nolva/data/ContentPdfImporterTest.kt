package app.nolva.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ContentPdfImporterTest {

    @Test
    fun `renders pages at 200 dpi`() {
        // A4 is 595 × 842 pt.
        assertEquals(1653 to 2339, ContentPdfImporter.renderSize(595, 842))
        assertEquals(1 to 1, ContentPdfImporter.renderSize(0, 0))
    }

    @Test
    fun `caps huge pages`() {
        // An A0 plan: 2384 × 3370 pt.
        val (width, height) = ContentPdfImporter.renderSize(2384, 3370)
        assertEquals(ContentPdfImporter.MAX_SIDE, height)
        assertEquals(2476, width)
    }

    @Test
    fun `names documents after the file`() {
        assertEquals("Contrato 2026", ContentPdfImporter.documentTitle("Contrato 2026.PDF", "PDF"))
        assertEquals("informe.final", ContentPdfImporter.documentTitle(" informe.final ", "PDF"))
        assertEquals("PDF importado", ContentPdfImporter.documentTitle(".pdf", "PDF importado"))
        assertEquals("PDF importado", ContentPdfImporter.documentTitle(null, "PDF importado"))
        assertEquals(MAX_TITLE_LENGTH, ContentPdfImporter.documentTitle("x".repeat(300) + ".pdf", "PDF").length)
        // Names from other apps: no line breaks or direction overrides.
        assertEquals("Boleta marzo", ContentPdfImporter.documentTitle("Boleta\nmarzo.pdf", "PDF"))
        assertEquals("Facturafdp.exe", ContentPdfImporter.documentTitle("Factura\u202Efdp.exe.pdf", "PDF"))
    }
}
