package com.music.bitchord.playback

import com.music.bitchord.playback.LosslessGuard.State
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LosslessGuardTest {
    private val watching = State(
        hasItem = true,
        downgradeEnabled = true,
        declaredLossless = true,
        onOriginal = false,
        swapping = false,
        transitioning = false,
        inParty = false,
        fullyCached = false,
        noFallback = false,
    )

    @Test
    fun `a fetched lossless stream with a fallback is watched`() {
        assertTrue(LosslessGuard.shouldSample(watching))
    }

    @Test
    fun `every reason to leave the stream alone is enough`() {
        assertFalse(LosslessGuard.shouldSample(watching.copy(hasItem = false)))
        assertFalse(LosslessGuard.shouldSample(watching.copy(downgradeEnabled = false)))
        assertFalse(LosslessGuard.shouldSample(watching.copy(declaredLossless = false)))
        assertFalse(LosslessGuard.shouldSample(watching.copy(onOriginal = true)))
        assertFalse(LosslessGuard.shouldSample(watching.copy(swapping = true)))
        assertFalse(LosslessGuard.shouldSample(watching.copy(transitioning = true)))
        assertFalse(LosslessGuard.shouldSample(watching.copy(inParty = true)))
        assertFalse(LosslessGuard.shouldSample(watching.copy(fullyCached = true)))
        assertFalse(LosslessGuard.shouldSample(watching.copy(noFallback = true)))
    }
}
