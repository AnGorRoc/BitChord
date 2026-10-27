package com.music.bitchord

import com.music.bitchord.data.settings.AudioQuality
import com.music.bitchord.data.settings.LosslessDataNotice
import com.music.bitchord.data.settings.LosslessDataNotice.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the "lossless on mobile data" notice appears, and how it retires.
 *
 * The rule the listener asked for is that it should say its piece and then
 * get out of the way — so most of these are about when it must *not* show.
 */
class LosslessDataNoticeTest {

    private fun due(
        streamingLossless: Boolean = true,
        metered: Boolean? = true,
        cellularCeiling: AudioQuality = AudioQuality.LOSSLESS,
        ceilingOverride: AudioQuality? = null,
        done: Boolean = false,
        shownThisSession: Boolean = false,
    ) = LosslessDataNotice.shouldShow(
        streamingLossless = streamingLossless,
        metered = metered,
        cellularCeiling = cellularCeiling,
        ceilingOverride = ceilingOverride,
        done = done,
        shownThisSession = shownThisSession,
    )

    @Test
    fun `lossless streaming on mobile data at the lossless ceiling is due`() {
        assertTrue(due())
    }

    @Test
    fun `not on Wi-Fi, nor while offline`() {
        assertFalse(due(metered = false))
        assertFalse(due(metered = null))
    }

    @Test
    fun `not when nothing lossless is streaming`() {
        assertFalse(due(streamingLossless = false))
    }

    @Test
    fun `not when the mobile-data ceiling is already below lossless`() {
        assertFalse(due(cellularCeiling = AudioQuality.HIGH))
        assertFalse(due(cellularCeiling = AudioQuality.MEDIUM))
        assertFalse(due(cellularCeiling = AudioQuality.LOW))
    }

    /** The watch caps the stream itself; the phone's setting isn't what's in effect. */
    @Test
    fun `not while something else is capping the stream`() {
        assertFalse(due(ceilingOverride = AudioQuality.MEDIUM))
    }

    @Test
    fun `once per session`() {
        assertFalse(due(shownThisSession = true))
    }

    @Test
    fun `never once retired`() {
        assertFalse(due(done = true))
    }

    @Test
    fun `acting on it retires it at once`() {
        assertEquals(0 to true, LosslessDataNotice.after(Outcome.ACTED, ignored = 0))
    }

    @Test
    fun `closing it retires it at once`() {
        assertEquals(1 to true, LosslessDataNotice.after(Outcome.CLOSED, ignored = 1))
    }

    @Test
    fun `ignored twice, it stops appearing`() {
        val (once, retiredOnce) = LosslessDataNotice.after(Outcome.IGNORED, ignored = 0)
        assertEquals(1, once)
        assertFalse(retiredOnce)

        val (twice, retiredTwice) = LosslessDataNotice.after(Outcome.IGNORED, ignored = once)
        assertEquals(LosslessDataNotice.IGNORES_BEFORE_RETIRING, twice)
        assertTrue(retiredTwice)
    }
}
