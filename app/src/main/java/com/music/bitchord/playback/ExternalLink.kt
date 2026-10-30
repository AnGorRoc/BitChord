package com.music.bitchord.playback

import android.net.Uri
import com.music.bitchord.BuildConfig
import com.music.bitchord.data.Http
import com.music.bitchord.data.TrackLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * A link to a song on another streaming service, turned into words that
 * BitChord's own catalogue can search for.
 *
 * Those services' ids mean nothing to YouTube Music, so the only thing the two
 * have in common is the title and the artist. Each service publishes both on
 * the page a shared link opens (Open Graph tags), which is read without any
 * account or API key, as a link preview would be.
 *
 * A link can come from anyone, so what it is allowed to make this app fetch is
 * narrow: https, no credentials in the address, one of [HOSTS] - judged on the
 * same parsed address that is then requested, never on a second reading of the
 * text - and redirects followed by hand, one at a time, each held to the same
 * rules. See [PageFetcher].
 */
object ExternalLink {

    /** A shared page, reduced to what can be searched for. */
    data class Resolved(val query: String, val isTrack: Boolean)

    /**
     * Every host a shared link may name or be redirected to. A link shortener
     * is here because its answer is a redirect to a page that is also here.
     */
    internal val HOSTS = setOf(
        "open.spotify.com", "spotify.link", "spotify.app.link",
        "music.apple.com", "itunes.apple.com",
        "deezer.com", "deezer.page.link", "link.deezer.com",
        "soundcloud.com", "on.soundcloud.com", "m.soundcloud.com",
        "tidal.com", "listen.tidal.com",
        "music.amazon.com", "music.amazon.es",
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** The kinds of page whose path names what they hold. */
    private val KINDS = setOf("track", "song", "album", "playlist", "artist")

    /**
     * [raw] as an address this may fetch, or null when it is not one: not
     * https, carrying a user name or password, or not on one of [HOSTS].
     * Parsed once, by the parser that will make the request.
     */
    internal fun allowedUrl(raw: String): HttpUrl? {
        val url = raw.toHttpUrlOrNull() ?: return null
        return url.takeIf { isAllowed(it) }
    }

    internal fun isAllowed(url: HttpUrl): Boolean =
        url.isHttps && url.username.isEmpty() && url.password.isEmpty() &&
            url.host.lowercase().removePrefix("www.") in HOSTS

    /** Whether [uri] is a page [resolve] knows how to read. */
    fun supports(uri: Uri): Boolean = allowedUrl(uri.toString()) != null

    private val fetcher by lazy {
        PageFetcher(
            client = Http.client.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .callTimeout(CALL_TIMEOUT_S, TimeUnit.SECONDS)
                .build(),
            allows = ::isAllowed,
        )
    }

    /** Null when the page can't be fetched or names nothing searchable. */
    suspend fun resolve(link: String): Resolved? = withContext(Dispatchers.IO) {
        val start = allowedUrl(link) ?: run {
            TrackLog.w(TAG, "shared link refused: not an https page of a supported service")
            return@withContext null
        }
        try {
            val page = fetcher.fetch(start) ?: return@withContext null
            if (page.url.host.lowercase().removePrefix("www.") == "deezer.com") {
                deezerTrack(page.url)?.let { return@withContext it }
            }
            resolved(page.url, page.html)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TrackLog.w(TAG, "shared link ${start.host} not read: ${e.message}")
            null
        }
    }

    /** What [html], the page at [url], says it holds; separate from the fetching so it can be read without a network. */
    internal fun resolved(url: HttpUrl, html: String): Resolved? {
        val host = url.host.lowercase().removePrefix("www.")
        val doc = Jsoup.parse(html)
        fun meta(property: String) =
            doc.selectFirst("meta[property=$property]")?.attr("content")?.trim().orEmpty()
        val path = url.pathSegments
        val kind = path.firstOrNull { it in KINDS }
        val isTrack = kind == "track" || kind == "song" ||
            (kind == "album" && url.queryParameter("i") != null) ||
            // SoundCloud: user/track-name
            (host.endsWith("soundcloud.com") && path.size == 2 && path[1] != "sets")
        val query = when {
            host == "open.spotify.com" || host.startsWith("spotify.") ->
                spotifyQuery(meta("og:title"), meta("og:description"))
            else -> cleanTitle(meta("og:title"))
        }
        return query.takeIf { it.isNotBlank() }?.let { Resolved(it, isTrack) }
    }

    private fun deezerTrack(url: HttpUrl): Resolved? {
        val id = url.pathSegments.let { segments ->
            val at = segments.indexOf("track")
            if (at < 0) null else segments.getOrNull(at + 1)
        }?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) } ?: return null
        val api = "https://api.deezer.com/track/$id".toHttpUrlOrNull() ?: return null
        val body = deezerFetcher.fetch(api)?.html ?: return null
        val track = json.parseToJsonElement(body) as? JsonObject ?: return null
        val title = track["title"]?.jsonPrimitive?.contentOrNull ?: return null
        val artist = track["artist"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
        return Resolved(listOfNotNull(title, artist).joinToString(" "), isTrack = true)
    }

