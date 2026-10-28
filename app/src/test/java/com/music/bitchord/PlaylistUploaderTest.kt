package com.music.bitchord

import com.music.bitchord.data.spotify.ExportErrorKind
import com.music.bitchord.data.spotify.ExportLedger
import com.music.bitchord.data.spotify.ExportState
import com.music.bitchord.data.spotify.LedgerEntry
import com.music.bitchord.data.spotify.PlaylistUploader
import com.music.bitchord.data.spotify.YouTubeTarget
import com.music.bitchord.data.spotify.exportErrorKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class PlaylistUploaderTest {
    private class MemoryLedger : ExportLedger {
        val entries = mutableMapOf<String, LedgerEntry>()
        override fun get(localId: String) = entries[localId]
        override fun put(localId: String, entry: LedgerEntry) { entries[localId] = entry }
        override fun remove(localId: String) { entries.remove(localId) }
    }

    /** A YouTube that records what it was sent and fails on request. */
    private class FakeTarget : YouTubeTarget {
        val calls = mutableListOf<String>()
        val received = mutableListOf<String>()
        /** Errors to throw, one per request, in order; null lets that request through. */
        val script = ArrayDeque<Exception?>()
        var created = 0

        private fun next() { script.removeFirstOrNull()?.let { throw it } }

        override suspend fun create(title: String, videoIds: List<String>): String {
            next()
            calls += "create:${videoIds.size}"
            received += videoIds
            return "PL${++created}"
        }

        override suspend fun add(playlistId: String, videoIds: List<String>) {
            next()
            calls += "add:$playlistId:${videoIds.size}"
            received += videoIds
        }
    }

    private val pauses = mutableListOf<Long>()
    private val target = FakeTarget()
    private val ledger = MemoryLedger()
    private val uploader = PlaylistUploader(target, ledger, pause = { pauses += it }, jitter = { it })

    private fun ids(n: Int) = List(n) { "video%06d".format(it).takeLast(11).padStart(11, 'a') }

    private fun upload(songs: List<String>, id: String = "local") =
        runBlocking { uploader.upload(id, "Mix", songs) {} }

    @Test
    fun sendsInSmallBatchesWithAPauseBetweenEach() {
        val result = upload(ids(60))

        assertEquals(ExportState.Done(60, 0, upToDate = false), result)
        assertEquals(listOf("create:25", "add:PL1:25", "add:PL1:10"), target.calls)
        assertEquals(listOf(PlaylistUploader.BASE_GAP_MS, PlaylistUploader.BASE_GAP_MS), pauses)
        assertEquals(60, ledger.get("local")!!.exported.size)
    }

    @Test
    fun skipsDuplicatesAndIdsYouTubeCannotTake() {
        val songs = ids(3) + ids(3) + listOf("", "local-file-1", "short")
        val result = upload(songs) as ExportState.Done

        assertEquals(3, result.exported)
        assertEquals(6, result.skipped)
        assertEquals(listOf("create:3"), target.calls)
    }

    @Test
    fun waitsOutARateLimitAndPacesSlowerAfterIt() {
        target.script += null // create goes through
        target.script += IllegalStateException("Client request(POST playlist) invalid: 429 Too Many Requests")
        val result = upload(ids(75))

        assertTrue(result is ExportState.Done)
        // Gap, then the long wait, then a doubled gap before the last batch.
        assertEquals(
            listOf(
                PlaylistUploader.BASE_GAP_MS,
                PlaylistUploader.RATE_LIMIT_WAITS_MS[0],
                PlaylistUploader.BASE_GAP_MS * 2,
            ),
            pauses,
        )
        assertEquals(75, target.received.size)
    }

    @Test
    fun stopsWhenTheRateLimitWillNotLiftAndCarriesOnFromWhatWasSent() {
        target.script += null
        repeat(PlaylistUploader.RATE_LIMIT_WAITS_MS.size + 1) {
            target.script += IllegalStateException("invalid: 429 Too Many Requests")
        }
        val first = upload(ids(60)) as ExportState.Failed

        assertEquals(ExportErrorKind.RATE_LIMITED, first.kind)
        assertEquals(25, first.done)
        assertEquals(PlaylistUploader.RATE_LIMIT_WAITS_MS, pauses.drop(1))
        assertEquals(25, ledger.get("local")!!.exported.size)

        target.calls.clear()
        val second = upload(ids(60))

        assertTrue(second is ExportState.Done)
        // Only the 35 that were missing, into the same playlist.
        assertEquals(listOf("add:PL1:25", "add:PL1:10"), target.calls)
        assertEquals(60, target.received.size)
    }

    @Test
    fun retriesADroppedConnectionButNotALostSignIn() {
        target.script += IOException("connection reset")
        assertTrue(upload(ids(5)) is ExportState.Done)
        assertEquals(listOf(PlaylistUploader.NETWORK_WAITS_MS[0]), pauses)

        target.script += IllegalStateException("Client request invalid: 401 Unauthorized")
        val failed = upload(ids(5), id = "other") as ExportState.Failed
        assertEquals(ExportErrorKind.SIGNED_OUT, failed.kind)
        // No wait was spent on it.
        assertEquals(1, pauses.size)
    }

    @Test
    fun sendsNothingWhenEverythingIsAlreadyThere() {
        upload(ids(10))
        target.calls.clear()

        val again = upload(ids(10))

        assertEquals(ExportState.Done(10, 0, upToDate = true), again)
        assertTrue(target.calls.isEmpty())
    }

    @Test
    fun sendsOnlyTheSongsAddedSinceTheLastExport() {
        upload(ids(10))
        target.calls.clear()

        upload(ids(14))

        assertEquals(listOf("add:PL1:4"), target.calls)
    }

    @Test
    fun startsAgainOnANewPlaylistWhenTheOldOneWasDeleted() {
        upload(ids(10))
        target.calls.clear()
        target.script += IllegalStateException("Client request invalid: 404 Not Found")

        val result = upload(ids(12))

        assertTrue(result is ExportState.Done)
        // Everything, not only the two new ones, goes to the new playlist.
        assertEquals(listOf("create:12"), target.calls)
        assertEquals("PL2", ledger.get("local")!!.youtubeId)
    }

    @Test
    fun anEmptyPlaylistIsNotAnExport() {
        val result = upload(emptyList()) as ExportState.Failed

        assertEquals(ExportErrorKind.OTHER, result.kind)
        assertNull(ledger.get("local"))
    }

    @Test
    fun classifiesFailuresByWhatCanBeDoneAboutThem() {
        assertEquals(ExportErrorKind.RATE_LIMITED, exportErrorKind(IllegalStateException("429 Too Many Requests")))
        assertEquals(ExportErrorKind.SIGNED_OUT, exportErrorKind(IllegalStateException("403 Forbidden")))
        assertEquals(ExportErrorKind.NETWORK, exportErrorKind(IOException("timeout")))
        assertEquals(ExportErrorKind.NETWORK, exportErrorKind(IllegalStateException("Server error: 503")))
        assertEquals(ExportErrorKind.OTHER, exportErrorKind(IllegalStateException("YouTube Music refused the edit (STATUS_FAILED)")))
    }

    @Test
    fun aStatusNumberInsideATitleIsNotTakenForAStatus() {
        assertEquals(ExportErrorKind.OTHER, exportErrorKind(IllegalStateException("Could not add \"Route 401 Blues\"")))
        assertEquals(ExportErrorKind.OTHER, exportErrorKind(IllegalStateException("video id a403b429c5d was refused as a duplicate")))
    }

    @Test
    fun aRepeatedAddAsksWhatTheFirstTryLeftBehind() {
        val adds = mutableListOf<List<String>>()
        var failOnce = true
        val landing = object : YouTubeTarget {
            override suspend fun create(title: String, videoIds: List<String>) = "PL1"
            override suspend fun add(playlistId: String, videoIds: List<String>) {
                adds += videoIds
                // The songs arrive, and the answer is lost on the way back.
                if (failOnce) { failOnce = false; throw IOException("connection reset") }
            }
            override suspend fun contents(playlistId: String): Set<String>? = adds.flatten().toSet()
        }
        val result = runBlocking {
            PlaylistUploader(landing, ledger, pause = { pauses += it }, jitter = { it })
                .upload("local", "Mix", ids(40)) {}
        }

        assertEquals(ExportState.Done(40, 0, upToDate = false), result)
        // One add, not two: the retry found the batch already there.
        assertEquals(1, adds.size)
        assertEquals(40, ledger.get("local")!!.exported.size)
    }
}
