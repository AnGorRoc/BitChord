package com.music.bitchord.playback

import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.innertube.StreamResolver
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.data.sources.TrackMatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs [DownloadPlan]: one download at a time, chosen afresh every second and
 * on every change that matters, and swapped out the moment a more important
 * one comes up.
 *
 * This is what makes a pause worth something. The read-ahead it replaces
 * stopped dead on pause, so a lossless track paused a minute in stayed a minute
 * long on disk, and its better copy was never looked for until the listener
 * pressed play again. Here a pause is simply the moment with the most time to
 * spare: the rest of the track comes down, its better copy is found and
 * swapped in while nobody is listening, and the next two tracks are made
 * ready in the quality they will play in.
 *
 * Only ever one task in flight, and replacing it cancels it outright — the
 * network read included, see [AudioCache.fillRendition] — because the one
 * thing worse than not reading ahead is reading ahead while the track playing
 * waits for bytes.
 */
@UnstableApi
class DownloadScheduler(
    private val scope: CoroutineScope,
    private val host: Host,
) {

    /** What the scheduler needs from the service. Called on the main thread. */
    interface Host {
        /** The player as it is right now, or null when there is nothing this should do. */
        fun read(): View?

        /** Starts the second look for a better copy of the playing track; see `lookForBetterCopy`. */
        fun lookForBetterCopy()

        /** Whether the scheduler has a download in flight, so the service can keep the process awake for it. */
        fun onBusyChanged(busy: Boolean)

        /**
         * Whether a pause right now would leave downloads to make — so the
         * service stays in the foreground through that pause. Kept current
         * while playing, because the foreground timeout is read at the moment
         * playback stops, not after.
         */
        fun onPendingChanged(pending: Boolean)
    }

    /** One read of the player. */
    data class View(
        val current: Song,
        val currentUri: Uri?,
        val durationMs: Long,
        val positionMs: Long,
        val bufferedPositionMs: Long,
        val speed: Float,
        val playWhenReady: Boolean,
        val playbackState: Int,
        val loading: Boolean,
        val transitioning: Boolean,
        val upgradeBusy: Boolean,
        /** The playing track may have a better copy still to look for. */
        val canUpgrade: Boolean,
        val elsewhere: Boolean,
        val direct: Boolean,
        /** The next tracks in play order, the playing one excluded. */
        val upcoming: List<Pair<Song, Uri?>>,
    )

    private val kicks = Channel<Unit>(Channel.CONFLATED)
    private var loop: Job? = null
    private var running: Job? = null
    private var runningTask: Pair<DownloadPlan.Task, String>? = null
    private var busy = false

    private var currentId: String? = null
    private var currentSince = 0L

    /** When a task for a track may next be tried, after one that got nowhere. */
    private val retryAt = ConcurrentHashMap<String, Long>()
    private val retryLevel = ConcurrentHashMap<String, Int>()

    /** Tracks whose stream has been settled this session, whatever the answer was. */
    private val prepared = ConcurrentHashMap.newKeySet<String>()

    /** Tracks whose YouTube URL has been warmed this session. */
    private val warmed = ConcurrentHashMap.newKeySet<String>()

    fun start() {
        if (loop != null) return
        loop = scope.launch(Dispatchers.Main) {
            while (isActive) {
                withTimeoutOrNull(TICK_MS) { kicks.receive() }
                try {
                    step()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Whatever the cache or the player threw, the loop is what
                    // decides every later download: it is slowed down, not ended.
                    TrackLog.w(TAG, "download scheduler step failed: ${e.message}")
                    cancelRunning()
                    delay(ERROR_PAUSE_MS)
                }
            }
        }
    }

    /** Something changed: decide again now rather than at the next tick. */
    fun kick() {
        kicks.trySend(Unit)
    }

    fun stop() {
        loop?.cancel()
        loop = null
        cancelRunning()
    }

    private fun cancelRunning() {
        running?.cancel()
        running = null
        runningTask = null
        setBusy(false)
        setPending(false)
    }

    private fun setBusy(value: Boolean) {
        if (busy == value) return
        busy = value
        host.onBusyChanged(value)
    }

    private var pending = false

    private fun setPending(value: Boolean) {
        if (pending == value) return
        pending = value
        host.onPendingChanged(value)
    }

    private suspend fun step() {
        if (!AppSettings.smartPreload.value) {
            cancelRunning()
            return
        }
        val view = host.read()
        if (view == null) {
            cancelRunning()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (view.current.videoId != currentId) {
            currentId = view.current.videoId
            currentSince = now
        }
        forgetOutsideQueue(view)
        val snapshot = withContext(Dispatchers.IO) { snapshotOf(view, now) }
        val task = DownloadPlan.next(snapshot)
        val pausedTask = if (snapshot.playWhenReady) {
            DownloadPlan.next(snapshot.copy(playWhenReady = false, stalled = false, loading = false))
        } else {
            task
        }
        setPending(pausedTask != null || running?.isActive == true)
        val subject = task?.let { subjectOf(it, view) }
        val wanted = if (task != null && subject != null) task to subject else null
        if (wanted != null && wanted == runningTask && running?.isActive == true) return
        if (running?.isActive == true) {
            TrackLog.d(TAG, "download: ${runningTask?.first} for ${runningTask?.second} gives way to ${wanted?.first ?: "nothing"}")
            running?.cancel()
        }
        running = null
        runningTask = null
        if (wanted == null) {
            setBusy(false)
            return
        }
        runningTask = wanted
        setBusy(true)
        val job = scope.launch(Dispatchers.IO + TrackLog.about(wanted.second.substringBefore(RENDITION_SEPARATOR))) {
            runCatching {
                // A task that has stopped getting anywhere (a socket that never
                // answers, a stuck cache lock) is ended and backed off rather than
                // left to hold the connection, the CPU and the service open.
                val finished = withTimeoutOrNull(timeoutOf(wanted.first)) {
                    execute(wanted.first, wanted.second, view)
                    true
                }
                if (finished == null) {
                    TrackLog.w(TAG, "download: ${wanted.first} for ${wanted.second} timed out")
                    backOff(wanted.second.substringBefore(RENDITION_SEPARATOR))
                }
            }
                .onFailure {
                    if (it is CancellationException) throw it
                    TrackLog.d(TAG, "download: ${wanted.first} for ${wanted.second} failed: ${it.message}")
                    backOff(wanted.second.substringBefore(RENDITION_SEPARATOR))
                }
        }
        running = job
        job.invokeOnCompletion { kick() }
    }

    private fun subjectOf(task: DownloadPlan.Task, view: View): String? = when (task) {
        DownloadPlan.Task.CurrentUpgrade -> view.current.videoId
        // With its rendition: an upgrade swapped in mid-fill is a different
        // file, and the fill of the one it replaced has to give way to it.
        DownloadPlan.Task.FillCurrent ->
            view.current.videoId + RENDITION_SEPARATOR + view.currentUri?.getQueryParameter(QualityUpgrade.MARKER).orEmpty()
        DownloadPlan.Task.WarmUrls -> view.upcoming.joinToString(",") { it.first.videoId }
        is DownloadPlan.Task.Prepare -> view.upcoming.getOrNull(task.index)?.first?.videoId
        is DownloadPlan.Task.FillNext -> view.upcoming.getOrNull(task.index)?.first?.videoId
    }

    private suspend fun execute(task: DownloadPlan.Task, subject: String, view: View) {
        TrackLog.d(TAG, "download: $task for $subject")
        when (task) {
            DownloadPlan.Task.CurrentUpgrade -> {
                backOff(upgradeKey(subject))
                withContext(Dispatchers.Main) { host.lookForBetterCopy() }
            }
            DownloadPlan.Task.FillCurrent -> {
                val uri = view.currentUri ?: return
                val id = view.current.videoId
                fill(id, uri, AudioCache.claimedKeyOf(id), guardSegments = if (view.playWhenReady) PLAYING_GUARD_SEGMENTS else 0)
            }
            is DownloadPlan.Task.Prepare -> {
                val (song, itemUri) = view.upcoming.getOrNull(task.index) ?: return
                AudioCache.prepareUpcoming(AudioCache.Upcoming(song.videoId, TrackMatcher.targetOf(song)))
                prepared += song.videoId
                // An opening that did not land in full would be asked for again
                // at once, every tick, and starve the upgrade look: rest first.
                val uri = itemUri?.takeIf { it.scheme == "bitchord" }
                if (uri != null && !AudioCache.headReady(uri)) backOff(headKey(song.videoId))
            }
            DownloadPlan.Task.WarmUrls -> {
                for ((song, _) in view.upcoming) {
                    val id = song.videoId
                    if (id in warmed || id in prepared) continue
                    runCatching { StreamResolver.resolve(id) }
                        .onFailure {
                            if (it is CancellationException) throw it
                            TrackLog.d(TAG, "queue warm-up skipped $id: ${it.message}", about = id)
                        }
                    warmed += id
                    delay(WARM_STAGGER_MS)
                }
            }
            is DownloadPlan.Task.FillNext -> {
                val (song, uri) = view.upcoming.getOrNull(task.index) ?: return
                fill(song.videoId, uri ?: Uri.parse("bitchord://watch?v=${song.videoId}"), key = null)
            }
        }
    }

    private suspend fun fill(id: String, uri: Uri, key: String?, guardSegments: Int = 0) {
        val before = AudioCache.missingBytes(uri, key)
        val result = AudioCache.fillRendition(uri, key, guardSegments)
        if (result == AudioCache.Fill.COMPLETE) {
            retryLevel.remove(id)
            retryAt.remove(id)
            TrackLog.d(TAG, "download: $id is on disk in full", about = id)
            return
        }
        val after = AudioCache.missingBytes(uri, key)
        // Some of it landed: try again soon. None did: something holds the
        // rest, or the server refused it, and asking at once would only ask
        // the same question.
        if (before < 0 || after < before) retryLevel.remove(id)
        backOff(id)
    }

    private fun backOff(key: String) {
        val level = retryLevel.merge(key, 1, Int::plus) ?: 1
        val wait = RETRY_STEPS_MS[(level - 1).coerceAtMost(RETRY_STEPS_MS.lastIndex)]
        retryAt[key] = SystemClock.elapsedRealtime() + wait
    }

    /** The longest a task may run before it is given up on; fills get the longest, a master takes minutes. */
    private fun timeoutOf(task: DownloadPlan.Task): Long = when (task) {
        DownloadPlan.Task.FillCurrent, is DownloadPlan.Task.FillNext -> FILL_TIMEOUT_MS
        DownloadPlan.Task.CurrentUpgrade -> UPGRADE_LOOK_TIMEOUT_MS
        is DownloadPlan.Task.Prepare, DownloadPlan.Task.WarmUrls -> PREPARE_TIMEOUT_MS
    }

    private fun due(key: String, now: Long) = (retryAt[key] ?: 0L) <= now

    private fun upgradeKey(id: String) = "$id#upgrade"

    private fun headKey(id: String) = "$id#head"

    /**
     * Drops what was remembered about tracks that are no longer playing or
     * queued. Without this the sets and the retry tables grow with every
     * track of a long session, and a track that comes round again is taken as
     * already prepared on the strength of a URL that has long expired.
     */
    private fun forgetOutsideQueue(view: View) {
        val live = HashSet<String>(view.upcoming.size + 1).apply {
            add(view.current.videoId)
            view.upcoming.forEach { add(it.first.videoId) }
        }
        prepared.retainAll(live)
        warmed.retainAll(live)
        // A key names a track, or a few joined by commas, with a suffix for an
        // upgrade or a rendition: it is live while any track it names is.
        fun stale(key: String) =
            key.substringBefore('#').substringBefore(RENDITION_SEPARATOR).split(',').none { it in live }
        retryAt.keys.removeIf(::stale)
        retryLevel.keys.removeIf(::stale)
    }

    private fun snapshotOf(view: View, now: Long): DownloadPlan.Snapshot {
        val id = view.current.videoId
        val uri = view.currentUri
        val cacheable = uri != null && AudioCache.isPinnedRead(uri)
        val missing = if (cacheable) AudioCache.missingBytes(uri, AudioCache.claimedKeyOf(id)) else 0L
        val speed = view.speed.coerceAtLeast(0.1f)
        // A runtime not reported yet — the first moments of a track — is not
        // "nothing left": read that way it would make every track look urgent
        // until its decoder spoke.
        val remainingMs = if (view.durationMs > 0) {
            ((view.durationMs - view.positionMs).coerceAtLeast(0L) / speed).toLong()
        } else {
            UNKNOWN_REMAINING_MS
        }
        val current = DownloadPlan.Current(
            mediaId = id,
            remainingMs = remainingMs,
            bufferedAheadMs = (view.bufferedPositionMs - view.positionMs).coerceAtLeast(0L),
            missingBytes = missing,
            fillDue = cacheable && missing != 0L && due(id, now),
        )
        AudioCache.noteSongs(view.upcoming.map { it.first })
        val upcoming = view.upcoming.map { (song, itemUri) -> upcomingOf(song, itemUri, now) }
        val ceiling = AudioCache.cacheCeilingBytes()
        // The playing track stays on disk while it plays, so what it holds is
        // not room for anything else.
        val keptByCurrent = if (cacheable) AudioCache.contentLengthOf(uri) else 0L
        return DownloadPlan.Snapshot(
            prepared = view.playbackState == Player.STATE_READY || view.playbackState == Player.STATE_BUFFERING,
            offline = AudioCache.isOffline(),
            elsewhere = view.elsewhere,
            playWhenReady = view.playWhenReady,
            stalled = view.playWhenReady && view.playbackState == Player.STATE_BUFFERING,
            loading = view.loading,
            transitioning = view.transitioning,
            upgradeBusy = view.upgradeBusy,
            upgradeLookDue = view.canUpgrade && due(upgradeKey(id), now),
            direct = view.direct,
            current = current,
            upcoming = upcoming,
            currentForMs = now - currentSince,
            bandwidthBps = AudioCache.bandwidthEstimateBps(),
            metered = AppSettings.meteredConnection.value == true,
            fullTrackBudgetBytes = ((ceiling * BUDGET_SHARE).toLong() - keptByCurrent).coerceAtLeast(0L),
        )
    }

    private fun upcomingOf(song: Song, itemUri: Uri?, now: Long): DownloadPlan.Upcoming {
        val id = song.videoId
        val uri = itemUri?.takeIf { it.scheme == "bitchord" }
        // Nothing to fetch for a download, a local file, or a track whose
        // bytes may not be read ahead: it counts as ready, so the plan moves on.
        val allowed = uri != null && AudioCache.readAheadAllowed(id)
        val settled = id in prepared || !SourceResolver.canSubstituteForYouTube() ||
            StreamChoice.peek(id) != null || uri == null
        val full = !allowed || AudioCache.isFullyCached(uri)
        return DownloadPlan.Upcoming(
            mediaId = id,
            durationMs = (TrackMatcher.secondsOf(song.durationText) ?: 0) * 1000L,
            prepared = settled,
            // Resting after an opening that fell short counts as done for now.
            headCached = full || AudioCache.headReady(uri) || !due(headKey(id), now),
            fullyCached = full,
            fillDue = allowed && !full && due(id, now),
            warmed = id in warmed || uri == null,
            sizeBytes = if (allowed) AudioCache.contentLengthOf(uri).takeIf { it > 0 } ?: -1L else -1L,
        )
    }

    private companion object {
        const val TAG = "BitChord"
        const val TICK_MS = 1_000L
        const val WARM_STAGGER_MS = 500L

        const val FILL_TIMEOUT_MS = 15 * 60_000L
        const val UPGRADE_LOOK_TIMEOUT_MS = 3 * 60_000L
        const val PREPARE_TIMEOUT_MS = 2 * 60_000L

        /** How long the loop rests after a step that threw. */
        const val ERROR_PAUSE_MS = 5_000L
        const val UNKNOWN_REMAINING_MS = 10 * 60 * 1000L
        const val RENDITION_SEPARATOR = "@"

        /** Segments just ahead of a playing reader that a fill of its track leaves until last. */
        const val PLAYING_GUARD_SEGMENTS = 2

        /** Waits after a task that got nowhere: soon at first, then less often. */
        val RETRY_STEPS_MS = longArrayOf(5_000L, 15_000L, 30_000L, 60_000L)

        /**
         * The share of the cache ceiling the playing and the next tracks may
         * fill between them. The rest is left for what else the cache holds -
         * the tracks played before, whose eviction is what the margin is for.
         */
        const val BUDGET_SHARE = 0.8
    }
}
