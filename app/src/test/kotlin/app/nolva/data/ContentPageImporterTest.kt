package app.nolva.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContentPageImporterTest {

    @Test
    fun `maps supported MIME types to the extensions the core expects`() {
        assertEquals("jpg", ContentPageImporter.extensionFor("image/jpeg"))
        assertEquals("png", ContentPageImporter.extensionFor("image/png"))
        assertEquals("heic", ContentPageImporter.extensionFor("image/heif"))
        assertEquals("webp", ContentPageImporter.extensionFor("IMAGE/WEBP"))
    }

    @Test
    fun `copies formats the core reads`() {
        assertEquals(ContentPageImporter.ImportPlan.Copy("jpg"), ContentPageImporter.importPlanFor("image/jpeg", 26))
        assertEquals(ContentPageImporter.ImportPlan.Copy("webp"), ContentPageImporter.importPlanFor("image/webp", 36))
    }

    @Test
    fun `converts HEIC and HEIF to JPEG where Android can decode them`() {
        assertEquals(ContentPageImporter.ImportPlan.ConvertToJpeg, ContentPageImporter.importPlanFor("image/heic", 28))
        assertEquals(ContentPageImporter.ImportPlan.ConvertToJpeg, ContentPageImporter.importPlanFor("image/heif", 37))
        assertEquals("jpg", ContentPageImporter.ImportPlan.ConvertToJpeg.extension)
    }

    @Test
    fun `rejects HEIC before Android 9`() {
        assertNull(ContentPageImporter.importPlanFor("image/heic", 27))
    }

    @Test
    fun `rejects unsupported or unknown types`() {
        assertNull(ContentPageImporter.importPlanFor("image/gif", 36))
        assertNull(ContentPageImporter.extensionFor("image/gif"))
        assertNull(ContentPageImporter.extensionFor("application/pdf"))
        assertNull(ContentPageImporter.extensionFor(null))
    }
}
