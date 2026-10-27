package com.music.bitchord.ui

import android.content.Context
import com.music.bitchord.R
import com.music.bitchord.data.spotify.ExportErrorKind
import com.music.bitchord.data.spotify.ExportEvent
import com.music.bitchord.data.spotify.ExportState

/** What to tell the person when an export ends, in the words of how it ended. */
fun ExportEvent.message(context: Context): String = when (val result = state) {
    is ExportState.Done -> when {
        result.upToDate -> context.getString(R.string.yt_export_up_to_date, title)
        result.skipped > 0 -> context.getString(R.string.yt_export_done_skipped, title, result.skipped)
        else -> context.getString(R.string.yt_export_done, title, result.exported)
    }
    is ExportState.Failed -> when (result.kind) {
        ExportErrorKind.RATE_LIMITED ->
            context.getString(R.string.yt_export_failed_rate, result.done, result.total, title)
        ExportErrorKind.NETWORK ->
            context.getString(R.string.yt_export_failed_network, result.done, result.total, title)
        ExportErrorKind.SIGNED_OUT -> context.getString(R.string.yt_export_failed_signed_out)
        ExportErrorKind.OTHER -> context.getString(R.string.yt_export_failed_other, title, result.detail)
    }
    else -> ""
}
