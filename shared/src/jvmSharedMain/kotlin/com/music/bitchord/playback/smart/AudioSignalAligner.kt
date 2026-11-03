package com.music.bitchord.playback.smart

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Platform-independent DSP routines for computing audio energy envelopes
 * and FFT-based normalized cross-correlation across audio signals.
 */
object AudioSignalAligner {

    const val DEFAULT_HOP_MS = 10.0
    const val DEFAULT_WIN_MS = 40.0
    const val DEFAULT_MAX_LAG_SECONDS = 40.0
    const val DEFAULT_MIN_CONFIDENCE = 0.40

    data class Correlation(
        val lagFrames: Int,
        val confidence: Double,
        val floor: Int,
        val ceiling: Int,
    )

    fun computeEnergyEnvelope(
        samples: FloatArray,
        sampleRate: Double,
        hopMs: Double = DEFAULT_HOP_MS,
        winMs: Double = DEFAULT_WIN_MS,
    ): FloatArray {
        val hopSamples = max(1, (sampleRate * (hopMs / 1000.0)).toInt())
        val winSamples = max(hopSamples, (sampleRate * (winMs / 1000.0)).toInt())
        val numFrames = max(0, (samples.size - winSamples) / hopSamples)
        if (numFrames <= 0) return FloatArray(0)

        val env = FloatArray(numFrames)
        for (i in 0 until numFrames) {
            val start = i * hopSamples
            val end = min(samples.size, start + winSamples)
            var sumSq = 0.0
            for (j in start until end) {
                val s = samples[j]
                sumSq += s * s
            }
            env[i] = sqrt(sumSq / (end - start)).toFloat()
        }
        return env
    }

    fun crossCorrelate(
        sourceEnv: FloatArray,
        targetEnv: FloatArray,
        hopMs: Double = DEFAULT_HOP_MS,
        maxLagSec: Double = DEFAULT_MAX_LAG_SECONDS,
    ): Correlation {
        if (sourceEnv.isEmpty() || targetEnv.isEmpty()) return Correlation(0, 0.0, 0, 0)

        val maxLagFrames = (maxLagSec * 1000.0 / hopMs).toInt()
        val minOverlapFrames = (3.0 * 1000.0 / hopMs).toInt() // At least 3 seconds overlap

        var bestLag = 0
        var bestCorrelation = -1.0

        val minLag = max(-maxLagFrames, -(sourceEnv.size - minOverlapFrames))
        val maxLag = min(maxLagFrames, targetEnv.size - minOverlapFrames)

        val crossTerms = crossTermsByLag(sourceEnv, targetEnv)

        val srcPrefix = DoubleArray(sourceEnv.size + 1)
        val srcSqPrefix = DoubleArray(sourceEnv.size + 1)
        for (i in sourceEnv.indices) {
            val v = sourceEnv[i].toDouble()
            srcPrefix[i + 1] = srcPrefix[i] + v
            srcSqPrefix[i + 1] = srcSqPrefix[i] + v * v
        }

        val tgtPrefix = DoubleArray(targetEnv.size + 1)
        val tgtSqPrefix = DoubleArray(targetEnv.size + 1)
        for (i in targetEnv.indices) {
            val v = targetEnv[i].toDouble()
            tgtPrefix[i + 1] = tgtPrefix[i] + v
            tgtSqPrefix[i + 1] = tgtSqPrefix[i] + v * v
        }

        for (lag in minLag..maxLag) {
            val srcStart = max(0, -lag)
            val srcEnd = min(sourceEnv.size, targetEnv.size - lag)
            val overlapLen = srcEnd - srcStart
            if (overlapLen < minOverlapFrames) continue

            val tgtStart = srcStart + lag
            val tgtEnd = srcEnd + lag

            val sumSrc = srcPrefix[srcEnd] - srcPrefix[srcStart]
            val sumSrcSq = srcSqPrefix[srcEnd] - srcSqPrefix[srcStart]
            val sumTgt = tgtPrefix[tgtEnd] - tgtPrefix[tgtStart]
            val sumTgtSq = tgtSqPrefix[tgtEnd] - tgtSqPrefix[tgtStart]

            val num = crossTerms[lag + sourceEnv.size - 1] - sumSrc * sumTgt / overlapLen
            val denSrc = sumSrcSq - sumSrc * sumSrc / overlapLen
            val denTgt = sumTgtSq - sumTgt * sumTgt / overlapLen

            val denom = sqrt(max(0.0, denSrc) * max(0.0, denTgt))
            val r = if (denom > 1e-9) (num / denom).coerceIn(-1.0, 1.0) else 0.0

            if (r > bestCorrelation) {
                bestCorrelation = r
                bestLag = lag
            }
        }

        return Correlation(
            lagFrames = bestLag,
            confidence = max(0.0, bestCorrelation),
            floor = minLag,
            ceiling = maxLag,
        )
    }

    private fun crossTermsByLag(source: FloatArray, target: FloatArray): DoubleArray {
        val n = source.size
        val m = target.size
        var fftLen = 1
        while (fftLen < n + m) fftLen = fftLen shl 1

        val srcRe = DoubleArray(fftLen)
        val srcIm = DoubleArray(fftLen)
        for (i in 0 until n) srcRe[i] = source[i].toDouble()
        val tgtRe = DoubleArray(fftLen)
        val tgtIm = DoubleArray(fftLen)
        for (i in 0 until m) tgtRe[i] = target[i].toDouble()

        fft(srcRe, srcIm)
        fft(tgtRe, tgtIm)

        for (i in 0 until fftLen) {
            val a = srcRe[i]
            val b = -srcIm[i]
            val c = tgtRe[i]
            val d = tgtIm[i]
            srcRe[i] = a * c - b * d
            srcIm[i] = a * d + b * c
        }
        fft(srcRe, srcIm, inverse = true)

        val result = DoubleArray(n + m - 1)
        for (lag in -(n - 1) until m) {
            val bin = ((lag % fftLen) + fftLen) % fftLen
            result[lag + n - 1] = srcRe[bin]
        }
        return result
    }

    private fun fft(re: DoubleArray, im: DoubleArray, inverse: Boolean = false) {
        val n = re.size

        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }

        var len = 2
        while (len <= n) {
            val angle = 2.0 * PI / len * (if (inverse) 1.0 else -1.0)
            val stepRe = cos(angle)
            val stepIm = sin(angle)
            for (start in 0 until n step len) {
                var twiddleRe = 1.0
                var twiddleIm = 0.0
                for (k in 0 until len / 2) {
                    val evenRe = re[start + k]
                    val evenIm = im[start + k]
                    val oddRe =
                        re[start + k + len / 2] * twiddleRe - im[start + k + len / 2] * twiddleIm
                    val oddIm =
                        re[start + k + len / 2] * twiddleIm + im[start + k + len / 2] * twiddleRe
                    re[start + k] = evenRe + oddRe
                    im[start + k] = evenIm + oddIm
                    re[start + k + len / 2] = evenRe - oddRe
                    im[start + k + len / 2] = evenIm - oddIm
                    val nextRe = twiddleRe * stepRe - twiddleIm * stepIm
                    twiddleIm = twiddleRe * stepIm + twiddleIm * stepRe
                    twiddleRe = nextRe
                }
            }
            len = len shl 1
        }

        if (inverse) {
            for (i in 0 until n) {
                re[i] /= n
                im[i] /= n
            }
        }
    }
}
