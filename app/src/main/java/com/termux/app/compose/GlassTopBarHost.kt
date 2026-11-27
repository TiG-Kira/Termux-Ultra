package com.termux.app.compose

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.glass.GlassIconButton as MiuixGlassIconButton
import top.yukonga.miuix.kmp.glass.GlassTopAppBar as MiuixGlassTopAppBar
import top.yukonga.miuix.kmp.glass.GlassTopAppBarDefaults
import top.yukonga.miuix.kmp.material.EdgeStroke
import top.yukonga.miuix.kmp.material.GlassDefaults
import top.yukonga.miuix.kmp.material.GlassMaterial
import top.yukonga.miuix.kmp.material.GlassShadow
import top.yukonga.miuix.kmp.material.GlassShape
import top.yukonga.miuix.kmp.material.GlassStyle
import top.yukonga.miuix.kmp.material.GlassShadows
import top.yukonga.miuix.kmp.theme.MiuixTheme
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
        remember(surface) { GlassTopAppBarDefaults.bandBrush(surface) }
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
            navigationIcon = navigationIcon,
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
        remember(surface) { GlassTopAppBarDefaults.bandBrush(surface) }
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
            navigationIcon = navigationIcon,
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
                navigationIcon = navigationIcon,
                actions = actions,
                bottomContent = bottomContent,
            )
        }
    }
}

/**
 * miuix `GlassIconButton` 的应用侧包装：液态玻璃外观下，顶栏骨架内已滚到栏下
 * （[LocalGlassTopBarFloating]）的按钮分流 [LiquidIconButton]（vendored 同款分时机：
 * 展开态保持裸图标，收缩态换液态圆钮）。其余样式 / 非骨架场景转发官方。
 */
@Composable
fun GlassIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = GlassTopAppBarDefaults.ButtonSize,
    shape: GlassShape = remember(size) { GlassShape(size / 2) },
    content: @Composable () -> Unit,
) {
    if (LocalLiquidTopBarEnabled.current && LocalGlassTopBarFloating.current) {
        LiquidIconButton(onClick = onClick, modifier = modifier, content = content)
        return
    }
    MiuixGlassIconButton(
        onClick = onClick,
        modifier = modifier,
        size = size,
        shape = shape,
        content = content,
    )
}
