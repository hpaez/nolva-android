package app.nolva.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfFileNameTest {

    @Test
    fun `keeps readable titles as they are`() {
        assertEquals("Contrato de arriendo.pdf", pdfFileName("Contrato de arriendo"))
    }

    @Test
    fun `replaces characters file systems reject`() {
        assertEquals("Factura 3_2026 _luz_.pdf", pdfFileName("Factura 3/2026 \"luz\""))
        assertEquals("a_b_c_d.pdf", pdfFileName("a:b*c?d"))
    }

    @Test
    fun `never produces hidden or empty names`() {
        assertEquals("notas.pdf", pdfFileName("...notas"))
        assertEquals("document.pdf", pdfFileName("   "))
    }

    @Test
    fun `names shared page images after the document and page number`() {
        assertEquals("Factura 3_2026 - 2.jpg", pageImageFileName("Factura 3/2026", 2))
        assertEquals("document - 1.jpg", pageImageFileName("  ", 1))
        assertTrue(pageImageFileName("x".repeat(300), 12).endsWith(" - 12.jpg"))
    }

    @Test
    fun `limits very long titles`() {
        assertEquals(84, pdfFileName("x".repeat(300)).length)
    }
}
