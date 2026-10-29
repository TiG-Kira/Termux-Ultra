package com.termux.app.compose

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateTopPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.glass.GlassDefaults
import top.yukonga.miuix.kmp.glass.GlassShape
import top.yukonga.miuix.kmp.glass.GlassStrokes
import top.yukonga.miuix.kmp.glass.GlassStroke
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import top.yukonga.miuix.kmp.glass.GlassTopAppBarDefaults
import top.yukonga.miuix.kmp.glass.LocalGlassTopAppBarContext
import top.yukonga.miuix.kmp.glass.glassPanel
import top.yukonga.miuix.kmp.glass.internal.drawGlassMask
import top.yukonga.miuix.kmp.glass.internal.drawGlassRim
import top.yukonga.miuix.kmp.glass.internal.drawGlassStroke
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 玻璃栏的亮暗取色口径。
 *
 * - [Light] / [Dark]：强制亮色 / 暗色玻璃（按钮底板、描边、默认文字色整套切换）。
 * - [System]：跟随系统亮暗主题（等价 GlassTopAppBarDefaults 的 isDarkTheme()）。
 */
enum class GlassTerminalBarColorStyle {
    Light,
    Dark,
    System,
}

/** [GlassTerminalBar] 的默认 token。布局规格全部动态委托给原版 GlassTopAppBar，这里只留终端专属项。 */
object GlassTerminalBarDefaults {

    /**
     * 渐隐背景板越过栏下沿的延伸距离。
     *
     * 原值 28.dp 与 GlassTopAppBarDefaults.BandOverhang 一致，但终端内容首行紧贴栏底，
     * 28dp 的渐隐尾段正好整条压在首行上，把它洗得看不清（Issue #327）。收窄到 8dp 后
     * 尾段只轻扫首行顶部一点点，渐隐观感保留、文字恢复可读。
     */
    val BandOverhang: Dp = 8.dp

    /**
     * 渐隐带在 [BandOverhang] 之外再向下压的距离。
     *
     * 栏底补白（[BottomGap]）与内容让位（[ContentTopInset]）拉开后，副标题与终端首行之间
     * 已有整行空隙，渐隐段却停在空隙上沿、显得悬在半空。这里只把渐隐带往下多铺 4dp，
     * 吃掉一部分空隙——栏高与内容位置都不动；尾段末端 alpha 已归零（见 [bandBrush]），
     * 首行起点正好落在全透明处，不会被洗白。上限是 [ContentTopInset] 里预留的净空。
     */
    val BandDrop: Dp = 4.dp

    /**
     * 栏身内部的底部留白：副标题之下、栏下沿之上额外让出的空隙。
     *
     * 渐隐带的色标是比例（0.86 起坡），栏身加长后实色段随之向下延伸约 0.86 × 留白，
     * 副标题与终端首行之间才留得出空隙。它只负责把渐隐带压低，首行不再被尾段压住
     * 靠的是调用方应用的 [ContentTopInset]。
     */
    val BottomGap: Dp = 8.dp

    /**
     * 终端内容顶部额外避让的距离。调用方需自行 `padding(top = ContentTopInset)`。
     *
     * 渐隐尾段会越过栏下沿 [BandOverhang] 压在首行上，内容必须再往下让出相同的距离，
     * 首行才能完全脱离尾段、不再被洗白。多出的 4dp 正好被 [BandDrop] 用满：尾段末端落在
     * 首行行框顶端、alpha 已归零，字形本身还留有余量；再往下压就会碰到文字。这段让位
     * 不能再压缩，Scaffold 的内容 padding 比顶栏测量晚一帧到位。
     */
    val ContentTopInset: Dp = BandOverhang + 4.dp

    /** 收缩/展开过渡时长（对齐 AppMotion.NORMAL）。 */
    val CollapseDuration: Int = 220

    /** 动作区玻璃面板的高度（与栏内按钮 pill 同高）。 */
    val PanelHeight: Dp = 44.dp

    /** 面板内单个按钮的方形命中区边长。 */
    val PanelButtonSize: Dp = 40.dp

    /** 面板左右内边距。 */
    val PanelInnerPadding: Dp = 4.dp

    /** 面板圆角：44dp 高配 22dp 圆角即标准胶囊形。 */
    val PanelShape: GlassShape = GlassShape(22.dp)

    /** 面板内按钮按压时的内容淡出档位。 */
    val PressedContentAlpha: Float = 0.7f

