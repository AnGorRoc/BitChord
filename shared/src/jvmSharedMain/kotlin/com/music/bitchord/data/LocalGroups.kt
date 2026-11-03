package com.music.bitchord.data

import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.LocalMusicSort
import com.music.bitchord.download.DownloadedCollection
import com.music.bitchord.download.downloadPageIdFor
import java.util.Locale

data class LocalGroup(
    val browseId: String,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String?,
    val type: BrowseType,
    val songs: List<Song>,
    val downloadId: String? = null,
)

object LocalGroups {
    const val PREFIX = "local:group:"
    private const val ALBUM = "album"
    private const val ARTIST = "artist"
    private const val FOLDER = "folder"

    fun albumPageId(folderId: String, name: String): String = pageId(ALBUM, folderId, name)

    fun artistPageId(folderId: String, name: String): String = pageId(ARTIST, folderId, name)

    fun directoryPageId(folderId: String, path: String): String = pageId(FOLDER, folderId, path)

    private fun pageId(kind: String, folderId: String, name: String) =
        "$PREFIX$kind:${folderId.removePrefix("local:")}:$name"

    private val KINDS = setOf(ALBUM, ARTIST, FOLDER)

    private class Parsed(val kind: String, val folderId: String, val name: String)

    private fun parse(browseId: String?): Parsed? {
        if (browseId == null || !browseId.startsWith(PREFIX)) return null
        val parts = browseId.removePrefix(PREFIX).split(':', limit = 3)
        if (parts.size != 3 || parts[0] !in KINDS || parts[1].isEmpty()) return null
        return Parsed(parts[0], "local:" + parts[1], parts[2])
    }

    fun folderOf(browseId: String?): String? = parse(browseId)?.folderId

    fun typeOf(browseId: String): BrowseType? =
        parse(browseId)?.let {
            when (it.kind) {
                ALBUM -> BrowseType.ALBUM
                FOLDER -> BrowseType.PLAYLIST
                else -> BrowseType.OTHER
            }
        }

    fun isArtist(browseId: String?): Boolean = parse(browseId)?.kind == ARTIST

    fun isDirectory(browseId: String?): Boolean = parse(browseId)?.kind == FOLDER

    fun songsOf(browseId: String, folderSongs: List<Song>): List<Song> {
        val group = parse(browseId) ?: return emptyList()
        return when (group.kind) {
            ALBUM -> folderSongs.filter { it.albumName == group.name }.sortedForAlbum()
            FOLDER -> folderSongs.filter { it.directory() == group.name }.sortedByTitle()
            else -> folderSongs.filter { it.artist == group.name }.sortedByTitle()
        }
    }

    fun albumsOf(
        folderId: String,
        songs: List<Song>,
        collections: List<DownloadedCollection>,
    ): List<LocalGroup> {
        val asked = collections.map { collection ->
            LocalGroup(
                browseId = downloadPageIdFor(collection.id),
                title = collection.title,
                subtitle = collection.subtitle.ifBlank {
                    if (collection.playlist) "" else collection.songs.firstOrNull()?.artist.orEmpty()
                },
                thumbnailUrl = collection.thumbnailUrl,
                type = if (collection.playlist) BrowseType.PLAYLIST else BrowseType.ALBUM,
                songs = collection.songs,
                downloadId = collection.id,
            )
        }
        val claimed = asked.mapTo(HashSet()) { it.title.lowercase(Locale.ROOT) }
        val derived = songs
            .groupBy { it.albumName }
            .mapNotNull { (name, group) ->
                if (name == null || name.lowercase(Locale.ROOT) in claimed) return@mapNotNull null
                album(folderId, name, group)
            }
        return (asked + derived).sortedWith(
            compareByDescending<LocalGroup> { it.downloadId != null }
                .thenBy { it.title.lowercase(Locale.ROOT) },
        )
    }

    fun artistsOf(folderId: String, songs: List<Song>): List<LocalGroup> =
        songs.groupBy { it.artist }
            .map { (name, group) -> artist(folderId, name, group) }
            .sortedBy { it.title.lowercase(Locale.ROOT) }

