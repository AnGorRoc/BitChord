package com.music.bitchord.desktop

import androidx.compose.ui.graphics.Color
import com.music.bitchord.desktop.DesktopWindowsTheme.toColorRef
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopWindowsThemeTest {

    @Test
    fun `toColorRef converts Compose Color to Win32 BGR format`() {
        assertEquals(0x000000FF, Color.Red.toColorRef())
        assertEquals(0x0000FF00, Color.Green.toColorRef())
        assertEquals(0x00FF0000, Color.Blue.toColorRef())
        assertEquals(0x00202020, DesktopBackground.toColorRef())
        assertEquals(0x00FFFFFF, Color.White.toColorRef())
        assertEquals(0x00000000, Color.Black.toColorRef())
    }

    @Test
    fun `updateTitleBar safely handles null window`() {
        DesktopWindowsTheme.updateTitleBar(null)
    }
}
