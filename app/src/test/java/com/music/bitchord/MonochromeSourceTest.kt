package com.music.bitchord

import com.music.bitchord.data.settings.AudioQuality
import com.music.bitchord.data.settings.permits
import com.music.bitchord.data.sources.MonochromeGate
import com.music.bitchord.data.sources.MonochromeSource
import com.music.bitchord.data.sources.SkipPace
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceHealth
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.SourceThrottled
import com.music.bitchord.data.sources.StreamRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Monochrome, end to end, against a real HTTP server.
 *
 * As with [AddonSourceTest], the server is real and only what it says is
 * scripted — the behaviour worth pinning down is what this source does with
 * the answers Monochrome actually gives, including the one that motivated the
 * header check: an MP4 of AAC served from the same endpoint as the FLACs.
 */
class MonochromeSourceTest {

    private lateinit var server: MockWebServer

    /** What the server answers, per path, set by each test before it runs. */
    private val routes = mutableMapOf<String, MockResponse>()

    /** Every request the server saw, in order. */
    private val seen = mutableListOf<RecordedRequest>()

    @Before
    fun setUp() {
        MonochromeGate.shared.reset()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(seen) { seen += request }
                val path = request.requestUrl?.encodedPath.orEmpty()
                return routes[path] ?: MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        SkipPace.shared.reset()
        MonochromeGate.shared.reset()
        runCatching { server.shutdown() }
    }

    // ── Search ────────────────────────────────────────────────────────────

    @Test
    fun `search maps rows to songs tagged with this source`() = runBlocking {
        route("/search/tracks", json(SEARCH_ANSWER))
        val source = source()

        val songs = source.search("daft punk get lucky", limit = 10)

        assertEquals(2, songs.size)
        val first = songs.first()
        assertEquals(SourceRegistry.trackKey(CONFIG_ID, "155142501534011392"), first.videoId)
        assertEquals("Get Lucky (feat. Pharrell Williams and Nile Rodgers)", first.title)
        assertEquals("Daft Punk, Pharrell Williams, Nile Rodgers", first.artist)
        // Milliseconds in, M:SS out: the matcher's duration veto depends on it.
        assertEquals("6:09", first.durationText)
        assertEquals("LOSSLESS", first.sourceQuality)
        assertEquals(false, first.isExplicit)
        assertEquals("https://tracks.example/art.jpg", first.thumbnailUrl)
    }

    @Test
    fun `search asks with the query and a limit`() = runBlocking {
        route("/search/tracks", json(SEARCH_ANSWER))
        source().search("radiohead creep", limit = 5)

        val request = seen.single()
        assertEquals("radiohead creep", request.requestUrl?.queryParameter("q"))
        assertEquals("15", request.requestUrl?.queryParameter("limit"))
        assertTrue(request.getHeader("User-Agent").orEmpty().startsWith("BitChord/"))
    }

    /**
     * A row the server marks unplayable would only be a miss later, and one
     * with no id or title can't be matched or opened.
     */
    @Test
    fun `search drops unplayable rows and rows with no id or title`() = runBlocking {
        route("/search/tracks", json(SEARCH_ANSWER))
        val ids = source().search("anything", limit = 25).map { SourceRegistry.parseTrackKey(it.videoId)?.second }
        assertEquals(listOf("155142501534011392", "158233189067153408"), ids)
    }

    @Test
    fun `older answers in seconds still read as the right length`() = runBlocking {
        route("/search/tracks", json("""{"tracks":[{"trackId":"1","title":"Song","artistNames":["A"],"duration":247}]}"""))
        assertEquals("4:07", source().search("song", limit = 5).single().durationText)
    }

    @Test
    fun `a search the server refuses is no results rather than a crash`() = runBlocking {
        route("/search/tracks", MockResponse().setResponseCode(503))
        assertTrue(source().search("anything", limit = 5).isEmpty())
    }

    @Test
    fun `an unreachable server returns no results and no stream`() = runBlocking {
        val source = source()
        server.shutdown()
        assertTrue(source.search("anything", limit = 5).isEmpty())
        assertNull(source.stream("158233189067153408", StreamRequest.Lossless))
    }

    @Test
    fun `the same search is asked once`() = runBlocking {
        route("/search/tracks", json(SEARCH_ANSWER))
        val source = source()
        source.search("get lucky", limit = 5)
        source.search("get lucky", limit = 5)
        assertEquals(1, seen.size)
    }

    // ── Stream ────────────────────────────────────────────────────────────

