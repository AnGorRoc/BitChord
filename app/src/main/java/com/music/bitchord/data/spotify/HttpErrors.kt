package com.music.bitchord.data.spotify

import io.ktor.client.plugins.ResponseException

/**
 * The HTTP status a failure carries, read from what it is rather than from
 * what it says: Ktor's [ResponseException] and Spotify's own
 * [SpotifyHttpException] hold the code, and either can sit anywhere along the
 * cause chain of whatever a repository wrapped it in.
 *
 * Only when neither is there does the message get read, and then only where a
 * status code is *placed* — after "invalid:", "error:", "failed (" and so on,
 * or at the very start — so a song title or a video id that happens to
 * contain "401" is not taken for one.
 */
internal fun httpStatusOf(e: Throwable): Int? {
    var cause: Throwable? = e
    var depth = 0
    while (cause != null && depth++ < MAX_CAUSE_DEPTH) {
        when (cause) {
            is ResponseException -> return cause.response.status.value
            is SpotifyHttpException -> return cause.code
        }
        cause = cause.cause
    }
    return PLACED_STATUS.find(e.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
}

private const val MAX_CAUSE_DEPTH = 8

private val PLACED_STATUS = Regex("""(?:^|invalid|error|failed|HTTP|status|\()\W{0,3}([1-5]\d{2})\b""", RegexOption.IGNORE_CASE)
