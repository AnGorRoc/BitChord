package com.music.bitchord.desktop

import com.music.bitchord.data.model.PlaylistPrivacy
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.spotify.ExportLedger
import com.music.bitchord.data.spotify.ExportState
import com.music.bitchord.data.spotify.LedgerEntry
import com.music.bitchord.data.spotify.PlaylistUploader
import com.music.bitchord.data.spotify.YouTubeTarget
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal object DesktopPlaylistExporter {
    private const val KEY_LEDGER = "playlist_export_ledger_v1"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    private data class StoredLedgerEntry(
        val youtubeId: String,
        val exported: List<String>,
    )

    private class PersistenceLedger(private val persistence: DesktopPersistence) : ExportLedger {
        private fun readAll(): MutableMap<String, StoredLedgerEntry> {
            val raw = persistence.string(KEY_LEDGER)
            if (raw.isBlank()) return mutableMapOf()
            return runCatching {
                json.decodeFromString<Map<String, StoredLedgerEntry>>(raw).toMutableMap()
            }.getOrElse { mutableMapOf() }
        }

        private fun writeAll(map: Map<String, StoredLedgerEntry>) {
            persistence.saveString(KEY_LEDGER, json.encodeToString(map))
        }

        override fun get(localId: String): LedgerEntry? {
            val stored = readAll()[localId] ?: return null
            return LedgerEntry(stored.youtubeId, stored.exported.toSet())
        }

        override fun put(localId: String, entry: LedgerEntry) {
            val all = readAll()
            all[localId] = StoredLedgerEntry(entry.youtubeId, entry.exported.toList())
            writeAll(all)
        }

        override fun remove(localId: String) {
            val all = readAll()
            if (all.remove(localId) != null) writeAll(all)
        }
    }

    private val target = object : YouTubeTarget {
        override suspend fun create(title: String, videoIds: List<String>): String =
            DesktopSearchClient.createPlaylist(title, PlaylistPrivacy.PRIVATE, videoIds).getOrThrow()

        override suspend fun add(playlistId: String, videoIds: List<String>) {
            DesktopSearchClient.addToPlaylist(playlistId.removePrefix("VL"), videoIds).getOrThrow()
        }

        override suspend fun contents(playlistId: String): Set<String>? {
            val browseId = if (playlistId.startsWith("VL")) playlistId else "VL$playlistId"
            return DesktopSearchClient.playlistEntries(browseId).getOrNull()?.mapTo(HashSet()) { it.videoId }
        }
    }

    suspend fun export(
        persistence: DesktopPersistence,
        localId: String,
        title: String,
        songs: List<Song>,
        onState: (ExportState) -> Unit,
    ): ExportState {
        val uploader = PlaylistUploader(target = target, ledger = PersistenceLedger(persistence))
        return uploader.upload(
            localId = localId,
            title = title,
            songIds = songs.map { it.videoId },
            report = onState,
        )
    }
}
