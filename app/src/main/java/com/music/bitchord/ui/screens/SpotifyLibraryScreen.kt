package com.music.bitchord.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.spotify.LocalPlaylistStore
import com.music.bitchord.data.spotify.SpotifyCache
import com.music.bitchord.data.spotify.SpotifyLibrary
import com.music.bitchord.data.spotify.SpotifyPlaylist
import com.music.bitchord.data.spotify.SpotifySync
import com.music.bitchord.data.spotify.SpotifySyncState
import com.music.bitchord.data.spotify.SyncErrorKind
import com.music.bitchord.data.spotify.syncErrorKind
import kotlinx.coroutines.CancellationException
import androidx.compose.ui.platform.LocalContext
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.spotify_logo
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import com.music.bitchord.ui.components.thumbnailBorder
import com.music.bitchord.ui.components.topBarContentPadding
import org.jetbrains.compose.resources.painterResource

/**
 * The Spotify page's playlists and, while one is being made, the choice of
 * which to import. Held above the page so the top bar's "…" can start the
 * choosing — the same split as [LocalFolderState] for Downloads.
 */
@Stable
class SpotifyLibraryState {
    var playlists by mutableStateOf<List<SpotifyPlaylist>>(SpotifyCache.getPlaylists().orEmpty())
        internal set

    /** True while the list is being picked from rather than opened. */
    var selecting by mutableStateOf(false)
        private set
    var selectedIds by mutableStateOf<Set<String>>(emptySet())
        private set

    /** Starts choosing, with what is not on the device yet already ticked. */
    fun startSelecting() {
        val imported = LocalPlaylistStore.playlists.value.mapNotNull { it.spotifyId }.toSet()
        selectedIds = playlists.map { it.id }.filter { it !in imported }.toSet()
        selecting = true
    }

    fun toggle(playlist: SpotifyPlaylist) {
        selectedIds = if (playlist.id in selectedIds) selectedIds - playlist.id else selectedIds + playlist.id
    }

    fun selectAll() {
        selectedIds = playlists.mapTo(linkedSetOf()) { it.id }
    }

    fun clear() {
        selecting = false
        selectedIds = emptySet()
    }

    /** Imports what is ticked and leaves the choosing. */
    fun importSelected() {
        SpotifySync.dismiss()
        SpotifySync.start(playlists.filter { it.id in selectedIds })
        clear()
    }
}

