package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpgradeSizingTest {
    private val mb = 1024L * 1024

    @Test
    fun `without the setting only the analysis head is fetched`() {
        val bytes = UpgradeSizing.headBytes(
            improveCachedSongs = false, keepsRendition = true,
            durationMs = 240_000, landedAtMs = 120_000, lengthBytes = { 30 * mb },
        )

        assertEquals(UpgradeSizing.ANALYSIS_HEAD_BYTES, bytes)
    }

    @Test
    fun `a copy the cache does not keep needs only the analysis head`() {
        val bytes = UpgradeSizing.headBytes(true, keepsRendition = false, durationMs = 240_000, landedAtMs = 120_000, lengthBytes = { 30 * mb })

        assertEquals(UpgradeSizing.ANALYSIS_HEAD_BYTES, bytes)
    }

    @Test
    fun `a kept copy is fetched up to where the swap landed, plus a margin`() {
        // Halfway through a 30 MB file.
        val bytes = UpgradeSizing.headBytes(true, true, durationMs = 240_000, landedAtMs = 120_000, lengthBytes = { 30 * mb })

        assertEquals(15 * mb + UpgradeSizing.HEAD_MARGIN_BYTES, bytes)
    }

    @Test
    fun `it never asks for more than the file holds`() {
        val bytes = UpgradeSizing.headBytes(true, true, durationMs = 240_000, landedAtMs = 239_000, lengthBytes = { 30 * mb })

        assertEquals(30 * mb, bytes)
    }

    @Test
    fun `it never asks for less than the analysis head`() {
        val bytes = UpgradeSizing.headBytes(true, true, durationMs = 240_000, landedAtMs = 1_000, lengthBytes = { 30 * mb })

        assertEquals(UpgradeSizing.ANALYSIS_HEAD_BYTES, bytes)
    }

    @Test
    fun `an unknown length or duration falls back to the analysis head`() {
        assertEquals(UpgradeSizing.ANALYSIS_HEAD_BYTES, UpgradeSizing.headBytes(true, true, 240_000, 120_000) { 0L })
        assertEquals(UpgradeSizing.ANALYSIS_HEAD_BYTES, UpgradeSizing.headBytes(true, true, 0, 120_000) { 30 * mb })
    }

    @Test
    fun `the length is not asked when the setting makes it moot`() {
        var asked = false
        UpgradeSizing.headBytes(false, true, 240_000, 120_000) { asked = true; 30 * mb }

        assertTrue(!asked)
    }

    @Test
    fun `the alignment head scales with the stated bitrate within its bounds`() {
        val cd = UpgradeSizing.alignmentHeadBytes(1_000)
        val master = UpgradeSizing.alignmentHeadBytes(9_000)
        val tiny = UpgradeSizing.alignmentHeadBytes(8)

        assertTrue(cd in UpgradeSizing.HEADER_BYTES..UpgradeSizing.ALIGN_MAX_HEAD_BYTES)
        assertEquals(UpgradeSizing.ALIGN_MAX_HEAD_BYTES, master)
        assertEquals(UpgradeSizing.HEADER_BYTES, tiny)
    }

    @Test
    fun `without a stated bitrate the alignment head is the analysis head`() {
        assertEquals(UpgradeSizing.ANALYSIS_HEAD_BYTES, UpgradeSizing.alignmentHeadBytes(null))
    }
}
