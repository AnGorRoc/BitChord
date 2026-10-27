package com.music.bitchord.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import org.jetbrains.compose.resources.painterResource
import com.music.bitchord.sharedui.resources.spotify_logo
import com.music.bitchord.sharedui.resources.Res
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.spotify.LocalPlaylistStore
import com.music.bitchord.data.spotify.SpotifyLibrary
import com.music.bitchord.data.spotify.SpotifyPlaylist
import com.music.bitchord.data.spotify.SpotifySync
import com.music.bitchord.data.spotify.SpotifySyncState
import com.music.bitchord.data.spotify.SpotifyTrack

/**
 * The signed-in Spotify account's playlists. A tap opens one as an ordinary
 * playlist page ([onOpenPlaylist]) — this screen is only the way in.
 */
@Composable
fun SpotifyLibraryScreen(
    onOpenPlaylist: (SpotifyPlaylist) -> Unit,
    onOpenLogin: () -> Unit,
    onOpenTokenSetup: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val cookie by AppSettings.spotifySpdcToken.collectAsStateWithLifecycle()
    var playlists by remember { mutableStateOf<List<SpotifyPlaylist>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(cookie) {
        if (cookie.isBlank()) {
            playlists = emptyList()
            error = null
            return@LaunchedEffect
        }
        loading = true
        error = null
        runCatching { SpotifyLibrary.playlists() }
            .onSuccess { playlists = it }
            .onFailure { error = it.message }
        loading = false
    }

    val autoSync by AppSettings.spotifyAutoSync.collectAsStateWithLifecycle()
    val syncState by SpotifySync.state.collectAsStateWithLifecycle()
    val imported by LocalPlaylistStore.playlists.collectAsStateWithLifecycle()
    val importedIds = remember(imported) { imported.mapNotNull { it.spotifyId }.toSet() }

    // Once the list is in, a library that is kept up to date is brought up to date.
    LaunchedEffect(playlists, autoSync) {
        if (autoSync && playlists.isNotEmpty()) SpotifySync.start(playlists)
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 14.dp),
            ) {
                Icon(
                    painter = painterResource(Res.drawable.spotify_logo),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(38.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.spotify),
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
        if (cookie.isBlank()) {
            item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Text(
                        text = stringResource(R.string.spotify_connect_subtitle),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onOpenLogin) {
                        Text(stringResource(R.string.spotify_sign_in))
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onOpenTokenSetup) {
                        Text(stringResource(R.string.spotify_use_spdc_token))
                    }
                }
            }
        } else {
            if (loading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
            error?.let { message ->
                item {
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
            }
            if (playlists.isNotEmpty()) {
                item {
                    SyncPanel(
                        state = syncState,
                        anyImported = importedIds.isNotEmpty(),
                        autoSync = autoSync,
                        onSync = { SpotifySync.start(playlists) },
                        onAutoSync = AppSettings::setSpotifyAutoSync,
                    )
                }
            }
            items(playlists, key = { it.id }) { item ->
                val isImported = item.id in importedIds
                LibraryRow(
                    title = item.name,
                    subtitle = item.owner.orEmpty(),
                    imageUrl = item.imageUrl,
                    onClick = { onOpenPlaylist(item) },
                    imported = isImported,
                    syncing = syncState is SpotifySyncState.Running,
                    onImport = { SpotifySync.start(listOf(item)) },
                )
            }
        }
    }
}

/** Import-everything button, its progress or result, and the keep-up-to-date switch. */
@Composable
private fun SyncPanel(
    state: SpotifySyncState,
    anyImported: Boolean,
    autoSync: Boolean,
    onSync: () -> Unit,
    onAutoSync: (Boolean) -> Unit,
) {
    val running = state as? SpotifySyncState.Running
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
        if (!anyImported && state is SpotifySyncState.Idle) {
            Text(
                text = stringResource(R.string.spotify_sync_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
        }
        Button(onClick = onSync, enabled = running == null) {
            if (running != null) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(stringResource(if (anyImported) R.string.spotify_sync_update else R.string.spotify_sync_all))
        }
        val status = when (state) {
            is SpotifySyncState.Running -> stringResource(
                R.string.spotify_sync_running, state.index, state.total, state.playlist,
            )
            is SpotifySyncState.Done -> stringResource(
                R.string.spotify_sync_done, state.playlists, state.added, state.unmatched,
            )
            is SpotifySyncState.Failed -> stringResource(R.string.spotify_sync_failed, state.message)
            SpotifySyncState.Idle -> null
        }
        if (status != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium,
                color = if (state is SpotifySyncState.Failed) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.spotify_sync_auto),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = stringResource(R.string.spotify_sync_auto_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = autoSync, onCheckedChange = onAutoSync)
        }
    }
}

@Composable
private fun LibraryRow(
    title: String,
    subtitle: String,
    imageUrl: String?,
    onClick: () -> Unit,
    imported: Boolean,
    syncing: Boolean,
    onImport: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AsyncImage(
            model = imageUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (imported) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = stringResource(R.string.spotify_sync_imported),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
        } else {
            IconButton(onClick = onImport, enabled = !syncing) {
                Icon(
                    imageVector = Icons.Rounded.Download,
                    contentDescription = stringResource(R.string.spotify_sync_playlist),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
