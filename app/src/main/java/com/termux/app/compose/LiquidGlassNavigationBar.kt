package com.termux.app.compose

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

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

    val baseSideMargin = 24.dp
    val availableWidth = (screenWidthDp - baseSideMargin * 2).coerceAtLeast(0.dp)

    val minItemWidth = 32.dp
    val maxItemWidth = 56.dp
    val minIconSize = 16.dp
    val maxIconSize = 22.dp

    val gap = when (style) {
        NavStyle.GLASS, NavStyle.SOFT_LIGHT, NavStyle.DEFAULT -> 4.dp
        NavStyle.FLOATING -> 6.dp
    }

    // SpaceEvenly 布局间距：两端和之间都有间距，共 itemCount + 1 个间距
    val computedItemWidth = (availableWidth - gap * (itemCount + 1)) / itemCount
    val itemWidth = computedItemWidth.coerceIn(minItemWidth, maxItemWidth)
    val containerHeight = (itemWidth * 1.15f).coerceIn(42.dp, 64.dp)
    val iconSize = (itemWidth * 0.40f).coerceIn(minIconSize, maxIconSize)
    val labelSizeDp = (itemWidth * 0.18f).coerceIn(8.dp, 10.dp)
    val labelSize = with(density) { labelSizeDp.toSp() }
    val indicatorWidth = itemWidth * 1.25f
    val indicatorHeight = containerHeight * 0.92f
    val indicatorExpandedWidth = itemWidth * 1.35f
    val indicatorExpandedHeight = containerHeight * 0.97f
    val indicatorCornerRadius = indicatorHeight * 0.68f
    val cornerRadius = containerHeight * 0.65f
    val horizontalPadding = when (style) {
        NavStyle.GLASS -> 6.dp
        NavStyle.SOFT_LIGHT -> 6.dp
        NavStyle.FLOATING -> 4.dp
        NavStyle.DEFAULT -> 5.dp
    }
    val verticalPadding = when (style) {
        NavStyle.GLASS -> 4.dp
        NavStyle.SOFT_LIGHT -> 3.dp
        NavStyle.FLOATING -> 0.dp
        NavStyle.DEFAULT -> 3.dp
    }
    val bottomMargin = when (style) {
        NavStyle.GLASS -> 24.dp
        NavStyle.SOFT_LIGHT -> 20.dp
        NavStyle.FLOATING -> 24.dp
        NavStyle.DEFAULT -> 16.dp
    }
    val sideMargin = 24.dp

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
    dims: ResponsiveNavDimensions,
    index: Int = -1,
    onPositioned: ((Int, Float) -> Unit)? = null
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
            .onGloballyPositioned { coordinates ->
                // positionInParent() 返回相对于父级（Row）的位置
                onPositioned?.invoke(index, coordinates.positionInParent().x + coordinates.size.width / 2f)
            }
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

/**
 * 玻璃导航栏 — 按官方 LiquidBottomTabs 的结构实现：
 * 1. 外层 Row：绘制容器玻璃背景（vibrancy + blur + lens + Capsule 形状）
 * 2. 内层 Row（alpha=0）：通过 layerBackdrop 捕获按钮层，colorFilter 着色
 * 3. 指示器 Box：使用 rememberCombinedBackdrop 同时折射容器和按钮层
 * 4. 手势层：透明层处理点击和拖动
 */