    /** The head of a real file from the live server: 24-bit, 44.1 kHz, stereo. */
    @Test
    fun `a FLAC opens with the depth and rate its STREAMINFO states`() = runBlocking {
        route("/track/158233189067153408", partial(LIVE_FLAC_HEAD))

        val stream = source().stream("158233189067153408", StreamRequest.Lossless)

        assertNotNull(stream)
        assertEquals("flac", stream!!.format.codec)
        assertEquals(true, stream.format.isLossless)
        assertEquals(24, stream.format.bitDepth)
        assertEquals(44_100, stream.format.sampleRateHz)
        assertNull(stream.format.kbps)
        assertTrue(stream.headers.isEmpty())
        // The size from the probe's Content-Range, so the player can read it in
        // bounded ranges rather than one read the server cuts at thirty seconds.
        assertTrue(stream.url, stream.url.endsWith("/track/158233189067153408?mime=audio/flac&clen=51478920"))
    }

    @Test
    fun `a file whose size wasn't stated carries none`() = runBlocking {
        route("/track/1", partial(LIVE_FLAC_HEAD).removeHeader("Content-Range"))

        val url = source().stream("1", StreamRequest.Lossless)!!.url

        assertTrue(url, url.endsWith("/track/1?mime=audio/flac"))
    }

    /**
     * A lossless stream with no stated bitrate shows none in the audio
     * pipeline. This one states its true figure: the file's size, from the
     * probe's Content-Range, over the length its search row gave.
     */
    @Test
    fun `a searched track states its real bitrate`() = runBlocking {
        route("/search/tracks", json(SEARCH_ANSWER))
        route("/track/158233189067153408", partial(LIVE_FLAC_HEAD))
        val source = source()

        source.search("get lucky", limit = 5)
        val stream = source.stream("158233189067153408", StreamRequest.Lossless)

        // 51,478,920 bytes over 247 s.
        assertEquals(1667, stream?.format?.kbps)
        assertFalse(stream!!.format.kbpsInferred)
    }

    @Test
    fun `bitrate is left out when the length isn't known`() {
        assertNull(MonochromeSource.bitrateKbps(51_478_920, null))
        assertNull(MonochromeSource.bitrateKbps(null, 247))
        assertEquals(1667, MonochromeSource.bitrateKbps(51_478_920, 247))
    }

    @Test
    fun `the file's size is read from whichever header carries it`() {
        assertEquals(51_478_920L, MonochromeSource.totalBytes(206, "bytes 0-41/51478920", "42"))
        assertNull(MonochromeSource.totalBytes(206, "bytes 0-41/*", "42"))
        assertEquals(65_578L, MonochromeSource.totalBytes(200, null, "65578"))
        assertNull(MonochromeSource.totalBytes(206, null, null))
    }

    @Test
    fun `the probe reads only the head of the file`() = runBlocking {
        route("/track/1", partial(flacHead(rate = 44_100, bits = 16)))
        source().stream("1", StreamRequest.Lossless)
        assertEquals("bytes=0-41", seen.single().getHeader("Range"))
    }

    @Test
    fun `hi-res and CD FLACs are told apart`() = runBlocking {
        route("/track/1", partial(flacHead(rate = 44_100, bits = 16)))
        route("/track/2", partial(flacHead(rate = 96_000, bits = 24)))
        route("/track/3", partial(flacHead(rate = 192_000, bits = 24)))
        val source = source()

        val cd = source.stream("1", StreamRequest.Lossless)!!.format
        val hiRes = source.stream("2", StreamRequest.Lossless)!!.format
        val master = source.stream("3", StreamRequest.Lossless)!!.format

        assertEquals(16 to 44_100, cd.bitDepth to cd.sampleRateHz)
        assertEquals(24 to 96_000, hiRes.bitDepth to hiRes.sampleRateHz)
        assertEquals(24 to 192_000, master.bitDepth to master.sampleRateHz)
    }

    /**
     * The reason the header is read at all: the live server answered one of
     * eight tracks with an AAC in an MP4, from the same endpoint, with nothing
     * in the search row to say so. Played, it would have worn a lossless label.
     */
    @Test
    fun `an MP4 of AAC is a miss, not a lossless stream`() = runBlocking {
        route("/track/155142501534011392", partial(MP4_HEAD, contentType = "audio/mp4"))
        assertNull(source().stream("155142501534011392", StreamRequest.Lossless))
    }

