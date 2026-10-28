package com.music.bitchord.playback

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.withTimeoutOrNull

/** What opening a song shared from another service came to. */
sealed interface SharedLinkOutcome {
    /** The song, found in BitChord's own catalogue: play it. */
    data class Play(val song: Song) : SharedLinkOutcome

    /** The page named something, but not a song that could be found: show the search for it. */
    data class Search(val query: String) : SharedLinkOutcome

    /** Nothing could be made of the link, or it took too long. */
    data object Unresolved : SharedLinkOutcome
}

/**
 * Turns a link shared from another streaming service into something to play:
 * read what the page names ([ExternalLink]), then look that up the way a
 * spoken "play <title> <artist>" is.
 *
 * Bounded as a whole by [timeoutMs], so a service that does not answer ends as
 * [SharedLinkOutcome.Unresolved] for the person waiting rather than as an
 * open-ended wait. The two lookups are parameters so the decision can be
 * tested without a network.
 */
class SharedLinkResolver(
    private val read: suspend (String) -> ExternalLink.Resolved? = ExternalLink::resolve,
    private val findSong: suspend (String) -> Song? = ::findInCatalogue,
    private val timeoutMs: Long = TIMEOUT_MS,
) {
    suspend fun open(link: String): SharedLinkOutcome =
        withTimeoutOrNull(timeoutMs) {
            val page = read(link) ?: return@withTimeoutOrNull SharedLinkOutcome.Unresolved
            val song = page.takeIf { it.isTrack }?.let { findSong(it.query) }
            if (song != null) SharedLinkOutcome.Play(song) else SharedLinkOutcome.Search(page.query)
        } ?: SharedLinkOutcome.Unresolved

    companion object {
        /** Long enough for a slow connection to read a page and search; short enough to say no. */
        const val TIMEOUT_MS = 12_000L

        val default = SharedLinkResolver()

        private suspend fun findInCatalogue(query: String): Song? =
            YtMusicRepository.search(query, SearchFilter.SONGS).getOrNull()
                ?.filterIsInstance<SearchResult.Track>()?.firstOrNull()?.song
    }
}
