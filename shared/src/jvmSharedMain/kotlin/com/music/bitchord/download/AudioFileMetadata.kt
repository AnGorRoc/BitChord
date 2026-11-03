package com.music.bitchord.download

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Reads embedded title, artist, album, track number, lyrics and cover art from
 * local audio files (`.flac`, `.m4a`/`.mp4`, `.webm`, `.mp3`).
 */
object AudioFileMetadata {

    private const val MAX_HEAD_BYTES = 8 * 1024 * 1024

    data class Tags(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val trackNumber: Int? = null,
        val coverBytes: ByteArray? = null,
        val coverMime: String? = null,
    )

    fun read(path: Path, artworkCacheDir: File? = null): Pair<Tags, String?>? {
        if (!Files.isRegularFile(path)) return null
        val head = runCatching {
            Files.newInputStream(path).use { stream ->
                val buffer = ByteArray(minOf(MAX_HEAD_BYTES.toLong(), Files.size(path).coerceAtLeast(1L)).toInt())
                var total = 0
                while (total < buffer.size) {
                    val n = stream.read(buffer, total, buffer.size - total)
                    if (n <= 0) break
                    total += n
                }
                if (total == buffer.size) buffer else buffer.copyOf(total)
            }
        }.getOrNull() ?: return null
        val tags = fromBytes(head) ?: return null
        val coverUri = if (artworkCacheDir != null && tags.coverBytes != null && tags.coverBytes.isNotEmpty()) {
            persistCover(artworkCacheDir, tags.coverBytes, tags.coverMime)
        } else {
            null
        }
        return tags to coverUri
    }

    fun fromBytes(bytes: ByteArray): Tags? = when {
        bytes.startsWith(FLAC_MAGIC) -> readFlac(bytes)
        bytes.startsWith(ID3_MAGIC) -> readId3(bytes)
        bytes.isMp4() -> readMp4(bytes)
        bytes.startsWith(MATROSKA_MAGIC) -> readMatroska(bytes)
        else -> null
    }

    /** Reads ID3v2 `USLT` lyrics from [bytes] if it begins with an ID3v2 header. */
    fun readId3Lyrics(bytes: ByteArray): String? = runCatching {
        if (!bytes.startsWith(ID3_MAGIC) || bytes.size < 10) return null
        val version = bytes[3].toInt() and 0xFF
        if (version !in 2..4) return null
        val tagSize = syncsafe(bytes, 6)
        if (tagSize <= 0 || tagSize > MAX_HEAD_BYTES) return null
        val tag = bytes.copyOfRange(10, minOf(10 + tagSize, bytes.size))
        var pos = 0
        if (version >= 3 && (bytes[5].toInt() and 0x40) != 0) {
            val declared = if (version == 4) syncsafe(tag, 0) else u32(tag, 0)
            if (declared < 0) return null
            pos = if (version == 4) declared else declared + 4
            if (pos < 0 || pos > tag.size) return null
        }
        val idLen = if (version == 2) 3 else 4
        val headLen = if (version == 2) 6 else 10
        while (pos + headLen <= tag.size) {
            val id = String(tag, pos, idLen, StandardCharsets.ISO_8859_1)
            if (!id.all { it in 'A'..'Z' || it in '0'..'9' }) break
            val size = when (version) {
                2 -> u24(tag, pos + 3)
                4 -> syncsafe(tag, pos + 4)
                else -> u32(tag, pos + 4)
            }
            if (size <= 0 || size > tag.size - pos - headLen) break
            if (id == "USLT" || id == "USL") {
                val text = usltPayload(tag.copyOfRange(pos + headLen, pos + headLen + size))
                if (text != null) return text
            }
            pos += headLen + size
        }
        null
    }.getOrNull()

