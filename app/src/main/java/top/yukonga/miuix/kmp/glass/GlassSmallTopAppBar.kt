// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package top.yukonga.miuix.kmp.glass

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * A [SmallTopAppBar] carrying the [GlassTopAppBar] treatment.
 *
 * 融合目标：保留 SmallTopAppBar 固定小高度、不折叠的空间优势，同时叠加 GlassTopAppBar 的
 * 内部穿透渐隐玻璃——内容滚到顶栏下方时被 band 压暗渐隐、玻璃胶囊按钮随浮动过渡亮起。
 *
 * 与 [GlassTopAppBar] 的差异（魔改点，共 3 处）：
 * 1. **内嵌栏换成 SmallTopAppBar**（见文件底部 CompositionLocalProvider 内）：固定小高度、
 *    标题恒为居中标题、无大标题折叠过程；`subtitle` 由 SmallTopAppBar 布局居中置于标题下方。
 *    `scrollBehavior` 仍须传入——SmallTopAppBar 靠它把状态 pin 住（heightOffsetLimit /
 *    heightOffset 恒 0，顶栏因此不折叠），而 `contentOffset` 随内容滚动继续累积，玻璃的
 *    band / 浮动过渡由 `contentOffset` 驱动，与是否折叠解耦。
 * 2. **删去 largeTitle / largeTitleBlurRadius / largeTitleColor / titleAlpha 参数**：
 *    SmallTopAppBar 没有大标题与标题淡出能力，其余公共参数与 GlassTopAppBar 逐一保持同名同序。
 * 3. band（穿透渐隐带）、`LocalGlassTopAppBarContext`、浮动/材质过渡、玻璃胶囊按钮逻辑与
 *    GlassTopAppBar 完全一致，直接复用 [GlassTopAppBarDefaults] 与 GlassMotion，不另造 token。
 *
 * @param title The compact title, centred in the fixed-height bar.
 * @param backdrop The page behind the bar. `null` uses [fill].
 * @param modifier The modifier applied to the bar.
 * @param subtitle Optional line under the title, laid out by [SmallTopAppBar].
 * @param scrollBehavior The behavior driving the glass transitions. The bar itself never
 *   collapses — [SmallTopAppBar] pins the state — but `contentOffset` still accumulates as
 *   content scrolls under the bar, which is what turns the band and the pills on. Without one
 *   the bar stays at full strength, which is what a bar over non-scrolling content wants.
 *   Only user gestures feed `contentOffset`: a page that scrolls itself (`animateScrollTo`,
 *   `animateScrollToItem`) never reaches this connection, so see the [isContentScrolled]
 *   overload instead.
 * @param bandBrush The band that dims the content passing under the bar.
 * @param bandOverhang How far the band reaches past the bottom of the bar.
 * @param style Compatibility style forwarded to the renderer. The default button body uses
 *   [GlassTopAppBarDefaults.buttonMaterial] with bionic shading disabled.
 * @param alpha Opacity multiplier for the button pills, on top of their floating transition.
 * @param buttonShape Silhouette of the button pills. Defaults to a circle of [buttonSize].
 * @param buttonSize Diameter of the button pills.
 * @param buttonShadow The shadow under the button pills. `null` removes it.
 * @param fill Traditional fill behind the button pills when runtime shaders are unavailable.
 * @param stroke Optional bloom stroke on the button pills. The default is the
 *   small action-button stroke.
 * @param defaultWindowInsetsPadding Whether to inset the bar for the status bar.
 * @param contentModifier Applied to the foreground bar, leaving its background band stationary.
 * @param navigationIcon The leading control. Use [GlassIconButton] for the same glass surface and
 *   press feedback as trailing controls; the slot adds no surface of its own.
 * @param actions Trailing controls. Wrap them in [GlassIconButton] to give them the same pill.
 * @param bottomContent Content pinned under the bar, inside its layout. A tab row goes here.
 */
