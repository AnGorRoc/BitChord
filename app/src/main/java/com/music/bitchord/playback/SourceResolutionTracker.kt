package com.music.bitchord.playback

import android.content.Context
import androidx.annotation.StringRes
import com.music.bitchord.R
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceTrackKeys
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/**
 * Report of an individual audio source resolution attempt for the playing track.
 */
data class SourceAttemptReport(
    val sourceId: String,
    val displayName: String,
    val kind: SourceKind,
    val status: String,
    val detail: String? = null,
    val isWinner: Boolean = false,
    val timestampMs: Long = System.currentTimeMillis(),
)

/**
 * Live state of all source resolution reports for the current track.
 */
data class TrackResolutionState(
    val mediaId: String = "",
    val title: String = "",
    val reports: List<SourceAttemptReport> = emptyList(),
)

/**
 * Tracks what every source returned during track resolution and quality upgrade passes.
 *
 * Exposes plain-language statuses for the Audio Pipeline UI so listeners can see
 * exactly why a track played from one source rather than another (e.g. not in catalogue,
 * length mismatch, network error, data ceiling, or superseded by lossless).
 */
object SourceResolutionTracker {

    private val _current = MutableStateFlow(TrackResolutionState())
    val current: StateFlow<TrackResolutionState> = _current.asStateFlow()

    private val attemptMap = ConcurrentHashMap<String, SourceAttemptReport>()

    private var appContext: Context? = null

    /** Gives [text] the strings to read; called once from the application. */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * A status line from the string table. The statuses are stored as text, so
     * one is written in the language in force when it happened. Before [init]
     * — plain unit tests — it reads as the resource id, which is still stable
     * to compare against.
     */
    fun text(@StringRes id: Int, vararg args: Any): String =
        appContext?.getString(id, *args) ?: "#$id"

    /**
     * Clears prior reports when advancing or changing to a new track.
     */
    fun resetForTrack(mediaId: String, title: String) {
        attemptMap.clear()
        _current.value = TrackResolutionState(mediaId = mediaId, title = title, reports = emptyList())
        TrackLog.d("SourceTracker", "Reset for track '$title' ($mediaId)", about = mediaId)
    }

    /**
     * Records or updates the resolution outcome for [sourceId].
     *
     * A later lookup that comes back empty or failing for the source that is
     * currently serving the audio does not demote it: the stream is still
     * playing, so the row keeps saying so. Only [setWinner] changes who wins.
     */
    fun record(
        sourceId: String,
        displayName: String,
        kind: SourceKind,
        status: String,
        detail: String? = null,
        isWinner: Boolean = false,
    ) {
        val existing = attemptMap[sourceId]
        if (existing?.isWinner == true && !isWinner) return
        val report = SourceAttemptReport(
            sourceId = sourceId,
            displayName = displayName,
            kind = kind,
            status = status,
            detail = detail,
            isWinner = isWinner,
        )
        attemptMap[sourceId] = report
        TrackLog.d("SourceTracker", "[$displayName] -> $status${detail?.let { " ($it)" } ?: ""}${if (isWinner) " [WINNER]" else ""}")
        publish()
    }

    /**
     * Declares [sourceId] as the active winning source that is currently serving the audio.
     * Any previously active winner is demoted (e.g. when upgraded to lossless).
     *
     * A winner nobody recorded a lookup for (a module or upgrade stream that
     * was never asked about by name) gets a row of its own, so the playing
     * source is always listed. When [mediaId] names a track other than the one
     * being tracked, the call is a late report from a previous track and is
     * dropped rather than demoting the current winner.
     */
    fun setWinner(sourceId: String, winnerStatus: String? = null, mediaId: String? = null) {
        if (mediaId != null && !sameTrack(mediaId, _current.value.mediaId)) {
            TrackLog.d("SourceTracker", "ignored winner '$sourceId' for $mediaId; tracking ${_current.value.mediaId}")
            return
        }
        val base = attemptMap[sourceId] ?: SourceAttemptReport(
            sourceId = sourceId,
            displayName = sourceId,
            kind = SourceKind.MODULE,
            status = winnerStatus ?: text(R.string.pipeline_status_playing),
        )
        attemptMap.forEach { (id, old) ->
            if (id != sourceId && old.isWinner) {
                attemptMap[id] = old.copy(
                    isWinner = false,
                    status = text(R.string.pipeline_status_superseded),
                )
                TrackLog.d("SourceTracker", "[${old.displayName}] demoted from winner (superseded by higher quality)")
            }
        }
        attemptMap[sourceId] = base.copy(
            isWinner = true,
            status = winnerStatus ?: base.status,
        )
        TrackLog.d("SourceTracker", "[${base.displayName}] promoted to WINNER: ${winnerStatus ?: base.status}")
        publish()
    }

    private fun publish() {
        val sorted = attemptMap.values.sortedBy { it.kind.rank }
        _current.update { it.copy(reports = sorted) }
    }

    /**
     * Whether two track ids name the same track. Source-backed queue items
     * carry `src:<config>::<track>`; the resolver keys its reports by the
     * bare track id, so the two are compared on that part.
     */
    fun sameTrack(a: String?, b: String?): Boolean {
        if (a == null || b == null) return a == b
        if (a == b) return true
        return SourceTrackKeys.parse(a)?.second == b || SourceTrackKeys.parse(b)?.second == a
    }
}
