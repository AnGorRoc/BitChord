package com.music.bitchord.data.spotify

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.CacheSchema
import com.music.bitchord.data.PrefsWriter
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.ArtistRef
import com.music.bitchord.data.model.SPOTIFY_MISSING_PREFIX
import com.music.bitchord.data.model.SPOTIFY_PENDING_PREFIX
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.isUnresolvedSpotify
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * A cached Spotify playlist, keeping both its Spotify track metadata and its
 * resolved YouTube Music songs, along with the timestamp of the last verification.
 */
data class CachedSpotifyPlaylist(
    val playlistId: String,
    val title: String,
    val coverUrl: String?,
    val trackIds: List<String>,
    val tracks: List<SpotifyTrack>,
    val songs: List<Song>,
    val lastCheckedMs: Long = System.currentTimeMillis(),
)

/**
 * Caches Spotify playlists, their resolved YouTube songs, and individual track
 * matches so that opening a Spotify playlist shows its songs instantly without
 * re-fetching and re-matching everything on every open.
 *
 * Checks Spotify in the background (stale-while-revalidate), updating songs only
 * if the playlist actually changed or gained new tracks.
 */
object SpotifyCache {
    private const val PREFS_NAME = "bitchord_spotify_cache"
    private const val KEY_PLAYLIST_PREFIX = "playlist_"
    private const val KEY_INDEX = "playlist_index"
    private const val KEY_PLAYLISTS = "playlists_library"
    private const val KEY_TRACK_MATCHES = "track_matches"
    private const val KEY_LAST_AUTO_SYNC = "last_auto_sync_ms"
    private const val TAG = "BitChord"

    /** Bumped whenever what is written here changes shape; see [CacheSchema]. */
    const val SCHEMA_VERSION = 2

    const val MAX_CACHED_PLAYLISTS = 50
    const val MAX_TRACK_MATCHES = 3000

    private var prefs: SharedPreferences? = null
    private var writer: PrefsWriter? = null
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val playlistMemoryCache = ConcurrentHashMap<String, CachedSpotifyPlaylist>()

