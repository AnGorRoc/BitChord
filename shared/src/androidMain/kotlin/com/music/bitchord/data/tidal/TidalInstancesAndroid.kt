package com.music.bitchord.data.tidal

import android.content.Context

fun TidalInstances.init(context: Context) {
    val prefs = context.getSharedPreferences("bitchord_tidal", Context.MODE_PRIVATE)
    init(
        read = { prefs.getString("tidal_manual_json", null) },
        write = { prefs.edit().putString("tidal_manual_json", it).apply() },
    )
}