    fun foldersOf(folderId: String, songs: List<Song>): List<LocalGroup> =
        songs.groupBy { it.directory() }
            .mapNotNull { (path, group) ->
                if (path == null) return@mapNotNull null
                folder(folderId, path, group)
            }
            .sortedWith(compareBy<LocalGroup> { it.title.lowercase(Locale.ROOT) }.thenBy { it.subtitle })

    fun groupOf(browseId: String, songs: List<Song>): LocalGroup? {
        val group = parse(browseId) ?: return null
        return when (group.kind) {
            ALBUM -> album(group.folderId, group.name, songs)
            FOLDER -> folder(group.folderId, group.name, songs)
            else -> artist(group.folderId, group.name, songs)
        }
    }

    private fun album(folderId: String, name: String, songs: List<Song>) = LocalGroup(
        browseId = albumPageId(folderId, name),
        title = name,
        subtitle = songs.firstOrNull()?.artist.orEmpty(),
        thumbnailUrl = songs.firstNotNullOfOrNull { it.thumbnailUrl },
        type = BrowseType.ALBUM,
        songs = songs.sortedForAlbum(),
    )

    private fun artist(folderId: String, name: String, songs: List<Song>) = LocalGroup(
        browseId = artistPageId(folderId, name),
        title = name,
        subtitle = "",
        thumbnailUrl = null,
        type = BrowseType.OTHER,
        songs = songs.sortedByTitle(),
    )

    private fun folder(folderId: String, path: String, songs: List<Song>) = LocalGroup(
        browseId = directoryPageId(folderId, path),
        title = path.substringAfterLast('/').ifBlank { path },
        subtitle = path.substringBeforeLast('/', "").readablePath(),
        thumbnailUrl = songs.firstNotNullOfOrNull { it.thumbnailUrl },
        type = BrowseType.PLAYLIST,
        songs = songs.sortedByTitle(),
    )

    private fun Song.directory(): String? =
        localPath?.replace('\\', '/')?.substringBeforeLast('/', "")?.takeIf { it.isNotEmpty() }

    private fun String.readablePath(): String {
        val home = System.getProperty("user.home")?.replace('\\', '/')?.trimEnd('/').orEmpty()
        return when {
            startsWith("/storage/emulated/0") -> removePrefix("/storage/emulated/0").trimStart('/')
            startsWith("/storage/") -> removePrefix("/storage/")
            home.isNotEmpty() && startsWith(home, ignoreCase = true) -> substring(home.length).trimStart('/')
            else -> trimStart('/')
        }
    }
}

fun Song.matchesSearch(query: String): Boolean =
    title.contains(query, ignoreCase = true) ||
        artist.contains(query, ignoreCase = true) ||
        albumName?.contains(query, ignoreCase = true) == true

fun LocalGroup.matchesSearch(query: String): Boolean =
    query.isBlank() ||
        title.contains(query, ignoreCase = true) ||
        subtitle.contains(query, ignoreCase = true) ||
        songs.any { it.matchesSearch(query) }

fun List<Song>.sortedForLibrary(order: LocalMusicSort): List<Song> = when (order) {
    LocalMusicSort.TITLE_ASC -> sortedByTitle()
    LocalMusicSort.TITLE_DESC -> sortedWith(compareByDescending<Song> { it.title.lowercase(Locale.ROOT) })
    LocalMusicSort.DATE_ADDED -> sortedWith(
        compareByDescending<Song> { it.localDateAddedSeconds ?: Long.MIN_VALUE }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title },
    )
    LocalMusicSort.DATE_MODIFIED -> sortedWith(
        compareByDescending<Song> { it.localDateModifiedSeconds ?: Long.MIN_VALUE }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title },
    )
}

private fun List<Song>.sortedByTitle(): List<Song> =
    sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })

fun List<Song>.sortedForAlbum(): List<Song> {
    if (none { it.trackNumber != null }) return this
    return sortedWith(
        compareBy<Song> { it.trackNumber ?: Int.MAX_VALUE }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title },
    )
}

fun List<Song>.useTrackTags(): Boolean {
    if (isEmpty() || none { it.trackNumber != null }) return false
    val album = first().albumName ?: return false
    return all { it.albumName == album }
}
