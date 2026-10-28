package com.music.bitchord.playback

import android.os.SystemClock
import com.music.bitchord.data.sources.SourceStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Which copy of a track is being served, remembered for as long as the track
 * is being served from it.
 *
 * ExoPlayer opens a source many times over one play: the initial read, a seek
 * past the buffer, a resume, and — the one that matters here — the
 * continuation fetch when playback reaches the end of what the cache holds.
 * Every one of those goes back through the resolving data source, and until
 * this existed, every one of them was free to come back with a *different*
 * answer.
 *
 * That is not a hypothetical. Measured on this device: a track started on
 * YouTube with 57 seconds pre-buffered, played to the end of those 57 seconds,
 * and its continuation fetch resolved — correctly, by its own lights — to a
 * module's 320kbps AAC instead. The player was handed the middle of an MP4
 * where it expected the rest of a WebM, and `MatroskaExtractor` threw
 * `EOFException`:
 *
 * ```
 *   12:09:04  first audio          (YouTube Opus, buffered position 57761)
 *   12:10:12  Source error
 *             Caused by: java.io.EOFException
 *               at MatroskaExtractor.read
 * ```
 *
 * Two sources cannot share one cache entry, and [AudioCache]'s key factory
 * cannot tell them apart: it runs *before* the resolve, off the
 * `bitchord://watch?v=…` URI, and at that point which server will answer is
 * not yet known. So the fix goes the other way round — the entry does not
 * learn who is filling it, the resolver is held to whoever filled it first.
 *
 * [QualityUpgrade] already worked this way for the streams it swaps in, for
 * exactly the same reason, and the reasoning simply hadn't been carried back
 * to the first resolve.
 *
 * ### Why a time limit
 *
 * A module's stream URL is signed and expires — around five hours, on the
 * catalogues in use here. Holding one indefinitely would eventually hand the
 * player a dead URL rather than a wrong one, which is no better. [TTL_MS] is
 * far inside every expiry seen, so a choice is either reused while it is
 * certainly still good or made again from scratch.
 *
 * The limit counts from the last time a choice was *used*, not from when it was
 * made. Counted from the start, a long track paused for a while, or a track
 * pinned two places ahead of the one playing, outlived its choice with its
 * entry half full — and the next open re-ran the race, which is free to land on
 * a different server and finish that entry with a different file. A choice
 * that still stands behind a half-filled entry is not dropped for being idle at
 * all, only once [MAX_PIN_MS] has passed since it was made; and then the bytes
 * it was standing behind are dropped with it — see [onLapsed].
 */
object StreamChoice {

    private class Choice(
        val stream: SourceStream,
        val madeAt: Long,
        @Volatile var usedAt: Long,
        val substituted: Boolean,
    )

    private val chosen = ConcurrentHashMap<String, Choice>()

    /** The clock everything here is measured on; replaceable so tests can move it. */
    internal var clock: () -> Long = { SystemClock.elapsedRealtime() }

    /**
     * Whether some cache entry of the track holds part of a file and not the
     * whole of it, i.e. whether letting its choice go could let another server
     * finish it. Installed by [AudioCache]; nothing is held without it.
     */
    @Volatile
    var holdsPartial: (videoId: String) -> Boolean = { false }

    /**
     * Told when a choice is dropped from over a half-filled entry after
     * [MAX_PIN_MS], so those bytes go with it and the next resolve starts a
     * clean file. Installed by [AudioCache].
     */
    @Volatile
    var onLapsed: (videoId: String) -> Unit = {}

    /**
     * The stream already serving [videoId], or null if this is the first read
     * for it — or it has gone unused long enough that its URL is no longer
     * worth trusting. Asking counts as using it.
     */
    fun of(videoId: String): SourceStream? = lookup(videoId, renew = true)

    /**
     * As [of], without counting as a use. For everything that only wants to
     * know what is chosen - the download scheduler asks about every queued
     * track every second, and if each of those asks renewed the choice it would
     * never expire, and a signed URL could be handed to the player hours after
     * it stopped being good.
     */
    fun peek(videoId: String): SourceStream? = lookup(videoId, renew = false)

    private fun lookup(videoId: String, renew: Boolean): SourceStream? {
        val choice = chosen[videoId] ?: return null
        val now = clock()
        if (now - choice.usedAt > TTL_MS) {
            val held = now - choice.madeAt <= MAX_PIN_MS && holdsPartial(videoId)
            if (!held) {
                if (chosen.remove(videoId, choice) && holdsPartial(videoId)) onLapsed(videoId)
                return null
            }
        }
        if (renew) choice.usedAt = now
        return choice.stream
    }

    private fun Choice.isLive(now: Long): Boolean = now - usedAt <= TTL_MS

    /**
     * Records [stream] for [videoId] unless something is already serving it,
     * and returns whichever stream ends up recorded — the one to read from.
     *
     * Atomic, because two writers now choose for the same track: the player's
     * own resolve and the read-ahead that pins a track before it is reached.
     * Checking and then writing let either overwrite the other after it had
     * started filling the entry, which is the mixed-file seam this object
     * exists to prevent.
     */
    fun rememberIfAbsent(videoId: String, stream: SourceStream, substituted: Boolean): SourceStream {
        makeRoom()
        val now = clock()
        return chosen.compute(videoId) { _, old ->
            if (old != null && old.isLive(now)) old.also { it.usedAt = now } else Choice(stream, now, now, substituted)
        }!!.stream
    }

