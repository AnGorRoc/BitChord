package com.music.bitchord.desktop

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.artworkAt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val STORY_PAGES_COUNT = 8
private const val STORY_DURATION_MS = 6000

@Composable
fun DesktopReplayStoriesDialog(
    summary: DesktopReplaySummary,
    holder: String,
    memberSince: String?,
    onDismiss: () -> Unit,
    onPlaySong: (Song) -> Unit,
    onSharePoster: () -> Unit,
) {
    var currentPage by remember { mutableIntStateOf(0) }
    var isPaused by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f)),
            contentAlignment = Alignment.Center,
        ) {
            // Card frame 9:16
            Box(
                Modifier
                    .fillMaxHeight(0.92f)
                    .aspectRatio(9f / 16f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFF221A38), Color(0xFF110D1E), Color(0xFF08060E))
                        )
                    )
            ) {
                // Interactive gesture area for tap-to-advance and hold-to-pause
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(currentPage) {
                            detectTapGestures(
                                onPress = {
                                    isPaused = true
                                    tryAwaitRelease()
                                    isPaused = false
                                },
                                onTap = { offset ->
                                    if (offset.x < size.width * 0.35f) {
                                        if (currentPage > 0) currentPage--
                                    } else {
                                        if (currentPage < STORY_PAGES_COUNT - 1) currentPage++ else onDismiss()
                                    }
                                }
                            )
                        }
                ) {
                    // Story Page Content
                    StoryPageContent(
                        page = currentPage,
                        summary = summary,
                        holder = holder,
                        memberSince = memberSince,
                        onPlaySong = onPlaySong,
                    )
                }

                // Top Progress Indicators and Controls
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp, start = 16.dp, end = 16.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        for (i in 0 until STORY_PAGES_COUNT) {
                            StorySegmentBar(
                                index = i,
                                currentIndex = currentPage,
                                isPaused = isPaused,
                                modifier = Modifier.weight(1f),
                                onFinish = {
                                    if (currentPage < STORY_PAGES_COUNT - 1) {
                                        currentPage++
                                    } else {
                                        onDismiss()
                                    }
                                },
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "BITCHORD REPLAY",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White.copy(alpha = 0.7f),
                            letterSpacing = 1.sp,
                        )

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = onSharePoster, modifier = Modifier.size(32.dp)) {
                                Icon(
                                    Icons.Rounded.IosShare,
                                    contentDescription = "Share",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                                Icon(
                                    Icons.Rounded.Close,
                                    contentDescription = "Close",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StorySegmentBar(
    index: Int,
    currentIndex: Int,
    isPaused: Boolean,
    modifier: Modifier = Modifier,
    onFinish: () -> Unit,
) {
    val progress = remember { Animatable(0f) }

    LaunchedEffect(currentIndex, isPaused) {
        if (index < currentIndex) {
            progress.snapTo(1f)
        } else if (index > currentIndex) {
            progress.snapTo(0f)
        } else {
            if (!isPaused) {
                val remainingTime = ((1f - progress.value) * STORY_DURATION_MS).toInt().coerceAtLeast(10)
                progress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = remainingTime, easing = LinearEasing),
                )
                if (progress.value >= 1f) {
                    onFinish()
                }
            } else {
                progress.stop()
            }
        }
    }

    Box(
        modifier
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Color.White.copy(alpha = 0.25f))
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress.value)
                .background(Color.White)
        )
    }
}

@Composable
private fun StoryPageContent(
    page: Int,
    summary: DesktopReplaySummary,
    holder: String,
    memberSince: String?,
    onPlaySong: (Song) -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 72.dp),
        contentAlignment = Alignment.Center,
    ) {
        when (page) {
            0 -> StoryIntro(summary, holder, memberSince)
            1 -> StoryMinutes(summary)
            2 -> StoryTopSong(summary, onPlaySong)
            3 -> StoryTopArtist(summary)
            4 -> StoryTopAlbum(summary)
            5 -> StoryTopGenre(summary)
            6 -> StoryHabits(summary)
            7 -> StorySummary(summary, holder)
        }
    }
}

@Composable
private fun StoryIntro(summary: DesktopReplaySummary, holder: String, memberSince: String?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(
            summary.label.ifBlank { summary.period.chip },
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Black,
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            if (holder.isNotBlank()) "Ready to see your year, $holder?" else "Ready to relive your music journey?",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
        )
        if (!memberSince.isNullOrBlank()) {
            Spacer(Modifier.height(24.dp))
            Text(
                "MEMBER SINCE $memberSince",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White.copy(alpha = 0.5f),
                letterSpacing = 1.sp,
            )
        }
    }
}

