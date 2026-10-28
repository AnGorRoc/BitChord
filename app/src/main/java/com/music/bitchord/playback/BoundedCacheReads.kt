package com.music.bitchord.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.util.concurrent.ConcurrentHashMap

/**
 * The player's reads of a cached track, opened a segment at a time so the
 * cache is only ever locked for the segment being read.
 *
 * Media3 locks a cache entry per *range*, and the range a reader locks is the
 * hole it is filling, clipped to the length it asked for. ExoPlayer asks for
 * the rest of the file, so a player reading into an empty stretch locked
 * everything from there to the end — and kept it locked while paused, because
 * its loader blocks with the source still open once the buffer is full. That
 * made the playing track the one track nothing else could finish: a fetch
 * aimed past the player found the range held, read the network into nowhere,
 * and gave up. Read a segment at a time and the lock covers one segment, so
 * the rest of the file is free for [AudioCache.fillRendition] to download
 * while the player waits — in particular through a pause.
 *
 * Transparent to the player. [open] reports the rest of the file, as before,
 * and each segment is opened on the next [read] after the previous one ends.
 *
 * ### One key for the whole open
 *
 * The cache key is worked out once, when the player opens, and carried on
 * every segment. [AudioCache]'s key factory answers from settings and from
 * what else is on disk, and either can change mid-track — a source re-ranked,
 * Atmos turned off, an upgrade completing. A reopen used to happen once per
 * seek, so that was rare; a reopen every segment would have made it routine,
 * and a segment opened under a different key is a second file read into the
 * middle of the first.
 *
 * ### When it stays out of the way
 *
 * A bounded request is passed straight through, and so is any file whose
 * length is not yet known or that would fit in two segments. A first open
 * whose length is unknown reads open-ended up to the next segment boundary —
 * the response says how long the file is — and segments from there on.
 *
 * Every range held, segmented or not, is published in [claims], so a writer
 * working alongside can step around it rather than collide with it.
 */
@UnstableApi
internal class BoundedCacheReads(
    private val cacheSource: DataSource,
    private val keyOf: (DataSpec) -> String,
    private val knownLength: (key: String) -> Long,
    private val segmentBytesFor: (Uri) -> Long,
) : DataSource {

    private var base: DataSpec? = null
    private var key: String? = null
    private var position = 0L
    private var total = C.LENGTH_UNSET.toLong()
    private var segment = 0L

    /** Where the source open right now stops, exclusive; [Long.MAX_VALUE] for an open-ended one. */
    private var openEnd = 0L
    private var opened = false

    /** Passing the caller's request straight through: bounded, or nothing to gain by segmenting. */
    private var passthrough = false

    override fun addTransferListener(transferListener: TransferListener) {
        cacheSource.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val key = keyOf(dataSpec)
        this.key = key
        val keyed = dataSpec.buildUpon().setKey(key).build()
        base = keyed
        position = dataSpec.position
        segment = segmentBytesFor(dataSpec.uri)
        val known = knownLength(key)
        if (dataSpec.length != C.LENGTH_UNSET.toLong() || segment <= 0) {
            passthrough = true
            return openSource(keyed, end = if (dataSpec.length == C.LENGTH_UNSET.toLong()) Long.MAX_VALUE else position + dataSpec.length)
        }
        passthrough = false
        if (known > 0) {
            total = known
            if (!worthSegmenting(total) || position >= total) {
                passthrough = true
                return openSource(keyed, end = Long.MAX_VALUE)
            }
            openSegment()
            return (total - position).coerceAtLeast(0L)
        }
        // Length unknown: one open-ended read, which learns it, held only up
        // to the next boundary.
        val remaining = openSource(keyed, end = Long.MAX_VALUE)
        if (remaining == C.LENGTH_UNSET.toLong() || !worthSegmenting(position + remaining)) {
            passthrough = true
            return remaining
        }
        total = position + remaining
        openEnd = boundaryAfter(position)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (passthrough) return cacheSource.read(buffer, offset, length)
        if (position >= total) return C.RESULT_END_OF_INPUT
        if (position >= openEnd) {
            closeSource()
            openSegment()
        }
        val wanted = minOf(length.toLong(), openEnd - position).toInt()
        val read = cacheSource.read(buffer, offset, wanted)
        if (read == C.RESULT_END_OF_INPUT) return read
        position += read
        return read
    }

    override fun getUri(): Uri? = cacheSource.uri

    override fun getResponseHeaders(): Map<String, List<String>> = cacheSource.responseHeaders

    override fun close() {
        closeSource()
        base = null
        key = null
        passthrough = false
        total = C.LENGTH_UNSET.toLong()
    }

    private fun worthSegmenting(length: Long) = length > 2 * segment

    private fun boundaryAfter(at: Long): Long = boundaryAfter(at, segment, total)

    private fun openSegment() {
        val spec = requireNotNull(base)
        val end = boundaryAfter(position)
        openSource(spec.buildUpon().setPosition(position).setLength(end - position).build(), end)
    }

    private fun openSource(spec: DataSpec, end: Long): Long {
        claim(spec.position, end)
        opened = true
        openEnd = end
        return try {
            cacheSource.open(spec)
        } catch (e: Exception) {
            closeSource()
            throw e
        }
    }

    private fun closeSource() {
        if (!opened) return
        opened = false
        claims.remove(this)
        cacheSource.close()
    }

    private fun claim(start: Long, end: Long) {
        claims[this] = Claim(requireNotNull(key), start, end)
    }

    /** A range of [key] some player is reading, and so holding locked; [end] is exclusive. */
    data class Claim(val key: String, val start: Long, val end: Long) {
        fun overlaps(from: Long, to: Long) = from < end && to > start
    }

    companion object {
        /**
         * Where the segment holding [at] ends: the next multiple of [segment],
         * or [total]. A fixed grid, so a reopen after a seek lands on the same
         * boundaries a fill is working along.
         */
        internal fun boundaryAfter(at: Long, segment: Long, total: Long): Long =
            minOf((at / segment + 1) * segment, total)

        /** Every range a player has open right now, one per reader. */
        private val claims = ConcurrentHashMap<BoundedCacheReads, Claim>()

        /** The ranges of [key] players hold right now. */
        fun claimsOn(key: String): List<Claim> = claims.values.filter { it.key == key }

        /** Every range players hold right now, of any key. */
        fun allClaims(): List<Claim> = claims.values.toList()
    }
}