    /** Deezer's own API, which answers in place and is asked for nothing else. */
    private val deezerFetcher by lazy {
        PageFetcher(
            client = Http.client.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .callTimeout(CALL_TIMEOUT_S, TimeUnit.SECONDS)
                .build(),
            allows = { it.isHttps && it.host == "api.deezer.com" },
            maxRedirects = 0,
        )
    }

    /**
     * Spotify writes the artist first in the description ("Artist · Song ·
     * 2020"), sometimes with the song again as the second part.
     */
    internal fun spotifyQuery(title: String, description: String): String {
        val artist = description.split('·', '•').map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.equals(title, ignoreCase = true) }
            ?.takeIf { !it.matches(YEAR) }
        return listOfNotNull(title.takeIf { it.isNotBlank() }, artist).joinToString(" ")
    }

    /**
     * "Blinding Lights - Song by The Weeknd - Apple Music" and
     * "Blinding Lights by The Weeknd | Free Listening on SoundCloud" both
     * become "Blinding Lights The Weeknd".
     *
     * A title can hold " by " of its own ("Stand By Me"), so the word that
     * separates title from artist is only looked for where the page's wording
     * says there is one, and it is the last such word.
     */
    internal fun cleanTitle(raw: String): String {
        var text = raw.replace("‎", "").trim()
        var decorated = false
        text = text.replace(SERVICE_SUFFIX) { decorated = true; "" }
        text = text.replace(SOUNDCLOUD_SUFFIX) { decorated = true; "" }
        if (KIND_BY.containsMatchIn(text)) return text.replace(KIND_BY, " ").trim()
        if (decorated) text = text.replace(LAST_BY) { "${it.groupValues[1]} ${it.groupValues[2]}" }
        return text.trim()
    }

    private val YEAR = Regex("""\d{4}""")
    private val SERVICE_SUFFIX = Regex("""\s*[-|]\s*(Apple Music|TIDAL|Deezer|Amazon Music).*$""", RegexOption.IGNORE_CASE)
    private val SOUNDCLOUD_SUFFIX = Regex("""\s*\|\s*Free Listening on SoundCloud.*$""", RegexOption.IGNORE_CASE)
    private val KIND_BY = Regex("""\s+-\s+(Song|Single|EP|Album)\s+by\s+""", RegexOption.IGNORE_CASE)
    private val LAST_BY = Regex("""^(.*)\s+by\s+(.+)$""", RegexOption.IGNORE_CASE)

    private const val TAG = "BitChord"
    private const val CALL_TIMEOUT_S = 8L
}

/** A page fetched, and the address it was finally read from. */
internal class FetchedPage(val url: HttpUrl, val html: String)

/**
 * Fetches a page the way a link preview would, and only as far as the rules
 * allow.
 *
 * Redirects are not left to the HTTP client, which would follow any of them to
 * anywhere: each one is read here, checked with [allows] before it is asked
 * for, and there are at most [maxRedirects]. The body is read only as far as
 * [maxBytes] - the tags that matter are at the top of a page - so a page
 * that never ends costs a fixed amount of memory. The client is given in so
 * that it can be told not to follow redirects itself.
 *
 * The agent says what it is. The pages that matter serve their tags to it.
 */
internal class PageFetcher(
    private val client: OkHttpClient,
    private val allows: (HttpUrl) -> Boolean,
    private val maxRedirects: Int = MAX_REDIRECTS,
    private val maxBytes: Long = MAX_BYTES,
) {
    /** Null when the page is not there, is not allowed, or redirects too far. */
    fun fetch(start: HttpUrl): FetchedPage? {
        var url = start
        repeat(maxRedirects + 1) {
            if (!allows(url)) {
                TrackLog.w(TAG, "link preview refused ${url.host}: not an allowed address")
                return null
            }
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept-Language", "en")
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isRedirect) {
                    val next = response.header("Location")?.let { url.resolve(it) } ?: return null
                    url = next
                    return@use
                }
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val source = response.body.source()
                source.request(maxBytes)
                val html = source.buffer.readUtf8(minOf(source.buffer.size, maxBytes))
                return FetchedPage(url, html)
            }
        }
        TrackLog.w(TAG, "link preview gave up on ${start.host}: more than $maxRedirects redirects")
        return null
    }

    companion object {
        const val MAX_REDIRECTS = 4

        /** Enough for the head of any page that carries Open Graph tags. */
        const val MAX_BYTES = 512L * 1024

        private const val TAG = "BitChord"
        private val USER_AGENT = "BitChord/${BuildConfig.VERSION_NAME} (link preview)"
    }
}
