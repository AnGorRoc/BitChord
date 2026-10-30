package com.music.bitchord.data.tidal

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.TrackLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The pool of providers behind the single Hi-Res Lossless source: the one
 * bundled with the app, then any the listener typed in.
 *
 * Providers are volunteers' servers and rot constantly, so nothing here is
 * trusted until probed from *this* device: [endpoints] health-checks on a
 * short memory and the source walks whatever answered. The pool is only ever
 * what ships in [BUNDLED] plus what the listener added — there is no remote
 * list, so no third party can decide which servers this app streams from, and
 * the app fetches nothing except from the providers it actually streams from.
 */
object TidalInstances {

    private const val TAG = "BitChord"

    @Serializable
    data class Entry(val url: String, val label: String = "", val verified: Boolean = false)

    data class RatedInstance(
        val url: String,
        val label: String,
        val pingMs: Long,
        val isHealthy: Boolean,
    )

    /**
     * The provider that ships with the app. tracks.monochrome.st is
     * live-verified (real search rows + `fLaC` stream bytes + artwork).
     */
    val BUNDLED = listOf(
        Entry("https://tracks.monochrome.st", "Monochrome Tracks", verified = true),
    )

    private const val FILE = "bitchord_tidal"
    private const val KEY_MANUAL_JSON = "tidal_manual_json"

    // Half an hour: probes are network, and a pool that was alive minutes
    // ago is alive now. Every search/stream would otherwise pay the slow
    // volunteer's timeout on each new track.
    private const val HEALTH_TTL_MS = 30 * 60 * 1000L

    private lateinit var prefs: SharedPreferences

    private val json = Json { ignoreUnknownKeys = true }

    /** Providers the user typed in. Probed and ranked alongside the bundled one. */
    private val _manual = MutableStateFlow<List<Entry>>(emptyList())
    val manual: StateFlow<List<Entry>> = _manual.asStateFlow()

    /** Rated instances ordered by latency / health. */
    private val _ratings = MutableStateFlow<List<RatedInstance>>(emptyList())
    val ratings: StateFlow<List<RatedInstance>> = _ratings.asStateFlow()

    private var healthAt = 0L
    private val lock = Any()

    fun init(context: Context) {
        if (this::prefs.isInitialized) return
        prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        _manual.value = readManual()
    }

    private fun readManual(): List<Entry> {
        val raw = prefs.getString(KEY_MANUAL_JSON, null) ?: return emptyList()
        return runCatching { json.decodeFromString(ListSerializer(Entry.serializer()), raw) }
            .getOrDefault(emptyList())
    }

    private fun writeManual(list: List<Entry>) {
        if (!this::prefs.isInitialized) return
        prefs.edit()
            .putString(KEY_MANUAL_JSON, json.encodeToString(ListSerializer(Entry.serializer()), list))
            .apply()
    }

    /**
     * Adds a provider the user typed in. False when [raw] is not an address
     * this can use, or when it is already one of the manual providers.
     */
    fun addManual(raw: String): Boolean {
        val url = TidalApi.normalize(raw) ?: return false
        synchronized(lock) {
            if (_manual.value.any { TidalApi.normalize(it.url) == url }) return false
            val next = _manual.value + Entry(url = url)
            _manual.value = next
            writeManual(next)
        }
        return true
    }

    /** Forgets a manual provider and drops its row from the last probe. */
    fun removeManual(url: String) {
        synchronized(lock) {
            val next = _manual.value.filterNot { TidalApi.normalize(it.url) == url }
            _manual.value = next
            _ratings.value = _ratings.value.filterNot { it.url == url }
            writeManual(next)
        }
    }

    /** Manual providers first, then the bundled one, each address once. */
    private fun candidates(): List<Entry> =
        (_manual.value + BUNDLED).distinctBy { TidalApi.normalize(it.url) ?: it.url }

    /**
     * Endpoints to try, in order: the primary URL first, then the bundled
     * and manual providers. Unreachable instances are filtered by a cached
     * health pass so a dead volunteer doesn't cost a timeout per track.
     */
    suspend fun endpoints(primaryRaw: String): List<String> {
        val primary = TidalApi.normalize(primaryRaw)
        val pool = (listOfNotNull(primary) + candidates().mapNotNull { TidalApi.normalize(it.url) })
            .distinct()
        if (pool.size <= 1) return pool
        val rated = probeAndRank(pool)
        val healthy = rated.filter { it.isHealthy }.map { it.url }
        return healthy.ifEmpty { pool }
    }

    suspend fun refreshRatings(force: Boolean = false) {
        val pool = candidates().mapNotNull { TidalApi.normalize(it.url) }.distinct()
        probeAndRank(pool, force = force)
    }

    private suspend fun probeAndRank(urls: List<String>, force: Boolean = false): List<RatedInstance> =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            synchronized(lock) {
                // Only while it still covers every address asked about: a provider
                // added since the last pass has to be probed, not assumed.
                val rated = _ratings.value
                if (!force && now - healthAt < HEALTH_TTL_MS &&
                    urls.all { url -> rated.any { it.url == url } }
                ) {
                    return@withContext rated
                }
            }
            val known = candidates()
            val results = urls.map { url ->
                async {
                    val label = known.firstOrNull { TidalApi.normalize(it.url) == url }?.label
                        ?: url.substringAfter("://").substringBefore('/')
                    val t0 = System.currentTimeMillis()
                    val ok = runCatching { TidalApi.isLive(url) }.getOrDefault(false)
                    val rtt = if (ok) System.currentTimeMillis() - t0 else Long.MAX_VALUE
                    RatedInstance(url = url, label = label, pingMs = rtt, isHealthy = ok)
                }
            }.awaitAll()

            // Order by: healthy first, then lowest ping / RTT
            val sorted = results.sortedWith(
                compareBy<RatedInstance> { !it.isHealthy }.thenBy { it.pingMs }
            )

            synchronized(lock) {
                _ratings.value = sorted
                healthAt = now
            }
            TrackLog.d(TAG, "tidal ratings updated: ${sorted.filter { it.isHealthy }.map { "${it.label} (${it.pingMs}ms)" }}")
            sorted
        }
}
