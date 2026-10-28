package com.music.bitchord.playback

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class ExternalLinkTest {

    // ── what is read from a page's words ──────────────────────────────────

    @Test
    fun spotifyPutsArtistFirstInDescription() {
        assertEquals(
            "Blinding Lights The Weeknd",
            ExternalLink.spotifyQuery("Blinding Lights", "The Weeknd · Blinding Lights · 2020"),
        )
    }

    @Test
    fun spotifyWithoutArtistKeepsTitle() {
        assertEquals("Blinding Lights", ExternalLink.spotifyQuery("Blinding Lights", ""))
    }

    @Test
    fun appleMusicTitleLosesItsDecoration() {
        assertEquals(
            "Blinding Lights The Weeknd",
            ExternalLink.cleanTitle("‎Blinding Lights - Song by The Weeknd - Apple Music"),
        )
    }

    @Test
    fun soundCloudTitleLosesItsDecoration() {
        assertEquals(
            "Song Artist",
            ExternalLink.cleanTitle("Song by Artist | Free Listening on SoundCloud"),
        )
    }

    @Test
    fun aTitleThatHoldsByOfItsOwnKeepsIt() {
        assertEquals(
            "Stand By Me Ben E. King",
            ExternalLink.cleanTitle("Stand By Me by Ben E. King | Free Listening on SoundCloud"),
        )
        assertEquals(
            "Stand By Me Ben E. King",
            ExternalLink.cleanTitle("Stand By Me - Song by Ben E. King - Apple Music"),
        )
    }

    @Test
    fun aPlainTitleWithByIsLeftAlone() {
        assertEquals("Stand By Me", ExternalLink.cleanTitle("Stand By Me"))
    }

    @Test
    fun aTrackPageIsReadFromItsTags() {
        val html = """
            <html><head>
            <meta property="og:title" content="Blinding Lights"/>
            <meta property="og:description" content="The Weeknd · Song · 2020"/>
            </head></html>
        """.trimIndent()

        val resolved = ExternalLink.resolved("https://open.spotify.com/track/abc".toHttpUrl(), html)

        assertEquals(ExternalLink.Resolved("Blinding Lights The Weeknd", isTrack = true), resolved)
    }

    @Test
    fun anAlbumPageIsNotATrack() {
        val html = """<meta property="og:title" content="After Hours - Album by The Weeknd - Apple Music"/>"""

        val resolved = ExternalLink.resolved("https://music.apple.com/us/album/after-hours/1".toHttpUrl(), html)

        assertEquals("After Hours The Weeknd", resolved?.query)
        assertFalse(resolved!!.isTrack)
    }

    @Test
    fun anAlbumPageNamingOneSongIsATrack() {
        val html = """<meta property="og:title" content="Song - Single by X - Apple Music"/>"""

        val resolved = ExternalLink.resolved("https://music.apple.com/us/album/a/1?i=2".toHttpUrl(), html)

        assertTrue(resolved!!.isTrack)
    }

    @Test
    fun aPageWithNoTagsNamesNothing() {
        assertNull(ExternalLink.resolved("https://open.spotify.com/track/abc".toHttpUrl(), "<html></html>"))
    }

    // ── what a link may make this app fetch ───────────────────────────────

    @Test
    fun aSupportedServicePageIsAllowed() {
        assertNotNull(ExternalLink.allowedUrl("https://open.spotify.com/track/abc"))
        assertNotNull(ExternalLink.allowedUrl("https://www.deezer.com/track/3135556"))
        assertNotNull(ExternalLink.allowedUrl("https://spotify.link/xyz"))
    }

    @Test
    fun plainHttpIsRefused() {
        assertNull(ExternalLink.allowedUrl("http://open.spotify.com/track/abc"))
    }

    @Test
    fun aHostThatOnlyLooksLikeAServiceIsRefused() {
        assertNull(ExternalLink.allowedUrl("https://open.spotify.com.evil.example/track/abc"))
        assertNull(ExternalLink.allowedUrl("https://evilspotify.link/xyz"))
        assertNull(ExternalLink.allowedUrl("https://evil.example/open.spotify.com"))
    }

    /**
     * Two readings of one address used to disagree: Android's took the host to
     * be the service, the HTTP client's took it to be evil.example, and the
     * client is the one that made the request.
     */
    @Test
    fun anAddressTheTwoParsersReadDifferentlyIsJudgedOnTheOneThatFetches() {
        assertNull(ExternalLink.allowedUrl("https://evil.example\\@open.spotify.com/track/abc"))
        assertNull(ExternalLink.allowedUrl("https://open.spotify.com@evil.example/track/abc"))
    }

    @Test
    fun credentialsInAnAddressAreRefusedEvenForAService() {
        assertNull(ExternalLink.allowedUrl("https://user:pass@open.spotify.com/track/abc"))
        assertNull(ExternalLink.allowedUrl("https://user@open.spotify.com/track/abc"))
    }

    @Test
    fun textThatIsNotAnAddressIsRefused() {
        assertNull(ExternalLink.allowedUrl("not a url"))
        assertNull(ExternalLink.allowedUrl(""))
    }

    /**
     * Every host the manifest hands a tapped link to has to be one the reader
     * accepts, or a tap would open BitChord on a link it then refuses.
     */
    @Test
    fun everyHostTheManifestRoutesToTheAppIsOneTheReaderAccepts() {
        val manifest = File("src/main/AndroidManifest.xml")
        assertTrue("manifest not found from ${File(".").absolutePath}", manifest.exists())
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
            .newDocumentBuilder().parse(manifest)
        val filters = doc.getElementsByTagName("intent-filter")
        val hosts = mutableSetOf<String>()
        for (i in 0 until filters.length) {
            val filter = filters.item(i) as org.w3c.dom.Element
            val data = filter.getElementsByTagName("data")
            val schemes = (0 until data.length).mapNotNull { (data.item(it) as org.w3c.dom.Element).getAttribute("android:scheme").takeIf(String::isNotEmpty) }
            val hostNames = (0 until data.length).mapNotNull { (data.item(it) as org.w3c.dom.Element).getAttribute("android:host").takeIf(String::isNotEmpty) }
            // Only the filter that is about songs from other services.
            if (hostNames.any { it == "open.spotify.com" }) hosts += hostNames
            assertTrue(schemes.isNotEmpty() || hostNames.isEmpty())
        }
        assertTrue(hosts.isNotEmpty())
        hosts.forEach { host ->
            assertNotNull("$host is routed to the app but refused by the reader", ExternalLink.allowedUrl("https://$host/x"))
        }
    }

    /** The app is routed a tap for exactly the hosts the reader accepts: no more, and no fewer. */
    @Test
    fun theManifestAndTheReaderNameTheSameHosts() {
        val doc = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val filters = doc.getElementsByTagName("intent-filter")
        val routed = mutableSetOf<String>()
        for (i in 0 until filters.length) {
            val filter = filters.item(i) as org.w3c.dom.Element
            val data = filter.getElementsByTagName("data")
            val hostNames = (0 until data.length).mapNotNull { (data.item(it) as org.w3c.dom.Element).getAttribute("android:host").takeIf(String::isNotEmpty) }
            if (hostNames.any { it == "open.spotify.com" }) routed += hostNames
        }
        assertEquals(
            ExternalLink.HOSTS,
            routed.map { it.removePrefix("www.") }.toSet(),
        )
    }

    // ── fetching, held to those rules ─────────────────────────────────────

    private lateinit var server: MockWebServer
    private lateinit var other: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        other = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
        runCatching { other.shutdown() }
    }

    private fun fetcher(maxRedirects: Int = PageFetcher.MAX_REDIRECTS, maxBytes: Long = PageFetcher.MAX_BYTES) = PageFetcher(
        client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build(),
        // This server only: the one a redirect may not leave.
        allows = { it.host == server.hostName && it.port == server.port },
        maxRedirects = maxRedirects,
        maxBytes = maxBytes,
    )

    @Test
    fun aPageIsReadWithTheAppsOwnAgent() {
        server.enqueue(MockResponse().setBody("<html>hello</html>"))

        val page = fetcher().fetch(server.url("/track/1"))

        assertEquals("<html>hello</html>", page?.html)
        assertTrue(server.takeRequest().getHeader("User-Agent")!!.startsWith("BitChord/"))
    }

    @Test
    fun aRedirectWithinTheAllowedHostsIsFollowed() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/real"))
        server.enqueue(MockResponse().setBody("landed"))

        val page = fetcher().fetch(server.url("/short"))

        assertEquals("landed", page?.html)
        assertEquals("/real", page?.url?.encodedPath)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun aRedirectToAnotherHostIsNotFollowed() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/inside").toString()))
        other.enqueue(MockResponse().setBody("secret"))

        val page = fetcher().fetch(server.url("/short"))

        assertNull(page)
        assertEquals("the other host was asked", 0, other.requestCount)
    }

    @Test
    fun aRedirectLoopGivesUp() {
        repeat(10) { server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/again")) }

        assertNull(fetcher(maxRedirects = 3).fetch(server.url("/loop")))
        assertEquals(4, server.requestCount)
    }

    @Test
    fun anErrorPageIsAFailureNotContent() {
        server.enqueue(MockResponse().setResponseCode(503).setBody("busy"))

        val failure = runCatching { fetcher().fetch(server.url("/x")) }.exceptionOrNull()

        assertTrue(failure is java.io.IOException)
    }

    @Test
    fun aPageThatNeverEndsIsReadOnlyAsFarAsTheLimit() {
        server.enqueue(MockResponse().setBody("x".repeat(100_000)))

        val page = fetcher(maxBytes = 1_000).fetch(server.url("/long"))

        assertEquals(1_000, page?.html?.length)
    }

    // ── from a link to something to play ──────────────────────────────────

    private val song = com.music.bitchord.data.model.Song("abcdefghijk", "Song", "Artist", null)

    @Test
    fun aTrackThatIsFoundIsPlayed() = runBlocking {
        val resolver = SharedLinkResolver(
            read = { ExternalLink.Resolved("Song Artist", isTrack = true) },
            findSong = { song },
        )

        assertEquals(SharedLinkOutcome.Play(song), resolver.open("https://open.spotify.com/track/1"))
    }

    @Test
    fun aTrackThatIsNotFoundBecomesASearch() = runBlocking {
        val resolver = SharedLinkResolver(
            read = { ExternalLink.Resolved("Song Artist", isTrack = true) },
            findSong = { null },
        )

        assertEquals(SharedLinkOutcome.Search("Song Artist"), resolver.open("https://open.spotify.com/track/1"))
    }

    @Test
    fun anAlbumIsSearchedNotPlayed() = runBlocking {
        var searched = false
        val resolver = SharedLinkResolver(
            read = { ExternalLink.Resolved("Album Artist", isTrack = false) },
            findSong = { searched = true; song },
        )

        assertEquals(SharedLinkOutcome.Search("Album Artist"), resolver.open("https://open.spotify.com/album/1"))
        assertFalse(searched)
    }

    @Test
    fun aPageThatNamesNothingIsUnresolved() = runBlocking {
        val resolver = SharedLinkResolver(read = { null }, findSong = { song })

        assertEquals(SharedLinkOutcome.Unresolved, resolver.open("https://open.spotify.com/track/1"))
    }

    @Test
    fun aServiceThatDoesNotAnswerEndsAsUnresolvedAfterTheLimit() = runBlocking {
        val resolver = SharedLinkResolver(
            read = { delay(10_000); ExternalLink.Resolved("Song Artist", isTrack = true) },
            findSong = { song },
            timeoutMs = 100,
        )

        assertEquals(SharedLinkOutcome.Unresolved, resolver.open("https://open.spotify.com/track/1"))
    }
}
