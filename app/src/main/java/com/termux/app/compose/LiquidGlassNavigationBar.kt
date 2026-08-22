package com.termux.app.compose

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs
import kotlin.math.roundToInt

data class ResponsiveNavDimensions(
    val itemWidth: Dp,
    val itemHeight: Dp,
    val iconSize: Dp,
    val labelSize: TextUnit,
    val containerHeight: Dp,
    val indicatorWidth: Dp,
    val indicatorHeight: Dp,
    val indicatorExpandedWidth: Dp,
    val indicatorExpandedHeight: Dp,
    val indicatorCornerRadius: Dp,
    val cornerRadius: Dp,
    val horizontalPadding: Dp,
    val verticalPadding: Dp,
    val bottomMargin: Dp,
    val sideMargin: Dp,
    val gap: Dp,
    val totalHeight: Dp
)

enum class NavStyle {
    DEFAULT,
    GLASS,
    SOFT_LIGHT,
    FLOATING
}

@Composable
fun computeNavDimensions(
    itemCount: Int,
    style: NavStyle = NavStyle.GLASS
): ResponsiveNavDimensions {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current

    val screenWidthDp = with(density) { configuration.screenWidthDp.dp }

    val baseSideMargin = 16.dp
    val availableWidth = (screenWidthDp - baseSideMargin * 2).coerceAtLeast(0.dp)

    val minItemWidth = 36.dp
    val maxItemWidth = 64.dp
    val minIconSize = 16.dp
    val maxIconSize = 24.dp

    val gap = when (style) {
        NavStyle.GLASS, NavStyle.SOFT_LIGHT, NavStyle.DEFAULT -> 4.dp
        NavStyle.FLOATING -> 6.dp
    }

    val computedItemWidth = (availableWidth - gap * (itemCount - 1)) / itemCount
    val itemWidth = computedItemWidth.coerceIn(minItemWidth, maxItemWidth)
    val containerHeight = (itemWidth * 1.3f).coerceIn(46.dp, 80.dp)
    val iconSize = (itemWidth * 0.42f).coerceIn(minIconSize, maxIconSize)
    val labelSizeDp = (itemWidth * 0.2f).coerceIn(8.dp, 11.dp)
    val labelSize = with(density) { labelSizeDp.toSp() }
    val indicatorWidth = itemWidth * 1.02f
    val indicatorHeight = containerHeight - 6.dp
    val indicatorExpandedWidth = itemWidth * 1.08f
    val indicatorExpandedHeight = containerHeight - 2.dp
    val indicatorCornerRadius = indicatorHeight * 0.65f
    val cornerRadius = containerHeight * 0.62f
    val horizontalPadding = when (style) {
        NavStyle.GLASS -> 5.dp
        NavStyle.SOFT_LIGHT -> 6.dp
        NavStyle.FLOATING -> 4.dp
        NavStyle.DEFAULT -> 5.dp
    }
    val verticalPadding = when (style) {
        NavStyle.GLASS -> 3.dp
        NavStyle.SOFT_LIGHT -> 2.dp
        NavStyle.FLOATING -> 0.dp
        NavStyle.DEFAULT -> 3.dp
    }
    val bottomMargin = when (style) {
        NavStyle.GLASS -> 24.dp
        NavStyle.SOFT_LIGHT -> 20.dp
        NavStyle.FLOATING -> 24.dp
        NavStyle.DEFAULT -> 16.dp
    }
    val sideMargin = 16.dp

    val totalHeight = when (style) {
        NavStyle.GLASS, NavStyle.SOFT_LIGHT, NavStyle.FLOATING -> containerHeight + bottomMargin
        NavStyle.DEFAULT -> containerHeight + bottomMargin
    }

    return ResponsiveNavDimensions(
        itemWidth = itemWidth,
        itemHeight = containerHeight,
        iconSize = iconSize,
        labelSize = labelSize,
        containerHeight = containerHeight,
        indicatorWidth = indicatorWidth,
        indicatorHeight = indicatorHeight,
        indicatorExpandedWidth = indicatorExpandedWidth,
        indicatorExpandedHeight = indicatorExpandedHeight,
        indicatorCornerRadius = indicatorCornerRadius,
        cornerRadius = cornerRadius,
        horizontalPadding = horizontalPadding,
        verticalPadding = verticalPadding,
        bottomMargin = bottomMargin,
        sideMargin = sideMargin,
        gap = gap,
        totalHeight = totalHeight
    )
}