    /** 颜色类参数（背景板、文字、副标题）切换时的过渡时长。 */
    val ColorDuration: Int = 220

    /**
     * 解析 [GlassTerminalBarColorStyle] 到实际亮暗。
     * System 口径与 GlassTopAppBarDefaults.isDarkTheme() 相同：取 MiuixTheme 背景亮度。
     */
    @Composable
    fun isDark(colorStyle: GlassTerminalBarColorStyle): Boolean = when (colorStyle) {
        GlassTerminalBarColorStyle.Light -> false
        GlassTerminalBarColorStyle.Dark -> true
        GlassTerminalBarColorStyle.System -> MiuixTheme.colorScheme.background.luminance() < 0.5f
    }

    /** 运行时着色器不可用时的传统 pill 填充（同 GlassTopAppBarDefaults.buttonFill，但按终端口径传亮暗）。 */
    fun buttonFill(isDark: Boolean): Color = if (isDark) Color(0xFF2C2C2C) else Color.White

    /** 配套的小号 bloom 描边（同 GlassTopAppBarDefaults.buttonStroke 的 small 档）。 */
    fun buttonStroke(isDark: Boolean): GlassStroke = GlassStrokes.forTheme(
        isDark = isDark,
        light = GlassStrokes.SmallLight,
        dark = GlassStrokes.SmallDark,
    )

    /**
     * 由终端背景色派生的栏底色：浅底压暗、深底提亮一点。
     *
     * 收缩态原本直接铺终端背景色，栏与内容完全同色，栏杆只靠玻璃 chrome 硬撑边界。这里
     * 按原底色的明暗叠一层极淡的黑/白（浅底 7%、深底 10%），栏与内容之间才有层次感，幅度
     * 刻意压得很小，不取系统 opaque 色那种强反差；[steps] 用于在同方向上再加一档（如功能
     * 键栏的按键比容器更深/更浅）。
     *
     * 用 sRGB 叠加而不是 [lerp]：Compose 的 lerp 走 Oklab，近黑端几乎提不动（#000000 混 10%
     * 白只到 #030303），纯黑主题下仍然看不出层次。方向只由原底色的明暗决定，加档不会反转。
     */
    fun bandTint(background: Color, steps: Int = 1): Color {
        val dark = background.luminance() < 0.5f
        val overlay = (if (dark) Color.White else Color.Black)
            .copy(alpha = (if (dark) 0.10f else 0.07f) * steps)
        return overlay.compositeOver(background)
    }

    /**
     * 渐隐背景板的笔刷：终端控制台专属曲线。
     *
     * 与 GlassTopAppBarDefaults.bandBrush（页面色）不同——终端内容不可预测（可能是高亮彩色
     * 输出），文字必须全程可读，因此整条栏保持实色，仅靠越过栏下沿的 [BandOverhang]
     * 完成「实 → 渐隐 → 0」的过渡，把渐隐效果完整保留在栏外。
     *
     * Issue #327：渐隐段整体前移并压陡（0.86 起坡；色标吃的是比例，展开态栏高下栏下沿
     * 处 alpha 已降到 ~0.25 以下，收缩态栏矮、栏下沿仍偏实），配合越过栏下沿的尾段
     * （[BandOverhang] + [BandDrop]），让压在终端首行上的那部分尽量薄——只轻扫一点点。
     *
     * @param color 背景板颜色（终端背景色 / 系统 opaque 色 / Monet surface，随时可改）。
     */
    fun bandBrush(color: Color): Brush = Brush.verticalGradient(
        0.00f to color,
        0.86f to color,
        0.93f to color.copy(alpha = 0.25f),
        1.00f to color.copy(alpha = 0f),
    )
}

