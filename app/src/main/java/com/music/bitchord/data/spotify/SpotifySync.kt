package com.music.bitchord.data.spotify

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.isUnresolvedSpotify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** Why a playlist, or the whole run, could not be imported. */
enum class SyncErrorKind { SIGNED_OUT, NETWORK, OTHER }

/**
 * What a failure means to the person: an expired sign-in (Spotify answering
 * 401/403, or no token to send) is theirs to fix, a dropped connection passes.
 */
fun syncErrorKind(e: Throwable): SyncErrorKind {
    val status = httpStatusOf(e)
    return when {
        e is SpotifySignInExpiredException ||
            e.message.orEmpty().contains("sign-in expired", ignoreCase = true) ||
            status == 401 || status == 403 -> SyncErrorKind.SIGNED_OUT
        e is IOException -> SyncErrorKind.NETWORK
        else -> SyncErrorKind.OTHER
    }
}

/** A playlist that did not make it, and why. */
data class SyncFailure(
    val playlist: SpotifyPlaylist,
    val kind: SyncErrorKind,
    val detail: String,
)

/** Where the copy of the Spotify library stands. */
sealed interface SpotifySyncState {
    data object Idle : SpotifySyncState

    /**
     * Playlist number [index] (1-based) of [total] is under way. [songsTotal]
     * is 0 while its tracks are still being read, after which [songsDone] of
     * them have been matched. [failures] are the playlists already given up on.
     */
    data class Running(
        val index: Int,
        val total: Int,
        val playlist: SpotifyPlaylist,
        val songsDone: Int,
        val songsTotal: Int,
        val failures: List<SyncFailure>,
    ) : SpotifySyncState {
        /** How far through the whole run, 0 to 1. */
        val fraction: Float
            get() {
                val inPlaylist = if (songsTotal > 0) songsDone.toFloat() / songsTotal else 0f
                return ((index - 1) + inPlaylist) / total
            }
    }

    /** The run ended: [imported] playlists saved, [added] songs new to this device, [unmatched] with no YouTube Music version. */
    data class Finished(
        val imported: Int,
        val added: Int,
        val unmatched: Int,
        val failures: List<SyncFailure>,
        val cancelled: Boolean,
    ) : SpotifySyncState

    /** The run could not go on at all, as when the sign-in has expired. */
    data class Aborted(val kind: SyncErrorKind, val detail: String) : SpotifySyncState
}

/**
 * Keeps a copy of the Spotify library on this device: every playlist (and
 * Liked Songs) as a playlist of its YouTube Music matches, which is what
 * actually plays.
 *
 * Re-running is cheap and is how new songs arrive — a song already matched is
 * reused from the copy, so only what Spotify gained is looked up. The work runs
 * in its own scope, so leaving the Spotify page does not stop it.
 *
 * One playlist failing does not stop the rest: it is recorded and offered for
 * [retryFailed]. Only a lost sign-in ends the run, since every later playlist
 * would fail the same way.
 */
object SpotifySync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var job: Job? = null

    private val _state = MutableStateFlow<SpotifySyncState>(SpotifySyncState.Idle)
    val state: StateFlow<SpotifySyncState> = _state.asStateFlow()

    val running: Boolean get() = _state.value is SpotifySyncState.Running

    /** Mirrors [playlists] in the background; ignored while a run is already going. */
    @Synchronized
    fun start(playlists: List<SpotifyPlaylist>) {
        if (running || playlists.isEmpty()) return
        _state.value = SpotifySyncState.Running(1, playlists.size, playlists.first(), 0, 0, emptyList())
        job = scope.launch { run(playlists) }
    }

    /** Stops the run; playlists already saved stay. */
    fun cancel() {
        job?.cancel()
    }

    /** Tries again just the playlists the last run gave up on. */
    fun retryFailed() {
        val failed = (_state.value as? SpotifySyncState.Finished)?.failures?.map { it.playlist }.orEmpty()
        if (failed.isNotEmpty()) start(failed)
    }

    /** Puts the result away once it has been read. */
    fun dismiss() {
        if (!running) _state.value = SpotifySyncState.Idle
    }

    private suspend fun run(playlists: List<SpotifyPlaylist>) {
        var imported = 0
        var added = 0
        var unmatched = 0
        val failures = mutableListOf<SyncFailure>()
        try {
            playlists.forEachIndexed { index, playlist ->
                _state.value = SpotifySyncState.Running(
                    index = index + 1,
                    total = playlists.size,
                    playlist = playlist,
                    songsDone = 0,
                    songsTotal = 0,
                    failures = failures.toList(),
                )
                try {
                    val result = syncPlaylist(playlist)
                    imported++
                    added += result.added
                    unmatched += result.unmatched
                    if (result.lookupFailures > 0) {
                        failures += SyncFailure(
                            playlist,
                            SyncErrorKind.NETWORK,
                            "${result.lookupFailures} songs could not be looked up",
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val kind = syncErrorKind(e)
                    if (kind == SyncErrorKind.SIGNED_OUT) {
                        _state.value = SpotifySyncState.Aborted(kind, e.message.orEmpty())
                        return
                    }
                    failures += SyncFailure(playlist, kind, e.message.orEmpty())
                }
            }
            _state.value = SpotifySyncState.Finished(imported, added, unmatched, failures.toList(), cancelled = false)
        } catch (e: CancellationException) {
            _state.value = SpotifySyncState.Finished(imported, added, unmatched, failures.toList(), cancelled = true)
            throw e
        }
    }

    private class Result(val added: Int, val unmatched: Int, val lookupFailures: Int)

    private val matcher = SpotifyTrackMatcher(SpotifyImporter::lookUp)

    private suspend fun syncPlaylist(playlist: SpotifyPlaylist): Result {
        val tracks = SpotifyLibrary.tracks(playlist.id)
        val known = LocalPlaylistStore.findBySpotifyId(playlist.id)?.matchedSongs().orEmpty()
        publishProgress(playlist, 0, tracks.size)
        val done = AtomicInteger(0)
        val outcomes: List<TrackMatch> = coroutineScope {
            tracks.map { track ->
                async {
                    val outcome = (known[track.id] ?: SpotifyCache.getMatch(track.id))
                        ?.let { TrackMatch.Found(it) }
                        ?: matcher.match(track)
                    if (outcome is TrackMatch.Found && !outcome.song.isUnresolvedSpotify) {
                        SpotifyCache.putMatch(track.id, outcome.song)
                    }
                    publishProgress(playlist, done.incrementAndGet(), tracks.size)
                    outcome
                }
            }.awaitAll()
        }
        val kept = tracks.indices.filter { outcomes[it] is TrackMatch.Found }
        val failed = outcomes.count { it is TrackMatch.Failed }
        // A song whose lookup failed is not kept out for good: it is simply not
        // saved yet, and the playlist is reported as incomplete so a retry
        // looks those up again.
        LocalPlaylistStore.saveSpotifyPlaylist(
            spotifyId = playlist.id,
            title = playlist.name,
            songs = kept.map { (outcomes[it] as TrackMatch.Found).song },
            trackIds = kept.map { tracks[it].id },
        )
        return Result(
            added = kept.count { tracks[it].id !in known },
            unmatched = outcomes.count { it is TrackMatch.NotFound },
            lookupFailures = failed,
        )
    }

    private fun publishProgress(playlist: SpotifyPlaylist, done: Int, total: Int) {
        _state.update { state ->
            if (state is SpotifySyncState.Running && state.playlist.id == playlist.id) {
                state.copy(songsDone = maxOf(state.songsDone, done), songsTotal = total)
            } else {
                state
            }
        }
    }
}
