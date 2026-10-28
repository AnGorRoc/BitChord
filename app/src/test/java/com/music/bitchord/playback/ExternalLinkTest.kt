package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class ExternalLinkTest {
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
}
