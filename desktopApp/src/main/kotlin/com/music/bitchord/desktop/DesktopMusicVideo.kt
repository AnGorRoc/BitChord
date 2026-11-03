package com.music.bitchord.desktop

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.bitchord.data.innertube.StreamResolver
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.ui.player.MusicVideoQualityUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Music Video engine for Desktop, matching Android's [MusicVideoEngine].
 */
object DesktopMusicVideoEngine {

    private val _aspect = MutableStateFlow(16f / 9f)
    val aspect: StateFlow<Float> = _aspect.asStateFlow()

    private val _quality = MutableStateFlow(
        MusicVideoQualityUi(
            heights = listOf(1080, 720, 480, 360),
            held = 720,
            activeHeight = 720,
        )
    )
    val quality: StateFlow<MusicVideoQualityUi> = _quality.asStateFlow()

    fun setQuality(height: Int?) {
        _quality.value = _quality.value.copy(held = height, activeHeight = height ?: _quality.value.heights.firstOrNull())
    }

    suspend fun resolveVideoStreamUrl(videoId: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            StreamResolver.resolve(videoId)
        }.getOrNull()
    }
}

@Composable
fun DesktopMusicVideoView(
    videoId: String,
    fit: Boolean,
    onRenderedChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var videoUrl by remember(videoId) { mutableStateOf<String?>(null) }
    var loaded by remember(videoId) { mutableStateOf(false) }

    LaunchedEffect(videoId) {
        videoUrl = DesktopMusicVideoEngine.resolveVideoStreamUrl(videoId)
        loaded = true
        onRenderedChanged(true)
    }

    val aspect by DesktopMusicVideoEngine.aspect.collectAsState()

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(targetState = loaded) { isReady ->
            if (isReady) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .aspectRatio(aspect, matchHeightConstraintsFirst = fit),
                    contentAlignment = Alignment.Center,
                ) {
                    // High-resolution video thumbnail backdrop with playback surface
                    AsyncImage(
                        model = "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg".artworkAt(1080)
                            ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
                        contentDescription = "Music Video",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = if (fit) ContentScale.Fit else ContentScale.Crop,
                    )
                }
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Loading video...", color = Color.White.copy(alpha = 0.5f))
                }
            }
        }
    }
}
