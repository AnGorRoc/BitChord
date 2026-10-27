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
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.music.bitchord.data.spotify.SyncErrorKind
import com.music.bitchord.data.spotify.SyncFailure
import com.music.bitchord.data.spotify.syncErrorKind

/** What the row of a playlist shows about its copy on this device. */
private enum class RowSync { Idle, Active, Imported, Failed }

/**
 * The signed-in Spotify account's playlists. A tap opens one as an ordinary
 * playlist page ([onOpenPlaylist]) — this screen is only the way in, and the
 * place the library is copied to this device from.
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
    var error by remember { mutableStateOf<Pair<SyncErrorKind, String>?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(cookie, reload) {
        if (cookie.isBlank()) {
            playlists = emptyList()
            error = null
            return@LaunchedEffect
        }
        loading = true
        error = null
        runCatching { SpotifyLibrary.playlists() }
            .onSuccess { playlists = it }
            .onFailure { error = syncErrorKind(it) to it.message.orEmpty() }
        loading = false
    }

    val autoSync by AppSettings.spotifyAutoSync.collectAsStateWithLifecycle()
    val syncState by SpotifySync.state.collectAsStateWithLifecycle()
    val imported by LocalPlaylistStore.playlists.collectAsStateWithLifecycle()
    val importedIds = remember(imported) { imported.mapNotNull { it.spotifyId }.toSet() }
    val failedIds = when (val state = syncState) {
        is SpotifySyncState.Running -> state.failures
        is SpotifySyncState.Finished -> state.failures
        else -> emptyList()
    }.map { it.playlist.id }.toSet()
    val activeId = (syncState as? SpotifySyncState.Running)?.playlist?.id

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
            error?.let { (kind, detail) ->
                item {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                        Text(
                            text = syncErrorText(kind, detail),
                            color = MaterialTheme.colorScheme.error,
                        )
                        Row {
                            TextButton(onClick = { reload++ }) {
                                Text(stringResource(R.string.spotify_sync_retry))
                            }
                            if (kind == SyncErrorKind.SIGNED_OUT) {
                                TextButton(onClick = onOpenLogin) {
                                    Text(stringResource(R.string.spotify_sign_in))
                                }
                            }
                        }
                    }
                }
            }
            if (playlists.isNotEmpty()) {
                item {
                    SyncPanel(
                        state = syncState,
                        anyImported = importedIds.isNotEmpty(),
                        autoSync = autoSync,
                        onSync = { SpotifySync.start(playlists) },
                        onCancel = SpotifySync::cancel,
                        onRetryFailed = SpotifySync::retryFailed,
                        onDismiss = SpotifySync::dismiss,
                        onSignIn = onOpenLogin,
                        onAutoSync = AppSettings::setSpotifyAutoSync,
                    )
                }
            }
            items(playlists, key = { it.id }) { item ->
                LibraryRow(
                    title = item.name,
                    subtitle = item.owner.orEmpty(),
                    imageUrl = item.imageUrl,
                    onClick = { onOpenPlaylist(item) },
                    sync = when {
                        item.id == activeId -> RowSync.Active
                        item.id in failedIds -> RowSync.Failed
                        item.id in importedIds -> RowSync.Imported
                        else -> RowSync.Idle
                    },
                    busy = activeId != null,
                    onImport = { SpotifySync.start(listOf(item)) },
                )
            }
        }
    }
}

@Composable
private fun syncErrorText(kind: SyncErrorKind, detail: String): String = when (kind) {
    SyncErrorKind.SIGNED_OUT -> stringResource(R.string.spotify_sync_error_signed_out)
    SyncErrorKind.NETWORK -> stringResource(R.string.spotify_sync_error_network)
    SyncErrorKind.OTHER -> stringResource(R.string.spotify_sync_error_other, detail)
}

/**
 * The import-everything button, and under it whatever the last run has to
 * say: how far it has got and a way to stop it, or how it ended, with the
 * playlists that failed named and a way to retry them.
 */