/**
 * 终端控制台专属的玻璃 TopBar——「GlassTerminalBar」。
 *
 * **动态委托**：标题与副标题的位置、大小、排布，玻璃按钮的底盘/材质/阴影机制，展开↔收缩的
 * 高度过渡，全部委托给原版 [GlassTopAppBar]（其内部 [top.yukonga.miuix.kmp.basic.BlurTopAppBar]
 * 的布局规格即「原版」口径），本栏只包三层终端专属外挂：
 *
 * 1. **常驻渐隐背景板**：原版 band 的显隐跟随滚动向的 mask 动画（展开态不显示），终端要求
 *    两种状态下都压在内容之上、且 [bandColor] 随时可改——所以自己画一层常驻板（颜色走
 *    动画 + 笔刷重建，渐隐曲线不变），原版那层用全透明笔刷屏蔽。
 * 2. **收缩命令驱动**：终端没有滚动内容，自建 [top.yukonga.miuix.kmp.basic.TopAppBarState]，
 *    由 [collapsed] 命令动画其 `heightOffset` 驱动大标题折叠；同一个命令经 `isContentScrolled`
 *    驱动按钮材质浮起——展开态按钮是裸图标（无底盘/玻璃/阴影，同原版页顶行为），收缩态
 *    pill 完整浮起。栏自身没有任何自动触发源，命令之间持续保持当前状态。
 * 3. **栏底留白**：经原版 `bottomContent` 槽在栏下沿补 [GlassTerminalBarDefaults.BottomGap]，
 *    标题（含副标题）与终端首行之间因此有空隙；调用方还需自行应用
 *    [GlassTerminalBarDefaults.ContentTopInset] 让首行避开渐隐尾段。渐隐带本身再经
 *    [GlassTerminalBarDefaults.BandDrop] 往下多铺一点，填住空隙上沿、不悬在半空
 *    （只动绘制，栏高与内容位置都不变）。
 *
 * 颜色三轨，均可随时切换：
 * - [colorStyle]：亮色 / 暗色 / 跟随系统，驱动收缩态默认文字色与玻璃 chrome（按钮底板、描边）；
 * - [bandColor]：渐隐背景板颜色，随时更改且渐隐效果不变（内部走颜色动画 + 笔刷重建）；
 * - [subtitleColor] / [contentColor]：副标题与标题文字色，副标题传空串即取消显示。
 *
 * @param collapsed 收缩/展开命令。false = 展开（大标题 + 副标题 + 动作区），true = 收缩
 *   （原版主行规格）。这是栏状态的唯一来源，不会被栏自身改变。
 * @param title 收缩态紧凑标题（原版居中口径）。传空串时收缩态标题淡出；展开态不显示小标题。
 * @param largeTitle 展开态大标题（原版大标题口径）。默认取 [title]；调用方应保证展开态非空。
 * @param bandColor 渐隐背景板颜色。随时可改，渐隐曲线由 [GlassTerminalBarDefaults.bandBrush] 保证。
 * @param modifier 应用到栏根节点的 modifier。
 * @param backdrop 栏身后供玻璃按钮采样的层。`null` 时按钮退回传统实色 pill。
 * @param subtitle 副标题文案。空串 = 不显示；随时可改。
 * @param subtitleColor 副标题颜色，任意色，随时可改。`Color.Unspecified` 时按次要文字色推导。
 * @param contentColor 标题文字主色（收缩态小标题 + 展开态大标题共用）。`Color.Unspecified`
 *   时按 [colorStyle] / 系统 Monet 口径推导。
 * @param colorStyle 亮/暗/跟随系统的取色口径，同时决定玻璃 chrome 与收缩态默认文字色。
 * @param navigationIcon 栏首（返回）控件。包一层 [top.yukonga.miuix.kmp.glass.GlassIconButton]
 *   即自动获得本栏的玻璃表面与按压反馈。
 * @param actions 栏尾动作区。整组应放进一个 [GlassTerminalBarActionsPanel]（一块玻璃面板里
 *   排若干个裸图标按钮，各自不再带底板）。
 * @param trailingIcon 最尾部的把手控件（终端控制台的「极简模式」开关），同样建议用 GlassIconButton。
 * @param onBlankTap 栏空白处（返回键与动作区之间的空档，以及标题之外的区域）被点击时的回调。
 *   栏本身吞掉所有点击，避免穿透到下面的内容；子控件先命中，所以按钮手势不受影响。
 *   传 null 则只吞不响应（原版行为）。
 */