@Composable
fun GlassSmallTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    contentModifier: Modifier = Modifier,
    backdrop: Backdrop? = null,
    subtitle: String = "",
    scrollBehavior: ScrollBehavior? = null,
    bandBrush: Brush = GlassTopAppBarDefaults.bandBrush(MiuixTheme.colorScheme.surface),
    bandOverhang: Dp = GlassTopAppBarDefaults.BandOverhang,
    style: GlassStyle = GlassDefaults.Style,
    alpha: Float = 1f,
    buttonSize: Dp = GlassTopAppBarDefaults.ButtonSize,
    buttonShape: GlassShape = GlassShape(buttonSize / 2),
    buttonPadding: Dp = GlassTopAppBarDefaults.ButtonPadding,
    fill: Color = GlassTopAppBarDefaults.buttonFill(),
    stroke: GlassStroke? = GlassTopAppBarDefaults.buttonStroke(),
    buttonShadow: GlassShadow? = GlassShadows.Regular,
    defaultWindowInsetsPadding: Boolean = true,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    bottomContent: @Composable () -> Unit = {},
) {
    // 固定小高度顶栏没有折叠过程，穿透信号只看 contentOffset：内容滚过顶栏下沿即视为已滚动。
    // 无 scrollBehavior 时（内容不滚动）保持全强度，与 GlassTopAppBar 的语义一致。
    val isContentScrolled by remember(scrollBehavior) {
        derivedStateOf { scrollBehavior?.state?.let { it.contentOffset < 0f } ?: true }
    }
    GlassSmallTopAppBar(
        title = title,
        isContentScrolled = isContentScrolled,
        modifier = modifier,
        contentModifier = contentModifier,
        backdrop = backdrop,
        subtitle = subtitle,
        scrollBehavior = scrollBehavior,
        bandBrush = bandBrush,
        bandOverhang = bandOverhang,
        style = style,
        alpha = alpha,
        buttonSize = buttonSize,
        buttonShape = buttonShape,
        buttonPadding = buttonPadding,
        fill = fill,
        stroke = stroke,
        buttonShadow = buttonShadow,
        defaultWindowInsetsPadding = defaultWindowInsetsPadding,
        navigationIcon = navigationIcon,
        actions = actions,
        bottomContent = bottomContent,
    )
}

/**
 * A glass small top bar with an explicit page-overlap state.
 *
 * Use this overload whenever the page is not scrolled by touch alone: `animateScrollTo` /
 * `animateScrollToItem` are programmatic scrolls that never reach the behaviour's nested-scroll
 * connection, so its `contentOffset` stays at 0, the band never turns on and the pills keep no
 * plate (the button shadow rides the same ramp). Deriving the state from the real scroll position
 * fixes self-scrolling pages such as log/console views.
 *
 * @param title The compact title.
 * @param isContentScrolled Whether page content has left its resting position. For a lazy list,
 *   pass `listState.canScrollBackward`; for a vertically scrolling column pass
 *   `scrollState.value > 0`. This clears the material when the page returns to the top
 *   even if the bar keeps its fixed height. Other parameters behave as in the scroll-behaviour
 *   overload.
 */
