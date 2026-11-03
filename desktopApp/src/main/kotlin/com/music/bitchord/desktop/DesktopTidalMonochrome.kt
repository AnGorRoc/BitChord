package com.music.bitchord.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.ArtistRef
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.MonochromeGate
import com.music.bitchord.data.sources.SkipPace
import com.music.bitchord.data.sources.SourceTrackKeys
import com.music.bitchord.data.sources.module.SharedCalls
import com.music.bitchord.data.tidal.TidalApi
import com.music.bitchord.data.tidal.TidalInstances
import kotlinx.coroutines.launch

/**
 * Desktop adapter for Hi-Res Lossless (`TidalApi` + `TidalInstances`), matching
 * Android's `TidalSource`.
 */
internal class DesktopTidalSource(
    val config: DesktopSourceConfig,
    private val endpointsProvider: suspend () -> List<String> = { TidalInstances.endpoints(config.baseUrl) },
) {
    val id: String get() = config.id
    val kind: DesktopSourceKind = DesktopSourceKind.TIDAL

    private val sharedSearch = SharedCalls<List<Song>>()
    private val sharedStream = SharedCalls<DesktopStream>()

    private suspend fun pool(): List<String> = endpointsProvider()

    suspend fun search(query: String, limit: Int = 20): Result<List<Song>> = runCatching {
        if (SkipPace.shared.isBrowsing()) {
            TrackLog.d(TAG, "tidal search deferred — rapid skipping")
            return@runCatching emptyList()
        }
        val trimmed = query.trim()
        val key = "${trimmed.lowercase()}|$limit"
        sharedSearch.run(key) {
            val urls = pool()
            if (urls.isEmpty()) error("No lossless instances available")
            for (endpoint in urls) {
                val memo = TidalApi.knownProtocol(endpoint)
                if (memo != TidalApi.Protocol.HIFI) {
                    val tracks = runCatching { TidalApi.searchTracks(endpoint, trimmed, limit) }.getOrNull()
                    if (tracks != null) {
                        TidalApi.noteProtocol(endpoint, TidalApi.Protocol.TRACKS)
                        if (tracks.isNotEmpty()) {
                            return@run tracks.take(limit).map { it.toSong(endpoint) }
                        }
                    }
                }
                if (memo != TidalApi.Protocol.TRACKS) {
                    val hifi = runCatching { TidalApi.search(endpoint, trimmed, limit) }.getOrNull()
                    if (hifi != null) {
                        TidalApi.noteProtocol(endpoint, TidalApi.Protocol.HIFI)
                        if (hifi.isNotEmpty()) {
                            return@run hifi.take(limit).map { it.toSong(endpoint) }
                        }
                    }
                }
            }
            emptyList()
        }
    }

    private fun TidalApi.TidalTrack.toSong(endpoint: String): Song {
        val packed = TidalApi.packId(protocol, id)
        TidalApi.memoizeOrigin(packed, endpoint, durationSec)
        val coverUrl = artDirectUrl ?: coverSlug?.let { TidalApi.coverUrl(it) }
        val duration = durationSec?.let { formatDuration(it) }
        return Song(
            videoId = SourceTrackKeys.forSource(id, packed),
            title = title,
            artist = artist,
            albumName = album,
            thumbnailUrl = coverUrl,
            durationText = duration,
            isExplicit = explicit,
            sourceQuality = "FLAC",
        )
    }

    suspend fun resolveStream(trackKey: String): Result<DesktopStream> = runCatching {
        sharedStream.run(trackKey) {
            val (protocol, id) = TidalApi.unpackId(trackKey)
            if (protocol == TidalApi.Protocol.TRACKS) {
                val origin = TidalApi.originOf(trackKey)
                val base = origin?.base
                    ?: pool().firstOrNull()
                    ?: error("No lossless provider configured")
                val url = TidalApi.tracksStreamUrl(base, id)
                DesktopStream(
                    url = url,
                    format = DesktopStreamFormat(
                        codec = "flac",
                        bitDepth = 16,
                        sampleRateHz = 44_100,
                    ),
                    sourceId = this.id,
                    isDolbyAtmos = false,
                    durationSec = origin?.durationSec,
                )
            } else {
                val base = pool().firstOrNull() ?: error("No lossless provider configured")
                val stream = TidalApi.streamUrl(base, id, TidalApi.LOSSLESS_TIERS)
                    ?: error("Lossless stream not available")
                DesktopStream(
                    url = stream.url,
                    format = DesktopStreamFormat(
                        codec = "flac",
                        bitDepth = 16,
                        sampleRateHz = 44_100,
                    ),
                    sourceId = this.id,
                    isDolbyAtmos = false,
                )
            }
        }
    }

    companion object {
        private const val TAG = "BitChord"

        private fun formatDuration(seconds: Int): String {
            val m = seconds / 60
            val s = seconds % 60
            return "$m:${s.toString().padStart(2, '0')}"
        }
    }
}

