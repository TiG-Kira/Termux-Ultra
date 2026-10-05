// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package top.yukonga.miuix.kmp.basic

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastFirst
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.anim.folmeSpring
import top.yukonga.miuix.kmp.basic.TopAppBarState.Companion.Saver
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs
/**
 * A [BlurTopAppBar] whose large title blurs as it collapses.
 *
 * @param title The title of the [BlurTopAppBar].
 * @param largeTitleBlurRadius How far the large title blurs out as it collapses. It reaches this
 *   radius at the point the title has fully faded.
 * @param modifier The modifier to be applied to the [BlurTopAppBar].
 * @param color The background color of the [BlurTopAppBar].
 * @param titleColor The color of the collapsed small title text.
 * @param largeTitle The large title of the [BlurTopAppBar].
 * @param largeTitleColor The color of the expanded large title text.
 * @param subtitle The subtitle displayed below the title bar area.
 * @param subtitleColor The color of the subtitle text.
 * @param navigationIcon The content that represents the navigation icon.
 * @param actions The content that represents the action icons.
 * @param scrollBehavior The behavior that controls the [BlurTopAppBar].
 * @param defaultWindowInsetsPadding Whether to apply default window insets padding.
 * @param titlePadding The horizontal padding of the title and large title.
 * @param navigationIconPadding The start padding of the navigation icon.
 * @param actionIconPadding The end padding of the action icons.
 * @param titleAlpha Draw-phase opacity applied to both title containers.
 * @param bottomContent Content displayed below the title bar area.
 */
