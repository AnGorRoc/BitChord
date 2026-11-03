package com.music.bitchord.desktop

import com.music.bitchord.data.stats.Backup
import com.music.bitchord.data.stats.ListeningStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.awt.FileDialog
import java.awt.Frame

/**
 * Cross-platform backup manager for BitChord Desktop.
 *
 * Saves listening history and settings to universal BitChord .json backups.
 */
object DesktopBackup {

    fun suggestedName(): String = Backup.suggestedName()

    suspend fun exportToFile(file: File): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val buckets = ListeningStats.exportAll()
            val persistence = DesktopPersistence()
            val text = Backup.createBackupJson(
                versionName = "1.9.1",
                settings = persistence.exportPrefs(),
                listening = buckets,
            )
            file.writeText(text)
            buckets.size
        }
    }

    suspend fun importFromFile(file: File): Result<Backup.Summary> = withContext(Dispatchers.IO) {
        runCatching {
            val text = file.readText()
            val (backupFile, summary) = Backup.decodeBackupJson(text)
            ListeningStats.importAll(backupFile.listening)
            val persistence = DesktopPersistence()
            persistence.importPrefs(backupFile.settings.mapValues { it.value.decoded() })
            summary
        }
    }

    fun promptExportFile(parent: Frame? = null): File? {
        val dialog = FileDialog(parent, "Export BitChord Backup", FileDialog.SAVE).apply {
            file = suggestedName()
            isVisible = true
        }
        val dir = dialog.directory ?: return null
        val filename = dialog.file ?: return null
        val target = File(dir, filename)
        return if (target.name.endsWith(".json", ignoreCase = true)) target else File(dir, "$filename.json")
    }

    fun promptImportFile(parent: Frame? = null): File? {
        val dialog = FileDialog(parent, "Import BitChord Backup", FileDialog.LOAD).apply {
            setFilenameFilter { _, name -> name.endsWith(".json", ignoreCase = true) }
            isVisible = true
        }
        val dir = dialog.directory ?: return null
        val filename = dialog.file ?: return null
        return File(dir, filename)
    }
}
