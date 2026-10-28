package com.music.bitchord.data.spotify

/**
 * Spotify has no token to send: the sign-in expired or was never made. Typed
 * so a caller decides what to do from the class rather than from the words of
 * the message, which are free to be reworded.
 */
class SpotifySignInExpiredException : IllegalStateException("Spotify sign-in expired")

/** Spotify answered [operation] with HTTP [code]. */
class SpotifyHttpException(val operation: String, val code: Int) :
    IllegalStateException("Spotify $operation failed ($code)")
