package com.music.bitchord

import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.StreamFormat
import com.music.bitchord.playback.StreamChoice
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The promise [StreamChoice] makes: a track being served from one copy keeps
 * being served from that copy.
 *
 * Every live entry stands behind a half-filled cache entry on disk, and the
 * cost of breaking one is not a wrong bitrate but a corrupt file — the middle
 * of an MP4 appended to a WebM. So the tests here are about what happens under
 * pressure, which is where that promise was breakable.
 */
class StreamChoiceTest {

    private fun stream(host: String) = SourceStream(
        url = "https://$host/track.mp4",
        format = StreamFormat(codec = "mp4", kbps = 320),
    )

    @Before
    @After
    fun reset() {
        // Ids used below, cleared both ways round so a failure can't leak into
        // the next test through the shared object.
        (0..80).forEach { StreamChoice.forget("track-$it") }
    }

    @Test
    fun `a remembered choice is handed back`() {
        StreamChoice.remember("track-1", stream("aac.saavncdn.com"), substituted = true)
        assertEquals("https://aac.saavncdn.com/track.mp4", StreamChoice.of("track-1")?.url)
    }

    @Test
    fun `a track nothing has chosen for is free to resolve`() {
        assertNull(StreamChoice.of("track-2"))
    }

    /** Only the substituted ones, so the recovery path can tell them apart. */
    @Test
    fun `remembers whether the copy came from a substitute`() {
        StreamChoice.remember("track-3", stream("aac.saavncdn.com"), substituted = true)
        StreamChoice.remember("track-4", stream("googlevideo.com"), substituted = false)
        assertEquals(true, StreamChoice.isSubstitute("track-3"))
        assertEquals(false, StreamChoice.isSubstitute("track-4"))
    }

    /**
     * The eviction that mattered. Overflow used to `clear()` the whole map,
     * which releases every track being served — including ones with bytes
     * half-written under a key only their own stream may finish.
     *
     * Read-ahead made this reachable: it pins the next track and caches its
     * bytes before the listener gets there, so a pin now has to survive other
     * tracks being remembered in between. The most recent entries must still be
     * honoured after the map has been pushed well past its limit.
     */
    @Test
    fun `overflow drops the oldest choice rather than all of them`() {
        repeat(40) { StreamChoice.remember("track-$it", stream("host-$it.example"), substituted = true) }

        // The newest write is always honoured under either policy: it is the
        // track being read right now.
        assertNotNull(StreamChoice.of("track-39"))
        assertEquals("https://host-39.example/track.mp4", StreamChoice.of("track-39")?.url)

        // This is the assertion that separates evicting-the-oldest from
        // emptying the map. `track-20` is one of the twenty most recent writes,
        // so any bounded map worth the name still holds it — but a `clear()` on
        // overflow throws it out along with everything else, and the cache entry
        // it was standing behind becomes free for another source to finish.
        assertNotNull(
            "a recent choice was evicted, so overflow is still emptying the map wholesale",
            StreamChoice.of("track-20"),
        )
        assertEquals("https://host-20.example/track.mp4", StreamChoice.of("track-20")?.url)
    }

    /** Releasing a track lets the next resolve decide afresh. */
    @Test
    fun `forgetting a choice reopens the question`() {
        StreamChoice.remember("track-5", stream("aac.saavncdn.com"), substituted = true)
        StreamChoice.forget("track-5")
        assertNull(StreamChoice.of("track-5"))
    }

    /** Read-ahead and the player's resolve race for the same track; the first to record wins for both. */
    @Test
    fun `remembering if absent keeps the choice already made`() {
        val first = stream("tracks.monochrome.st")
        assertEquals(first.url, StreamChoice.rememberIfAbsent("track-6", first, substituted = true).url)
        val second = stream("aac.saavncdn.com")
        assertEquals(first.url, StreamChoice.rememberIfAbsent("track-6", second, substituted = true).url)
        assertEquals(first.url, StreamChoice.of("track-6")?.url)
    }

    @Test
    fun `a choice in use does not expire`() {
        StreamChoice.remember("track-7", stream("aac.saavncdn.com"), substituted = true)
        // Used every ten minutes for an hour: never idle past the limit.
        repeat(6) {
            now += 10 * 60 * 1000L
            assertNotNull(StreamChoice.of("track-7"))
        }
    }

    @Test
    fun `an idle choice over nothing half-filled expires`() {
        StreamChoice.remember("track-8", stream("aac.saavncdn.com"), substituted = true)
        now += StreamChoice.TTL_MS + 1
        assertNull(StreamChoice.of("track-8"))
    }

    @Test
    fun `an idle choice over a half-filled entry is held, then lapses with its bytes`() {
        val lapsed = mutableListOf<String>()
        StreamChoice.holdsPartial = { it == "track-9" }
        StreamChoice.onLapsed = { lapsed += it }
        StreamChoice.remember("track-9", stream("tracks.monochrome.st"), substituted = true)
        now += StreamChoice.TTL_MS + 1
        assertNotNull(StreamChoice.of("track-9"))
        now += StreamChoice.MAX_PIN_MS
        // Used a moment ago, so only the absolute cap can release it.
        now += StreamChoice.TTL_MS + 1
        assertNull(StreamChoice.of("track-9"))
        assertEquals(listOf("track-9"), lapsed)
    }

    @Test
    fun `an expired choice can be replaced`() {
        StreamChoice.remember("track-10", stream("aac.saavncdn.com"), substituted = true)
        now += StreamChoice.TTL_MS + 1
        val fresh = stream("tracks.monochrome.st")
        assertEquals(fresh.url, StreamChoice.rememberIfAbsent("track-10", fresh, substituted = true).url)
    }

    private var now = 1_000_000L

    @Before
    fun useTestClock() {
        StreamChoice.clock = { now }
        StreamChoice.holdsPartial = { false }
        StreamChoice.onLapsed = {}
    }
}