@Composable
fun getNavContainerHeight(
    itemCount: Int,
    style: NavStyle
): Dp {
    val dims = computeNavDimensions(itemCount, style)
    return when (style) {
        NavStyle.DEFAULT -> 56.dp
        else -> dims.totalHeight
    }
}

@Composable
fun LiquidGlassNavigationBarItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dims: ResponsiveNavDimensions
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = when {
            isPressed -> 0.88f
            selected -> 1.1f
            else -> 1.0f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "navScale"
    )

    val labelAlpha by animateFloatAsState(
        targetValue = if (selected) 1f else 0.55f,
        animationSpec = tween(durationMillis = 150),
        label = "labelAlpha"
    )

    val labelColor = if (selected) {
        MiuixTheme.colorScheme.onSurface
    } else {
        MiuixTheme.colorScheme.onSurfaceVariantSummary
    }

    Column(
        modifier = modifier
            .width(dims.itemWidth)
            .fillMaxHeight()
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier
                .size(dims.iconSize)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
            tint = if (selected) {
                MiuixTheme.colorScheme.primary
            } else {
                MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.75f)
            }
        )

        Text(
            text = label,
            fontSize = dims.labelSize,
            color = labelColor,
            modifier = Modifier
                .padding(top = 1.dp)
                .graphicsLayer {
                    alpha = labelAlpha
                }
        )
    }
}

@Composable
private fun LiquidGlassIndicator(
    offsetX: Float,
    isDragging: Boolean,
    expandedScale: Float,
    backdrop: Backdrop,
    dims: ResponsiveNavDimensions,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val surfaceColor = if (isDark) {
        Color.White.copy(alpha = 0.08f)
    } else {
        Color.White.copy(alpha = 0.50f)
    }

    Box(
        modifier = modifier
            .offset { IntOffset(x = offsetX.roundToInt(), y = 0) }
            .width(dims.indicatorWidth)
            .height(dims.indicatorHeight)
            .graphicsLayer {
                scaleX = expandedScale
                scaleY = expandedScale
            }
            .drawBackdrop(
                backdrop = backdrop,
                shape = { RoundedCornerShape(dims.indicatorCornerRadius) },
                effects = {
                    blur(radius = 12f)
                },
                highlight = {
                    Highlight(
                        width = 0.5.dp,
                        blurRadius = 0.5.dp,
                        alpha = 1f,
                        style = com.kyant.backdrop.highlight.HighlightStyle.Default
                    )
                },
                shadow = {
                    Shadow(
                        radius = if (!isDragging) 16.dp else 10.dp,
                        color = if (isDark) {
                            Color.Black.copy(alpha = 0.30f)
                        } else {
                            Color.Black.copy(alpha = 0.10f)
                        }
                    )
                },
                innerShadow = {
                    InnerShadow(
                        radius = if (isDark) 10.dp else 8.dp,
                        color = if (isDark) {
                            Color.White.copy(alpha = 0.08f)
                        } else {
                            Color.White.copy(alpha = 0.12f)
                        }
                    )
                },
                onDrawSurface = {
                    drawRect(
                        color = surfaceColor
                    )
                }
            )
    )
}

