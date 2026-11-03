package com.music.bitchord.desktop

import com.music.bitchord.data.model.SPOTIFY_MISSING_PREFIX
import com.music.bitchord.data.model.SPOTIFY_PENDING_PREFIX
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.isUnresolvedSpotify
import com.music.bitchord.data.spotify.SPOTIFY_PAGE_PREFIX
import com.music.bitchord.data.spotify.SpotifyImporter
import com.music.bitchord.data.spotify.SpotifyLibrary
import com.music.bitchord.data.spotify.SpotifyPlaylist
import com.music.bitchord.data.spotify.SpotifyTrack
import com.music.bitchord.data.spotify.SpotifyTrackMatcher
import com.music.bitchord.data.spotify.TrackMatch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Collections

/** The Library row that opens the Spotify account's playlists; the phone's browse id. */
internal const val SPOTIFY_LIBRARY_ID = "app:spotify"

/** The Library grid's title for those playlists, which is also how the grid is told apart. */
internal const val SPOTIFY_SHELF = "Spotify"

/** Matches looked up at once; the phone's figure, gentle enough on YouTube Music's search. */
private const val MATCH_PARALLELISM = 4

/** A card on the Spotify grid, opening a [DesktopCollection] of its tracks. */
internal fun SpotifyPlaylist.toShelfItem() = ShelfItem(
    title = name,
    subtitle = owner ?: SPOTIFY_SHELF,
    thumbnailUrl = imageUrl,
    videoId = null,
    browseId = SPOTIFY_PAGE_PREFIX + id,
)

/** A row for a Spotify track whose YouTube Music version has not been found yet. */
internal fun SpotifyTrack.asPendingSong() = Song(
    videoId = SPOTIFY_PENDING_PREFIX + id,
    title = title,
    artist = artist,
    thumbnailUrl = imageUrl,
    durationText = durationMs.takeIf { it > 0 }?.let { ms -> "%d:%02d".format(ms / 60000, ms / 1000 % 60) },
    albumName = album,
)

internal object DesktopSpotifyCache {
    private const val KEY_MATCHES = "spotify_track_matches_v1"
    private const val MAX_MATCHES = 2000
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val persistence = DesktopPersistence()

    @Serializable
    private data class CachedMatch(
        val videoId: String,
        val title: String,
        val artist: String,
        val thumbnailUrl: String? = null,
        val durationText: String? = null,
        val artistId: String? = null,
        val albumId: String? = null,
        val albumName: String? = null,
        val isExplicit: Boolean? = null,
    ) {
        fun toSong(): Song = Song(
            videoId = videoId,
            title = title,
            artist = artist,
            thumbnailUrl = thumbnailUrl,
            durationText = durationText,
            artistId = artistId,
            albumId = albumId,
            albumName = albumName,
            isExplicit = isExplicit,
        )

        companion object {
            fun fromSong(song: Song): CachedMatch = CachedMatch(
                videoId = song.videoId,
                title = song.title,
                artist = song.artist,
                thumbnailUrl = song.thumbnailUrl,
                durationText = song.durationText,
                artistId = song.artistId,
                albumId = song.albumId,
                albumName = song.albumName,
                isExplicit = song.isExplicit,
            )
        }
    }

    private val matches: MutableMap<String, Song> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Song>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Song>): Boolean =
                size > MAX_MATCHES
        },
    )

    @Volatile
    private var loaded = false

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val raw = persistence.string(KEY_MATCHES)
            if (raw.isNotBlank()) {
                runCatching {
                    val decoded = json.decodeFromString<Map<String, CachedMatch>>(raw)
                    decoded.forEach { (id, cached) -> matches[id] = cached.toSong() }
                }
            }
            loaded = true
        }
    }

    fun getMatch(trackId: String): Song? {
        ensureLoaded()
        return matches[trackId]
    }

    fun putMatch(trackId: String, song: Song) {
        if (trackId.isBlank() || song.isUnresolvedSpotify) return
        ensureLoaded()
        matches[trackId] = song
    }

    fun flush() {
        if (!loaded) return
        val snapshot = synchronized(matches) {
            matches.mapValues { (_, song) -> CachedMatch.fromSong(song) }
        }
        runCatching {
            persistence.saveString(KEY_MATCHES, json.encodeToString(snapshot))
        }
    }
}

private val spotifyTrackMatcher = SpotifyTrackMatcher(SpotifyImporter::lookUp)

/**
 * Fills a Spotify playlist page the way the phone's `MainViewModel` does: Spotify's tracks as each
 * page of them arrives (immediately resolved from [DesktopSpotifyCache] when known), then every
 * remaining row swapped in place for its YouTube Music match with retry/backoff via
 * [SpotifyTrackMatcher], or marked as having none. [update] applies a change to the page if it is
 * still the one open.
 */
internal suspend fun loadSpotifyPlaylist(
    playlistId: String,
    update: ((DesktopCollection) -> DesktopCollection) -> Unit,
) = coroutineScope {
    launch {
        // The library card only had a thumbnail.
        runCatching { SpotifyLibrary.cover(playlistId) }.getOrNull()
            ?.let { cover -> update { it.copy(thumbnailUrl = cover) } }
    }
    val tracks = SpotifyLibrary.tracks(playlistId) { soFar ->
        val initialRows = soFar.map { track ->
            DesktopSpotifyCache.getMatch(track.id)
                ?.let { cached -> cached.copy(thumbnailUrl = cached.thumbnailUrl ?: track.imageUrl) }
                ?: track.asPendingSong()
        }
        update { it.copy(songs = initialRows, loading = false) }
    }
    check(tracks.isNotEmpty()) { "This Spotify playlist has no tracks." }
    val gate = Semaphore(MATCH_PARALLELISM)
    var dirty = false
    tracks.forEachIndexed { index, track ->
        val cached = DesktopSpotifyCache.getMatch(track.id)
        if (cached != null) {
            val row = cached.copy(thumbnailUrl = cached.thumbnailUrl ?: track.imageUrl)
            update { page ->
                if (index < page.songs.size) {
                    page.copy(songs = page.songs.toMutableList().also { it[index] = row })
                } else {
                    page
                }
            }
            return@forEachIndexed
        }
        launch {
            gate.withPermit {
                val outcome = spotifyTrackMatcher.match(track)
                val match = (outcome as? TrackMatch.Found)?.song
                if (match != null && !match.isUnresolvedSpotify) {
                    DesktopSpotifyCache.putMatch(track.id, match)
                    dirty = true
                }
                val row = match?.copy(thumbnailUrl = match.thumbnailUrl ?: track.imageUrl)
                    ?: track.asPendingSong().copy(videoId = SPOTIFY_MISSING_PREFIX + track.id)
                update { page ->
                    if (index < page.songs.size) {
                        page.copy(songs = page.songs.toMutableList().also { it[index] = row })
                    } else {
                        page
                    }
                }
            }
        }
    }
    if (dirty) {
        DesktopSpotifyCache.flush()
    }
}
