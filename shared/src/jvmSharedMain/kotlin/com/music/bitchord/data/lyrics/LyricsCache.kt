package com.music.bitchord.data.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Collections
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Persistent disk and memory cache for synchronized lyrics.
 *
 * Keeps fetched lyrics stored on disk under the application cache directory so
 * replaying tracks or listening offline does not require repetitive remote
 * network queries.
 */
object LyricsCache {

    private const val CACHE_VERSION = 1
    private const val CACHE_DIRECTORY = "lyrics_cache_v1"
    private const val MAX_CACHE_BYTES = 10L * 1024L * 1024L // 10 MB
    private const val MEMORY_CACHE_LIMIT = 50

    @Serializable
    data class Entry(
        val version: Int = CACHE_VERSION,
        val source: LyricsSource,
        val lines: List<LyricLine>,
        val isrc: String? = null,
        val timestampMs: Long = System.currentTimeMillis(),
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val diskMutex = Mutex()

    private val memory: MutableMap<String, Entry> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Entry>(MEMORY_CACHE_LIMIT, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, Entry>) = size > MEMORY_CACHE_LIMIT
        },
    )

    /**
     * Cache directory on device or host. Null keeps cache in memory only.
     */
    @Volatile
    var cacheDir: File? = null

    suspend fun get(
        videoId: String,
        title: String,
        artist: String,
        source: LyricsSource? = null,
    ): Entry? {
        val key = cacheKey(videoId, title, artist, source)
        memory[key]?.let { return it }

        return readDisk(key)?.also {
            memory[key] = it
        }
    }

    suspend fun put(
        videoId: String,
        title: String,
        artist: String,
        source: LyricsSource,
        lines: List<LyricLine>,
        isrc: String? = null,
        isPrimary: Boolean = false,
    ) {
        if (lines.isEmpty()) return
        val entry = Entry(
            version = CACHE_VERSION,
            source = source,
            lines = lines,
            isrc = isrc,
        )

        val specificKey = cacheKey(videoId, title, artist, source)
        memory[specificKey] = entry
        writeDisk(specificKey, entry)

        if (isPrimary) {
            val primaryKey = cacheKey(videoId, title, artist, null)
            memory[primaryKey] = entry
            writeDisk(primaryKey, entry)
        }
    }

    fun clearMemory() {
        memory.clear()
    }

    private fun cacheKey(
        videoId: String,
        title: String,
        artist: String,
        source: LyricsSource?,
    ): String {
        val raw = buildString {
            if (videoId.isNotBlank()) {
                append("v:").append(videoId.trim())
            } else {
                append("t:").append(title.trim().lowercase())
                    .append(":a:").append(artist.trim().lowercase())
            }
            if (source != null) {
                append(":s:").append(source.name)
            } else {
                append(":primary")
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private suspend fun readDisk(key: String): Entry? = withContext(Dispatchers.IO) {
        diskMutex.withLock {
            val root = cacheDir ?: return@withLock null
            val file = File(File(root, CACHE_DIRECTORY), "$key.json.gz")
            if (!file.isFile) return@withLock null
            runCatching {
                val entry = GZIPInputStream(FileInputStream(file)).bufferedReader().use {
                    json.decodeFromString<Entry>(it.readText())
                }
                file.setLastModified(System.currentTimeMillis())
                entry.takeIf { it.version == CACHE_VERSION }
            }.getOrNull()
        }
    }

    private suspend fun writeDisk(key: String, entry: Entry) = withContext(Dispatchers.IO) {
        diskMutex.withLock {
            val root = cacheDir ?: return@withLock
            val directory = File(root, CACHE_DIRECTORY)
            if (!directory.exists() && !directory.mkdirs()) return@withLock
            val destination = File(directory, "$key.json.gz")
            val temporary = File(directory, "$key.tmp")
            runCatching {
                GZIPOutputStream(FileOutputStream(temporary)).bufferedWriter().use {
                    it.write(json.encodeToString(entry))
                }
                if (!temporary.renameTo(destination)) {
                    temporary.copyTo(destination, overwrite = true)
                    temporary.delete()
                }
                trimCache(directory)
            }.onFailure { temporary.delete() }
        }
    }

    private fun trimCache(directory: File) {
        val files = directory.listFiles { file -> file.extension == "gz" }
            ?.sortedByDescending(File::lastModified)
            .orEmpty()
        var total = 0L
        for (file in files) {
            total += file.length()
            if (total > MAX_CACHE_BYTES) {
                file.delete()
            }
        }
    }
}