@Composable
fun GlassTerminalBar(
    collapsed: Boolean,
    title: String,
    bandColor: Color,
    modifier: Modifier = Modifier,
    /** 极窄栏模式：控制台由 TopBar 收缩时进一步塌缩为仅居中显示终端标题的细横条。 */
    narrow: Boolean = false,
    backdrop: Backdrop? = null,
    largeTitle: String = title,
    subtitle: String = "",
    subtitleColor: Color = Color.Unspecified,
    contentColor: Color = Color.Unspecified,
    colorStyle: GlassTerminalBarColorStyle = GlassTerminalBarColorStyle.System,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    trailingIcon: @Composable () -> Unit = {},
    onBlankTap: (() -> Unit)? = null,
) {
    // 极窄栏：替代整条玻璃栏，仅居中显示终端标题的小横条，点击恢复 TopBar。
    // 它只是 collapsed 态的一种更简渲染，栏状态的唯一来源仍是 collapsed 命令，
    // 其余收缩/展开/计时/死会话强展逻辑完全不变。narrow=true 时直接走极简布局，
    // 跳过原版 GlassTopAppBar（含大标题折叠动画）与导航/动作/把手槽。
    if (narrow) {
        GlassTerminalBarNarrow(
            title = title,
            bandColor = bandColor,
            colorStyle = colorStyle,
            contentColor = contentColor,
            modifier = modifier,
            onClick = onBlankTap,
        )
        return
    }
    val isDark = GlassTerminalBarDefaults.isDark(colorStyle)
    // 展开态默认走系统 / Monet（原版口径）；收缩态默认追 colorStyle 解析的黑白。
    // 调用方显式传 contentColor 时（终端双轨：展开 opaque / 收缩终端色）优先。
    val defaultContent = if (collapsed) {
        if (isDark) Color.White else Color.Black
    } else {
        MiuixTheme.colorScheme.onSurface
    }
    val defaultContentSecondary = if (collapsed) {
        defaultContent.copy(alpha = 0.7f)
    } else {
        MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    val resolvedContentColor = if (contentColor == Color.Unspecified) defaultContent else contentColor
    val resolvedContentSecondary =
        if (contentColor == Color.Unspecified) defaultContentSecondary else contentColor.copy(alpha = 0.7f)
    val resolvedSubtitleColor = if (subtitleColor == Color.Unspecified) resolvedContentSecondary else subtitleColor

    // ===== 渐隐背景板：常驻 + 颜色动画，渐隐曲线由笔刷保证不变 =====
    val animatedBandColor by animateColorAsState(
        targetValue = bandColor,
        animationSpec = tween(
            durationMillis = GlassTerminalBarDefaults.ColorDuration,
            easing = FastOutSlowInEasing,
        ),
        label = "glassTerminalBarBandColor",
    )
    val bandBrush = remember(animatedBandColor) { GlassTerminalBarDefaults.bandBrush(animatedBandColor) }
    // 屏蔽原版 GlassTopAppBar 自带的滚动向 band（显隐逻辑不满足终端「常驻」诉求）
    val transparentBrush = remember { Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent)) }

    // ===== 收缩命令 → 大标题折叠：终端没有滚动内容，自建 state 驱动 heightOffset =====
    // BlurTopAppBar 首帧 layout 时经 onSizeChanged 把 heightOffsetLimit 回写为 -largeTitleBox
    // 高度；等一帧确保它就位后再读，避免拿到 0 导致收缩命令空转。largeTitle 也在 key 里——
    // 文字高度变化（如会话名变长）时若处于收缩态，补一次到新 limit 的动画。
    val topBarState = rememberTopAppBarState()
    val scrollBehavior = MiuixScrollBehavior(topBarState, canScroll = { false })
    LaunchedEffect(collapsed, largeTitle) {
        withFrameNanos { }
        val target = if (collapsed) topBarState.heightOffsetLimit else 0f
        if (target != topBarState.heightOffset) {
            animate(
                initialValue = topBarState.heightOffset,
                targetValue = target,
                animationSpec = tween(
                    durationMillis = GlassTerminalBarDefaults.CollapseDuration,
                    easing = FastOutSlowInEasing,
                ),
            ) { value, _ ->
                topBarState.heightOffset = value
            }
        }
    }

    Box(modifier = modifier) {
        // 自绘常驻渐隐背景板：颜色随命令平滑切换。笔刷 0→1 映射到「栏高 + 越界 + 下压」，
        // transformOrigin 钉在顶沿，scaleY 把渐隐段拉到栏外（与原版 band 同机制）。
        val bandTailPx = with(LocalDensity.current) {
            (GlassTerminalBarDefaults.BandOverhang + GlassTerminalBarDefaults.BandDrop).toPx()
        }
        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer {
                    this.alpha = 1f
                    transformOrigin = TransformOrigin(0.5f, 0f)
                    scaleY = if (size.height > 0f) (size.height + bandTailPx) / size.height else 1f
                }
                .background(bandBrush),
        )
        // 动态委托原版 GlassTopAppBar：
        // - isContentScrolled = collapsed：收缩命令驱动按钮材质浮起（展开态裸图标、收缩态 pill）
        // - scrollBehavior：上面的 heightOffset 动画驱动大标题折叠
        // - 标题位置/大小、主副标题排布、按钮规格全部是原版口径
        GlassTopAppBar(
            title = title,
            isContentScrolled = collapsed,
            largeTitle = largeTitle,
            subtitle = subtitle,
            titleColor = resolvedContentColor,
            largeTitleColor = resolvedContentColor,
            subtitleColor = resolvedSubtitleColor,
            backdrop = backdrop,
            bandBrush = transparentBrush,
            // 终端栏排除柔光：bandBlurRadius 不走全局默认（柔光样式 20dp），与关于页同款
            // 显式置 0。终端栏有自己的 bandTint 渐隐底板设计，且终端内容高频重绘，
            // 叠一层实时模糊带区既破坏取色口径又白耗渲染线程。
            bandBlurRadius = 0.dp,
            scrollBehavior = scrollBehavior,
            fill = GlassTerminalBarDefaults.buttonFill(isDark),
            stroke = GlassTerminalBarDefaults.buttonStroke(isDark),
            navigationIcon = navigationIcon,
            actions = {
                actions()
                trailingIcon()
            },
            bottomContent = {
                // 栏底补白：栏身因此变长，渐隐带（按栏高绘制）的实色段随之向下延伸，
                // 副标题与终端首行之间留出空隙。
                Spacer(Modifier.height(GlassTerminalBarDefaults.BottomGap))
            },
            onBlankTap = onBlankTap,
        )
    }
}

