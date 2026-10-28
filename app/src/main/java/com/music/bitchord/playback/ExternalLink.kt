package com.music.bitchord.playback

import android.net.Uri
import com.music.bitchord.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import org.jsoup.Jsoup

/**
 * A link to a song on another streaming service, turned into words that
 * BitChord's own catalogue can search for.
 *
 * Those services' ids mean nothing to YouTube Music, so the only thing the two
 * have in common is the title and the artist. Each service publishes both on
 * the page a shared link opens (Open Graph tags), which is read without any
 * account or API key.
 */
object ExternalLink {

    /** A shared page, reduced to what can be searched for. */
    data class Resolved(val query: String, val isTrack: Boolean)

    private val HOSTS = setOf(
        "open.spotify.com", "spotify.link", "spotify.app.link",
        "music.apple.com", "itunes.apple.com",
        "deezer.com", "deezer.page.link", "link.deezer.com",
        "soundcloud.com", "on.soundcloud.com", "m.soundcloud.com",
        "tidal.com", "listen.tidal.com",
        "music.amazon.com", "music.amazon.es",
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** Whether [uri] is a page [resolve] knows how to read. */
    fun supports(uri: Uri): Boolean = hostOf(uri) in HOSTS

    private fun hostOf(uri: Uri): String? =
        uri.host?.lowercase()?.removePrefix("www.")

    /** Null when the page can't be fetched or names nothing searchable. */
    suspend fun resolve(uri: Uri): Resolved? = withContext(Dispatchers.IO) {
        runCatching {
            val host = hostOf(uri) ?: return@runCatching null
            if (host == "deezer.com") deezerTrack(uri)?.let { return@runCatching it }
            val request = Request.Builder()
                .url(uri.toString())
                // Spotify serves its tags to anything that looks like a link
                // preview, and a bare HTTP-library agent gets a bare page.
                .header("User-Agent", "Mozilla/5.0 (compatible; Twitterbot/1.0)")
                .header("Accept-Language", "en")
                .build()
            val html = Http.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.string()
            } ?: return@runCatching null
            val doc = Jsoup.parse(html)
            fun meta(property: String) =
                doc.selectFirst("meta[property=$property]")?.attr("content")?.trim().orEmpty()
            val kind = uri.pathSegments.orEmpty()
                .firstOrNull { it in setOf("track", "song", "album", "playlist", "artist") }
            val path = uri.pathSegments.orEmpty()
            val isTrack = kind == "track" || kind == "song" ||
                (kind == "album" && uri.getQueryParameter("i") != null) ||
                // SoundCloud: user/track-name
                (host.endsWith("soundcloud.com") && path.size == 2 && path[1] != "sets")
            val query = when {
                host == "open.spotify.com" || host.startsWith("spotify.") ->
                    spotifyQuery(meta("og:title"), meta("og:description"))
                else -> cleanTitle(meta("og:title"))
            }
            query.takeIf { it.isNotBlank() }?.let { Resolved(it, isTrack) }
        }.getOrNull()
    }

    private fun deezerTrack(uri: Uri): Resolved? {
        val id = uri.pathSegments.orEmpty().let { segments ->
            val at = segments.indexOf("track")
            if (at < 0) null else segments.getOrNull(at + 1)
        }?.takeIf { it.all(Char::isDigit) } ?: return null
        val request = Request.Builder().url("https://api.deezer.com/track/$id").build()
        val body = Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.string()
        } ?: return null
        val track = json.parseToJsonElement(body) as? JsonObject ?: return null
        val title = track["title"]?.jsonPrimitive?.contentOrNull ?: return null
        val artist = track["artist"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
        return Resolved(listOfNotNull(title, artist).joinToString(" "), isTrack = true)
    }

    /**
     * Spotify writes the artist first in the description ("Artist · Song ·
     * 2020"), sometimes with the song again as the second part.
     */
    internal fun spotifyQuery(title: String, description: String): String {
        val artist = description.split('·', '•').map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.equals(title, ignoreCase = true) }
            ?.takeIf { !it.matches(Regex("""\d{4}""")) }
        return listOfNotNull(title.takeIf { it.isNotBlank() }, artist).joinToString(" ")
    }

    /**
     * "Blinding Lights - Song by The Weeknd - Apple Music" and
     * "Blinding Lights by The Weeknd | Free Listening on SoundCloud" both
     * become "Blinding Lights The Weeknd".
     */
    internal fun cleanTitle(raw: String): String {
        var text = raw.replace("‎", "").trim()
        text = text.replace(Regex("""\s*[-|]\s*(Apple Music|TIDAL|Deezer|Amazon Music).*$""", RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("""\s*\|\s*Free Listening on SoundCloud.*$""", RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("""\s+-\s+(Song|Single|EP|Album)\s+by\s+""", RegexOption.IGNORE_CASE), " ")
        text = text.replace(Regex("""\s+by\s+""", RegexOption.IGNORE_CASE), " ")
        return text.trim()
    }
}
