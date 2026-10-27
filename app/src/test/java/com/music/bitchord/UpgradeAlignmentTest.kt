package com.music.bitchord

import com.music.bitchord.playback.smart.VersionAudioAligner
import org.junit.Assert.assertEquals
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

    /** Twelve seconds of envelope at the aligner's 10 ms hop. */
    private fun envelope(seed: Int, frames: Int = 1_200) = Random(seed).let { r -> FloatArray(frames) { r.nextFloat() } }

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

    @Test
    fun `a shift too small to hear is no shift`() {
        val source = envelope(seed = 4)
        assertEquals(0L, VersionAudioAligner.renditionOffsetMs(source, padded(source, 1), startDiffMs = 0))
        assertEquals(0L, VersionAudioAligner.renditionOffsetMs(source, source.copyOf(), startDiffMs = 0))
    }

    /** A different cut, or noise: crossfading unshifted beats trusting a weak peak. */
    @Test
    fun `two unrelated openings are not aligned at all`() {
        assertNull(VersionAudioAligner.renditionOffsetMs(envelope(seed = 5), envelope(seed = 6), startDiffMs = 0))
    }

    /** Further apart than a mastering pad: the peak can only sit on the search's edge. */
    @Test
    fun `a lag beyond the search is refused rather than clipped`() {
        val source = envelope(seed = 7)
        assertNull(VersionAudioAligner.renditionOffsetMs(source, padded(source, 600), startDiffMs = 0))
    }
}
