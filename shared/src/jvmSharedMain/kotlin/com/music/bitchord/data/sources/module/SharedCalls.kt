package com.music.bitchord.data.sources.module

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Joins callers asking for the same thing at the same moment onto a single
 * in-flight call.
 *
 * When a track is about to play, the resolver and the preloader — or a song
 * switch that races its own retry — can ask the same source for the same
 * search or stream within milliseconds of each other. Letting both go out not
 * only doubles the traffic to volunteer servers, it trips per-host rate
 * gates on the second call while the first is still on the wire.
 *
 * Only *concurrent* calls share: once the first finishes, the key is gone and
 * the next caller makes a fresh request. This is not a cache.
 */
class SharedCalls<T> {
    private val mutex = Mutex()
    private val inFlight = HashMap<String, CompletableDeferred<T>>()

    suspend fun run(key: String, block: suspend () -> T): T {
        val existing: CompletableDeferred<T>?
        val mine: CompletableDeferred<T>?
        mutex.withLock {
            val found = inFlight[key]
            if (found != null) {
                existing = found
                mine = null
            } else {
                existing = null
                mine = CompletableDeferred<T>().also { inFlight[key] = it }
            }
        }
        if (existing != null) return existing.await()
        val deferred = mine!!
        val result = runCatching { block() }
        mutex.withLock { inFlight.remove(key, deferred) }
        result.fold(
            onSuccess = {
                deferred.complete(it)
                return it
            },
            onFailure = {
                deferred.completeExceptionally(it)
                throw it
            },
        )
    }
}