    private fun readFlac(bytes: ByteArray): Tags? {
        var pos = FLAC_MAGIC.size
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var trackNumber: Int? = null
        var coverBytes: ByteArray? = null
        var coverMime: String? = null

        while (pos + 4 <= bytes.size) {
            val flags = bytes[pos].toInt() and 0xFF
            val type = flags and 0x7F
            val length = ((bytes[pos + 1].toInt() and 0xFF) shl 16) or
                ((bytes[pos + 2].toInt() and 0xFF) shl 8) or
                (bytes[pos + 3].toInt() and 0xFF)
            val start = pos + 4
            if (start + length > bytes.size) break
            when (type) {
                4 -> {
                    var p = start
                    fun le32(): Int? {
                        if (p + 4 > start + length) return null
                        val v = (bytes[p].toInt() and 0xFF) or
                            ((bytes[p + 1].toInt() and 0xFF) shl 8) or
                            ((bytes[p + 2].toInt() and 0xFF) shl 16) or
                            ((bytes[p + 3].toInt() and 0xFF) shl 24)
                        p += 4
                        return v
                    }
                    val vendor = le32() ?: -1
                    if (vendor >= 0 && p + vendor <= start + length) {
                        p += vendor
                        val count = le32() ?: 0
                        repeat(count.coerceIn(0, 4096)) {
                            val len = le32() ?: return@repeat
                            if (len <= 0 || p + len > start + length) return@repeat
                            val entry = String(bytes, p, len, StandardCharsets.UTF_8)
                            p += len
                            val key = entry.substringBefore('=').uppercase()
                            val value = entry.substringAfter('=', "").trim()
                            if (value.isNotEmpty()) {
                                when (key) {
                                    "TITLE" -> if (title == null) title = value
                                    "ARTIST" -> if (artist == null) artist = value
                                    "ALBUM" -> if (album == null) album = value
                                    "TRACKNUMBER", "TRACK" -> if (trackNumber == null) {
                                        trackNumber = value.substringBefore('/').trim().toIntOrNull()
                                    }
                                }
                            }
                        }
                    }
                }
                6 -> if (coverBytes == null && length > 32) {
                    var p = start + 4 // skip picture type
                    val mimeLen = u32(bytes, p)
                    p += 4
                    if (mimeLen in 0..128 && p + mimeLen + 20 <= start + length) {
                        val mime = String(bytes, p, mimeLen, StandardCharsets.US_ASCII)
                        p += mimeLen
                        val descLen = u32(bytes, p)
                        p += 4
                        if (descLen >= 0 && p + descLen + 20 <= start + length) {
                            p += descLen + 16 // skip width, height, depth, colors
                            val dataLen = u32(bytes, p)
                            p += 4
                            if (dataLen > 0 && p + dataLen <= start + length) {
                                coverBytes = bytes.copyOfRange(p, p + dataLen)
                                coverMime = mime.ifBlank { "image/jpeg" }
                            }
                        }
                    }
                }
            }
            if (flags and 0x80 != 0) break
            pos = start + length
        }
        if (title == null && artist == null && album == null && trackNumber == null && coverBytes == null) return null
        return Tags(title, artist, album, trackNumber, coverBytes, coverMime)
    }

    private fun readMp4(bytes: ByteArray): Tags? {
        val ilst = Mp4Boxes.find(bytes, "moov", "udta", "meta")?.let { meta ->
            Mp4Boxes.boxes(bytes, meta.content + 4, meta.end).firstOrNull { it.type == "ilst" }
        } ?: return null
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var trackNumber: Int? = null
        var coverBytes: ByteArray? = null
        var coverMime: String? = null

        for (item in Mp4Boxes.boxes(bytes, ilst.content, ilst.end)) {
            val data = Mp4Boxes.child(bytes, item, "data") ?: continue
            if (data.content + 8 > data.end) continue
            val typeIndicator = Mp4Boxes.readU32(bytes, data.content).toInt() and 0xFFFFFF
            val payloadStart = data.content + 8
            val payloadLen = data.end - payloadStart
            if (payloadLen <= 0) continue
            when (item.type) {
                "©nam" -> if (typeIndicator == 1) title = String(bytes, payloadStart, payloadLen, StandardCharsets.UTF_8).trim().takeIf { it.isNotEmpty() }
                "©ART" -> if (typeIndicator == 1) artist = String(bytes, payloadStart, payloadLen, StandardCharsets.UTF_8).trim().takeIf { it.isNotEmpty() }
                "©alb" -> if (typeIndicator == 1) album = String(bytes, payloadStart, payloadLen, StandardCharsets.UTF_8).trim().takeIf { it.isNotEmpty() }
                "trkn" -> if (payloadLen >= 4) {
                    val trk = ((bytes[payloadStart + 2].toInt() and 0xFF) shl 8) or (bytes[payloadStart + 3].toInt() and 0xFF)
                    if (trk > 0) trackNumber = trk
                }
                "covr" -> if (coverBytes == null) {
                    coverBytes = bytes.copyOfRange(payloadStart, data.end)
                    coverMime = if (typeIndicator == 14) "image/png" else "image/jpeg"
                }
            }
        }
        if (title == null && artist == null && album == null && trackNumber == null && coverBytes == null) return null
        return Tags(title, artist, album, trackNumber, coverBytes, coverMime)
    }

