package com.music.bitchord.data

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.MoodGenre
import com.music.bitchord.data.model.MoodGenreSection
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.ShelfLayout
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.concurrent.ConcurrentHashMap

/** Cached representation of a Home feed, keyed by listener identity. */
data class CachedHomeFeed(
    val listenerKey: String?,
    val shelves: List<HomeShelf>,
    val continuation: String?,
    val timestamp: Long = System.currentTimeMillis(),
)

/**
 * Caches Home shelves and Explore sections in memory and persistently in disk
 * so that both tabs open instantly upon app launch or navigation, and revalidates
 * in the background updating the view smoothly when content changes.
 *
 * A Home feed older than [MAX_AGE_MS] is not shown: stale-while-revalidate is
 * for a feed that is a little behind, not one from last month. [touchHome]
 * renews a feed the network has just confirmed unchanged, which is what keeps
 * an unchanged feed young. What is written is versioned by [SCHEMA_VERSION]
 * and bounded: mood and genre pages are kept for the last [MAX_MOOD_GENRES]
 * opened.
 */
object FeedCache {
    private const val PREFS_NAME = "bitchord_feed_cache"
    private const val KEY_HOME_PREFIX = "home_"
    private const val KEY_EXPLORE = "explore_sections"
    private const val KEY_MOOD_GENRE_PREFIX = "mood_genre_"
    private const val KEY_MOOD_GENRE_INDEX = "mood_genre_index"

    /** Bumped whenever what is written here changes shape; see [CacheSchema]. */
    const val SCHEMA_VERSION = 1

    /** Older than this and a Home feed is treated as absent. */
    const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

    const val MAX_MOOD_GENRES = 24

    private var prefs: SharedPreferences? = null
    private var writer: PrefsWriter? = null
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** The mood and genre pages held, oldest first. */
    private val moodGenreIndex = mutableListOf<String>()

    /** The clock the age of a feed is read on; replaceable so a test can move it. */
    internal var clock: () -> Long = System::currentTimeMillis

    private val homeMemoryCache = ConcurrentHashMap<String, CachedHomeFeed>()
    @Volatile
    private var exploreMemoryCache: List<MoodGenreSection>? = null
    private val moodGenreMemoryCache = ConcurrentHashMap<String, List<HomeShelf>>()

