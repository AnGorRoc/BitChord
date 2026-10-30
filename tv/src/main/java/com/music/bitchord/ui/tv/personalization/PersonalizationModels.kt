package com.music.bitchord.ui.tv.personalization

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

enum class AppThemeOption(
    val id: String,
    val title: String,
    val description: String,
) {
    DYNAMIC_ARTWORK(
        id = "dynamic_artwork",
        title = "Artwork",
        description = "Dark, softly tinted by the artwork of what's playing.",
    ),
    MIDNIGHT(
        id = "midnight",
        title = "Midnight",
        description = "A deep navy background.",
    ),
    PURE_BLACK(
        id = "pure_black",
        title = "Pure Black",
        description = "True black, for OLED screens.",
    );

    companion object {
        fun fromId(id: String?): AppThemeOption = when (id?.lowercase()) {
            "midnight" -> MIDNIGHT
            "pure_black", "oled" -> PURE_BLACK
            else -> DYNAMIC_ARTWORK
        }
    }
}

@Immutable
data class TvThemePalette(
    val background: Color,
    val backgroundElevated: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val surfaceFocused: Color,
    val surfaceSelected: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val accent: Color,
    val accentGlow: Color,
    val borderFocused: Color,
)
