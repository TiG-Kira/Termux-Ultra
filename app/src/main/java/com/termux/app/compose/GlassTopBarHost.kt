package com.termux.app.compose

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.glass.GlassTopAppBar as MiuixGlassTopAppBar
import top.yukonga.miuix.kmp.glass.GlassTopAppBarDefaults
import top.yukonga.miuix.kmp.glass.glassShadow
import top.yukonga.miuix.kmp.material.EdgeStroke
import top.yukonga.miuix.kmp.material.GlassDefaults
import top.yukonga.miuix.kmp.material.GlassMaterial
import top.yukonga.miuix.kmp.material.GlassShadow
import top.yukonga.miuix.kmp.material.GlassShape
import top.yukonga.miuix.kmp.material.GlassStyle
import top.yukonga.miuix.kmp.material.GlassShadows
import top.yukonga.miuix.kmp.material.drawEdgeStroke
import top.yukonga.miuix.kmp.material.drawGlassMask
import top.yukonga.miuix.kmp.material.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.MiuixIndication
import com.termux.app.compose.liquid.LiquidIconButton
import com.termux.app.compose.liquid.LiquidTopBarSkin
import com.termux.app.compose.liquid.LocalLiquidBackdrop
import com.termux.app.compose.liquid.LocalLiquidTopBarEnabled

/**
 * 顶栏骨架的「内容已滚到栏下」状态，由 [GlassTopAppBar] 包装层统一下发。
 *
 * 官方 header context 是 internal 的，按钮拿不到折叠时机；液态圆钮分流
 * （[GlassIconButton] → LiquidIconButton）与皮肤淡入都依赖它。不在顶栏骨架内时
 * 恒为 false，弹层 / 面板里的玻璃按钮不受影响。
 */
val LocalGlassTopBarFloating = staticCompositionLocalOf { false }

/**
 * 玻璃按钮的水平外边距（vendored miuix `GlassTopAppBarDefaults.ButtonPadding` 同款 6dp）。
 *
 * 官方 0.9.5-rc01 删除了该 token 且按钮改为紧贴，PR #405 迁移后顶栏按钮间隔随之丢失；
 * 应用侧包装层按 vendored 口径还原：相邻按钮间隔 = 2 × [GlassTopBarButtonPadding] = 12dp，
 * 导航槽额外再包一层（见 [GlassTopAppBar] 的 navigationIcon 包装）。
 */
val GlassTopBarButtonPadding: Dp = 6.dp

/**
 * vendored 时代的 band 口径：顶部**实色**、过 bar 高度一半才往下淡出，1.0 处归零。
 * 官方 `TopAppBarBandBrush` 顶段 MAX_ALPHA 0.90 且顶部即开始 smoothstep 淡出，
 * 内容滚到顶栏上方时几乎透到底；恢复 vendored 渐变曲线统一观感。
 */
private fun topBarBandBrush(color: Color): Brush = Brush.verticalGradient(
    0.00f to color,
    0.45f to color,
    0.58f to color.copy(alpha = 0.82f),
    0.72f to color.copy(alpha = 0.58f),
    0.86f to color.copy(alpha = 0.34f),
    1.00f to color.copy(alpha = 0f),
)

/**
 * miuix `GlassTopAppBar` 的应用侧包装：官方签名原样转发，仅液态玻璃外观接管 band。
 *
 * 液态外观下（vendored 骨架同款）：官方 band 关掉（backgroundAlpha 置 0，几何不变），
 * [LiquidTopBarSkin] 垫在骨架之下随折叠淡入；同时下发 [LocalGlassTopBarFloating]
 * 供按钮槽内的 [GlassIconButton] 分流液态圆钮。其余样式一行不改，直接转发官方。
 */