@Composable
fun BlurTopAppBar(
    title: String,
    largeTitleBlurRadius: Dp,
    modifier: Modifier = Modifier,
    color: Color = MiuixTheme.colorScheme.surface,
    titleColor: Color = MiuixTheme.colorScheme.onSurface,
    largeTitle: String = title,
    largeTitleColor: Color = MiuixTheme.colorScheme.onSurface,
    subtitle: String = "",
    subtitleColor: Color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: ScrollBehavior? = null,
    defaultWindowInsetsPadding: Boolean = true,
    titlePadding: Dp = TopAppBarDefaults.TitlePadding,
    navigationIconPadding: Dp = TopAppBarDefaults.NavigationIconPadding,
    actionIconPadding: Dp = TopAppBarDefaults.ActionIconPadding,
    titleAlpha: () -> Float = { 1f },
    bottomContent: @Composable () -> Unit = {},
) {
    // Wrap the given actions in a Row.
    val actionsRow =
        @Composable {
            Row(
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
                content = actions,
            )
        }

    // Compose a Surface with a TopAppBarLayout content.
    // The surface's background color is animated as specified above.
    // The height of the app bar is determined by subtracting the bar's height offset from the
    // app bar's defined constant height value (i.e. the ContainerHeight token).
    TopAppBarLayout(
        title = title,
        titleAlpha = titleAlpha,
        color = color,
        titleColor = titleColor,
        largeTitle = largeTitle,
        largeTitleColor = largeTitleColor,
        largeTitleBlurRadius = largeTitleBlurRadius,
        subtitle = subtitle,
        subtitleColor = subtitleColor,
        navigationIcon = navigationIcon,
        actions = actionsRow,
        titlePadding = titlePadding,
        navigationIconPadding = navigationIconPadding,
        actionIconPadding = actionIconPadding,
        scrollBehavior = scrollBehavior,
        modifier = modifier,
        defaultWindowInsetsPadding = defaultWindowInsetsPadding,
        bottomContent = bottomContent,
    )
}
@Composable
private fun TopAppBarLayout(
    title: String,
    color: Color,
    titleColor: Color,
    largeTitleColor: Color,
    subtitle: String,
    subtitleColor: Color,
    navigationIcon: @Composable () -> Unit,
    actions: @Composable () -> Unit,
    titlePadding: Dp,
    navigationIconPadding: Dp,
    actionIconPadding: Dp,
    scrollBehavior: ScrollBehavior?,
    modifier: Modifier = Modifier,
    largeTitle: String = title,
    largeTitleBlurRadius: Dp = 0.dp,
    defaultWindowInsetsPadding: Boolean = true,
    titleAlpha: () -> Float = { 1f },
    bottomContent: @Composable () -> Unit = {},
) {
    // Producer lambdas — reads stay in layout/draw phases so scroll never recomposes this subtree.
    val scrolledOffset = remember(scrollBehavior) {
        { scrollBehavior?.state?.heightOffset ?: 0f }
    }
    // The title does not merely fade out — it goes out of focus on the way, the way the source
    // system dissolves it. `progress` is shared by both so the blur peaks exactly as the last of
    // the title goes, instead of stopping short of it or outliving it.
    val largeTitleFade = remember(scrollBehavior) {
        {
            val frac = scrollBehavior?.state?.collapsedFraction ?: 0f
            (frac * 3f).coerceIn(0f, 1f)
        }
    }
    val largeTitleAlpha = remember(largeTitleFade) {
        {
            1f - largeTitleFade()
        }
    }
    val updateHeightOffsetLimit = remember(scrollBehavior) {
        { height: Int ->
            scrollBehavior?.state?.let { state ->
                val limit = -height.toFloat()
                if (state.heightOffsetLimit != limit) {
                    state.heightOffsetLimit = limit
                }
            }
            Unit
        }
    }

    // Boolean derivedStateOf invalidates only on flip → the spring fires once per crossing, not per frame.
    val smallTitleVisible by remember(scrollBehavior) {
        derivedStateOf {
            scrollBehavior?.state?.let { state ->
                state.collapsedFraction * 3f >= 1f
            } ?: false
        }
    }
    val smallTitleAlpha = remember { Animatable(if (smallTitleVisible) 1f else 0f) }
    val smallTitleTranslationY = remember { Animatable(if (smallTitleVisible) 0f else 20f) }

    LaunchedEffect(smallTitleVisible) {
        if (smallTitleVisible) {
            val showSpec = folmeSpring<Float>(damping = 1.0f, response = 0.3f)
            launch { smallTitleAlpha.animateTo(1f, showSpec) }
            launch { smallTitleTranslationY.animateTo(0f, showSpec) }
        } else {
            val hideSpec = folmeSpring<Float>(damping = 1.0f, response = 0.15f)
            launch { smallTitleAlpha.animateTo(0f, hideSpec) }
            launch { smallTitleTranslationY.animateTo(20f, hideSpec) }
        }
    }

    val animatedTitleColor by animateColorAsState(
        targetValue = titleColor,
        animationSpec = tween(durationMillis = 50),
    )
    val animatedLargeTitleColor by animateColorAsState(
        targetValue = largeTitleColor,
        animationSpec = tween(durationMillis = 50),
    )
    val animatedSubtitleColor by animateColorAsState(
        targetValue = subtitleColor,
        animationSpec = tween(durationMillis = 50),
    )

    Layout(
        {
            Box(
                Modifier
                    .layoutId("navigationIcon")
                    .padding(start = navigationIconPadding),
            ) {
                navigationIcon()
            }
            Box(
                Modifier
                    .layoutId("title")
                    .padding(horizontal = titlePadding)
                    .graphicsLayer {
                        alpha = smallTitleAlpha.value * titleAlpha()
                        translationY = smallTitleTranslationY.value
                    },
            ) {
                Text(
                    text = title,
                    color = animatedTitleColor,
                    fontSize = MiuixTheme.textStyles.title3.fontSize,
                    fontWeight = FontWeight.Medium,
                    overflow = TextOverflow.Ellipsis,
                    softWrap = false,
                )
            }
            Box(
                Modifier
                    .layoutId("actionIcons")
                    .padding(end = actionIconPadding),
            ) {
                actions()
            }
            Box(
                Modifier
                    .layoutId("largeTitle")
                    .padding(top = TopAppBarDefaults.CollapsedHeight)
                    .padding(horizontal = titlePadding)
                    .graphicsLayer {
                        alpha = largeTitleAlpha() * titleAlpha()
                        // Assigned on every path, including the disabled one: the layer keeps
                        // whatever it was last given, so a radius that drops back to zero would
                        // otherwise leave the last blur on the title for good.
                        val radius = if (largeTitleBlurRadius > 0.dp) {
                            largeTitleBlurRadius.toPx() * largeTitleFade()
                        } else {
                            0f
                        }
                        renderEffect = if (radius > 0.1f) {
                            BlurEffect(radius, radius, TileMode.Decal)
                        } else {
                            null
                        }
                    },
            ) {
                Column(
                    modifier = Modifier
                        .offset {
                            val v = scrolledOffset()
                            IntOffset(0, v.fastRoundToInt())
                        }
                        .onSizeChanged { updateHeightOffsetLimit(it.height) },
                ) {
                    Text(
                        text = largeTitle,
                        color = animatedLargeTitleColor,
                        fontSize = MiuixTheme.textStyles.title1.fontSize,
                        fontWeight = FontWeight.Normal,
                    )
                    if (subtitle.isNotEmpty()) {
                        Text(
                            text = subtitle,
                            color = animatedSubtitleColor,
                            style = MiuixTheme.textStyles.body2,
                        )
                    }
                }
            }
            if (subtitle.isNotEmpty()) {
                Box(
                    Modifier
                        .layoutId("smallSubtitle")
                        .graphicsLayer {
                            alpha = smallTitleAlpha.value * titleAlpha()
                            translationY = smallTitleTranslationY.value
                        },
                ) {
                    Text(
                        text = subtitle,
                        color = animatedSubtitleColor,
                        style = MiuixTheme.textStyles.body2,
                    )
                }
            }
            Box(Modifier.layoutId("bottomContent")) {
                bottomContent()
            }
        },
        modifier = modifier
            .background(color)
            .then(
                if (defaultWindowInsetsPadding) {
                    Modifier
                        .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
                        .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal))
                } else {
                    Modifier
                },
            )
            .clipToBounds()
            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top))
            .pointerInput(Unit) {
                detectTapGestures { /* Consume click */ }
            },
    ) { measurables, constraints ->
        val navigationIconPlaceable =
            measurables
                .fastFirst { it.layoutId == "navigationIcon" }
                .measure(constraints.copy(minWidth = 0, minHeight = 0))

        val actionIconsPlaceable =
            measurables
                .fastFirst { it.layoutId == "actionIcons" }
                .measure(constraints.copy(minWidth = 0, minHeight = 0))

        val maxTitleWidth =
            if (constraints.maxWidth == Constraints.Infinity) {
                constraints.maxWidth
            } else {
                (constraints.maxWidth - navigationIconPlaceable.width - actionIconsPlaceable.width)
                    .coerceAtLeast(0)
            }
        // 直接给 title 完整安全区宽度：两侧的 navIcon / actionIcons 已经各自占位，
        // 标题文字在安全区内部自然居中 + ellipsis。之前额外再乘 TITLE_WIDTH_FRACTION=0.9
        // 会让 title 宽度被额外砍 10%，在 actionIcons 很宽时（对话页 3 个玻璃按钮 ≈200dp）
        // 导致 title 永远挤在安全区左侧一小段。
        val titleMaxWidth = maxTitleWidth

        val titlePlaceable =
            measurables
                .fastFirst { it.layoutId == "title" }
                .measure(constraints.copy(minWidth = 0, maxWidth = titleMaxWidth, minHeight = 0))

        val largeTitlePlaceable =
            measurables
                .fastFirst { it.layoutId == "largeTitle" }
                .measure(
                    constraints.copy(
                        minWidth = 0,
                        minHeight = 0,
                        maxHeight = Constraints.Infinity,
                    ),
                )

        val smallSubtitlePlaceable =
            measurables
                .firstOrNull { it.layoutId == "smallSubtitle" }
                ?.measure(constraints.copy(minWidth = 0, maxWidth = titleMaxWidth, minHeight = 0))

        val bottomContentPlaceable =
            measurables
                .fastFirst { it.layoutId == "bottomContent" }
                .measure(constraints.copy(minWidth = 0, minHeight = 0))

        val collapsedHeight = TopAppBarDefaults.CollapsedHeight.roundToPx()
        // largeTitle Box has top padding = collapsedHeight, so subtract it back for the pure expansion.
        val expansion = (largeTitlePlaceable.height - collapsedHeight).coerceAtLeast(0)
        val barHeight = if (expansion > 0) {
            val offset = scrolledOffset()
            val collapseFraction = if (offset.isNaN()) {
                0f
            } else {
                (abs(offset) / expansion.toFloat()).coerceIn(0f, 1f)
            }
            lerp(
                start = collapsedHeight,
                stop = collapsedHeight + expansion,
                fraction = 1f - collapseFraction,
            )
        } else {
            collapsedHeight
        }

        val verticalCenter = collapsedHeight / 2
        val smallSubtitleHeight = smallSubtitlePlaceable?.height ?: 0
        val smallSubtitleBottom = verticalCenter + titlePlaceable.height / 2 + smallSubtitleHeight
        val expandedBottomPadding = if (smallSubtitlePlaceable != null) {
            TopAppBarDefaults.SubtitleBottomPadding.roundToPx()
        } else {
            TopAppBarDefaults.LargeTitleBottomPadding.roundToPx()
        }
        val contentTop = maxOf(barHeight + expandedBottomPadding, smallSubtitleBottom + expandedBottomPadding)
        val layoutHeight = contentTop + bottomContentPlaceable.height

        layout(constraints.maxWidth, layoutHeight) {
            // Navigation icon
            navigationIconPlaceable.placeRelative(
                x = 0,
                y = verticalCenter - navigationIconPlaceable.height / 2,
            )

            // Title
            // baseX 先按屏幕几何中心算，再双向 clamp 到 navIcon / actionIcons 的安全区。
            // 之前是 if/else if 互斥——当 actionIcons 很宽时（对话页 3 个按钮 ≈200dp），
            // title 同时被左右两侧挤，if/else if 只会触发一侧修正，另一侧溢出被"遗忘"。
            var baseX = (constraints.maxWidth - titlePlaceable.width) / 2
            val leftSafe = navigationIconPlaceable.width
            val rightSafe = constraints.maxWidth - actionIconsPlaceable.width - titlePlaceable.width
            baseX = baseX.coerceIn(leftSafe, rightSafe)
            titlePlaceable.placeRelative(
                x = baseX,
                y = verticalCenter - titlePlaceable.height / 2,
            )

            // Small subtitle：居中在与 title 相同的安全区内部。
            // 既不居中屏幕（会被右侧 actionIcons 挡），也不左对齐 baseX（视觉上不居中）。
            smallSubtitlePlaceable?.let { sub ->
                val subLeftSafe = navigationIconPlaceable.width
                val subRightSafe = constraints.maxWidth - actionIconsPlaceable.width - sub.width
                val subCenter = subLeftSafe + ((subRightSafe - subLeftSafe) / 2)
                val subX = subCenter.coerceIn(subLeftSafe, subRightSafe)
                sub.placeRelative(
                    x = subX,
                    y = verticalCenter + titlePlaceable.height / 2,
                )
            }

            // Action icons
            actionIconsPlaceable.placeRelative(
                x = constraints.maxWidth - actionIconsPlaceable.width,
                y = verticalCenter - actionIconsPlaceable.height / 2,
            )

            // Large title (includes large subtitle in a Column)
            largeTitlePlaceable.placeRelative(x = 0, y = 0)

            // Bottom content (pinned, below bar and subtitle)
            bottomContentPlaceable.placeRelative(x = 0, y = contentTop)
        }
    }
}

// 上游私有常量，原与 TopAppBarLayout 同处 TopAppBar.kt，vendor 拆文件后需一并带走。
private const val TITLE_WIDTH_FRACTION = 0.9
