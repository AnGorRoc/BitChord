package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import com.music.bitchord.download.AudioFileMetadata
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.util.Locale
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension

/** Finds ordinary audio files without requiring an Android MediaStore, reading embedded tags and covers. */
object DesktopLocalMusic {
    private val supportedExtensions = setOf("aac", "flac", "m4a", "mp3", "ogg", "wav", "webm")

    private val artworkCacheDir: File
        get() = File(System.getProperty("user.home"), ".bitchord").also { it.mkdirs() }

    fun scan(): List<Song> {
        val filterNonMusic = DesktopPersistence().boolean(KEY_FILTER_NON_MUSIC_AUDIO, false)
        return roots()
            .filter(Files::isDirectory)
            .flatMap { scanDirectory(it, filterNonMusic) }
            .distinctBy(Song::localPath)
            .sortedBy { it.title.lowercase() }
    }

    internal fun roots(): List<Path> {
        val chosen = folder()
        if (chosen != null) return listOf(chosen)
        val home = Paths.get(System.getProperty("user.home"))
        return listOf(home.resolve("Music"), home.resolve("Downloads"))
    }

    /** The folder the listener picked, or null for all of them. */
    fun folder(): Path? = DesktopPersistence().string(KEY_LOCAL_MUSIC_FOLDER)
        .takeIf(String::isNotBlank)
        ?.let { runCatching { Paths.get(it) }.getOrNull() }
        ?.takeIf(Files::isDirectory)

    fun setFolder(path: Path?) {
        DesktopPersistence().saveString(KEY_LOCAL_MUSIC_FOLDER, path?.toAbsolutePath()?.toString().orEmpty())
    }

    /** What the Local Music settings row says underneath its title. */
    fun folderLabel(): String = folder()?.fileName?.toString() ?: "All audio folders"

    internal const val KEY_LOCAL_MUSIC_FOLDER = "local_music_folder"

    fun chooseFolder(): Path? = runCatching {
        val chooser = javax.swing.JFileChooser(folder()?.toFile() ?: File(System.getProperty("user.home")))
        chooser.fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
        chooser.dialogTitle = "Local music folder"
        if (chooser.showOpenDialog(null) != javax.swing.JFileChooser.APPROVE_OPTION) return null
        chooser.selectedFile?.toPath()
    }.getOrNull()

    private fun scanDirectory(root: Path, filterNonMusic: Boolean): List<Song> = buildList {
        runCatching {
            Files.walk(root, 4).use { files ->
                files.filter(Files::isRegularFile)
                    .filter { it.extension.lowercase() in supportedExtensions }
                    .filter { !filterNonMusic || isMusic(it) }
                    .forEach { path -> add(path.toSong()) }
            }
        }
    }

    internal fun isMusic(path: Path): Boolean {
        if (path.extension.lowercase(Locale.ROOT) == "wav") return false
        if (runCatching { Files.size(path) }.getOrDefault(Long.MAX_VALUE) < MIN_MUSIC_BYTES) return false
        val haystack = path.toString().lowercase(Locale.ROOT)
        return NON_MUSIC_MARKERS.none { it in haystack }
    }

    private val NON_MUSIC_MARKERS = listOf(
        "/ringtones/", "/notifications/", "/alarms/", "/ui/", "/recordings/", "/voice recorder/",
        "voice note", "voicenote", "recording", "ringtone", "notification", "alarm",
    )

    private const val MIN_MUSIC_BYTES = 240L * 1024

    internal const val KEY_FILTER_NON_MUSIC_AUDIO = "filter_non_music_audio"

    private fun Path.toSong(): Song {
        val absolutePath = toAbsolutePath().normalize()
        val attributes = runCatching {
            Files.readAttributes(absolutePath, BasicFileAttributes::class.java)
        }.getOrNull()
        val modified = attributes?.lastModifiedTime()?.toInstant()
        val added = attributes?.creationTime()?.toInstant() ?: modified ?: Instant.now()
        val identity = absolutePath.toString()
        val parsed = parseFileName(nameWithoutExtension)
        val embedded = runCatching { AudioFileMetadata.read(absolutePath, artworkCacheDir) }.getOrNull()
        val tags = embedded?.first
        val coverUri = embedded?.second
        val codec = extension.lowercase(Locale.ROOT).let { value ->
            when (value) {
                "flac" -> "FLAC"
                "wav" -> "WAV"
                "aac", "m4a" -> "AAC"
                "ogg" -> "OGG"
                "webm" -> "OPUS"
                else -> value.uppercase(Locale.ROOT)
            }
        }
        return Song(
            videoId = "local:$identity",
            title = tags?.title?.takeIf(String::isNotBlank) ?: parsed.title,
            artist = tags?.artist?.takeIf(String::isNotBlank) ?: parsed.artist,
            thumbnailUrl = coverUri,
            albumName = tags?.album?.takeIf(String::isNotBlank),
            trackNumber = tags?.trackNumber,
            localUri = absolutePath.toUri().toString(),
            localPath = absolutePath.toString(),
            localDateAddedSeconds = added.epochSecond,
            localDateModifiedSeconds = modified?.epochSecond,
            sourceQuality = codec,
        )
    }

    private fun parseFileName(name: String): ParsedName {
        val parts = name.split(" - ", limit = 2)
        return if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
            ParsedName(title = parts[1].trim(), artist = parts[0].trim())
        } else {
            ParsedName(title = name, artist = "On This Computer")
        }
    }

    private data class ParsedName(val title: String, val artist: String)
}

enum class DesktopLibrarySort {
    TITLE_ASC,
    TITLE_DESC,
    DATE_ADDED,
    DATE_MODIFIED,
}

enum class DesktopLibraryView {
    LIST,
    GRID,
}

fun List<Song>.sortedForDesktopLibrary(sort: DesktopLibrarySort): List<Song> = when (sort) {
    DesktopLibrarySort.TITLE_ASC -> sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, Song::title))
    DesktopLibrarySort.TITLE_DESC -> sortedWith(compareByDescending(String.CASE_INSENSITIVE_ORDER, Song::title))
    DesktopLibrarySort.DATE_ADDED -> sortedWith(
        compareByDescending<Song> { it.localDateAddedSeconds ?: Long.MIN_VALUE }
            .thenBy(String.CASE_INSENSITIVE_ORDER, Song::title),
    )
    DesktopLibrarySort.DATE_MODIFIED -> sortedWith(
        compareByDescending<Song> { it.localDateModifiedSeconds ?: Long.MIN_VALUE }
            .thenBy(String.CASE_INSENSITIVE_ORDER, Song::title),
    )
}
