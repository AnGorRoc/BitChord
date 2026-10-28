package com.music.bitchord.playback

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.sources.SourceKind
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
        // Do not overwrite an active winner with an unaligned or inferior upgrade reject note
        if (existing?.isWinner == true && !isWinner && (status.contains("menor") || status.contains("inferior"))) {
            return
        }
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
     */
    fun setWinner(sourceId: String, winnerStatus: String? = null) {
        attemptMap.forEach { (id, old) ->
            if (id == sourceId) {
                attemptMap[id] = old.copy(
                    isWinner = true,
                    status = winnerStatus ?: old.status,
                )
                TrackLog.d("SourceTracker", "[${old.displayName}] promoted to WINNER: ${winnerStatus ?: old.status}")
            } else if (old.isWinner) {
                attemptMap[id] = old.copy(
                    isWinner = false,
                    status = "Reemplazado por mayor calidad",
                )
                TrackLog.d("SourceTracker", "[${old.displayName}] demoted from winner (superseded by higher quality)")
            }
        }
        publish()
    }

    private fun publish() {
        val sorted = attemptMap.values.sortedBy { it.kind.rank }
        _current.update { it.copy(reports = sorted) }
    }
}
