package com.music.bitchord.playback

/**
 * What the download scheduler should do next, decided from one snapshot of the
 * player, the queue and the network — see [DownloadScheduler].
 *
 * One task at a time, never two. The network is one pipe shared with the
 * player, NewPipe extraction collapses when run in parallel (1.8s alone, 30s
 * with three in flight — see [AudioCache]), and Monochrome turns away a burst.
 * So instead of several read-aheads each guessing when it is safe to run,
 * everything goes through this one ordering:
 *
 *  1. **The track playing, if it is in trouble.** Nothing else runs, so the
 *     player has the whole connection. Side work cannot speed up the byte the
 *     player is waiting on; it can only slow it down.
 *  2. **The better copy of the track playing.** Looking for it is cheap, and
 *     doing it first means the rest of the track is downloaded in the quality
 *     it will play in, not in the copy about to be replaced.
 *  3. **The next track's opening, once its turn is close** — so the change
 *     never waits on the network, however the rest of the list is going.
 *  4. **The rest of the track playing.**
 *  5. **The next track: its stream settled, its opening on disk**, then the
 *     URL of the one after.
 *  6. **The next track in full**, once the playing one is complete — and then
 *     the one after it, while there is time to spare.
 *
 * A pause is simply the case with the most time to spare: nothing is
 * running down, so the list is worked through to the end.
 *
 * On a metered connection the next tracks stop at their opening. Their full
 * files — tens of megabytes each when lossless — are for unmetered networks.
 * The track playing is still completed: it is going to be played either way.
 *
 * Pure and clock-free, so every rule can be tested on its own.
 */
object DownloadPlan {

    /** The track playing, as the plan needs it. */
    data class Current(
        val mediaId: String,
        /** Playback time left, at the current speed. */
        val remainingMs: Long,
        /** How far the player's buffer reaches past the playhead. */
        val bufferedAheadMs: Long,
        /** Bytes of the playing rendition not on disk yet; -1 when its size isn't known. */
        val missingBytes: Long,
        /** Whether filling it is worth asking for now: something is missing and the last try wasn't refused moments ago. */
        val fillDue: Boolean,
    )

    /** One of the next tracks, in play order. */
    data class Upcoming(
        val mediaId: String,
        val durationMs: Long,
        /** The stream it will play from is settled — pinned, or there is nothing to choose between. */
        val prepared: Boolean,
        val headCached: Boolean,
        val fullyCached: Boolean,
        /** Whether a full download is worth asking for now; see [Current.fillDue]. */
        val fillDue: Boolean,
        /** Its YouTube URL has been resolved already, or doesn't need to be. */
        val warmed: Boolean,
        /** The size of the file it will play, -1 when unknown. */
        val sizeBytes: Long,
    )

    data class Snapshot(
        /** The player has a current item and is ready or buffering. */
        val prepared: Boolean,
        val offline: Boolean,
        /** The phone's player is not the speaker: casting, or the watch streaming. */
        val elsewhere: Boolean,
        val playWhenReady: Boolean,
        /** Asked to play and waiting on data. */
        val stalled: Boolean,
        /** The player's own loader is pulling bytes right now. */
        val loading: Boolean,
        /** A crossfade or Automix blend is in progress. */
        val transitioning: Boolean,
        /** An upgrade of the playing track is being proved or swapped in. */
        val upgradeBusy: Boolean,
        /** The playing track may have a better copy, and it is time to look. */
        val upgradeLookDue: Boolean,
        /** Direct Source Stream: tracks ahead are its business, not this plan's. */
        val direct: Boolean,
        val current: Current?,
        val upcoming: List<Upcoming>,
        /** How long the playing track has been the current one. */
        val currentForMs: Long,
        val bandwidthBps: Long,
        val metered: Boolean,
        /** How many of the next tracks' full files the cache budget leaves room for. */
        val roomForFullTracks: Int,
    )

    sealed interface Task {
        data object CurrentUpgrade : Task
        data object FillCurrent : Task

        /** Settle the stream of upcoming track [index] and fetch its opening. */
        data class Prepare(val index: Int) : Task

