package com.music.bitchord.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal enum class DesktopAppFont(val label: String) {
    SF_PRO("SF Pro Display"),
    VAZIRMATN("Vazirmatn"),
}

/**
 * The toggles read far from the Settings sheet that sets them.
 *
 * Mirrors Android's `AppSettings` for these few: a composable deep in the player or the lyrics
 * pane can collect one without it being threaded down through every caller.
 */
internal object DesktopAppearanceSettings {

    private fun flag(key: String, default: Boolean) =
        MutableStateFlow(DesktopPersistence().boolean(key, default))

    private val _reduceAnimation = flag(KEY_REDUCE_ANIMATION, false)
    private val _reduceDynamicBlur = flag(KEY_REDUCE_DYNAMIC_BLUR, false)
    private val _hideVolumeBar = flag(KEY_HIDE_VOLUME_BAR, false)
    private val _ambientBackdrop = flag(KEY_AMBIENT_BACKDROP, false)
    private val _showQueueHistory = flag(KEY_SHOW_QUEUE_HISTORY, false)
    private val _lyricAboveScrubber = flag(KEY_LYRIC_ABOVE_SCRUBBER, true)
    private val _appFont = MutableStateFlow(
        runCatching { DesktopAppFont.valueOf(DesktopPersistence().string(KEY_APP_FONT, DesktopAppFont.SF_PRO.name)) }
            .getOrDefault(DesktopAppFont.SF_PRO),
    )

    /** Freezes the main player's gradient instead of drifting. */
    val reduceAnimation: StateFlow<Boolean> = _reduceAnimation

    /** Swaps frosted glass for solid fills across the app. */
    val reduceDynamicBlur: StateFlow<Boolean> = _reduceDynamicBlur

    /** Removes the volume slider from the main player. */
    val hideVolumeBar: StateFlow<Boolean> = _hideVolumeBar

    /** Diffuses current artwork colors under the window glass chrome like Apple Music. */
    val ambientBackdrop: StateFlow<Boolean> = _ambientBackdrop

    /** Shows songs already played in the current session above the playing song in the queue. */
    val showQueueHistory: StateFlow<Boolean> = _showQueueHistory

    /** Shows the current lyric line above the scrubber on the main player view. */
    val lyricAboveScrubber: StateFlow<Boolean> = _lyricAboveScrubber

    /** Typeface used across the desktop UI. */
    val appFont: StateFlow<DesktopAppFont> = _appFont

    fun setReduceAnimation(value: Boolean) = write(KEY_REDUCE_ANIMATION, value, _reduceAnimation)

    fun setReduceDynamicBlur(value: Boolean) = write(KEY_REDUCE_DYNAMIC_BLUR, value, _reduceDynamicBlur)

    fun setHideVolumeBar(value: Boolean) = write(KEY_HIDE_VOLUME_BAR, value, _hideVolumeBar)

    fun setAmbientBackdrop(value: Boolean) = write(KEY_AMBIENT_BACKDROP, value, _ambientBackdrop)

    fun setShowQueueHistory(value: Boolean) = write(KEY_SHOW_QUEUE_HISTORY, value, _showQueueHistory)

    fun setLyricAboveScrubber(value: Boolean) = write(KEY_LYRIC_ABOVE_SCRUBBER, value, _lyricAboveScrubber)

    fun setAppFont(value: DesktopAppFont) {
        DesktopPersistence().saveString(KEY_APP_FONT, value.name)
        _appFont.value = value
    }

    private fun write(key: String, value: Boolean, into: MutableStateFlow<Boolean>) {
        DesktopPersistence().saveBoolean(key, value)
        into.value = value
    }

    internal const val KEY_REDUCE_ANIMATION = "reduce_animation"
    internal const val KEY_REDUCE_DYNAMIC_BLUR = "reduce_dynamic_blur"
    internal const val KEY_HIDE_VOLUME_BAR = "hide_volume_bar"
    internal const val KEY_AMBIENT_BACKDROP = "ambient_backdrop"
    internal const val KEY_SHOW_QUEUE_HISTORY = "show_queue_history"
    internal const val KEY_LYRIC_ABOVE_SCRUBBER = "lyric_above_scrubber"
    internal const val KEY_APP_FONT = "app_font"
}
