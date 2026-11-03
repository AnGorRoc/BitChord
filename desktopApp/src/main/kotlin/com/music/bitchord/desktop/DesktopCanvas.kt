package com.music.bitchord.desktop

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * A looping video that stands in for a track's cover art — what Spotify calls a Canvas and Apple
 * calls motion artwork.
 *
 * [url] is what the player mounts; [fallbackUrl] is tried once if that errors, which is how the
 * Apple provider hands over a second rendition of the same clip. The metadata is not decoration:
 * providers search by free text and will happily return the wrong album's clip, so [matches]
 * re-checks the answer against what is playing.
 */
data class DesktopCanvasArtwork(
    val url: String,
    val fallbackUrl: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val source: DesktopCanvasSource = DesktopCanvasSource.OTHER,
) {
    /**
     * Whether this clip really belongs to the track we asked about.
     *
     * Title and artists must match exactly once punctuation, case and accents are stripped — a near
     * miss is a different song by the same artist, which is the failure people notice. The album is
     * only held to that standard when both sides know it, since a track's album resolves after the
     * player opens and one queued from search may never get one.
     */
    fun matches(wantTitle: String, wantArtist: String, wantAlbum: String?): Boolean {
        val titleOk = title == null || wantTitle.isBlank() ||
            title.normalizeForCanvasMatch() == wantTitle.normalizeForCanvasMatch()
        val wanted = splitCanvasArtists(wantArtist)
        val ours = splitCanvasArtists(artist.orEmpty())
        val artistOk = artist == null || wantArtist.isBlank() ||
            (wanted.isNotEmpty() && ours.isNotEmpty() && wanted.all { want -> ours.any { it == want } })
        val albumOk = album.isNullOrBlank() || wantAlbum.isNullOrBlank() ||
            album.normalizeForCanvasMatch() == wantAlbum.normalizeForCanvasMatch()
        return titleOk && artistOk && albumOk
    }
}

/**
 * The four providers, asked in turn until one answers with a clip that is really this track's.
 *
 * Apple and Tidal first because their clips are square and belong to the release; the community
 * index next because it is the only one covering back catalogue; Spotify last because it is the
 * one that needs a credential.
 */
object DesktopCanvasClient {

    private const val CACHE_SIZE = 64

    /**
     * A settled answer for one track or release.
     *
     * [withAlbum] records whether the album name was known when this was worked out. It is the one
     * thing that can turn a miss into a hit later: the album is what makes the catalogue searches
     * land, and on the player it resolves a beat after the track starts.
     */
    private class Entry(val artwork: DesktopCanvasArtwork?, val withAlbum: Boolean) {
        /** A hit is a hit — the album could only have confirmed it. A miss stands too, unless it
         * was reached blind and there is now an album name to try. */
        fun reusable(nowWithAlbum: Boolean): Boolean =
            artwork != null || withAlbum || !nowWithAlbum
    }

    private val cache = object : LinkedHashMap<String, Entry>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>) = size > CACHE_SIZE
    }

    // Skipping through a queue fires a lookup per track. Serialising them keeps four providers'
    // worth of requests off the wire at once.
    private val gate = Mutex()

    /**
     * The canvas for [song], or null when there is not one. Never throws.
     *
     * Only a local file with no catalogue identity is answered as a miss without a request. A
     * download carries both a videoId and a local path, and skipping on the path alone is what
     * left downloaded tracks with no canvas.
     */
    suspend fun lookup(song: Song): DesktopCanvasArtwork? {
        if (song.videoId.isBlank() && (song.localUri != null || song.localPath != null)) return null
        val title = song.title.cleanedForCanvas()
        val artist = song.artist.cleanedForCanvas()
        if (title.isBlank() || artist.isBlank()) return null
        val album = song.albumName
        // Keyed on the track alone: the album arrives after the player opens, and keying on it made
        // the late arrival look like a different question.
        return resolve("song|${song.videoId}", album != null) {
            firstHit(
                { DesktopAppleMusicCanvas.search(title, artist, album) },
                { DesktopTidalCanvas.search(title, artist, album) },
                { DesktopCommunityCanvas.search(title, artist, album) },
                { DesktopSpotifyCanvas.search(title, artist, album) },
            ) { it.matches(title, artist, album) }
        }
    }

    /** A canvas already worked out for [song], without going near the network. */
    fun cached(song: Song): DesktopCanvasArtwork? =
        synchronized(cache) { cache["song|${song.videoId}"]?.artwork }

    /**
     * The canvas for a release, for an album page's header.
     *
     * A separate lookup rather than the first track's: the services hang motion artwork off the
     * album, so asking directly is both fewer requests and a better match.
     */
    suspend fun lookupAlbum(album: String, artist: String): DesktopCanvasArtwork? {
        val name = album.cleanedForCanvas()
        val credit = artist.cleanedForCanvas()
        if (name.isBlank() || credit.isBlank()) return null
        return resolve("album|$name|$credit", withAlbum = true) {
            firstHit(
                { DesktopAppleMusicCanvas.searchAlbum(name, credit) },
                { DesktopTidalCanvas.searchAlbum(name, credit) },
                { DesktopCommunityCanvas.searchAlbum(name, credit) },
                { DesktopSpotifyCanvas.searchAlbum(name, credit) },
            ) { it.matches(name, credit, name) }
        }
    }

    private suspend fun resolve(
        key: String,
        withAlbum: Boolean,
        lookUp: suspend () -> DesktopCanvasArtwork?,
    ): DesktopCanvasArtwork? = gate.withLock {
        synchronized(cache) {
            cache[key]?.let { if (it.reusable(withAlbum)) return@withLock it.artwork }
        }
        val found = withContext(Dispatchers.IO) { lookUp() }
        synchronized(cache) { cache[key] = Entry(found, withAlbum) }
        found
    }

    /**
     * The first source answering with something that survives [accept].
     *
     * Sources are passed unevaluated so each is only reached if the ones before came up empty. One
     * that throws is treated as one that found nothing: none of these hosts are ours.
     */
    private suspend fun firstHit(
        vararg sources: suspend () -> DesktopCanvasArtwork?,
        accept: (DesktopCanvasArtwork) -> Boolean,
    ): DesktopCanvasArtwork? {
        for (source in sources) {
            val found = runCatching { source() }.getOrNull() ?: continue
            if (!accept(found)) continue
            return found
        }
        return null
    }
}

