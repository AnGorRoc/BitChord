package com.music.bitchord.playback

/**
 * Whether the lossless stall watch has any business sampling the stream
 * playing right now. Only a fetched lossless stream with another copy to fall
 * back to can starve and be helped by lowering its quality; a copy already on
 * disk, a download, a local file or a stream in the middle of being swapped
 * never needs the watch.
 */
object LosslessGuard {

    /** What the service knows about the playing item. */
    data class State(
        val hasItem: Boolean,
        val downgradeEnabled: Boolean,
        /** The source declared the stream lossless. */
        val declaredLossless: Boolean,
        /** Already on the YouTube original, by the listener's choice or a pin. */
        val onOriginal: Boolean,
        /** A downgrade or a version swap is already under way. */
        val swapping: Boolean,
        /** A crossfade or Automix blend is running. */
        val transitioning: Boolean,
        val inParty: Boolean,
        val fullyCached: Boolean,
        /** The watch already found nothing to fall back to for this track. */
        val noFallback: Boolean,
    )

    fun shouldSample(s: State): Boolean = s.hasItem &&
        s.downgradeEnabled &&
        !s.noFallback &&
        s.declaredLossless &&
        !s.onOriginal &&
        !s.swapping &&
        !s.transitioning &&
        !s.inParty &&
        !s.fullyCached
}
