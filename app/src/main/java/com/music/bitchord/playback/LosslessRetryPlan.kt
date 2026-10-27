package com.music.bitchord.playback

/**
 * How long to play lossy before trying lossless again, after a lossless
 * stream couldn't keep up — see `PlaybackService.lowerQualityToKeepUp`.
 *
 * Three tries ten seconds apart, then three thirty seconds apart, then once a
 * minute for as long as it keeps failing. A successful lossless swap starts
 * the schedule over, via [reset].
 */
class LosslessRetryPlan {

    private var attempts = 0

    /** The wait before the next lossless attempt, counting this one as made. */
    fun nextDelayMs(): Long {
        val step = attempts++
        return when {
            step < TEN_SECOND_TRIES -> 10_000L
            step < TEN_SECOND_TRIES + THIRTY_SECOND_TRIES -> 30_000L
            else -> 60_000L
        }
    }

    /** Lossless came through: the next failure starts from the first ten-second wait. */
    fun reset() {
        attempts = 0
    }

    private companion object {
        const val TEN_SECOND_TRIES = 3
        const val THIRTY_SECOND_TRIES = 3
    }
}