/**
 * YouTube Music titles carry packaging the catalogue services never see. Searching with it finds
 * nothing and matching against it rejects everything, so it comes off before either.
 */
internal fun String.cleanedForCanvas(): String = replace(CANVAS_NOISE, " ")
    .substringBefore(" | ")
    .replace(Regex("\\s+"), " ")
    .trim()
    .ifBlank { this }

private val CANVAS_NOISE = Regex(
    """\((?:from|official|lyrical|video|audio)[^)]*\)|\[[^]]*]|""" +
        """\b(?:official (?:video|audio|music video)|lyrical|full song|4k video)\b""",
    RegexOption.IGNORE_CASE,
)

/** Whether this URL points at a playlist rather than at the media itself. */
internal fun isManifest(url: String): Boolean {
    val path = url.substringBefore('?').substringBefore('#').lowercase()
    return path.endsWith(".m3u8") || path.endsWith(".mpd")
}

/** The clip itself, on disk. */
internal object DesktopCanvasCache {

    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    fun fileFor(url: String): java.nio.file.Path? {
        DesktopMediaCache.existing(url, "mp4")?.let { return it }
        return runCatching {
            val target = DesktopMediaCache.pathFor(url, "mp4")
            val mediaUrl = if (isManifest(url)) resolveManifestToMp4(url) ?: url else url
            if (isManifest(mediaUrl)) return null // Cannot download raw manifest file as MP4

            val response = client.send(
                HttpRequest.newBuilder(URI.create(mediaUrl))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", DESKTOP_CANVAS_UA)
                    .header("Accept", "*/*")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofFile(target),
            )
            check(response.statusCode() in 200..299) { "canvas HTTP ${response.statusCode()}" }
            check(java.nio.file.Files.size(target) > 0) { "canvas came back empty" }
            // The only moment the cache can be over its limit is just after a write.
            DesktopMediaCache.trim()
            target
        }.onFailure { DesktopTrackLog.log("canvas: could not fetch the clip — ${it.message}") }.getOrNull()
    }

    /** Resolves an HLS manifest to a direct MP4 URL if available (e.g. Apple Music motion artwork). */
    private fun resolveManifestToMp4(m3u8Url: String): String? = runCatching {
        val request = HttpRequest.newBuilder(URI.create(m3u8Url))
            .timeout(Duration.ofSeconds(10))
            .header("User-Agent", DESKTOP_CANVAS_UA)
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) return null
        val text = response.body()

        if (text.contains("#EXT-X-STREAM-INF:")) {
            val variants = mutableListOf<Pair<Int, String>>()
            val lines = text.lines()
            for (i in lines.indices) {
                val line = lines[i]
                if (line.startsWith("#EXT-X-STREAM-INF:")) {
                    val resMatch = Regex("""RESOLUTION=(\d+)x(\d+)""").find(line)
                    val w = resMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    val nextLine = lines.getOrNull(i + 1)?.trim().orEmpty()
                    if (nextLine.isNotEmpty() && !nextLine.startsWith("#")) {
                        val fullUri = URI.create(m3u8Url).resolve(nextLine).toString()
                        variants.add(w to fullUri)
                    }
                }
            }
            if (variants.isNotEmpty()) {
                variants.sortBy { kotlin.math.abs(it.first - 540) }
                return resolveManifestToMp4(variants.first().second)
            }
        }

