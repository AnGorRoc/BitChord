package com.music.bitchord.playback

/**
 * Decides when a lossless stream has stopped keeping up with playback, so it
 * can be crossfaded down to a lossy copy before the audio cuts out — see
 * `PlaybackService.checkLosslessKeepingUp`.
 *
 * Fed one sample a second for the playing stream. Two signs count, and only
 * once the opening grace has passed (the buffer is still filling then, and a
 * low reading means nothing):
 *
 *  - **A stall.** The player is waiting for data in the middle of the song.
 *    The music has already stopped; the swap only decides what comes back.
 *  - **A draining buffer.** What is buffered ahead is low and has fallen by
 *    [drainMs] over the last [windowMs] of playback: the download is running
 *    at well under the speed the music plays, and the stall is coming.
 *
 * A jump in position is a seek, which empties the buffer on purpose; the watch
 * starts over from there, grace included.
 */
class LosslessStallWatch(
    private val graceMs: Long = 8_000,
    private val lowWaterMs: Long = 12_000,
    private val windowMs: Long = 6_000,
    private val drainMs: Long = 3_000,
) {
    private var startedAt = -1L
    private var lastAt = -1L
    private var lastPosition = -1L
    private var lastPlaying = false
    private val ahead = ArrayDeque<Pair<Long, Long>>()

    /** Forgets everything: a new track, a new stream or a seek. */
    fun reset() {
        startedAt = -1L
        lastAt = -1L
        lastPosition = -1L
        lastPlaying = false
        ahead.clear()
    }

    /**
     * @param aheadMs buffered position minus playing position.
     * @param stalled waiting for data while meant to be playing.
     * @param loadedToEnd the rest of the track is already buffered.
     * @return true when it is time to lower the quality.
     */
    fun sample(
        nowMs: Long,
        positionMs: Long,
        aheadMs: Long,
        playing: Boolean,
        stalled: Boolean,
        loadedToEnd: Boolean,
    ): Boolean {
        if (loadedToEnd) {
            reset()
            return false
        }
        if (lastAt >= 0) {
            val expected = lastPosition + if (lastPlaying) nowMs - lastAt else 0L
            if (kotlin.math.abs(positionMs - expected) > SEEK_TOLERANCE_MS) reset()
        }
        if (startedAt < 0) startedAt = nowMs
        lastAt = nowMs
        lastPosition = positionMs
        lastPlaying = playing
        val settled = nowMs - startedAt >= graceMs
        if (stalled) return settled
        if (!playing) return false
        ahead.addLast(nowMs to aheadMs)
        while (ahead.isNotEmpty() && nowMs - ahead.first().first > windowMs) ahead.removeFirst()
        if (!settled || aheadMs >= lowWaterMs) return false
        val (oldestAt, oldestAhead) = ahead.first()
        return nowMs - oldestAt >= windowMs - SAMPLE_SLACK_MS && oldestAhead - aheadMs >= drainMs
    }

    private companion object {
        const val SEEK_TOLERANCE_MS = 2_000L

        /** Samples arrive about a second apart, never exactly. */
        const val SAMPLE_SLACK_MS = 1_000L
    }
}
