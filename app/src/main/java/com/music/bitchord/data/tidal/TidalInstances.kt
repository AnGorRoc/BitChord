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
import kotlinx.serialization.json.Json
import okhttp3.Request

/**
 * The pool of hifi-api instances behind the single Tidal source: the
 * primary URL from settings first, then live community instances.
 *
 * Instances are volunteers' servers and rot constantly (Tidal bans
 * aggressively), so nothing here is trusted until probed from *this*
 * device: [endpoints] health-checks on a short memory and the source walks
 * whatever answered. The public list refreshes from [REMOTE_URL] at most
 * daily while [autoUpdate] is on — off, and the app never fetches anything
 * except the instances it actually streams from.
 */
object TidalInstances {

    private const val TAG = "BitChord"

    /** Curated list, refreshed without an app release by editing this file on main. */
    const val REMOTE_URL =
        "https://raw.githubusercontent.com/OreoMuncher45/BitChord/main/tidal-instances.json"

    @Serializable
    data class Entry(val url: String, val label: String = "", val verified: Boolean = false)

    data class RatedInstance(
        val url: String,
        val label: String,
        val pingMs: Long,
        val isHealthy: Boolean,
    )

    @Serializable
    private data class RemoteList(val instances: List<Entry> = emptyList())

    /**
     * Bundled fallback. tracks.monochrome.st is live-verified (real search
     * rows + `fLaC` stream bytes + artwork, checked 2026-10-07); the rest are
     * candidates from Monochrome's instance list, unverified from any given
     * network — the on-device probe decides, not this list.
     */
    val BUNDLED = listOf(
        Entry("https://wolf.qqdl.site", "Lucida (Wolf)", verified = true),
        Entry("https://maus.qqdl.site", "Lucida (Maus)", verified = true),
        Entry("https://katze.qqdl.site", "Lucida (Katze)", verified = true),
        Entry("https://hund.qqdl.site", "Lucida (Hund)", verified = true),
        Entry("https://tracks.monochrome.st", "Monochrome Tracks", verified = true),
        Entry("https://vogel.qqdl.site", "Lucida (Vogel)"),
    )

    private const val FILE = "bitchord_tidal"
    private const val KEY_PUBLIC_JSON = "tidal_public_json"
    private const val KEY_LAST_FETCH = "tidal_last_fetch"
    private const val KEY_AUTO_UPDATE = "tidal_auto_update"

    private const val FETCH_INTERVAL_MS = 24 * 60 * 60 * 1000L
    // Half an hour: probes are network, and a pool that was alive minutes
    // ago is alive now. Every search/stream would otherwise pay the slow
    // volunteer's timeout on each new track.
    private const val HEALTH_TTL_MS = 30 * 60 * 1000L

    private lateinit var prefs: SharedPreferences

    private val json = Json { ignoreUnknownKeys = true }

    private val _autoUpdate = MutableStateFlow(true)
    val autoUpdate: StateFlow<Boolean> = _autoUpdate.asStateFlow()

    /** Last known reachability per instance URL; null means never probed. */
    private val _health = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val health: StateFlow<Map<String, Boolean>> = _health.asStateFlow()

    /** Rated instances ordered by latency / health. */
    private val _ratings = MutableStateFlow<List<RatedInstance>>(emptyList())
    val ratings: StateFlow<List<RatedInstance>> = _ratings.asStateFlow()

    private var healthAt = 0L
    private val lock = Any()

    fun init(context: Context) {
        if (this::prefs.isInitialized) return
        prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        _autoUpdate.value = prefs.getBoolean(KEY_AUTO_UPDATE, true)
    }

    fun setAutoUpdate(value: Boolean) {
        _autoUpdate.value = value
        if (this::prefs.isInitialized) prefs.edit().putBoolean(KEY_AUTO_UPDATE, value).apply()
    }

    /** The public pool: fetched list when fresh, else the bundled candidates. */
    fun public(): List<Entry> {
        if (!this::prefs.isInitialized) return BUNDLED
        val raw = prefs.getString(KEY_PUBLIC_JSON, null) ?: return BUNDLED
        return runCatching { json.decodeFromString(RemoteList.serializer(), raw).instances }
            .getOrNull()?.takeIf { it.isNotEmpty() } ?: BUNDLED
    }

    /** Refreshes the public pool from [REMOTE_URL]; keeps the old list on any failure. */
    suspend fun refresh(force: Boolean = false): List<Entry> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!force && now - lastFetch() < FETCH_INTERVAL_MS) return@withContext public()
        if (!_autoUpdate.value) return@withContext public()
        val fetched = runCatching { fetchRemote() }.getOrNull()
        if (fetched.isNullOrEmpty()) {
            TrackLog.d(TAG, "tidal instance list refresh failed, keeping ${public().size} known")
            return@withContext public()
        }
        // this@ required: withContext's scope is the implicit receiver here,
        // and this::prefs would resolve against it instead of this object.
        val ready = this@TidalInstances::prefs.isInitialized
        synchronized(lock) {
            if (ready) {
                prefs.edit()
                    .putString(KEY_PUBLIC_JSON, json.encodeToString(RemoteList.serializer(), RemoteList(fetched)))
                    .putLong(KEY_LAST_FETCH, now)
                    .apply()
            }
        }
        TrackLog.d(TAG, "tidal instance list refreshed: ${fetched.size} entries")
        fetched
    }

    /**
     * Endpoints to try, in order: the primary URL first, then the public
     * pool minus the primary. Unreachable instances are filtered by a cached
     * health pass so a dead volunteer doesn't cost a timeout per track.
     */
    suspend fun endpoints(primaryRaw: String): List<String> {
        val primary = TidalApi.normalize(primaryRaw)
        val pool = (listOfNotNull(primary) + public().mapNotNull { TidalApi.normalize(it.url) })
            .distinct()
        if (pool.size <= 1) return pool
        val rated = probeAndRank(pool)
        val healthy = rated.filter { it.isHealthy }.map { it.url }
        return healthy.ifEmpty { pool }
    }

    suspend fun refreshRatings(force: Boolean = false) {
        val pool = public().mapNotNull { TidalApi.normalize(it.url) }.distinct()
        probeAndRank(pool, force = force)
    }

    private suspend fun probeAndRank(urls: List<String>, force: Boolean = false): List<RatedInstance> =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            synchronized(lock) {
                if (!force && now - healthAt < HEALTH_TTL_MS && _ratings.value.isNotEmpty()) {
                    return@withContext _ratings.value
                }
            }
            val publicList = public()
            val results = urls.map { url ->
                async {
                    val label = publicList.firstOrNull { TidalApi.normalize(it.url) == url }?.label
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
                _health.value = sorted.associate { it.url to it.isHealthy }
                _ratings.value = sorted
                healthAt = now
            }
            TrackLog.d(TAG, "tidal ratings updated: ${sorted.filter { it.isHealthy }.map { "${it.label} (${it.pingMs}ms)" }}")
            sorted
        }

    private fun lastFetch(): Long =
        if (this::prefs.isInitialized) prefs.getLong(KEY_LAST_FETCH, 0L) else 0L

    private fun fetchRemote(): List<Entry> {
        val request = Request.Builder().url(REMOTE_URL)
            .header("Accept", "application/json")
            .build()
        com.music.bitchord.data.Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) error("empty list")
            return json.decodeFromString(RemoteList.serializer(), body).instances
                .filter { it.url.isNotBlank() }
        }
    }
}
