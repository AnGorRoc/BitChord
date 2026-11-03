package com.music.bitchord.desktop

import androidx.compose.ui.window.WindowPlacement
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** How the window is sitting on the screen: floating, maximized, or filling it. */
internal object DesktopWindowMode {

    private val _placement = MutableStateFlow(WindowPlacement.Floating)
    private val _fullScreen = MutableStateFlow(false)
    private val _maximized = MutableStateFlow(false)
    private var previousPlacement = WindowPlacement.Floating

    /** What the window's own `placement` is driven from. */
    val placement: StateFlow<WindowPlacement> = _placement

    /** Whether the player is filling the screen, for the controls that say so. */
    val fullScreen: StateFlow<Boolean> = _fullScreen

    /** Whether the window is maximized, for the caption button's own glyph. */
    val maximized: StateFlow<Boolean> = _maximized

    fun toggle() = setFullScreen(!_fullScreen.value)

    /**
     * Maximize or restore, which is what the caption button in the corner does and what
     * double-clicking the title bar has always done.
     */
    fun toggleMaximized() {
        if (_fullScreen.value) {
            setFullScreen(false)
            return
        }
        set(if (_placement.value == WindowPlacement.Maximized) WindowPlacement.Floating else WindowPlacement.Maximized)
    }

    /**
     * Leaves full screen, for the paths that can only mean "get me out" — closing the player, or
     * the window being put away to the tray.
     */
    fun exit() {
        if (_fullScreen.value) setFullScreen(false)
    }

    /** Takes the window's word for how it is sitting, rather than this object's. */
    fun adopt(placement: WindowPlacement) {
        if (placement == _placement.value) return
        when (placement) {
            WindowPlacement.Floating -> {
                _fullScreen.value = false
                _maximized.value = false
            }
            WindowPlacement.Maximized -> {
                _fullScreen.value = false
                _maximized.value = true
            }
            WindowPlacement.Fullscreen -> {
                _fullScreen.value = true
                _maximized.value = false
            }
        }
        _placement.value = placement
    }

    private fun setFullScreen(on: Boolean) {
        _fullScreen.value = on
        if (on) {
            if (_placement.value != WindowPlacement.Fullscreen) {
                previousPlacement = _placement.value
            }
            set(WindowPlacement.Fullscreen)
        } else {
            set(if (previousPlacement == WindowPlacement.Maximized) WindowPlacement.Maximized else WindowPlacement.Floating)
        }
    }

    private fun set(placement: WindowPlacement) {
        _placement.value = placement
        _maximized.value = placement == WindowPlacement.Maximized
        _fullScreen.value = placement == WindowPlacement.Fullscreen
    }
}