/**
 * Desktop adapter for Monochrome (`https://tracks.monochrome.st`), matching
 * Android's `MonochromeSource`.
 */
internal class DesktopMonochromeSource(
    val config: DesktopSourceConfig,
) {
    val id: String get() = config.id
    val kind: DesktopSourceKind = DesktopSourceKind.MONOCHROME

    private val baseUrl: String
        get() = TidalApi.normalize(config.baseUrl) ?: DEFAULT_URL

    suspend fun search(query: String, limit: Int = 20): Result<List<Song>> = runCatching {
        if (SkipPace.shared.isBrowsing()) return@runCatching emptyList()
        if (MonochromeGate.shared.isResting()) return@runCatching emptyList()
        val tracks = TidalApi.searchTracks(baseUrl, query, limit) ?: return@runCatching emptyList()
        tracks.map { t ->
            val coverUrl = t.artDirectUrl ?: t.coverSlug?.let { TidalApi.coverUrl(it) }
            val duration = t.durationSec?.let { formatDuration(it) }
            Song(
                videoId = SourceTrackKeys.forSource(id, t.id),
                title = t.title,
                artist = t.artist,
                albumName = t.album,
                thumbnailUrl = coverUrl,
                durationText = duration,
                isExplicit = t.explicit,
                sourceQuality = "FLAC",
            )
        }
    }

    suspend fun resolveStream(trackKey: String): Result<DesktopStream> = runCatching {
        if (MonochromeGate.shared.isResting()) {
            error("Monochrome rate-limited; resting")
        }
        val url = TidalApi.tracksStreamUrl(baseUrl, trackKey)
        DesktopStream(
            url = url,
            format = DesktopStreamFormat(
                codec = "flac",
                bitDepth = 16,
                sampleRateHz = 44_100,
            ),
            sourceId = id,
            isDolbyAtmos = false,
        )
    }

    companion object {
        const val DEFAULT_URL = "https://tracks.monochrome.st"

        private fun formatDuration(seconds: Int): String {
            val m = seconds / 60
            val s = seconds % 60
            return "$m:${s.toString().padStart(2, '0')}"
        }
    }
}

@Composable
internal fun DesktopTidalInstancesSection() {
    val scope = rememberCoroutineScope()
    val ratings by TidalInstances.ratings.collectAsState()
    val manual by TidalInstances.manual.collectAsState()
    var newInstanceUrl by remember { mutableStateOf("") }
    var refreshing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (ratings.isEmpty()) {
            TidalInstances.refreshRatings()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, top = 4.dp, bottom = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Lossless Provider Instances",
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.9f),
            )
            TextButton(
                onClick = {
                    refreshing = true
                    scope.launch {
                        TidalInstances.refreshRatings(force = true)
                        refreshing = false
                    }
                },
                enabled = !refreshing,
            ) {
                Text(if (refreshing) "Testing..." else "Test latency", fontSize = 12.sp)
            }
        }

        // Ratings list
        ratings.forEach { instance ->
            val isManual = manual.any { TidalApi.normalize(it.url) == TidalApi.normalize(instance.url) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        instance.label,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                    )
                    Text(
                        instance.url,
                        fontSize = 10.sp,
                        color = Color.Gray,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (instance.isHealthy) "${instance.pingMs} ms" else "Offline",
                        fontSize = 11.sp,
                        color = if (instance.isHealthy) Color(0xFF4CAF50) else Color(0xFFE57373),
                        fontWeight = FontWeight.Medium,
                    )
                    if (isManual) {
                        Spacer(Modifier.width(6.dp))
                        IconButton(
                            onClick = { TidalInstances.removeManual(instance.url) },
                            modifier = Modifier.size(24.dp),
                        ) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = "Remove",
                                tint = Color.Gray,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        // Add manual instance input
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = newInstanceUrl,
                onValueChange = { newInstanceUrl = it },
                placeholder = { Text("Add custom provider URL...", fontSize = 11.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    if (newInstanceUrl.isNotBlank()) {
                        val added = TidalInstances.addManual(newInstanceUrl.trim())
                        if (added) {
                            newInstanceUrl = ""
                            scope.launch { TidalInstances.refreshRatings(force = true) }
                        }
                    }
                },
                enabled = newInstanceUrl.isNotBlank(),
            ) {
                Text("Add", fontSize = 12.sp)
            }
        }
    }
}
