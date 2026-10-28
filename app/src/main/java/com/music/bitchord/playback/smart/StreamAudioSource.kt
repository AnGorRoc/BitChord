package com.music.bitchord.playback.smart

import android.content.Context
import android.media.MediaDataSource
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.music.bitchord.playback.AudioCache
import java.io.IOException

/**
 * Adapts a Media3 [DataSource] to Android's [MediaDataSource] for random-access
 * inspection and decoding of audio streams.
 *
 * Keeps the underlying source open across consecutive sequential reads —
 * the access pattern [android.media.MediaExtractor] uses during decoding —
 * and reopens at a new position when seeking.
 */
@UnstableApi
class StreamAudioSource(
    private val dataSource: DataSource,
    private val uri: Uri,
) : MediaDataSource() {

    private var isOpen = false
    private var openPosition = -1L

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size <= 0) return 0
        if (position < 0) return -1
        if (offset < 0 || offset + size > buffer.size) return -1
        if (!isOpen || position != openPosition) {
            closeUpstream()
            val available = try {
                dataSource.open(DataSpec.Builder().setUri(uri).setPosition(position).build())
            } catch (error: IOException) {
                return -1
            }
            isOpen = true
            openPosition = position
            if (available == 0L) return -1
        }
        val count = try {
            dataSource.read(buffer, offset, size)
        } catch (error: IOException) {
            closeUpstream()
            return -1
        }
        if (count <= 0) {
            closeUpstream()
            return -1
        }
        openPosition += count
        return count
    }

    override fun getSize(): Long {
        closeUpstream()
        return try {
            dataSource.open(DataSpec(uri))
        } catch (error: IOException) {
            -1L
        } finally {
            closeUpstream()
        }
    }

    override fun close() {
        closeUpstream()
    }

    private fun closeUpstream() {
        if (isOpen) {
            runCatching { dataSource.close() }
            isOpen = false
        }
    }

    companion object {
        /**
         * Safely closes [ds] without throwing exceptions.
         */
        fun safeClose(ds: MediaDataSource?) {
            if (ds != null) {
                runCatching { ds.close() }
            }
        }

        /**
         * Opens [uri] for random-access reading via [LocalAudioSource] or [AudioCache].
         * Returns null when no seekable bytes can be read.
         */
        fun open(context: Context? = null, uri: Uri): MediaDataSource? {
            if (LocalAudioSource.isLocal(uri)) {
                return context?.let { LocalAudioSource.open(it.contentResolver, uri) }
            }
            return AudioCache.streamMediaDataSource(uri)
        }
    }
}

