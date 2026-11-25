package app.nolva.ui.document

import org.junit.Assert.assertEquals
import org.junit.Test

class PdfPasswordDialogTest {

    @Test
    fun `passwords must be set, short enough for pdf readers and confirmed`() {
        assertEquals(PasswordProblem.Empty, passwordProblem("", ""))
        assertEquals(PasswordProblem.Mismatch, passwordProblem("clave", "clavE"))
        assertEquals(null, passwordProblem("clave", "clave"))
        // The limit is in UTF-8 bytes: 127 ASCII letters fit, 64 "ñ" (128 bytes) don't.
        val ascii = "a".repeat(127)
        assertEquals(null, passwordProblem(ascii, ascii))
        val accented = "ñ".repeat(64)
        assertEquals(PasswordProblem.TooLong, passwordProblem(accented, accented))
    }
}