/**
 * 极窄栏：由 [GlassTerminalBar] 在 [narrow]=true 时渲染，替代整条控制台玻璃栏。
 *
 * 视觉：顶栏整条塌缩为一条贴着系统状态栏下方的极细横条——系统状态栏照常绘制在最上方，
 * 横条内只居中显示一行小号终端标题，导航键 / 动作区 / 极简把手 / 副标题一概不显示；
 * 点击整条 = 展开命令（即调用方传入的 [onClick]，通常是 showTopBarTemporarily），直接变回完整
 * TopBar。其余收缩 / 展开 / 计时 / 死会话强展逻辑完全不变，本栏只是 collapsed 态的一种更简渲染。
 *
 * 取色沿用调用方口径：[colorStyle] 解析亮暗决定默认文字色，显式 [contentColor] 优先；横条底色
 * 铺 [bandColor]（收缩态即终端背景的微调色），与下方终端内容连成一片。
 *
 * @param title 居中显示的终端标题（小号）。
 * @param bandColor 横条底色。
 * @param colorStyle 亮/暗/跟随系统的取色口径，决定默认文字色。
 * @param contentColor 标题文字色；`Color.Unspecified` 时按 [colorStyle] 推导。
 * @param modifier 应用到横条根节点的 modifier（已含横向 cutout 避让）。
 * @param onClick 点击整条横条的回调；传 null 则不响应（仅展示）。
 */
@Composable
private fun GlassTerminalBarNarrow(
    title: String,
    bandColor: Color,
    colorStyle: GlassTerminalBarColorStyle,
    contentColor: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val isDark = GlassTerminalBarDefaults.isDark(colorStyle)
    val resolvedContent = if (contentColor == Color.Unspecified) {
        if (isDark) Color.White else Color.Black
    } else {
        contentColor
    }

    // 横条整体高度 = 系统状态栏高度 + 一小条文字高度；状态栏区域透出系统状态栏，
    // 文字条落在状态栏正下方，与示例（浏览器样式）一致：状态栏在上、细文字条在下。
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val stripHeight = 30.dp
    val totalHeight = statusBarTop + stripHeight

    // 横条底色：顶部（状态栏区域）实色，底部边缘渐隐进终端内容，与收缩态 band 同款观感，
    // 避免横条与下方终端之间出现硬边。bandColor 已是终端背景的微调色，差异本就很小。
    val narrowBandBrush = remember(bandColor) {
        Brush.verticalGradient(
            0.0f to bandColor,
            0.7f to bandColor,
            1.0f to bandColor.copy(alpha = 0f),
        )
    }

    Box(
        modifier = modifier
            .height(totalHeight)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    ) {
        // 整条（含状态栏区域）铺 band 色，系统状态栏图标绘制其上，与横条连成一片。
        Box(Modifier.matchParentSize().background(narrowBandBrush))
        // 仅状态栏下方的细条内居中显示终端标题（小号、单行、超长省略）。
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(stripHeight),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = title,
                fontSize = 13.sp,
                color = resolvedContent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
    }
}

