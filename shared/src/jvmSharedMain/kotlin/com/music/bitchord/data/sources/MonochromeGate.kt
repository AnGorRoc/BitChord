package com.music.bitchord.data.sources

import com.music.bitchord.data.TrackLog
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Whether Monochrome's file endpoint is worth asking right now — one answer
 * for every part of the app that asks it.
 *
 * ### What it is protecting against
 *
 * `/track` turns callers away with a 429 in episodes of about a minute that
 * have nothing to do with how often they ask. Measured in October 2026: forty
 * requests a minute, six in parallel and 127 MB of whole files all went
 * through, while one request every twenty seconds and nothing else was
 * refused for a minute straight. No `Retry-After`, no rate-limit headers, a
 * body of `oooooooooooooooooof beep boop`. Pacing can't avoid that, so this
 * doesn't try: it makes a refusal cheap. Once the server says no, nobody asks
 * again until it has had time to recover, and what wanted the file falls back
 * to the next source at once instead of retrying into the episode.
 *
 * ### Why an interceptor
 *
 * Every byte from Monochrome comes through [Http.client][com.music.bitchord.data.Http]:
 * the probe that reads a file's head, the player, the audition of an upgrade,
 * read-ahead. Each of them used to learn about a 429 separately or not at
 * all, so one could be backing off while the next walked straight into the
 * same refusal. Sitting in the client, this sees every answer and gates every
 * question, and none of those callers needs to know it exists.
 *
 * ### The states
 *
 *  - **Closed**: requests go through.
 *  - **Open**, for [windowMs] after a 429: requests are answered here with a
 *    429 of our own, marked [SYNTHETIC_HEADER], and nothing reaches the
 *    network. To the caller it is the same refusal, handled the same way.
 *  - **Half-open**, once the window has passed: exactly one request goes
 *    through as a trial. A success closes the circuit and forgets the
 *    history; a 429 opens it again for twice as long, up to [MAX_WINDOW_MS];
 *    anything else (a timeout, a 404) says nothing about the limit and lets
 *    the next request be the trial instead.
 *
 * A 429 that arrives while the circuit is already open is a straggler from
 * before it opened, and doesn't lengthen the window.
 */
class MonochromeGate(
    private val now: () -> Long = { com.music.bitchord.data.MonotonicClock.nowMs() },
) : Interceptor {

    private val hosts: MutableSet<String> = ConcurrentHashMap.newKeySet<String>().apply { add(DEFAULT_HOST) }

    private var openUntilMs = 0L

    /** 429s in a row with no success in between; zero is closed. */
    private var trips = 0

    private var trialInFlight = false

    @Volatile
    private var lastRefusedAtMs = 0L

    /**
     * Gates [host] too: a Monochrome instance configured at another address,
     * or a test server. The shipped one is always gated.
     */
    fun watch(host: String) {
        hosts += host
    }

    /** Whether [url] is one of the files this gates. The search is limited apart and never was refused. */
    fun gates(url: HttpUrl): Boolean = url.host in hosts && url.encodedPath.startsWith("/track/")

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!gates(request.url)) return chain.proceed(request)
        val trial = when (admit()) {
            Admission.REFUSED -> return refusal(chain)
            Admission.TRIAL -> true
            Admission.OPEN_DOOR -> false
        }
        // Settled whatever happens, not only on an IOException: a trial that
        // ended on any other throwable and was never settled would leave the
        // circuit answering 429 itself until the process died.
        var code: Int? = null
        try {
            return chain.proceed(request).also { code = it.code }
        } finally {
            settle(code, trial)
        }
    }

    private enum class Admission { OPEN_DOOR, TRIAL, REFUSED }

    private fun admit(): Admission = synchronized(this) {
        val at = now()
        when {
            at < openUntilMs -> Admission.REFUSED
            trips == 0 -> Admission.OPEN_DOOR
            trialInFlight -> Admission.REFUSED
            else -> {
                trialInFlight = true
                Admission.TRIAL
            }
        }.also { if (it == Admission.REFUSED) lastRefusedAtMs = at }
    }

    private fun settle(code: Int?, trial: Boolean) = synchronized(this) {
        if (trial) trialInFlight = false
        val at = now()
        when {
            code == 429 -> {
                lastRefusedAtMs = at
                if (at < openUntilMs) return@synchronized
                trips++
                openUntilMs = at + windowMs(trips)
                TrackLog.w(TAG, "Monochrome refused a file; leaving it alone for ${windowMs(trips) / 1000}s (trip $trips)")
            }
            code != null && code in 200..299 && trial -> {
                trips = 0
                TrackLog.d(TAG, "Monochrome is answering again")
            }
        }
    }

    private fun refusal(chain: Interceptor.Chain): Response = Response.Builder()
        .request(chain.request())
        .protocol(Protocol.HTTP_1_1)
        .code(429)
        .message("Too Many Requests")
        .header(SYNTHETIC_HEADER, "1")
        .header("Retry-After", ((remainingMs() + 999) / 1000).coerceAtLeast(1).toString())
        .body("".toResponseBody())
        .build()

    /** How much longer the circuit stays open; zero once a trial may be made. */
    fun remainingMs(): Long = synchronized(this) { (openUntilMs - now()).coerceAtLeast(0L) }

    /**
     * Whether a file asked for at or after [sinceMs] was refused, by the
     * server or by this. A lookup that found nothing meanwhile learned nothing
     * about the track, only about the server.
     */
    fun refusedSince(sinceMs: Long): Boolean = lastRefusedAtMs >= sinceMs

    /**
     * Whether a lookup that can wait — a warm-up, a second look — should leave
     * Monochrome out right now. A pure question: it changes nothing, so it can
     * be asked from a filter, twice, or by something that never acts on it.
     */
    fun isResting(): Boolean = synchronized(this) {
        now() < openUntilMs || (trips > 0 && trialInFlight)
    }

    /**
     * Records that a lookup was left out because [isResting], which counts as
     * a refusal for [refusedSince]: the second look comes back for the track
     * rather than taking the silence as Monochrome not having it.
     */
    fun noteTurnedAway() = synchronized(this) {
        lastRefusedAtMs = now()
    }

    /** Back to closed with no history. For tests. */
    fun reset() = synchronized(this) {
        openUntilMs = 0L
        trips = 0
        trialInFlight = false
        lastRefusedAtMs = 0L
    }

    companion object {
        /** The one every request goes through; installed in [Http.client][com.music.bitchord.data.Http]. */
        val shared = MonochromeGate()

        const val DEFAULT_HOST = "tracks.monochrome.st"

        /** Set on a refusal made here, so it can be told from the server's own. */
        const val SYNTHETIC_HEADER = "X-BitChord-Circuit"

        /** A measured episode lasts about a minute; this outlasts one. */
        const val BASE_WINDOW_MS = 90_000L

        const val MAX_WINDOW_MS = 10 * 60_000L

        fun windowMs(trips: Int): Long =
            (BASE_WINDOW_MS shl (trips - 1).coerceIn(0, 8)).coerceAtMost(MAX_WINDOW_MS)

        private const val TAG = "BitChord"
    }
}
