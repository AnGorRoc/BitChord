package com.music.bitchord.data.settings

/** Stable persisted ordering for each on-device music library. */
enum class LocalMusicSort {
    TITLE_ASC,
    TITLE_DESC,
    DATE_ADDED,
    DATE_MODIFIED,
}

/**
 * Ordering for the track list on an album or playlist page.
 */
enum class SongSort {
    DEFAULT,
    TITLE_ASC,
    TITLE_DESC,
    DATE_ADDED_ASC,
    DATE_ADDED_DESC,
}