/**
 * 动作区的玻璃面板组：一整块玻璃面板，里面排若干裸图标按钮——各按钮不再自带底板、
 * 描边与阴影，统一由这块面板承载（视觉上回到旧版 TopBarActionCard 的成组观感，材质换成玻璃）。
 *
 * 面板的玻璃表面（backdrop / 材质 / 描边 / 阴影 / 圆角）全部取自所在栏的
 * [LocalGlassTopAppBarContext]，因此放进 [GlassTerminalBar] 的 actions 槽即可自动对齐栏的
 * 取色口径（亮/暗/跟随系统）与收缩态浮起动画。
 *
 * @param modifier 应用到面板根节点的 modifier。
 * @param content 面板内的按钮行。用 [GlassTerminalBarPanelButton] 排布单个按钮（含长按）。
 */
@Composable
fun GlassTerminalBarActionsPanel(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val barContext = LocalGlassTopAppBarContext.current
    val style = barContext?.style ?: GlassDefaults.Style
    val backdrop = barContext?.backdrop
    val material = barContext?.material ?: GlassTopAppBarDefaults.buttonMaterial()
    val fill = barContext?.fill ?: GlassTerminalBarDefaults.buttonFill(false)
    val stroke = barContext?.stroke
    val shadow = barContext?.shadow
    val shape = GlassTerminalBarDefaults.PanelShape
    // 面板恒显：动作区是一整组容器，不能跟单个按钮那样在展开态（未浮起）退回裸图标——
    // 栏的浮起进度 / 阴影 ramp 在展开态都是 0，拿它们当不透明度会让整块面板消失。
    // 这里只跟随栏下发的 alpha 乘数（如调用方整体调暗），其余一概不接管。
    val panelAlpha = (barContext?.alpha ?: 1f).coerceIn(0f, 1f)
    // 分层与 GlassButtonSurface 完全同构：玻璃（阴影 + 采样 backdrop 的实时模糊材质）
    // 在下，fill 底板 + 描边 + rim + mask 在上，图标行最上层——材质观感与按钮 pill 一致，
    // 只是形状从正圆拉成胶囊。
    Box(
        modifier = modifier
            .height(GlassTerminalBarDefaults.PanelHeight)
            .graphicsLayer { alpha = panelAlpha }
            .glassPanel(
                backdrop = backdrop,
                shape = shape,
                style = style,
                alpha = 1f,
                material = material,
                stroke = null,
                shadow = shadow,
                shading = false,
            ),
    ) {
        // 底板层：与按钮 pill 的 plate 同款（fill 实色 + stroke + rim + mask），
        // matchParentSize 覆层不参与测量——Box 的尺寸由下面的按钮 Row 决定。
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
                    stroke?.let { drawGlassStroke(shape, layoutDirection, it, 1f) }
                    drawGlassRim(shape, layoutDirection, style, fill, 1f)
                    drawGlassMask(shape, layoutDirection)
                },
        )
        Row(
            // Box 默认 TopStart，Row 只包内容（按钮 40dp < 面板 44dp）会贴顶——必须显式垂直居中。
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = GlassTerminalBarDefaults.PanelInnerPadding),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/**
 * 玻璃面板组内的单个按钮：裸图标（无底板、无阴影），只保留点击/长按手势与按压淡出反馈。
 *
 * @param onClick 点击回调。
 * @param onLongClick 长按回调，null 表示不响应长按。
 * @param enabled 是否响应手势。
 * @param modifier 应用到按钮的 modifier。
 * @param content 图标内容。
 */
@Composable
fun GlassTerminalBarPanelButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val contentAlpha by animateFloatAsState(
        targetValue = if (pressed) GlassTerminalBarDefaults.PressedContentAlpha else 1f,
        animationSpec = tween(durationMillis = 120),
        label = "glassTerminalBarPanelButtonPress",
    )
    Box(
        modifier = modifier
            .size(GlassTerminalBarDefaults.PanelButtonSize)
            .graphicsLayer { alpha = contentAlpha }
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
                enabled = enabled,
            ),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
