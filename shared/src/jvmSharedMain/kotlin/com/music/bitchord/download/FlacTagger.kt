package com.music.bitchord.download

import com.music.bitchord.data.lyrics.WORD_LYRICS_FIELD
import java.io.ByteArrayOutputStream

/**
 * Rewrites a downloaded FLAC's metadata blocks to carry title, artist, album,
 * lyrics and cover art.
 */
object FlacTagger {

    fun tag(
        bytes: ByteArray,
        title: String,
        artist: String,
        album: String?,
        lyrics: String?,
        cover: ByteArray?,
        coverMime: String,
        /** The A2 form, under a name of this app's own — see [WORD_LYRICS_FIELD]. */
        wordLyrics: String? = null,
    ): ByteArray = runCatching {
        val done = header(bytes, title, artist, album, lyrics, cover, coverMime, wordLyrics)
            ?: return@runCatching bytes
        val out = ByteArrayOutputStream(done.metadata.size + (bytes.size - done.audioStart))
        out.write(done.metadata)
        // The frames, verbatim.
        out.write(bytes, done.audioStart, bytes.size - done.audioStart)
        out.toByteArray()
    }.getOrDefault(bytes)

    class Rewrite(val metadata: ByteArray, val audioStart: Int)

    fun header(
        head: ByteArray,
        title: String,
        artist: String,
        album: String?,
        lyrics: String?,
        cover: ByteArray?,
        coverMime: String,
        wordLyrics: String? = null,
    ): Rewrite? {
        if (!head.regionMatches(0, MAGIC)) return null

        val blocks = mutableListOf<Block>()
        var offset = MAGIC.size
        while (true) {
            if (offset + BLOCK_HEADER > head.size) return null
            val flags = head[offset].toInt() and 0xFF
            val length = ((head[offset + 1].toInt() and 0xFF) shl 16) or
                ((head[offset + 2].toInt() and 0xFF) shl 8) or
                (head[offset + 3].toInt() and 0xFF)
            val start = offset + BLOCK_HEADER
            if (start + length > head.size) return null
            blocks += Block(flags and 0x7F, start, length)
            offset = start + length
            if (flags and 0x80 != 0) break
        }
        if (blocks.firstOrNull()?.type != TYPE_STREAMINFO) return null

        val additions = listOfNotNull(
            vorbisComment(title, artist, album, lyrics, wordLyrics)?.let { TYPE_VORBIS_COMMENT to it },
            cover?.takeIf { it.isNotEmpty() }?.let { TYPE_PICTURE to picture(it, coverMime) },
        ).filter { it.second.size <= MAX_BLOCK_BYTES }
        if (additions.isEmpty()) return null

        val chain = blocks.filterNot { it.type in REPLACED }
            .map { it.type to head.copyOfRange(it.start, it.start + it.length) } + additions

        val out = ByteArrayOutputStream(offset + additions.sumOf { it.second.size } + 64)
        out.write(MAGIC)
        chain.forEachIndexed { index, (type, payload) ->
            out.write(if (index == chain.lastIndex) type or 0x80 else type)
            out.write((payload.size ushr 16) and 0xFF)
            out.write((payload.size ushr 8) and 0xFF)
            out.write(payload.size and 0xFF)
            out.write(payload)
        }
        return Rewrite(out.toByteArray(), offset)
    }

    private fun vorbisComment(
        title: String,
        artist: String,
        album: String?,
        lyrics: String?,
        wordLyrics: String?,
    ): ByteArray? {
        val fields = buildList {
            if (title.isNotBlank()) add("TITLE=$title")
            if (artist.isNotBlank()) add("ARTIST=$artist")
            if (!album.isNullOrBlank()) add("ALBUM=$album")
            if (!lyrics.isNullOrBlank()) add("LYRICS=$lyrics")
            if (!wordLyrics.isNullOrBlank()) add("$WORD_LYRICS_FIELD=$wordLyrics")
        }
        if (fields.isEmpty()) return null

        val out = ByteArrayOutputStream()
        val vendor = VENDOR.toByteArray(Charsets.UTF_8)
        out.writeLe(vendor.size)
        out.write(vendor)
        out.writeLe(fields.size)
        for (field in fields) {
            val encoded = field.toByteArray(Charsets.UTF_8)
            out.writeLe(encoded.size)
            out.write(encoded)
        }
        return out.toByteArray()
    }

    private fun picture(cover: ByteArray, mime: String): ByteArray {
        val out = ByteArrayOutputStream(cover.size + 64)
        val mimeBytes = mime.toByteArray(Charsets.US_ASCII)
        out.writeBe(PICTURE_FRONT_COVER)
        out.writeBe(mimeBytes.size)
        out.write(mimeBytes)
        out.writeBe(0) // description length
        out.writeBe(0) // width
        out.writeBe(0) // height
        out.writeBe(0) // colour depth
        out.writeBe(0) // colours used
        out.writeBe(cover.size)
        out.write(cover)
        return out.toByteArray()
    }

    private class Block(val type: Int, val start: Int, val length: Int)

    private fun ByteArrayOutputStream.writeLe(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 24) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeBe(value: Int) {
        write((value ushr 24) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 8) and 0xFF)
        write(value and 0xFF)
    }

    private fun ByteArray.regionMatches(offset: Int, other: ByteArray): Boolean {
        if (offset < 0 || offset + other.size > size) return false
        for (i in other.indices) if (this[offset + i] != other[i]) return false
        return true
    }

    private val MAGIC = "fLaC".toByteArray(Charsets.US_ASCII)
    private const val BLOCK_HEADER = 4
    private const val TYPE_STREAMINFO = 0
    private const val TYPE_PADDING = 1
    private const val TYPE_VORBIS_COMMENT = 4
    private const val TYPE_PICTURE = 6
    private val REPLACED = setOf(TYPE_PADDING, TYPE_VORBIS_COMMENT, TYPE_PICTURE)
    private const val MAX_BLOCK_BYTES = (1 shl 24) - 1
    private const val PICTURE_FRONT_COVER = 3
    private const val VENDOR = "BitChord"
}
