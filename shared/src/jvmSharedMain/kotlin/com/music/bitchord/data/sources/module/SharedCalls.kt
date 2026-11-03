package com.music.bitchord.data.sources.module

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * One in-flight-or-recent answer per key, shared by everyone who asks for it.
 */
class SharedCalls<T>(
    private val ttlMs: Long = 0L,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /** Where a reuse is reported, so a log still shows one line per question asked. */
    private val log: (String) -> Unit = {},
    private val now: () -> Long = { System.currentTimeMillis() },
    /**
     * Whether a completed answer is a miss. A miss is kept only for
     * [missTtlMs], so one that came from a server having a bad minute is asked
     * again soon rather than believed for the whole of [ttlMs].
     */
    private val isMiss: (T) -> Boolean = { false },
    private val missTtlMs: Long = ttlMs,
) {

    /**
     * The work behind one key, and when it was started.
     */
    private class Pending<T>(val work: Deferred<Result<T>>, val startedAtMs: Long, @Volatile var ttlMs: Long)

    private val entries = ConcurrentHashMap<String, Pending<T>>()
    private val lock = Mutex()

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun get(key: String, describe: () -> String = { key }, produce: suspend () -> Result<T>): Result<T> {
        val entry = lock.withLock {
            val live = entries[key]?.takeIf { it.work.isActive || now() - it.startedAtMs < it.ttlMs }
            if (live != null) {
                log(describe() + if (live.work.isActive) " — ALREADY RUNNING" else " — CACHE HIT")
                live
            } else {
                if (entries.size >= MAX_ENTRIES) prune()
                val work = scope.async { produce() }
                Pending(work, now(), ttlMs).also { pending ->
                    entries[key] = pending
                    work.invokeOnCompletion { cause ->
                        if (cause != null || work.getCompleted().isFailure) {
                            entries.remove(key, pending)
                        }
                    }
                }
            }
        }
        return entry.work.await().also { result ->
            if (result.isSuccess && isMiss(result.getOrThrow())) {
                entry.ttlMs = minOf(entry.ttlMs, missTtlMs)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun run(key: String, produce: suspend () -> T): T {
        val res = get(key = key, describe = { key }) {
            runCatching { produce() }
        }
        return res.getOrThrow()
    }

    /** Whether [key] is being answered or already was recently, so asking costs nothing. */
    fun has(key: String): Boolean =
        entries[key]?.let { it.work.isActive || now() - it.startedAtMs < it.ttlMs } == true

    /** Everything held, dropped — the configuration it was all about is gone. */
    fun clear() = entries.clear()

    /**
     * Drops answers that have already completed while preserving work still in
     * flight.
     */
    fun clearCompleted() {
        entries.entries.removeIf { !it.value.work.isActive }
    }

    /** For tests and diagnostics: how many answers are being held. */
    fun size() = entries.size

    private fun prune() {
        val at = now()
        entries.entries.removeIf { !it.value.work.isActive && at - it.value.startedAtMs >= it.value.ttlMs }
        if (entries.size >= MAX_ENTRIES) {
            entries.entries
                .filterNot { it.value.work.isActive }
                .minByOrNull { it.value.startedAtMs }
                ?.let { entries.remove(it.key, it.value) }
        }
    }

    companion object {
        const val MAX_ENTRIES = 128
    }
}
