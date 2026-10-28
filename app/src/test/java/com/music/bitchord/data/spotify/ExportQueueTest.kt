package com.music.bitchord.data.spotify

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The export queue against a fake YouTube and a ledger in memory, with the
 * cool-down shortened so a test waits for tenths of a second, not quarters of
 * a minute.
 */
class ExportQueueTest {

    private class MemoryLedger(var failPuts: Boolean = false) : ExportLedger {
        val entries = mutableMapOf<String, LedgerEntry>()
        override fun get(localId: String) = entries[localId]
        override fun put(localId: String, entry: LedgerEntry) {
            if (failPuts) throw IllegalStateException("ledger is broken")
            entries[localId] = entry
        }
        override fun remove(localId: String) { entries.remove(localId) }
    }

    private class RecordingTarget : YouTubeTarget {
        val created = CopyOnWriteArrayList<String>()
        override suspend fun create(title: String, videoIds: List<String>): String {
            created += title
            return "PL-$title"
        }
        override suspend fun add(playlistId: String, videoIds: List<String>) = Unit
    }

    private lateinit var scope: CoroutineScope
    private val target = RecordingTarget()
    private val ledger = MemoryLedger()

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun queue(cooldownMs: Long = 0L) = ExportQueue(
        uploader = PlaylistUploader(target, ledger, pause = {}, jitter = { it }),
        scope = scope,
        cooldownMs = cooldownMs,
        extraRestMs = { 0L },
    )

    private fun playlist(id: String) = LocalPlaylist(
        id = id,
        title = id,
        songs = listOf(Song(videoId = "abcdefghij$id".take(11).padEnd(11, 'x'), title = id, artist = "a", thumbnailUrl = null)),
    )

    private fun ExportQueue.awaitState(id: String, accept: (ExportState?) -> Boolean) = runBlocking {
        withTimeout(5_000) { states.first { accept(it[id]) } }[id]
    }

    @Test
    fun `a queued playlist is exported and reported done`() {
        val queue = queue()
        queue.export(playlist("one"))

        val state = queue.awaitState("one") { it is ExportState.Done }

        assertEquals(ExportState.Done(1, 0, upToDate = false), state)
        assertEquals(listOf("one"), target.created)
    }

    @Test
    fun `an upload that throws unexpectedly fails that playlist and leaves the queue working`() {
        ledger.failPuts = true
        val queue = queue()
        queue.export(playlist("first"))
        val failed = queue.awaitState("first") { it is ExportState.Failed } as ExportState.Failed
        assertEquals(ExportErrorKind.OTHER, failed.kind)

        // The worker must still be there for the next one.
        ledger.failPuts = false
        queue.export(playlist("second"))
        assertTrue(queue.awaitState("second") { it is ExportState.Done } is ExportState.Done)
    }

    @Test
    fun `a playlist cancelled while the queue rests is never sent`() {
        val queue = queue(cooldownMs = 400L)
        queue.export(playlist("one"))
        queue.export(playlist("two"))
        queue.awaitState("one") { it is ExportState.Done }

        // "two" has left the queue and is resting before its turn.
        runBlocking { delay(100) }
        queue.cancel("two")

        runBlocking { delay(800) }
        assertNull(queue.states.value["two"])
        assertFalse("two" in target.created)
    }

    @Test
    fun `a playlist already queued is not queued again`() {
        val queue = queue(cooldownMs = 300L)
        queue.export(playlist("one"))
        queue.export(playlist("one"))

        queue.awaitState("one") { it is ExportState.Done }
        runBlocking { delay(500) }

        assertEquals(listOf("one"), target.created)
    }
}
