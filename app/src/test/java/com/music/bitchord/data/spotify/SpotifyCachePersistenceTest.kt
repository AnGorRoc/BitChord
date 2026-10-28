package com.music.bitchord.data.spotify

import com.music.bitchord.data.MemoryPreferences
import com.music.bitchord.data.model.ArtistRef
import com.music.bitchord.data.model.SPOTIFY_PENDING_PREFIX
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What [SpotifyCache] writes and what a restart reads back, against
 * preferences held in memory. Writes run on the caller's thread so a test sees
 * them as soon as they are asked for.
 */
class SpotifyCachePersistenceTest {

    private lateinit var prefs: MemoryPreferences

    @Before
    fun setUp() {
        prefs = MemoryPreferences()
        SpotifyCache.initWith(prefs, Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        SpotifyCache.initWith(MemoryPreferences(), Dispatchers.Unconfined)
    }

    /** A new process: the same preferences, nothing in memory. */
    private fun restart() = SpotifyCache.initWith(prefs, Dispatchers.Unconfined)

    private fun track(n: Int) = SpotifyTrack("tr$n", "Song $n", "Artist", null, 180_000, "https://cover/$n")
    private fun song(n: Int, artists: List<ArtistRef> = emptyList()) =
        Song(videoId = "yt%09d".format(n), title = "Song $n", artist = "Artist", thumbnailUrl = null, artists = artists)

    @Test
    fun `a saved playlist is read back after a restart`() {
        SpotifyCache.savePlaylist("pl", "Mix", "cover", listOf(track(1), track(2)), listOf(song(1), song(2)))

        restart()
        val cached = SpotifyCache.getPlaylist("pl")

        assertNotNull(cached)
        assertEquals(listOf("tr1", "tr2"), cached!!.trackIds)
        assertEquals(listOf("yt000000001", "yt000000002"), cached.songs.map { it.videoId })
    }

    @Test
    fun `matches survive a restart`() {
        SpotifyCache.savePlaylist("pl", "Mix", null, listOf(track(1)), listOf(song(1)))

        restart()

        assertEquals("yt000000001", SpotifyCache.getMatch("tr1")?.videoId)
    }

    @Test
    fun `credited artists are kept with a cached song`() {
        val credited = song(1, artists = listOf(ArtistRef("A", "UC1"), ArtistRef("B", null)))
        SpotifyCache.savePlaylist("pl", "Mix", null, listOf(track(1)), listOf(credited))

        restart()

        assertEquals(credited.artists, SpotifyCache.getPlaylist("pl")!!.songs.single().artists)
    }

    @Test
    fun `an unreadable song keeps every other song on its own track`() {
        SpotifyCache.savePlaylist("pl", "Mix", null, listOf(track(1), track(2), track(3)), listOf(song(1), song(2), song(3)))
        // The middle song's entry is damaged on disk.
        val key = "playlist_pl"
        prefs.data[key] = (prefs.data[key] as String).replace("\"videoId\":\"yt000000002\"", "\"videoId\":{\"broken\":1}")

        restart()
        val cached = SpotifyCache.getPlaylist("pl")!!

        assertEquals(3, cached.songs.size)
        assertEquals("yt000000001", cached.songs[0].videoId)
        assertTrue(cached.songs[1].videoId.startsWith(SPOTIFY_PENDING_PREFIX))
        assertEquals("yt000000003", cached.songs[2].videoId)
    }

    @Test
    fun `a copy whose songs no longer line up with its tracks is dropped`() {
        SpotifyCache.savePlaylist("pl", "Mix", null, listOf(track(1), track(2)), listOf(song(1), song(2)))
        prefs.data["playlist_pl"] = (prefs.data["playlist_pl"] as String).replace(
            Regex("\"songs\":\\[.*?\\}\\],"), "\"songs\":[],",
        )

        restart()

        assertNull(SpotifyCache.getPlaylist("pl"))
    }

    @Test
    fun `only the most recent playlists are kept on disk`() {
        repeat(SpotifyCache.MAX_CACHED_PLAYLISTS + 3) { n ->
            SpotifyCache.savePlaylist("pl$n", "Mix $n", null, listOf(track(n)), listOf(song(n)))
        }

        assertFalse(prefs.contains("playlist_pl0"))
        assertFalse(prefs.contains("playlist_pl2"))
        assertTrue(prefs.contains("playlist_pl3"))
        assertTrue(prefs.contains("playlist_pl${SpotifyCache.MAX_CACHED_PLAYLISTS + 2}"))
    }

    @Test
    fun `the match table is bounded and drops what was used least`() {
        repeat(SpotifyCache.MAX_TRACK_MATCHES + 5) { n -> SpotifyCache.putMatch("tr$n", song(n)) }

        assertNull(SpotifyCache.getMatch("tr0"))
        assertNotNull(SpotifyCache.getMatch("tr${SpotifyCache.MAX_TRACK_MATCHES + 4}"))
    }

    @Test
    fun `a song with no Spotify track is not filed under its YouTube id`() {
        SpotifyCache.savePlaylist("pl", "Mix", null, emptyList(), listOf(song(7)))

        assertNull(SpotifyCache.getMatch("yt000000007"))
    }

    @Test
    fun `prefs written under another schema are dropped`() {
        SpotifyCache.savePlaylist("pl", "Mix", null, listOf(track(1)), listOf(song(1)))
        prefs.data[com.music.bitchord.data.CacheSchema.KEY] = SpotifyCache.SCHEMA_VERSION - 1

        restart()

        assertNull(SpotifyCache.getPlaylist("pl"))
    }

    @Test
    fun `an automatic sync is due once the interval has passed`() {
        assertTrue(SpotifyCache.autoSyncDue(minIntervalMs = 1_000, now = 5_000))

        SpotifyCache.markAutoSynced(now = 5_000)

        assertFalse(SpotifyCache.autoSyncDue(minIntervalMs = 1_000, now = 5_500))
        assertTrue(SpotifyCache.autoSyncDue(minIntervalMs = 1_000, now = 6_000))
    }
}
