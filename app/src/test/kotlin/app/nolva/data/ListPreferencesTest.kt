package app.nolva.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ListPreferencesTest {

    @Test
    fun `unknown or missing stored values fall back to the default`() {
        assertEquals(DocumentSort.Size, enumOrDefault("Size", DocumentSort.Updated))
        assertEquals(DocumentSort.Updated, enumOrDefault("Color", DocumentSort.Updated))
        assertEquals(ListLayout.List, enumOrDefault(null, ListLayout.List))
    }
}