    /**
     * Records [stream] as the one copy of [videoId] this play is reading.
     *
     * @param substituted whether this came from a source standing in for
     *   YouTube rather than from YouTube itself. Only the resolver knows, and
     *   only [refuseSubstitutes] needs it — a substitution that turns out to be
     *   unplayable has somewhere else to fall back to, and a YouTube stream
     *   that fails has not.
     */
    fun remember(videoId: String, stream: SourceStream, substituted: Boolean) {
        makeRoom()
        val now = clock()
        chosen[videoId] = Choice(stream, now, now, substituted)
    }

    private fun makeRoom() {
        if (chosen.size >= MAX_REMEMBERED) {
            // Drop what can no longer be honoured, and only then the oldest of
            // what can. This used to `clear()`, which is the one eviction
            // capable of causing the corruption this class exists to prevent:
            // every live entry is a promise that a half-filled cache entry will
            // be finished by the stream that started filling it, and emptying
            // the map frees *all* of those entries to be finished by a
            // different server instead.
            //
            // Harmless while only playback wrote here — an entry was made and
            // consumed within one track. Read-ahead now pins the next track
            // before it is reached and caches its bytes on the strength of that
            // pin, so a promise can outlive several other tracks' worth of
            // entries, and the blunt version became reachable.
            val now = clock()
            chosen.entries.removeIf { !it.value.isLive(now) }
            if (chosen.size >= MAX_REMEMBERED) {
                chosen.minByOrNull { it.value.usedAt }?.let { chosen.remove(it.key) }
            }
        }
    }

    /**
     * Whether the copy currently serving [videoId] came from a source standing
     * in for YouTube.
     *
     * Read on the recovery path, before [forget], to tell a track that died on
     * a substitution from one that died on YouTube's own stream. The two have
     * opposite right answers: the first should stop substituting, the second
     * has nothing better to try.
     */
    fun isSubstitute(videoId: String): Boolean = chosen[videoId]?.substituted == true

    /**
     * Releases [videoId] to be resolved afresh.
     *
     * Called when the bytes behind the choice are gone or were never any good:
     * the queue moved past the track, its cache entry was discarded after a
     * read error, or [QualityUpgrade] replaced the whole stream with a better
     * one.
     */
    fun forget(videoId: String) {
        chosen.remove(videoId)
    }

    /**
     * Tracks whose substituted stream refused to serve its bytes, and when.
     *
     * [forget] alone is not enough to recover from one. The search behind a
     * substitution is deterministic — the same module, asked the same query for
     * the same tier, answers with the same URL — and it is also *fast*, because
     * by the second attempt its index and its module are cached. So a retry
     * that is free to substitute again wins the race against YouTube by the
     * same margin it won it the first time, and resolves straight back to the
     * URL that just failed. Three attempts of that is a track that never plays
     * at all, while a working YouTube URL sits in
     * [StreamResolver][com.music.bitchord.data.innertube.StreamResolver]'s cache
     * unused.
     *
     * ### Why this expires
     *
     * The first version of this held a refusal for the life of the process, on
     * the reasoning that a deterministic search deserves a permanent answer.
     * Nothing measured supports going that far. What still reaches here is a URL
     * that was well formed and would not serve anyway, and the ordinary causes
     * of that — a signature that expired between minting and use, a backend
     * having a bad minute — clear up by themselves. Held forever, one of those
     * would cost the lossless copy of every track it touched for the rest of the
     * session: a real loss traded against an unproven gain. So the refusal is a
     * cooling-off period, not a verdict — long enough to get the track playing
     * and keep it playing, short enough to ask again in the same sitting.
     *
     * Most of what used to arrive here doesn't any more: [ModuleSource.malformed]
     * rejects a URL carrying its own origin twice before anything tries to play
     * it. This is the net under that, for the failures only playback can find.
     */
    private val refusedSubstitutes = ConcurrentHashMap<String, Long>()

    /**
     * Stops [videoId] being substituted for [REFUSAL_MS], and sends it to
     * YouTube instead. Called from the recovery path; see
     * [PlaybackService.recoverFrom].
     */
    fun refuseSubstitutes(videoId: String) {
        if (refusedSubstitutes.size >= MAX_REMEMBERED) refusedSubstitutes.clear()
        refusedSubstitutes[videoId] = SystemClock.elapsedRealtime()
    }

    /** Whether a substitution has broken [videoId] recently enough to still count. */
    fun substitutesRefused(videoId: String): Boolean {
        val at = refusedSubstitutes[videoId] ?: return false
        if (SystemClock.elapsedRealtime() - at <= REFUSAL_MS) return true
        refusedSubstitutes.remove(videoId)
        return false
    }

    /** How long a choice may sit unused: longer than any one pause or play, far short of any signed URL's life. */
    internal const val TTL_MS = 15 * 60 * 1000L

    /**
     * The most a choice standing behind a half-filled entry is held, however
     * recently it was used. Well inside the roughly five hours a signed URL
     * was seen to live, so the stream it promises is still there to finish
     * the file with.
     */
    internal const val MAX_PIN_MS = 2 * 60 * 60 * 1000L

    /**
     * How long a track stays off substitution after one broke it.
     *
     * Comfortably longer than the recovery it exists to protect — the retry
     * happens within seconds — and longer than a play of the track, so nothing
     * swaps back mid-song. Short enough that a module which was briefly
     * returning bad URLs gets another chance inside the same listening session.
     */
    private const val REFUSAL_MS = 10 * 60 * 1000L

    private const val MAX_REMEMBERED = 32
}