    private fun readMatroska(bytes: ByteArray): Tags? {
        fun findSimpleTag(name: String): String? {
            val needle = name.toByteArray(StandardCharsets.US_ASCII)
            var from = 0
            while (true) {
                val at = bytes.indexOf(needle, from, bytes.size) ?: return null
                from = at + needle.size
                if (at < 3 || bytes[at - 3] != 0x45.toByte() || bytes[at - 2] != 0xA3.toByte()) continue
                if ((bytes[at - 1].toInt() and 0x7F) != needle.size) continue
                val strAt = bytes.indexOf(ID_TAGSTRING, from, minOf(bytes.size, from + 256)) ?: continue
                val vint = readVint(bytes, strAt + 2) ?: continue
                val valueStart = strAt + 2 + vint.second
                if (vint.first <= 0 || valueStart + vint.first > bytes.size) continue
                return String(bytes, valueStart, vint.first.toInt(), StandardCharsets.UTF_8).trim().takeIf { it.isNotEmpty() }
            }
        }
        val title = findSimpleTag("TITLE")
        val artist = findSimpleTag("ARTIST")
        val album = findSimpleTag("ALBUM")
        val track = findSimpleTag("PART_NUMBER")?.substringBefore('/')?.trim()?.toIntOrNull()
        if (title == null && artist == null && album == null && track == null) return null
        return Tags(title, artist, album, track)
    }

    private fun readId3(bytes: ByteArray): Tags? = runCatching {
        if (bytes.size < 10) return null
        val version = bytes[3].toInt() and 0xFF
        if (version !in 2..4) return null
        val tagSize = syncsafe(bytes, 6)
        if (tagSize <= 0 || tagSize > MAX_HEAD_BYTES) return null
        val tag = bytes.copyOfRange(10, minOf(10 + tagSize, bytes.size))
        var pos = 0
        if (version >= 3 && (bytes[5].toInt() and 0x40) != 0) {
            val declared = if (version == 4) syncsafe(tag, 0) else u32(tag, 0)
            if (declared < 0) return null
            pos = if (version == 4) declared else declared + 4
            if (pos < 0 || pos > tag.size) return null
        }

        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var trackNumber: Int? = null
        var coverBytes: ByteArray? = null
        var coverMime: String? = null

        val idLen = if (version == 2) 3 else 4
        val headLen = if (version == 2) 6 else 10
        while (pos + headLen <= tag.size) {
            val id = String(tag, pos, idLen, StandardCharsets.ISO_8859_1)
            if (!id.all { it in 'A'..'Z' || it in '0'..'9' }) break
            val size = when (version) {
                2 -> u24(tag, pos + 3)
                4 -> syncsafe(tag, pos + 4)
                else -> u32(tag, pos + 4)
            }
            if (size <= 0 || size > tag.size - pos - headLen) break
            val payload = tag.copyOfRange(pos + headLen, pos + headLen + size)
            when (id) {
                "TIT2", "TT2" -> if (title == null) title = id3Text(payload)
                "TPE1", "TP1" -> if (artist == null) artist = id3Text(payload)
                "TALB", "TAL" -> if (album == null) album = id3Text(payload)
                "TRCK", "TRK" -> if (trackNumber == null) {
                    trackNumber = id3Text(payload)?.substringBefore('/')?.trim()?.toIntOrNull()
                }
                "APIC" -> if (coverBytes == null) {
                    apicPayload(payload)?.let { (bytes, mime) ->
                        coverBytes = bytes
                        coverMime = mime
                    }
                }
            }
            pos += headLen + size
        }
        if (title == null && artist == null && album == null && trackNumber == null && coverBytes == null) return null
        Tags(title, artist, album, trackNumber, coverBytes, coverMime)
    }.getOrNull()

    private fun id3Text(payload: ByteArray): String? {
        if (payload.size < 2) return null
        val charset = when (payload[0].toInt() and 0xFF) {
            0 -> StandardCharsets.ISO_8859_1
            1 -> StandardCharsets.UTF_16
            2 -> StandardCharsets.UTF_16BE
            3 -> StandardCharsets.UTF_8
            else -> return null
        }
        return String(payload, 1, payload.size - 1, charset)
            .trim { it <= ' ' || it == '\uFEFF' || it == '\u0000' }
            .takeIf { it.isNotEmpty() }
    }

    private fun apicPayload(payload: ByteArray): Pair<ByteArray, String>? {
        if (payload.size < 6) return null
        val encoding = payload[0].toInt() and 0xFF
        val mimeEnd = skipNull(payload, 1, 1)
        if (mimeEnd !in 2 until payload.size) return null
        val mime = String(payload, 1, mimeEnd - 2, StandardCharsets.ISO_8859_1).ifBlank { "image/jpeg" }
        val descStart = mimeEnd + 1 // skip picture type byte
        if (descStart >= payload.size) return null
        val width = if (encoding == 1 || encoding == 2) 2 else 1
        val dataStart = skipNull(payload, descStart, width).takeIf { it in descStart..payload.size } ?: return null
        if (dataStart >= payload.size) return null
        return payload.copyOfRange(dataStart, payload.size) to mime
    }

