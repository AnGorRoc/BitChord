package com.music.bitchord

import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.AudioQuality
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.data.sources.StreamRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a track waits for lossless before its first note, and how a stream
 * that couldn't keep up holds lossless back — see
 * [SourceResolver.losslessFromStart] and [AppSettings.holdLossless].
 */
class LosslessFromStartTest {

    private val wifi = AppSettings.audioQualityWifi.value
    private val metered = AppSettings.meteredConnection.value
    private val fromStart = AppSettings.losslessFromStart.value

    @After
    fun restore() {
        AppSettings.audioQualityWifi.value = wifi
        AppSettings.meteredConnection.value = metered
        AppSettings.losslessFromStart.value = fromStart
        AppSettings.holdLossless(0)
    }

    @Test
    fun `waits only when lossless is what the connection asks for`() {
        AppSettings.meteredConnection.value = false
        AppSettings.losslessFromStart.value = true
        AppSettings.audioQualityWifi.value = AudioQuality.LOSSLESS
        assertTrue(SourceResolver.losslessFromStart())
        AppSettings.audioQualityWifi.value = AudioQuality.HIGH
        assertFalse(SourceResolver.losslessFromStart())
    }

    @Test
    fun `off by default`() {
        AppSettings.meteredConnection.value = false
        AppSettings.audioQualityWifi.value = AudioQuality.LOSSLESS
        AppSettings.losslessFromStart.value = false
        assertFalse(SourceResolver.losslessFromStart())
    }

    @Test
    fun `a held connection asks for lossy until the hold runs out`() {
        AppSettings.meteredConnection.value = false
        AppSettings.audioQualityWifi.value = AudioQuality.LOSSLESS
        AppSettings.holdLossless(60_000)
        assertEquals(StreamRequest.Best, SourceResolver.requestForNow())
        AppSettings.holdLossless(0)
        assertEquals(StreamRequest.Lossless, SourceResolver.requestForNow())
    }
}
