package com.music.bitchord.data.spotify

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Where the copy of the Spotify library stands. */
sealed interface SpotifySyncState {
    data object Idle : SpotifySyncState

    /** Playlist [index] of [total] is being read and matched. */
    data class Running(val index: Int, val total: Int, val playlist: String) : SpotifySyncState

    /** The last run finished: [added] songs new to this device, [unmatched] with no YouTube Music version. */
    data class Done(val playlists: Int, val added: Int, val unmatched: Int) : SpotifySyncState

    data class Failed(val message: String) : SpotifySyncState
}

/**
 * Keeps a copy of the Spotify library on this device: every playlist (and
 * Liked Songs) as a playlist of its YouTube Music matches, which is what
 * actually plays.
 *
 * Re-running is cheap and is how new songs arrive — a song already matched is
 * reused from the copy, so only what Spotify gained is looked up. The work runs
 * in its own scope, so leaving the Spotify page does not stop it.
 */
object SpotifySync {
    /** How many Spotify tracks are looked up on YouTube Music at once. */
    private const val MATCH_PARALLELISM = 6

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<SpotifySyncState>(SpotifySyncState.Idle)
    val state: StateFlow<SpotifySyncState> = _state.asStateFlow()

    val running: Boolean get() = _state.value is SpotifySyncState.Running

    /** Mirrors [playlists] in the background; ignored while a run is already going. */
    fun start(playlists: List<SpotifyPlaylist>) {
        if (running || playlists.isEmpty()) return
        _state.value = SpotifySyncState.Running(0, playlists.size, playlists.first().name)
        scope.launch {
            var added = 0
            var unmatched = 0
            try {
                playlists.forEachIndexed { index, playlist ->
                    _state.value = SpotifySyncState.Running(index + 1, playlists.size, playlist.name)
                    val result = syncPlaylist(playlist)
                    added += result.added
                    unmatched += result.unmatched
                }
                _state.value = SpotifySyncState.Done(playlists.size, added, unmatched)
            } catch (e: CancellationException) {
                _state.value = SpotifySyncState.Idle
                throw e
            } catch (e: Exception) {
                _state.value = SpotifySyncState.Failed(e.message.orEmpty())
            }
        }
    }

    fun clearResult() {
        if (!running) _state.value = SpotifySyncState.Idle
    }

    fun isImported(spotifyId: String): Boolean = LocalPlaylistStore.findBySpotifyId(spotifyId) != null

    private class Result(val added: Int, val unmatched: Int)

    private suspend fun syncPlaylist(playlist: SpotifyPlaylist): Result {
        val tracks = SpotifyLibrary.tracks(playlist.id)
        val known = LocalPlaylistStore.findBySpotifyId(playlist.id)
            ?.let { local -> local.spotifyTrackIds.zip(local.songs).toMap() }
            .orEmpty()
        val gate = Semaphore(MATCH_PARALLELISM)
        val matched: List<Song?> = coroutineScope {
            tracks.map { track ->
                async {
                    known[track.id] ?: gate.withPermit {
                        runCatching { SpotifyImporter.matchTrack(track) }.getOrNull()
                            ?.let { it.copy(thumbnailUrl = it.thumbnailUrl ?: track.imageUrl) }
                    }
                }
            }.awaitAll()
        }
        val kept = tracks.indices.filter { matched[it] != null }
        LocalPlaylistStore.saveSpotifyPlaylist(
            spotifyId = playlist.id,
            title = playlist.name,
            songs = kept.map { matched[it]!! },
            trackIds = kept.map { tracks[it].id },
        )
        return Result(
            added = kept.count { tracks[it].id !in known },
            unmatched = tracks.size - kept.size,
        )
    }
}
