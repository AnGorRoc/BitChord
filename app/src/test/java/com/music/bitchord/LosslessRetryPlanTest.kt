package com.music.bitchord

import com.music.bitchord.playback.LosslessRetryPlan
import org.junit.Assert.assertEquals
import org.junit.Test

class LosslessRetryPlanTest {

    @Test
    fun `three tries ten seconds apart, then three thirty seconds apart, then every minute`() {
        val plan = LosslessRetryPlan()
        val waits = List(9) { plan.nextDelayMs() }
        assertEquals(
            listOf(10_000L, 10_000L, 10_000L, 30_000L, 30_000L, 30_000L, 60_000L, 60_000L, 60_000L),
            waits,
        )
    }

    @Test
    fun `a successful lossless swap starts the schedule over`() {
        val plan = LosslessRetryPlan()
        repeat(5) { plan.nextDelayMs() }
        plan.reset()
        assertEquals(10_000L, plan.nextDelayMs())
    }
}