@Composable
fun GlassTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    contentModifier: Modifier = Modifier,
    expandable: Boolean = true,
    progressiveBlur: Boolean = false,
    backdrop: Backdrop? = null,
    largeTitle: String = title,
    subtitle: String = "",
    scrollBehavior: ScrollBehavior? = null,
    bandBrush: Brush = MiuixTheme.colorScheme.surface.let { surface ->
        remember(surface) { topBarBandBrush(surface) }
    },
    bandOverhang: Dp = GlassTopAppBarDefaults.BandOverhang,
    titleBlurRadius: Dp = GlassTopAppBarDefaults.TitleBlurRadius,
    style: GlassStyle = GlassDefaults.Style,
    material: GlassMaterial = GlassTopAppBarDefaults.buttonMaterial(),
    alpha: Float = 1f,
    buttonSize: Dp = GlassTopAppBarDefaults.ButtonSize,
    buttonShape: GlassShape = remember(buttonSize) { GlassShape(buttonSize / 2) },
    fill: Color = GlassTopAppBarDefaults.buttonFill(),
    stroke: EdgeStroke? = GlassTopAppBarDefaults.buttonStroke(),
    buttonShadow: GlassShadow? = GlassShadows.Regular,
    defaultWindowInsetsPadding: Boolean = true,
    titleAlpha: () -> Float = { 1f },
    backgroundAlpha: () -> Float = { 1f },
    centerObstructionStart: Dp = Dp.Unspecified,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    bottomContent: @Composable () -> Unit = {},
) {
    if (!LocalLiquidTopBarEnabled.current) {
        MiuixGlassTopAppBar(
            title = title,
            modifier = modifier,
            contentModifier = contentModifier,
            expandable = expandable,
            progressiveBlur = progressiveBlur,
            backdrop = backdrop,
            largeTitle = largeTitle,
            subtitle = subtitle,
            scrollBehavior = scrollBehavior,
            bandBrush = bandBrush,
            bandOverhang = bandOverhang,
            titleBlurRadius = titleBlurRadius,
            style = style,
            material = material,
            alpha = alpha,
            buttonSize = buttonSize,
            buttonShape = buttonShape,
            fill = fill,
            stroke = stroke,
            buttonShadow = buttonShadow,
            defaultWindowInsetsPadding = defaultWindowInsetsPadding,
            titleAlpha = titleAlpha,
            backgroundAlpha = backgroundAlpha,
            centerObstructionStart = centerObstructionStart,
            // vendored 骨架同款：导航槽外包一层按钮边距（官方 0.9.5 删除了 slot wrap），
            // 还原「栏内边距 12dp + 槽位 6dp +（玻璃按钮自带边距）」的原始留白口径。
            navigationIcon = {
                Box(Modifier.padding(horizontal = GlassTopBarButtonPadding)) { navigationIcon() }
            },
            actions = actions,
            bottomContent = bottomContent,
        )
        return
    }
    // 与官方 scrollBehavior 重载同口径的折叠判定（含弹性过 scroll）。
    val contentScrolled by remember(scrollBehavior) {
        derivedStateOf {
            scrollBehavior?.state?.let { it.contentOffset < -1f || it.overscrollOffset < -1f } ?: true
        }
    }
    LiquidGlassTopBar(
        title = title,
        contentScrolled = contentScrolled,
        modifier = modifier,
        contentModifier = contentModifier,
        expandable = expandable,
        backdrop = backdrop,
        largeTitle = largeTitle,
        subtitle = subtitle,
        scrollBehavior = scrollBehavior,
        bandOverhang = bandOverhang,
        titleBlurRadius = titleBlurRadius,
        style = style,
        material = material,
        alpha = alpha,
        buttonSize = buttonSize,
        buttonShape = buttonShape,
        fill = fill,
        stroke = stroke,
        buttonShadow = buttonShadow,
        defaultWindowInsetsPadding = defaultWindowInsetsPadding,
        titleAlpha = titleAlpha,
        centerObstructionStart = centerObstructionStart,
        navigationIcon = navigationIcon,
        actions = actions,
        bottomContent = bottomContent,
    )
}

