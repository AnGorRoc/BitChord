package com.music.bitchord

import com.music.bitchord.data.settings.LosslessCacheLevel
import com.music.bitchord.data.settings.LosslessCachePolicy
import com.music.bitchord.data.settings.LosslessCachePolicy.Entry
import com.music.bitchord.data.settings.LosslessTier
import com.music.bitchord.data.sources.StreamFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which lossless files the song cache keeps, and how its own budget is held.
 * See [LosslessCachePolicy]: a 24-bit master can outweigh the whole song cache,
 * so the rules have to be right about both the level and the budget.
 */
class LosslessCachePolicyTest {

    private val mb = 1024L * 1024

    @Test
    fun `past 16-bit or past 48 kHz is hi-res`() {
        assertEquals(LosslessTier.CD, LosslessTier.of(StreamFormat(codec = "flac", bitDepth = 16, sampleRateHz = 44_100)))
        assertEquals(LosslessTier.CD, LosslessTier.of(StreamFormat(codec = "flac", bitDepth = 16, sampleRateHz = 48_000)))
        assertEquals(LosslessTier.HI_RES, LosslessTier.of(StreamFormat(codec = "flac", bitDepth = 24, sampleRateHz = 44_100)))
        assertEquals(LosslessTier.HI_RES, LosslessTier.of(StreamFormat(codec = "flac", bitDepth = 16, sampleRateHz = 96_000)))
    }

    @Test
    fun `with no format stated the bitrate decides, and nothing at all reads as hi-res`() {
        assertEquals(LosslessTier.CD, LosslessTier.of(StreamFormat(codec = "flac", kbps = 900)))
        assertEquals(LosslessTier.HI_RES, LosslessTier.of(StreamFormat(codec = "flac", kbps = 5_900)))
        assertEquals(LosslessTier.HI_RES, LosslessTier.of(null))
    }

    @Test
    fun `each level keeps what it says`() {
        assertFalse(LosslessCachePolicy.keeps(LosslessCacheLevel.OFF, LosslessTier.CD))
        assertTrue(LosslessCachePolicy.keeps(LosslessCacheLevel.CD, LosslessTier.CD))
        assertFalse(LosslessCachePolicy.keeps(LosslessCacheLevel.CD, LosslessTier.HI_RES))
        assertTrue(LosslessCachePolicy.keeps(LosslessCacheLevel.ALL, LosslessTier.HI_RES))
        // An entry recorded before the tier was: not known to be CD quality.
        assertFalse(LosslessCachePolicy.keeps(LosslessCacheLevel.CD, null))
    }

    @Test
    fun `over budget, the least recently used go first`() {
        val entries = listOf(
            Entry("new", 200 * mb, lastUsedMs = 3_000),
            Entry("old", 200 * mb, lastUsedMs = 1_000),
            Entry("mid", 200 * mb, lastUsedMs = 2_000),
        )
        assertEquals(listOf("old"), LosslessCachePolicy.overBudget(entries, limitBytes = 450 * mb))
        assertEquals(listOf("old", "mid"), LosslessCachePolicy.overBudget(entries, limitBytes = 300 * mb))
        assertTrue(LosslessCachePolicy.overBudget(entries, limitBytes = 600 * mb).isEmpty())
    }

    @Test
    fun `lossless gets room on top of the song cache, never out of it`() {
        assertEquals(512 * mb, LosslessCachePolicy.evictorCeiling(512 * mb, LosslessCacheLevel.OFF, 1024 * mb))
        assertEquals(1536 * mb, LosslessCachePolicy.evictorCeiling(512 * mb, LosslessCacheLevel.CD, 1024 * mb))
        assertEquals(Long.MAX_VALUE, LosslessCachePolicy.evictorCeiling(Long.MAX_VALUE, LosslessCacheLevel.ALL, 1024 * mb))
    }
}
