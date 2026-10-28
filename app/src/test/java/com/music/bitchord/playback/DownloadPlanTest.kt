package com.music.bitchord.playback

import com.music.bitchord.playback.DownloadPlan.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The order [DownloadPlan] works through: the playing track when it is in
 * trouble, its better copy, the next track's opening near the change, the rest
 * of the playing track, the next tracks — and nothing past their openings on a
 * metered connection.
 */
class DownloadPlanTest {

    private val mb = 1024L * 1024

    private fun current(
        remainingMs: Long = 180_000,
        bufferedAheadMs: Long = 60_000,
        missingBytes: Long = 20 * mb,
        fillDue: Boolean = missingBytes != 0L,
    ) = DownloadPlan.Current("now", remainingMs, bufferedAheadMs, missingBytes, fillDue)

    private fun upcoming(
        id: String = "next",
        prepared: Boolean = false,
        headCached: Boolean = false,
        fullyCached: Boolean = false,
        warmed: Boolean = false,
        sizeBytes: Long = 30 * mb,
    ) = DownloadPlan.Upcoming(
        mediaId = id,
        durationMs = 240_000,
        prepared = prepared,
        headCached = headCached,
        fullyCached = fullyCached,
        fillDue = !fullyCached,
        warmed = warmed,
        sizeBytes = sizeBytes,
    )

    private fun snapshot(
        playWhenReady: Boolean = true,
        stalled: Boolean = false,
        loading: Boolean = false,
        transitioning: Boolean = false,
        upgradeBusy: Boolean = false,
        upgradeLookDue: Boolean = false,
        direct: Boolean = false,
        current: DownloadPlan.Current? = current(),
        upcoming: List<DownloadPlan.Upcoming> = listOf(upcoming(), upcoming("after")),
        currentForMs: Long = 30_000,
        bandwidthBps: Long = 40_000_000,
        metered: Boolean = false,
        room: Int = 2,
        prepared: Boolean = true,
        offline: Boolean = false,
        elsewhere: Boolean = false,
    ) = DownloadPlan.Snapshot(
        prepared = prepared,
        offline = offline,
        elsewhere = elsewhere,
        playWhenReady = playWhenReady,
        stalled = stalled,
        loading = loading,
        transitioning = transitioning,
        upgradeBusy = upgradeBusy,
        upgradeLookDue = upgradeLookDue,
        direct = direct,
        current = current,
        upcoming = upcoming,
        currentForMs = currentForMs,
        bandwidthBps = bandwidthBps,
        metered = metered,
        roomForFullTracks = room,
    )

    private val ready = listOf(
        upcoming(prepared = true, headCached = true, fullyCached = true, warmed = true),
        upcoming("after", prepared = true, headCached = true, fullyCached = true, warmed = true),
    )

    @Test
    fun `nothing while offline, casting or unprepared`() {
        assertNull(DownloadPlan.next(snapshot(offline = true)))
        assertNull(DownloadPlan.next(snapshot(elsewhere = true)))
        assertNull(DownloadPlan.next(snapshot(prepared = false)))
        assertNull(DownloadPlan.next(snapshot(current = null)))
    }

    @Test
    fun `a stall leaves the whole connection to the player`() {
        assertNull(DownloadPlan.next(snapshot(stalled = true, loading = true, upgradeLookDue = true)))
    }

    @Test
    fun `a low buffer while the player loads leaves it alone`() {
        assertNull(DownloadPlan.next(snapshot(loading = true, current = current(bufferedAheadMs = 5_000))))
    }

    /**
     * Hi-res FLAC: eight megabytes of buffer, back buffer included, is about
     * twenty seconds, so the buffer always reads low. The player is at its
     * ceiling rather than short of data, and the rest of the file is worth
     * writing for it.
     */
    @Test
    fun `a low buffer at the byte ceiling still lets the track be filled`() {
        val plan = DownloadPlan.next(snapshot(loading = false, current = current(bufferedAheadMs = 12_000)))
        assertEquals(Task.FillCurrent, plan)
    }

    @Test
    fun `a download that would not arrive in time is urgent`() {
        // 40 MB at 1 Mbps is over five minutes, against one minute left.
        val slow = snapshot(
            loading = true,
            bandwidthBps = 1_000_000,
            current = current(remainingMs = 60_000, missingBytes = 40 * mb),
        )
        assertNull(DownloadPlan.next(slow))
    }

    @Test
    fun `a blend in progress holds everything`() {
        assertNull(DownloadPlan.next(snapshot(transitioning = true)))
    }

