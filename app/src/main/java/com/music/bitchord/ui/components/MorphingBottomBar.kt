package com.music.bitchord.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.clear
import com.music.bitchord.sharedui.resources.search
import com.music.bitchord.sharedui.resources.search_hint
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlin.math.abs
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.stringResource

private val GESTURE_BAR_FLOOR = 15.dp
private const val TAB_SEARCH = 3

/**
 * Morphing navigation bar:
 * In normal mode, displays the navigation tabs (Home, Explore, Library) on the left
 * with hold-and-slide scrubbing gesture and liquid selection indicator,
 * and an exact circular Search button (58x58dp) on the right.
 * When Search is tapped, the Search circle expands towards the left to become the
 * full search mega-pill, while the navigation tabs collapse towards the left and disappear.
 * When the keyboard opens, it floats directly above the keyboard keys.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun MorphingBottomBar(
    tabs: List<BottomTab>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismissSearch: () -> Unit,
    searchFocusRequester: FocusRequester,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
) {
    val pillShape = remember { RoundedCornerShape(percent = 50) }
    val container = MaterialTheme.colorScheme.surface
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val reduceAnimation by AppSettings.reduceAnimation.collectAsStateWithLifecycle()
    val useGlass = LocalLiquidGlassEnabled.current && isGlassSupported()
    val barTint = glassBarTint()
    val contentColor = if (useGlass) glassContentColor() else MaterialTheme.colorScheme.onSurface
    val haptics = rememberHaptics()
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    val isSearching = selectedIndex == TAB_SEARCH
    val searchProgress by animateFloatAsState(
        targetValue = if (isSearching) 1f else 0f,
        animationSpec = if (reduceAnimation) snap() else spring(
            dampingRatio = 0.82f,
            stiffness = 360f,
        ),
        label = "searchMorphProgress",
    )

    // Keyboard and bottom padding
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    val isKeyboardOpen = imeBottom > 0

    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val defaultBottomPadding = max(navBottom, GESTURE_BAR_FLOOR)
    val targetBottomPadding = if (isKeyboardOpen) 18.dp else defaultBottomPadding
    val currentBottomPadding by animateDpAsState(
        targetValue = targetBottomPadding,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "navBottomPadding",
    )

    // Nav tabs setup (Home, Explore, Library)
    val navTabs = remember(tabs) { tabs.take(3) }
    val n = navTabs.size
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var rowSize by remember { mutableStateOf(IntSize.Zero) }
    val gapPx = with(density) { 6.dp.toPx() }

    val tabWidthPx = if (rowSize.width > 0 && n > 0) {
        (rowSize.width - gapPx * (n - 1)) / n
    } else 0f
    val tabStepPx = if (rowSize.width > 0 && n > 0) {
        (rowSize.width + gapPx) / n
    } else 0f

    var lastSelectedNavTab by remember {
        mutableIntStateOf(if (selectedIndex < 3) selectedIndex else 0)
    }

    LaunchedEffect(selectedIndex) {
        if (selectedIndex < 3) {
            lastSelectedNavTab = selectedIndex
        }
        dragOffset = 0f
    }

    val glassSpec: AnimationSpec<Float> = if (reduceAnimation) snap() else GlassSpring

    val pillTargetPx = if (tabStepPx > 0f) {
        lastSelectedNavTab * tabStepPx + dragOffset
    } else 0f

    val animatedPillOffset by animateFloatAsState(
        targetValue = pillTargetPx,
        animationSpec = glassSpec,
        label = "pillOffset",
    )

    val lag = if (tabStepPx > 0f) {
        (abs(pillTargetPx - animatedPillOffset) / tabStepPx).coerceIn(0f, 1f)
    } else {
        0f
    }

    var lastHapticTab by remember { mutableIntStateOf(lastSelectedNavTab) }
    val currentSelectedNavTab by rememberUpdatedState(lastSelectedNavTab)
    val currentOnTabSelected by rememberUpdatedState(onTabSelected)

    LaunchedEffect(isSearching) {
        if (isSearching) {
            searchFocusRequester.requestFocus()
            keyboardController?.show()
        } else {
            focusManager.clearFocus()
            keyboardController?.hide()
        }
    }

    val submitSearch: () -> Unit = {
        onSubmit()
        focusManager.clearFocus()
        keyboardController?.hide()
    }

    val dismissSearch: () -> Unit = {
        focusManager.clearFocus()
        keyboardController?.hide()
        onDismissSearch()
    }

    val surfaceModifier: @Composable () -> Modifier = {
        Modifier.then(
            if (reduceDynamicBlur) {
                Modifier.background(container)
            } else if (useGlass) {
                Modifier.liquidGlass(shape = pillShape, surfaceTint = barTint)
            } else {
                Modifier.optimizedHazeEffect(
                    state = hazeState,
                    style = HazeMaterials.regular(container),
                )
            },
        )
    }

    val barHeight = 58.dp
    val restingCircleSize = 58.dp

    BoxWithConstraints(
        modifier = modifier
            .padding(horizontal = BAR_GUTTER)
            .padding(bottom = currentBottomPadding + 2.dp)
            .fillMaxWidth(),
    ) {
        val totalWidth = maxWidth
        val spacing = 8.dp * (1f - searchProgress)
        val searchWidth = restingCircleSize + (totalWidth - restingCircleSize) * searchProgress
        val tabsWidth = (totalWidth - searchWidth - spacing).coerceAtLeast(0.dp)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 1. LEFT PILL: Navigation tabs (Home, Explore, Library)
            // Collapses towards the left as search expands
            if (searchProgress < 0.99f && tabsWidth > 1.dp) {
                val tabsAlpha = (1f - searchProgress * 2.2f).coerceIn(0f, 1f)

                Box(
                    modifier = Modifier
                        .width(tabsWidth)
                        .fillMaxHeight()
                        .graphicsLayer {
                            alpha = tabsAlpha
                            translationX = -searchProgress * 50f
                        }
                        .clip(pillShape)
                        .then(surfaceModifier())
                        .border(GLASS_EDGE_WIDTH, GLASS_EDGE_COLOR, pillShape)
                        .padding(horizontal = PILL_INSET, vertical = 4.dp),
                ) {
                    // Liquid sliding indicator pill
                    if (tabWidthPx > 0f) {
                        Box(
                            modifier = Modifier
                                .width(with(density) { tabWidthPx.toDp() })
                                .fillMaxHeight()
                                .graphicsLayer {
                                    translationX = animatedPillOffset
                                    scaleX = 1f + lag * STRETCH
                                    scaleY = 1f - lag * STRETCH * SQUASH
                                }
                                .clip(pillShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .onSizeChanged { rowSize = it }
                            .pointerInput(Unit) {
                                var totalDrag = 0f
                                var dragStartIndex = currentSelectedNavTab
                                detectHorizontalDragGestures(
                                    onDragStart = {
                                        totalDrag = 0f
                                        dragStartIndex = currentSelectedNavTab
                                    },
                                    onDragCancel = { dragOffset = 0f },
                                    onDragEnd = {
                                        if (tabStepPx > 0f) {
                                            val absoluteIndexFloat = (dragStartIndex + totalDrag / tabStepPx)
                                                .coerceIn(0f, (n - 1).toFloat())
                                            val newIndex = absoluteIndexFloat.roundToInt()
                                            if (newIndex != currentSelectedNavTab) {
                                                currentOnTabSelected(newIndex)
                                            }
                                        }
                                        dragOffset = 0f
                                    },
                                    onHorizontalDrag = { _, delta ->
                                        totalDrag += delta
                                        val absoluteIndexFloat = (dragStartIndex + totalDrag / tabStepPx)
                                            .coerceIn(0f, (n - 1).toFloat())
                                        dragOffset = absoluteIndexFloat * tabStepPx - dragStartIndex * tabStepPx

                                        val approxTab = absoluteIndexFloat.roundToInt()
                                        if (approxTab != lastHapticTab) {
                                            haptics.play(Haptic.Tick)
                                            lastHapticTab = approxTab
                                        }
                                    },
                                )
                            },
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        navTabs.forEachIndexed { index, tab ->
                            val isSelected = index == lastSelectedNavTab && selectedIndex < 3
                            val scale by animateFloatAsState(
                                targetValue = if (isSelected) 1.08f else 1f,
                                animationSpec = glassSpec,
                                label = "tabScale",
                            )
                            val tint by animateColorAsState(
                                targetValue = if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    contentColor.copy(alpha = 0.65f)
                                },
                                animationSpec = tween(200),
                                label = "tabTint",
                            )
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clip(pillShape)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) {
                                        if (!isSelected) {
                                            haptics.play(Haptic.Select)
                                            onTabSelected(index)
                                        }
                                    },
                            ) {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = tab.label,
                                    tint = tint,
                                    modifier = Modifier
                                        .size(24.dp)
                                        .graphicsLayer {
                                            scaleX = scale
                                            scaleY = scale
                                        },
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = tab.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = tint,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }

                if (spacing > 0.dp) {
                    Spacer(Modifier.width(spacing))
                }
            }

            // 2. RIGHT PILL: Search button -> Expanding Mega-Pill
            // Starts on the right as a circle (58x58dp) and expands leftward to take the full bar
            val searchBoxModifier = if (searchProgress >= 0.99f) {
                Modifier.fillMaxWidth().fillMaxHeight()
            } else {
                Modifier.width(searchWidth).fillMaxHeight()
            }

            Box(
                modifier = Modifier
                    .then(searchBoxModifier)
                    .clip(pillShape)
                    .then(surfaceModifier())
                    .border(GLASS_EDGE_WIDTH, GLASS_EDGE_COLOR, pillShape)
                    .clickable(
                        enabled = !isSearching,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        haptics.play(Haptic.Select)
                        onTabSelected(TAB_SEARCH)
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (searchProgress < 0.35f) {
                    // Closed state: round search button with magnifying glass
                    Icon(
                        imageVector = tabs.getOrNull(TAB_SEARCH)?.icon ?: Icons.Rounded.Search,
                        contentDescription = stringResource(Res.string.search),
                        tint = if (isSearching) MaterialTheme.colorScheme.primary else contentColor.copy(alpha = 0.75f),
                        modifier = Modifier.size(25.dp),
                    )
                } else {
                    // Expanding / Expanded state: full search field
                    val contentAlpha = ((searchProgress - 0.35f) / 0.65f).coerceIn(0f, 1f)
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 14.dp)
                            .graphicsLayer { alpha = contentAlpha },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = stringResource(Res.string.search),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .clickable(enabled = query.isNotBlank(), onClick = submitSearch),
                        )
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            if (query.isEmpty()) {
                                Text(
                                    text = stringResource(Res.string.search_hint),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = contentColor.copy(alpha = 0.55f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            BasicTextField(
                                value = query,
                                onValueChange = onQueryChange,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(searchFocusRequester),
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyLarge.copy(
                                    color = contentColor,
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { submitSearch() }),
                            )
                        }
                        if (query.isNotEmpty()) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = stringResource(Res.string.clear),
                                tint = contentColor.copy(alpha = 0.7f),
                                modifier = Modifier
                                    .size(26.dp)
                                    .clip(CircleShape)
                                    .clickable { onQueryChange("") }
                                    .padding(3.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = androidx.compose.ui.res.stringResource(com.music.bitchord.R.string.back),
                            tint = contentColor.copy(alpha = 0.7f),
                            modifier = Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .clickable(onClick = dismissSearch)
                                .padding(3.dp),
                        )
                    }
                }
            }
        }
    }
}
