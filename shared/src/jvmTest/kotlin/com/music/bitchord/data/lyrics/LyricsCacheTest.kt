package com.music.bitchord.data.lyrics

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LyricsCacheTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun setUp() {
        LyricsCache.cacheDir = tempFolder.root
        LyricsCache.clearMemory()
    }

    @After
    fun tearDown() {
        LyricsCache.clearMemory()
        LyricsCache.cacheDir = null
    }

    @Test
    fun roundtripSerializationWithSyllablesAndBackgroundVocals() = runBlocking {
        val syllables = listOf(
            LyricSyllable(startMs = 1000, endMs = 1500, startChar = 0, endChar = 4),
            LyricSyllable(startMs = 1500, endMs = 2000, startChar = 4, endChar = 8),
        )
        val word = LyricWord(startMs = 1000, endMs = 2000, text = "testword", syllables = syllables)
        val background = LyricLine(
            timeMs = 1200,
            text = "(echo)",
            alignment = LyricAlignment.End,
        )
        val line = LyricLine(
            timeMs = 1000,
            text = "testword",
            words = listOf(word),
            sungUntilMs = 2500,
            background = background,
            alignment = LyricAlignment.Start,
        )

        LyricsCache.put(
            videoId = "vid123",
            title = "Test Song",
            artist = "Test Artist",
            source = LyricsSource.BINI_LYRICS,
            lines = listOf(line),
            isrc = "USRC12345678",
            isPrimary = true,
        )

        // Clear memory to ensure it reads from disk
        LyricsCache.clearMemory()

        val fromDisk = LyricsCache.get("vid123", "Test Song", "Test Artist")
        assertNotNull("Should read cached lyrics from disk", fromDisk)
        assertEquals(LyricsSource.BINI_LYRICS, fromDisk?.source)
        assertEquals("USRC12345678", fromDisk?.isrc)
        assertEquals(1, fromDisk?.lines?.size)

        val retrievedLine = fromDisk?.lines?.first()!!
        assertEquals(1000L, retrievedLine.timeMs)
        assertEquals("testword", retrievedLine.text)
        assertEquals(2500L, retrievedLine.sungUntilMs)
        assertEquals(LyricAlignment.Start, retrievedLine.alignment)
        assertTrue(retrievedLine.isWordSynced)

        val retrievedWord = retrievedLine.words.first()
        assertEquals("testword", retrievedWord.text)
        assertEquals(2, retrievedWord.syllables.size)

        val retrievedBg = retrievedLine.background
        assertNotNull(retrievedBg)
        assertEquals("(echo)", retrievedBg?.text)
        assertEquals(LyricAlignment.End, retrievedBg?.alignment)
    }

    @Test
    fun primaryAndSpecificSourceResolution() = runBlocking {
        val linesBini = listOf(LyricLine(1000, "Bini lyrics"))
        val linesMusix = listOf(LyricLine(1000, "Musixmatch lyrics"))

        LyricsCache.put(
            videoId = "vid999",
            title = "Song",
            artist = "Artist",
            source = LyricsSource.MUSIXMATCH,
            lines = linesMusix,
            isPrimary = false,
        )

        LyricsCache.put(
            videoId = "vid999",
            title = "Song",
            artist = "Artist",
            source = LyricsSource.BINI_LYRICS,
            lines = linesBini,
            isPrimary = true,
        )

        // Primary returns Bini
        val primary = LyricsCache.get("vid999", "Song", "Artist")
        assertEquals(LyricsSource.BINI_LYRICS, primary?.source)
        assertEquals("Bini lyrics", primary?.lines?.first()?.text)

        // Specific source query returns Musixmatch
        val specific = LyricsCache.get("vid999", "Song", "Artist", LyricsSource.MUSIXMATCH)
        assertEquals(LyricsSource.MUSIXMATCH, specific?.source)
        assertEquals("Musixmatch lyrics", specific?.lines?.first()?.text)

        // Unknown source returns null
        val none = LyricsCache.get("vid999", "Song", "Artist", LyricsSource.GENIUS)
        assertNull(none)
    }
}

