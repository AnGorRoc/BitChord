package com.music.bitchord.download

import com.music.bitchord.data.Http
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.artworkAt
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Request
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.max

/**
 * Pure JVM file tagger and cover sidecar manager shared with Desktop.
 *
 * Uses [FlacTagger], [Mp4Tagger], [Mp4Sidx] and [WebmTagger] with streaming
 * FLAC support so large lossless files never require multiple in-memory copies.
 */
object FileMediaTagger {

    private const val TAG = "BitChord"
    private const val COVER_MAX_SIDE = 1000
    private const val FLAC_HEAD_BYTES = 16 * 1024 * 1024
    private const val COPY_BUFFER = 64 * 1024
    private val TAGGABLE = setOf("m4a", "webm", "flac")

    class Artwork(val bytes: ByteArray, val mime: String)

    private val taggingLock = Mutex()
    private val artworkFileLock = Any()

    fun carriesTags(extension: String): Boolean = extension.lowercase() in TAGGABLE

    fun artworkFor(cacheDir: File, track: Song): Artwork? {
        val thumbnailUrl = track.thumbnailUrl
        if (thumbnailUrl.isNullOrBlank()) {
            TrackLog.d(TAG, "no artwork url for ${track.videoId}; saving it without a cover")
            return null
        }
        cachedArtwork(cacheDir, thumbnailUrl)?.let { return it }
        return fetchCover(track)
    }

    fun persistArtwork(cacheDir: File, sourceUrl: String?, artwork: Artwork?): String? {
        if (artwork == null || artwork.bytes.isEmpty()) return null
        return runCatching {
            val folder = File(cacheDir, "download-artwork")
            val target = artworkFile(cacheDir, sourceUrl, artwork)
            val name = target.name
            synchronized(artworkFileLock) {
                if (!target.exists()) {
                    if (!folder.exists() && !folder.mkdirs()) error("Could not create artwork folder")
                    val temp = File(folder, ".$name-${System.nanoTime()}.part")
                    try {
                        temp.writeBytes(artwork.bytes)
                        if (!temp.renameTo(target) && !target.exists()) {
                            temp.copyTo(target, overwrite = false)
                        }
                    } finally {
                        temp.delete()
                    }
                }
            }
            target.toURI().toString()
        }.onFailure {
            TrackLog.w(TAG, "could not save artwork sidecar: ${it.message}")
        }.getOrNull()
    }

    fun cacheArtwork(cacheDir: File, sourceUrl: String?): String? {
        if (sourceUrl.isNullOrBlank()) return null
        artworkFile(cacheDir, sourceUrl).takeIf { it.isFile }?.let { return it.toURI().toString() }
        val artwork = fetchCover(sourceUrl.artworkAt(1200) ?: sourceUrl, "collection") ?: return null
        return persistArtwork(cacheDir, sourceUrl, artwork)
    }

    private fun cachedArtwork(cacheDir: File, sourceUrl: String): Artwork? = runCatching {
        artworkFile(cacheDir, sourceUrl).takeIf { it.isFile }
            ?.readBytes()
            ?.takeIf { it.isNotEmpty() }
            ?.let { Artwork(it, "image/jpeg") }
    }.getOrNull()

