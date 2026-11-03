package com.music.bitchord.data.artwork

import com.music.bitchord.data.Http
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * Shared JVM high-resolution artwork downloader.
 */
object ArtworkFileDownload {

    private const val TAG = "BitChord"
    const val FOLDER_NAME = "BitChord"

    data class Fetched(val data: ByteArray, val contentType: String)

    /** Largest rendition for a thumbnail URL, or null when there is no art. */
    fun fullUrl(thumbnailUrl: String?): String? {
        if (thumbnailUrl.isNullOrBlank()) return null
        val sized = Regex("""w\d+-h\d+""").replace(thumbnailUrl, "w1400-h1400")
        if (sized != thumbnailUrl) return sized
        if ("/640x640.jpg" in thumbnailUrl) {
            return thumbnailUrl.replace("/640x640.jpg", "/1280x1280.jpg")
        }
        return thumbnailUrl
    }

    /** Fetches full-quality bytes and saves them into [picturesDir]/BitChord (defaults to `~/Pictures/BitChord`). */
    suspend fun saveToFolder(
        song: Song,
        picturesDir: File = File(System.getProperty("user.home"), "Pictures"),
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val url = fullUrl(song.thumbnailUrl) ?: error("No artwork available")
            val fetched = fetch(url)
            val ext = mimeExt(fetched.contentType)
            val name = fileName(song, ext)
            val dir = File(picturesDir, FOLDER_NAME).also { it.mkdirs() }
            val target = File(dir, name)
            FileOutputStream(target).use { it.write(fetched.data) }
            TrackLog.d(TAG, "cover saved: ${target.absolutePath} (${fetched.data.size / 1024} KB)")
            target
        }
    }

    fun fetch(url: String): Fetched {
        if (url.startsWith("file:")) {
            val file = File(java.net.URI(url))
            val bytes = file.readBytes().takeIf { it.isNotEmpty() } ?: error("Empty image file")
            val mime = if (file.name.endsWith(".png", ignoreCase = true)) "image/png" else "image/jpeg"
            return Fetched(bytes, mime)
        }
        val request = Request.Builder().url(url)
            .header("User-Agent", "BitChord")
            .build()
        Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val type = response.header("Content-Type")?.substringBefore(';')?.trim().orEmpty()
            if (!type.startsWith("image/")) error("Not an image")
            val data = response.body.bytes()?.takeIf { it.isNotEmpty() } ?: error("Empty image")
            return Fetched(data, type)
        }
    }

    fun mimeExt(mime: String): String = when (mime.lowercase(Locale.ROOT)) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        else -> "jpg"
    }

    fun fileName(song: Song, ext: String): String {
        val safe = { s: String ->
            s.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-').take(60)
        }
        val base = "${safe(song.artist)}-${safe(song.title)}".trim('-').ifBlank { "cover" }
        return "$base.$ext"
    }
}
