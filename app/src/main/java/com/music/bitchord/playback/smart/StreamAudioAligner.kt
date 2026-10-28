package com.music.bitchord.playback.smart

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.util.UnstableApi
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.AudioCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/**
 * Cross-correlates audio energy envelopes across playback sources to compute
 * the precise latency/seek offset for seamless stream quality upgrades.
 */
@UnstableApi
object StreamAudioAligner {

    private const val TAG = "StreamAudioAligner"

    internal const val HOP_MS = 2.0 // 500 fps high-precision envelope
    internal const val WIN_MS = 10.0 // 10ms transient window
    internal const val MIN_CONFIDENCE = 0.40 // Minimum normalized cross-correlation
    internal const val TIMEOUT_MS = 3000L // 3.0s deadline for alignment analysis
    internal const val ANALYSIS_WARM_BYTES = 1_500_000L // 1.5MB background pre-warm
    internal const val PRIMARY_WINDOW_START_SEC = 0.0
    internal const val PRIMARY_WINDOW_END_SEC = 15.0
    internal const val SECONDARY_WINDOW_BEFORE_SEC = 5.0
    internal const val SECONDARY_WINDOW_AFTER_SEC = 10.0

    // Cache computed offsets so repeat upgrades or seeks reuse the measurement instantly:
    // (mediaId, streamUrl) -> offsetMs
    private val offsetCache = ConcurrentHashMap<Pair<String, String>, Long>()

    fun getCachedOffsetMs(mediaId: String, streamUrl: String): Long? =
        offsetCache[mediaId to streamUrl]

    fun putCachedOffsetMs(mediaId: String, streamUrl: String, offsetMs: Long) {
        offsetCache[mediaId to streamUrl] = offsetMs
    }

    fun clearCache() {
        offsetCache.clear()
    }

    /** Where the incoming deck must start so the offset is applied as content compensation. */
    fun seekPositionMs(currentPositionMs: Long, offsetMs: Long): Long =
        (currentPositionMs + offsetMs).coerceAtLeast(0L)

    /**
     * Resolves analysis window bounds around the playback position.
     */
    fun resolveWindow(currentPosMs: Long): Pair<Double, Double> {
        val curSec = currentPosMs / 1000.0
        val start = max(0.0, curSec - SECONDARY_WINDOW_BEFORE_SEC)
        val end = curSec + SECONDARY_WINDOW_AFTER_SEC
        return start to end
    }

    /**
     * Correlates two PCM signals and returns Pair(totalOffsetMs, confidence).
     */
    fun computeOffsetFromSignals(
        sourceSamples: FloatArray,
        sourceSampleRate: Double,
        sourceStartSec: Double,
        targetSamples: FloatArray,
        targetSampleRate: Double,
        targetStartSec: Double,
    ): Pair<Long, Double> {
        val sourceEnv = VersionAudioAligner.computeEnergyEnvelope(sourceSamples, sourceSampleRate, HOP_MS, WIN_MS)
        val targetEnv = VersionAudioAligner.computeEnergyEnvelope(targetSamples, targetSampleRate, HOP_MS, WIN_MS)
        if (sourceEnv.isEmpty() || targetEnv.isEmpty()) return Pair(0L, 0.0)

        val correlation = VersionAudioAligner.crossCorrelate(
            sourceEnv = sourceEnv,
            targetEnv = targetEnv,
            hopMs = HOP_MS,
            maxLagSec = 15.0,
        )

        val bestLagFrames = correlation.lagFrames
        val floor = correlation.floor
        val ceiling = correlation.ceiling
        if (bestLagFrames == floor || bestLagFrames == ceiling) {
            TrackLog.w(
                TAG,
                "Stream alignment: peak pinned to searchable edge ($bestLagFrames of $floor..$ceiling)",
            )
            return Pair(0L, 0.0)
        }

        val lagMs = (bestLagFrames * HOP_MS).toLong()
        val startDiffMs = ((targetStartSec - sourceStartSec) * 1000.0).toLong()
        val totalOffsetMs = lagMs + startDiffMs
        return Pair(totalOffsetMs, correlation.confidence)
    }

    /** Whether the correlation was strong enough to trust, at the shared floor. */
    internal fun isConfident(confidence: Double): Boolean = confidence >= MIN_CONFIDENCE

