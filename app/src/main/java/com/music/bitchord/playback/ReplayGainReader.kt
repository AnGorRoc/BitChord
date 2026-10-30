package com.music.bitchord.playback

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Reads common ReplayGain metadata from local audio tracks or from the ReplayGainScanner cache.
 * Designed without native JNI dependencies (such as TagLib) for pure Android platform stability.
 */
object ReplayGainReader {
    private const val MAX_CACHED_FILES = 256
    private val cacheMutex = Mutex()
    private val propertyCache = object : LinkedHashMap<String, Map<String, Array<String>>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Map<String, Array<String>>>?): Boolean =
            size > MAX_CACHED_FILES
    }

    suspend fun hasReplayGainTags(context: Context, song: Song): Boolean =
        ReplayGainValues.hasGainTags(readProperties(context, song))

    private suspend fun readProperties(context: Context, song: Song): Map<String, Array<String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val source = song.localUri ?: return@runCatching emptyMap()
                val uri = Uri.parse(source).let { parsed ->
                    if (parsed.scheme == null) Uri.fromFile(File(source)) else parsed
                }
                val cacheKey = song.localUri ?: source
                cacheMutex.withLock {
                    propertyCache[cacheKey] ?: run {
                        val properties = mutableMapOf<String, Array<String>>()
                        val retriever = MediaMetadataRetriever()
                        try {
                            when (uri.scheme) {
                                "content" -> retriever.setDataSource(context, uri)
                                "file" -> retriever.setDataSource(uri.path)
                                else -> retriever.setDataSource(source)
                            }
                            // Check scanner store first
                            val cached = ReplayGainScanner.cachedProperties(context, song)
                            if (!cached.isNullOrEmpty()) {
                                properties.putAll(cached)
                            }
                        } catch (_: Throwable) {
                        } finally {
                            try {
                                retriever.release()
                            } catch (_: Throwable) {
                            }
                        }
                        if (properties.isNotEmpty()) propertyCache[cacheKey] = properties
                        properties
                    }
                }
            }.getOrDefault(emptyMap())
        }
}