@Composable
private fun SyncPanel(
    state: SpotifySyncState,
    anyImported: Boolean,
    autoSync: Boolean,
    onSync: () -> Unit,
    onCancel: () -> Unit,
    onRetryFailed: () -> Unit,
    onDismiss: () -> Unit,
    onSignIn: () -> Unit,
    onAutoSync: (Boolean) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
        when (state) {
            SpotifySyncState.Idle -> {
                if (!anyImported) {
                    Text(
                        text = stringResource(R.string.spotify_sync_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                }
                SyncButton(anyImported, onSync)
            }
            is SpotifySyncState.Running -> RunningCard(state, onCancel)
            is SpotifySyncState.Finished -> FinishedCard(state, onRetryFailed, onDismiss)
            is SpotifySyncState.Aborted -> {
                ResultCard(isError = true) {
                    Text(
                        text = syncErrorText(state.kind, state.detail),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Row(Modifier.padding(top = 4.dp)) {
                        if (state.kind == SyncErrorKind.SIGNED_OUT) {
                            TextButton(onClick = onSignIn) { Text(stringResource(R.string.spotify_sign_in)) }
                        } else {
                            TextButton(onClick = onSync) { Text(stringResource(R.string.spotify_sync_retry)) }
                        }
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) }
                    }
                }
            }
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
private fun SyncButton(anyImported: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick) {
        Text(stringResource(if (anyImported) R.string.spotify_sync_update else R.string.spotify_sync_all))
    }
}

@Composable
private fun ResultCard(isError: Boolean, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) { content() }
    }
}

@Composable
private fun RunningCard(state: SpotifySyncState.Running, onCancel: () -> Unit) {
    ResultCard(isError = false) {
        Text(
            text = stringResource(R.string.spotify_sync_running),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = state.playlist.name,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
        Spacer(Modifier.height(8.dp))
        if (state.songsTotal > 0) {
            LinearProgressIndicator(
                progress = { state.fraction },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            // Still reading the playlist: nothing to count yet.
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Text(
            text = if (state.songsTotal > 0) {
                stringResource(
                    R.string.spotify_sync_step_matching,
                    state.index, state.total, state.songsDone, state.songsTotal,
                )
            } else {
                stringResource(R.string.spotify_sync_step_reading, state.index, state.total)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (state.failures.isNotEmpty()) {
            Text(
                text = stringResource(R.string.spotify_sync_some_failed, state.failures.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        TextButton(onClick = onCancel) { Text(stringResource(R.string.spotify_sync_cancel)) }
    }
}

@Composable
private fun FinishedCard(
    state: SpotifySyncState.Finished,
    onRetryFailed: () -> Unit,
    onDismiss: () -> Unit,
) {
    ResultCard(isError = state.failures.isNotEmpty() && state.imported == 0) {
        Text(
            text = if (state.cancelled) {
                stringResource(R.string.spotify_sync_cancelled, state.imported)
            } else {
                stringResource(R.string.spotify_sync_done, state.imported, state.added, state.unmatched)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (state.failures.isNotEmpty()) {
            Text(
                text = stringResource(R.string.spotify_sync_some_failed, state.failures.size),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
            state.failures.take(MAX_LISTED_FAILURES).forEach { failure -> FailureLine(failure) }
            if (state.failures.size > MAX_LISTED_FAILURES) {
                Text(
                    text = "+${state.failures.size - MAX_LISTED_FAILURES}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(Modifier.padding(top = 4.dp)) {
            if (state.failures.isNotEmpty()) {
                TextButton(onClick = onRetryFailed) {
                    Text(stringResource(R.string.spotify_sync_retry_failed))
                }
            }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) }
        }
    }
}

private const val MAX_LISTED_FAILURES = 5

@Composable
private fun FailureLine(failure: SyncFailure) {
    Column(Modifier.padding(top = 4.dp)) {
        Text(
            text = failure.playlist.name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = syncErrorText(failure.kind, failure.detail),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LibraryRow(
    title: String,
    subtitle: String,
    imageUrl: String?,
    onClick: () -> Unit,
    sync: RowSync,
    /** A run is under way, so another cannot be started from here. */
    busy: Boolean,
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
        when (sync) {
            RowSync.Active -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            RowSync.Imported -> Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = stringResource(R.string.spotify_sync_imported),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            RowSync.Failed -> IconButton(onClick = onImport, enabled = !busy) {
                Icon(
                    imageVector = Icons.Rounded.ErrorOutline,
                    contentDescription = stringResource(R.string.spotify_sync_row_failed),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
            RowSync.Idle -> IconButton(onClick = onImport, enabled = !busy) {
                Icon(
                    imageVector = Icons.Rounded.Download,
                    contentDescription = stringResource(R.string.spotify_sync_playlist),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
