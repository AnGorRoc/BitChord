package com.music.bitchord.playback

import kotlin.math.abs

/**
 * The arithmetic of bringing a silent standby player onto the playing one for
 * a version swap - how its position readout is read, what to learn from it and
 * how hard to nudge it. The service owns the players and the waiting; what is
 * decided from the numbers is decided here, so it can be tested without either.
 *
 * What is said here about a readout is about the position the app reads from
 * the player (`currentPosition`), as measured on the devices this was tuned on.
 * It is not a measurement of what comes out of the speaker.
 */
object StandbyLock {

    /**
     * Nothing is read for this long after the standby starts; its readout is
     * still estimated. Longer than it looks like it needs to be because the
     * readout's corrections were seen as late as a second and a half in. The
     * outgoing player keeps playing through all of this, so it costs nothing
     * but the moment the better copy arrives.
     */
    const val QUIET_MS = 1_800L

    /** The longest the readout is waited on for the first reading. */
    const val WINDOW_MS = 3_000L

    /** Readings that must agree for the readout to count as still. */
    const val STABLE_SAMPLES = 15

    /** The most the last [STABLE_SAMPLES] readings may differ by and count as agreeing. */
    const val STABLE_SPREAD_MS = 6L

    /** The width of the stretch of readings that is taken as where they gather. */
    const val CLUSTER_MS = 40L

    const val SAMPLE_GAP_MS = 40L

    /**
     * An error this small is left alone: a few milliseconds above what the
     * readout itself can resolve, which nothing further could beat.
     */
    const val TOLERANCE_MS = 8L

    /** The ceiling on the seek latency that is carried to the next swap. */
    const val MAX_LATENCY_MS = 1_500L

    /** ...and on how much earlier than the playhead the next seek may aim. */
    const val MAX_EARLY_MS = 500L

    /**
     * The nudge is slow: no further than [NUDGE_MAX_ERROR_MS], at no more than
     * [NUDGE_MAX_RATE] off real time, and for at least [NUDGE_MIN_SPAN_MS] so a
     * small error is a gentle one. Checked again after [VERIFY_QUIET_MS] for the
     * log's sake.
     */
    const val NUDGE_MAX_ERROR_MS = 600L
    const val NUDGE_MAX_RATE = 0.25f
    const val NUDGE_MIN_SPAN_MS = 800L
    const val VERIFY_QUIET_MS = 600L
    const val VERIFY_WINDOW_MS = 2_000L

    /** How much of the way to the newest reading the carried latency moves each swap. */
    private const val LEARNING_RATE = 0.7

    /**
     * The seek latency to carry to the next swap, having just read the standby
     * [errorMs] off its target at playback [speed]. Moves most of the way
     * rather than all of it, since one reading is still one reading; positions
     * are media time and the latency is wall time, hence the division by speed.
     */
    fun learnedLatencyMs(currentMs: Long, errorMs: Long, speed: Float): Long {
        val target = currentMs - (errorMs / speed).toLong()
        return (currentMs + ((target - currentMs) * LEARNING_RATE).toLong())
            .coerceIn(-MAX_EARLY_MS, MAX_LATENCY_MS)
    }

    /** What to do about a standby found [errorMs] off. */
    sealed interface Correction {
        /** Close enough to leave alone. */
        data object None : Correction

        /** Too far to take up by running fast or slow; the fade goes ahead off. */
        data object TooFar : Correction

        /** Run at [rate] times the playback speed for [spanMs]. */
        data class Nudge(val spanMs: Long, val rate: Float) : Correction
    }

    /**
     * Running at `1 - error / (speed * span)` for `span` closes `error`: a
     * standby that is ahead (positive) slows down, one behind speeds up.
     */
    fun correctionFor(errorMs: Long, speed: Float): Correction {
        if (abs(errorMs) <= TOLERANCE_MS) return Correction.None
        if (abs(errorMs) > NUDGE_MAX_ERROR_MS) return Correction.TooFar
        val spanMs = (abs(errorMs) / NUDGE_MAX_RATE).toLong().coerceAtLeast(NUDGE_MIN_SPAN_MS)
        val rate = (1f - errorMs.toFloat() / (speed * spanMs))
            .coerceIn(1f - NUDGE_MAX_RATE, 1f + NUDGE_MAX_RATE)
        return Correction.Nudge(spanMs, rate)
    }

    /**
     * The error to act on once the readout has held still: the median of the
     * last [STABLE_SAMPLES] readings if they agree within [STABLE_SPREAD_MS],
     * otherwise null - not yet.
     */
    fun settledError(errors: List<Long>): Long? {
        if (errors.size < STABLE_SAMPLES) return null
        val recent = errors.takeLast(STABLE_SAMPLES)
        if (recent.max() - recent.min() > STABLE_SPREAD_MS) return null
        return recent.sorted()[recent.size / 2]
    }

    /**
     * The value [samples] gather around: the middle of the [widthMs]-wide
     * stretch of the sorted samples that holds the most of them. Unlike a
     * median it is not pulled toward a minority of outliers on one side. For a
     * readout that never held still: it was seen sitting on a base level and
     * jumping some 280 ms off it every half second or so, and a median of
     * fifteen samples lands on the jumps when they make up a third of them.
     */
    fun densestCluster(samples: List<Long>, widthMs: Long = CLUSTER_MS): Long {
        require(samples.isNotEmpty()) { "no samples to gather" }
        val sorted = samples.sorted()
        var bestStart = 0
        var bestCount = 0
        var end = 0
        for (start in sorted.indices) {
            if (end < start) end = start
            while (end + 1 < sorted.size && sorted[end + 1] - sorted[start] <= widthMs) end++
            if (end - start + 1 > bestCount) {
                bestCount = end - start + 1
                bestStart = start
            }
        }
        val cluster = sorted.subList(bestStart, bestStart + bestCount)
        return cluster[cluster.size / 2]
    }
}