/**
 * The signed-in Spotify account's playlists. A tap opens one as an ordinary
 * playlist page ([onOpenPlaylist]) — this screen is only the way in.
 *
 * Importing happens on the list itself, the way Downloads selects: the rows
 * take a tick, and a bar floats under the top bar with the count and the
 * action. The same bar then reports the import while it runs and once it
 * ends, so the page never leaves for a dialog.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotifyLibraryScreen(
    state: SpotifyLibraryState,
    onOpenPlaylist: (SpotifyPlaylist) -> Unit,
    onOpenLogin: () -> Unit,
    onOpenTokenSetup: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val cookie by AppSettings.spotifySpdcToken.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Bumped by a pull from the top of the list, to read the library again.
    var reload by remember { mutableIntStateOf(0) }
    val pullState = rememberPullToRefreshState()

    LaunchedEffect(cookie, reload) {
        if (cookie.isBlank()) {
            state.playlists = emptyList()
            state.clear()
            SpotifyCache.clear()
            error = null
            return@LaunchedEffect
        }
        if (state.playlists.isEmpty()) {
            val cached = SpotifyCache.getPlaylists()
            if (!cached.isNullOrEmpty()) {
                state.playlists = cached
            }
        }
        loading = true
        error = null
        try {
            val fresh = SpotifyLibrary.playlists()
            state.playlists = fresh
            SpotifyCache.savePlaylists(fresh)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (state.playlists.isEmpty() || reload > 0) {
                error = when (syncErrorKind(e)) {
                    SyncErrorKind.SIGNED_OUT -> context.getString(R.string.spotify_sync_error_signed_out)
                    SyncErrorKind.NETWORK -> context.getString(R.string.spotify_sync_error_network)
                    SyncErrorKind.OTHER -> context.getString(R.string.spotify_sync_error_other, e.message.orEmpty())
                }
            }
        }
        loading = false
    }

    // A library kept up to date brings the playlists already imported up to date.
    val autoSync by AppSettings.spotifyAutoSync.collectAsStateWithLifecycle()
    // Not on every look at the page: a run asks about every playlist, so one
    // that finished a few hours ago stands.
    LaunchedEffect(state.playlists, autoSync) {
        if (!autoSync || !SpotifyCache.autoSyncDue(AUTO_SYNC_MIN_INTERVAL_MS)) return@LaunchedEffect
        val imported = LocalPlaylistStore.playlists.value.mapNotNull { it.spotifyId }.toSet()
        val due = state.playlists.filter { it.id in imported }
        if (due.isEmpty()) return@LaunchedEffect
        SpotifySync.start(due)
        SpotifyCache.markAutoSynced()
    }

    val sync by SpotifySync.state.collectAsStateWithLifecycle()
    val local by LocalPlaylistStore.playlists.collectAsStateWithLifecycle()
    val importedIds = remember(local) { local.mapNotNull { it.spotifyId }.toSet() }
    val failures = when (val s = sync) {
        is SpotifySyncState.Running -> s.failures
        is SpotifySyncState.Finished -> s.failures
        else -> emptyList()
    }.associateBy { it.playlist.id }
    val activeId = (sync as? SpotifySyncState.Running)?.playlist?.id

    BackHandler(enabled = state.selecting) { state.clear() }

    // Pulling down from the top reads the library again. The indicator sits
    // where the page's own content starts, so it is the page's, not the bar's.
    PullToRefreshBox(
        isRefreshing = loading,
        onRefresh = { reload++ },
        state = pullState,
        modifier = modifier.fillMaxSize(),
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = pullState,
                isRefreshing = loading,
                modifier = Modifier.align(Alignment.TopCenter),
                color = MaterialTheme.colorScheme.primary,
            )
        },
    ) {
        Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
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
                if (state.selecting) {
                    item { SelectingIntro() }
                }
                // The pull's own indicator covers a reload of a list already on
                // screen; this spinner is only for the first read.
                if (loading && state.playlists.isEmpty()) {
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
                items(state.playlists, key = { it.id }) { item ->
                    val failure = failures[item.id]
                    LibraryRow(
                        title = item.name,
                        subtitle = when {
                            failure != null -> syncErrorText(failure.kind, failure.detail)
                            item.id in importedIds -> listOfNotNull(
                                item.owner?.takeIf { it.isNotBlank() },
                                stringResource(R.string.spotify_sync_imported),
                            ).joinToString(" · ")
                            else -> item.owner.orEmpty()
                        },
                        subtitleIsError = failure != null,
                        imageUrl = item.imageUrl,
                        trailing = when {
                            state.selecting -> if (item.id in state.selectedIds) RowTrailing.Selected else RowTrailing.Unselected
                            item.id == activeId -> RowTrailing.Importing
                            else -> RowTrailing.None
                        },
                        onClick = { if (state.selecting) state.toggle(item) else onOpenPlaylist(item) },
                    )
                }
            }
        }

        val barModifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = topBarContentPadding(), start = PAGE_GUTTER, end = PAGE_GUTTER)
        when {
            state.selecting -> SelectionBar(state, barModifier)
            sync !is SpotifySyncState.Idle -> SyncBar(sync, onOpenLogin, barModifier)
        }
        }
    }
}

/** What appears over the list once choosing starts: what it does. */
@Composable
private fun SelectingIntro() {
    Text(
        text = stringResource(R.string.spotify_sync_description),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun syncErrorText(kind: SyncErrorKind, detail: String): String = when (kind) {
    SyncErrorKind.SIGNED_OUT -> stringResource(R.string.spotify_sync_error_signed_out)
    SyncErrorKind.NETWORK -> stringResource(R.string.spotify_sync_error_network)
    SyncErrorKind.OTHER -> stringResource(R.string.spotify_sync_error_other, detail)
}

/**
 * The floating pill the page's bars share: the shape, lift and fill of
 * Downloads' selection bar ([LocalFolderSelectionBar]), so both read as the
 * same control.
 */
@Composable
private fun FloatingBar(
    modifier: Modifier,
    progress: (() -> Float)? = null,
    indeterminate: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    Box(modifier.floating().heightIn(min = 48.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) { content() }
        // A hairline of progress along the bar's foot rather than a widget of its own.
        val trackModifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp)
        when {
            progress != null -> LinearProgressIndicator(
                progress = progress,
                modifier = trackModifier,
                trackColor = Color.Transparent,
                drawStopIndicator = {},
            )
            indeterminate -> LinearProgressIndicator(modifier = trackModifier, trackColor = Color.Transparent)
        }
    }
}

/** The lift, corners and fill the page's floating bars share with Downloads' ([LocalFolderSelectionBar]). */
@Composable
private fun Modifier.floating(): Modifier {
    val shape = RoundedCornerShape(14.dp)
    return fillMaxWidth()
        .shadow(10.dp, shape)
        .clip(shape)
        .background(MaterialTheme.colorScheme.surfaceVariant)
}

/** Height of the two capsules at the foot of the selection bar. */
private val BAR_PILL_HEIGHT = 44.dp

/**
 * The selection, the way a contextual action bar is laid out: what is chosen
 * on top (close, the count, Select all), and under it what to do with it — the
 * main action as the app's filled capsule, with the one setting that goes with
 * it as its quieter twin beside it, as Play and Shuffle sit on a release page.
 */
@Composable
private fun SelectionBar(state: SpotifyLibraryState, modifier: Modifier) {
    val count = state.selectedIds.size
    val allSelected = state.playlists.isNotEmpty() && count == state.playlists.size
    val autoSync by AppSettings.spotifyAutoSync.collectAsStateWithLifecycle()
    val ink = if (isSystemInDarkTheme()) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    val haptics = rememberHaptics()
    Column(modifier.floating()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = state::clear) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.cancel),
                    tint = ink,
                    modifier = Modifier.size(20.dp),
                )
            }
            Text(
                text = stringResource(R.string.selected_count, count),
                style = MaterialTheme.typography.titleSmall,
                color = ink,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = state::selectAll, enabled = !allSelected) {
                Text(stringResource(R.string.select_all))
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BarPill(
                icon = Icons.Rounded.Sync,
                label = stringResource(R.string.spotify_sync_auto),
                highlighted = autoSync,
                onClick = {
                    haptics.play(if (autoSync) Haptic.ToggleOff else Haptic.ToggleOn)
                    AppSettings.setSpotifyAutoSync(!autoSync)
                },
                modifier = Modifier.weight(1f),
            )
            BarPill(
                icon = Icons.Rounded.Download,
                label = stringResource(R.string.spotify_import_button),
                filled = true,
                enabled = count > 0,
                onClick = {
                    haptics.play(Haptic.Resume)
                    state.importSelected()
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * A capsule of the selection bar. [filled] is the main action, in the primary
 * colour (white with black type on dark, as Play is); otherwise it is the
 * quiet twin, which [highlighted] turns to the accent while its setting is on.
 */
@Composable
private fun BarPill(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    highlighted: Boolean = false,
    enabled: Boolean = true,
) {
    val dark = isSystemInDarkTheme()
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when {
        filled -> (if (dark) Color.White else scheme.primary) to (if (dark) Color.Black else scheme.onPrimary)
        highlighted -> scheme.primary.copy(alpha = 0.18f) to scheme.primary
        else -> scheme.onSurface.copy(alpha = 0.08f) to scheme.onSurface
    }
    Row(
        modifier = modifier
            .height(BAR_PILL_HEIGHT)
            .alpha(if (enabled) 1f else 0.38f)
            .clip(CircleShape)
            .background(container)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Where an import stands, in the selection bar's place: the playlist under way
 * and a hairline of progress while it runs, then how it ended.
 */
@Composable
private fun SyncBar(sync: SpotifySyncState, onSignIn: () -> Unit, modifier: Modifier) {
    when (sync) {
        is SpotifySyncState.Running -> FloatingBar(
            modifier = modifier,
            progress = if (sync.songsTotal > 0) ({ sync.fraction }) else null,
            indeterminate = sync.songsTotal == 0,
        ) {
            BarText(
                title = sync.playlist.name,
                subtitle = if (sync.songsTotal > 0) {
                    stringResource(
                        R.string.spotify_sync_step_matching,
                        sync.index, sync.total, sync.songsDone, sync.songsTotal,
                    )
                } else {
                    stringResource(R.string.spotify_sync_step_reading, sync.index, sync.total)
                },
            )
            TextButton(onClick = SpotifySync::cancel) {
                Text(stringResource(R.string.cancel), color = DESTRUCTIVE)
            }
        }
        is SpotifySyncState.Finished -> FloatingBar(modifier) {
            BarText(
                title = if (sync.cancelled) {
                    stringResource(R.string.spotify_sync_cancelled, sync.imported)
                } else {
                    stringResource(R.string.spotify_sync_done, sync.imported, sync.added, sync.unmatched)
                },
                subtitle = sync.failures.takeIf { it.isNotEmpty() }?.let {
                    stringResource(R.string.spotify_sync_some_failed, it.size)
                },
                subtitleIsError = true,
            )
            if (sync.failures.isNotEmpty()) {
                TextButton(onClick = SpotifySync::retryFailed) {
                    Text(stringResource(R.string.spotify_sync_retry))
                }
            }
            CloseButton()
        }
        is SpotifySyncState.Aborted -> FloatingBar(modifier) {
            BarText(title = syncErrorText(sync.kind, sync.detail), titleIsError = true)
            if (sync.kind == SyncErrorKind.SIGNED_OUT) {
                TextButton(
                    onClick = {
                        SpotifySync.dismiss()
                        onSignIn()
                    },
                ) { Text(stringResource(R.string.spotify_sign_in)) }
            }
            CloseButton()
        }
        SpotifySyncState.Idle -> Unit
    }
}

@Composable
private fun RowScope.BarText(
    title: String,
    subtitle: String? = null,
    titleIsError: Boolean = false,
    subtitleIsError: Boolean = false,
) {
    val dark = isSystemInDarkTheme()
    val ink = if (dark) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier
            .weight(1f)
            .padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = if (titleIsError) MaterialTheme.colorScheme.error else ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (subtitleIsError) MaterialTheme.colorScheme.error else ink.copy(alpha = 0.72f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CloseButton() {
    IconButton(onClick = SpotifySync::dismiss) {
        Icon(
            Icons.Rounded.Close,
            contentDescription = stringResource(R.string.close),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** The red of Downloads' destructive actions. */
private val DESTRUCTIVE = Color(0xFFFF453A)

/** How long after an automatic sync the next one waits, however often the page is opened. */
private const val AUTO_SYNC_MIN_INTERVAL_MS = 6L * 60 * 60 * 1000

private enum class RowTrailing { None, Selected, Unselected, Importing }

/** The Library's list style: a 64 dp cover, about Spotify's own size. */
private val ROW_COVER_SIZE = 64.dp
private val ROW_COVER_SHAPE = RoundedCornerShape(8.dp)

@Composable
private fun LibraryRow(
    title: String,
    subtitle: String,
    subtitleIsError: Boolean,
    imageUrl: String?,
    trailing: RowTrailing,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = PAGE_GUTTER, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = imageUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(ROW_COVER_SIZE)
                .clip(ROW_COVER_SHAPE)
                .thumbnailBorder(ROW_COVER_SHAPE)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (subtitleIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        when (trailing) {
            RowTrailing.Selected -> TrailingIcon(Icons.Rounded.CheckCircle, MaterialTheme.colorScheme.primary, stringResource(R.string.selected))
            RowTrailing.Unselected -> TrailingIcon(Icons.Rounded.RadioButtonUnchecked, MaterialTheme.colorScheme.onSurfaceVariant, null)
            RowTrailing.Importing -> {
                Spacer(Modifier.width(12.dp))
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            RowTrailing.None -> Unit
        }
    }
}

@Composable
private fun TrailingIcon(icon: ImageVector, tint: Color, description: String?) {
    Spacer(Modifier.width(12.dp))
    Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(22.dp))
}
