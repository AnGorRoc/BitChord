package com.music.bitchord.desktop

import com.music.bitchord.data.LocalGroup
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class OfflinePlaylist(
    val id: String,
    val title: String,
    val songs: List<Song> = emptyList(),
) {
    val browseId: String get() = DesktopOfflinePlaylists.PREFIX + id
    val isLiked: Boolean get() = id == DesktopOfflinePlaylists.LIKED_ID
}

@Serializable
private data class StoredOfflineSong(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String? = null,
    val durationText: String? = null,
    val artistId: String? = null,
    val albumId: String? = null,
    val albumName: String? = null,
    val localUri: String? = null,
    val localPath: String? = null,
    val localDateAddedSeconds: Long? = null,
    val localDateModifiedSeconds: Long? = null,
    val trackNumber: Int? = null,
    val sourceQuality: String? = null,
) {
    fun toSong(): Song = Song(
        videoId = videoId,
        title = title,
        artist = artist,
        thumbnailUrl = thumbnailUrl,
        durationText = durationText,
        artistId = artistId,
        albumId = albumId,
        albumName = albumName,
        localUri = localUri,
        localPath = localPath,
        localDateAddedSeconds = localDateAddedSeconds,
        localDateModifiedSeconds = localDateModifiedSeconds,
        trackNumber = trackNumber,
        sourceQuality = sourceQuality,
    )
}

@Serializable
private data class StoredOfflinePlaylist(
    val id: String,
    val title: String,
    val songs: List<StoredOfflineSong> = emptyList(),
) {
    fun toDomain(): OfflinePlaylist = OfflinePlaylist(
        id = id,
        title = title,
        songs = songs.map(StoredOfflineSong::toSong),
    )
}

object DesktopOfflinePlaylists {
    const val PREFIX = "local:offline:"
    const val LIKED_ID = "liked"
    private const val KEY_OFFLINE_PLAYLISTS = "offline_playlists_json"

    private val persistence = DesktopPersistence()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()

    private val likedTitle: String
        get() = DesktopStrings["liked_songs", "Liked songs"]

    private val _playlists = MutableStateFlow(loadInitial())
    val playlists: StateFlow<List<OfflinePlaylist>> = _playlists.asStateFlow()

    private val _liked = MutableStateFlow(
        _playlists.value.firstOrNull { it.isLiked }?.songs?.mapNotNullTo(HashSet()) { it.offlineKey() }.orEmpty(),
    )
    val liked: StateFlow<Set<String>> = _liked.asStateFlow()

    fun isOfflinePlaylist(browseId: String?): Boolean = browseId?.startsWith(PREFIX) == true

    fun isLikedPage(browseId: String?): Boolean = browseId == PREFIX + LIKED_ID