    @Test
    fun `a server that ignores the range still has its head read`() = runBlocking {
        val whole = flacHead(rate = 48_000, bits = 16) + ByteArray(64 * 1024)
        route("/track/1", MockResponse().setResponseCode(200).setBody(Buffer().write(whole)))
        val format = source().stream("1", StreamRequest.Lossless)?.format
        assertEquals(48_000, format?.sampleRateHz)
    }

    @Test
    fun `a missing track is a miss`() = runBlocking {
        assertNull(source().stream("404404", StreamRequest.Lossless))
    }

    @Test
    fun `a capped request is never asked for`() = runBlocking {
        route("/track/1", partial(flacHead(rate = 44_100, bits = 16)))
        assertNull(source().stream("1", StreamRequest.Capped(maxKbps = 320)))
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `an id that isn't one is refused before any request`() = runBlocking {
        assertNull(source().stream("../search/tracks", StreamRequest.Lossless))
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `a track's file is checked once, then remembered`() = runBlocking {
        route("/track/1", partial(flacHead(rate = 44_100, bits = 16)))
        val source = source()
        source.stream("1", StreamRequest.Lossless)
        source.stream("1", StreamRequest.Lossless)
        assertEquals(1, seen.size)
    }

    /** A server error says nothing about the track, so it is asked again next time. */
    @Test
    fun `a failed probe is not remembered`() = runBlocking {
        route("/track/1", MockResponse().setResponseCode(502))
        val source = source()
        assertNull(source.stream("1", StreamRequest.Lossless))

        route("/track/1", partial(flacHead(rate = 44_100, bits = 16)))
        assertNotNull(source.stream("1", StreamRequest.Lossless))
    }

    // ── Rate limiting ─────────────────────────────────────────────────────
    //
    // The waiting itself is MonochromeGate's, tested on its own clock in
    // MonochromeGateTest. These pin down what the source does with a refusal.

    /**
     * A 429 is the server asking to be left alone, not an answer about the
     * track — so it is said up rather than read as a miss, and not asked again
     * on the spot: a second ask a second later was one more refusal.
     */
    @Test
    fun `a throttled probe is asked once and said up`() = runBlocking {
        script(throttled("1"), partial(flacHead(rate = 44_100, bits = 16)))

        refused { source().stream("1", StreamRequest.Lossless) }

        assertEquals(1, seen.size)
    }

    /** Once the circuit has closed the same track is asked again, so the refusal wasn't kept. */
    @Test
    fun `a throttled probe is not remembered`() = runBlocking {
        script(throttled("1"), partial(flacHead(rate = 44_100, bits = 16)))
        val source = source()

        refused { source.stream("1", StreamRequest.Lossless) }
        MonochromeGate.shared.reset()

        assertNotNull(source.stream("1", StreamRequest.Lossless))
    }

    /** The gate sits in the shared client, so every probe after a refusal is held back with it. */
    @Test
    fun `a probe while the circuit is open fails without a request`() = runBlocking {
        script(throttled("1"), partial(flacHead(rate = 44_100, bits = 16)))
        val source = source()

        refused { source.stream("1", StreamRequest.Lossless) }
        refused { source.stream("2", StreamRequest.Lossless) }

        assertEquals(1, seen.size)
    }

    /** A refused search is a miss; the file circuit is not its business. */
    @Test
    fun `a throttled search is a miss and is not asked again`() = runBlocking {
        script(throttled("1"), json(SEARCH_ANSWER))

        assertTrue(source().search("daft punk get lucky", limit = 10).isEmpty())
        assertEquals(1, seen.size)
    }

    /** What a lookup that found nothing is told about why, so it isn't taken as a no. */
    @Test
    fun `a refusal is noted for a lookup that was running through it`() = runBlocking {
        script(throttled("1"))
        val before = System.currentTimeMillis()

        refused { source().stream("1", StreamRequest.Lossless) }

        assertTrue(MonochromeGate.shared.refusedSince(before))
        assertFalse(MonochromeGate.shared.refusedSince(System.currentTimeMillis() + 60_000))
    }

    /** Never more than two files being opened at once, however many tracks ask. */
    @Test
    fun `probes are opened two at a time`() = runBlocking {
        val open = java.util.concurrent.atomic.AtomicInteger()
        val peak = java.util.concurrent.atomic.AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(seen) { seen += request }
                peak.accumulateAndGet(open.incrementAndGet(), ::maxOf)
                Thread.sleep(150)
                open.decrementAndGet()
                return partial(flacHead(rate = 44_100, bits = 16))
            }
        }
        val source = source()

        (1..6).map { id -> async(Dispatchers.IO) { source.stream("$id", StreamRequest.Lossless) } }
            .forEach { it.await() }

        assertEquals(6, seen.size)
        assertTrue("peak was ${peak.get()}", peak.get() <= MonochromeSource.MAX_PARALLEL_PROBES)
    }

