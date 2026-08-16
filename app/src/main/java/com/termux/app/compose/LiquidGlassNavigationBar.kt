package com.termux.app.compose

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
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

private val GlassContainerHeight = 64.dp
private val GlassContainerHorizontalPadding = 10.dp
private val GlassContainerVerticalPadding = 6.dp
private val GlassItemWidth = 56.dp
private val GlassIndicatorWidth = 68.dp
private val GlassIndicatorHeight = 52.dp
private val GlassIndicatorExpandedWidth = 76.dp
private val GlassIndicatorExpandedHeight = 56.dp
private val GlassCornerRadius = 32.dp
private val GlassBottomMargin = 18.dp

@Composable
fun LiquidGlassNavigationBarItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
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
            .width(GlassItemWidth)
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
                .size(24.dp)
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
            fontSize = 10.sp,
            color = labelColor,
            modifier = Modifier
                .padding(top = 2.dp)
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
    backdrop: Backdrop,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()

    val expandedScale by animateFloatAsState(
        targetValue = if (!isDragging) 1.12f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "indicatorExpanded"
    )

    val indicatorWidth by animateDpAsState(
        targetValue = if (!isDragging) GlassIndicatorExpandedWidth else GlassIndicatorWidth,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "indicatorWidth"
    )

    val indicatorHeight by animateDpAsState(
        targetValue = if (!isDragging) GlassIndicatorExpandedHeight else GlassIndicatorHeight,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "indicatorHeight"
    )

    val surfaceColor = if (isDark) {
        Color.White.copy(alpha = 0.12f)
    } else {
        Color.White.copy(alpha = 0.65f)
    }

    Box(
        modifier = modifier
            .offset {
                IntOffset(
                    x = offsetX.roundToInt(),
                    y = 0
                )
            }
            .width(indicatorWidth)
            .height(indicatorHeight)
            .graphicsLayer {
                scaleX = expandedScale
                scaleY = expandedScale
            }
            .drawBackdrop(
                backdrop = backdrop,
                shape = { RoundedCornerShape(28.dp) },
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
    var contentWidthPx by remember { mutableStateOf(0) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val itemWidthPx = with(density) { GlassItemWidth.toPx() }
    val indicatorWidthPx = with(density) { GlassIndicatorWidth.toPx() }
    val indicatorExpandedWidthPx = with(density) { GlassIndicatorExpandedWidth.toPx() }
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
            .padding(bottom = GlassBottomMargin)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(GlassContainerHeight)
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { RoundedCornerShape(GlassCornerRadius) },
                    effects = {
                        blur(radius = 18f)
                        lens(
                            refractionHeight = 28f,
                            refractionAmount = 28f
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
                            radius = 24.dp,
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
                        horizontal = GlassContainerHorizontalPadding,
                        vertical = GlassContainerVerticalPadding
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
                    .height(GlassContainerHeight)
                    .padding(
                        horizontal = GlassContainerHorizontalPadding,
                        vertical = GlassContainerVerticalPadding
                    )
            ) {
                LiquidGlassIndicator(
                    offsetX = indicatorOffsetPx,
                    isDragging = isDragging,
                    backdrop = backdrop,
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
