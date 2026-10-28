package com.music.bitchord.data

import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** What [FeedCache] writes, ages out and reads back after a restart. */
class FeedCachePersistenceTest {

    private lateinit var prefs: MemoryPreferences
    private var now = 1_000_000_000L

    @Before
    fun setUp() {
        prefs = MemoryPreferences()
        FeedCache.clock = { now }
        FeedCache.initWith(prefs, Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        FeedCache.clock = System::currentTimeMillis
        FeedCache.initWith(MemoryPreferences(), Dispatchers.Unconfined)
    }

    private fun restart() = FeedCache.initWith(prefs, Dispatchers.Unconfined)

    private fun shelf(title: String) = HomeShelf(
        title = title,
        subtitle = "",
        items = listOf(ShelfItem(title = "Item of $title", subtitle = "", thumbnailUrl = null, videoId = "v-$title", browseId = null)),
    )

    @Test
    fun `a saved home feed is shown after a restart`() {
        FeedCache.saveHome("me", listOf(shelf("A")), continuation = "next")

        restart()
        val feed = FeedCache.getHome("me")

        assertNotNull(feed)
        assertEquals(listOf("A"), feed!!.shelves.map { it.title })
        assertEquals("next", feed.continuation)
    }

    @Test
    fun `a home feed from last month is not shown`() {
        FeedCache.saveHome("me", listOf(shelf("A")), continuation = null)
        restart()

        now += FeedCache.MAX_AGE_MS + 1

        assertNull(FeedCache.getHome("me"))
    }

    @Test
    fun `a feed the network just confirmed stays young`() {
        FeedCache.saveHome("me", listOf(shelf("A")), continuation = null)

        now += FeedCache.MAX_AGE_MS - 1_000
        FeedCache.touchHome("me")
        now += FeedCache.MAX_AGE_MS - 1_000
        restart()

        assertNotNull(FeedCache.getHome("me"))
    }

    @Test
    fun `an unreadable feed is absent, not an error`() {
        prefs.data["home_me"] = "{ this is not json"

        assertNull(FeedCache.getHome("me"))
    }

    @Test
    fun `mood and genre pages are kept for the last few opened only`() {
        repeat(FeedCache.MAX_MOOD_GENRES + 4) { n -> FeedCache.saveMoodGenreShelves("g$n", listOf(shelf("S$n"))) }

        assertFalse(prefs.contains("mood_genre_g0"))
        assertFalse(prefs.contains("mood_genre_g3"))
        assertTrue(prefs.contains("mood_genre_g4"))
        assertTrue(prefs.contains("mood_genre_g${FeedCache.MAX_MOOD_GENRES + 3}"))
    }

    @Test
    fun `prefs written under another schema are dropped`() {
        FeedCache.saveHome("me", listOf(shelf("A")), continuation = null)
        prefs.data[CacheSchema.KEY] = FeedCache.SCHEMA_VERSION - 1

        restart()

        assertNull(FeedCache.getHome("me"))
    }

    @Test
    fun `clearing keeps the schema marker so the next start does not clear again`() {
        FeedCache.saveHome("me", listOf(shelf("A")), continuation = null)

        FeedCache.clear()

        assertEquals(FeedCache.SCHEMA_VERSION, prefs.getInt(CacheSchema.KEY, 0))
        assertNull(prefs.getString("home_me", null))
    }
}
