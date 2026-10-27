package com.music.bitchord

import com.music.bitchord.playback.LosslessStallWatch
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a lossless stream counts as not keeping up — see [LosslessStallWatch].
 * Samples a second apart, as the service feeds them.
 */
class LosslessStallWatchTest {

    /** Plays [seconds] of audio from [from], the buffer ahead moving by [aheadStep] a second. */
    private fun LosslessStallWatch.play(
        from: Long,
        seconds: Int,
        startAhead: Long,
        aheadStep: Long,
        clockFrom: Long = from,
    ): Boolean {
        var lowered = false
        for (i in 0..seconds) {
            val t = i * 1_000L
            lowered = sample(
                nowMs = clockFrom + t,
                positionMs = from + t,
                aheadMs = startAhead + aheadStep * i,
                playing = true,
                stalled = false,
                loadedToEnd = false,
            ) || lowered
        }
        return lowered
    }

    @Test
    fun `a buffer that stays ahead is left alone`() {
        assertFalse(LosslessStallWatch().play(from = 0, seconds = 60, startAhead = 30_000, aheadStep = 0))
    }

    @Test
    fun `a low buffer that is draining lowers the quality`() {
        assertTrue(LosslessStallWatch().play(from = 0, seconds = 20, startAhead = 14_000, aheadStep = -600))
    }

    @Test
    fun `a low buffer that is still filling is left alone`() {
        assertFalse(LosslessStallWatch().play(from = 0, seconds = 30, startAhead = 2_000, aheadStep = 200))
    }

    @Test
    fun `nothing counts during the opening grace`() {
        val watch = LosslessStallWatch()
        assertFalse(watch.sample(0, 0, 0, playing = false, stalled = true, loadedToEnd = false))
        assertFalse(watch.play(from = 0, seconds = 7, startAhead = 6_000, aheadStep = -800))
    }

    @Test
    fun `a stall mid-song lowers the quality at once`() {
        val watch = LosslessStallWatch()
        watch.play(from = 0, seconds = 20, startAhead = 30_000, aheadStep = 0)
        assertTrue(watch.sample(21_000, 20_500, 0, playing = false, stalled = true, loadedToEnd = false))
    }

    @Test
    fun `a seek starts the grace over`() {
        val watch = LosslessStallWatch()
        watch.play(from = 0, seconds = 20, startAhead = 30_000, aheadStep = 0)
        // Jumped to 2:00; the buffer there is empty and filling.
        assertFalse(watch.sample(21_000, 120_000, 0, playing = false, stalled = true, loadedToEnd = false))
    }

    @Test
    fun `a track loaded to its end never lowers`() {
        val watch = LosslessStallWatch()
        watch.play(from = 0, seconds = 20, startAhead = 14_000, aheadStep = -600)
        assertFalse(watch.sample(21_000, 21_000, 1_000, playing = true, stalled = false, loadedToEnd = true))
    }

    @Test
    fun `a pause is not a seek`() {
        val watch = LosslessStallWatch()
        watch.play(from = 0, seconds = 20, startAhead = 30_000, aheadStep = 0)
        for (s in 21..40) watch.sample(s * 1_000L, 20_000, 30_000, playing = false, stalled = false, loadedToEnd = false)
        // Resumed at the same spot, past the grace, and the buffer then stalls.
        assertTrue(watch.sample(41_000, 20_000, 0, playing = false, stalled = true, loadedToEnd = false))
    }
}
