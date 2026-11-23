package app.nolva.data

import org.junit.Assert.assertEquals
import org.junit.Test

class MlKitTextRecognizerTest {

    @Test
    fun `downsamples only images larger than the limit`() {
        assertEquals(1, MlKitTextRecognizer.sampleSizeFor(4000, 3000))
        assertEquals(1, MlKitTextRecognizer.sampleSizeFor(4096, 3072))
        assertEquals(2, MlKitTextRecognizer.sampleSizeFor(3000, 8000))
        assertEquals(4, MlKitTextRecognizer.sampleSizeFor(16384, 100))
    }
}
