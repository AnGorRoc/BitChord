package com.music.bitchord.data.spotify

import com.music.bitchord.data.innertube.Innertube
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.IOException
import kotlin.random.Random

/** Why an export stopped. */
enum class ExportErrorKind { SIGNED_OUT, RATE_LIMITED, NETWORK, OTHER }

/**
 * What a failure means for the export. Rate limiting and a bad connection are
 * worth waiting out; a lost sign-in or a refusal are not, since asking again
 * the same way changes nothing.
 */
fun exportErrorKind(e: Throwable): ExportErrorKind {
    val message = e.message.orEmpty()
    return when {
        e is Innertube.NotSignedInException ||
            message.contains("401") || message.contains("403") ||
            message.contains("rejected", ignoreCase = true) -> ExportErrorKind.SIGNED_OUT
        message.contains("429") ||
            message.contains("Too Many", ignoreCase = true) ||
            message.contains("RESOURCE_EXHAUSTED", ignoreCase = true) ||
            message.contains("quota", ignoreCase = true) -> ExportErrorKind.RATE_LIMITED
        e is IOException ||
            Regex("""\b50[0234]\b""").containsMatchIn(message) -> ExportErrorKind.NETWORK
        else -> ExportErrorKind.OTHER
    }
}

/** Where one playlist's export stands. */
sealed interface ExportState {
    data object Queued : ExportState

    /** [done] of [total] songs are on YouTube Music; [throttled] while waiting out a limit. */
    data class Running(val done: Int, val total: Int, val throttled: Boolean = false) : ExportState

    /** [exported] songs are there; [skipped] had no YouTube id or went past the playlist limit. */
    data class Done(val exported: Int, val skipped: Int, val upToDate: Boolean) : ExportState

    /** Stopped with [done] of [total] already there; exporting again carries on from them. */
    data class Failed(
        val kind: ExportErrorKind,
        val detail: String,
        val done: Int,
        val total: Int,
    ) : ExportState
}

/** The two writes an export needs, as YouTube Music takes them. */
interface YouTubeTarget {
    /** Makes a playlist already holding [videoIds] and returns its id. */
    suspend fun create(title: String, videoIds: List<String>): String
    suspend fun add(playlistId: String, videoIds: List<String>)
}

/** What has been uploaded for each local playlist, so an export can carry on rather than start over. */
interface ExportLedger {
    fun get(localId: String): LedgerEntry?
    fun put(localId: String, entry: LedgerEntry)
    fun remove(localId: String)
}

data class LedgerEntry(val youtubeId: String, val exported: Set<String>)

/**
 * Uploads a playlist to YouTube Music a little at a time.
 *
 * YouTube Music has no published write quota, so the safe course is to look
 * like a person: small batches, a pause with jitter between every request, and
 * when it pushes back, a long wait that grows — and a wider gap from then on —
 * rather than hammering on. What is uploaded is written to the [ledger] after
 * each batch, so a stop (rate limit, no signal, the user's) loses nothing and
 * the next export only sends what is missing.
 *
 * [pause] and [jitter] are parameters so tests can run it without the waiting.
 */
class PlaylistUploader(
    private val target: YouTubeTarget,
    private val ledger: ExportLedger,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val jitter: (Long) -> Long = { (it * Random.nextDouble(0.8, 1.2)).toLong() },
) {
    private var gapMs = BASE_GAP_MS
    private var cleanRequests = 0

    private class Stop(val kind: ExportErrorKind, val detail: String) : Exception(detail)

    /**
     * Sends [songIds] to the playlist this [localId] was exported to before, or
     * to a new one named [title]. Returns [ExportState.Done] or
     * [ExportState.Failed]; only cancellation is thrown.
     */
    suspend fun upload(
        localId: String,
        title: String,
        songIds: List<String>,
        report: (ExportState) -> Unit,
    ): ExportState = upload(localId, title, songIds, report, mayRelink = true)

    private suspend fun upload(
        localId: String,
        title: String,
        songIds: List<String>,
        report: (ExportState) -> Unit,
        mayRelink: Boolean,
    ): ExportState {
        val ids = songIds.filter { VIDEO_ID.matches(it) }.distinct().take(MAX_TRACKS)
        val skipped = songIds.size - ids.size
        if (ids.isEmpty()) return ExportState.Failed(ExportErrorKind.OTHER, "no songs to export", 0, 0)

        val entry = ledger.get(localId)
        val wanted = ids.toHashSet()
        val sent = LinkedHashSet(entry?.exported.orEmpty())
        val pending = ids.filter { it !in sent }
        if (pending.isEmpty() && entry != null) return ExportState.Done(ids.size, skipped, upToDate = true)

        var youtubeId = entry?.youtubeId
        fun uploaded() = sent.count { it in wanted }
        report(ExportState.Running(uploaded(), ids.size))
        try {
            pending.chunked(BATCH).forEachIndexed { index, batch ->
                if (index > 0) pause(jitter(gapMs))
                val before = youtubeId
                youtubeId = withBackoff(uploaded(), ids.size, report) {
                    if (before == null) target.create(title, batch) else before.also { target.add(it, batch) }
                }
                sent += batch
                ledger.put(localId, LedgerEntry(youtubeId!!, sent))
                report(ExportState.Running(uploaded(), ids.size))
            }
        } catch (e: Stop) {
            // A playlist that was deleted on YouTube Music: the link is dead, so
            // drop it and send everything to a new one — once.
            if (mayRelink && entry != null && e.detail.contains("404")) {
                ledger.remove(localId)
                return upload(localId, title, songIds, report, mayRelink = false)
            }
            return ExportState.Failed(e.kind, e.detail, uploaded(), ids.size)
        }
        return ExportState.Done(ids.size, skipped, upToDate = false)
    }

    /** Runs one request, waiting and retrying when YouTube pushes back or the connection drops. */
    private suspend fun <T> withBackoff(
        done: Int,
        total: Int,
        report: (ExportState) -> Unit,
        request: suspend () -> T,
    ): T {
        var attempt = 0
        while (true) {
            try {
                return request().also { settle() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val kind = exportErrorKind(e)
                val waits = when (kind) {
                    ExportErrorKind.RATE_LIMITED -> RATE_LIMIT_WAITS_MS
                    ExportErrorKind.NETWORK -> NETWORK_WAITS_MS
                    else -> emptyList()
                }
                if (attempt >= waits.size) throw Stop(kind, e.message.orEmpty())
                // Slower from here on, not only for this one wait.
                gapMs = minOf(gapMs * 2, MAX_GAP_MS)
                cleanRequests = 0
                report(ExportState.Running(done, total, throttled = true))
                pause(jitter(waits[attempt++]))
                report(ExportState.Running(done, total))
            }
        }
    }

    /** A run of clean requests earns the normal pace back. */
    private fun settle() {
        if (++cleanRequests >= CLEAN_BEFORE_RESET) {
            gapMs = BASE_GAP_MS
            cleanRequests = 0
        }
    }

    internal companion object {
        /** Songs per request. */
        const val BATCH = 25
        /** YouTube's cap on a playlist's length. */
        const val MAX_TRACKS = 5000
        const val BASE_GAP_MS = 2_500L
        const val MAX_GAP_MS = 20_000L
        const val CLEAN_BEFORE_RESET = 10
        val NETWORK_WAITS_MS = listOf(5_000L, 15_000L, 45_000L)
        val RATE_LIMIT_WAITS_MS = listOf(45_000L, 120_000L, 300_000L)
        /** A YouTube video id: eleven characters of the URL-safe alphabet. */
        val VIDEO_ID = Regex("""[A-Za-z0-9_-]{11}""")
    }
}
