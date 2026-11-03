package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import com.music.bitchord.download.DownloadTarget
import com.music.bitchord.download.DownloadedCollection
import com.music.bitchord.download.FileMediaTagger
import com.music.bitchord.download.LyricsTag
import com.music.bitchord.download.SavedCollection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Downloads resolved audio into the user's own BitChord folder, embedding tags, lyrics and artwork. */
object DesktopDownloadManager {
    internal const val KEY_DOWNLOAD_FOLDER = "download_folder"
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val persistence = DesktopPersistence()

    private val _collections = MutableStateFlow(persistence.downloadedCollections())
    val collections: StateFlow<Map<String, SavedCollection>> = _collections.asStateFlow()

    private val cacheDir: File
        get() = File(System.getProperty("user.home"), ".bitchord").also { it.mkdirs() }

    /** Default folder (`~/Music/BitChord`), or the custom directory chosen in Settings. */
    fun folder(): Path = customFolder() ?: defaultFolder()

    fun defaultFolder(): Path = Path.of(System.getProperty("user.home"), "Music", "BitChord")

    fun customFolder(): Path? = persistence.string(KEY_DOWNLOAD_FOLDER)
        .takeIf(String::isNotBlank)
        ?.let { runCatching { Paths.get(it) }.getOrNull() }
        ?.takeIf(Files::isDirectory)

    fun setFolder(path: Path?) {
        persistence.saveString(KEY_DOWNLOAD_FOLDER, path?.toAbsolutePath()?.toString().orEmpty())
    }

    fun folderLabel(): String = customFolder()?.toString() ?: defaultFolder().toString()

    fun chooseFolder(): Path? = runCatching {
        val chooser = javax.swing.JFileChooser(folder().toFile())
        chooser.fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
        chooser.dialogTitle = "Download folder"
        if (chooser.showOpenDialog(null) != javax.swing.JFileChooser.APPROVE_OPTION) return null
        chooser.selectedFile?.toPath()
    }.getOrNull()

    fun rememberCollection(target: DownloadTarget, songs: List<Song>) {
        if (songs.isEmpty()) return
        val localArt = FileMediaTagger.cacheArtwork(cacheDir, target.thumbnailUrl) ?: target.thumbnailUrl
        val saved = SavedCollection(
            id = target.id,
            title = target.title,
            subtitle = target.subtitle,
            thumbnailUrl = localArt,
            playlist = target.playlist,
            videoIds = songs.map(Song::videoId).distinct(),
        )
        val next = _collections.value + (target.id to saved)
        _collections.value = next
        persistence.saveDownloadedCollections(next)
    }

    fun forgetCollection(id: String) {
        if (id !in _collections.value) return
        val next = _collections.value - id
        _collections.value = next
        persistence.saveDownloadedCollections(next)
    }

    fun collectionsAmong(songs: List<Song>): List<DownloadedCollection> {
        if (_collections.value.isEmpty() || songs.isEmpty()) return emptyList()
        val byId = songs.associateBy(Song::videoId)
        return _collections.value.values.mapNotNull { record ->
            val present = record.videoIds.mapNotNull(byId::get)
            if (present.isEmpty()) return@mapNotNull null
            DownloadedCollection(
                id = record.id,
                title = record.title,
                subtitle = record.subtitle,
                thumbnailUrl = record.thumbnailUrl ?: present.firstNotNullOfOrNull { it.thumbnailUrl },
                playlist = record.playlist,
                songs = present,
            )
        }.sortedBy { it.title.lowercase(Locale.ROOT) }
    }

    /** Automatically queues [song] if [browseId] is a downloaded playlist and auto-download is enabled. */
    fun autoDownload(browseId: String, song: Song, alreadyDownloadedIds: Set<String> = emptySet()) {
        if (!persistence.autoDownloadPlaylists()) return
        val bare = browseId.removePrefix("VL")
        val record = _collections.value.values
            .firstOrNull { it.playlist && it.id.removePrefix("VL") == bare } ?: return
        val next = _collections.value + (
            record.id to record.copy(videoIds = (record.videoIds + song.videoId).distinct())
            )
        _collections.value = next
        persistence.saveDownloadedCollections(next)
        if (song.videoId in alreadyDownloadedIds) return
        DesktopDownloadQueue.enqueue(song)
        DesktopPlayerHost.showMessage("Downloading '${song.title}' for ${record.title}")
    }