/**
 * 显式折叠态重载，转发口径同上。
 */
@Composable
fun GlassTopAppBar(
    title: String,
    isContentScrolled: Boolean,
    modifier: Modifier = Modifier,
    contentModifier: Modifier = Modifier,
    expandable: Boolean = true,
    progressiveBlur: Boolean = false,
    backdrop: Backdrop? = null,
    largeTitle: String = title,
    subtitle: String = "",
    scrollBehavior: ScrollBehavior? = null,
    bandBrush: Brush = MiuixTheme.colorScheme.surface.let { surface ->
        remember(surface) { topBarBandBrush(surface) }
    },
    bandOverhang: Dp = GlassTopAppBarDefaults.BandOverhang,
    titleBlurRadius: Dp = GlassTopAppBarDefaults.TitleBlurRadius,
    style: GlassStyle = GlassDefaults.Style,
    material: GlassMaterial = GlassTopAppBarDefaults.buttonMaterial(),
    alpha: Float = 1f,
    buttonSize: Dp = GlassTopAppBarDefaults.ButtonSize,
    buttonShape: GlassShape = remember(buttonSize) { GlassShape(buttonSize / 2) },
    fill: Color = GlassTopAppBarDefaults.buttonFill(),
    stroke: EdgeStroke? = GlassTopAppBarDefaults.buttonStroke(),
    buttonShadow: GlassShadow? = GlassShadows.Regular,
    defaultWindowInsetsPadding: Boolean = true,
    titleAlpha: () -> Float = { 1f },
    backgroundAlpha: () -> Float = { 1f },
    centerObstructionStart: Dp = Dp.Unspecified,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    bottomContent: @Composable () -> Unit = {},
) {
    if (!LocalLiquidTopBarEnabled.current) {
        MiuixGlassTopAppBar(
            title = title,
            isContentScrolled = isContentScrolled,
            modifier = modifier,
            contentModifier = contentModifier,
            expandable = expandable,
            progressiveBlur = progressiveBlur,
            backdrop = backdrop,
            largeTitle = largeTitle,
            subtitle = subtitle,
            scrollBehavior = scrollBehavior,
            bandBrush = bandBrush,
            bandOverhang = bandOverhang,
            titleBlurRadius = titleBlurRadius,
            style = style,
            material = material,
            alpha = alpha,
            buttonSize = buttonSize,
            buttonShape = buttonShape,
            fill = fill,
            stroke = stroke,
            buttonShadow = buttonShadow,
            defaultWindowInsetsPadding = defaultWindowInsetsPadding,
            titleAlpha = titleAlpha,
            backgroundAlpha = backgroundAlpha,
            centerObstructionStart = centerObstructionStart,
            // vendored 骨架同款：导航槽外包一层按钮边距（官方 0.9.5 删除了 slot wrap），
            // 还原「栏内边距 12dp + 槽位 6dp +（玻璃按钮自带边距）」的原始留白口径。
            navigationIcon = {
                Box(Modifier.padding(horizontal = GlassTopBarButtonPadding)) { navigationIcon() }
            },
            actions = actions,
            bottomContent = bottomContent,
        )
        return
    }
    // 官方第二重载同款合并：显式折叠态 ∪ 弹性过 scroll。
    val contentScrolled by remember(isContentScrolled, scrollBehavior) {
        derivedStateOf {
            isContentScrolled || (scrollBehavior?.state?.overscrollOffset ?: 0f) < -1f
        }
    }
    LiquidGlassTopBar(
        title = title,
        contentScrolled = contentScrolled,
        modifier = modifier,
        contentModifier = contentModifier,
        expandable = expandable,
        backdrop = backdrop,
        largeTitle = largeTitle,
        subtitle = subtitle,
        scrollBehavior = scrollBehavior,
        bandOverhang = bandOverhang,
        titleBlurRadius = titleBlurRadius,
        style = style,
        material = material,
        alpha = alpha,
        buttonSize = buttonSize,
        buttonShape = buttonShape,
        fill = fill,
        stroke = stroke,
        buttonShadow = buttonShadow,
        defaultWindowInsetsPadding = defaultWindowInsetsPadding,
        titleAlpha = titleAlpha,
        centerObstructionStart = centerObstructionStart,
        navigationIcon = navigationIcon,
        actions = actions,
        bottomContent = bottomContent,
    )
}

