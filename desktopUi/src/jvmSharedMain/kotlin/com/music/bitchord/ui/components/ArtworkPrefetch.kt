package com.music.bitchord.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import kotlinx.coroutines.flow.distinctUntilChanged

/** How many rows past the bottom of the viewport get their covers fetched ahead of the scroll. */
const val ARTWORK_PREFETCH_AHEAD = 8

/**
 * Asks Coil for the covers of the rows just below [state]'s viewport, so the
 * ones the scroll reaches next are already cached when their row composes.
 *
 * The rows on screen are left to their own `AsyncImage`, so they still load
 * first; only the rows past them are fetched early. [artwork] is indexed the way
 * the lazy list is — one entry per lazy item, null for an item with no cover —
 * and each URL must be the one its row loads, or Coil's cache misses it.
 */
@Composable
fun PrefetchArtworkAhead(
    state: LazyListState,
    artwork: List<String?>,
) {
    val context = LocalPlatformContext.current
    val currentArtwork by rememberUpdatedState(artwork)
    LaunchedEffect(state) {
        snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index to currentArtwork }
            .distinctUntilChanged()
            .collect { (last, list) ->
                if (last == null) return@collect
                val loader = SingletonImageLoader.get(context)
                for (index in last + 1..minOf(last + ARTWORK_PREFETCH_AHEAD, list.lastIndex)) {
                    // No size on the request: the row's own AsyncImage asks for its
                    // size, so the cache entry shared is the downloaded file, not the bitmap.
                    val url = list[index] ?: continue
                    loader.enqueue(ImageRequest.Builder(context).data(url).build())
                }
            }
    }
}
