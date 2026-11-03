package com.music.bitchord.download

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings

suspend fun LyricsTag.forTrack(track: Song): LyricsTag.Embeddable? {
    val sources = if (AppSettings.syncedLyrics.value) {
        AppSettings.lyricsSources.value
    } else {
        emptySet()
    }
    return forTrack(
        track = track,
        sources = sources,
        order = AppSettings.lyricsSourceOrder.value,
        prioritizeSyllableSync = AppSettings.prioritizeSyllableSync.value,
    )
}
