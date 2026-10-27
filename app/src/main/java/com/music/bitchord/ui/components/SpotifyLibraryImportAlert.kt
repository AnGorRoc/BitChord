package com.music.bitchord.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.spotify.LocalPlaylistStore
import com.music.bitchord.data.spotify.SpotifyPlaylist
import com.music.bitchord.data.spotify.SpotifySync
import com.music.bitchord.data.spotify.SpotifySyncState
import com.music.bitchord.data.spotify.SyncErrorKind
import com.music.bitchord.data.spotify.SyncFailure
import dev.chrisbanes.haze.HazeState

/** How many failed playlists the result names before summarising the rest. */
private const val MAX_LISTED_FAILURES = 5

/**
 * Copies Spotify playlists to this device: pick which, watch it run, see how
 * it ended.
 *
 * The frosted card of the other import alerts. What it shows follows
 * [SpotifySync.state], not its own memory, so closing it while an import runs
 * hides it without stopping anything and opening it again finds the progress
 * where it was.
 */
@Composable
fun SpotifyLibraryImportAlert(
    hazeState: HazeState,
    playlists: List<SpotifyPlaylist>,
    onSignIn: () -> Unit,
    onDismiss: () -> Unit,
) {
    val state by SpotifySync.state.collectAsStateWithLifecycle()
    val imported by LocalPlaylistStore.playlists.collectAsStateWithLifecycle()
    val importedIds = remember(imported) { imported.mapNotNull { it.spotifyId }.toSet() }
    // What is not on the device yet, or everything when it all is already.
    var selected by remember {
        mutableStateOf(
            playlists.map { it.id }.filter { it !in importedIds }.toSet()
                .ifEmpty { playlists.map { it.id }.toSet() },
        )
    }
    val autoSync by AppSettings.spotifyAutoSync.collectAsStateWithLifecycle()

    AlertScaffold(hazeState = hazeState, onDismiss = onDismiss) {
        when (val current = state) {
            SpotifySyncState.Idle -> {
                Chooser(
                    playlists = playlists,
                    importedIds = importedIds,
                    selected = selected,
                    onSelected = { selected = it },
                    autoSync = autoSync,
                )
                AlertRule()
                AlertAction(
                    label = stringResource(R.string.spotify_sync_import_selected, selected.size),
                    emphasised = true,
                    enabled = selected.isNotEmpty(),
                    onClick = { SpotifySync.start(playlists.filter { it.id in selected }) },
                )
                AlertRule()
                AlertAction(label = stringResource(R.string.cancel), emphasised = false, onClick = onDismiss)
            }
            is SpotifySyncState.Running -> {
                Progress(current)
                AlertRule()
                AlertAction(label = stringResource(R.string.spotify_sync_hide), emphasised = true, onClick = onDismiss)
                AlertRule()
                AlertAction(
                    label = stringResource(R.string.spotify_sync_cancel),
                    emphasised = false,
                    destructive = true,
                    onClick = SpotifySync::cancel,
                )
            }
            is SpotifySyncState.Finished -> {
                Result(current)
                AlertRule()
                if (current.failures.isNotEmpty()) {
                    AlertAction(
                        label = stringResource(R.string.spotify_sync_retry_failed),
                        emphasised = true,
                        onClick = SpotifySync::retryFailed,
                    )
                    AlertRule()
                }
                AlertAction(
                    label = stringResource(R.string.done),
                    emphasised = current.failures.isEmpty(),
                    onClick = {
                        SpotifySync.dismiss()
                        onDismiss()
                    },
                )
            }
            is SpotifySyncState.Aborted -> {
                Body {
                    Text(
                        text = errorText(current.kind, current.detail),
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                }
                AlertRule()
                if (current.kind == SyncErrorKind.SIGNED_OUT) {
                    AlertAction(
                        label = stringResource(R.string.spotify_sign_in),
                        emphasised = true,
                        onClick = {
                            SpotifySync.dismiss()
                            onDismiss()
                            onSignIn()
                        },
                    )
                } else {
                    AlertAction(
                        label = stringResource(R.string.spotify_sync_retry),
                        emphasised = true,
                        onClick = {
                            SpotifySync.dismiss()
                            SpotifySync.start(playlists.filter { it.id in selected })
                        },
                    )
                }
                AlertRule()
                AlertAction(
                    label = stringResource(R.string.cancel),
                    emphasised = false,
                    onClick = {
                        SpotifySync.dismiss()
                        onDismiss()
                    },
                )
            }
        }
    }
}

@Composable
private fun Title() {
    Text(
        text = stringResource(R.string.spotify_sync_title),
        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, fontWeight = FontWeight.W600),
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
    )
}