@Composable
fun LiquidGlassNavigationBarWithIndicator(
    selectedIndex: Int,
    itemCount: Int,
    backdrop: Backdrop,
    onIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onItemPositioned: (Int, Float) -> Unit = { _, _ -> },
    content: @Composable ((Int, Float) -> Unit) -> Unit
) {
    val dims = computeNavDimensions(itemCount, NavStyle.GLASS)
    val density = LocalDensity.current
    val isDark = isSystemInDarkTheme()
    val isLightTheme = !isDark
    val scope = rememberCoroutineScope()

    // 官方配色
    val accentColor = if (isLightTheme) Color(0xFF0088FF) else Color(0xFF0091FF)
    val containerColor = if (isLightTheme) Color(0xFFFAFAFA).copy(0.28f) else Color(0xFF121212).copy(0.28f)

    // 按钮层 backdrop（用于指示器 rememberCombinedBackdrop）
    val tabsBackdrop = rememberLayerBackdrop()

    // 存储实际测量的按钮中心位置
    var measuredCenters by remember { mutableStateOf<List<Float>>(emptyList()) }

    // 内部位置更新函数
    val updatePosition: (Int, Float) -> Unit = { index, centerX ->
        // centerX 是按钮相对于父级 Row 内容区（padding 后）的位置
        // 但指示器的坐标原点是 BoxWithConstraints 的左边（包括 Row 的 padding）
        // 所以需要加上 Row 的左侧 padding (4dp)
        val adjustedCenterX = centerX + with(density) { 4.dp.toPx() }
        measuredCenters = if (index in 0 until itemCount) {
            val newList = measuredCenters.toMutableList()
            while (newList.size <= index) newList.add(0f)
            newList[index] = adjustedCenterX
            newList
        } else {
            measuredCenters
        }
        // 同时调用外部回调
        onItemPositioned(index, adjustedCenterX)
    }

    BoxWithConstraints(
        modifier = modifier
            .padding(
                start = dims.sideMargin,
                end = dims.sideMargin,
                bottom = dims.bottomMargin
            ),
        contentAlignment = Alignment.CenterStart
    ) {
        val maxWidthPx = with(density) { maxWidth.toPx() }
        val indicatorWidthPx = with(density) { dims.indicatorWidth.toPx() }
        val innerPaddingPx = with(density) { 4.dp.toPx() }
        val contentWidthPx = maxWidthPx - innerPaddingPx * 2

        // 根据实际测量的位置计算指示器位置
        val itemCenters = if (measuredCenters.size == itemCount) {
            measuredCenters
        } else {
            // 回退到计算值
            val itemWidthPx = with(density) { dims.itemWidth.toPx() }
            val evenlySpacing = (contentWidthPx - itemCount * itemWidthPx) / (itemCount + 1)
            (0 until itemCount).map { i ->
                innerPaddingPx + evenlySpacing * (i + 1) + itemWidthPx * i + itemWidthPx / 2
            }
        }

        // 指示器居中偏移
        val indicatorCenterOffset = -indicatorWidthPx / 2
        // 指示器移动范围限制
        val maxIndicatorTranslation = (maxWidthPx - indicatorWidthPx).coerceAtLeast(0f)

        // 指示器位置动画
        val indicatorAnim = remember { Animatable(0f) }
        LaunchedEffect(selectedIndex) {
            indicatorAnim.animateTo(
                selectedIndex.toFloat(),
                spring(stiffness = Spring.StiffnessLow)
            )
        }

        // 拖动状态
        var isDragging by remember { mutableStateOf(false) }

        // ====== 第1层：容器玻璃背景 Row（Capsule 形状） ======
        Row(
            Modifier
                .graphicsLayer {
                    translationX = 0f
                }
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { Capsule() },
                    effects = {
                        vibrancy()
                        blur(8f.dp.toPx())
                        lens(24f.dp.toPx(), 24f.dp.toPx())
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
                            color = if (isDark) Color.Black.copy(alpha = 0.20f)
                            else Color.Black.copy(alpha = 0.08f)
                        )
                    },
                    innerShadow = {
                        InnerShadow(
                            radius = 14.dp,
                            color = if (isDark) Color.White.copy(alpha = 0.05f)
                            else Color.White.copy(alpha = 0.10f)
                        )
                    },
                    onDrawSurface = { drawRect(containerColor) }
                )
                .height(dims.containerHeight)
                .fillMaxWidth()
                .padding(4f.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            content(updatePosition)
        }

        // ====== 第2层：按钮层（不可见，通过 layerBackdrop 捕获按钮内容） ======
        Row(
            Modifier
                .clearAndSetSemantics {}
                .alpha(0f)
                .layerBackdrop(tabsBackdrop)
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { Capsule() },
                    effects = {
                        vibrancy()
                        blur(8f.dp.toPx())
                    },
                    highlight = {
                        Highlight(
                            width = 0.5.dp,
                            blurRadius = 0.5.dp,
                            alpha = 0.6f,
                            style = com.kyant.backdrop.highlight.HighlightStyle.Default
                        )
                    },
                    onDrawSurface = { drawRect(containerColor) }
                )
                .height(dims.containerHeight - 8.dp)
                .fillMaxWidth()
                .padding(horizontal = 4f.dp)
                .graphicsLayer(colorFilter = ColorFilter.tint(accentColor)),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            content(updatePosition)
        }

        // ====== 第3层：指示器（使用 rememberCombinedBackdrop 同时折射容器和按钮层） ======
        Box(
            Modifier
                .graphicsLayer {
                    // 根据当前动画索引获取对应的中心位置
                    val index = indicatorAnim.value.coerceIn(0f, (itemCount - 1).toFloat())
                    val centerX = if (index < itemCenters.size) {
                        itemCenters[index.toInt().coerceIn(0, itemCenters.size - 1)]
                    } else {
                        itemCenters.lastOrNull() ?: 0f
                    }
                    // 小数部分用于平滑过渡
                    val fractional = index - index.toInt()
                    val centerXNext = if (index.toInt() + 1 < itemCenters.size) {
                        itemCenters[index.toInt() + 1]
                    } else {
                        centerX
                    }
                    val smoothedCenterX = centerX + (centerXNext - centerX) * fractional
                    translationX = (smoothedCenterX + indicatorCenterOffset).coerceIn(0f, maxIndicatorTranslation)
                }
                .drawBackdrop(
                    backdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop),
                    shape = { Capsule() },
                    effects = {
                        lens(
                            12f.dp.toPx(),
                            18f.dp.toPx(),
                            chromaticAberration = true
                        )
                    },
                    highlight = {
                        Highlight.Default.copy(alpha = if (isDragging) 1f else 0.6f)
                    },
                    shadow = {
                        Shadow(alpha = if (isDragging) 1f else 0.7f)
                    },
                    innerShadow = {
                        InnerShadow(
                            radius = 8f.dp,
                            alpha = if (isDragging) 1f else 0.7f
                        )
                    },
                    layerBlock = {
                        if (isDragging) {
                            scaleX = 1.08f
                            scaleY = 1.08f
                        }
                    },
                    onDrawSurface = {
                        drawRect(
                            if (isLightTheme) Color.Black.copy(0.08f)
                            else Color.White.copy(0.08f),
                            alpha = if (isDragging) 0.35f else 0.55f
                        )
                        drawRect(Color.Black.copy(alpha = 0.02f))
                    }
                )
                .height(dims.indicatorHeight)
                .width(dims.indicatorWidth)
        )

        // ====== 第4层：手势检测层（最上层） ======
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(dims.containerHeight)
                .pointerInput(selectedIndex, itemCount, itemCenters, contentWidthPx) {
                    detectTapGestures(
                        onTap = { offset ->
                            // 找到最近的按钮中心
                            var closestIndex = 0
                            var minDist = Float.MAX_VALUE
                            for ((i, center) in itemCenters.withIndex()) {
                                val dist = abs(offset.x - center)
                                if (dist < minDist) {
                                    minDist = dist
                                    closestIndex = i
                                }
                            }
                            if (closestIndex != selectedIndex) {
                                onIndexChange(closestIndex)
                            }
                        }
                    )
                    detectDragGestures(
                        onDragStart = { isDragging = true },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val currentIndex = indicatorAnim.value
                            // 使用按钮间距作为拖动比例
                            val avgSpacing = contentWidthPx / itemCount
                            val newIndex = (currentIndex + dragAmount.x / avgSpacing)
                                .coerceIn(0f, (itemCount - 1).toFloat())
                            scope.launch {
                                indicatorAnim.snapTo(newIndex)
                            }
                        },
                        onDragEnd = {
                            isDragging = false
                            val targetIndex = indicatorAnim.value.fastRoundToInt().coerceIn(0, itemCount - 1)
                            scope.launch {
                                indicatorAnim.animateTo(targetIndex.toFloat())
                            }
                            if (targetIndex != selectedIndex) {
                                onIndexChange(targetIndex)
                            }
                        },
                        onDragCancel = {
                            isDragging = false
                            scope.launch {
                                indicatorAnim.animateTo(selectedIndex.toFloat())
                            }
                        }
                    )
                }
        )
    }
}