@Composable
private fun StoryMinutes(summary: DesktopReplaySummary) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(
            "You spent",
            style = MaterialTheme.typography.titleLarge,
            color = Color.White.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            String.format("%,d", summary.minutes),
            style = MaterialTheme.typography.displayLarge,
            fontWeight = FontWeight.Black,
            color = Color.White,
        )
        Text(
            "minutes listening to music",
            style = MaterialTheme.typography.titleLarge,
            color = Color.White.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(24.dp))
        Text(
            "Across ${summary.totalPlays} plays",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.5f),
        )
    }
}

@Composable
private fun StoryTopSong(summary: DesktopReplaySummary, onPlaySong: (Song) -> Unit) {
    val top = summary.songs.firstOrNull()
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(
            "Your #1 Song",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(20.dp))
        if (top != null) {
            Box(
                Modifier
                    .size(200.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = top.song.thumbnailUrl?.artworkAt(400) ?: top.song.thumbnailUrl,
                    contentDescription = top.song.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            Spacer(Modifier.height(24.dp))
            Text(
                top.song.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                top.song.artist,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                "${top.plays} plays",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFE25555),
            )
        } else {
            Text("No song plays yet", color = Color.White.copy(alpha = 0.5f))
        }
    }
}

@Composable
private fun StoryTopArtist(summary: DesktopReplaySummary) {
    val top = summary.artists.firstOrNull()
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(
            "Your Top Artist",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(20.dp))
        if (top != null) {
            Box(
                Modifier
                    .size(190.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = top.artworkUrl?.artworkAt(400) ?: top.artworkUrl,
                    contentDescription = top.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            Spacer(Modifier.height(24.dp))
            Text(
                top.title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            val mins = top.ms / 60_000L
            Text(
                "$mins minutes • ${top.plays} plays",
                style = MaterialTheme.typography.titleSmall,
                color = Color.White.copy(alpha = 0.7f),
            )
        } else {
            Text("No artist stats yet", color = Color.White.copy(alpha = 0.5f))
        }
    }
}

@Composable
private fun StoryTopAlbum(summary: DesktopReplaySummary) {
    val top = summary.albums.firstOrNull()
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(
            "Your Top Album",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(20.dp))
        if (top != null) {
            Box(
                Modifier
                    .size(200.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = top.artworkUrl?.artworkAt(400) ?: top.artworkUrl,
                    contentDescription = top.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            Spacer(Modifier.height(24.dp))
            Text(
                top.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "${top.plays} plays",
                style = MaterialTheme.typography.titleSmall,
                color = Color.White.copy(alpha = 0.7f),
            )
        } else {
            Text("No album stats yet", color = Color.White.copy(alpha = 0.5f))
        }
    }
}

@Composable
private fun StoryTopGenre(summary: DesktopReplaySummary) {
    val top = summary.genres.firstOrNull()
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(
            "Your Top Genre",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(20.dp))
        if (top != null) {
            Text(
                top.title,
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Black,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                "${top.plays} plays",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.7f),
            )
            val others = summary.genres.drop(1).take(3).map { it.title }
            if (others.isNotEmpty()) {
                Spacer(Modifier.height(28.dp))
                Text(
                    "Followed by: " + others.joinToString(", "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            Text("Diverse taste! No single genre dominated.", color = Color.White.copy(alpha = 0.7f), textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun StoryHabits(summary: DesktopReplaySummary) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(
            "Your Listening Habits",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(24.dp))
        Text(
            "${summary.distinctSongs} tracks",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
        Text(
            "from ${summary.distinctArtists} artists",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White.copy(alpha = 0.7f),
        )
        if (summary.peakHour != null) {
            Spacer(Modifier.height(24.dp))
            Text(
                "Peak Listening Hour",
                style = MaterialTheme.typography.labelLarge,
                color = Color.White.copy(alpha = 0.5f),
            )
            val hour = summary.peakHour!!
            val hourFormatted = when {
                hour == 0 -> "Midnight"
                hour == 12 -> "Noon"
                hour < 12 -> "$hour AM"
                else -> "${hour - 12} PM"
            }
            Text(
                hourFormatted,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF6E56CF),
            )
        }
    }
}

@Composable
private fun StorySummary(summary: DesktopReplaySummary, holder: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(
            summary.label.ifBlank { summary.period.chip },
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Black,
            color = Color.White,
        )
        if (holder.isNotBlank()) {
            Text(
                holder,
                style = MaterialTheme.typography.titleSmall,
                color = Color.White.copy(alpha = 0.7f),
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "${String.format("%,d", summary.minutes)} mins • ${summary.distinctSongs} songs",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFE25555),
        )
        Spacer(Modifier.height(20.dp))

        // Mini top 3 songs
        summary.songs.take(3).forEachIndexed { i, entry ->
            Text(
                "${i + 1}. ${entry.song.title} — ${entry.song.artist}",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
        }
    }
}