    @Test
    fun `the better copy is looked for before the rest of the track is fetched`() {
        assertEquals(Task.CurrentUpgrade, DownloadPlan.next(snapshot(upgradeLookDue = true)))
    }

    @Test
    fun `while an upgrade is being proved the track is not filled under it`() {
        val plan = DownloadPlan.next(snapshot(upgradeBusy = true, upcoming = ready))
        assertNull(plan)
    }

    @Test
    fun `the next opening jumps the queue near the change`() {
        val plan = DownloadPlan.next(snapshot(current = current(remainingMs = 40_000)))
        assertEquals(Task.Prepare(0), plan)
    }

    @Test
    fun `otherwise the playing track is completed first`() {
        assertEquals(Task.FillCurrent, DownloadPlan.next(snapshot()))
    }

    @Test
    fun `paused, the playing track is completed`() {
        assertEquals(Task.FillCurrent, DownloadPlan.next(snapshot(playWhenReady = false)))
    }

    @Test
    fun `then the next track is prepared, once the playing one has settled`() {
        val done = current(missingBytes = 0)
        assertEquals(Task.Prepare(0), DownloadPlan.next(snapshot(current = done)))
        assertEquals(Task.WarmUrls, DownloadPlan.next(snapshot(current = done, currentForMs = 2_000)))
    }

    @Test
    fun `then the next track is fetched in full`() {
        val plan = DownloadPlan.next(
            snapshot(
                current = current(missingBytes = 0),
                upcoming = listOf(upcoming(prepared = true, headCached = true, warmed = true), upcoming("after", warmed = true)),
            ),
        )
        assertEquals(Task.FillNext(0), plan)
    }

    @Test
    fun `the next track waits until the playing one is complete`() {
        val plan = DownloadPlan.next(
            snapshot(
                current = current(missingBytes = 5 * mb, fillDue = false),
                upcoming = listOf(upcoming(prepared = true, headCached = true, warmed = true), upcoming("after", warmed = true)),
            ),
        )
        assertNull(plan)
    }

    @Test
    fun `then the second track, prepared and fetched in full`() {
        val upcoming = listOf(ready[0], upcoming("after", warmed = true))
        assertEquals(Task.Prepare(1), DownloadPlan.next(snapshot(current = current(missingBytes = 0), upcoming = upcoming)))
        val prepared = listOf(ready[0], upcoming("after", prepared = true, headCached = true, warmed = true))
        assertEquals(Task.FillNext(1), DownloadPlan.next(snapshot(current = current(missingBytes = 0), upcoming = prepared)))
    }

    @Test
    fun `the second track is left alone without time to spare`() {
        // 30 MB at 0.5 Mbps is over eight minutes, against twenty seconds of
        // this track and four of the next.
        val tight = snapshot(
            bandwidthBps = 500_000,
            current = current(remainingMs = 20_000, missingBytes = 0),
            upcoming = listOf(ready[0], upcoming("after", warmed = true)),
        )
        assertNull(DownloadPlan.next(tight))
    }

    @Test
    fun `paused, there is always time for the second track`() {
        val paused = snapshot(
            playWhenReady = false,
            bandwidthBps = 500_000,
            current = current(remainingMs = 20_000, missingBytes = 0),
            upcoming = listOf(ready[0], upcoming("after", warmed = true)),
        )
        assertEquals(Task.Prepare(1), DownloadPlan.next(paused))
    }

    @Test
    fun `a metered connection stops at the next opening`() {
        val metered = snapshot(
            metered = true,
            current = current(missingBytes = 0),
            upcoming = listOf(upcoming(prepared = true, headCached = true, warmed = true), upcoming("after", warmed = true)),
        )
        assertNull(DownloadPlan.next(metered))
        // ...but still completes the track playing, which will be heard anyway.
        assertEquals(Task.FillCurrent, DownloadPlan.next(snapshot(metered = true)))
    }

    @Test
    fun `no room in the cache, no full downloads ahead`() {
        val full = snapshot(
            room = 0,
            current = current(missingBytes = 0),
            upcoming = listOf(upcoming(prepared = true, headCached = true, warmed = true)),
        )
        assertNull(DownloadPlan.next(full))
    }

    @Test
    fun `direct source stream keeps to the track playing`() {
        val direct = snapshot(direct = true, current = current(missingBytes = 0))
        assertNull(DownloadPlan.next(direct))
        assertEquals(Task.FillCurrent, DownloadPlan.next(snapshot(direct = true)))
    }

    @Test
    fun `nothing left to do is nothing`() {
        assertNull(DownloadPlan.next(snapshot(current = current(missingBytes = 0), upcoming = ready)))
    }
}
