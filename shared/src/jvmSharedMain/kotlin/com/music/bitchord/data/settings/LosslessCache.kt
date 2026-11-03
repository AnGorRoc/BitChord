package com.music.bitchord.data.settings

import com.music.bitchord.data.sources.StreamFormat

/**
 * How much lossless audio the song cache keeps once a track has played.
 *
 * A lossless file is ten to sixty times the size of the Opus copy it
 * replaces, and the catalogue hands over whatever master it has — a 24-bit,
 * 176.4 kHz *Billie Jean* is 220 MB. Kept under the ordinary song-cache
 * limit, two or three of those empty everything else out of it. So lossless
 * gets a level of its own and a budget of its own:
 * [AppSettings.losslessCacheLimitBytes] is added on top of the song cache
 * rather than carved out of it, and trimmed separately — see
 * [AudioCache][com.music.bitchord.playback.AudioCache].
 */
enum class LosslessCacheLevel {
    /** Lossless plays and is let go; replays fetch it again. */
    OFF,

    /** CD-quality FLAC (16-bit, up to 48 kHz) is kept; hi-res masters are not. */
    CD,

    /** Everything, hi-res included. */
    ALL,
}

/** Which side of the CD line a lossless file sits on. */
enum class LosslessTier {
    CD,
    HI_RES,
    ;

    companion object {
        /**
         * The tier [format] describes: past 16-bit or past 48 kHz is hi-res,
         * the line every service draws. When the source stated neither, its
         * bitrate decides — a CD-quality FLAC runs well under 1,600 kbps and a
         * hi-res one well over — and with nothing stated at all the file is
         * taken as hi-res, the reading that keeps less rather than more.
         */
        fun of(format: StreamFormat?): LosslessTier {
            val depth = format?.bitDepth
            val rate = format?.sampleRateHz
            if (depth != null || rate != null) {
                return if ((depth ?: 16) > 16 || (rate ?: 44_100) > 48_000) HI_RES else CD
            }
            val kbps = format?.kbps ?: return HI_RES
            return if (kbps > CD_CEILING_KBPS) HI_RES else CD
        }

        private const val CD_CEILING_KBPS = 1_600
    }
}

/** The rules the cache applies to lossless entries, kept apart from the cache so they can be tested. */
object LosslessCachePolicy {

    /** Whether a lossless entry of [tier] outlives its track at [level]. Unknown tier counts as hi-res. */
    fun keeps(level: LosslessCacheLevel, tier: LosslessTier?): Boolean = when (level) {
        LosslessCacheLevel.OFF -> false
        LosslessCacheLevel.CD -> tier == LosslessTier.CD
        LosslessCacheLevel.ALL -> true
    }

    /** One kept lossless entry, as the budget sees it. */
    data class Entry(val key: String, val bytes: Long, val lastUsedMs: Long)

    /**
     * The entries to drop, least recently used first, until what is left fits
     * in [limitBytes]. Nothing when it already fits.
     */
    fun overBudget(entries: List<Entry>, limitBytes: Long): List<String> {
        var total = entries.sumOf { it.bytes }
        if (total <= limitBytes) return emptyList()
        val dropped = mutableListOf<String>()
        for (entry in entries.sortedBy { it.lastUsedMs }) {
            if (total <= limitBytes) break
            dropped += entry.key
            total -= entry.bytes
        }
        return dropped
    }

    /**
     * The ceiling the cache's own evictor works to: the song cache plus the
     * lossless budget, so kept lossless never pushes other songs out. The
     * lossless share is trimmed to its own limit separately; this only makes
     * room for it. Unlimited stays unlimited.
     */
    fun evictorCeiling(songCacheBytes: Long, level: LosslessCacheLevel, losslessBytes: Long): Long {
        if (level == LosslessCacheLevel.OFF) return songCacheBytes
        if (songCacheBytes > Long.MAX_VALUE - losslessBytes) return Long.MAX_VALUE
        return songCacheBytes + losslessBytes
    }
}
