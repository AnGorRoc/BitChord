package com.music.bitchord.data.spotify

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SpotifyTrackMatcherTest {
    private val track = SpotifyTrack(id = "t1", title = "Song", artist = "Artist", album = null, durationMs = 180_000, imageUrl = "cover")
    private val song = Song(videoId = "abcdefghijk", title = "Song", artist = "Artist", thumbnailUrl = null)
    private val waits = mutableListOf<Long>()

    private fun matcher(lookUp: suspend (SpotifyTrack) -> Song?) =
        SpotifyTrackMatcher(lookUp, parallelism = 2, waitsMs = listOf(10L, 20L), pause = { waits += it })

    @Test
    fun `a match carries the Spotify cover when the song has none`() = runBlocking {
        val outcome = matcher { song }.match(track)

        assertEquals(TrackMatch.Found(song.copy(thumbnailUrl = "cover")), outcome)
    }

    @Test
    fun `no such song is an answer about the track`() = runBlocking {
        assertEquals(TrackMatch.NotFound, matcher { null }.match(track))
        assertTrue(waits.isEmpty())
    }

    @Test
    fun `a lookup that fails is tried again before it is given up`() = runBlocking {
        var calls = 0
        val outcome = matcher { if (++calls < 3) throw IOException("reset") else song }.match(track)

        assertTrue(outcome is TrackMatch.Found)
        assertEquals(3, calls)
        assertEquals(listOf(10L, 20L), waits)
    }

    @Test
    fun `a lookup that keeps failing is reported as failed, not as missing`() = runBlocking {
        val outcome = matcher { throw IOException("HTTP 429") }.match(track)

        assertTrue(outcome is TrackMatch.Failed)
        assertEquals("HTTP 429", (outcome as TrackMatch.Failed).cause.message)
    }

    @Test
    fun `cancellation is not turned into a result`() {
        var thrown: Throwable? = null
        try {
            runBlocking { matcher { throw CancellationException("gone") }.match(track) }
        } catch (e: CancellationException) {
            thrown = e
        }
        assertTrue(thrown is CancellationException)
        assertTrue(waits.isEmpty())
    }
}