    private fun artworkFile(cacheDir: File, sourceUrl: String?, artwork: Artwork? = null): File {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = if (sourceUrl.isNullOrBlank()) {
            artwork?.bytes ?: byteArrayOf()
        } else {
            sourceUrl.toByteArray(Charsets.UTF_8)
        }
        val name = digest.digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xFF) } + ".jpg"
        return File(File(cacheDir, "download-artwork"), name)
    }

    suspend fun embed(
        file: File,
        track: Song,
        extension: String,
        lyrics: LyricsTag.Embeddable? = null,
        artwork: Artwork? = null,
    ) {
        val ext = extension.lowercase()
        if (!carriesTags(ext)) return
        taggingLock.withLock {
            embedNow(file, track, ext, lyrics, artwork)
        }
    }

    private fun embedNow(
        file: File,
        track: Song,
        extension: String,
        lyrics: LyricsTag.Embeddable?,
        artwork: Artwork?,
    ) {
        val plain = lyrics?.plain
        val words = lyrics?.enhanced

        if (extension == "flac" && tagFlacStreaming(file, track, plain, words, artwork)) {
            return
        }

        val original = runCatching { file.readBytes() }
            .onFailure { TrackLog.w(TAG, "could not read $file for tagging: ${it.message}") }
            .getOrNull() ?: return

        val tagged = runCatching {
            when (extension) {
                "m4a" -> Mp4Tagger.tag(
                    Mp4Sidx.ensure(original),
                    track.title,
                    track.artist,
                    track.albumName,
                    plain,
                    artwork?.bytes,
                    coverIsPng = artwork?.mime?.contains("png", ignoreCase = true) == true,
                    wordLyrics = words,
                )
                "flac" -> FlacTagger.tag(
                    original,
                    track.title,
                    track.artist,
                    track.albumName,
                    plain,
                    artwork?.bytes,
                    artwork?.mime ?: "image/jpeg",
                    wordLyrics = words,
                )
                else -> WebmTagger.tag(
                    original,
                    track.title,
                    track.artist,
                    track.albumName,
                    plain,
                    artwork?.bytes,
                    artwork?.mime ?: "image/jpeg",
                    wordLyrics = words,
                )
            }
        }.onFailure {
            TrackLog.w(TAG, "could not build tags for ${track.videoId} (${original.size}B $extension): $it")
        }.getOrNull() ?: return

        if (tagged === original) return
        runCatching { file.writeBytes(tagged) }
            .onFailure { TrackLog.w(TAG, "could not write tags to $file: ${it.message}") }
    }

    private fun tagFlacStreaming(
        file: File,
        track: Song,
        lyrics: String?,
        wordLyrics: String?,
        artwork: Artwork?,
    ): Boolean {
        val head = readPrefix(file, FLAC_HEAD_BYTES) ?: return false
        val wholeFile = head.size < FLAC_HEAD_BYTES
        val rewrite = FlacTagger.header(
            head,
            track.title,
            track.artist,
            track.albumName,
            lyrics,
            artwork?.bytes,
            artwork?.mime ?: "image/jpeg",
            wordLyrics = wordLyrics,
        ) ?: return wholeFile

        val temp = File(file.parentFile ?: File(System.getProperty("java.io.tmpdir")), ".tagging-${track.videoId.hashCode()}-${System.nanoTime()}.flac")
        return runCatching {
            temp.outputStream().buffered().use { sink ->
                sink.write(rewrite.metadata)
                file.inputStream().use { source ->
                    source.skipFully(rewrite.audioStart.toLong())
                    source.copyTo(sink, COPY_BUFFER)
                }
            }
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
            true
        }.onFailure {
            TrackLog.w(TAG, "streaming flac tag failed for ${track.videoId}: ${it.message}")
            temp.delete()
        }.getOrDefault(false)
    }

    private fun readPrefix(file: File, max: Int): ByteArray? = runCatching {
        file.inputStream().use { source ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(COPY_BUFFER)
            while (out.size() < max) {
                val read = source.read(buffer, 0, minOf(buffer.size, max - out.size()))
                if (read == -1) break
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        }
    }.getOrNull()

    private fun InputStream.skipFully(count: Long) {
        var left = count
        while (left > 0) {
            val skipped = skip(left)
            if (skipped > 0) {
                left -= skipped
                continue
            }
            if (read() == -1) error("file ended ${left}B before its audio frames")
            left--
        }
    }

    fun fetchCover(track: Song): Artwork? {
        val url = track.artworkAt(1200) ?: return null
        return fetchCover(url, track.videoId)
    }

    fun fetchCover(url: String, logId: String): Artwork? = runCatching {
        val request = Request.Builder().url(url).build()
        Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@runCatching null
            val contentType = response.header("Content-Type")?.substringBefore(';')?.trim().orEmpty()
            val raw = response.body.bytes()?.takeIf { it.isNotEmpty() } ?: return@runCatching null
            val decoded = runCatching { ImageIO.read(ByteArrayInputStream(raw)) }.getOrNull()
            if (decoded != null) {
                val scaled = downscale(decoded, COVER_MAX_SIDE)
                val out = ByteArrayOutputStream()
                val writers = ImageIO.getImageWritersByFormatName("jpeg")
                if (writers.hasNext()) {
                    val writer = writers.next()
                    try {
                        val param = writer.defaultWriteParam.apply {
                            if (canWriteCompressed()) {
                                compressionMode = ImageWriteParam.MODE_EXPLICIT
                                compressionQuality = 0.92f
                            }
                        }
                        ImageIO.createImageOutputStream(out).use { ios ->
                            writer.output = ios
                            writer.write(null, IIOImage(scaled, null, null), param)
                        }
                        return@use Artwork(out.toByteArray(), "image/jpeg")
                    } finally {
                        writer.dispose()
                    }
                }
            }
            val mime = if (contentType.startsWith("image/")) contentType else "image/jpeg"
            Artwork(raw, mime)
        }
    }.onFailure { TrackLog.d(TAG, "no cover saved for $logId: ${it.message}") }.getOrNull()

    private fun downscale(image: BufferedImage, maxSide: Int): BufferedImage {
        val longest = max(image.width, image.height)
        val scale = if (longest > maxSide) maxSide.toDouble() / longest else 1.0
        val targetW = (image.width * scale).toInt().coerceAtLeast(1)
        val targetH = (image.height * scale).toInt().coerceAtLeast(1)
        val rgb = BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB)
        val g = rgb.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.drawImage(image, 0, 0, targetW, targetH, null)
        } finally {
            g.dispose()
        }
        return rgb
    }
}