    private fun usltPayload(payload: ByteArray): String? {
        if (payload.size < 5) return null
        val encoding = payload[0].toInt() and 0xFF
        val charset = when (encoding) {
            0 -> StandardCharsets.ISO_8859_1
            1 -> StandardCharsets.UTF_16
            2 -> StandardCharsets.UTF_16BE
            3 -> StandardCharsets.UTF_8
            else -> return null
        }
        val width = if (encoding == 1 || encoding == 2) 2 else 1
        val textAt = skipNull(payload, 4, width).takeIf { it in 4 until payload.size } ?: 4
        return String(payload, textAt, payload.size - textAt, charset)
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim { it == '\uFEFF' || it == '\u0000' }
            .takeIf { it.isNotBlank() }
    }

    private fun skipNull(b: ByteArray, from: Int, width: Int): Int {
        var i = from
        while (i + width <= b.size) {
            var zero = true
            for (k in 0 until width) {
                if (b[i + k] != 0.toByte()) zero = false
            }
            if (zero) return i + width
            i += width
        }
        return -1
    }

    private fun persistCover(cacheDir: File, bytes: ByteArray, mime: String?): String? = runCatching {
        val folder = File(cacheDir, "local-artwork")
        if (!folder.exists() && !folder.mkdirs()) return null
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
            .take(16)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        val ext = if (mime?.contains("png", ignoreCase = true) == true) "png" else "jpg"
        val target = File(folder, "$hash.$ext")
        if (!target.exists()) target.writeBytes(bytes)
        target.toURI().toString()
    }.getOrNull()

    private fun syncsafe(b: ByteArray, off: Int): Int {
        if (off < 0 || off + 4 > b.size) return -1
        return ((b[off].toInt() and 0x7F) shl 21) or
            ((b[off + 1].toInt() and 0x7F) shl 14) or
            ((b[off + 2].toInt() and 0x7F) shl 7) or
            (b[off + 3].toInt() and 0x7F)
    }

    private fun u24(b: ByteArray, off: Int): Int {
        if (off < 0 || off + 3 > b.size) return -1
        return ((b[off].toInt() and 0xFF) shl 16) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            (b[off + 2].toInt() and 0xFF)
    }

    private fun u32(b: ByteArray, off: Int): Int {
        if (off < 0 || off + 4 > b.size) return -1
        val v = ((b[off].toLong() and 0xFF) shl 24) or
            ((b[off + 1].toLong() and 0xFF) shl 16) or
            ((b[off + 2].toLong() and 0xFF) shl 8) or
            (b[off + 3].toLong() and 0xFF)
        return if (v > Int.MAX_VALUE) Int.MAX_VALUE else v.toInt()
    }

    private fun readVint(bytes: ByteArray, offset: Int): Pair<Long, Int>? {
        if (offset >= bytes.size) return null
        val first = bytes[offset].toInt() and 0xFF
        if (first == 0) return null
        var width = 1
        var mask = 0x80
        while (first and mask == 0) {
            mask = mask shr 1
            width++
        }
        if (offset + width > bytes.size) return null
        var value = (first and mask.inv() and 0xFF).toLong()
        for (i in 1 until width) value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
        return value to width
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private fun ByteArray.isMp4(): Boolean =
        size > 12 && this[4] == 'f'.code.toByte() && this[5] == 't'.code.toByte() &&
            this[6] == 'y'.code.toByte() && this[7] == 'p'.code.toByte()

    private fun ByteArray.indexOf(needle: ByteArray, from: Int, until: Int): Int? {
        if (needle.isEmpty()) return null
        val last = minOf(until, size) - needle.size
        var i = from.coerceAtLeast(0)
        outer@ while (i <= last) {
            for (j in needle.indices) {
                if (this[i + j] != needle[j]) {
                    i++
                    continue@outer
                }
            }
            return i
        }
        return null
    }

    private val FLAC_MAGIC = "fLaC".toByteArray(StandardCharsets.US_ASCII)
    private val ID3_MAGIC = "ID3".toByteArray(StandardCharsets.US_ASCII)
    private val MATROSKA_MAGIC = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())
    private val ID_TAGSTRING = byteArrayOf(0x44, 0x87.toByte())
}
