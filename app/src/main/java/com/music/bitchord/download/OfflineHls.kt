package com.music.bitchord.download

import android.content.Context
import android.net.Uri

internal suspend fun OfflineHls.save(
    context: Context,
    id: String,
    url: String,
    headers: Map<String, String>,
    onProgress: (Long, Long) -> Unit,
    lyrics: LyricsTag.Embeddable?,
    artwork: MediaTagger.Artwork?,
): Uri = Uri.fromFile(
    save(
        filesDir = context.filesDir,
        id = id,
        url = url,
        headers = headers,
        onProgress = onProgress,
        lyrics = lyrics,
        artworkBytes = artwork?.bytes,
        artworkMime = artwork?.mime,
    ),
)
