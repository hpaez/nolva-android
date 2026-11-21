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
    fun `rejects unsupported or unknown types`() {
        assertNull(ContentPageImporter.extensionFor("image/gif"))
        assertNull(ContentPageImporter.extensionFor("application/pdf"))
        assertNull(ContentPageImporter.extensionFor(null))
    }
}