/** The padded column every state of the alert puts its text in. */
@Composable
private fun Body(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 19.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Title()
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@Composable
private fun Chooser(
    playlists: List<SpotifyPlaylist>,
    importedIds: Set<String>,
    selected: Set<String>,
    onSelected: (Set<String>) -> Unit,
    autoSync: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 19.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Title()
        Text(
            text = stringResource(R.string.spotify_sync_description),
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.spotify_sync_selected_count, selected.size, playlists.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            val all = selected.size == playlists.size
            TextButton(
                onClick = { onSelected(if (all) emptySet() else playlists.map { it.id }.toSet()) },
            ) {
                Text(stringResource(if (all) R.string.spotify_sync_select_none else R.string.select_all))
            }
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
            items(playlists, key = { it.id }) { playlist ->
                val checked = playlist.id in selected
                val toggle = { onSelected(if (checked) selected - playlist.id else selected + playlist.id) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = toggle)
                        .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AsyncImage(
                        model = playlist.imageUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = playlist.name,
                            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (playlist.id in importedIds) {
                            Text(
                                text = stringResource(R.string.spotify_sync_imported),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        } else if (!playlist.owner.isNullOrBlank()) {
                            Text(
                                text = playlist.owner.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Checkbox(checked = checked, onCheckedChange = { toggle() })
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.spotify_sync_auto),
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 14.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.spotify_sync_auto_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = autoSync, onCheckedChange = AppSettings::setSpotifyAutoSync)
        }
    }
}

@Composable
private fun Progress(state: SpotifySyncState.Running) {
    Body {
        Text(
            text = state.playlist.name,
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 14.sp),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(10.dp))
        if (state.songsTotal > 0) {
            LinearProgressIndicator(progress = { state.fraction }, modifier = Modifier.fillMaxWidth())
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
            modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (state.failures.isNotEmpty()) {
            Text(
                text = stringResource(R.string.spotify_sync_some_failed, state.failures.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun Result(state: SpotifySyncState.Finished) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 420.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 19.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Title()
        Text(
            text = if (state.cancelled) {
                stringResource(R.string.spotify_sync_cancelled, state.imported)
            } else {
                stringResource(R.string.spotify_sync_done, state.imported, state.added, state.unmatched)
            },
            modifier = Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        if (state.failures.isNotEmpty()) {
            Text(
                text = stringResource(R.string.spotify_sync_some_failed, state.failures.size),
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.error,
            )
            state.failures.take(MAX_LISTED_FAILURES).forEach { FailureLine(it) }
            if (state.failures.size > MAX_LISTED_FAILURES) {
                Text(
                    text = "+${state.failures.size - MAX_LISTED_FAILURES}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FailureLine(failure: SyncFailure) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = failure.playlist.name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = errorText(failure.kind, failure.detail),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun errorText(kind: SyncErrorKind, detail: String): String = when (kind) {
    SyncErrorKind.SIGNED_OUT -> stringResource(R.string.spotify_sync_error_signed_out)
    SyncErrorKind.NETWORK -> stringResource(R.string.spotify_sync_error_network)
    SyncErrorKind.OTHER -> stringResource(R.string.spotify_sync_error_other, detail)
}
