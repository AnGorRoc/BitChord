package com.music.bitchord.data.spotify

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.MonotonicClock
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.PlaylistPrivacy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/** The end of an export, for the page to tell the person about. */
data class ExportEvent(val title: String, val state: ExportState)

/**
 * [PlaylistUploader]'s ledger, kept in its own preferences so the playlists
 * themselves are untouched.
 *
 * Each entry names the account it was written for: what one account was sent
 * says nothing about another's, and carrying on into a playlist the signed-in
 * account does not own would only fail. An entry written before accounts were
 * recorded is taken as the current account's. Reads are answered from memory,
 * since [isExported][LocalPlaylistExporter.isExported] is asked while a list
 * is being drawn.
 */
object YouTubeExportLedger : ExportLedger {
    private lateinit var prefs: SharedPreferences

    /** Who is signed in; set by [init], replaced in tests. */
    internal var account: () -> String? = { null }

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Optional>()
    private class Optional(val entry: LedgerEntry?, val account: String?)

    private val _changes = MutableSharedFlow<Pair<String, LedgerEntry>>(extraBufferCapacity = 64)
    /** Each batch as it is recorded: the local playlist's id and what is now on YouTube for it. */
    val changes: SharedFlow<Pair<String, LedgerEntry>> = _changes.asSharedFlow()

    fun init(context: Context, authStore: com.music.bitchord.auth.AuthStore) {
        prefs = context.getSharedPreferences("bitchord_youtube_export", Context.MODE_PRIVATE)
        account = { authStore.activeAccountId }
    }

    override fun get(localId: String): LedgerEntry? {
        if (!this::prefs.isInitialized) return null
        val held = cache.getOrPut(localId) { read(localId) }
        return held.entry?.takeIf { held.account == null || held.account == account() }
    }

    private fun read(localId: String): Optional {
        val raw = prefs.getString(localId, null) ?: return Optional(null, null)
        return runCatching {
            val obj = JSONObject(raw)
            val ids = obj.getJSONArray("ids")
            Optional(
                LedgerEntry(obj.getString("yt"), List(ids.length()) { ids.getString(it) }.toSet()),
                obj.optString("acct").takeIf { it.isNotBlank() },
            )
        }.getOrElse { Optional(null, null) }
    }

    override fun put(localId: String, entry: LedgerEntry) {
        if (!this::prefs.isInitialized) return
        val owner = account()
        val obj = JSONObject()
            .put("yt", entry.youtubeId)
            .put("ids", JSONArray(entry.exported.toList()))
        owner?.let { obj.put("acct", it) }
        prefs.edit().putString(localId, obj.toString()).apply()
        cache[localId] = Optional(entry, owner)
        _changes.tryEmit(localId to entry)
    }

    override fun remove(localId: String) {
        cache.remove(localId)
        if (this::prefs.isInitialized) prefs.edit().remove(localId).apply()
    }
}

/**
 * Exports the playlists kept on this device to the signed-in YouTube Music
 * account, one after another.
 *
 * One at a time on purpose: the limits are the account's, not a playlist's, so
 * two exports in parallel would only spend them twice as fast. Between
 * playlists there is a cool-down on top of [PlaylistUploader]'s pace, and if
 * YouTube says to slow down the rest of the queue is dropped rather than
 * making it worse. The work runs in its own scope, so closing the menu or the
 * page does not stop it.
 */
