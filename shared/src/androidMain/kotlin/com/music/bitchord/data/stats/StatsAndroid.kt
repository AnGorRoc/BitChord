package com.music.bitchord.data.stats

import android.content.Context
import android.util.Log
import java.io.File

fun ListeningStats.init(context: Context) {
    logWarning = { msg, tr -> Log.w("ListeningStats", msg, tr) }
    init(File(context.filesDir, DIRECTORY))
}

fun ArtistFacts.init(context: Context) {
    logWarning = { msg, tr -> Log.w("ArtistFacts", msg, tr) }
    init(context.filesDir)
}
