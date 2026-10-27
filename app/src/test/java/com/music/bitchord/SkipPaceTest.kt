package com.music.bitchord

import com.music.bitchord.data.sources.SkipPace
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Who counts as flicking through the queue, and that nobody else waits. */
class SkipPaceTest {

    private var clock = 1_000_000L
    private fun pace(settleMs: Long = 50) = SkipPace(settleMs = settleMs, now = { clock })

    @Test
    fun `a listener who isn't skipping is never held`() = runBlocking {
        val pace = pace()
        val started = System.currentTimeMillis()
        pace.settle()
        pace.noteSkip()
        pace.noteSkip()
        assertFalse(pace.isBrowsing())
        pace.settle()
        assertTrue(System.currentTimeMillis() - started < 40)
    }

    @Test
    fun `three skips in a few seconds is browsing`() {
        val pace = pace()
        repeat(3) { pace.noteSkip(); clock += 2_000 }
        assertTrue(pace.isBrowsing())
    }

    @Test
    fun `skips spread out are not browsing, and neither are old ones`() {
        val pace = pace()
        repeat(3) { pace.noteSkip(); clock += 6_000 }
        assertFalse(pace.isBrowsing())

        val another = pace()
        repeat(3) { another.noteSkip() }
        assertTrue(another.isBrowsing())
        clock += SkipPace.BROWSING_WINDOW_MS + 1
        assertFalse(another.isBrowsing())
    }

    @Test
    fun `settle returns once the skipping has stopped for long enough`() = runBlocking {
        // Real time here: the clock above only moves when told to.
        val pace = SkipPace(settleMs = 120)
        repeat(3) { pace.noteSkip() }
        val started = System.currentTimeMillis()
        pace.settle()
        assertTrue(System.currentTimeMillis() - started >= 100)
    }
}
