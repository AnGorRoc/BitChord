package com.music.bitchord.data

import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.MoodGenre
import com.music.bitchord.data.model.MoodGenreSection
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.ShelfLayout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FeedCacheTest {

    @Before
    @After
    fun cleanup() {
        FeedCache.clear()
    }

    @Test
    fun serializationOfShelfItemAndHomeShelf() {
        val item1 = ShelfItem(
            title = "Starboy",
            subtitle = "The Weeknd",
            thumbnailUrl = "https://example.com/starboy.jpg",
            videoId = "d38H1GZ18tA",
            browseId = null,
            isVideo = false,
        )
        val item2 = ShelfItem(
            title = "Top Hits 2026",
            subtitle = "Playlist • 50 tracks",
            thumbnailUrl = "https://example.com/hits.jpg",
            videoId = null,
            browseId = "VLPL12345",
            isVideo = false,
        )

        val shelf = HomeShelf(
            title = "Listen again",
            subtitle = "Picked for you",
            moreBrowseId = "FEmusic_listen_again",
            moreParams = "param123",
            layout = ShelfLayout.LIST,
            items = listOf(item1, item2),
        )

        val jsonShelf = FeedCache.run { shelf.toJsonObject() }
        val decodedShelf = FeedCache.homeShelfFromJson(jsonShelf)

        assertEquals(shelf.title, decodedShelf.title)
        assertEquals(shelf.subtitle, decodedShelf.subtitle)
        assertEquals(shelf.moreBrowseId, decodedShelf.moreBrowseId)
        assertEquals(shelf.moreParams, decodedShelf.moreParams)
        assertEquals(shelf.layout, decodedShelf.layout)
        assertEquals(2, decodedShelf.items.size)
        assertEquals(item1.title, decodedShelf.items[0].title)
        assertEquals(item1.videoId, decodedShelf.items[0].videoId)
        assertEquals(item2.title, decodedShelf.items[1].title)
        assertEquals(item2.browseId, decodedShelf.items[1].browseId)
    }

    @Test
    fun serializationOfMoodGenreAndSection() {
        val mg1 = MoodGenre(
            title = "Pop",
            browseId = "FEmusic_mood_pop",
            params = "pop_params",
            thumbnailUrl = "https://example.com/pop.jpg",
            stripeColor = 0xFF4285F4,
        )
        val mg2 = MoodGenre(
            title = "Rock",
            browseId = "FEmusic_mood_rock",
            params = "rock_params",
            thumbnailUrl = null,
            stripeColor = null,
        )

        val section = MoodGenreSection(
            title = "Genres",
            items = listOf(mg1, mg2),
        )

        val jsonSection = FeedCache.run { section.toJsonObject() }
        val decodedSection = FeedCache.moodGenreSectionFromJson(jsonSection)

        assertEquals("Genres", decodedSection.title)
        assertEquals(2, decodedSection.items.size)
        assertEquals("Pop", decodedSection.items[0].title)
        assertEquals("FEmusic_mood_pop", decodedSection.items[0].browseId)
        assertEquals("https://example.com/pop.jpg", decodedSection.items[0].thumbnailUrl)
        assertEquals(0xFF4285F4, decodedSection.items[0].stripeColor)
        assertEquals("Rock", decodedSection.items[1].title)
        assertNull(decodedSection.items[1].thumbnailUrl)
    }

    @Test
    fun serializationOfCachedHomeFeed() {
        val shelf = HomeShelf(
            title = "New Releases",
            items = listOf(
                ShelfItem("Song A", "Artist A", null, "vidA", null, false)
            ),
        )
        val cached = CachedHomeFeed(
            listenerKey = "account_1:profile_2",
            shelves = listOf(shelf),
            continuation = "token_abc_123",
            timestamp = 1700000000L,
        )

        val jsonFeed = FeedCache.run { cached.toJsonObject() }
        val decodedFeed = FeedCache.cachedHomeFeedFromJson(jsonFeed)

        assertEquals("account_1:profile_2", decodedFeed.listenerKey)
        assertEquals("token_abc_123", decodedFeed.continuation)
        assertEquals(1700000000L, decodedFeed.timestamp)
        assertEquals(1, decodedFeed.shelves.size)
        assertEquals("New Releases", decodedFeed.shelves[0].title)
        assertEquals("Song A", decodedFeed.shelves[0].items[0].title)
    }

    @Test
    fun homeCacheGetSaveTouch() {
        assertNull(FeedCache.getHome("user_1"))

        val shelves = listOf(
            HomeShelf("Listen again", listOf(ShelfItem("Song 1", "Artist 1", null, "vid1", null)))
        )
        FeedCache.saveHome("user_1", shelves, "cont_1")

        val retrieved = FeedCache.getHome("user_1")
        assertNotNull(retrieved)
        assertEquals(1, retrieved!!.shelves.size)
        assertEquals("Listen again", retrieved.shelves[0].title)
        assertEquals("cont_1", retrieved.continuation)
        val initialTimestamp = retrieved.timestamp

        // Different user should return null
        assertNull(FeedCache.getHome("user_2"))

        // Touch updates timestamp
        FeedCache.touchHome("user_1")
        val touched = FeedCache.getHome("user_1")
        assertNotNull(touched)
        assertTrue(touched!!.timestamp >= initialTimestamp)
    }

    @Test
    fun exploreAndMoodGenreMemoryCache() {
        assertNull(FeedCache.getExplore())

        val sections = listOf(
            MoodGenreSection("Moods", listOf(MoodGenre("Chill", "browse_chill", null)))
        )
        FeedCache.saveExplore(sections)
        assertEquals(1, FeedCache.getExplore()?.size)
        assertEquals("Chill", FeedCache.getExplore()?.first()?.items?.first()?.title)

        val shelves = listOf(
            HomeShelf("Chill Hits", listOf(ShelfItem("Song", "Artist", null, "v1", null)))
        )
        FeedCache.saveMoodGenreShelves("browse_chill_", shelves)
        assertEquals(1, FeedCache.getMoodGenreShelves("browse_chill_")?.size)
    }

    @Test
    fun areShelvesEqualChecks() {
        val item1 = ShelfItem("Track 1", "Artist 1", "https://thumb1.jpg", "vid1", null)
        val item2 = ShelfItem("Track 2", "Artist 2", "https://thumb2.jpg", "vid2", null)
        val shelf1 = HomeShelf("Recently played", listOf(item1, item2))
        val shelf2 = HomeShelf("Recently played", listOf(item1, item2))

        assertTrue(FeedCache.areShelvesEqual(listOf(shelf1), listOf(shelf2)))

        // Different item in shelf
        val item3 = ShelfItem("Track 3", "Artist 3", null, "vid3", null)
        val shelf3 = HomeShelf("Recently played", listOf(item1, item3))
        assertFalse(FeedCache.areShelvesEqual(listOf(shelf1), listOf(shelf3)))

        // Different shelf title
        val shelf4 = HomeShelf("Different title", listOf(item1, item2))
        assertFalse(FeedCache.areShelvesEqual(listOf(shelf1), listOf(shelf4)))

        // Different shelf list size
        assertFalse(FeedCache.areShelvesEqual(listOf(shelf1), listOf(shelf1, shelf2)))
    }

    @Test
    fun areExploreSectionsEqualChecks() {
        val mg1 = MoodGenre("Pop", "b1", "p1")
        val mg2 = MoodGenre("Rock", "b2", "p2")
        val s1 = MoodGenreSection("Genres", listOf(mg1, mg2))
        val s2 = MoodGenreSection("Genres", listOf(mg1, mg2))

        assertTrue(FeedCache.areExploreSectionsEqual(listOf(s1), listOf(s2)))

        // Different item
        val mg3 = MoodGenre("Jazz", "b3", "p3")
        val s3 = MoodGenreSection("Genres", listOf(mg1, mg3))
        assertFalse(FeedCache.areExploreSectionsEqual(listOf(s1), listOf(s3)))

        // Different section title
        val s4 = MoodGenreSection("Moods", listOf(mg1, mg2))
        assertFalse(FeedCache.areExploreSectionsEqual(listOf(s1), listOf(s4)))
    }
}