/** 液态外观的顶栏骨架：LiquidTopBarSkin 垫底 + 官方骨架（band 关闭）。 */
@Composable
private fun LiquidGlassTopBar(
    title: String,
    contentScrolled: Boolean,
    modifier: Modifier,
    contentModifier: Modifier,
    expandable: Boolean,
    backdrop: Backdrop?,
    largeTitle: String,
    subtitle: String,
    scrollBehavior: ScrollBehavior?,
    bandOverhang: Dp,
    titleBlurRadius: Dp,
    style: GlassStyle,
    material: GlassMaterial,
    alpha: Float,
    buttonSize: Dp,
    buttonShape: GlassShape,
    fill: Color,
    stroke: EdgeStroke?,
    buttonShadow: GlassShadow?,
    defaultWindowInsetsPadding: Boolean,
    titleAlpha: () -> Float,
    centerObstructionStart: Dp,
    navigationIcon: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit,
    bottomContent: @Composable () -> Unit,
) {
    // 皮肤淡入跟随折叠时机（vendored GlassMotion.topBarMask 同规格：100ms 线性）。
    val maskState = animateFloatAsState(
        targetValue = if (contentScrolled) 1f else 0f,
        animationSpec = tween(durationMillis = 100, easing = LinearEasing),
        label = "liquidTopBarMask",
    )
    // State 对象身份恒定，lambda 不随进度逐帧重建（取景重录旧坑）。
    val collapseProgress = remember { { maskState.value } }
    Box {
        LiquidTopBarSkin(
            backdrop = LocalLiquidBackdrop.current,
            scrollBehavior = null,
            collapseProgress = collapseProgress,
            modifier = Modifier.matchParentSize(),
        )
        CompositionLocalProvider(LocalGlassTopBarFloating provides contentScrolled) {
            MiuixGlassTopAppBar(
                title = title,
                isContentScrolled = contentScrolled,
                modifier = modifier,
                contentModifier = contentModifier,
                expandable = expandable,
                progressiveBlur = false,
                backdrop = backdrop,
                largeTitle = largeTitle,
                subtitle = subtitle,
                scrollBehavior = scrollBehavior,
                bandOverhang = bandOverhang,
                titleBlurRadius = titleBlurRadius,
                style = style,
                material = material,
                alpha = alpha,
                buttonSize = buttonSize,
                buttonShape = buttonShape,
                fill = fill,
                stroke = stroke,
                buttonShadow = buttonShadow,
                defaultWindowInsetsPadding = defaultWindowInsetsPadding,
                titleAlpha = titleAlpha,
                // band 交给 LiquidTopBarSkin（vendored 骨架同款：官方背板整体关掉）。
                backgroundAlpha = { 0f },
                centerObstructionStart = centerObstructionStart,
                // vendored 骨架同款：导航槽外包一层按钮边距（见上方 GlassTopAppBar 重载）。
                navigationIcon = {
                    Box(Modifier.padding(horizontal = GlassTopBarButtonPadding)) { navigationIcon() }
                },
                actions = actions,
                bottomContent = bottomContent,
            )
        }
    }
}