    /**
     * Calculates or retrieves the time offset (in milliseconds) from [currentSong]
     * to [targetStreamUri]. Clamped to fallback 0L if analysis fails or times out.
     *
     * @param streamUrl The real stream URL; the cache is keyed by it, not by the
     * generation-marked cache URI in [targetStreamUri].
     */
    suspend fun findStreamOffsetMs(
        context: Context?,
        mediaId: String,
        currentPosMs: Long,
        currentSong: Song?,
        targetStreamUri: Uri,
        streamUrl: String,
    ): Long = withTimeoutOrNull(TIMEOUT_MS) {
        getCachedOffsetMs(mediaId, streamUrl)?.let { return@withTimeoutOrNull it }

        withContext(Dispatchers.IO) {
            // Pre-warm 1.5 MB of audio data in background
            runCatching { AudioCache.warmRange(targetStreamUri, 0L, ANALYSIS_WARM_BYTES) }

            val sourceDs = if (context == null || currentSong == null) {
                null
            } else {
                VersionAudioAligner.openDataSource(context, currentSong)
            }
            val targetDs = StreamAudioSource.open(context, targetStreamUri)

            if (sourceDs == null || targetDs == null) {
                StreamAudioSource.safeClose(sourceDs)
                StreamAudioSource.safeClose(targetDs)
                TrackLog.w(TAG, "Cannot open data sources for stream alignment on $mediaId")
                return@withContext 0L
            }

            try {
                // Primary: Try opening window [0.0 .. 15.0s]
                var sourceDecode = AudioDecoder.decodeRegion(
                    sourceDs,
                    PRIMARY_WINDOW_START_SEC,
                    PRIMARY_WINDOW_END_SEC,
                    abort = { !isActive },
                )
                var targetDecode = AudioDecoder.decodeRegion(
                    targetDs,
                    PRIMARY_WINDOW_START_SEC,
                    PRIMARY_WINDOW_END_SEC,
                    abort = { !isActive },
                )

                // Secondary: Fall back around current playback position if primary missing/evicted
                if (sourceDecode == null || targetDecode == null ||
                    sourceDecode.first.samples.isEmpty() || targetDecode.first.samples.isEmpty()
                ) {
                    val (secStart, secEnd) = resolveWindow(currentPosMs)
                    sourceDecode = AudioDecoder.decodeRegion(sourceDs, secStart, secEnd, abort = { !isActive })
                    targetDecode = AudioDecoder.decodeRegion(targetDs, secStart, secEnd, abort = { !isActive })
                }

                if (sourceDecode == null || targetDecode == null ||
                    sourceDecode.first.samples.isEmpty() || targetDecode.first.samples.isEmpty()
                ) {
                    ensureActive()
                    TrackLog.w(TAG, "PCM decoding failed for stream alignment on $mediaId: source=${sourceDecode?.first?.samples?.size} target=${targetDecode?.first?.samples?.size} (null = decoder/extractor failed, 0 = nothing cached in window)")
                    return@withContext 0L
                }

                val (pcmSrc, startSrc) = sourceDecode
                val (pcmTgt, startTgt) = targetDecode
                val (offsetMs, confidence) = computeOffsetFromSignals(
                    pcmSrc.samples,
                    pcmSrc.sampleRate,
                    startSrc,
                    pcmTgt.samples,
                    pcmTgt.sampleRate,
                    startTgt,
                )

                if (isConfident(confidence)) {
                    putCachedOffsetMs(mediaId, streamUrl, offsetMs)
                    TrackLog.d(TAG, "Stream alignment for $mediaId: offset=${offsetMs}ms, confidence=$confidence")
                    return@withContext offsetMs
                }

                TrackLog.w(TAG, "Stream alignment confidence $confidence < $MIN_CONFIDENCE for $mediaId; fallback 0ms")
                0L
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Stream audio alignment failed for $mediaId", e)
                0L
            } finally {
                StreamAudioSource.safeClose(sourceDs)
                StreamAudioSource.safeClose(targetDs)
            }
        }
    } ?: run {
        TrackLog.w(TAG, "Stream alignment timed out after ${TIMEOUT_MS}ms for $mediaId; fallback 0ms")
        0L
    }
}

/**
 * The alignment already computed, or 0L once [waitMs] passes.
 *
 * The measurement runs alongside the audition, so it is normally finished by
 * the time the swap is ready. When it is not, landing unaligned beats stalling
 * the player for the rest of the deadline; [job] keeps running either way and
 * caches its answer for the next upgrade.
 */
internal suspend fun awaitAlignmentOrZero(job: Deferred<Long>?, waitMs: Long): Long =
    if (job == null) 0L else withTimeoutOrNull(waitMs) { job.await() } ?: 0L

