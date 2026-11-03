package com.music.bitchord.data.canvas

import androidx.compose.ui.graphics.Color
import com.music.bitchord.ui.theme.ArtworkKeyColors

/**
 * Extension for [AppleArtistArt] converting ARGB colors to Compose [ArtworkKeyColors].
 */
fun AppleArtistArt.keyColors() = ArtworkKeyColors(Color(background), Color(accent))
