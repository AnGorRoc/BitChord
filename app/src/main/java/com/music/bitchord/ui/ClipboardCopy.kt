package com.music.bitchord.ui

import android.content.ClipData
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipEntry

/** Puts [text] on the clipboard as plain text. */
suspend fun Clipboard.copyText(text: String) {
    setClipEntry(ClipEntry(ClipData.newPlainText(null, text)))
}
