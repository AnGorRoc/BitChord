package com.music.bitchord.download

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.lyrics.LyricsRepository
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.lyrics.toEnhancedLrc
import com.music.bitchord.data.lyrics.toLrc
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The lyrics to write into a track about to be saved, as LRC text.
 */
object LyricsTag {

    private const val TAG = "BitChord"

    class Embeddable(val plain: String, val enhanced: String?)

    suspend fun forTrack(
        track: Song,
        sources: Set<LyricsSource>,
        order: List<LyricsSource> = LyricsSource.offered,
        prioritizeSyllableSync: Boolean = false,
    ): Embeddable? {
        if (sources.isEmpty()) return null

        val durationMs = track.durationMillis()
        if (durationMs <= 0L) {
            TrackLog.d(TAG, "no duration for ${track.videoId}; looking up lyrics by title and artist")
        }

        val found = try {
            withTimeoutOrNull(LOOKUP_MS) {
                LyricsRepository.lyrics(
                    videoId = track.videoId,
                    title = track.title,
                    artist = track.artist,
                    durationMs = durationMs,
                    album = track.albumName,
                    sources = sources,
                    order = order,
                    prioritizeSyllableSync = prioritizeSyllableSync,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TrackLog.d(TAG, "no lyrics for ${track.videoId}: ${e.message}")
            return null
        } ?: return null

        if (found.lines.none { it.text.isNotBlank() }) return null

        val lrc = found.lines.toLrc()
        if (lrc.length > MAX_LRC_CHARS) {
            TrackLog.w(TAG, "lyrics for ${track.videoId} are ${lrc.length} chars; not embedding")
            return null
        }
        if (lrc.isBlank()) return null
        val enhanced = found.lines.toEnhancedLrc().takeIf {
            it.isNotBlank() && it.length <= MAX_LRC_CHARS * 2
        }
        TrackLog.d(
            TAG,
            "embedding ${found.source.label} lyrics for ${track.videoId}" +
                if (enhanced != null) " (word-synced)" else "",
        )
        return Embeddable(plain = lrc, enhanced = enhanced)
    }

    private const val MAX_LRC_CHARS = 64_000
    private const val LOOKUP_MS = 30_000L
}
