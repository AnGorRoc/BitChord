package com.music.bitchord.data.sources

import com.music.bitchord.BuildConfig
import com.music.bitchord.data.Http
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.module.SharedCalls
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private const val TAG = "BitChord"

/**
 * Lossless FLAC from Monochrome's track streamer.
 *
 * Two endpoints, both plain HTTP with no account: `/search/tracks` answers a
 * title-and-artist query with the catalogue's rows, and `/track/{id}` serves
 * the recording as one file with byte ranges. [SourceResolver] matches the rows
 * against the track being played and opens the best one here.
 *
 * The one thing that can't be taken on trust is the format. Measured against
 * the live server in October 2026, seven of eight tracks came back as FLAC and
 * the eighth as AAC in an MP4 — same endpoint, same request, nothing in the
 * search row to tell them apart. So [stream] reads the first 42 bytes before
 * answering: a file that opens as `fLaC` is handed up with the bit depth and
 * sample rate its STREAMINFO states, and anything else is a miss, which lets
 * the resolver try the next row or the next source rather than play AAC under
 * a lossless label.
 *
 * [SourceConfig.baseUrl] is blank for the shipped instance and falls back to
 * [DEFAULT_BASE]; it exists so a test can point this at a local server.
 */
class MonochromeSource(
    override val config: SourceConfig,
) : MusicSource, SourceRegistry.ConfigBacked {

    override val configId: String get() = config.id
    override val kind: SourceKind get() = SourceKind.MONOCHROME
    override val displayName: String get() = config.label.ifBlank { SourceKind.MONOCHROME.label }

    private val base: String get() = config.baseUrl.ifBlank { DEFAULT_BASE }.trimEnd('/')

    /**
     * Where shared calls run — not the caller's scope, for the reason
     * [SharedCalls] gives: the resolver cancels every source still running
     * the moment one answers, and the request it would tear down has usually
     * reached the server already.
     */
    private val calls = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * The same query is asked several times per track — the live resolve, the
     * read-ahead before it, a re-open after a seek — and a catalogue's answer
     * does not change in minutes.
     */
    private val searches = SharedCalls<List<MonochromeRow>>(SEARCH_TTL_MS, calls, log = ::logReuse)

    /**
     * What a track id's file turned out to be, kept so a seek or a second look
     * doesn't read its header again. A miss (not FLAC) is an answer and is
     * kept; a failure to ask is not, see [SharedCalls.get].
     */
    private val probes = SharedCalls<Probe?>(PROBE_TTL_MS, calls, log = ::logReuse)

    /**
     * Each row's length, by track id, from the searches that issued the ids.
     * Paired with the file's size it gives the stream's real bitrate — see
     * [bitrateKbps].
     */
    private val durations = ConcurrentHashMap<String, Int>()

    private fun logReuse(line: String) = TrackLog.d(TAG, line)

    override suspend fun health(): SourceHealth = withContext(Dispatchers.IO) {
        runCatching { fetchJson(searchUrl("test", 1)) }.fold(
            onSuccess = { SourceHealth.Ok() },
            onFailure = { SourceHealth.Unreachable(it.message ?: "Monochrome didn't answer") },
        )
    }

    /**
     * Rows for [query], as [Song]s tagged with this source's id. [waitForAll]
     * and [request] are moot: one endpoint, and every row is the same tier
     * until its file is opened.
     *
     * Never throws. A server that is down or slow is a source with nothing to
     * offer this time, which is how [SourceResolver] already treats an empty
     * answer.
     */
    override suspend fun search(
        query: String,
        limit: Int,
        waitForAll: Boolean,
        request: StreamRequest?,
    ): List<Song> {
        if (query.isBlank()) return emptyList()
        val url = searchUrl(query, SEARCH_LIMIT) ?: return emptyList()
        // A search for a song the listener is skipping past is not made at all.
        if (!searches.has(url)) SkipPace.shared.settle()
        val rows = searches.get(
            key = url,
            describe = { "▶ Monochrome search \"$query\"" },
        ) {
            runCatching { rowsOf(politely { fetchJson(url) }) }
        }.getOrElse {
            TrackLog.w(TAG, "Monochrome search failed for \"$query\": ${it.message}")
            return emptyList()
        }
        TrackLog.d(TAG, "  ✓ Monochrome returned ${rows.size} rows for \"$query\"")
        rows.forEach { row -> row.durationSec?.let { durations[row.id] = it } }
        return rows.take(limit).map { it.toSong() }
    }

    /**
     * The FLAC for [trackId], or null when there isn't one to give.
     *
     * Null for a [StreamRequest.Capped] request, which a FLAC cannot honour —
     * though [AudioQuality.permits][com.music.bitchord.data.settings.permits]
     * normally keeps such a request from reaching here at all.
     */
    override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? {
        if (request is StreamRequest.Capped) return null
        if (!TRACK_ID.matches(trackId)) {
            TrackLog.w(TAG, "Monochrome: refusing malformed track id '$trackId'")
            return null
        }
        if (!probes.has(trackId)) SkipPace.shared.settle()
        val probe = probes.get(
            key = trackId,
            describe = { "▶ Monochrome probe $trackId" },
        ) {
            runCatching { probe(trackId) }
        }.getOrElse {
            TrackLog.w(TAG, "Monochrome: couldn't open $trackId: ${it.message}")
            return null
        } ?: return null
        val format = StreamFormat(
            codec = "flac",
            // Stated, not guessed: the file's own size over the catalogue's
            // length. A lossless stream with no bitrate shows none at all —
            // see NerdStats.sourceBitrate — and this one has a true figure.
            kbps = bitrateKbps(probe.totalBytes, durations[trackId]),
            sampleRateHz = probe.info?.sampleRateHz,
            bitDepth = probe.info?.bitDepth,
        )
        TrackLog.d(TAG, "  ✓ Monochrome $trackId is ${format.summary}, ${format.kbps ?: "?"} kbps")
        return SourceStream(
            // `mime` is ignored by the server and read by Cast, which is told
            // a file's type from its URL and would otherwise announce this
            // extensionless one as MP4 — see CastPlayback.mimeTypeOf.
            url = "${trackUrl(trackId)}?mime=audio/flac",
            format = format,
        )
    }

    /**
     * When the server may be spoken to again, after it said it was being asked
     * too often.
     *
     * Per-instance and not per-request: a 429 is a statement about the server,
     * not about the track that drew it. A song opens up to three candidates and
     * the next one is warmed alongside, so without this each of those would
     * walk into the limiter in turn.
     */
    @Volatile
    private var quietUntilMs = 0L

    /** A 429, with how long the server asked to be left alone. */
    private class Throttled(val retryAfter: String?) : IOException("HTTP 429")

    /**
     * How many 429s in a row the server has given without answering anything
     * in between. A block that lasts is not one a second's wait gets past, so
     * the quiet window grows with it, see [backoffMs].
     */
    @Volatile
    private var throttleStreak = 0

    /**
     * Opens at most [MAX_PARALLEL_PROBES] files at once. A song opens up to
     * three candidates and the next track is warmed alongside, which is the
     * burst that draws the limit in the first place.
     */
    private val probeGate = Semaphore(MAX_PARALLEL_PROBES)

    /** [probeOnce], within the limits [politely] keeps. */
    private suspend fun probe(trackId: String): Probe? =
        politely { probeGate.withPermit { probeOnce(trackId) } }

    /**
     * Starts the quiet window a 429 asked for: the server's own `Retry-After`
     * when it sent one, otherwise one that doubles with each 429 in a row.
     *
     * A 429 that lands inside a window already running is a straggler from the
     * same burst, answered before the server's word reached the others, and
     * doesn't count towards the streak. Without that, three calls in flight
     * would read as three refusals in a row and the wait would triple at once.
     */
    private fun noteThrottled(retryAfter: String?) {
        val now = System.currentTimeMillis()
        lastThrottledAtMs = now
        if (now >= quietUntilMs) throttleStreak++
        val waitMs = if (retryAfter?.trim()?.toDoubleOrNull() != null) {
            retryAfterMs(retryAfter)
        } else {
            backoffMs(throttleStreak)
        }
        quietUntilMs = maxOf(quietUntilMs, now + waitMs)
        lastQuietUntilMs = quietUntilMs
    }

    /**
     * [call], after waiting out the server's quiet window, and asked once more
     * if it still answers with a 429. Shared by the search and the probe,
     * since the limit is the server's and not either one's.
     *
     * A wait longer than [MAX_QUIET_WAIT_MS] is not sat through: the live
     * resolve is blocked on this and the next source is always there to fall
     * back to, so it fails at once. The window is still kept, so the calls
     * behind this one don't ask either. Fails like any other error afterwards,
     * which isn't remembered, and the next look at the track asks again.
     */
    private suspend fun <T> politely(call: suspend () -> T): T {
        var retried = false
        while (true) {
            val quietFor = quietUntilMs - System.currentTimeMillis()
            if (quietFor > MAX_QUIET_WAIT_MS) {
                lastThrottledAtMs = System.currentTimeMillis()
                throw IOException("HTTP 429, asked to wait ${quietFor / 1000}s")
            }
            if (quietFor > 0) delay(quietFor)
            try {
                return call().also { throttleStreak = 0 }
            } catch (throttled: Throttled) {
                noteThrottled(throttled.retryAfter)
                if (retried) throw throttled
                retried = true
            }
        }
    }

    /**
     * Reads the head of [trackId]'s file and says what it is: a FLAC's
     * STREAMINFO and size, or null for anything else.
     *
     * Throws when the server can't be asked or answers an error, so the failure
     * isn't remembered — a 404 is the exception, being an answer about the
     * track rather than about the server.
     */
    private fun probeOnce(trackId: String): Probe? {
        val request = Request.Builder()
            .url(trackUrl(trackId))
            .header("User-Agent", USER_AGENT)
            .header("Range", "bytes=0-${HEAD_BYTES - 1}")
            .build()
        probeClient.newCall(request).execute().use { response ->
            if (response.code == 404) return null
            if (response.code == 429) throw Throttled(response.header("Retry-After"))
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            // A server that ignores the range answers 200 with the whole file;
            // only its first bytes are read either way.
            val source = response.body?.source() ?: throw IOException("Empty body")
            source.request(HEAD_BYTES.toLong())
            val head = source.buffer.readByteArray(minOf(HEAD_BYTES.toLong(), source.buffer.size))
            if (!isFlac(head)) {
                TrackLog.w(TAG, "Monochrome: $trackId isn't FLAC (${response.header("Content-Type")}); passing")
                return null
            }
            return Probe(
                info = flacInfo(head),
                totalBytes = totalBytes(response.code, response.header("Content-Range"), response.header("Content-Length")),
            )
        }
    }

    /** What the head of a FLAC said: its STREAMINFO, and the whole file's size. */
    private class Probe(val info: FlacInfo?, val totalBytes: Long?)

    private fun fetchJson(url: String?): String {
        requireNotNull(url) { "Not a URL Monochrome can be asked at" }
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .build()
        searchClient.newCall(request).execute().use { response ->
            if (response.code == 429) throw Throttled(response.header("Retry-After"))
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            return response.body?.string() ?: throw IOException("Empty body")
        }
    }

    private fun searchUrl(query: String, limit: Int): String? =
        "$base/search/tracks".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("q", query)
            ?.addQueryParameter("limit", limit.toString())
            ?.build()
            ?.toString()

    private fun trackUrl(trackId: String) = "$base/track/$trackId"

    private fun MonochromeRow.toSong() = Song(
        videoId = SourceRegistry.trackKey(config.id, id),
        title = title,
        artist = artist,
        albumName = album,
        thumbnailUrl = artwork,
        durationText = durationSec?.let { String.format(Locale.ROOT, "%d:%02d", it / 60, it % 60) },
        // Every row is offered as lossless and checked when opened — the
        // search answer says nothing about which file is behind it.
        sourceQuality = ModuleSource.LOSSLESS,
        isExplicit = explicit,
    )

    internal companion object {
        const val DEFAULT_BASE = "https://tracks.monochrome.st"

        /** How many rows to ask for: the resolver opens the best three at most. */
        const val SEARCH_LIMIT = 15

        /** `fLaC`, then the STREAMINFO block's header and body. */
        const val HEAD_BYTES = 42

        const val SEARCH_TTL_MS = 10 * 60 * 1000L

        /** The file behind an id doesn't change, but a fresh check now and then is cheap. */
        const val PROBE_TTL_MS = 30 * 60 * 1000L

        /** How long to leave the server alone after a 429 that didn't say. */
        const val DEFAULT_RETRY_MS = 1_000L

        /** The most of a stated `Retry-After` that is believed. */
        const val MAX_RETRY_MS = 30_000L

        /** The longest a call sits waiting for the quiet window to pass. */
        const val MAX_QUIET_WAIT_MS = 3_000L

        /** Files opened at once; see [probeGate]. */
        const val MAX_PARALLEL_PROBES = 2

        @Volatile
        private var lastThrottledAtMs = 0L

        @Volatile
        private var lastQuietUntilMs = 0L

        /** How much longer the server has asked to be left alone, if it still has. */
        fun quietForMs(): Long = (lastQuietUntilMs - System.currentTimeMillis()).coerceAtLeast(0L)

        /**
         * Whether Monochrome turned a call away for being asked too often at
         * or after [sinceMs]. A lookup that found nothing meanwhile learned
         * nothing about the track, only about the server.
         */
        fun throttledSince(sinceMs: Long): Boolean = lastThrottledAtMs >= sinceMs

        /**
         * How long a 429 asked to be left alone: the `Retry-After` seconds when
         * it sent them, [DEFAULT_RETRY_MS] when it sent nothing or a date.
         */
        fun backoffMs(streak: Int): Long =
            (DEFAULT_RETRY_MS shl (streak - 1).coerceIn(0, 5)).coerceAtMost(MAX_RETRY_MS)

        fun retryAfterMs(header: String?): Long =
            header?.trim()?.toDoubleOrNull()?.times(1000)?.toLong()
                ?.coerceIn(DEFAULT_RETRY_MS / 2, MAX_RETRY_MS)
                ?: DEFAULT_RETRY_MS

        private val USER_AGENT = "BitChord/v${BuildConfig.VERSION_NAME}"

        /** The ids the catalogue issues: long decimal numbers. Kept loose, but never a path. */
        private val TRACK_ID = Regex("""[A-Za-z0-9_-]{1,64}""")

        /**
         * Short, because the live resolve waits on these before audio starts
         * and the next source is always there to fall back to.
         */
        private val searchClient: OkHttpClient by lazy {
            Http.client.newBuilder().callTimeout(6, TimeUnit.SECONDS).build()
        }
        private val probeClient: OkHttpClient by lazy {
            Http.client.newBuilder().callTimeout(5, TimeUnit.SECONDS).build()
        }

        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
            coerceInputValues = true
        }

        /**
         * The playable rows in a search answer, in the server's order.
         *
         * A row with no id or title can't be matched or opened, and one marked
         * `playable: false` would only be a miss later.
         */
        fun rowsOf(body: String): List<MonochromeRow> =
            json.decodeFromString<MonochromeSearch>(body).tracks.mapNotNull { track ->
                if (track.playable == false) return@mapNotNull null
                val id = (track.trackId ?: track.id)?.takeIf { TRACK_ID.matches(it) } ?: return@mapNotNull null
                val title = track.title?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                MonochromeRow(
                    id = id,
                    title = title,
                    artist = track.artistNames.filter { it.isNotBlank() }.joinToString(", ")
                        .ifBlank { "Unknown Artist" },
                    album = (track.albumTitle ?: track.releaseTitle)?.takeIf { it.isNotBlank() },
                    // Milliseconds now; seconds in older answers. No track is
                    // under ten seconds long in ms or over ten thousand in s.
                    durationSec = track.duration?.takeIf { it > 0 }
                        ?.let { if (it > 10_000) (it / 1000).toInt() else it.toInt() },
                    artwork = track.artwork?.replace(Regex("^http://"), "https://"),
                    explicit = track.explicit,
                )
            }.distinctBy { it.id }

        /**
         * The whole file's size from a probe's answer: the total after the
         * slash of a `206`'s `Content-Range` (`bytes 0-41/51478920`), or a
         * `200`'s `Content-Length` when the server ignored the range. Null
         * when neither says, or the total is the `*` of an unknown length.
         */
        fun totalBytes(code: Int, contentRange: String?, contentLength: String?): Long? {
            val total = if (code == 206) {
                contentRange?.substringAfterLast('/', "")?.trim()?.toLongOrNull()
            } else {
                contentLength?.trim()?.toLongOrNull()
            }
            return total?.takeIf { it > 0 }
        }

        /**
         * The stream's average bitrate, container and all — a FLAC has no
         * other figure to state. Null when either half isn't known.
         */
        fun bitrateKbps(totalBytes: Long?, durationSec: Int?): Int? {
            if (totalBytes == null || durationSec == null || durationSec <= 0) return null
            return (totalBytes * 8 / (durationSec * 1000L)).toInt().takeIf { it > 0 }
        }

        fun isFlac(head: ByteArray): Boolean =
            head.size >= 4 && head[0] == 'f'.code.toByte() && head[1] == 'L'.code.toByte() &&
                head[2] == 'a'.code.toByte() && head[3] == 'C'.code.toByte()

        /**
         * The bit depth and sample rate from a FLAC's head, or null when the
         * bytes don't hold a readable STREAMINFO.
         *
         * After `fLaC` comes a 4-byte block header (type 0 = STREAMINFO,
         * length 34), then: min/max block size (4 bytes), min/max frame size
         * (6), and then 20 bits of sample rate, 3 of channels − 1 and 5 of
         * bits per sample − 1. Nothing is assumed when they're missing: a
         * format left blank is honest, where a guessed 16/44.1 would be shown
         * to the listener as a fact.
         */
        fun flacInfo(head: ByteArray): FlacInfo? {
            if (!isFlac(head) || head.size < 26) return null
            val b = { i: Int -> head[i].toInt() and 0xff }
            if ((b(4) and 0x7f) != 0) return null
            val rate = (b(18) shl 12) or (b(19) shl 4) or (b(20) shr 4)
            val bits = (((b(20) and 0x01) shl 4) or (b(21) shr 4)) + 1
            if (rate !in 1..655_350 || bits !in 4..32) return null
            return FlacInfo(bitDepth = bits, sampleRateHz = rate)
        }
    }
}

/** What a FLAC's STREAMINFO states. */
internal data class FlacInfo(val bitDepth: Int, val sampleRateHz: Int)

/** One search row, reduced to what matching and opening need. */
internal data class MonochromeRow(
    val id: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationSec: Int?,
    val artwork: String?,
    val explicit: Boolean?,
)

@Serializable
internal data class MonochromeSearch(val tracks: List<MonochromeTrack> = emptyList())

/** A row as `/search/tracks` sends it; every field optional, since the server owes us nothing. */
@Serializable
internal data class MonochromeTrack(
    val id: String? = null,
    val trackId: String? = null,
    val title: String? = null,
    val artistNames: List<String> = emptyList(),
    val albumTitle: String? = null,
    val releaseTitle: String? = null,
    val duration: Long? = null,
    val explicit: Boolean? = null,
    val playable: Boolean? = null,
    val artwork: String? = null,
)
