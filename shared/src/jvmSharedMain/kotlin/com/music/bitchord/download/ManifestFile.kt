package com.music.bitchord.download

import com.music.bitchord.data.Http
import com.music.bitchord.download.Mp4Boxes.boxes
import com.music.bitchord.download.Mp4Boxes.child
import com.music.bitchord.download.Mp4Boxes.find
import com.music.bitchord.download.Mp4Boxes.readU32
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.File
import java.io.OutputStream

/**
 * A manifest's segments joined back into one ordinary file, so a lossless
 * download is a `.flac` whether it stays in the app or is exported.
 */
object ManifestFile {

    /** The extensions this can produce; a manifest bound for anything else stays declined. */
    val EXTENSIONS = setOf("flac", "m4a")

    suspend fun write(
        cacheDir: File,
        url: String,
        headers: Map<String, String>,
        dash: Boolean,
        extension: String,
        sink: OutputStream,
        onProgress: (Long, Long) -> Unit,
    ): Unit = withContext(Dispatchers.IO) {
        fun fetch(target: String): ByteArray {
            val request = Request.Builder().url(target).apply {
                headers.forEach { (name, value) -> header(name, value) }
            }.build()
            return Http.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("Segment failed (HTTP ${response.code})")
                response.body.bytes() ?: error("Empty segment")
            }
        }

        val base = url.toHttpUrlOrNull() ?: error("Invalid manifest URL")
        val index = String(fetch(url), Charsets.UTF_8)
        val (initRef, mediaRefs) = if (dash) {
            OfflineDash.parse(index).let { it.initialization to it.media }
        } else {
            hlsSegments(index)
        }
        fun resolve(ref: String) = base.resolve(ref)?.toString() ?: error("Invalid segment URL")
        val total = mediaRefs.size + 1L

        val init = fetch(resolve(initRef))
        onProgress(1, total)
        val scope = coroutineContext
        val media = mediaRefs.asSequence().mapIndexed { i, ref ->
            scope.ensureActive()
            fetch(resolve(ref)).also { onProgress(i + 2L, total) }
        }

        assemble(cacheDir, extension, init, media, sink)
        sink.flush()
    }

    /** Everything after the fetching: [init] and [media] in, one [extension] file out. */
    fun assemble(cacheDir: File, extension: String, init: ByteArray, media: Sequence<ByteArray>, sink: OutputStream) {
        when (extension) {
            "flac" -> writeFlac(cacheDir, init, media, sink)
            "m4a" -> {
                sink.write(init)
                media.forEach { segment ->
                    boxes(segment).filter { it.type != "styp" && it.type != "sidx" }.forEach {
                        sink.write(segment, it.offset, it.size)
                    }
                }
            }
            else -> error("Can't save a .$extension from a manifest")
        }
    }

    /** The init segment and media segments of an HLS media playlist, refusing what [OfflineHls] refuses. */
    fun hlsSegments(playlist: String): Pair<String, List<String>> {
        if (!playlist.startsWith("#EXTM3U")) error("Invalid HLS playlist")
        if (playlist.contains("#EXT-X-KEY:") && !playlist.contains("METHOD=NONE")) {
            error("Encrypted HLS cannot be saved")
        }
        val init = Regex("""#EXT-X-MAP:.*URI="([^"]+)"""").find(playlist)?.groupValues?.get(1)
            ?: error("HLS playlist has no initialisation segment")
        val media = playlist.lineSequence().map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith('#') }
            .toList()
        if (media.isEmpty()) error("HLS playlist has no fragments")
        return init to media
    }

    private fun writeFlac(cacheDir: File, init: ByteArray, media: Sequence<ByteArray>, sink: OutputStream) {
        val trak = find(init, "moov", "trak") ?: error("Not an MP4 initialisation segment")
        val trackId = readU32(init, (child(init, trak, "tkhd") ?: error("No track header")).let {
            it.content + if (init[it.content].toInt() == 1) 20 else 12
        })
        val mdhd = find(init, "moov", "trak", "mdia", "mdhd") ?: error("No media header")
        val timescale = readU32(init, mdhd.content + if (init[mdhd.content].toInt() == 1) 20 else 12)
        val stsd = find(init, "moov", "trak", "mdia", "minf", "stbl", "stsd") ?: error("No sample description")
        val entry = boxes(init, stsd.content + 8, stsd.end).firstOrNull()
        if (entry?.type != "fLaC") error("Stream is ${entry?.type ?: "unknown"}, not FLAC")
        val dfla = boxes(init, entry.content + 28, entry.end).firstOrNull { it.type == "dfLa" }
            ?: error("FLAC stream has no dfLa")
        val blocks = metadataBlocks(init.copyOfRange(dfla.content + 4, dfla.end))
        val trex = find(init, "moov", "mvex", "trex")
        val trexDuration = trex?.let { readU32(init, it.content + 12) } ?: 0L
        val trexSize = trex?.let { readU32(init, it.content + 16) } ?: 0L

        val frames = File.createTempFile("manifest-", ".flac", cacheDir)
        try {
            var ticks = 0L
            frames.outputStream().buffered().use { out ->
                media.forEach { segment ->
                    boxes(segment).filter { it.type == "moof" }.forEach { moof ->
                        val samples = Mp4Boxes.samples(segment, moof, trackId, trexDuration, trexSize)
                            ?: error("Unreadable FLAC fragment")
                        samples.forEach {
                            out.write(segment, it.offset, it.size)
                            ticks += it.duration
                        }
                    }
                }
            }
            if (ticks <= 0) error("Manifest held no audio")

            val info = blocks.first().second
            val rate = ((info[10].toLong() and 0xFF) shl 12) or ((info[11].toLong() and 0xFF) shl 4) or
                ((info[12].toLong() and 0xFF) shr 4)
            val totalSamples = if (timescale > 0 && rate > 0) ticks * rate / timescale else ticks
            info[13] = ((info[13].toInt() and 0xF0) or ((totalSamples shr 32).toInt() and 0x0F)).toByte()
            for (i in 0 until 4) info[14 + i] = (totalSamples shr (8 * (3 - i))).toByte()

            sink.write("fLaC".toByteArray(Charsets.ISO_8859_1))
            blocks.forEachIndexed { i, (type, body) ->
                val last = if (i == blocks.lastIndex) 0x80 else 0
                sink.write(byteArrayOf((last or type).toByte(), (body.size shr 16).toByte(), (body.size shr 8).toByte(), body.size.toByte()))
                sink.write(body)
            }
            frames.inputStream().use { it.copyTo(sink) }
        } finally {
            frames.delete()
        }
    }

    private fun metadataBlocks(payload: ByteArray): List<Pair<Int, ByteArray>> {
        val out = mutableListOf<Pair<Int, ByteArray>>()
        var p = 0
        while (p + 4 <= payload.size) {
            val type = payload[p].toInt() and 0x7F
            val length = ((payload[p + 1].toInt() and 0xFF) shl 16) or
                ((payload[p + 2].toInt() and 0xFF) shl 8) or (payload[p + 3].toInt() and 0xFF)
            if (p + 4 + length > payload.size) break
            out += type to payload.copyOfRange(p + 4, p + 4 + length)
            val last = payload[p].toInt() and 0x80 != 0
            p += 4 + length
            if (last) break
        }
        if (out.firstOrNull()?.let { it.first == 0 && it.second.size == 34 } != true) error("FLAC stream has no STREAMINFO")
        return out
    }
}