    /**
     * Spotify track id to the song it matched, least recently used first and
     * bounded: it used to grow with every song ever opened, and was written
     * back by taking the first 3000 of a hash map, which kept an arbitrary
     * 3000 rather than the ones in use.
     */
    private val trackMatchMemoryCache: MutableMap<String, Song> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Song>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Song>) = size > MAX_TRACK_MATCHES
        },
    )

    /** Whether matches changed since they were last written, so a save does not rewrite thousands unchanged. */
    @Volatile
    private var matchesDirty = false

    @Volatile
    private var playlistsMemoryCache: List<SpotifyPlaylist>? = null
    private val cachedPlaylistIndex = mutableListOf<String>()

    /**
     * What is on disk, read the first time anything asks and not at start-up:
     * a few thousand matches parsed on the launch path is time spent before the
     * first frame for a screen most launches never open.
     */
    @Volatile
    private var loaded = false

    private fun ready() {
        if (loaded) return
        val sp = prefs ?: return
        synchronized(this) {
            if (loaded) return
            loadInitialData(sp)
            loaded = true
        }
    }

    fun init(context: Context) {
        if (prefs != null) return
        initWith(context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    internal fun initWith(sp: SharedPreferences, lane: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)) {
        CacheSchema.ensure(sp, SCHEMA_VERSION)
        playlistMemoryCache.clear()
        trackMatchMemoryCache.clear()
        playlistsMemoryCache = null
        synchronized(cachedPlaylistIndex) { cachedPlaylistIndex.clear() }
        matchesDirty = false
        prefs = sp
        writer = PrefsWriter(sp, lane)
        loaded = false
    }

    private fun loadInitialData(sp: SharedPreferences) {
        runCatching {
            sp.getString(KEY_INDEX, null)?.let { rawIndex ->
                val arr = json.parseToJsonElement(rawIndex).jsonArray
                synchronized(cachedPlaylistIndex) {
                    cachedPlaylistIndex.clear()
                    arr.forEach { el ->
                        el.jsonPrimitive.contentOrNull?.let { cachedPlaylistIndex.add(it) }
                    }
                }
            }

            sp.getString(KEY_PLAYLISTS, null)?.let { rawPlaylists ->
                val arr = json.parseToJsonElement(rawPlaylists).jsonArray
                playlistsMemoryCache = arr.mapNotNull { el ->
                    el.takeIf { it !is JsonNull }?.let { runCatching { spotifyPlaylistFromJson(it.jsonObject) }.getOrNull() }
                }
            }

            sp.getString(KEY_TRACK_MATCHES, null)?.let { rawMatches ->
                val obj = json.parseToJsonElement(rawMatches).jsonObject
                obj.forEach { (trackId, songElem) ->
                    songElem.takeIf { it !is JsonNull }?.let {
                        runCatching { songFromJson(it.jsonObject) }.getOrNull()?.let { song ->
                            trackMatchMemoryCache.putIfAbsent(trackId, song)
                        }
                    }
                }
            }
        }.onFailure { TrackLog.w(TAG, "spotify cache: unreadable on start: ${it.message}") }
    }

    fun getPlaylist(playlistId: String): CachedSpotifyPlaylist? {
        ready()
        playlistMemoryCache[playlistId]?.let { return it }

        prefs?.getString(KEY_PLAYLIST_PREFIX + playlistId, null)?.let { raw ->
            runCatching {
                val parsed = cachedPlaylistFromJson(json.parseToJsonElement(raw).jsonObject)
                // A copy whose songs no longer line up with its track ids would
                // attach each id to the wrong song; it is dropped and refetched.
                if (parsed.trackIds.size != parsed.songs.size) {
                    TrackLog.w(TAG, "spotify cache: $playlistId has ${parsed.songs.size} songs for ${parsed.trackIds.size} tracks; dropped")
                    writer?.remove(KEY_PLAYLIST_PREFIX + playlistId)
                } else {
                    playlistMemoryCache[playlistId] = parsed
                    rememberMatches(parsed.trackIds, parsed.songs)
                    return parsed
                }
            }.onFailure { TrackLog.w(TAG, "spotify cache: unreadable playlist $playlistId: ${it.message}") }
        }

        // Fallback to LocalPlaylistStore if previously imported/synced
        LocalPlaylistStore.findBySpotifyId(playlistId)?.let { local ->
            val cached = CachedSpotifyPlaylist(
                playlistId = playlistId,
                title = local.title,
                coverUrl = local.songs.firstOrNull { !it.thumbnailUrl.isNullOrBlank() }?.thumbnailUrl,
                trackIds = local.spotifyTrackIds,
                tracks = emptyList(),
                songs = local.songs,
                lastCheckedMs = 0L,
            )
            playlistMemoryCache[playlistId] = cached
            rememberMatches(local.matchedSongs().keys.toList(), local.matchedSongs().values.toList())
            return cached
        }

        return null
    }

    fun savePlaylist(
        playlistId: String,
        title: String,
        coverUrl: String?,
        tracks: List<SpotifyTrack>,
        songs: List<Song>,
    ) {
        ready()
        // With the Spotify tracks in hand their ids are the ids. Without them,
        // only a song still waiting on its match carries one; a resolved song's
        // id is YouTube's, and filing that under a Spotify track would make the
        // match table answer for a track it was never matched to.
        val trackIds = if (tracks.isNotEmpty()) {
            tracks.map { it.id }
        } else {
            songs.map {
                if (it.isUnresolvedSpotify) it.videoId.removePrefix(SPOTIFY_MISSING_PREFIX).removePrefix(SPOTIFY_PENDING_PREFIX) else ""
            }
        }
        val cached = CachedSpotifyPlaylist(
            playlistId = playlistId,
            title = title,
            coverUrl = coverUrl,
            trackIds = trackIds,
            tracks = tracks,
            songs = songs,
            lastCheckedMs = System.currentTimeMillis(),
        )
        playlistMemoryCache[playlistId] = cached
        rememberMatches(trackIds, songs)

        val w = writer ?: return
        val jsonString = cached.toJsonObject().toString()
        val toRemove = synchronized(cachedPlaylistIndex) {
            cachedPlaylistIndex.remove(playlistId)
            cachedPlaylistIndex.add(playlistId)
            buildList { while (cachedPlaylistIndex.size > MAX_CACHED_PLAYLISTS) add(cachedPlaylistIndex.removeAt(0)) }
        }
        toRemove.forEach { playlistMemoryCache.remove(it) }
        val index = buildJsonArray { synchronized(cachedPlaylistIndex) { cachedPlaylistIndex.forEach { add(it) } } }.toString()
        val matches = if (matchesDirty) matchesJson() else null
        w.write {
            putString(KEY_PLAYLIST_PREFIX + playlistId, jsonString)
            toRemove.forEach { remove(KEY_PLAYLIST_PREFIX + it) }
            putString(KEY_INDEX, index)
            matches?.let { putString(KEY_TRACK_MATCHES, it) }
        }
    }

    /** Files each resolved [songs] entry under the Spotify track id at its position in [trackIds]. */
    private fun rememberMatches(trackIds: List<String>, songs: List<Song>) {
        if (trackIds.size != songs.size) return
        trackIds.zip(songs).forEach { (id, song) ->
            if (id.isNotBlank() && !song.isUnresolvedSpotify) putMatch(id, song)
        }
    }

    fun touch(playlistId: String) {
        val existing = playlistMemoryCache[playlistId] ?: return
        val updated = existing.copy(lastCheckedMs = System.currentTimeMillis())
        playlistMemoryCache[playlistId] = updated
        writer?.put(KEY_PLAYLIST_PREFIX + playlistId, updated.toJsonObject().toString())
    }

    fun updateCover(playlistId: String, coverUrl: String) {
        val existing = playlistMemoryCache[playlistId] ?: return
        if (existing.coverUrl == coverUrl) return
        val updated = existing.copy(coverUrl = coverUrl)
        playlistMemoryCache[playlistId] = updated
        writer?.put(KEY_PLAYLIST_PREFIX + playlistId, updated.toJsonObject().toString())
    }

    fun getPlaylists(): List<SpotifyPlaylist>? {
        ready()
        playlistsMemoryCache?.let { return it }
        val sp = prefs ?: return null
        val raw = sp.getString(KEY_PLAYLISTS, null) ?: return null
        return runCatching {
            val arr = json.parseToJsonElement(raw).jsonArray
            val list = arr.mapNotNull { el ->
                el.takeIf { it !is JsonNull }?.let { runCatching { spotifyPlaylistFromJson(it.jsonObject) }.getOrNull() }
            }
            playlistsMemoryCache = list
            list
        }.getOrNull()
    }

    fun savePlaylists(playlists: List<SpotifyPlaylist>) {
        playlistsMemoryCache = playlists
        val w = writer ?: return
        val array = buildJsonArray {
            playlists.forEach { add(it.toJsonObject()) }
        }
        w.put(KEY_PLAYLISTS, array.toString())
    }

    fun getMatch(spotifyTrackId: String): Song? {
        ready()
        trackMatchMemoryCache[spotifyTrackId]?.let { return it }
        for (playlist in LocalPlaylistStore.playlists.value) {
            val match = playlist.matchedSongs()[spotifyTrackId] ?: continue
            if (!match.isUnresolvedSpotify) {
                putMatch(spotifyTrackId, match)
                return match
            }
        }
        return null
    }

    fun putMatch(spotifyTrackId: String, song: Song) {
        if (song.isUnresolvedSpotify) return
        if (trackMatchMemoryCache.put(spotifyTrackId, song) != song) matchesDirty = true
    }

    fun putMatches(matches: Map<String, Song>) {
        matches.forEach { (id, song) -> putMatch(id, song) }
    }

    /** The match table as written to disk, oldest first so a reload keeps the order of use. */
    private fun matchesJson(): String {
        val snapshot = synchronized(trackMatchMemoryCache) { trackMatchMemoryCache.entries.map { it.key to it.value } }
        matchesDirty = false
        return buildJsonObject {
            snapshot.forEach { (id, song) -> put(id, song.toJsonObject()) }
        }.toString()
    }

    /**
     * Whether an automatic library sync is due: none has run in [minIntervalMs].
     * The sync looks up every playlist, so opening the page ten times in a row
     * must not start it ten times.
     */
    fun autoSyncDue(minIntervalMs: Long, now: Long = System.currentTimeMillis()): Boolean {
        val last = prefs?.getLong(KEY_LAST_AUTO_SYNC, 0L) ?: 0L
        return now - last >= minIntervalMs
    }

    /** Records that an automatic sync was started at [now]. */
    fun markAutoSynced(now: Long = System.currentTimeMillis()) {
        writer?.write { putLong(KEY_LAST_AUTO_SYNC, now) }
    }

    fun clear() {
        playlistMemoryCache.clear()
        trackMatchMemoryCache.clear()
        matchesDirty = false
        playlistsMemoryCache = null
        synchronized(cachedPlaylistIndex) {
            cachedPlaylistIndex.clear()
        }
        writer?.write {
            clear()
            putInt(CacheSchema.KEY, SCHEMA_VERSION)
        }
    }

    // JSON serialization helpers
    fun CachedSpotifyPlaylist.toJsonObject(): JsonObject = buildJsonObject {
        put("playlistId", playlistId)
        put("title", title)
        coverUrl?.let { put("coverUrl", it) }
        putJsonArray("trackIds") { trackIds.forEach { add(it) } }
        putJsonArray("tracks") { tracks.forEach { add(it.toJsonObject()) } }
        putJsonArray("songs") { songs.forEach { add(it.toJsonObject()) } }
        put("lastCheckedMs", lastCheckedMs)
    }

    fun cachedPlaylistFromJson(obj: JsonObject): CachedSpotifyPlaylist {
        val trackIds = obj["trackIds"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        val tracks = obj["tracks"]?.jsonArray?.mapNotNull { el ->
            el.takeIf { it !is JsonNull }?.let { runCatching { spotifyTrackFromJson(it.jsonObject) }.getOrNull() }
        }.orEmpty()
        // One song for each track id, in place. An entry that cannot be read
        // becomes a song waiting on its match, not a gap that shifts every
        // later song onto the wrong track.
        val songs = obj["songs"]?.jsonArray?.mapIndexed { index, el ->
            el.takeIf { it !is JsonNull }?.let { runCatching { songFromJson(it.jsonObject) }.getOrNull() }
                ?: Song(
                    videoId = SPOTIFY_PENDING_PREFIX + trackIds.getOrElse(index) { "" },
                    title = tracks.getOrNull(index)?.title.orEmpty(),
                    artist = tracks.getOrNull(index)?.artist.orEmpty(),
                    thumbnailUrl = tracks.getOrNull(index)?.imageUrl,
                )
        }.orEmpty()
        return CachedSpotifyPlaylist(
            playlistId = obj["playlistId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            title = obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            coverUrl = obj["coverUrl"]?.jsonPrimitive?.contentOrNull,
            trackIds = trackIds,
            tracks = tracks,
            songs = songs,
            lastCheckedMs = obj["lastCheckedMs"]?.jsonPrimitive?.longOrNull ?: 0L,
        )
    }

    fun SpotifyTrack.toJsonObject(): JsonObject = buildJsonObject {
        put("id", id)
        put("title", title)
        put("artist", artist)
        album?.let { put("album", it) }
        put("durationMs", durationMs)
        imageUrl?.let { put("imageUrl", it) }
    }

    fun spotifyTrackFromJson(obj: JsonObject): SpotifyTrack = SpotifyTrack(
        id = obj["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        title = obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        artist = obj["artist"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        album = obj["album"]?.jsonPrimitive?.contentOrNull,
        durationMs = obj["durationMs"]?.jsonPrimitive?.intOrNull ?: 0,
        imageUrl = obj["imageUrl"]?.jsonPrimitive?.contentOrNull,
    )

    fun Song.toJsonObject(): JsonObject = buildJsonObject {
        put("videoId", videoId)
        put("title", title)
        put("artist", artist)
        thumbnailUrl?.let { put("thumbnailUrl", it) }
        durationText?.let { put("durationText", it) }
        artistId?.let { put("artistId", it) }
        albumId?.let { put("albumId", it) }
        albumName?.let { put("albumName", it) }
        put("isVideo", isVideo)
        put("isVideoOrigin", isVideoOrigin)
        if (artists.isNotEmpty()) {
            putJsonArray("artists") {
                artists.forEach { ref ->
                    add(buildJsonObject {
                        put("name", ref.name)
                        ref.browseId?.let { put("browseId", it) }
                    })
                }
            }
        }
    }

    fun songFromJson(obj: JsonObject): Song = Song(
        videoId = obj["videoId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        title = obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        artist = obj["artist"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        thumbnailUrl = obj["thumbnailUrl"]?.jsonPrimitive?.contentOrNull,
        durationText = obj["durationText"]?.jsonPrimitive?.contentOrNull,
        artistId = obj["artistId"]?.jsonPrimitive?.contentOrNull,
        albumId = obj["albumId"]?.jsonPrimitive?.contentOrNull,
        albumName = obj["albumName"]?.jsonPrimitive?.contentOrNull,
        isVideo = obj["isVideo"]?.jsonPrimitive?.booleanOrNull ?: false,
        isVideoOrigin = obj["isVideoOrigin"]?.jsonPrimitive?.booleanOrNull
            ?: (obj["isVideo"]?.jsonPrimitive?.booleanOrNull ?: false),
        artists = obj["artists"]?.jsonArray?.mapNotNull { el ->
            val ref = el.takeIf { it !is JsonNull }?.jsonObject ?: return@mapNotNull null
            val name = ref["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            ArtistRef(name, ref["browseId"]?.jsonPrimitive?.contentOrNull)
        }.orEmpty(),
    )

    fun SpotifyPlaylist.toJsonObject(): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        owner?.let { put("owner", it) }
        imageUrl?.let { put("imageUrl", it) }
    }

    fun spotifyPlaylistFromJson(obj: JsonObject): SpotifyPlaylist = SpotifyPlaylist(
        id = obj["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        owner = obj["owner"]?.jsonPrimitive?.contentOrNull,
        imageUrl = obj["imageUrl"]?.jsonPrimitive?.contentOrNull,
    )
}
