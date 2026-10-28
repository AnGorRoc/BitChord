package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic [BoundedCacheReads] locks by. A segment boundary that drifts
 * with the read position would leave a fill and a player working along
 * different grids, each forever overlapping the other's next range.
 */
class BoundedCacheReadsTest {

    private val mb = 1024L * 1024

    @Test
    fun `a read inside a segment stops at its end`() {
        assertEquals(2 * mb, BoundedCacheReads.boundaryAfter(300L, 2 * mb, 40 * mb))
        assertEquals(4 * mb, BoundedCacheReads.boundaryAfter(3 * mb, 2 * mb, 40 * mb))
    }

    @Test
    fun `a read starting on a boundary takes the whole next segment`() {
        assertEquals(6 * mb, BoundedCacheReads.boundaryAfter(4 * mb, 2 * mb, 40 * mb))
    }

    @Test
    fun `the last segment ends at the file`() {
        assertEquals(41 * mb, BoundedCacheReads.boundaryAfter(40 * mb + 5, 2 * mb, 41 * mb))
    }

    @Test
    fun `a claim overlaps exactly the ranges it shares bytes with`() {
        val claim = BoundedCacheReads.Claim("v#alt", start = 4 * mb, end = 6 * mb)
        assertTrue(claim.overlaps(5 * mb, 7 * mb))
        assertTrue(claim.overlaps(3 * mb, 4 * mb + 1))
        assertFalse(claim.overlaps(6 * mb, 8 * mb))
        assertFalse(claim.overlaps(2 * mb, 4 * mb))
    }
}
