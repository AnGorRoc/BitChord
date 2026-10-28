package com.music.bitchord.playback

import com.music.bitchord.playback.StandbyLock.Correction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The arithmetic of locking a silent standby onto the playing player; the players themselves are not needed. */
class StandbyLockTest {

    // ── reading the readout ───────────────────────────────────────────────

    @Test
    fun `too few readings are not a settled one`() {
        assertNull(StandbyLock.settledError(List(StandbyLock.STABLE_SAMPLES - 1) { 10L }))
    }

    @Test
    fun `readings that agree settle on their median`() {
        val readings = List(StandbyLock.STABLE_SAMPLES) { 20L + it % 3 }

        assertEquals(21L, StandbyLock.settledError(readings))
    }

    @Test
    fun `readings that still jump are not settled`() {
        val readings = List(StandbyLock.STABLE_SAMPLES) { if (it % 2 == 0) 10L else 300L }

        assertNull(StandbyLock.settledError(readings))
    }

    @Test
    fun `only the latest readings count toward settling`() {
        val early = List(10) { 280L }
        val late = List(StandbyLock.STABLE_SAMPLES) { 12L }

        assertEquals(12L, StandbyLock.settledError(early + late))
    }

    @Test
    fun `the densest cluster ignores a minority of jumps that a median would land on`() {
        // A base level at 10 ms, and a third of the readings 280 ms off it.
        val readings = List(20) { 10L + it % 4 } + List(10) { 290L + it % 3 }

        val center = StandbyLock.densestCluster(readings)

        assertTrue("landed on the jumps: $center", center in 10L..13L)
    }

    @Test
    fun `a single reading is its own cluster`() {
        assertEquals(42L, StandbyLock.densestCluster(listOf(42L)))
    }

    // ── what to do about it ───────────────────────────────────────────────

    @Test
    fun `an error inside the tolerance is left alone`() {
        assertEquals(Correction.None, StandbyLock.correctionFor(StandbyLock.TOLERANCE_MS, 1f))
        assertEquals(Correction.None, StandbyLock.correctionFor(-StandbyLock.TOLERANCE_MS, 1f))
    }

    @Test
    fun `an error past the nudge limit is too far to take up`() {
        assertEquals(Correction.TooFar, StandbyLock.correctionFor(StandbyLock.NUDGE_MAX_ERROR_MS + 1, 1f))
        assertEquals(Correction.TooFar, StandbyLock.correctionFor(-(StandbyLock.NUDGE_MAX_ERROR_MS + 1), 1f))
    }

    @Test
    fun `a standby that is ahead slows down and one that is behind speeds up`() {
        val ahead = StandbyLock.correctionFor(100, 1f) as Correction.Nudge
        val behind = StandbyLock.correctionFor(-100, 1f) as Correction.Nudge

        assertTrue(ahead.rate < 1f)
        assertTrue(behind.rate > 1f)
    }

    @Test
    fun `the nudge closes the error over its span`() {
        val nudge = StandbyLock.correctionFor(120, 1f) as Correction.Nudge

        // Running at `rate` for `span` moves the standby (1 - rate) * span relative to real time.
        val closed = (1f - nudge.rate) * nudge.spanMs
        assertEquals(120f, closed, 1f)
    }

    @Test
    fun `a small error is a gentle nudge, never shorter than the minimum span`() {
        val nudge = StandbyLock.correctionFor(20, 1f) as Correction.Nudge

        assertTrue(nudge.spanMs >= StandbyLock.NUDGE_MIN_SPAN_MS)
        assertTrue(1f - nudge.rate < 0.05f)
    }

    @Test
    fun `no nudge strays further than the rate limit from real time`() {
        val nudge = StandbyLock.correctionFor(StandbyLock.NUDGE_MAX_ERROR_MS, 1f) as Correction.Nudge

        assertTrue(nudge.rate >= 1f - StandbyLock.NUDGE_MAX_RATE - 0.0001f)
        assertTrue(nudge.rate <= 1f + StandbyLock.NUDGE_MAX_RATE + 0.0001f)
    }

    // ── what to carry to the next swap ────────────────────────────────────

    @Test
    fun `the carried latency moves most of the way to the newest reading`() {
        // A standby 100 ms behind means the seek should have aimed 100 ms further ahead.
        val next = StandbyLock.learnedLatencyMs(currentMs = 0, errorMs = -100, speed = 1f)

        assertEquals(70L, next)
    }

    @Test
    fun `the carried latency is wall time, so playback speed scales it`() {
        val atDouble = StandbyLock.learnedLatencyMs(currentMs = 0, errorMs = -100, speed = 2f)

        assertEquals(35L, atDouble)
    }

    @Test
    fun `the carried latency stays inside its bounds`() {
        assertEquals(StandbyLock.MAX_LATENCY_MS, StandbyLock.learnedLatencyMs(StandbyLock.MAX_LATENCY_MS, -1_000_000, 1f))
        assertEquals(-StandbyLock.MAX_EARLY_MS, StandbyLock.learnedLatencyMs(-StandbyLock.MAX_EARLY_MS, 1_000_000, 1f))
    }
}
