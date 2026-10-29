package com.music.bitchord.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.SearchSource
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

/**
 * Top-bar search source selector (YouTube | Library | Addons) styled
 * with the same floating liquid-glass / frosted materials as the back and profile buttons.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun SearchSourceSwitcher(
    searchSource: SearchSource,
    onSearchSourceChange: (SearchSource) -> Unit,
    hazeState: HazeState?,
    modifier: Modifier = Modifier,
) {
    val pillShape = remember { RoundedCornerShape(percent = 50) }
    val itemShape = remember { RoundedCornerShape(percent = 50) }
    val container = MaterialTheme.colorScheme.surface
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val useLiquidGlass = LocalLiquidGlassEnabled.current && isGlassSupported()
    val haptics = rememberHaptics()

    val options = listOf(
        SearchSource.YOUTUBE to stringResource(R.string.search_source_youtube),
        SearchSource.LIBRARY to stringResource(R.string.library),
        SearchSource.ADDONS to stringResource(R.string.search_source_addons),
    )

    Box(
        modifier = modifier
            .height(38.dp)
            .clip(pillShape)
            .then(
                if (reduceDynamicBlur) {
                    Modifier.background(container)
                } else if (useLiquidGlass) {
                    Modifier.liquidGlass(shape = pillShape, surfaceTint = glassBarTint())
                } else if (hazeState != null) {
                    Modifier.optimizedHazeEffect(
                        state = hazeState,
                        style = HazeMaterials.regular(container),
                    )
                } else {
                    Modifier.background(container.copy(alpha = 0.85f))
                },
            )
            .border(GLASS_EDGE_WIDTH, GLASS_EDGE_COLOR, pillShape)
            .padding(3.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxHeight(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            options.forEach { (source, label) ->
                val selected = source == searchSource
                val bg by animateColorAsState(
                    targetValue = if (selected) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                    } else {
                        Color.Transparent
                    },
                    animationSpec = tween(180),
                    label = "sourceSwitcherBg",
                )
                val textColor by animateColorAsState(
                    targetValue = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    },
                    animationSpec = tween(180),
                    label = "sourceSwitcherText",
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(itemShape)
                        .background(bg)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            if (!selected) {
                                haptics.play(Haptic.Select)
                                onSearchSourceChange(source)
                            }
                        }
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = textColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
