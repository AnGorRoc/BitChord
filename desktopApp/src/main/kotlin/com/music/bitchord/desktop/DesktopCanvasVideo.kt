package com.music.bitchord.desktop

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.music.bitchord.data.canvas.CanvasArtwork
import com.music.bitchord.data.canvas.CanvasSource
import com.music.bitchord.ui.player.CanvasContentMode
import com.music.bitchord.ui.player.CanvasVideoSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import kotlin.math.roundToInt

/**
 * The desktop's decoder behind the shared player's Canvas clip.
 *
 * The phone plays the clip in a silent ExoPlayer on a TextureView; here FFmpeg
 * decodes it into frames that Compose draws. Everything the shared player asks
 * of the clip is answered the same way as on the phone: nothing until a first
 * frame, then a 320ms fade reported through `onCoverChanged`, the aspect once
 * known, frames for the backdrop to re-tint off, the portrait fit, the bottom
 * dissolve, and a pause that keeps the last frame on screen.
 */
@Composable
internal fun DesktopCanvasVideo(spec: CanvasVideoSpec, modifier: Modifier) {
    val canvas = spec.canvas
    var frame by remember(canvas) { mutableStateOf<ImageBitmap?>(null) }
    val rendered = frame != null
    val running by rememberUpdatedState(spec.isPlaying && !spec.pausedForTransition)
    val reportAspect by rememberUpdatedState(spec.onAspectRatioChanged)
    val reportRendered by rememberUpdatedState(spec.onRenderedChanged)
    val reportFrame by rememberUpdatedState(spec.onFrameCaptured)
    val reportCover by rememberUpdatedState(spec.onCoverChanged)
    val presentationAlpha by rememberUpdatedState(spec.presentationAlpha)

    LaunchedEffect(canvas) {
        val decoder = DesktopCanvasDecoder()
        val opened = withContext(Dispatchers.IO) {
            var last: Result<Unit> = Result.failure(IllegalStateException("the clip could not be fetched"))
            for (candidate in listOfNotNull(canvas.url, canvas.fallbackUrl)) {
                val source = DesktopCanvasCache.fileFor(candidate)?.toAbsolutePath()?.toString()
                    ?: if (isManifest(candidate)) candidate else null
                    ?: continue
                last = decoder.open(source)
                if (last.isSuccess) break
            }
            last
        }
        if (opened.isFailure) {
            DesktopTrackLog.log("canvas: ${opened.exceptionOrNull()?.message}")
            withContext(Dispatchers.IO) { decoder.close() }
            return@LaunchedEffect
        }
        reportAspect(decoder.width.toFloat() / decoder.height.coerceAtLeast(1))

        val cachedFrames = ArrayList<ImageBitmap>()
        var allFramesLoaded = false
        var frameIndex = 0
        var decoderClosed = false
        val frameIntervalNanos = decoder.frameIntervalMillis * 1_000_000L
        var nextFrameTimeNanos = System.nanoTime()

        try {
            val pixels = ByteArray(decoder.width * decoder.height * 4)
            val info = ImageInfo.makeN32(decoder.width, decoder.height, ColorAlphaType.OPAQUE)

            while (isActive) {
                snapshotFlow { running }.first { it }

                val currentBmp: ImageBitmap? = if (allFramesLoaded) {
                    if (cachedFrames.isEmpty()) break
                    val bmp = cachedFrames[frameIndex]
                    frameIndex = (frameIndex + 1) % cachedFrames.size
                    bmp
                } else {
                    val decoded = withContext(Dispatchers.IO) { decoder.nextFrame(pixels) }
                    if (decoded) {
                        val bmp = Image.makeRaster(info, pixels.copyOf(), decoder.width * 4).toComposeImageBitmap()
                        cachedFrames.add(bmp)
                        frameIndex++
                        if (cachedFrames.size >= 300) {
                            allFramesLoaded = true
                            frameIndex = 0
                            withContext(NonCancellable + Dispatchers.IO) {
                                decoder.close()
                                decoderClosed = true
                            }
                        }
                        bmp
                    } else {
                        withContext(NonCancellable + Dispatchers.IO) {
                            decoder.close()
                            decoderClosed = true
                        }
                        if (cachedFrames.isEmpty()) break
                        allFramesLoaded = true
                        frameIndex = 0
                        val bmp = cachedFrames[0]
                        frameIndex = (frameIndex + 1) % cachedFrames.size
                        bmp
                    }
                }

                frame = currentBmp

                nextFrameTimeNanos += frameIntervalNanos
                val nowNanos = System.nanoTime()
                val sleepNanos = nextFrameTimeNanos - nowNanos

                if (sleepNanos > 0L) {
                    val sleepMs = sleepNanos / 1_000_000L
                    if (sleepMs > 2L) {
                        delay(sleepMs - 1L)
                    }
                    while (System.nanoTime() < nextFrameTimeNanos) {
                        Thread.yield()
                    }
                } else if (nowNanos - nextFrameTimeNanos > frameIntervalNanos * 3L) {
                    nextFrameTimeNanos = nowNanos
                }
            }
        } finally {
            if (!decoderClosed) {
                withContext(NonCancellable + Dispatchers.IO) { decoder.close() }
            }
        }
    }

    LaunchedEffect(rendered) { reportRendered(rendered) }

    // One frame once there is one, and then — for a caller re-tinting off the
    // clip — another every so often.
    LaunchedEffect(rendered, spec.refreshFrameEveryMs) {
        if (!rendered) return@LaunchedEffect
        frame?.let(reportFrame)
        val interval = spec.refreshFrameEveryMs ?: return@LaunchedEffect
        while (isActive) {
            delay(interval)
            frame?.let(reportFrame)
        }
    }

    val alpha by animateFloatAsState(
        targetValue = if (rendered) 1f else 0f,
        animationSpec = tween(durationMillis = 320),
        label = "canvasAlpha",
    )
    LaunchedEffect(Unit) {
        snapshotFlow { alpha * presentationAlpha() }.collect { reportCover(it) }
    }
    DisposableEffect(Unit) {
        onDispose {
            reportRendered(false)
            reportCover(0f)
        }
    }

    val bottomFade = spec.bottomFade
    val bottomFadeEndPx = spec.bottomFadeEndPx
    Canvas(
        modifier = modifier.graphicsLayer {
            this.alpha = alpha * presentationAlpha()
            // Offscreen so the bottom dissolve masks the clip, not what is behind it.
            if (bottomFade > 0.001f) compositingStrategy = CompositingStrategy.Offscreen
        },
    ) {
        val image = frame ?: return@Canvas
        val clipAspect = image.width.toFloat() / image.height
        val viewAspect = size.width / size.height
        val fit = spec.contentMode == CanvasContentMode.FIT_PORTRAIT && clipAspect < 1f
        val (drawWidth, drawHeight) = when {
            fit && clipAspect > viewAspect -> size.width to size.width / clipAspect
            fit -> size.height * clipAspect to size.height
            clipAspect > viewAspect -> size.height * clipAspect to size.height
            else -> size.width to size.width / clipAspect
        }
        val left = (size.width - drawWidth) / 2f
        val top = if (fit && spec.alignPortraitTop) 0f else (size.height - drawHeight) / 2f
        drawImage(
            image = image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(image.width, image.height),
            dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
            dstSize = IntSize(drawWidth.roundToInt(), drawHeight.roundToInt()),
        )
        if (bottomFade > 0.001f) {
            val endY = bottomFadeEndPx?.coerceIn(0f, size.height) ?: size.height
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Black,
                    1f to Color.Transparent,
                    startY = endY * (1f - bottomFade.coerceAtMost(1f)),
                    endY = endY,
                ),
                topLeft = Offset.Zero,
                size = size,
                blendMode = BlendMode.DstIn,
            )
        }
    }
}

/** The desktop's clip, in the shared player's terms. */
internal fun DesktopCanvasArtwork.toShared(): CanvasArtwork = CanvasArtwork(
    url = url,
    fallbackUrl = fallbackUrl,
    title = title,
    artist = artist,
    album = album,
    source = if (source == DesktopCanvasSource.SPOTIFY) CanvasSource.SPOTIFY else CanvasSource.OTHER,
)
