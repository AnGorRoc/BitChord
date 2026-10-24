package com.music.bitchord.playback.cast

/**
 * URI schemes a receiver can never open, whatever the resolver makes of them.
 *
 * A receiver fetches an http(s) stream by itself. It cannot reach a file on the
 * phone, cannot be sent the headers a network share needs, and has no way of
 * resolving an address that only this app understands. These are the schemes that
 * mean the audio lives somewhere a receiver has no path to.
 *
 * What is deliberately *not* here is as important. `bitchord://` is how nearly
 * every track in the app is spelled — it names a source and a track id, and the
 * resolver turns it into a real stream before the receiver ever sees it. Asking
 * "is this http(s)?" instead of "is this local?" refuses every track the app
 * owns, which is what happened the first time round.
 */
internal val LOCAL_SCHEMES: Set<String> = setOf(
    "file",
    "content",
    "android.resource",
    "smb",
    "ftp",
    "webdav",
    "dav",
)

/**
 * Whether a receiver could ever open a track spelled with this scheme.
 *
 * Separate from the set so it can be asked without a URI, and so the question
 * itself is under test rather than only the list — the list being right is no
 * use if the test of it is the wrong way round. A track with no scheme at all
 * answers no: there is nothing for the receiver to fetch.
 */
internal fun receiverCanOpen(scheme: String?): Boolean = scheme != null && scheme !in LOCAL_SCHEMES