@Composable
fun LiquidGlassNavigationBarWithIndicator(
    selectedIndex: Int,
    itemCount: Int,
    backdrop: Backdrop,
    onIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val dims = computeNavDimensions(itemCount, NavStyle.GLASS)
    var contentWidthPx by remember { mutableStateOf(0) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val itemWidthPx = with(density) { dims.itemWidth.toPx() }
    val indicatorWidthPx = with(density) { dims.indicatorWidth.toPx() }
    val indicatorExpandedWidthPx = with(density) { dims.indicatorExpandedWidth.toPx() }
    val isDark = isSystemInDarkTheme()

    LaunchedEffect(selectedIndex) {
        dragOffset = 0f
        isDragging = false
    }

    val baseTargetOffset = run {
        if (contentWidthPx > 0 && itemCount > 0) {
            val totalItemWidth = itemWidthPx * itemCount
            val remainingSpace = contentWidthPx - totalItemWidth
            val gap = remainingSpace / (itemCount + 1)
            val itemLeft = gap * (selectedIndex + 1) + itemWidthPx * selectedIndex
            val itemCenter = itemLeft + itemWidthPx / 2
            itemCenter - indicatorExpandedWidthPx / 2
        } else {
            0f
        }
    }

    val indicatorOffsetPx by animateFloatAsState(
        targetValue = baseTargetOffset + dragOffset,
        animationSpec = if (isDragging) {
            spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessHigh
            )
        } else {
            spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessLow
            )
        },
        label = "indicatorOffset"
    )

    val containerSurfaceColor = if (isDark) {
        Color.White.copy(alpha = 0.08f)
    } else {
        Color.White.copy(alpha = 0.50f)
    }

    Box(
        modifier = modifier
            .padding(
                start = dims.sideMargin,
                end = dims.sideMargin,
                bottom = dims.bottomMargin
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(dims.containerHeight)
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { RoundedCornerShape(dims.cornerRadius) },
                    effects = {
                        blur(radius = 14f)
                        lens(
                            refractionHeight = 20f,
                            refractionAmount = 20f
                        )
                    },
                    highlight = {
                        Highlight(
                            width = 0.5.dp,
                            blurRadius = 0.5.dp,
                            alpha = 1f,
                            style = com.kyant.backdrop.highlight.HighlightStyle.Default
                        )
                    },
                    shadow = {
                        Shadow(
                            radius = 16.dp,
                            color = if (isDark) {
                                Color.Black.copy(alpha = 0.20f)
                            } else {
                                Color.Black.copy(alpha = 0.08f)
                            }
                        )
                    },
                    innerShadow = {
                        InnerShadow(
                            radius = 14.dp,
                            color = if (isDark) {
                                Color.White.copy(alpha = 0.05f)
                            } else {
                                Color.White.copy(alpha = 0.10f)
                            }
                        )
                    },
                    onDrawSurface = {
                        drawRect(
                            color = containerSurfaceColor
                        )
                    }
                )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .padding(
                        horizontal = dims.horizontalPadding,
                        vertical = dims.verticalPadding
                    )
                    .onSizeChanged { size ->
                        contentWidthPx = size.width
                    },
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                content()
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(dims.containerHeight)
                    .padding(
                        horizontal = dims.horizontalPadding,
                        vertical = dims.verticalPadding
                    )
            ) {
                LiquidGlassIndicator(
                    offsetX = indicatorOffsetPx,
                    isDragging = isDragging,
                    expandedScale = if (isDragging) 1.04f else 1f,
                    backdrop = backdrop,
                    dims = dims,
                    modifier = Modifier
                        .pointerInput(selectedIndex, itemCount) {
                            var totalDrag = 0f
                            detectTapGestures(
                                onTap = { }
                            )
                            detectDragGestures(
                                onDragStart = {
                                    isDragging = true
                                    totalDrag = 0f
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    totalDrag += dragAmount.x
                                    val currentBase = run {
                                        if (contentWidthPx > 0 && itemCount > 0) {
                                            val totalItemWidth = itemWidthPx * itemCount
                                            val remainingSpace = contentWidthPx - totalItemWidth
                                            val gap = remainingSpace / (itemCount + 1)
                                            val itemLeft = gap * (selectedIndex + 1) + itemWidthPx * selectedIndex
                                            val itemCenter = itemLeft + itemWidthPx / 2
                                            itemCenter - indicatorExpandedWidthPx / 2
                                        } else {
                                            0f
                                        }
                                    }
                                    val maxDragRight = (contentWidthPx - indicatorWidthPx - currentBase)
                                        .coerceAtLeast(0f)
                                    val maxDragLeft = (-currentBase).coerceAtMost(0f)
                                    dragOffset = totalDrag.coerceIn(maxDragLeft, maxDragRight)
                                },
                                onDragEnd = {
                                    isDragging = false
                                    val currentBase = run {
                                        if (contentWidthPx > 0 && itemCount > 0) {
                                            val totalItemWidth = itemWidthPx * itemCount
                                            val remainingSpace = contentWidthPx - totalItemWidth
                                            val gap = remainingSpace / (itemCount + 1)
                                            val itemLeft = gap * (selectedIndex + 1) + itemWidthPx * selectedIndex
                                            val itemCenter = itemLeft + itemWidthPx / 2
                                            itemCenter - indicatorExpandedWidthPx / 2
                                        } else {
                                            0f
                                        }
                                    }
                                    val indicatorCenterX = currentBase + dragOffset + indicatorWidthPx / 2
                                    var draggedIndex = selectedIndex
                                    var minDist = Float.MAX_VALUE
                                    for (i in 0 until itemCount) {
                                        val itemCenter = run {
                                            val totalItemWidth = itemWidthPx * itemCount
                                            val remainingSpace = contentWidthPx - totalItemWidth
                                            val gap = remainingSpace / (itemCount + 1)
                                            gap * (i + 1) + itemWidthPx * i + itemWidthPx / 2
                                        }
                                        val dist = abs(indicatorCenterX - itemCenter)
                                        if (dist < minDist) {
                                            minDist = dist
                                            draggedIndex = i
                                        }
                                    }
                                    dragOffset = 0f
                                    if (draggedIndex != selectedIndex) {
                                        onIndexChange(draggedIndex)
                                    }
                                },
                                onDragCancel = {
                                    isDragging = false
                                    dragOffset = 0f
                                }
                            )
                        }
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    content()
                }
            }
        }
    }
}