        /** Resolve the YouTube URLs of the upcoming tracks that haven't been. */
        data object WarmUrls : Task

        /** Download upcoming track [index] in full. */
        data class FillNext(val index: Int) : Task
    }

    fun next(s: Snapshot): Task? {
        val current = s.current
        if (!s.prepared || s.offline || s.elsewhere || current == null) return null

        if (isUrgent(s, current)) {
            // A player held back by its own byte ceiling is not short of data,
            // it is out of room to hold it — the rest of the file can still be
            // written to disk for it. One that is loading is using the line.
            return if (!s.loading && current.fillDue && !s.upgradeBusy && !s.transitioning) Task.FillCurrent else null
        }

        if (s.upgradeLookDue && !s.upgradeBusy) return Task.CurrentUpgrade

        val next = s.upcoming.getOrNull(0).takeUnless { s.direct }
        if (next != null && !next.headCached && current.remainingMs < NEXT_DEADLINE_MS) return Task.Prepare(0)

        if (current.fillDue && !s.upgradeBusy) return Task.FillCurrent

        if (next != null && (!next.prepared || !next.headCached) && !s.upgradeBusy && s.currentForMs >= SETTLE_MS) {
            return Task.Prepare(0)
        }

        if (!s.direct && s.upcoming.any { !it.warmed && !it.prepared }) return Task.WarmUrls

        if (next == null || s.metered || current.missingBytes != 0L) return null
        if (!next.fullyCached) {
            return if (next.fillDue && s.roomForFullTracks >= 1) Task.FillNext(0) else null
        }

        val after = s.upcoming.getOrNull(1) ?: return null
        if (s.roomForFullTracks < 2 || !hasTimeFor(s, current, next, after)) return null
        if (!after.prepared || !after.headCached) return if (!s.upgradeBusy) Task.Prepare(1) else null
        if (!after.fullyCached && after.fillDue) return Task.FillNext(1)
        return null
    }

    private fun isUrgent(s: Snapshot, current: Current): Boolean {
        if (s.stalled || s.transitioning) return true
        if (!s.playWhenReady) return false
        if (s.loading && current.bufferedAheadMs < URGENT_BUFFER_MS) return true
        // Will what is missing arrive before it is needed? Only a guess while
        // the size or the speed is unknown, so then it is not made.
        if (current.missingBytes > 0 && s.bandwidthBps > 0) {
            return transferMs(current.missingBytes, s.bandwidthBps) > current.remainingMs * URGENT_SHARE
        }
        return false
    }

    /**
     * Whether the second track can be fetched in full while what is ahead of
     * it plays, with room to spare. Always, while paused.
     */
    private fun hasTimeFor(s: Snapshot, current: Current, next: Upcoming, after: Upcoming): Boolean {
        if (!s.playWhenReady) return true
        if (s.bandwidthBps <= 0) return false
        val size = after.sizeBytes.takeIf { it > 0 } ?: (after.durationMs / 1000 * LOSSLESS_BYTES_PER_SECOND)
        val ahead = current.remainingMs + next.durationMs
        return transferMs(size, s.bandwidthBps) * SPARE_FACTOR < ahead
    }

    private fun transferMs(bytes: Long, bps: Long): Long = bytes * 8 * 1000 / bps

    /** Below this much buffered while the player is still loading, the playing track has the network to itself. */
    const val URGENT_BUFFER_MS = 15_000L

    /** What's missing of the playing track must arrive within this share of its remaining time, or it is urgent. */
    const val URGENT_SHARE = 0.8

    /** Inside this much of the end of the playing track, the next one's opening comes first. */
    const val NEXT_DEADLINE_MS = 60_000L

    /** How long a track must have been current before the next one is prepared, so a run of skips settles first. */
    const val SETTLE_MS = 8_000L

    /** Roughly a CD-quality FLAC, for sizing a file not yet seen. */
    const val LOSSLESS_BYTES_PER_SECOND = 110_000L

    /** How comfortably the second track has to fit in the time ahead of it. */
    const val SPARE_FACTOR = 1.5
}