        val mapMatch = Regex("""#EXT-X-MAP:URI="([^"]+)"""").find(text)
        if (mapMatch != null) {
            val mp4Rel = mapMatch.groupValues[1]
            return URI.create(m3u8Url).resolve(mp4Rel).toString()
        }

        null
    }.getOrNull()
}

/** The motion artwork, drawn as frames rather than played by a native child. */
@Composable
fun DesktopCanvasView(
    url: String,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = true,
    contentScale: ContentScale = ContentScale.Crop,
    /** Tried once if [url] will not decode — the Apple provider's second rendition of the clip. */
    fallbackUrl: String? = null,
) {
    var frame by remember(url) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(url, fallbackUrl, isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        val decoder = DesktopCanvasDecoder()
        val opened = withContext(Dispatchers.IO) {
            var last: Result<Unit> = Result.failure(IllegalStateException("the clip could not be fetched"))
            for (candidate in listOfNotNull(url, fallbackUrl)) {
                val source = DesktopCanvasCache.fileFor(candidate)?.toAbsolutePath()?.toString()
                    ?: if (isManifest(candidate)) candidate else null
                    ?: continue
                last = decoder.open(source)
                if (last.isSuccess) break
            }
            last
        }
        if (opened.isFailure) {
            DesktopTrackLog.log("canvas: ${opened.exceptionOrNull()?.message}")
            withContext(Dispatchers.IO) { decoder.close() }
            return@LaunchedEffect
        }

        val cachedFrames = ArrayList<ImageBitmap>()
        var allFramesLoaded = false
        var frameIndex = 0
        var decoderClosed = false
        val frameIntervalNanos = decoder.frameIntervalMillis * 1_000_000L
        var nextFrameTimeNanos = System.nanoTime()

        try {
            val pixels = ByteArray(decoder.width * decoder.height * 4)
            val info = ImageInfo.makeN32(decoder.width, decoder.height, ColorAlphaType.OPAQUE)
            while (isActive) {
                val currentBmp: ImageBitmap? = if (allFramesLoaded) {
                    if (cachedFrames.isEmpty()) break
                    val bmp = cachedFrames[frameIndex]
                    frameIndex = (frameIndex + 1) % cachedFrames.size
                    bmp
                } else {
                    val decoded = withContext(Dispatchers.IO) { decoder.nextFrame(pixels) }
                    if (decoded) {
                        val bmp = Image.makeRaster(info, pixels.copyOf(), decoder.width * 4).toComposeImageBitmap()
                        cachedFrames.add(bmp)
                        if (cachedFrames.size == 1) {
                            DesktopCanvasBackdrop.publish(url, bmp)
                        }
                        frameIndex++
                        if (cachedFrames.size >= 300) {
                            allFramesLoaded = true
                            frameIndex = 0
                            withContext(NonCancellable + Dispatchers.IO) {
                                decoder.close()
                                decoderClosed = true
                            }
                        }
                        bmp
                    } else {
                        withContext(NonCancellable + Dispatchers.IO) {
                            decoder.close()
                            decoderClosed = true
                        }
                        if (cachedFrames.isEmpty()) break
                        allFramesLoaded = true
                        frameIndex = 0
                        val bmp = cachedFrames[0]
                        frameIndex = (frameIndex + 1) % cachedFrames.size
                        bmp
                    }
                }

                frame = currentBmp

                nextFrameTimeNanos += frameIntervalNanos
                val nowNanos = System.nanoTime()
                val sleepNanos = nextFrameTimeNanos - nowNanos

                if (sleepNanos > 0L) {
                    val sleepMs = sleepNanos / 1_000_000L
                    if (sleepMs > 2L) {
                        delay(sleepMs - 1L)
                    }
                    while (System.nanoTime() < nextFrameTimeNanos) {
                        Thread.yield()
                    }
                } else if (nowNanos - nextFrameTimeNanos > frameIntervalNanos * 3L) {
                    nextFrameTimeNanos = nowNanos
                }
            }
        } finally {
            if (!decoderClosed) {
                withContext(NonCancellable + Dispatchers.IO) { decoder.close() }
            }
        }
    }

    DisposableEffect(url) {
        onDispose { DesktopCanvasBackdrop.clear(url) }
    }

    // Nothing at all until the first frame: a placeholder here would flash between the still cover
    // and the clip on every track change.
    frame?.let {
        Image(
            bitmap = it,
            contentDescription = null,
            contentScale = contentScale,
            modifier = modifier,
        )
    }
}

private const val DESKTOP_CANVAS_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"
