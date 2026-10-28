package com.music.bitchord.data

import android.content.SharedPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Writes a cache's preferences one change at a time, in the order they were
 * asked for, off the caller's thread.
 *
 * The caches used to launch each save on the multi-threaded IO pool. Two saves
 * of the same key could then run in either order, and the older one landing
 * last left a stale copy on disk under a newer one in memory. One lane keeps
 * the order; a failed write is logged, not lost silently and not fatal.
 *
 * [lane] is a parameter so tests can run the writes where they can see them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PrefsWriter(
    private val prefs: SharedPreferences,
    lane: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
) {
    private val scope = CoroutineScope(SupervisorJob() + lane)

    /** Applies [change] to a fresh editor, after everything asked before it. */
    fun write(change: SharedPreferences.Editor.() -> Unit) {
        scope.launch {
            try {
                prefs.edit().apply(change).apply()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TrackLog.w(TAG, "cache write failed: ${e.message}")
            }
        }
    }

    fun put(key: String, value: String) = write { putString(key, value) }

    fun remove(key: String) = write { remove(key) }

    fun clear() = write { clear() }

    private companion object {
        const val TAG = "BitChord"
    }
}

/**
 * The version of what a cache wrote. Anything older is dropped on start: the
 * caches hold only what a network call can rebuild, and reading a shape this
 * build no longer means as it did would show wrong data where a refetch would
 * show right data.
 */
internal object CacheSchema {
    const val KEY = "schema"

    /** Clears [prefs] when it was written under another [version]; true when it did. */
    fun ensure(prefs: SharedPreferences, version: Int): Boolean {
        if (prefs.getInt(KEY, 0) == version) return false
        prefs.edit().clear().putInt(KEY, version).apply()
        return true
    }
}