    /** Keeps an interrupted transfer in place and resumes it when the user tries again. */
    suspend fun download(
        song: Song,
        quality: String = persistence.string("download_quality", "LOSSLESS"),
        onProgress: (downloaded: Long, total: Long?) -> Unit = { _, _ -> },
    ): Result<Song> {
        val job = currentCoroutineContext()[Job]
        if (job != null) activeJobs[song.videoId] = job
        return try {
            require(song.localPath == null) { "This track is already local" }
            val stream = DesktopMusicSources.resolve(song, quality, forDownload = true).getOrThrow()
            val directory = folder()
            Files.createDirectories(directory)
            val safeName = buildString {
                append(song.artist)
                append(" - ")
                append(song.title)
            }.replace(ILLEGAL_FILENAME, "_").trim().take(180).ifBlank { song.videoId }
            val manifest = DesktopManifestDownload.isManifest(stream)
            val extension = stream.format.fileExtension().let { if (manifest && it != "flac") "m4a" else it }
            val target = directory.resolve("$safeName.$extension")
            val temporary = directory.resolve(".$safeName.$extension.part")
            val badge = stream.format.downloadBadge(extension)
            if (Files.isRegularFile(target) && Files.size(target) > 0L) {
                val nowSec = Instant.now().epochSecond
                return Result.success(
                    song.copy(
                        localUri = target.toUri().toString(),
                        localPath = target.toString(),
                        localDateAddedSeconds = song.localDateAddedSeconds ?: nowSec,
                        localDateModifiedSeconds = nowSec,
                        sourceQuality = badge ?: song.sourceQuality,
                    ),
                )
            }

            coroutineScope {
                val taggable = FileMediaTagger.carriesTags(extension)
                val lyricsDeferred = async(Dispatchers.IO) {
                    if (!taggable) null else lookupLyricsForTag(song)
                }
                val artworkDeferred = async(Dispatchers.IO) {
                    FileMediaTagger.artworkFor(cacheDir, song)
                }

                if (manifest) {
                    Files.deleteIfExists(temporary)
                    Files.newOutputStream(temporary).buffered().use { output ->
                        DesktopManifestDownload.save(
                            stream,
                            extension,
                            output,
                            tempDir = File(System.getProperty("java.io.tmpdir")),
                            userAgent = DOWNLOAD_USER_AGENT,
                        ) { done, total -> onProgress(done, total) }
                    }
                    check(Files.size(temporary) > 0L) { "Download failed: nothing was sent" }
                } else {
                    val existing = if (Files.isRegularFile(temporary)) Files.size(temporary) else 0L
                    val request = DesktopDownloadHttp.request(
                        stream.url,
                        DOWNLOAD_USER_AGENT,
                        stream.headers,
                        rangeFrom = existing.takeIf { it > 0L },
                    )
                    DesktopDownloadHttp.client.newCall(request).execute().use { response ->
                        check(response.isSuccessful) { "Download failed (HTTP ${response.code})" }
                        val body = response.body
                        val append = existing > 0L && response.code == HTTP_PARTIAL
                        val offset = if (append) existing else 0L
                        val total = response.header("Content-Range")
                            ?.substringAfterLast('/')
                            ?.toLongOrNull()
                            ?.takeIf { it > 0L }
                            ?: body.contentLength().takeIf { it > 0L }?.let { it + offset }
                        if (!append && existing > 0L) Files.deleteIfExists(temporary)
                        Files.newOutputStream(
                            temporary,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.WRITE,
                            if (append) StandardOpenOption.APPEND else StandardOpenOption.TRUNCATE_EXISTING,
                        ).use { output ->
                            body.byteStream().use { input ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                var downloaded = offset
                                onProgress(downloaded, total)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    if (read == 0) continue
                                    output.write(buffer, 0, read)
                                    downloaded += read
                                    onProgress(downloaded, total)
                                }
                            }
                        }
                    }
                    check(Files.size(temporary) > 0L) { "Download failed: nothing was sent" }
                }

                val artwork = artworkDeferred.await()
                val lyrics = lyricsDeferred.await()
                if (taggable) {
                    FileMediaTagger.embed(temporary.toFile(), song, extension, lyrics, artwork)
                }
                val localArtworkUri = FileMediaTagger.persistArtwork(cacheDir, song.thumbnailUrl, artwork)

                runCatching {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                }.getOrElse {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
                }
                val nowSec = Instant.now().epochSecond
                Result.success(
                    song.copy(
                        thumbnailUrl = localArtworkUri ?: song.thumbnailUrl,
                        localUri = target.toUri().toString(),
                        localPath = target.toString(),
                        localDateAddedSeconds = song.localDateAddedSeconds ?: nowSec,
                        localDateModifiedSeconds = nowSec,
                        sourceQuality = badge ?: song.sourceQuality,
                    ),
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Result.failure(failure)
        } finally {
            if (job != null) activeJobs.remove(song.videoId, job)
        }
    }

    private suspend fun lookupLyricsForTag(song: Song): LyricsTag.Embeddable? {
        if (!persistence.boolean(DesktopLyricsClient.KEY_SYNCED_LYRICS, true)) return null
        val order = DesktopLyricsClient.enabledSources(
            persistence.lyricsSourceOrder(),
            persistence.lyricsEnabledSources(),
        ).mapNotNull(::lyricsSourceNamed)
        if (order.isEmpty()) return null
        return LyricsTag.forTrack(
            track = song,
            sources = order.toSet(),
            order = order,
            prioritizeSyllableSync = persistence.boolean(DesktopLyricsClient.KEY_PRIORITIZE_SYLLABLES, false),
        )
    }

    fun cancel(videoId: String) {
        activeJobs[videoId]?.cancel(CancellationException("Download cancelled"))
    }

    fun savedFile(song: Song): Path? {
        val path = song.localPath?.let { runCatching { Path.of(it) }.getOrNull() }
            ?: song.localUri?.takeIf { it.startsWith("file:") }
                ?.let { runCatching { Path.of(URI(it)) }.getOrNull() }
            ?: return null
        return path.takeIf { Files.isRegularFile(it) && runCatching { Files.size(it) > 0 }.getOrDefault(false) }
    }

    fun isSaved(song: Song): Boolean = savedFile(song) != null

    fun verified(downloads: List<Song>): List<Song> = downloads.filter(::isSaved)

    fun delete(song: Song): Boolean {
        val file = savedFile(song) ?: return false
        return runCatching { Files.deleteIfExists(file) }
            .onFailure { DesktopTrackLog.log("could not delete the download of '${song.title}': ${it.message}") }
            .getOrDefault(false)
    }

    private fun DesktopStreamFormat.fileExtension(): String {
        val codec = codec.orEmpty().substringAfterLast('/').substringBefore(';').lowercase()
        return when (codec) {
            "flac" -> "flac"
            "wav", "wave" -> "wav"
            "alac" -> "m4a"
            "aac" -> "m4a"
            "mp3" -> "mp3"
            "ogg", "opus" -> "ogg"
            "webm" -> "webm"
            else -> "m4a"
        }
    }

    private fun DesktopStreamFormat.downloadBadge(extension: String): String? {
        val raw = codec.orEmpty().lowercase(Locale.ROOT)
        return when {
            isDolbyAtmos || "ec-3" in raw || "eac3" in raw -> "DOLBY"
            extension == "flac" || "flac" in raw -> "FLAC"
            kbps != null && kbps > 0 -> {
                val label = if (extension == "m4a") "AAC" else "OPUS"
                "$label · $kbps kbps"
            }
            else -> null
        }
    }

    private const val HTTP_PARTIAL = 206
    private const val DOWNLOAD_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"
    private val ILLEGAL_FILENAME = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]")
}
