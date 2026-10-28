package com.music.bitchord

import com.music.bitchord.playback.smart.VersionAudioAligner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

/**
 * The shift a quality upgrade's crossfade lands with — see
 * [VersionAudioAligner.renditionOffsetMs]. Two renditions of one master are
 * the same envelope with a pad of silence in front of one of them, so these
 * plant exactly that and ask for it back; and since a crossfade between them
 * is clean at zero, everything doubtful must come back as no shift at all.
 */
class UpgradeAlignmentTest {

    /** Eighteen seconds of envelope at the aligner's 10 ms hop. */
    private fun envelope(seed: Int, frames: Int = 1_800) = Random(seed).let { r -> FloatArray(frames) { r.nextFloat() } }

    /** [source] with [padFrames] of near-silence in front: the upgraded copy's shape. */
    private fun padded(source: FloatArray, padFrames: Int) = FloatArray(source.size) { i ->
        if (i < padFrames) 0.001f else source[i - padFrames]
    }

    @Test
    fun `a pad in front of the upgrade is measured as a forward shift`() {
        val source = envelope(seed = 1)
        // 350 ms of extra silence at the head of the FLAC.
        val shift = VersionAudioAligner.renditionOffsetMs(source, padded(source, 35), startDiffMs = 0)
        assertEquals(350L, shift)
    }

    @Test
    fun `a pad in front of the playing copy is a backward shift`() {
        val target = envelope(seed = 2)
        val shift = VersionAudioAligner.renditionOffsetMs(padded(target, 120), target, startDiffMs = 0)
        assertEquals(-1_200L, shift)
    }

    @Test
    fun `where each decode really started is folded in`() {
        val source = envelope(seed = 3)
        val shift = VersionAudioAligner.renditionOffsetMs(source, padded(source, 35), startDiffMs = -40)
        assertEquals(310L, shift)
    }

    /**
     * Ten milliseconds used to be rounded away as inaudible. Two copies of one
     * song summed 10 ms apart are a comb filter, so a measured hop is applied.
     */
    @Test
    fun `a single hop is measured rather than discarded`() {
        val source = envelope(seed = 4)
        assertEquals(10L, VersionAudioAligner.renditionOffsetMs(source, padded(source, 1), startDiffMs = 0))
        assertEquals(0L, VersionAudioAligner.renditionOffsetMs(source, source.copyOf(), startDiffMs = 0))
    }

    /** A different cut, or noise: crossfading unshifted beats trusting a weak peak. */
    @Test
    fun `two unrelated openings are not aligned at all`() {
        assertNull(VersionAudioAligner.renditionOffsetMs(envelope(seed = 5), envelope(seed = 6), startDiffMs = 0))
    }

    /**
     * A band-limited signal that can be sampled at any rate and at any
     * (fractional) shift, so the two "renditions" below are the same audio at
     * different sample rates with an exactly known pad in front of one.
     */
    private fun tone(seed: Int, rate: Double, seconds: Double, delaySec: Double): FloatArray {
        val r = Random(seed)
        val freqs = DoubleArray(40) { 80.0 + r.nextDouble() * 3_400.0 }
        val amps = DoubleArray(40) { 0.2 + r.nextDouble() }
        val phases = DoubleArray(40) { r.nextDouble() * 2 * Math.PI }
        return FloatArray((seconds * rate).toInt()) { i ->
            val t = i / rate - delaySec
            var v = 0.0
            for (k in freqs.indices) v += amps[k] * Math.sin(2 * Math.PI * freqs[k] * t + phases[k])
            (v / 40).toFloat()
        }
    }

    @Test
    fun `the waveform pins a 12 ms pad to the tenth of a millisecond across sample rates`() {
        val source = tone(seed = 11, rate = 48_000.0, seconds = 12.0, delaySec = 0.0)
        val target = tone(seed = 11, rate = 44_100.0, seconds = 12.0, delaySec = 0.01237)
        // The envelope would have said 10 or 20 ms.
        val lag = VersionAudioAligner.refineLagMs(source, 48_000.0, target, 44_100.0, coarseLagMs = 10.0)
        assertNotNull(lag)
        assertEquals(12.37, lag!!, 0.15)
    }

    @Test
    fun `the waveform corrects an envelope that was a frame or two out in either direction`() {
        val source = tone(seed = 12, rate = 48_000.0, seconds = 12.0, delaySec = 0.0)
        val target = tone(seed = 12, rate = 48_000.0, seconds = 12.0, delaySec = -0.0305)
        // The target leads the source by 30.5 ms; the envelope guessed 0.
        val lag = VersionAudioAligner.refineLagMs(source, 48_000.0, target, 48_000.0, coarseLagMs = -20.0)
        assertNotNull(lag)
        assertEquals(-30.5, lag!!, 0.15)
    }

    @Test
    fun `unrelated waveforms leave the envelope's figure alone`() {
        val a = tone(seed = 13, rate = 48_000.0, seconds = 12.0, delaySec = 0.0)
        val b = tone(seed = 14, rate = 48_000.0, seconds = 12.0, delaySec = 0.0)
        assertNull(VersionAudioAligner.refineLagMs(a, 48_000.0, b, 48_000.0, coarseLagMs = 0.0))
    }

    @Test
    fun `a pad outside the fine search is refused rather than clipped`() {
        val source = tone(seed = 15, rate = 48_000.0, seconds = 12.0, delaySec = 0.0)
        val target = tone(seed = 15, rate = 48_000.0, seconds = 12.0, delaySec = 0.2)
        assertNull(VersionAudioAligner.refineLagMs(source, 48_000.0, target, 48_000.0, coarseLagMs = 0.0))
    }

    /** Further apart than a mastering pad: the peak can only sit on the search's edge. */
    @Test
    fun `a lag beyond the search is refused rather than clipped`() {
        val source = envelope(seed = 7)
        assertNull(VersionAudioAligner.renditionOffsetMs(source, padded(source, 900), startDiffMs = 0))
    }

    // When listening cannot line the copies up, their lengths are the only evidence left.

    @Test
    fun `lengths that agree to within a few milliseconds are taken as unshifted`() {
        assertEquals(0L, VersionAudioAligner.offsetFromLengths(upgradedMs = 247_637, playingMs = 247_650))
        assertEquals(0L, VersionAudioAligner.offsetFromLengths(upgradedMs = 247_637, playingMs = 247_597))
    }

    @Test
    fun `lengths that differ by a pad are not taken as unshifted`() {
        assertNull(VersionAudioAligner.offsetFromLengths(upgradedMs = 247_637, playingMs = 247_800))
        assertNull(VersionAudioAligner.offsetFromLengths(upgradedMs = 247_637, playingMs = 247_000))
    }

    @Test
    fun `a length that is not known says nothing`() {
        assertNull(VersionAudioAligner.offsetFromLengths(upgradedMs = null, playingMs = 247_637))
        assertNull(VersionAudioAligner.offsetFromLengths(upgradedMs = 247_637, playingMs = null))
        assertNull(VersionAudioAligner.offsetFromLengths(upgradedMs = 0, playingMs = 0))
    }
}
