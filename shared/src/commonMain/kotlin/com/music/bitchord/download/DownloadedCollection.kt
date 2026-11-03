package com.music.bitchord.download

import com.music.bitchord.data.model.Song
import kotlinx.serialization.Serializable

/**
 * What a batch download was asked for as a whole.
 */
data class DownloadTarget(
    val id: String,
    val title: String,
    val subtitle: String = "",
    val thumbnailUrl: String? = null,
    val playlist: Boolean = false,
)

/**
 * A release the record says was downloaded whole, as it is written down.
 */
@Serializable
data class SavedCollection(
    val id: String,
    val title: String,
    val subtitle: String = "",
    val thumbnailUrl: String? = null,
    val playlist: Boolean = false,
    val videoIds: List<String> = emptyList(),
)

/** A [SavedCollection] with its surviving tracks attached, ready to draw. */
data class DownloadedCollection(
    val id: String,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String?,
    val playlist: Boolean,
    val songs: List<Song>,
)

const val DOWNLOAD_PLAYLIST_PREFIX = "local:playlist:"

fun downloadPageIdFor(id: String): String = DOWNLOAD_PLAYLIST_PREFIX + id
