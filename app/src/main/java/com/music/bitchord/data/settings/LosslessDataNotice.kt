package com.music.bitchord.data.settings

import com.music.bitchord.data.NerdStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The quiet heads-up that lossless is streaming over mobile data.
 *
 * The mobile-data ceiling is only ever Lossless when the listener picked it,
 * and a FLAC runs to 20-50 MB a track, so that choice can cost them real money.
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

    /**
     * Suspends until the notice is due, then marks it shown for this session.
     *
     * Watched as flows rather than collected into state by the caller: the
     * stats snapshot is republished as the decoder reports, and reading it
     * where the notice is drawn would recompose the whole app on every one of
     * those. Lossless counts as the decoder sees it, and fetched from a source
     * rather than read off the phone: a local FLAC costs no data.
     */
    suspend fun awaitDue() {
        combine(
            NerdStats.current.map { it != null && it.isLossless && it.claimed?.isLossless == true },
            AppSettings.meteredConnection,
            AppSettings.audioQualityCellular,
            AppSettings.streamCeilingOverride,
            AppSettings.losslessDataNoticeDone,
        ) { streaming, metered, cellular, override, done ->
            shouldShow(
                streamingLossless = streaming,
                metered = metered,
                cellularCeiling = cellular,
                ceilingOverride = override,
                done = done,
                shownThisSession = shownThisSession,
            )
        }.distinctUntilChanged().filter { it }.first()
        shownThisSession = true
    }

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
