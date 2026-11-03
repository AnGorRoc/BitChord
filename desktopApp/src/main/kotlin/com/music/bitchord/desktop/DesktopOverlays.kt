package com.music.bitchord.desktop

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import com.music.bitchord.ui.icons.BitChordIcons

/**
 * Which of the app's dialogs, sheets and full-page overlays are showing.
 *
 * One object rather than a score of loose booleans in the app composable. They were nineteen locals
 * that only ever appeared in pairs — a flag and the `if (flag) Dialog(...)` that reads it — and
 * collecting them names the thing they are collectively: what is on top of the page right now.
 */
@Stable
internal class DesktopOverlays {

    /** The signal-chain readout behind the player's quality badge. */
    var pipeline by mutableStateOf(false)

    /** Which device playback is sent to. */
    var audioOutput by mutableStateOf(false)

    /** The party this device is listening with. */
    var listenTogether by mutableStateOf(false)
    var songMenu by mutableStateOf(false)
    var downloadManager by mutableStateOf(false)
    var replay by mutableStateOf(false)
    var playlistDialog by mutableStateOf(false)
    var rename by mutableStateOf(false)
    var delete by mutableStateOf(false)
    var reorder by mutableStateOf(false)
    var nowPlaying by mutableStateOf(false)

    /**
     * Which page of Settings fills the page area, or none. A place like any other: it goes into
     * the back history, so back from a sub-page lands on Settings and back from Settings on
     * wherever it was opened from.
     */
    var settingsPage by mutableStateOf<DesktopSettingsPage?>(null)

    /**
     * Which category of Settings is currently drilled into, or null for the main settings page.
     */
    var settingsCategory by mutableStateOf<DesktopSettingsCategory?>(null)

    var lastfmLogin by mutableStateOf(false)
    var listenBrainzToken by mutableStateOf(false)
    var discordToken by mutableStateOf(false)
    var accounts by mutableStateOf(false)
    var signIn by mutableStateOf(false)

    /** The column beside the page with the lyrics or the queue in it, or none. */
    var sidePanel by mutableStateOf<DesktopSidePanel?>(null)

    /** Opens [panel] in the column, or puts the column away if [panel] is what it already shows. */
    fun toggleSidePanel(panel: DesktopSidePanel) {
        sidePanel = if (sidePanel == panel) null else panel
    }
}

/**
 * The 10 settings categories ported from the phone app.
 */
internal enum class DesktopSettingsCategory(val titleKey: String, val defaultTitle: String) {
    DOWNLOADS("downloads", "Downloads"),
    PLAYBACK("playback", "Playback"),
    APPEARANCE("appearance", "Appearance"),
    PERFORMANCE("performance", "Performance"),
    LOCAL_MUSIC("local_music", "Local Music"),
    STORAGE("storage", "Storage"),
    YOUR_DATA("your_data", "Your Data"),
    MISCELLANEOUS("miscellaneous", "Miscellaneous"),
    LANGUAGE("language", "Language"),
    ADVANCED("advanced_options", "Advanced options"),
    ;

    fun title(): String = DesktopStrings[titleKey, defaultTitle]

    val icon: ImageVector
        get() = when (this) {
            DOWNLOADS -> BitChordIcons.Download
            PLAYBACK -> BitChordIcons.Play
            APPEARANCE -> Icons.Rounded.Palette
            PERFORMANCE -> BitChordIcons.Performance
            LOCAL_MUSIC -> BitChordIcons.Library
            STORAGE -> Icons.Rounded.Storage
            YOUR_DATA -> Icons.Rounded.BarChart
            MISCELLANEOUS -> Icons.Rounded.Tune
            LANGUAGE -> Icons.Rounded.Language
            ADVANCED -> Icons.Rounded.Code
        }
}

/**
 * Settings and the pages its rows lead to. Only the screens are here; the short prompts behind them
 * — a sign-in, a token, a source's address — stay cards over the page, as Android's alerts do.
 */
internal enum class DesktopSettingsPage {
    MAIN,
    EQUALIZER,
    AUDIO_OUTPUT,
    LISTEN_TOGETHER,
    LYRICS_SOURCES,
    TRANSLATION_LANGUAGE,
    SPOTIFY_CANVAS,
    INTEGRATIONS,
    LICENSES,
}
