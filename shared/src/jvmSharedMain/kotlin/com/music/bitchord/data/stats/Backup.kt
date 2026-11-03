package com.music.bitchord.data.stats

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The whole of what this app knows about you, as one JSON file you own.
 *
 * Universal data models and serialization for BitChord cross-platform backup.
 */
object Backup {

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    const val APP_TAG = "bitchord"

    /**
     * Bump when the shape below stops being readable by an older build. A file
     * from a *newer* schema is refused rather than partially read; one from an
     * older schema is read as-is, since every field has a default.
     */
    const val SCHEMA_VERSION = 1

    @Volatile
    var platformExporter: (suspend (context: Any, target: Any) -> Result<Int>)? = null

    @Volatile
    var platformImporter: (suspend (context: Any, source: Any) -> Result<Summary>)? = null

    suspend fun exportTo(context: Any, target: Any): Result<Int> =
        platformExporter?.invoke(context, target)
            ?: Result.failure(UnsupportedOperationException("Platform exporter not initialized"))

    suspend fun importFrom(context: Any, source: Any): Result<Summary> =
        platformImporter?.invoke(context, source)
            ?: Result.failure(UnsupportedOperationException("Platform importer not initialized"))

    /** A suggested filename, dated so successive exports don't collide. */
    fun suggestedName(): String =
        "bitchord-backup-${DateTimeFormatter.ofPattern("yyyy-MM-dd").format(
            Instant.now().atZone(ZoneId.systemDefault()),
        )}.json"

    fun createBackupJson(
        versionName: String,
        settings: Map<String, Any?>,
        listening: List<StoredBucket>,
    ): String {
        val file = BackupFile(
            versionName = versionName,
            exportedAt = Instant.now().toString(),
            settings = settings.mapNotNull { (key, value) ->
                PrefValue.of(value)?.let { key to it }
            }.toMap(),
            listening = listening,
        )
        return json.encodeToString(BackupFile.serializer(), file)
    }

    fun decodeBackupJson(text: String): Pair<BackupFile, Summary> {
        val file = runCatching { json.decodeFromString(BackupFile.serializer(), text) }
            .getOrElse { error("That doesn't look like a BitChord backup") }
        require(file.app == APP_TAG) { "That backup is from another app" }
        require(file.version <= SCHEMA_VERSION) {
            "That backup was written by a newer version of BitChord"
        }
        val summary = Summary(
            months = file.listening.size,
            settings = file.settings.size,
            from = file.versionName,
            at = file.exportedAt,
        )
        return file to summary
    }

    /** What an import turned out to contain, for the line shown afterwards. */
    data class Summary(
        val months: Int,
        val settings: Int,
        val from: String,
        val at: String,
    )

    @Serializable
    data class BackupFile(
        val app: String = APP_TAG,
        val version: Int = SCHEMA_VERSION,
        /** Which build wrote it — for the reader, not for any logic here. */
        val versionName: String = "",
        val exportedAt: String = "",
        val settings: Map<String, PrefValue> = emptyMap(),
        val listening: List<StoredBucket> = emptyList(),
    )

    /** One preference, with the type it has to be restored as. */
    @Serializable
    data class PrefValue(
        val type: String,
        val value: String? = null,
        val values: List<String> = emptyList(),
    ) {
        fun decoded(): Any? = when (type) {
            BOOLEAN -> value?.toBooleanStrictOrNull()
            INT -> value?.toIntOrNull()
            LONG -> value?.toLongOrNull()
            FLOAT -> value?.toFloatOrNull()
            STRING -> value
            STRING_SET -> values.toSet()
            else -> null
        }

        companion object {
            /**
             * Numbers go out as strings on purpose. JSON has one number type and
             * every parser picks its own Kotlin type back off it, which is
             * exactly the ambiguity this whole class exists to remove.
             */
            fun of(value: Any?): PrefValue? = when (value) {
                is Boolean -> PrefValue(BOOLEAN, value.toString())
                is Int -> PrefValue(INT, value.toString())
                is Long -> PrefValue(LONG, value.toString())
                is Float -> PrefValue(FLOAT, value.toString())
                is String -> PrefValue(STRING, value)
                is Set<*> -> PrefValue(STRING_SET, values = value.filterIsInstance<String>())
                else -> null
            }

            private const val BOOLEAN = "bool"
            private const val INT = "int"
            private const val LONG = "long"
            private const val FLOAT = "float"
            private const val STRING = "string"
            private const val STRING_SET = "stringSet"
        }
    }
}
