package com.music.bitchord.data

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.MoodGenre
import com.music.bitchord.data.model.MoodGenreSection
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.ShelfLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
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
 */
object FeedCache {
    private const val PREFS_NAME = "bitchord_feed_cache"
    private const val KEY_HOME_PREFIX = "home_"
    private const val KEY_EXPLORE = "explore_sections"
    private const val KEY_MOOD_GENRE_PREFIX = "mood_genre_"

    private var prefs: SharedPreferences? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val homeMemoryCache = ConcurrentHashMap<String, CachedHomeFeed>()
    @Volatile
    private var exploreMemoryCache: List<MoodGenreSection>? = null
    private val moodGenreMemoryCache = ConcurrentHashMap<String, List<HomeShelf>>()

    fun init(context: Context) {
        if (prefs != null) return
        val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = sp
        loadExploreFromPrefs(sp)
    }

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
        homeMemoryCache[key]?.let { return it }

        val sp = prefs ?: return null
        val raw = sp.getString(key, null) ?: return null
        return runCatching {
            val parsed = cachedHomeFeedFromJson(json.parseToJsonElement(raw).jsonObject)
            homeMemoryCache[key] = parsed
            parsed
        }.getOrNull()
    }

    fun saveHome(listenerKey: String?, shelves: List<HomeShelf>, continuation: String?) {
        val key = homePrefKey(listenerKey)
        val feed = CachedHomeFeed(
            listenerKey = listenerKey,
            shelves = shelves,
            continuation = continuation,
            timestamp = System.currentTimeMillis(),
        )
        homeMemoryCache[key] = feed

        val sp = prefs ?: return
        scope.launch {
            runCatching {
                sp.edit().putString(key, feed.toJsonObject().toString()).apply()
            }
        }
    }

    fun touchHome(listenerKey: String?) {
        val key = homePrefKey(listenerKey)
        val existing = homeMemoryCache[key] ?: return
        val updated = existing.copy(timestamp = System.currentTimeMillis())
        homeMemoryCache[key] = updated
        val sp = prefs ?: return
        scope.launch {
            runCatching {
                sp.edit().putString(key, updated.toJsonObject().toString()).apply()
            }
        }
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
        val sp = prefs ?: return
        scope.launch {
            runCatching {
                val array = buildJsonArray {
                    sections.forEach { add(it.toJsonObject()) }
                }
                sp.edit().putString(KEY_EXPLORE, array.toString()).apply()
            }
        }
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
        val sp = prefs ?: return
        scope.launch {
            runCatching {
                val array = buildJsonArray {
                    shelves.forEach { add(it.toJsonObject()) }
                }
                sp.edit().putString(KEY_MOOD_GENRE_PREFIX + key, array.toString()).apply()
            }
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
        prefs?.edit()?.clear()?.apply()
    }

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
