package com.music.bitchord.data.stats

import android.content.Context
import android.net.Uri
import com.music.bitchord.BuildConfig
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.SearchHistory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun initAndroidBackup() {
    Backup.platformExporter = { context, target ->
        withContext(Dispatchers.IO) {
            runCatching {
                val c = context as Context
                val u = target as Uri
                val buckets = ListeningStats.exportAll()
                val text = Backup.createBackupJson(
                    versionName = BuildConfig.VERSION_NAME,
                    settings = AppSettings.exportPrefs(),
                    listening = buckets,
                )
                c.contentResolver.openOutputStream(u, "wt")
                    ?.use { it.write(text.toByteArray()) }
                    ?: error("Couldn't open that file for writing")
                buckets.size
            }
        }
    }
    Backup.platformImporter = { context, source ->
        withContext(Dispatchers.IO) {
            runCatching {
                val c = context as Context
                val u = source as Uri
                val text = c.contentResolver.openInputStream(u)
                    ?.use { it.readBytes().decodeToString() }
                    ?: error("Couldn't open that file")
                val (file, summary) = Backup.decodeBackupJson(text)
                ListeningStats.importAll(file.listening)
                AppSettings.importPrefs(file.settings.mapValues { it.value.decoded() })
                SearchHistory.reload()
                summary
            }
        }
    }
}

suspend fun Backup.exportTo(context: Context, target: Uri): Result<Int> =
    exportTo(context as Any, target as Any)

suspend fun Backup.importFrom(context: Context, source: Uri): Result<Backup.Summary> =
    importFrom(context as Any, source as Any)