/**
 * miuix `GlassIconButton` 的应用侧包装：液态玻璃外观下，顶栏骨架内已滚到栏下
 * （[LocalGlassTopBarFloating]）的按钮分流 [LiquidIconButton]（vendored 同款分时机：
 * 展开态保持裸图标，收缩态换液态圆钮）。其余场景走自绘柔光 pill
 * （[SoftGlassPillButton]）。
 *
 * 为什么不再转发官方：官方 0.9.5 `GlassIconButton` 的底板由 internal header context
 * 的 floating 进度门控（`graphicsLayer { alpha = progress }`），且按压叠层内建在
 * `GlassButtonSurface` 里但官方从不传入 interactionSource——顶栏展开态（未滚动）
 * 底板 alpha=0 恒裸、按压无反馈；官方公开 API（`inheritTopBarMaterial` 等）无法
 * 强制浮起。按「按钮默认使用柔光玻璃+ 控件」的口径自绘：常显 pill + 常显按压反馈。
 *
 * [padding] 还原 vendored 的按钮水平外边距（官方 0.9.5 删除了 `ButtonPadding` 与
 * 对应参数，相邻玻璃 pill 会紧贴成一整条）。默认 6dp = vendored `ButtonPadding`；
 * 终端返回键等显式贴边场景传 0。液态分支不生效——[LiquidIconButton] 自带同款
 * 6dp 外边距（`LiquidActionButtonPadding`），与 vendored 分时机行为一致。
 */
@Composable
fun GlassIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = GlassTopAppBarDefaults.ButtonSize,
    shape: GlassShape = remember(size) { GlassShape(size / 2) },
    padding: Dp = GlassTopBarButtonPadding,
    content: @Composable () -> Unit,
) {
    if (LocalLiquidTopBarEnabled.current && LocalGlassTopBarFloating.current) {
        LiquidIconButton(onClick = onClick, modifier = modifier, content = content)
        return
    }
    SoftGlassPillButton(
        onClick = onClick,
        modifier = modifier.padding(horizontal = padding),
        size = size,
        shape = shape,
        content = content,
    )
}

/**
 * 柔光玻璃+ 按钮控件：常显柔光 pill 底板 + 按压反馈。
 *
 * 结构对齐官方 `GlassButtonSurface` 的 null-backdrop 分支（fill 底板 + 边缘描边 +
 * 玻璃高光遮罩 + 阴影），但去掉 floating 进度门控（底板 alpha 恒 1），按压叠层
 * 用官方同款 token（`buttonPressedOverlay` + `MiuixIndication`）。点击区域含 6dp
 * 水平边距（vendored `combinedClickable` 同口径，挂在 padding 之前的整个方块上）。
 *
 * 弹层锚定注意：官方 anchor surface 由 `GlassButtonSurface` 发布，自绘后 anchor
 * 拿不到按钮材质 → `GlassTransformPopup` 回落 null-anchor 路径（实色兜底 +
 * iconMaterial 淡入），与 vendored 的「强制浮起」观感一致。
 */
@Composable
private fun SoftGlassPillButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp,
    shape: GlassShape,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val fill = GlassTopAppBarDefaults.buttonFill()
    val stroke = GlassTopAppBarDefaults.buttonStroke()
    val pressedOverlay = GlassTopAppBarDefaults.buttonPressedOverlay()
    val indication = remember(pressedOverlay) {
        MiuixIndication(
            color = pressedOverlay.copy(alpha = 1f),
            pressAlpha = pressedOverlay.alpha,
            hoverAlpha = 0f,
        )
    }
    Box(
        modifier = modifier
            .size(size)
            .glassShadow(shape, GlassShadows.Regular)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        // 底板：fill + 边缘描边 + 玻璃高光遮罩（官方 null-backdrop 分支同款，常显）。
        Box(
            modifier = Modifier
                .matchParentSize()
                .then(
                    if (isRuntimeShaderSupported()) {
                        Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    } else {
                        Modifier.clip(shape)
                    },
                )
                .background(fill)
                .drawWithContent {
                    drawContent()
                    drawEdgeStroke(shape, stroke, 1f)
                    drawGlassMask(shape)
                },
        )
        // 按压叠层：clip 到 pill 轮廓，不受任何进度门控（官方漏传 interactionSource 的补齐）。
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(shape)
                .indication(interactionSource, indication),
        )
        content()
    }
}