@Composable
fun SoftLightNavigationBarItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dims: ResponsiveNavDimensions
) {
    val labelAlpha by animateFloatAsState(
        targetValue = if (selected) 1f else 0.45f,
        animationSpec = tween(durationMillis = 100),
        label = "softLightLabelAlpha"
    )

    Column(
        modifier = modifier
            .width(dims.itemWidth)
            .fillMaxHeight()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(dims.iconSize),
            tint = if (selected) {
                MiuixTheme.colorScheme.onSurface
            } else {
                MiuixTheme.colorScheme.onSurface.copy(alpha = 0.50f)
            }
        )

        Text(
            text = label,
            fontSize = dims.labelSize,
            color = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier
                .padding(top = 1.dp)
                .graphicsLayer {
                    alpha = labelAlpha
                }
        )
    }
}

@Composable
private fun SoftLightIndicator(
    offsetX: Float,
    dims: ResponsiveNavDimensions,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val indicatorColor = if (isDark) {
        Color.White.copy(alpha = 0.08f)
    } else {
        Color.Black.copy(alpha = 0.06f)
    }

    Box(
        modifier = modifier
            .offset { IntOffset(x = offsetX.roundToInt(), y = 0) }
            .width(dims.indicatorWidth)
            .height(dims.indicatorHeight)
            .background(
                color = indicatorColor,
                shape = RoundedCornerShape(dims.cornerRadius * 0.7f)
            )
    )
}

