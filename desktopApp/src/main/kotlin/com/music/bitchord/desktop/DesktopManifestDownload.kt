package com.music.bitchord.desktop

import com.music.bitchord.download.ManifestFile
import com.music.bitchord.download.Mp4Sidx
import com.music.bitchord.download.OfflineDash
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.OutputStream
import java.net.URI
import java.util.Locale

/**
 * A download whose URL is an HLS or DASH manifest, saved as one ordinary audio file.
 *
 * Delegates assembly and indexing to shared [ManifestFile], [OfflineDash] and [Mp4Sidx].
 */
internal object DesktopManifestDownload {

    /** Whether [stream] points at an index of the audio rather than the audio. */
    fun isManifest(stream: DesktopStream): Boolean = transportOf(stream) != null

    /** `dash`, `hls`, or null — what the source declared, else the URL's extension. */
    private fun transportOf(stream: DesktopStream): String? =
        stream.transport?.lowercase()?.takeIf { it == DesktopAddonStream.DASH || it == DesktopAddonStream.HLS }
            ?: when (stream.url.substringBefore('?').substringAfterLast('.').lowercase(Locale.ROOT)) {
                "mpd" -> DesktopAddonStream.DASH
                "m3u8" -> DesktopAddonStream.HLS
                else -> null
            }

    /** Fetches every segment of [stream] and writes the joined [extension] file to [sink]. */
    suspend fun save(
        stream: DesktopStream,
        extension: String,
        sink: OutputStream,
        tempDir: File,
        userAgent: String,
        onProgress: (done: Long, total: Long) -> Unit,
    ) {
        fun fetch(target: String): ByteArray =
            DesktopDownloadHttp.client.newCall(DesktopDownloadHttp.request(target, userAgent, stream.headers))
                .execute()
                .use { response ->
                    check(response.isSuccessful) { "Segment failed (HTTP ${response.code})" }
                    response.body.bytes()
                }

        val base = URI(stream.url)
        val index = String(fetch(stream.url), Charsets.UTF_8)
        val (initRef, mediaRefs) = if (transportOf(stream) == DesktopAddonStream.DASH) {
            OfflineDash.parse(index).let { it.initialization to it.media }
        } else {
            ManifestFile.hlsSegments(index)
        }
        fun resolve(ref: String) = base.resolve(ref).toString()
        val total = mediaRefs.size + 1L

        val init = fetch(resolve(initRef))
        onProgress(1, total)
        val scope = currentCoroutineContext()
        val media = mediaRefs.asSequence().mapIndexed { i, ref ->
            scope.ensureActive()
            fetch(resolve(ref)).also { onProgress(i + 2L, total) }
        }
        assemble(tempDir, extension, init, media, sink)
        sink.flush()
    }

    internal fun assemble(tempDir: File, extension: String, init: ByteArray, media: Sequence<ByteArray>, sink: OutputStream) {
        when (extension) {
            "flac" -> ManifestFile.assemble(tempDir, "flac", init, media, sink)
            else -> {
                val joined = File.createTempFile("manifest-", ".m4a", tempDir)
                try {
                    joined.outputStream().buffered().use { out ->
                        ManifestFile.assemble(tempDir, "m4a", init, media, out)
                    }
                    val bytes = joined.readBytes()
                    sink.write(Mp4Sidx.ensure(bytes))
                } finally {
                    joined.delete()
                }
            }
        }
    }

    internal fun parseDash(manifest: String): OfflineDash.Plan = OfflineDash.parse(manifest)

    internal fun hlsSegments(playlist: String): Pair<String, List<String>> = ManifestFile.hlsSegments(playlist)

    internal object Mp4 {
        fun withSidx(bytes: ByteArray): ByteArray = Mp4Sidx.ensure(bytes)
    }
}
