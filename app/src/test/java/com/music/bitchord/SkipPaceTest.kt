package com.music.bitchord

import com.music.bitchord.data.sources.SkipPace
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who counts as flicking through the queue, and that nobody else waits. Time is
 * the test scheduler's, so a wait of a second is a second of virtual time and
 * nothing here depends on how fast the machine is.
 */
class SkipPaceTest {

    private fun TestScope.pace(settleMs: Long = 50) = SkipPace(settleMs = settleMs, now = { currentTime })

    @Test
    fun `a listener who isn't skipping is never held`() = runTest {
        val pace = pace()
        pace.settle()
        pace.noteSkip()
        pace.noteSkip()
        assertFalse(pace.isBrowsing())
        pace.settle()
        assertEquals(0L, currentTime)
    }

    @Test
    fun `three skips in a few seconds is browsing`() = runTest {
        val pace = pace()
        repeat(3) { pace.noteSkip(); delay(2_000) }
        assertTrue(pace.isBrowsing())
    }

    @Test
    fun `skips spread out are not browsing, and neither are old ones`() = runTest {
        val spread = pace()
        repeat(3) { spread.noteSkip(); delay(6_000) }
        assertFalse(spread.isBrowsing())

        val another = pace()
        repeat(3) { another.noteSkip() }
        assertTrue(another.isBrowsing())
        delay(SkipPace.BROWSING_WINDOW_MS + 1)
        assertFalse(another.isBrowsing())
    }

    @Test
    fun `settle returns once the skipping has stopped for long enough`() = runTest {
        val pace = pace(settleMs = 120)
        repeat(3) { pace.noteSkip() }
        val started = currentTime

        pace.settle()

        assertEquals(120L, currentTime - started)
    }

    @Test
    fun `settle does not wait for ever on a listener who never stops`() = runTest {
        val pace = pace(settleMs = 1_500)
        val skipping = launch {
            while (true) { pace.noteSkip(); delay(500) }
        }
        delay(1_100) // three skips in: browsing
        val started = currentTime

        pace.settle()

        val waited = currentTime - started
        skipping.cancel()
        assertTrue("waited $waited ms", waited in SkipPace.MAX_WAIT_MS..(SkipPace.MAX_WAIT_MS + 1_500))
    }
}
