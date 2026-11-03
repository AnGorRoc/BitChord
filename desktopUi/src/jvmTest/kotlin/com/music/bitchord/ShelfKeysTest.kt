package com.music.bitchord

import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.ui.screens.uniqueShelfKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShelfKeysTest {
    private fun item(title: String, browseId: String? = null) =
        ShelfItem(title = title, subtitle = "", thumbnailUrl = null, videoId = null, browseId = browseId)

    @Test
    fun `an id is the key, a title stands in for a missing one`() {
        assertEquals(listOf("MPRE1", "Mix"), uniqueShelfKeys(listOf(item("Album", "MPRE1"), item("Mix"))))
    }

    @Test
    fun `two playlists with one title and no id do not share a key`() {
        val keys = uniqueShelfKeys(listOf(item("Mix"), item("Mix"), item("Mix")))

        assertEquals(3, keys.toSet().size)
        assertEquals("Mix", keys.first())
    }

    @Test
    fun `a feed that repeats an id does not repeat a key`() {
        val keys = uniqueShelfKeys(listOf(item("A", "MPRE1"), item("A again", "MPRE1")))

        assertTrue(keys[0] != keys[1])
    }
}