    fun init(context: Context) {
        if (prefs != null) return
        initWith(context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    internal fun initWith(sp: SharedPreferences, lane: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)) {
        CacheSchema.ensure(sp, SCHEMA_VERSION)
        homeMemoryCache.clear()
        exploreMemoryCache = null
        moodGenreMemoryCache.clear()
        synchronized(moodGenreIndex) { moodGenreIndex.clear() }
        prefs = sp
        writer = PrefsWriter(sp, lane)
        loadMoodGenreIndex(sp)
        loadExploreFromPrefs(sp)
    }

    private fun loadMoodGenreIndex(sp: SharedPreferences) {
        runCatching {
            val arr = sp.getString(KEY_MOOD_GENRE_INDEX, null)?.let { json.parseToJsonElement(it).jsonArray } ?: return
            synchronized(moodGenreIndex) {
                moodGenreIndex.clear()
                arr.forEach { el -> el.jsonPrimitive.contentOrNull?.let(moodGenreIndex::add) }
            }
        }.onFailure { TrackLog.w(TAG, "feed cache: unreadable mood index: ${it.message}") }
    }

    private fun isFresh(feed: CachedHomeFeed) = clock() - feed.timestamp <= MAX_AGE_MS

    private fun homePrefKey(listenerKey: String?): String =
        KEY_HOME_PREFIX + (listenerKey?.replace(":", "_") ?: "signed_out")

    private fun loadExploreFromPrefs(sp: SharedPreferences) {
        runCatching {
            sp.getString(KEY_EXPLORE, null)?.let { raw ->
                val arr = json.parseToJsonElement(raw).jsonArray
                exploreMemoryCache = arr.mapNotNull { el ->
                    el.takeIf { it !is JsonNull }?.let { runCatching { moodGenreSectionFromJson(it.jsonObject) }.getOrNull() }
                }
            }
        }
    }

    fun getHome(listenerKey: String?): CachedHomeFeed? {
        val key = homePrefKey(listenerKey)
        homeMemoryCache[key]?.let { return it.takeIf(::isFresh) }

        val sp = prefs ?: return null
        val raw = sp.getString(key, null) ?: return null
        return runCatching {
            val parsed = cachedHomeFeedFromJson(json.parseToJsonElement(raw).jsonObject)
            homeMemoryCache[key] = parsed
            parsed.takeIf(::isFresh)
        }.onFailure { TrackLog.w(TAG, "feed cache: unreadable home feed $key: ${it.message}") }.getOrNull()
    }

    fun saveHome(listenerKey: String?, shelves: List<HomeShelf>, continuation: String?) {
        val key = homePrefKey(listenerKey)
        val feed = CachedHomeFeed(
            listenerKey = listenerKey,
            shelves = shelves,
            continuation = continuation,
            timestamp = clock(),
        )
        homeMemoryCache[key] = feed
        writer?.put(key, feed.toJsonObject().toString())
    }

    fun touchHome(listenerKey: String?) {
        val key = homePrefKey(listenerKey)
        val existing = homeMemoryCache[key] ?: return
        val updated = existing.copy(timestamp = clock())
        homeMemoryCache[key] = updated
        writer?.put(key, updated.toJsonObject().toString())
    }

    fun getExplore(): List<MoodGenreSection>? {
        exploreMemoryCache?.let { return it }
        val sp = prefs ?: return null
        val raw = sp.getString(KEY_EXPLORE, null) ?: return null
        return runCatching {
            val arr = json.parseToJsonElement(raw).jsonArray
            val list = arr.mapNotNull { el ->
                el.takeIf { it !is JsonNull }?.let { runCatching { moodGenreSectionFromJson(it.jsonObject) }.getOrNull() }
            }
            exploreMemoryCache = list
            list
        }.getOrNull()
    }

    fun saveExplore(sections: List<MoodGenreSection>) {
        exploreMemoryCache = sections
        val w = writer ?: return
        val array = buildJsonArray {
            sections.forEach { add(it.toJsonObject()) }
        }
        w.put(KEY_EXPLORE, array.toString())
    }

    fun getMoodGenreShelves(key: String): List<HomeShelf>? {
        moodGenreMemoryCache[key]?.let { return it }
        val sp = prefs ?: return null
        val raw = sp.getString(KEY_MOOD_GENRE_PREFIX + key, null) ?: return null
        return runCatching {
            val arr = json.parseToJsonElement(raw).jsonArray
            val list = arr.mapNotNull { el ->
                el.takeIf { it !is JsonNull }?.let { runCatching { homeShelfFromJson(it.jsonObject) }.getOrNull() }
            }
            moodGenreMemoryCache[key] = list
            list
        }.getOrNull()
    }

    fun saveMoodGenreShelves(key: String, shelves: List<HomeShelf>) {
        moodGenreMemoryCache[key] = shelves
        val w = writer ?: return
        val array = buildJsonArray {
            shelves.forEach { add(it.toJsonObject()) }
        }
        // Only the last few are kept: every genre ever opened would otherwise
        // stay on disk for good.
        val dropped = synchronized(moodGenreIndex) {
            moodGenreIndex.remove(key)
            moodGenreIndex.add(key)
            buildList { while (moodGenreIndex.size > MAX_MOOD_GENRES) add(moodGenreIndex.removeAt(0)) }
        }
        dropped.forEach { moodGenreMemoryCache.remove(it) }
        val index = buildJsonArray { synchronized(moodGenreIndex) { moodGenreIndex.forEach { add(it) } } }
        w.write {
            putString(KEY_MOOD_GENRE_PREFIX + key, array.toString())
            dropped.forEach { remove(KEY_MOOD_GENRE_PREFIX + it) }
            putString(KEY_MOOD_GENRE_INDEX, index.toString())
        }
    }

    fun areShelvesEqual(a: List<HomeShelf>, b: List<HomeShelf>): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) {
            val s1 = a[i]
            val s2 = b[i]
            if (s1.title != s2.title || s1.subtitle != s2.subtitle || s1.layout != s2.layout) return false
            if (s1.items.size != s2.items.size) return false
            for (j in s1.items.indices) {
                val it1 = s1.items[j]
                val it2 = s2.items[j]
                if (it1.videoId != it2.videoId ||
                    it1.browseId != it2.browseId ||
                    it1.title != it2.title ||
                    it1.subtitle != it2.subtitle
                ) {
                    return false
                }
            }
        }
        return true
    }

    fun areExploreSectionsEqual(a: List<MoodGenreSection>, b: List<MoodGenreSection>): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) {
            val sec1 = a[i]
            val sec2 = b[i]
            if (sec1.title != sec2.title) return false
            if (sec1.items.size != sec2.items.size) return false
            for (j in sec1.items.indices) {
                val it1 = sec1.items[j]
                val it2 = sec2.items[j]
                if (it1.browseId != it2.browseId ||
                    it1.params != it2.params ||
                    it1.title != it2.title
                ) {
                    return false
                }
            }
        }
        return true
    }

    fun clear() {
        homeMemoryCache.clear()
        exploreMemoryCache = null
        moodGenreMemoryCache.clear()
        synchronized(moodGenreIndex) { moodGenreIndex.clear() }
        writer?.write {
            clear()
            putInt(CacheSchema.KEY, SCHEMA_VERSION)
        }
    }

    private const val TAG = "BitChord"

    // JSON serialization functions
    fun ShelfItem.toJsonObject(): JsonObject = buildJsonObject {
        put("title", title)
        put("subtitle", subtitle)
        thumbnailUrl?.let { put("thumbnailUrl", it) }
        videoId?.let { put("videoId", it) }
        browseId?.let { put("browseId", it) }
        put("isVideo", isVideo)
    }

    fun shelfItemFromJson(obj: JsonObject): ShelfItem = ShelfItem(
        title = obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        subtitle = obj["subtitle"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        thumbnailUrl = obj["thumbnailUrl"]?.jsonPrimitive?.contentOrNull,
        videoId = obj["videoId"]?.jsonPrimitive?.contentOrNull,
        browseId = obj["browseId"]?.jsonPrimitive?.contentOrNull,
        isVideo = obj["isVideo"]?.jsonPrimitive?.booleanOrNull ?: false,
    )

    fun HomeShelf.toJsonObject(): JsonObject = buildJsonObject {
        put("title", title)
        put("subtitle", subtitle)
        moreBrowseId?.let { put("moreBrowseId", it) }
        moreParams?.let { put("moreParams", it) }
        put("layout", layout.name)
        putJsonArray("items") {
            items.forEach { add(it.toJsonObject()) }
        }
    }

    fun homeShelfFromJson(obj: JsonObject): HomeShelf = HomeShelf(
        title = obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        subtitle = obj["subtitle"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        moreBrowseId = obj["moreBrowseId"]?.jsonPrimitive?.contentOrNull,
        moreParams = obj["moreParams"]?.jsonPrimitive?.contentOrNull,
        layout = runCatching { ShelfLayout.valueOf(obj["layout"]?.jsonPrimitive?.contentOrNull.orEmpty()) }
            .getOrDefault(ShelfLayout.CARDS),
        items = obj["items"]?.jsonArray?.mapNotNull { el ->
            el.takeIf { it !is JsonNull }?.let { runCatching { shelfItemFromJson(it.jsonObject) }.getOrNull() }
        }.orEmpty(),
    )

    fun MoodGenre.toJsonObject(): JsonObject = buildJsonObject {
        put("title", title)
        put("browseId", browseId)
        params?.let { put("params", it) }
        thumbnailUrl?.let { put("thumbnailUrl", it) }
        stripeColor?.let { put("stripeColor", it) }
    }

    fun moodGenreFromJson(obj: JsonObject): MoodGenre = MoodGenre(
        title = obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        browseId = obj["browseId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        params = obj["params"]?.jsonPrimitive?.contentOrNull,
        thumbnailUrl = obj["thumbnailUrl"]?.jsonPrimitive?.contentOrNull,
        stripeColor = obj["stripeColor"]?.jsonPrimitive?.longOrNull,
    )

    fun MoodGenreSection.toJsonObject(): JsonObject = buildJsonObject {
        put("title", title)
        putJsonArray("items") {
            items.forEach { add(it.toJsonObject()) }
        }
    }

    fun moodGenreSectionFromJson(obj: JsonObject): MoodGenreSection = MoodGenreSection(
        title = obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        items = obj["items"]?.jsonArray?.mapNotNull { el ->
            el.takeIf { it !is JsonNull }?.let { runCatching { moodGenreFromJson(it.jsonObject) }.getOrNull() }
        }.orEmpty(),
    )

    fun CachedHomeFeed.toJsonObject(): JsonObject = buildJsonObject {
        listenerKey?.let { put("listenerKey", it) }
        continuation?.let { put("continuation", it) }
        put("timestamp", timestamp)
        putJsonArray("shelves") {
            shelves.forEach { add(it.toJsonObject()) }
        }
    }

    fun cachedHomeFeedFromJson(obj: JsonObject): CachedHomeFeed = CachedHomeFeed(
        listenerKey = obj["listenerKey"]?.jsonPrimitive?.contentOrNull,
        continuation = obj["continuation"]?.jsonPrimitive?.contentOrNull,
        timestamp = obj["timestamp"]?.jsonPrimitive?.longOrNull ?: 0L,
        shelves = obj["shelves"]?.jsonArray?.mapNotNull { el ->
            el.takeIf { it !is JsonNull }?.let { runCatching { homeShelfFromJson(it.jsonObject) }.getOrNull() }
        }.orEmpty(),
    )
}
