package com.music.bitchord.data.spotify

import android.content.Context
import android.content.SharedPreferences
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

/** [PlaylistUploader]'s ledger, kept in its own preferences so the playlists themselves are untouched. */
object YouTubeExportLedger : ExportLedger {
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences("bitchord_youtube_export", Context.MODE_PRIVATE)
    }

    override fun get(localId: String): LedgerEntry? {
        if (!this::prefs.isInitialized) return null
        val raw = prefs.getString(localId, null) ?: return null
        return runCatching {
            val obj = JSONObject(raw)
            val ids = obj.getJSONArray("ids")
            LedgerEntry(obj.getString("yt"), List(ids.length()) { ids.getString(it) }.toSet())
        }.getOrNull()
    }

    override fun put(localId: String, entry: LedgerEntry) {
        if (!this::prefs.isInitialized) return
        val obj = JSONObject()
            .put("yt", entry.youtubeId)
            .put("ids", JSONArray(entry.exported.toList()))
        prefs.edit().putString(localId, obj.toString()).apply()
    }

    override fun remove(localId: String) {
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
object LocalPlaylistExporter {
    /** Quiet time between one playlist and the next. */
    private const val COOLDOWN_MS = 15_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val queue = ArrayDeque<LocalPlaylist>()
    private var worker: Job? = null
    private var current: Pair<String, Deferred<ExportState>>? = null

    private val _states = MutableStateFlow<Map<String, ExportState>>(emptyMap())
    val states: StateFlow<Map<String, ExportState>> = _states.asStateFlow()

    private val _events = MutableSharedFlow<ExportEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<ExportEvent> = _events.asSharedFlow()

    private val uploader = PlaylistUploader(
        target = object : YouTubeTarget {
            override suspend fun create(title: String, videoIds: List<String>): String =
                YtMusicRepository.createPlaylist(title, PlaylistPrivacy.PRIVATE, videoIds).getOrThrow()

            override suspend fun add(playlistId: String, videoIds: List<String>) {
                YtMusicRepository.addToPlaylist(playlistId, videoIds).getOrThrow()
            }
        },
        ledger = YouTubeExportLedger,
    )

    /** True when everything in [playlist] has already been sent. */
    fun isExported(playlist: LocalPlaylist): Boolean {
        val sent = YouTubeExportLedger.get(playlist.id)?.exported ?: return false
        return playlist.songs.map { it.videoId }
            .filter { PlaylistUploader.VIDEO_ID.matches(it) }
            .all { it in sent }
    }

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
            if (queue.removeAll { it.id == localId }) {
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
        while (true) {
            val next = synchronized(lock) {
                queue.removeFirstOrNull().also { if (it == null) worker = null }
            } ?: return
            val rested = System.currentTimeMillis() - lastEnd
            if (lastEnd != 0L && rested < COOLDOWN_MS) {
                delay(COOLDOWN_MS - rested + Random.nextLong(0, 5_000))
            }
            val result = runOne(next)
            lastEnd = System.currentTimeMillis()
            if (result is ExportState.Failed && result.kind == ExportErrorKind.RATE_LIMITED) {
                dropQueue(result)
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
}