    fun get(browseId: String?): OfflinePlaylist? {
        val id = browseId?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX) ?: return null
        return _playlists.value.firstOrNull { it.id == id }
    }

    fun isLiked(song: Song): Boolean = song.offlineKey()?.let { it in _liked.value } == true

    fun toggleLike(song: Song): Boolean {
        val key = song.offlineKey() ?: return false
        var nowLiked = false
        mutate { list ->
            nowLiked = key !in _liked.value
            list.map { playlist ->
                if (!playlist.isLiked) return@map playlist
                val rest = playlist.songs.filterNot { it.offlineKey() == key }
                playlist.copy(songs = if (nowLiked) listOf(song.stored()) + rest else rest)
            }
        }
        return nowLiked
    }

    fun create(title: String, songs: List<Song> = emptyList()): OfflinePlaylist {
        val playlist = OfflinePlaylist(
            id = "p" + System.currentTimeMillis().toString(36),
            title = title.trim(),
            songs = songs.filter { it.offlineKey() != null }.distinctBy { it.offlineKey() }.map { it.stored() },
        )
        mutate { it + playlist }
        return playlist
    }

    class AddResult(val added: Int, val alreadyThere: Int)

    fun add(ids: Collection<String>, songs: List<Song>): AddResult {
        val incoming = songs.filter { it.offlineKey() != null }.distinctBy { it.offlineKey() }
        var added = 0
        var alreadyThere = 0
        mutate { list ->
            list.map { playlist ->
                if (playlist.id !in ids) return@map playlist
                val known = playlist.songs.mapNotNullTo(HashSet()) { it.offlineKey() }
                val fresh = incoming.filter { it.offlineKey()?.let(known::add) == true }.map { it.stored() }
                if (fresh.isEmpty()) {
                    alreadyThere++
                    return@map playlist
                }
                added++
                playlist.copy(songs = if (playlist.isLiked) fresh + playlist.songs else playlist.songs + fresh)
            }
        }
        return AddResult(added, alreadyThere)
    }

    fun remove(browseId: String, song: Song) {
        val id = browseId.removePrefix(PREFIX)
        val key = song.offlineKey() ?: return
        mutate { list ->
            list.map { if (it.id == id) it.copy(songs = it.songs.filterNot { s -> s.offlineKey() == key }) else it }
        }
    }

    fun rename(browseId: String, title: String) {
        val id = browseId.removePrefix(PREFIX)
        if (id == LIKED_ID || title.isBlank()) return
        mutate { list -> list.map { if (it.id == id) it.copy(title = title.trim()) else it } }
    }

    fun delete(browseId: String) {
        val id = browseId.removePrefix(PREFIX)
        if (id == LIKED_ID) return
        mutate { list -> list.filterNot { it.id == id } }
    }

    private inline fun mutate(change: (List<OfflinePlaylist>) -> List<OfflinePlaylist>) {
        synchronized(lock) {
            val next = ordered(change(_playlists.value))
            _playlists.value = next
            _liked.value = next.firstOrNull { it.isLiked }?.songs?.mapNotNullTo(HashSet()) { it.offlineKey() }.orEmpty()
            persist(next)
        }
    }

    private fun ordered(list: List<OfflinePlaylist>): List<OfflinePlaylist> {
        val liked = list.firstOrNull { it.isLiked } ?: OfflinePlaylist(LIKED_ID, likedTitle, emptyList())
        return listOf(liked.copy(title = likedTitle)) + list.filterNot { it.isLiked }
    }

    private fun loadInitial(): List<OfflinePlaylist> {
        val raw = persistence.string(KEY_OFFLINE_PLAYLISTS, "")
        val decoded = if (raw.isBlank()) {
            emptyList()
        } else {
            runCatching {
                json.decodeFromString<List<StoredOfflinePlaylist>>(raw).map(StoredOfflinePlaylist::toDomain)
            }.getOrDefault(emptyList())
        }
        return ordered(decoded)
    }

    private fun persist(list: List<OfflinePlaylist>) {
        runCatching {
            val stored = list.map { playlist ->
                StoredOfflinePlaylist(
                    id = playlist.id,
                    title = playlist.title,
                    songs = playlist.songs.map { song ->
                        StoredOfflineSong(
                            videoId = song.videoId,
                            title = song.title,
                            artist = song.artist,
                            thumbnailUrl = song.thumbnailUrl,
                            durationText = song.durationText,
                            artistId = song.artistId,
                            albumId = song.albumId,
                            albumName = song.albumName,
                            localUri = song.localUri,
                            localPath = song.localPath,
                            localDateAddedSeconds = song.localDateAddedSeconds,
                            localDateModifiedSeconds = song.localDateModifiedSeconds,
                            trackNumber = song.trackNumber,
                            sourceQuality = song.sourceQuality,
                        )
                    },
                )
            }
            persistence.saveString(KEY_OFFLINE_PLAYLISTS, json.encodeToString(stored))
        }
    }

    internal fun Song.offlineKey(): String? = localUri ?: localPath ?: videoId.takeIf { it.isNotBlank() }

    private fun Song.stored(): Song = Song(
        videoId = videoId,
        title = title,
        artist = artist,
        thumbnailUrl = thumbnailUrl,
        durationText = durationText,
        artistId = artistId,
        albumId = albumId,
        albumName = albumName,
        localUri = localUri ?: localPath,
        localPath = localPath,
        localDateAddedSeconds = localDateAddedSeconds,
        localDateModifiedSeconds = localDateModifiedSeconds,
        trackNumber = trackNumber,
        sourceQuality = sourceQuality,
    )
}

fun OfflinePlaylist.toGroup(): LocalGroup = LocalGroup(
    browseId = browseId,
    title = title,
    subtitle = "${songs.size} songs",
    thumbnailUrl = if (isLiked) null else songs.firstNotNullOfOrNull { it.thumbnailUrl },
    type = BrowseType.PLAYLIST,
    songs = songs,
)