@Composable
fun GlassSmallTopAppBar(
    title: String,
    isContentScrolled: Boolean,
    modifier: Modifier = Modifier,
    contentModifier: Modifier = Modifier,
    backdrop: Backdrop? = null,
    subtitle: String = "",
    scrollBehavior: ScrollBehavior? = null,
    bandBrush: Brush = GlassTopAppBarDefaults.bandBrush(MiuixTheme.colorScheme.surface),
    bandOverhang: Dp = GlassTopAppBarDefaults.BandOverhang,
    style: GlassStyle = GlassDefaults.Style,
    alpha: Float = 1f,
    buttonSize: Dp = GlassTopAppBarDefaults.ButtonSize,
    buttonShape: GlassShape = GlassShape(buttonSize / 2),
    buttonPadding: Dp = GlassTopAppBarDefaults.ButtonPadding,
    fill: Color = GlassTopAppBarDefaults.buttonFill(),
    stroke: GlassStroke? = GlassTopAppBarDefaults.buttonStroke(),
    buttonShadow: GlassShadow? = GlassShadows.Regular,
    defaultWindowInsetsPadding: Boolean = true,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    bottomContent: @Composable () -> Unit = {},
) {
    // Buttons carry their own horizontal margin (see [GlassTopAppBarDefaults.ButtonPadding]), so
    // the trailing row needs no spacing of its own; the wrapper only pins them to the end.
    val actionsWithPadding: @Composable RowScope.() -> Unit = {
        Row(
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
            content = actions,
        )
    }
    // The leading slot takes a single control, so it gets the same margin directly. Without it the
    // pill would sit flush against the bar's own start padding.
    val navigationIconWithPadding: @Composable () -> Unit = {
        Box(modifier = Modifier.padding(horizontal = buttonPadding)) { navigationIcon() }
    }
    val ramp = GlassTopAppBarDefaults.collapseRamp(scrollBehavior)
    val floating = isContentScrolled
    val transition = updateTransition(floating, label = "glassSmallTopBarFloat")
    val maskProgress = transition.animateFloat(
        transitionSpec = { GlassMotion.topBarMask() },
        label = "glassSmallTopBarMask",
    ) { if (it) 1f else 0f }
    val materialProgress = transition.animateFloat(
        transitionSpec = { GlassMotion.topBarButtonFloat() },
        label = "glassSmallTopBarMaterial",
    ) { if (it) 1f else 0f }
    val buttonBackdrop = backdrop?.takeIf { isRuntimeShaderSupported() }
    // Nothing has scrolled under the bar yet, so there is nothing for the glass to show: the pills
    // stay bare icons until [floating] turns the material, the plate and the shadow on together.
    val keepButtonMaterial by remember(floating, materialProgress) {
        derivedStateOf { floating || materialProgress.value > 0f }
    }
    val baseMaterial = GlassTopAppBarDefaults.buttonMaterial()
    val material = if (keepButtonMaterial && buttonBackdrop != null) {
        baseMaterial.copy(blurRadius = GlassTopAppBarDefaults.nestedMaterialBlurRadius())
    } else {
        baseMaterial
    }
    val underlayMaterial = GlassTopAppBarDefaults.actionBarUnderlayMaterial()
        .takeIf { keepButtonMaterial && buttonBackdrop != null }

    Box(modifier = modifier) {
        // 穿透渐隐带：页面自身的颜色压回内容之上，顶栏上沿实、到下沿（含 28dp 越界）渐隐为 0。
        // 与 GlassTopAppBar 同一支 bandBrush / 同一段 mask 过渡，仅高度随小顶栏变矮。
        val overhangPx = with(LocalDensity.current) { bandOverhang.toPx() }
        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer {
                    this.alpha = maskProgress.value
                    transformOrigin = TransformOrigin(0.5f, 0f)
                    scaleY = if (size.height > 0f) (size.height + overhangPx) / size.height else 1f
                }
                .background(bandBrush),
        )
        CompositionLocalProvider(
            LocalGlassTopAppBarContext provides GlassTopAppBarContext(
                backdrop = buttonBackdrop,
                material = material,
                underlayMaterial = underlayMaterial,
                floating = floating,
                alpha = alpha,
                ramp = ramp,
                materialProgress = materialProgress,
                keepMaterial = keepButtonMaterial && buttonBackdrop != null,
                style = style,
                size = buttonSize,
                buttonPadding = buttonPadding,
                shape = buttonShape,
                fill = fill,
                stroke = stroke,
                shadow = buttonShadow,
            ),
        ) {
            // 魔改点①：GlassTopAppBar 在此处是 BlurTopAppBar（大标题可折叠）；换成 SmallTopAppBar
            // 后顶栏固定小高度、不折叠。scrollBehavior 必须继续透传——SmallTopAppBar 内部把状态
            // pin 住（heightOffsetLimit/heightOffset 恒 0），contentOffset 仍随滚动累积，
            // 驱动上方 band 与胶囊按钮的浮动过渡。color 传透明，背景交给渐隐带。
            SmallTopAppBar(
                title = title,
                modifier = contentModifier,
                subtitle = subtitle,
                color = Color.Transparent,
                scrollBehavior = scrollBehavior,
                defaultWindowInsetsPadding = defaultWindowInsetsPadding,
                navigationIconPadding = GlassTopAppBarDefaults.HorizontalPadding,
                actionIconPadding = GlassTopAppBarDefaults.HorizontalPadding,
                navigationIcon = navigationIconWithPadding,
                actions = actionsWithPadding,
                bottomContent = bottomContent,
            )
        }
    }
}
