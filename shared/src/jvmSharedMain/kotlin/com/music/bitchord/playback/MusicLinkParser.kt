package com.music.bitchord.playback

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** What an incoming URL or text snippet turned out to be asking for. */
sealed interface ParsedMusicLink {
    /** A song, by video id — `watch?v=`, a `youtu.be` short link, a Short. */
    data class Track(val videoId: String) : ParsedMusicLink

    /** An album, playlist or artist page, by browse id. */
    data class Page(val browseId: String) : ParsedMusicLink

    /** A search URL (`music.youtube.com/search?q=...`). */
    data class Search(val query: String) : ParsedMusicLink

    /** A song or page on another service (Spotify, Apple Music, Tidal, Deezer, SoundCloud, Amazon Music). */
    data class External(val url: String) : ParsedMusicLink
}

/**
 * Parses YouTube / YouTube Music and external streaming service links from raw text or URLs.
 */
object MusicLinkParser {

    /**
     * Extracts and classifies the first supported music URL in [text], or null if none is found.
     */
    fun parseText(text: String): ParsedMusicLink? {
        val rawUrl = firstUrl(text.trim()) ?: text.trim()
        return parseUrl(rawUrl)
    }

    /**
     * Classifies [rawUrl] as a YouTube/YouTube Music link or a supported external streaming link.
     */
    fun parseUrl(rawUrl: String): ParsedMusicLink? {
        parseYouTube(rawUrl)?.let { return it }
        if (ExternalLink.supports(rawUrl)) return ParsedMusicLink.External(rawUrl)
        return null
    }

    /**
     * What a YouTube or YouTube Music URL points at, or null for one this app
     * has nothing to show for.
     */
    fun parseYouTube(rawUrl: String): ParsedMusicLink? {
        val url = rawUrl.toHttpUrlOrNull() ?: return null
        val host = url.host.lowercase().removePrefix("www.")
        val segments = url.pathSegments.filter { it.isNotEmpty() }
        if (host == "youtu.be") {
            return segments.firstOrNull()?.let(::track)
        }
        if (host != "youtube.com" && host != "music.youtube.com" && host != "m.youtube.com") {
            return null
        }
        val list = url.queryParameter("list")?.trim().orEmpty()
        return when (segments.firstOrNull()) {
            "watch" -> url.queryParameter("v")?.let(::track) ?: playlist(list)
            "playlist" -> playlist(list)
            "shorts", "embed", "v" -> segments.getOrNull(1)?.let(::track)
            "channel", "browse" -> segments.getOrNull(1)?.takeIf { it.isNotBlank() }
                ?.let(ParsedMusicLink::Page)
            "search" -> url.queryParameter("q")?.trim()?.takeIf { it.isNotEmpty() }
                ?.let(ParsedMusicLink::Search)
            else -> list.takeIf { it.isNotEmpty() }?.let { playlist(it) }
        }
    }

    private fun track(videoId: String): ParsedMusicLink.Track? =
        videoId.trim().takeIf { it.isNotEmpty() }?.let(ParsedMusicLink::Track)

    private fun playlist(listId: String): ParsedMusicLink.Page? {
        if (listId.isEmpty()) return null
        return ParsedMusicLink.Page(if (listId.startsWith("VL")) listId else "VL$listId")
    }

    fun firstUrl(text: String): String? =
        URL_IN_TEXT.find(text)?.value

    private val URL_IN_TEXT = Regex("""https?://\S+""")
}