    /** The server limits the files and the search separately, and so does this. */
    @Test
    fun `files refused don't hold back the search`() = runBlocking {
        route("/track/1", throttled("60"))
        route("/search/tracks", json(SEARCH_ANSWER))
        val source = source()

        refused { source.stream("1", StreamRequest.Lossless) }
        assertEquals(2, source.search("daft punk get lucky", limit = 10).size)
    }

    // ── Skipping ──────────────────────────────────────────────────────────

    /** A song the listener is skipping past is never asked about. */
    @Test
    fun `a lookup for a track skipped during a flick through the queue never reaches the server`() = runBlocking {
        route("/search/tracks", json(SEARCH_ANSWER))
        route("/track/1", partial(flacHead(rate = 44_100, bits = 16)))
        repeat(3) { SkipPace.shared.noteSkip() }
        val source = source()

        val search = async(Dispatchers.IO) { source.search("daft punk get lucky", limit = 10) }
        val stream = async(Dispatchers.IO) { source.stream("1", StreamRequest.Lossless) }
        kotlinx.coroutines.delay(200)
        search.cancel()
        stream.cancel()
        search.join()
        stream.join()

        assertTrue(seen.isEmpty())
    }

    /** Nothing is held back for the listener who is simply listening. */
    @Test
    fun `without skipping a lookup goes straight out`() = runBlocking {
        route("/track/1", partial(flacHead(rate = 44_100, bits = 16)))
        assertNotNull(source().stream("1", StreamRequest.Lossless))
        assertEquals(1, seen.size)
    }

    // ── Health ────────────────────────────────────────────────────────────

    @Test
    fun `health follows whether search answers`() = runBlocking {
        route("/search/tracks", json(SEARCH_ANSWER))
        assertTrue(source().health() is SourceHealth.Ok)

        route("/search/tracks", MockResponse().setResponseCode(500))
        assertTrue(source().health() is SourceHealth.Unreachable)
    }

    // ── STREAMINFO ────────────────────────────────────────────────────────

    @Test
    fun `flacInfo reads nothing it cannot see`() {
        assertNull(MonochromeSource.flacInfo(MP4_HEAD))
        // The magic alone: a FLAC, but no STREAMINFO to read a format from.
        assertNull(MonochromeSource.flacInfo(LIVE_FLAC_HEAD.copyOf(10)))
        assertTrue(MonochromeSource.isFlac(LIVE_FLAC_HEAD.copyOf(4)))
        assertFalse(MonochromeSource.isFlac(ByteArray(0)))
    }

    // ── Registry and policy ───────────────────────────────────────────────

    @Test
    fun `Monochrome ships on, including for installs that predate it`() {
        val fresh = SourceRegistry.sourcesForInit(emptyList(), forceJioSaavnOff = false)
        assertTrue(fresh.single { it.kind == SourceKind.MONOCHROME }.enabled)

        val upgraded = SourceRegistry.sourcesForInit(
            listOf(SourceConfig(kind = SourceKind.JIOSAAVN, enabled = false), SourceConfig(kind = SourceKind.YOUTUBE)),
            forceJioSaavnOff = false,
        )
        assertTrue(upgraded.single { it.kind == SourceKind.MONOCHROME }.enabled)
    }

    @Test
    fun `switching Monochrome off survives the next launch`() {
        val off = SourceConfig(kind = SourceKind.MONOCHROME, enabled = false)
        val next = SourceRegistry.sourcesForInit(listOf(off, SourceConfig(kind = SourceKind.YOUTUBE)), forceJioSaavnOff = false)
        assertFalse(next.single { it.kind == SourceKind.MONOCHROME }.enabled)
    }

    @Test
    fun `Monochrome is asked after the user's addons and before everything lossy`() {
        val order = listOf(SourceKind.YOUTUBE, SourceKind.JIOSAAVN, SourceKind.MONOCHROME, SourceKind.ADDON)
            .sortedBy { it.rank }
        assertEquals(listOf(SourceKind.ADDON, SourceKind.MONOCHROME, SourceKind.JIOSAAVN, SourceKind.YOUTUBE), order)
        assertFalse(SourceKind.MONOCHROME.isUserAdded)
    }

