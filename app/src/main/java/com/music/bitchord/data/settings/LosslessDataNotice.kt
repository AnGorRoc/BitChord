package com.music.bitchord.data.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The quiet heads-up that lossless is streaming over mobile data.
 *
 * The mobile-data ceiling ships at Lossless, and a FLAC runs to 20-50 MB a
 * track, so the setting the listener never touched can cost them real money.
 * The setting is theirs and is left alone; this only says, once, what it is
 * doing and where to change it.
 *
 * It is meant to fade out of the listener's life rather than nag:
 *  - at most once per app session, and only when the decoder is really being
 *    fed lossless audio that came from a source — never a file on the phone,
 *    and never on Wi-Fi;
 *  - acting on it, or closing it, retires it for good;
 *  - letting it run out [IGNORES_BEFORE_RETIRING] times retires it too, since
 *    a notice nobody reacts to is one nobody wants.
 *
 * The rules are the pure functions here, so they can be tested without a
 * device; [MainActivity][com.music.bitchord.MainActivity] shows it.
 */
object LosslessDataNotice {

    /** How long it stays up before counting as ignored. */
    const val SHOW_MS = 6_000L

    const val IGNORES_BEFORE_RETIRING = 2

    enum class Outcome {
        /** The action was tapped: the listener went to the setting. */
        ACTED,

        /** The close button was tapped: seen, and not wanted again. */
        CLOSED,

        /** It ran out with nobody touching it. */
        IGNORED,
    }

    /**
     * Whether the notice is due right now.
     *
     * [streamingLossless] is the decoder's word and the source's together —
     * lossless audio that was fetched, not read off the phone. [ceilingOverride]
     * set means something else (the watch) is already capping the stream, so
     * the mobile-data setting isn't what is in effect.
     */
    fun shouldShow(
        streamingLossless: Boolean,
        metered: Boolean?,
        cellularCeiling: AudioQuality,
        ceilingOverride: AudioQuality?,
        done: Boolean,
        shownThisSession: Boolean,
    ): Boolean =
        streamingLossless &&
            metered == true &&
            cellularCeiling == AudioQuality.LOSSLESS &&
            ceilingOverride == null &&
            !done &&
            !shownThisSession

    /** The persisted (ignored count, retired) once the notice ends in [outcome]. */
    fun after(outcome: Outcome, ignored: Int): Pair<Int, Boolean> = when (outcome) {
        Outcome.ACTED, Outcome.CLOSED -> ignored to true
        Outcome.IGNORED -> (ignored + 1).let { it to (it >= IGNORES_BEFORE_RETIRING) }
    }

    /** Process-lifetime, which is what "once per session" means here. */
    @Volatile
    var shownThisSession: Boolean = false

    /** Writes [outcome]'s consequence to [AppSettings]. */
    fun record(outcome: Outcome) {
        val (ignored, done) = after(outcome, AppSettings.losslessDataNoticeIgnored.value)
        AppSettings.setLosslessDataNoticeIgnored(ignored)
        if (done) AppSettings.setLosslessDataNoticeDone(true)
    }

    private val _openCellularQuality = MutableStateFlow(false)

    /**
     * A one-shot request for Settings to open its mobile-data quality picker,
     * raised by the notice's action and cleared by whoever honours it.
     */
    val openCellularQuality: StateFlow<Boolean> = _openCellularQuality.asStateFlow()

    fun requestCellularQuality() {
        _openCellularQuality.value = true
    }

    fun cellularQualityHandled() {
        _openCellularQuality.value = false
    }
}
