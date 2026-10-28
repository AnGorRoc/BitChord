package com.music.bitchord.data.spotify

import com.music.bitchord.data.model.SPOTIFY_MISSING_PREFIX
import com.music.bitchord.data.model.SPOTIFY_PENDING_PREFIX
import com.music.bitchord.data.model.Song
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SpotifyCacheTest {

    @Before
    @After
    fun cleanup() {
        SpotifyCache.clear()
    }

    @Test
    fun serializationOfSongAndSpotifyTrack() {
        val track = SpotifyTrack(
            id = "track123",
            title = "Bohemian Rhapsody",
            artist = "Queen",
            album = "A Night at the Opera",
            durationMs = 354000,
            imageUrl = "https://example.com/cover.jpg",
        )
        val jsonTrack = SpotifyCache.run { track.toJsonObject() }
        val decodedTrack = SpotifyCache.spotifyTrackFromJson(jsonTrack)
        assertEquals(track.id, decodedTrack.id)
        assertEquals(track.title, decodedTrack.title)
        assertEquals(track.artist, decodedTrack.artist)
        assertEquals(track.album, decodedTrack.album)
        assertEquals(track.durationMs, decodedTrack.durationMs)
        assertEquals(track.imageUrl, decodedTrack.imageUrl)

        val song = Song(
            videoId = "dQw4w9WgXcQ",
            title = "Never Gonna Give You Up",
            artist = "Rick Astley",
            thumbnailUrl = "https://example.com/rick.jpg",
            durationText = "3:32",
            albumName = "Whenever You Need Somebody",
            isVideo = false,
        )
        val jsonSong = SpotifyCache.run { song.toJsonObject() }
        val decodedSong = SpotifyCache.songFromJson(jsonSong)
        assertEquals(song.videoId, decodedSong.videoId)
        assertEquals(song.title, decodedSong.title)
        assertEquals(song.artist, decodedSong.artist)
        assertEquals(song.thumbnailUrl, decodedSong.thumbnailUrl)
        assertEquals(song.durationText, decodedSong.durationText)
        assertEquals(song.albumName, decodedSong.albumName)
        assertEquals(song.isVideo, decodedSong.isVideo)
    }

    @Test
    fun serializationOfCachedPlaylist() {
        val track = SpotifyTrack("t1", "Title 1", "Artist 1", "Album 1", 200000, "https://cover1.jpg")
        val song = Song(videoId = "v1", title = "Title 1", artist = "Artist 1", thumbnailUrl = "https://thumb1.jpg")
        val cached = CachedSpotifyPlaylist(
            playlistId = "p1",
            title = "Playlist 1",
            coverUrl = "https://playlist-cover.jpg",
            trackIds = listOf("t1"),
            tracks = listOf(track),
            songs = listOf(song),
            lastCheckedMs = 123456789L,
        )

        val jsonObj = SpotifyCache.run { cached.toJsonObject() }
        val restored = SpotifyCache.cachedPlaylistFromJson(jsonObj)

        assertEquals("p1", restored.playlistId)
        assertEquals("Playlist 1", restored.title)
        assertEquals("https://playlist-cover.jpg", restored.coverUrl)
        assertEquals(listOf("t1"), restored.trackIds)
        assertEquals(1, restored.tracks.size)
        assertEquals("t1", restored.tracks[0].id)
        assertEquals(1, restored.songs.size)
        assertEquals("v1", restored.songs[0].videoId)
        assertEquals(123456789L, restored.lastCheckedMs)
    }

    @Test
    fun saveAndRetrievePlaylistInMemory() {
        val track1 = SpotifyTrack("tr1", "Song 1", "Artist 1", "Album 1", 180000, null)
        val track2 = SpotifyTrack("tr2", "Song 2", "Artist 2", "Album 2", 210000, null)
        val song1 = Song(videoId = "yt_1", title = "Song 1", artist = "Artist 1", thumbnailUrl = null)
        val song2 = Song(videoId = "yt_2", title = "Song 2", artist = "Artist 2", thumbnailUrl = null)

        SpotifyCache.savePlaylist(
            playlistId = "my_playlist",
            title = "My Playlist",
            coverUrl = "https://cover.jpg",
            tracks = listOf(track1, track2),
            songs = listOf(song1, song2),
        )

        val retrieved = SpotifyCache.getPlaylist("my_playlist")
        assertNotNull(retrieved)
        assertEquals("my_playlist", retrieved?.playlistId)
        assertEquals("My Playlist", retrieved?.title)
        assertEquals(2, retrieved?.songs?.size)
        assertEquals("yt_1", retrieved?.songs?.get(0)?.videoId)
        assertEquals("yt_2", retrieved?.songs?.get(1)?.videoId)

        // Verifying track matches were automatically populated
        assertEquals("yt_1", SpotifyCache.getMatch("tr1")?.videoId)
        assertEquals("yt_2", SpotifyCache.getMatch("tr2")?.videoId)
    }

    @Test
    fun trackMatchDoesNotCacheUnresolvedSongs() {
        val pendingSong = Song(videoId = SPOTIFY_PENDING_PREFIX + "tr3", title = "Pending", artist = "Artist", thumbnailUrl = null)
        val missingSong = Song(videoId = SPOTIFY_MISSING_PREFIX + "tr4", title = "Missing", artist = "Artist", thumbnailUrl = null)
        val resolvedSong = Song(videoId = "yt_resolved", title = "Resolved", artist = "Artist", thumbnailUrl = null)

        SpotifyCache.putMatch("tr3", pendingSong)
        assertNull(SpotifyCache.getMatch("tr3"))

        SpotifyCache.putMatch("tr4", missingSong)
        assertNull(SpotifyCache.getMatch("tr4"))

        SpotifyCache.putMatch("tr5", resolvedSong)
        assertEquals("yt_resolved", SpotifyCache.getMatch("tr5")?.videoId)
    }

    @Test
    fun saveAndGetPlaylistsLibrary() {
        val p1 = SpotifyPlaylist("pl1", "Rock Classics", "Spotify", "https://rock.jpg")
        val p2 = SpotifyPlaylist("pl2", "Jazz Vibes", "Spotify", "https://jazz.jpg")

        SpotifyCache.savePlaylists(listOf(p1, p2))
        val retrieved = SpotifyCache.getPlaylists()

        assertNotNull(retrieved)
        assertEquals(2, retrieved?.size)
        assertEquals("pl1", retrieved?.get(0)?.id)
        assertEquals("pl2", retrieved?.get(1)?.id)
    }

    @Test
    fun updateCoverAndTouch() {
        val track = SpotifyTrack("tr1", "Song", "Artist", null, 180000, null)
        val song = Song(videoId = "yt_1", title = "Song", artist = "Artist", thumbnailUrl = null)

        SpotifyCache.savePlaylist("test_pl", "Title", "https://old_cover.jpg", listOf(track), listOf(song))

        SpotifyCache.updateCover("test_pl", "https://new_cover.jpg")
        assertEquals("https://new_cover.jpg", SpotifyCache.getPlaylist("test_pl")?.coverUrl)

        val beforeTouch = SpotifyCache.getPlaylist("test_pl")?.lastCheckedMs ?: 0L
        Thread.sleep(10)
        SpotifyCache.touch("test_pl")
        val afterTouch = SpotifyCache.getPlaylist("test_pl")?.lastCheckedMs ?: 0L
        assertTrue(afterTouch >= beforeTouch)
    }
}
