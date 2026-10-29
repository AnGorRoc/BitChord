package com.music.bitchord.data.spotify

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class LocalPlaylist(
    val id: String,
    val title: String,
    val songs: List<Song>,
    /** The Spotify playlist this one mirrors, or null for a playlist made here. */
    val spotifyId: String? = null,
    /** Spotify track ids parallel to [songs]; the matches a re-sync does not need to look up again. */
    val spotifyTrackIds: List<String> = emptyList(),
) {
    val browseId: String get() = "local:playlist:$id"

    /**
     * The songs this playlist holds against the Spotify track each one matched,
     * for a re-sync to reuse. Empty when the two lists do not line up, as after
     * a partly unreadable save: pairing them anyway would attach tracks to the
     * wrong songs.
     */
    fun matchedSongs(): Map<String, Song> =
        if (spotifyTrackIds.size == songs.size) spotifyTrackIds.zip(songs).toMap() else emptyMap()
}

object LocalPlaylistStore {
    private const val PREF_NAME = "bitchord_local_playlists"
    private const val KEY_PLAYLISTS = "playlists_json"

    private lateinit var prefs: SharedPreferences

    private val _playlists = MutableStateFlow<List<LocalPlaylist>>(emptyList())
    val playlists = _playlists.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        load()
    }

    private fun load() {
        if (!this::prefs.isInitialized) return
        val raw = prefs.getString(KEY_PLAYLISTS, null) ?: return
        runCatching {
            val list = mutableListOf<LocalPlaylist>()
            val jsonArray = JSONArray(raw)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val id = obj.getString("id")
                val title = obj.getString("title")
                val songsArray = obj.getJSONArray("songs")
                val songs = mutableListOf<Song>()
                for (j in 0 until songsArray.length()) {
                    val sObj = songsArray.getJSONObject(j)
                    val song = Song(
                        videoId = sObj.optString("videoId", ""),
                        title = sObj.optString("title", ""),
                        artist = sObj.optString("artist", ""),
                        thumbnailUrl = sObj.optString("thumbnailUrl", null).takeIf { !it.isNullOrBlank() && it != "null" },
                        durationText = sObj.optString("durationText", null).takeIf { !it.isNullOrBlank() && it != "null" },
                        artistId = sObj.optString("artistId", null).takeIf { !it.isNullOrBlank() && it != "null" },
                        albumId = sObj.optString("albumId", null).takeIf { !it.isNullOrBlank() && it != "null" },
                        albumName = sObj.optString("albumName", null).takeIf { !it.isNullOrBlank() && it != "null" },
                        isVideo = sObj.optBoolean("isVideo", false),
                    )
                    songs.add(song)
                }
                val trackIds = obj.optJSONArray("spotifyTrackIds")?.let { ids ->
                    List(ids.length()) { ids.optString(it, "") }
                }.orEmpty()
                list.add(
                    LocalPlaylist(
                        id = id,
                        title = title,
                        songs = songs,
                        spotifyId = obj.optString("spotifyId", "").takeIf { it.isNotBlank() },
                        spotifyTrackIds = trackIds,
                    ),
                )
            }
            _playlists.value = list
        }
    }

    /**
     * Changes the list and writes the result away as one step. Held so that two
     * writers — a sync on IO and an edit from the screen — cannot each read the
     * list, change their copy and have the later write undo the earlier one.
     */
    @Synchronized
    private fun mutate(change: (List<LocalPlaylist>) -> List<LocalPlaylist>) {
        val updated = change(_playlists.value)
        _playlists.value = updated
        persist(updated)
    }

    private fun newId() = "sp_local_" + UUID.randomUUID().toString().replace("-", "")

    fun savePlaylist(title: String, songs: List<Song>): LocalPlaylist {
        val playlist = LocalPlaylist(id = newId(), title = title, songs = songs)
        mutate { listOf(playlist) + it }
        return playlist
    }

    fun findBySpotifyId(spotifyId: String): LocalPlaylist? =
        _playlists.value.firstOrNull { it.spotifyId == spotifyId }

    /**
     * Writes the copy of a Spotify playlist, replacing the one a previous sync
     * made so the playlist keeps its place and id in the library.
     */
    fun saveSpotifyPlaylist(
        spotifyId: String,
        title: String,
        songs: List<Song>,
        trackIds: List<String>,
    ): LocalPlaylist {
        var saved: LocalPlaylist? = null
        mutate { current ->
            val existing = current.firstOrNull { it.spotifyId == spotifyId }
            val playlist = LocalPlaylist(
                id = existing?.id ?: newId(),
                title = title,
                songs = songs,
                spotifyId = spotifyId,
                spotifyTrackIds = trackIds,
            )
            saved = playlist
            if (existing == null) listOf(playlist) + current
            else current.map { if (it.id == existing.id) playlist else it }
        }
        return saved!!
    }

    fun deletePlaylist(id: String) {
        val cleanId = id.removePrefix("local:playlist:")
        val gone = _playlists.value.filter { it.id == id || it.browseId == id || it.id == cleanId }
        mutate { list -> list.filterNot { it.id == id || it.browseId == id || it.id == cleanId } }
        // What was exported from it is no longer anyone's to carry on from.
        gone.forEach { YouTubeExportLedger.remove(it.id) }
    }

    fun renamePlaylist(id: String, newTitle: String) {
        val cleanId = id.removePrefix("local:playlist:").removePrefix("VL")
        mutate { list ->
            list.map {
                if (it.id == cleanId || it.id == id || it.browseId == id) it.copy(title = newTitle) else it
            }
        }
    }

    fun addSongs(id: String, songsToAdd: List<Song>): Int {
        val cleanId = id.removePrefix("local:playlist:").removePrefix("VL")
        var addedCount = 0
        mutate { list -> list.map { playlist ->
            if (playlist.id == cleanId || playlist.id == id || playlist.browseId == id) {
                val existingIds = playlist.songs.mapTo(HashSet()) { it.videoId }
                val toAdd = songsToAdd.filter { it.videoId !in existingIds }
                addedCount = toAdd.size
                if (toAdd.isNotEmpty()) {
                    playlist.copy(songs = playlist.songs + toAdd)
                } else playlist
            } else playlist
        } }
        return addedCount
    }

    fun removeSong(id: String, videoId: String) {
        val cleanId = id.removePrefix("local:playlist:").removePrefix("VL")
        mutate { list -> list.map { playlist ->
            if (playlist.id == cleanId || playlist.id == id || playlist.browseId == id) {
                val idx = playlist.songs.indexOfFirst { it.videoId == videoId }
                if (idx >= 0) {
                    val newSongs = playlist.songs.toMutableList().apply { removeAt(idx) }
                    val newTrackIds = if (idx < playlist.spotifyTrackIds.size) {
                        playlist.spotifyTrackIds.toMutableList().apply { removeAt(idx) }
                    } else playlist.spotifyTrackIds
                    playlist.copy(songs = newSongs, spotifyTrackIds = newTrackIds)
                } else playlist
            } else playlist
        } }
    }

    fun getPlaylist(id: String): LocalPlaylist? {
        val cleanId = id.removePrefix("local:playlist:").removePrefix("VL")
        return _playlists.value.firstOrNull { it.id == cleanId || it.id == id || it.browseId == id }
    }

    private fun persist(list: List<LocalPlaylist>) {
        if (!this::prefs.isInitialized) return
        runCatching {
            val jsonArray = JSONArray()
            for (playlist in list) {
                val pObj = JSONObject()
                pObj.put("id", playlist.id)
                pObj.put("title", playlist.title)
                val songsArray = JSONArray()
                for (song in playlist.songs) {
                    val sObj = JSONObject()
                    sObj.put("videoId", song.videoId)
                    sObj.put("title", song.title)
                    sObj.put("artist", song.artist)
                    sObj.put("thumbnailUrl", song.thumbnailUrl)
                    sObj.put("durationText", song.durationText)
                    sObj.put("artistId", song.artistId)
                    sObj.put("albumId", song.albumId)
                    sObj.put("albumName", song.albumName)
                    sObj.put("isVideo", song.isVideo)
                    songsArray.put(sObj)
                }
                pObj.put("songs", songsArray)
                playlist.spotifyId?.let { pObj.put("spotifyId", it) }
                pObj.put("spotifyTrackIds", JSONArray(playlist.spotifyTrackIds))
                jsonArray.put(pObj)
            }
            prefs.edit().putString(KEY_PLAYLISTS, jsonArray.toString()).apply()
        }
    }
}