// ==================== 柔光导航栏（保持不变） ====================

/**
 * 计算每个按钮项的中心位置
 * @param contentWidthPx 内容区域宽度（像素）
 * @param itemCount 按钮数量
 * @param itemWidthPx 每个按钮宽度（像素）
 * @return 每个按钮中心位置的列表
 */
private fun calculateItemCenters(contentWidthPx: Float, itemCount: Int, itemWidthPx: Float): List<Float> {
    if (contentWidthPx <= 0f || itemCount <= 0) return emptyList()
    val totalItemWidth = itemWidthPx * itemCount
    val gap = (contentWidthPx - totalItemWidth) / (itemCount + 1)
    return (0 until itemCount).map { i ->
        gap * (i + 1) + itemWidthPx * i + itemWidthPx / 2
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
    isDragging: Boolean,
    expandedScale: Float,
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
            .graphicsLayer {
                scaleX = expandedScale
                scaleY = expandedScale
            }
            .background(
                color = indicatorColor,
                shape = RoundedCornerShape(dims.indicatorCornerRadius)
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
    var contentWidthPx by remember { mutableStateOf(0f) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val itemWidthPx = with(density) { dims.itemWidth.toPx() }
    val indicatorWidthPx = with(density) { dims.indicatorWidth.toPx() }
    val isDark = isSystemInDarkTheme()

    LaunchedEffect(selectedIndex) {
        dragOffset = 0f
        isDragging = false
    }

    val itemCenters = remember(contentWidthPx, itemCount, itemWidthPx) {
        calculateItemCenters(contentWidthPx, itemCount, itemWidthPx)
    }

    val baseTargetOffset = run {
        if (itemCenters.size > selectedIndex) {
            itemCenters[selectedIndex] - indicatorWidthPx / 2
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
                    .onSizeChanged {
                        contentWidthPx = it.width.toFloat()
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
                    .pointerInput(selectedIndex, itemCount, contentWidthPx) {
                        var totalDrag = 0f
                        detectTapGestures(
                            onTap = { offset ->
                                var minDist = Float.MAX_VALUE
                                var tappedIndex = selectedIndex
                                val centers = calculateItemCenters(contentWidthPx, itemCount, itemWidthPx)
                                for (i in centers.indices) {
                                    val dist = abs(offset.x - centers[i])
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
                        detectDragGestures(
                            onDragStart = {
                                isDragging = true
                                totalDrag = 0f
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                totalDrag += dragAmount.x
                                val centers = calculateItemCenters(contentWidthPx, itemCount, itemWidthPx)
                                val currentBase = if (centers.size > selectedIndex) {
                                    centers[selectedIndex] - indicatorWidthPx / 2
                                } else 0f
                                val maxDragRight = (contentWidthPx - indicatorWidthPx - currentBase).coerceAtLeast(0f)
                                val maxDragLeft = (-currentBase).coerceAtMost(0f)
                                dragOffset = totalDrag.coerceIn(maxDragLeft, maxDragRight)
                            },
                            onDragEnd = {
                                isDragging = false
                                val centers = calculateItemCenters(contentWidthPx, itemCount, itemWidthPx)
                                val currentBase = if (centers.size > selectedIndex) {
                                    centers[selectedIndex] - indicatorWidthPx / 2
                                } else 0f
                                val indicatorCenterX = currentBase + dragOffset + indicatorWidthPx / 2
                                var draggedIndex = selectedIndex
                                var minDist = Float.MAX_VALUE
                                for (i in centers.indices) {
                                    val dist = abs(indicatorCenterX - centers[i])
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
                    },
                contentAlignment = Alignment.CenterStart
            ) {
                SoftLightIndicator(
                    offsetX = indicatorOffsetPx,
                    isDragging = isDragging,
                    expandedScale = if (isDragging) 1.04f else 1f,
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
                    brush = androidx.compose.ui.graphics.Brush.verticalGradient(
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