    @Test
    fun `Monochrome is only asked when the ceiling is lossless`() {
        assertTrue(AudioQuality.LOSSLESS.permits(SourceKind.MONOCHROME))
        assertFalse(AudioQuality.HIGH.permits(SourceKind.MONOCHROME))
        assertFalse(AudioQuality.MEDIUM.permits(SourceKind.MONOCHROME))
        assertFalse(AudioQuality.LOW.permits(SourceKind.MONOCHROME))
    }

    @Test
    fun `Monochrome serves tracks but is not listed in search`() {
        assertFalse(SourceKind.MONOCHROME.showsInSearch)
        assertTrue(SourceKind.entries.filter { it != SourceKind.MONOCHROME }.all { it.showsInSearch })
        assertTrue(SourceKind.MONOCHROME.canServeLossless)
        assertFalse(SourceKind.MONOCHROME.needsServer)
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /** Runs [block], which must fail with the server asking to be left alone. */
    private suspend fun refused(block: suspend () -> Any?) {
        val failure = runCatching { block() }.exceptionOrNull()
        assertTrue("expected a throttle, got $failure", failure is SourceThrottled)
    }

    private fun route(path: String, response: MockResponse) {
        routes[path] = response
    }

    /** Answers every request with the next of [responses], in order, the last one for any after. */
    private fun script(vararg responses: MockResponse) {
        val queue = ArrayDeque(responses.toList())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(seen) { seen += request }
                return synchronized(queue) { if (queue.size > 1) queue.removeFirst() else queue.first() }
            }
        }
    }

    private fun throttled(retryAfter: String) = MockResponse()
        .setResponseCode(429)
        .setHeader("Retry-After", retryAfter)

    private fun json(body: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun partial(bytes: ByteArray, contentType: String = "application/octet-stream") = MockResponse()
        .setResponseCode(206)
        .setHeader("Content-Type", contentType)
        .setHeader("Content-Range", "bytes 0-${bytes.size - 1}/51478920")
        .setBody(Buffer().write(bytes))

    private fun source() = MonochromeSource(
        SourceConfig(
            id = CONFIG_ID,
            kind = SourceKind.MONOCHROME,
            baseUrl = server.url("/").toString().trimEnd('/'),
        ),
    )

    /**
     * `fLaC`, a STREAMINFO block header, then STREAMINFO with [rate], [bits]
     * and [channels] packed where the format puts them.
     */
    private fun flacHead(rate: Int, bits: Int, channels: Int = 2): ByteArray {
        val head = ByteArray(42)
        "fLaC".forEachIndexed { i, c -> head[i] = c.code.toByte() }
        head[7] = 0x22 // type 0 (STREAMINFO), length 34
        val packed = (rate.toLong() shl 44) or ((channels - 1).toLong() shl 41) or ((bits - 1).toLong() shl 36)
        for (i in 0 until 8) head[18 + i] = (packed shr (56 - 8 * i)).toByte()
        return head
    }

    private companion object {
        const val CONFIG_ID = "monochrome-test"

        /** Trimmed from a real `/search/tracks` answer, plus the rows it must drop. */
        val SEARCH_ANSWER = """
            {"tracks":[
              {"id":"155142501534011392","trackId":"155142501534011392",
               "title":"Get Lucky (feat. Pharrell Williams and Nile Rodgers)",
               "artistNames":["Daft Punk","Pharrell Williams","Nile Rodgers"],
               "artwork":"http://tracks.example/art.jpg","explicit":false,"playable":true,
               "duration":369626,"isrc":"USQX91300108"},
              {"id":"158233189067153408","trackId":"158233189067153408",
               "title":"Get Lucky (Radio Edit) [feat. Pharrell Williams and Nile Rodgers]",
               "artistNames":["Daft Punk"],"explicit":false,"playable":true,"duration":247632},
              {"trackId":"999","title":"Region locked","artistNames":["Daft Punk"],"playable":false,"duration":200000},
              {"trackId":"998","artistNames":["Daft Punk"],"duration":200000},
              {"title":"No id","artistNames":["Daft Punk"],"duration":200000},
              {"trackId":"155142501534011392","title":"Duplicate","artistNames":["Daft Punk"]}
            ],"releases":[],"artists":[]}
        """.trimIndent()

        val LIVE_FLAC_HEAD: ByteArray = (
            "664c614300000022100010000000120058fe0ac4437000a6a2a4f397e6b27bc159ca805900b98834b252"
            ).chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        /** The head of the MP4 the live server sent for the first row above. */
        val MP4_HEAD: ByteArray = (
            "0000001c667479706973 6f6d000002006973 6f6d69736f326d70 3431000000086672 6565000000000000"
            ).replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
