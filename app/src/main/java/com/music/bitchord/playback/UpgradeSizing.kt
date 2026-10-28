package com.music.bitchord.playback

import com.music.bitchord.playback.smart.VersionAudioAligner

/**
 * How much of an upgraded rendition's opening is worth fetching, and why.
 * Pure, so each rule is testable without a player: the service hands in what
 * it knows and gets a byte count back.
 */
object UpgradeSizing {

    /**
     * What an audition fetches of a copy before it is believed to open. A
     * megabyte because a FLAC header is not a header: STREAMINFO is 34 bytes,
     * but the seek table, the tags and an embedded cover in front of the first
     * audio frame routinely run to hundreds of kilobytes, and a range that stops
     * short of the first frame buys nothing at all.
     */
    const val HEADER_BYTES = 1L * 1024 * 1024

    /**
     * Opening fetched after an upgrade so the track stays analysable. Four
     * megabytes is a little over twelve seconds of lossless - the shortest
     * window Automix's head pass accepts - and many times that for a
     * compressed rendition, which simply finishes sooner.
     */
    const val ANALYSIS_HEAD_BYTES = 4L * 1024 * 1024

    /** Past the swap point, so the head fill overlaps what playback fetched. */
    const val HEAD_MARGIN_BYTES = 1L * 1024 * 1024

    /**
     * The most an alignment will fetch of an upgrade's opening: thirteen
     * seconds of a 24-bit/192 kHz master, which is ten megabytes. Anything
     * lighter fetches proportionally less - see [alignmentHeadBytes].
     */
    const val ALIGN_MAX_HEAD_BYTES = 10L * 1024 * 1024

    /**
     * How much of an upgraded rendition's opening to fetch once it has swapped
     * in: enough for Automix, or - when the cache keeps this copy and the
     * listener has asked for cached songs to be improved - everything up to
     * where it landed, so a track played to the end leaves the better copy on
     * disk in full and the next play starts on it.
     *
     * [lengthBytes] is only asked when the answer depends on it.
     */
    fun headBytes(
        improveCachedSongs: Boolean,
        keepsRendition: Boolean,
        durationMs: Long,
        landedAtMs: Long,
        lengthBytes: () -> Long,
    ): Long {
        if (!improveCachedSongs || durationMs <= 0 || !keepsRendition) return ANALYSIS_HEAD_BYTES
        val length = lengthBytes().takeIf { it > 0 } ?: return ANALYSIS_HEAD_BYTES
        val throughSwap = (length.toDouble() * landedAtMs / durationMs).toLong() + HEAD_MARGIN_BYTES
        return maxOf(ANALYSIS_HEAD_BYTES, throughSwap.coerceAtMost(length))
    }

    /**
     * The upgrade's opening worth fetching to align against: a little over
     * [VersionAudioAligner.RENDITION_WINDOW_SECONDS] of audio at the bitrate
     * the source stated, so a 24-bit/192 kHz master and a CD-quality FLAC each
     * get the seconds they need and no more. Without a stated bitrate, the head
     * Automix asks for.
     */
    fun alignmentHeadBytes(kbps: Int?): Long {
        val seconds = VersionAudioAligner.RENDITION_WINDOW_SECONDS + 1
        val wanted = kbps?.let { (it * 125L * seconds).toLong() } ?: ANALYSIS_HEAD_BYTES
        return wanted.coerceIn(HEADER_BYTES, ALIGN_MAX_HEAD_BYTES)
    }
}
