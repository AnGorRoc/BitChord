package com.music.bitchord.data.spotify

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** What looking a Spotify track up on YouTube Music came to. */
sealed interface TrackMatch {
    /** The YouTube Music song that is the track. */
    data class Found(val song: Song) : TrackMatch

    /** YouTube Music was asked and has nothing that matches. An answer about the track. */
    data object NotFound : TrackMatch

    /**
     * YouTube Music could not be asked, or refused. An answer about the
     * connection, not the track: it must not be recorded as a song that does
     * not exist.
     */
    data class Failed(val cause: Throwable) : TrackMatch
}

/**
 * Looks Spotify tracks up on YouTube Music, a few at a time, and tells "there
 * is no such song" from "the lookup did not work".
 *
 * The two used to be one `null`, so a dropped connection or a rate limit in the
 * middle of an import silently removed songs from the copy. A lookup that
 * throws is tried again after each of [waitsMs] before it is given up as
 * [TrackMatch.Failed]; the wait is spent holding its place among the
 * [parallelism] lookups, so a service pushing back slows every one of them.
 *
 * [lookUp] must throw when the search could not be made and return null only
 * when it was made and found nothing. [pause] is a parameter so tests need not
 * wait.
 */
class SpotifyTrackMatcher(
    private val lookUp: suspend (SpotifyTrack) -> Song?,
    parallelism: Int = DEFAULT_PARALLELISM,
    private val waitsMs: List<Long> = DEFAULT_WAITS_MS,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    private val gate = Semaphore(parallelism)

    suspend fun match(track: SpotifyTrack): TrackMatch = gate.withPermit { lookUpWithRetry(track) }

    private suspend fun lookUpWithRetry(track: SpotifyTrack): TrackMatch {
        var attempt = 0
        while (true) {
            try {
                val song = lookUp(track) ?: return TrackMatch.NotFound
                return TrackMatch.Found(song.copy(thumbnailUrl = song.thumbnailUrl ?: track.imageUrl))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (attempt >= waitsMs.size) return TrackMatch.Failed(e)
                pause(waitsMs[attempt++])
            }
        }
    }

    companion object {
        /** How many Spotify tracks are looked up on YouTube Music at once. */
        const val DEFAULT_PARALLELISM = 6
        val DEFAULT_WAITS_MS = listOf(1_500L, 4_000L)
    }
}
