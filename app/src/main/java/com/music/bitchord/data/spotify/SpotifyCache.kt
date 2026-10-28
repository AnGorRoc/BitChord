package com.music.bitchord.data.spotify

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.model.SPOTIFY_MISSING_PREFIX
import com.music.bitchord.data.model.SPOTIFY_PENDING_PREFIX
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.isUnresolvedSpotify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
    private const val MAX_CACHED_PLAYLISTS = 50
    private const val MAX_TRACK_MATCHES = 3000

    private var prefs: SharedPreferences? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val playlistMemoryCache = ConcurrentHashMap<String, CachedSpotifyPlaylist>()
    private val trackMatchMemoryCache = ConcurrentHashMap<String, Song>()
    @Volatile
    private var playlistsMemoryCache: List<SpotifyPlaylist>? = null
    private val cachedPlaylistIndex = mutableListOf<String>()

    fun init(context: Context) {
        if (prefs != null) return
        val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = sp
        loadInitialData(sp)
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
                            trackMatchMemoryCache[trackId] = song
                        }
                    }
                }
            }
        }
    }

    fun getPlaylist(playlistId: String): CachedSpotifyPlaylist? {
        playlistMemoryCache[playlistId]?.let { return it }

        prefs?.getString(KEY_PLAYLIST_PREFIX + playlistId, null)?.let { raw ->
            runCatching {
                val parsed = cachedPlaylistFromJson(json.parseToJsonElement(raw).jsonObject)
                playlistMemoryCache[playlistId] = parsed
                parsed.trackIds.zip(parsed.songs).forEach { (id, song) ->
                    if (!song.isUnresolvedSpotify) {
                        trackMatchMemoryCache[id] = song
                    }
                }
                return parsed
            }
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
            local.spotifyTrackIds.zip(local.songs).forEach { (id, song) ->
                if (!song.isUnresolvedSpotify) {
                    trackMatchMemoryCache[id] = song
                }
            }
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
        val trackIds = if (tracks.isNotEmpty()) {
            tracks.map { it.id }
        } else {
            songs.map { it.videoId.removePrefix(SPOTIFY_MISSING_PREFIX).removePrefix(SPOTIFY_PENDING_PREFIX) }
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

        trackIds.zip(songs).forEach { (id, song) ->
            if (!song.isUnresolvedSpotify) {
                trackMatchMemoryCache[id] = song
            }
        }

        val sp = prefs ?: return
        scope.launch {
            runCatching {
                val jsonString = cached.toJsonObject().toString()
                val editor = sp.edit()
                editor.putString(KEY_PLAYLIST_PREFIX + playlistId, jsonString)

                val toRemove = synchronized(cachedPlaylistIndex) {
                    cachedPlaylistIndex.remove(playlistId)
                    cachedPlaylistIndex.add(playlistId)
                    val excess = mutableListOf<String>()
                    while (cachedPlaylistIndex.size > MAX_CACHED_PLAYLISTS) {
                        excess.add(cachedPlaylistIndex.removeAt(0))
                    }
                    excess
                }
                for (oldId in toRemove) {
                    editor.remove(KEY_PLAYLIST_PREFIX + oldId)
                    playlistMemoryCache.remove(oldId)
                }
                editor.putString(KEY_INDEX, buildJsonArray {
                    synchronized(cachedPlaylistIndex) {
                        cachedPlaylistIndex.forEach { add(it) }
                    }
                }.toString())

                persistTrackMatches(editor)
                editor.apply()
            }
        }
    }

    fun touch(playlistId: String) {
        val existing = playlistMemoryCache[playlistId] ?: return
        val updated = existing.copy(lastCheckedMs = System.currentTimeMillis())
        playlistMemoryCache[playlistId] = updated
        val sp = prefs ?: return
        scope.launch {
            runCatching {
                sp.edit().putString(KEY_PLAYLIST_PREFIX + playlistId, updated.toJsonObject().toString()).apply()
            }
        }
    }

    fun updateCover(playlistId: String, coverUrl: String) {
        val existing = playlistMemoryCache[playlistId] ?: return
        if (existing.coverUrl == coverUrl) return
        val updated = existing.copy(coverUrl = coverUrl)
        playlistMemoryCache[playlistId] = updated
        val sp = prefs ?: return
        scope.launch {
            runCatching {
                sp.edit().putString(KEY_PLAYLIST_PREFIX + playlistId, updated.toJsonObject().toString()).apply()
            }
        }
    }

    fun getPlaylists(): List<SpotifyPlaylist>? {
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
        val sp = prefs ?: return
        scope.launch {
            runCatching {
                val array = buildJsonArray {
                    playlists.forEach { add(it.toJsonObject()) }
                }
                sp.edit().putString(KEY_PLAYLISTS, array.toString()).apply()
            }
        }
    }

    fun getMatch(spotifyTrackId: String): Song? {
        trackMatchMemoryCache[spotifyTrackId]?.let { return it }
        for (playlist in LocalPlaylistStore.playlists.value) {
            val idx = playlist.spotifyTrackIds.indexOf(spotifyTrackId)
            if (idx != -1 && idx < playlist.songs.size) {
                val match = playlist.songs[idx]
                if (!match.isUnresolvedSpotify) {
                    trackMatchMemoryCache[spotifyTrackId] = match
                    return match
                }
            }
        }
        return null
    }

    fun putMatch(spotifyTrackId: String, song: Song) {
        if (song.isUnresolvedSpotify) return
        trackMatchMemoryCache[spotifyTrackId] = song
    }

    fun putMatches(matches: Map<String, Song>) {
        matches.forEach { (id, song) ->
            if (!song.isUnresolvedSpotify) {
                trackMatchMemoryCache[id] = song
            }
        }
    }

    private fun persistTrackMatches(editor: SharedPreferences.Editor) {
        val snapshot = trackMatchMemoryCache.entries.take(MAX_TRACK_MATCHES)
        val obj = buildJsonObject {
            snapshot.forEach { (id, song) ->
                put(id, song.toJsonObject())
            }
        }
        editor.putString(KEY_TRACK_MATCHES, obj.toString())
    }

    fun clear() {
        playlistMemoryCache.clear()
        trackMatchMemoryCache.clear()
        playlistsMemoryCache = null
        synchronized(cachedPlaylistIndex) {
            cachedPlaylistIndex.clear()
        }
        prefs?.edit()?.clear()?.apply()
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
        val songs = obj["songs"]?.jsonArray?.mapNotNull { el ->
            el.takeIf { it !is JsonNull }?.let { runCatching { songFromJson(it.jsonObject) }.getOrNull() }
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
