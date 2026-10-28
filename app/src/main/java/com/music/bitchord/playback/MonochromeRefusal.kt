package com.music.bitchord.playback

import androidx.media3.datasource.HttpDataSource
import com.music.bitchord.data.sources.MonochromeGate
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Whether [error] is Monochrome's file endpoint answering 429 - the server's
 * own, or [MonochromeGate]'s while its circuit is open. Never a statement
 * about the track: the same file plays once the episode passes.
 */
internal fun isMonochromeRefusal(error: Throwable?): Boolean {
    var cause = error
    var depth = 0
    while (cause != null && depth++ < MAX_CAUSE_DEPTH) {
        if (cause is HttpDataSource.InvalidResponseCodeException && cause.responseCode == 429) {
            val url = cause.dataSpec.uri.toString().toHttpUrlOrNull()
            return url != null && MonochromeGate.shared.gates(url)
        }
        cause = cause.cause?.takeIf { it !== cause }
    }
    return false
}

private const val MAX_CAUSE_DEPTH = 8