@Composable
fun SoftLightNavigationBarWithIndicator(
    selectedIndex: Int,
    itemCount: Int,
    backdrop: Backdrop,
    onIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val dims = computeNavDimensions(itemCount, NavStyle.SOFT_LIGHT)
    var contentWidthPx by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val itemWidthPx = with(density) { dims.itemWidth.toPx() }
    val indicatorWidthPx = with(density) { dims.indicatorWidth.toPx() }
    val isDark = isSystemInDarkTheme()

    val baseTargetOffset = run {
        if (contentWidthPx > 0 && itemCount > 0) {
            val totalItemWidth = itemWidthPx * itemCount
            val remainingSpace = contentWidthPx - totalItemWidth
            val gap = remainingSpace / (itemCount + 1)
            val itemLeft = gap * (selectedIndex + 1) + itemWidthPx * selectedIndex
            val itemCenter = itemLeft + itemWidthPx / 2
            itemCenter - indicatorWidthPx / 2
        } else {
            0f
        }
    }

    val indicatorOffsetPx by animateFloatAsState(
        targetValue = baseTargetOffset,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessHigh
        ),
        label = "softLightIndicatorOffset"
    )

    val barSurfaceColor = if (isDark) {
        Color.White.copy(alpha = 0.10f)
    } else {
        Color.White.copy(alpha = 0.65f)
    }

    Box(
        modifier = modifier.padding(
            start = dims.sideMargin,
            end = dims.sideMargin,
            bottom = dims.bottomMargin
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(dims.containerHeight)
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { RoundedCornerShape(dims.cornerRadius) },
                    effects = {
                        blur(radius = 14f)
                    },
                    highlight = {
                        Highlight(
                            width = 0.5.dp,
                            blurRadius = 0.5.dp,
                            alpha = 0.6f,
                            style = com.kyant.backdrop.highlight.HighlightStyle.Default
                        )
                    },
                    shadow = {
                        Shadow(
                            radius = 12.dp,
                            color = if (isDark) {
                                Color.Black.copy(alpha = 0.12f)
                            } else {
                                Color.Black.copy(alpha = 0.05f)
                            }
                        )
                    },
                    onDrawSurface = {
                        drawRect(color = barSurfaceColor)
                    }
                )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .padding(
                        horizontal = dims.horizontalPadding,
                        vertical = dims.verticalPadding
                    )
                    .onSizeChanged { size ->
                        contentWidthPx = size.width
                    },
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                content()
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(dims.containerHeight)
                    .padding(
                        horizontal = dims.horizontalPadding,
                        vertical = dims.verticalPadding
                    )
                    .pointerInput(selectedIndex, itemCount) {
                        detectTapGestures(
                            onTap = { offset ->
                                var minDist = Float.MAX_VALUE
                                var tappedIndex = selectedIndex
                                for (i in 0 until itemCount) {
                                    val totalItemWidth = itemWidthPx * itemCount
                                    val remainingSpace = contentWidthPx - totalItemWidth
                                    val gap = remainingSpace / (itemCount + 1)
                                    val centerX = gap * (i + 1) + itemWidthPx * i + itemWidthPx / 2
                                    val dist = abs(offset.x - centerX)
                                    if (dist < minDist) {
                                        minDist = dist
                                        tappedIndex = i
                                    }
                                }
                                if (tappedIndex != selectedIndex) {
                                    onIndexChange(tappedIndex)
                                }
                            }
                        )
                    },
                contentAlignment = Alignment.CenterStart
            ) {
                SoftLightIndicator(
                    offsetX = indicatorOffsetPx,
                    dims = dims,
                    modifier = Modifier
                )
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(12.dp)
                .graphicsLayer {
                    shape = RoundedCornerShape(
                        bottomStart = dims.cornerRadius,
                        bottomEnd = dims.cornerRadius
                    )
                    clip = true
                }
                .background(
                    brush = Brush.verticalGradient(
                        colors = if (isDark) {
                            listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.06f),
                                Color.Black.copy(alpha = 0.14f)
                            )
                        } else {
                            listOf(
                                Color.Transparent,
                                Color.White.copy(alpha = 0.08f),
                                Color.White.copy(alpha = 0.18f)
                            )
                        },
                        startY = 0f,
                        endY = Float.POSITIVE_INFINITY
                    )
                )
        )
    }
}
