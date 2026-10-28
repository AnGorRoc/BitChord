package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/** The order a rendition is filled in: past the guard first, then the held stretch, then what comes before it. */
class FillOrderTest {

    @Test
    fun `with nothing held the fill goes in file order`() {
        val starts = listOf(0L, 500L, 1000L)

        assertEquals(starts, AudioCache.fillOrder(starts, firstHeld = 0L, guardEnd = 0L))
    }

    @Test
    fun `what is past the guard comes first, then the held stretch, then what is before it`() {
        val starts = listOf(0L, 500L, 1000L, 1500L, 2000L, 2500L, 3000L)

        val order = AudioCache.fillOrder(starts, firstHeld = 1000L, guardEnd = 3000L)

        assertEquals(listOf(3000L, 1000L, 1500L, 2000L, 2500L, 0L, 500L), order)
    }

    @Test
    fun `every start is filled exactly once`() {
        val starts = (0L..20L).map { it * 100 }

        val order = AudioCache.fillOrder(starts, firstHeld = 700L, guardEnd = 1500L)

        assertEquals(starts.sorted(), order.sorted())
    }
}