class ExportQueue(
    private val uploader: PlaylistUploader,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /** Quiet time between one playlist and the next. */
    private val cooldownMs: Long = COOLDOWN_MS,
    /** Added to the cool-down so that many devices do not fall into step. */
    private val extraRestMs: () -> Long = { Random.nextLong(0, 5_000) },
) {
    private val lock = Any()
    private val queue = ArrayDeque<LocalPlaylist>()
    private var worker: Job? = null
    private var current: Pair<String, Deferred<ExportState>>? = null

    /** Taken off the queue and resting before its turn, so that [cancel] can still find it. */
    private var cooling: LocalPlaylist? = null

    private val _states = MutableStateFlow<Map<String, ExportState>>(emptyMap())
    val states: StateFlow<Map<String, ExportState>> = _states.asStateFlow()

    private val _events = MutableSharedFlow<ExportEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<ExportEvent> = _events.asSharedFlow()

    /** Queues [playlist]; a playlist already queued or running is left alone. */
    fun export(playlist: LocalPlaylist) {
        synchronized(lock) {
            val state = _states.value[playlist.id]
            if (state is ExportState.Queued || state is ExportState.Running) return
            setState(playlist.id, ExportState.Queued)
            queue.addLast(playlist)
            if (worker == null) worker = scope.launch { drain() }
        }
    }

    /** Stops this playlist's export, or takes it off the queue; what was sent stays. */
    fun cancel(localId: String) {
        synchronized(lock) {
            val waiting = queue.removeAll { it.id == localId }
            val resting = cooling?.takeIf { it.id == localId }?.also { cooling = null } != null
            if (waiting || resting) {
                _states.update { it - localId }
            } else {
                current?.takeIf { it.first == localId }?.second?.cancel()
            }
        }
    }

    private fun setState(localId: String, state: ExportState) {
        _states.update { it + (localId to state) }
    }

    private suspend fun drain() {
        var lastEnd = 0L
        try {
            while (true) {
                val next = synchronized(lock) {
                    queue.removeFirstOrNull().also {
                        if (it == null) worker = null else cooling = it
                    }
                } ?: return
                val rested = MonotonicClock.nowMs() - lastEnd
                if (lastEnd != 0L && rested < cooldownMs) {
                    delay(cooldownMs - rested + extraRestMs())
                }
                // Cancelled while it rested: nothing to do, and nothing to report.
                val stillWanted = synchronized(lock) { (cooling === next).also { cooling = null } }
                if (!stillWanted) continue
                val result = runOne(next)
                lastEnd = MonotonicClock.nowMs()
                if (result is ExportState.Failed && result.kind == ExportErrorKind.RATE_LIMITED) {
                    dropQueue(result)
                }
            }
        } finally {
            // However this ended, the next export must be able to start a worker:
            // one left standing as "running" would leave every later playlist
            // queued for good. What is still waiting is picked up again.
            val me = currentCoroutineContext()[Job]
            synchronized(lock) {
                cooling?.let { _states.update { states -> states - it.id } }
                cooling = null
                if (worker === me) worker = null
                if (queue.isNotEmpty() && worker == null) worker = scope.launch { drain() }
            }
        }
    }

    private suspend fun runOne(playlist: LocalPlaylist): ExportState? {
        val job = scope.async {
            uploader.upload(
                localId = playlist.id,
                title = playlist.title,
                songIds = playlist.songs.map { it.videoId },
            ) { setState(playlist.id, it) }
        }
        synchronized(lock) { current = playlist.id to job }
        val result = try {
            job.await()
        } catch (e: CancellationException) {
            // The person stopped this one; the worker itself is still wanted.
            if (!currentCoroutineContext().isActive) throw e
            _states.update { it - playlist.id }
            return null
        } catch (e: Exception) {
            // Anything the upload did not turn into a result of its own ends
            // this playlist, not the worker.
            ExportState.Failed(exportErrorKind(e), e.message.orEmpty(), 0, playlist.songs.size)
        } finally {
            synchronized(lock) { current = null }
        }
        setState(playlist.id, result)
        _events.tryEmit(ExportEvent(playlist.title, result))
        return result
    }

    /** What is still waiting is not tried: asking again now would only dig the hole deeper. */
    private fun dropQueue(because: ExportState.Failed) {
        val dropped = synchronized(lock) { queue.toList().also { queue.clear() } }
        dropped.forEach { setState(it.id, because.copy(done = 0, total = it.songs.size)) }
    }

    companion object {
        const val COOLDOWN_MS = 15_000L
    }
}

/**
 * The application's export queue, wired to the signed-in YouTube Music account
 * and to [YouTubeExportLedger].
 */
object LocalPlaylistExporter {
    private val queue = ExportQueue(
        PlaylistUploader(
            target = object : YouTubeTarget {
                override suspend fun create(title: String, videoIds: List<String>): String =
                    YtMusicRepository.createPlaylist(title, PlaylistPrivacy.PRIVATE, videoIds).getOrThrow()

                override suspend fun add(playlistId: String, videoIds: List<String>) {
                    YtMusicRepository.addToPlaylist(playlistId, videoIds).getOrThrow()
                }

                override suspend fun contents(playlistId: String): Set<String>? =
                    YtMusicRepository.playlistEntries("VL$playlistId").getOrNull()?.map { it.videoId }?.toSet()
            },
            ledger = YouTubeExportLedger,
        ),
    )

    val states: StateFlow<Map<String, ExportState>> get() = queue.states
    val events: SharedFlow<ExportEvent> get() = queue.events

    /** True when everything in [playlist] has already been sent. */
    fun isExported(playlist: LocalPlaylist): Boolean {
        val sent = YouTubeExportLedger.get(playlist.id)?.exported ?: return false
        return playlist.songs.map { it.videoId }
            .filter { PlaylistUploader.VIDEO_ID.matches(it) }
            .all { it in sent }
    }

    /** Queues [playlist]; a playlist already queued or running is left alone. */
    fun export(playlist: LocalPlaylist) = queue.export(playlist)

    /** Stops this playlist's export, or takes it off the queue; what was sent stays. */
    fun cancel(localId: String) = queue.cancel(localId)
}
