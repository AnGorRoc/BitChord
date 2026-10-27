package com.music.bitchord.data.sources

import kotlinx.coroutines.delay

/**
 * How fast the listener is moving between tracks, so a catalogue isn't asked
 * about songs they are already leaving.
 *
 * Every track change starts lookups for the track landed on and warms the one
 * after it. Someone flicking through a queue starts dozens of those a minute
 * for songs that will never be heard, and it is that stream of requests, not
 * anything a listener does while listening, that draws a rate limit — which
 * then costs them the lossless copy of the song they finally stop on.
 *
 * Nothing is held back while the listener is simply listening. Only once
 * [BROWSING_CHANGES] skips have come within [BROWSING_WINDOW_MS] does [settle]
 * make a lookup wait until [settleMs] has gone by without another one. The
 * callers are cancelled when the track they were for is skipped, so a lookup
 * for a song passed over never reaches the network at all.
 */
class SkipPace(
    private val settleMs: Long = SETTLE_MS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val skips = ArrayDeque<Long>()

    /** The listener moved to another track by hand. Automatic advances aren't browsing. */
    fun noteSkip() {
        val at = now()
        synchronized(skips) {
            skips.addLast(at)
            while (skips.size > BROWSING_CHANGES || at - skips.first() > BROWSING_WINDOW_MS) skips.removeFirst()
        }
    }

    /** Whether the last few skips came fast enough to be flicking through the queue. */
    fun isBrowsing(): Boolean = synchronized(skips) {
        skips.size >= BROWSING_CHANGES && now() - skips.first() <= BROWSING_WINDOW_MS
    }

    /**
     * Returns at once unless the listener is browsing, in which case it waits
     * for the skipping to stop. Cancellable, which is the point: a caller whose
     * track was skipped is cancelled here, before it asks anything.
     *
     * Gives up waiting after [MAX_WAIT_MS], so a lookup that outlives its
     * track is not held forever by a listener who never stops.
     */
    suspend fun settle() {
        val started = now()
        while (isBrowsing() && now() - started < MAX_WAIT_MS) {
            val last = synchronized(skips) { skips.lastOrNull() } ?: return
            val quietFor = now() - last
            if (quietFor >= settleMs) return
            delay(settleMs - quietFor)
        }
    }

    /** Forgets every skip. For tests. */
    internal fun reset() = synchronized(skips) { skips.clear() }

    companion object {
        /** The listener's pace, shared by every source that asks. */
        val shared = SkipPace()

        const val BROWSING_CHANGES = 3
        const val BROWSING_WINDOW_MS = 10_000L
        const val SETTLE_MS = 1_500L
        const val MAX_WAIT_MS = 8_000L
    }
}
