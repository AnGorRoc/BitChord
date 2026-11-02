package com.music.bitchord.desktop

import androidx.compose.ui.graphics.Color
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.ptr.IntByReference
import java.awt.Window
import kotlin.math.roundToInt

/**
 * Bridges the UI theme (dark mode, caption colors) to Windows' native title bar via Desktop Window Manager (DWM).
 */
internal object DesktopWindowsTheme {

    private interface Dwmapi : Library {
        fun DwmSetWindowAttribute(hwnd: HWND, dwAttribute: Int, pvAttribute: Pointer, cbAttribute: Int): Int

        companion object {
            val INSTANCE: Dwmapi? = runCatching {
                Native.load("dwmapi", Dwmapi::class.java)
            }.getOrNull()
        }
    }

    private const val DWMWA_USE_IMMERSIVE_DARK_MODE_BEFORE_20H1 = 19
    private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
    private const val DWMWA_BORDER_COLOR = 34
    private const val DWMWA_CAPTION_COLOR = 35
    private const val DWMWA_TEXT_COLOR = 36

    /**
     * Applies the UI theme's background color and dark mode to Windows' native title bar.
     */
    fun updateTitleBar(window: Window?, backgroundColor: Color = DesktopBackground) {
        if (!DesktopPlatform.isWindows || window == null) return
        val dwmapi = Dwmapi.INSTANCE ?: return
        val pointer = runCatching { Native.getWindowPointer(window) }.getOrNull() ?: return
        if (Pointer.nativeValue(pointer) == 0L) return
        val hwnd = HWND(pointer)

        val luminance = 0.2126f * backgroundColor.red + 0.7152f * backgroundColor.green + 0.0722f * backgroundColor.blue
        val isDark = luminance < 0.5f

        // 1. Immersive dark mode (Windows 10 1809+ and Windows 11)
        val darkModeVal = IntByReference(if (isDark) 1 else 0)
        dwmapi.DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, darkModeVal.pointer, 4)
        dwmapi.DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE_BEFORE_20H1, darkModeVal.pointer, 4)

        // 2. Caption color (Windows 11 build 22000+)
        val captionRef = IntByReference(backgroundColor.toColorRef())
        dwmapi.DwmSetWindowAttribute(hwnd, DWMWA_CAPTION_COLOR, captionRef.pointer, 4)

        // 3. Caption text color (Windows 11 build 22000+)
        val textRef = IntByReference(if (isDark) 0x00FFFFFF else 0x00000000)
        dwmapi.DwmSetWindowAttribute(hwnd, DWMWA_TEXT_COLOR, textRef.pointer, 4)

        // 4. Border color (Windows 11 build 22000+)
        dwmapi.DwmSetWindowAttribute(hwnd, DWMWA_BORDER_COLOR, captionRef.pointer, 4)
    }

    internal fun Color.toColorRef(): Int {
        val r = (red * 255f).roundToInt().coerceIn(0, 255)
        val g = (green * 255f).roundToInt().coerceIn(0, 255)
        val b = (blue * 255f).roundToInt().coerceIn(0, 255)
        return (b shl 16) or (g shl 8) or r
    }
}
