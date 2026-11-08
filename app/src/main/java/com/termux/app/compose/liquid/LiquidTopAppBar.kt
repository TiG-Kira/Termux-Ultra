// Copyright 2026, Kira contributors
// SPDX-License-Identifier: MIT

package com.termux.app.compose.liquid

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import com.kyant.shapes.UnevenRoundedRectangle
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 顶栏单行高度：大标题、标题、按钮**全在这一行里**，折叠前后高度不变。 */
val LiquidTopBarPillHeight: Dp = 56.dp

/**
 * 折叠所需的滚动距离。
 *
 * 顶栏高度不变，所以这里只是给 miuix 的 nested-scroll 连接一个「可收起量」——
 * 连接按 `heightOffsetLimit` 分配手势，登记 0 会让它认为无处可收而把滚动全部吃掉。
 * 滚过这段距离后大标题完全换成小标题、玻璃完全成形。
 */
private val LiquidTopBarCollapseDistance: Dp = 36.dp

/** 顶栏左右内缩（内容留白用；玻璃块本身是通栏的，不内缩）。 */
val LiquidTopBarHorizontalInset: Dp = 12.dp

/** 玻璃块底部圆角：顶部贴屏幕边不圆角，底部 28dp 收口像一块垂下来的玻璃。 */
private val LiquidTopBarGlassRadius: Dp = 28.dp

/**
 * 玻璃块**顶部**上溢量：把玻璃的顶边推到屏幕之外，屏幕里只剩下一条笔直的边。
 *
 * 形状本身已经写成 `topStart = topEnd = 0dp`，但仅靠形状压不住顶角圆弧——
 * Kyant 的 `drawBackdrop` 会先把玻璃层按 `BackdropEffectScope.padding`（透镜采样外扩）
 * 放大、再按形状裁轮廓，外扩后的轮廓在屏幕顶边这一侧依旧会露出约 28dp 的圆弧
 * （用户实证：顶栏玻璃上方左右各一个圆角，下方才是想要的收口）。
 * 与其跟库的裁剪口径较劲，直接把玻璃块整体上移、高度同步加长：
 * 顶角圆弧落到屏幕外被裁掉，底部 28dp 圆角的位置分毫不动。
 *
 * 多给的 8dp 是余量：外扩 padding 会让轮廓再往外偏一点，只补 28dp 会残留一线弧。
 */
private val LiquidTopBarGlassBleed: Dp = LiquidTopBarGlassRadius + 8.dp

/**
 * 顶栏动作按钮的**外**间距（每个按钮左右各占这么多）。
 *
 * 必须与 miuix `GlassTopAppBarDefaults.ButtonPadding`（6dp，相邻按钮合计 12dp）保持一致：
 * 歌曲详情 / 播放页在展开态用 miuix 按钮、折叠后换成液态按钮，两者占位不一致会出现
 * 「同一组按钮平滑一下间距就变了」的跳变（用户实测指出）。按钮本体仍是 44dp 圆钮，
 * 这里只撑开占位，圆钮不变形。
 */
private val LiquidActionButtonPadding: Dp = 6.dp

/** 顶栏高度（不含状态栏）。折叠前后一致。 */
val LiquidTopBarCollapsedHeight: Dp
    get() = LiquidTopBarPillHeight

/**
 * 液态玻璃版顶栏（Kyant0/AndroidLiquidGlass 独立实现）。
 *
 * 形体（用户三轮口径收敛后的最终版）：
 * - **单行**：大标题 / 标题、动作按钮全在同一行里，顶栏高度恒为 [LiquidTopBarPillHeight]。
 *   之前"大标题一行 + 工具栏一行"会让没有按钮的页面（听 / Agent / 我）也白占两行高，
 *   顶上多出一大条空白，很难看——现在无论有没有按钮都是同一行高度。
 * - **置顶态不画玻璃**：大标题直接压在页面内容上方，右侧只有玻璃圆钮；
 * - 滚动后一整块玻璃从屏幕顶边淡入，**同时包住状态栏 + 顶栏**，底部 28dp 圆角收口
 *   （不是浮动胶囊——浮动胶囊在展开态会变成一条空荡荡的横条）。
 * - 折叠的视觉表现只剩「大标题缩小淡出 ↔ 小标题淡入」，高度不变，所以滚动不会引起
 *   列表重新布局，手感比收缩式顶栏更稳。
 *
 * 材质：`vibrancy + blur(8dp) + lens(16dp, 18dp, depthEffect)`；玻璃块通栏贴边，所以不加
 * 边缘高光和投影（那两个是浮动胶囊的语法，贴边玻璃加了会在屏幕边糊出一条亮线）。
 *
 * ## 为什么还要碰 `heightOffsetLimit`
 *
 * 页面把 `scrollBehavior.nestedScrollConnection` 挂在 LazyColumn 上，miuix 的连接按
 * `state.heightOffsetLimit` 判断「顶栏还能收起多少」。这个字段**只有顶栏自己会登记**
 * （`BlurTopAppBar` 在测量阶段写入）。自建顶栏如果忘了写，连接会认为无处可收 →
 * 把上滑手势全部吃掉 → 页面**完全划不动**（AVD 实证：整屏冻结，只有点击有效）。
 * 这里登记 [LiquidTopBarCollapseDistance]，只借用它换取折叠进度，不拿它算高度。
 *
 * @param backdrop 液态取景层；null 时落成实色背景（无着色器/材质关闭的兜底）
 * @param scrollBehavior 驱动折叠的滚动行为；null 视为已完全收窄
 */
/**
 * 液态顶栏的**皮肤层**：一块随折叠进度淡入的整片玻璃（matchParentSize，
 * 折叠态正好包住「状态栏 + 小标题栏」，底部 28dp 圆角收口）。
 *
 * 液态顶栏的骨架（折叠逻辑 / 大标题 / 居中小标题 / 按钮槽）是 miuix `GlassTopAppBar`
 * （backdrop 传 null 关掉它自己的玻璃与按钮材质，band 笔刷透明化），这层皮肤垫在
 * 骨架之下、内容之上：折叠时淡入盖住骨架的背板，液滴按钮画在皮肤之上。
 *
 * 不再登记 `heightOffsetLimit`——折叠量的语义（大标题区高度）归 miuix 骨架所有，
 * 这里只读 `collapsedFraction` 驱动透明度。
 *
 * @param backdrop 液态取景层；null 时落成实色背景（材质关闭的兜底）
 */
@Composable
fun LiquidTopBarSkin(
    backdrop: Backdrop?,
    scrollBehavior: ScrollBehavior?,
    modifier: Modifier = Modifier,
    collapsedHeight: Dp = 56.dp,
    // 宿主适配：顶栏骨架把自己的 maskProgress 喂进来，玻璃与 band / 按钮材质同源淡入。
    // Kiba 上游只从 scrollBehavior 取进度，但 Termux Ultra 的页面各自持有 scrollBehavior，
    // 全局槽外层拿不到；这里允许骨架在 band 位置注入自己的折叠进度（lambda 需 remember 恒定）。
    collapseProgress: (() -> Float)? = null,
) {
    val collapse: () -> Float = remember(scrollBehavior, collapseProgress) {
        collapseProgress ?: if (scrollBehavior == null) {
            ({ 1f })
        } else {
            ({ scrollBehavior.state.collapsedFraction.coerceIn(0f, 1f) })
        }
    }

    val isDark = MiuixTheme.colorScheme.background.luminance() < 0.5f
    val glassColor = remember(isDark) {
        if (isDark) Color(0xFF121212).copy(alpha = 0.55f) else Color(0xFFFAFAFA).copy(alpha = 0.62f)
    }
    val glassShape: () -> Shape = remember {
        {
            UnevenRoundedRectangle(
                topStart = 0.dp,
                topEnd = 0.dp,
                bottomEnd = LiquidTopBarGlassRadius,
                bottomStart = LiquidTopBarGlassRadius,
            )
        }
    }
    val glassEffects: BackdropEffectScope.() -> Unit = remember { { liquidBarEffects() } }
    // 玻璃与投影必须共用**同一个进度**，不能指望外层 graphicsLayer 的 alpha 顺带把投影收掉：
    // drawBackdrop 的投影是内部独立的 ShadowNode，它用自己的 GraphicsLayer 绘制，
    // shadowLayer.alpha 只认 Shadow.alpha（固定值）。展开/折叠切换快的那几帧里，
    // 玻璃已经淡没了而投影层还留在上一帧的强度，表现成「玻璃消失了阴影还在」。
    // 这里让投影强度跟着同一个折叠进度走，且进度归零时直接返回 null——
    // ShadowNode 会连投影层一起跳过，彻底不留残影。lambda 身份恒定（remember），
    // 只在 draw 阶段读进度，不会触发取景重录/滚动细抖。
    val glassAlpha: () -> Float = remember(collapse) {
        { (collapse() * 3f).coerceIn(0f, 1f) }
    }
    val glassShadow: () -> Shadow? = remember(glassAlpha) {
        {
            val p = glassAlpha()
            if (p <= 0f) {
                null
            } else {
                Shadow(radius = 16.dp, color = Color.Black.copy(alpha = 0.07f), alpha = p)
            }
        }
    }
    val glassSurface: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit =
        remember(glassColor) { { drawRect(glassColor) } }

    // 必须和 miuix 骨架用**同一个**顶部 inset：骨架走
    // `WindowInsets.systemBars.only(Top)`（BlurTopAppBar 里 windowInsetsPadding 那一行），
    // 这里之前用 `statusBars`——在挖孔 / 高状态栏机型上 `systemBars.top` 会比
    // `statusBars.top` 大 10~20dp（本机实测差 14dp），玻璃就矮了一截，
    // 底边正好切在顶栏按钮中间（实机：按钮只套了一半）。
    val statusBarTop = WindowInsets.systemBars.asPaddingValues().calculateTopPadding()

    Box(
        modifier = modifier
            .fillMaxWidth()
            // 皮肤只盖「状态栏 + 折叠态高度」：骨架有大标题区（展开态高很多），
            // matchParentSize 会让半透明玻璃在中途态罩住大标题（视觉 bug），固定高度杜绝。
            // 高度必须**含**状态栏且从屏幕顶边起画——玻璃要包住通知栏（statusBarsPadding
            // 会让玻璃从状态栏下方才开始，顶上露出一条内容，实证踩过）。
            // 再往上多铺 [LiquidTopBarGlassBleed] 并用同样的偏移把整块玻璃顶上去：
            // 底边位置不变，顶边的两个圆弧落到屏幕外，可见部分只剩一条直边。
            //
            // 高度必须用 requiredHeight 而不是 height：主屏传进来的 modifier 是
            // `Modifier.matchParentSize()`，它给的是**固定**约束（min == max == 顶栏高度），
            // 普通 height() 会被 clamp 回原高度 → 玻璃只上移没加长，底边缩到标题上方
            // （实机截图：玻璃只包住状态栏，标题露在玻璃外面）。requiredHeight 忽略
            // incoming 约束，才能真的画出比父级更高的玻璃块。
            .offset(y = -LiquidTopBarGlassBleed)
            .requiredHeight(collapsedHeight + statusBarTop + LiquidTopBarGlassBleed)
            .graphicsLayer {
                // 折叠到 1/3 时玻璃已经完全成形，避免半透明玻璃糊在标题后面
                alpha = glassAlpha()
            }
            .then(
                if (backdrop != null) {
                    Modifier.drawBackdrop(
                        backdrop = backdrop,
                        shape = glassShape,
                        effects = glassEffects,
                        highlight = null,
                        shadow = glassShadow,
                        onDrawSurface = glassSurface,
                    )
                } else {
                    Modifier.background(MiuixTheme.colorScheme.surface)
                },
            ),
    )
}

/**
 * 液态玻璃图标按钮（顶栏 action 用）：44dp 圆形胶囊。
 *
 * 折射高度取 demo `LiquidButton` 的 12dp / 24dp——按钮面积小，折射量给大才有「水滴」的
 * 隆起感；底色补一层很淡的白色（暗色 12% / 亮色 42%）保证图标在任何背景下都读得清。
 *
 * ## 按压反馈（液态语义）
 *
 * 之前 `clickable(indication = null, interactionSource = null)` 等于**完全没有点击效果**，
 * 而 miuix 的 `GlassIconButton` 有 pressed 叠色 + 图标淡出。这里做成「手指把一颗液滴压扁」：
 * - 整颗按钮 spring 收缩到 94%（松手弹回，不做回弹过冲，避免像果冻）；
 * - 中心泛起一圈随按压扩散的径向叠色（暗色提亮 / 亮色压暗，取 miuix
 *   `buttonPressedOverlay` 的同一组颜色）；
 * - 图标同步淡到 60%（miuix `PressedContentAlpha`），与柔光玻璃按钮的观感对齐。
 *
 * 所有动画值只在 `graphicsLayer` / draw 阶段读取，**不进 composition**：含 `drawBackdrop`
 * 的子树一旦收高频 State 就会逐帧重组，令 effects lambda 身份变化、整套取景重录重模糊
 * （底栏滚动细抖的旧坑）。`effects` / `onDrawSurface` / 光斑 lambda 一律 remember 恒定身份。
 */
@Composable
fun LiquidIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val backdrop = LocalLiquidBackdrop.current
    val isDark = MiuixTheme.colorScheme.background.luminance() < 0.5f
    val surface = remember(isDark) {
        if (isDark) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.42f)
    }
    val shape: () -> Shape = remember { { Capsule() } }
    val effects: BackdropEffectScope.() -> Unit = remember {
        {
            vibrancy()
            blur(2.dp.toPx())
            lens(12.dp.toPx(), 24.dp.toPx())
        }
    }
    val highlight = remember { { LiquidBarHighlight } }
    val shadow = remember { { LiquidBarShadow } }
    val onDrawSurface: DrawScope.() -> Unit = remember(surface) { { drawRect(surface) } }

    // ── 按压动画 ──
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    // Animatable 身份稳定：draw 阶段读 .value 只触发重绘，不触发重组/取景重录。
    val press = remember { Animatable(0f) }
    LaunchedEffect(pressed) {
        press.animateTo(
            targetValue = if (pressed) 1f else 0f,
            animationSpec = spring(
                dampingRatio = 1f,
                stiffness = if (pressed) Spring.StiffnessHigh else Spring.StiffnessMediumLow,
            ),
        )
    }
    val pressTint = remember(isDark) {
        if (isDark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.10f)
    }
    val pressOverlay: DrawScope.() -> Unit = remember(pressTint, press) {
        {
            val p = press.value
            if (p > 0f) {
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            pressTint.copy(alpha = pressTint.alpha * p),
                            pressTint.copy(alpha = 0f),
                        ),
                        center = Offset(size.width / 2f, size.height / 2f),
                        radius = size.minDimension * (0.45f + 0.55f * p),
                    ),
                    size = size,
                )
            }
        }
    }

    Box(
        modifier = modifier
            // 外间距对齐 miuix 的 ButtonPadding（见 LiquidActionButtonPadding）
            .padding(horizontal = LiquidActionButtonPadding)
            .size(44.dp)
            .graphicsLayer {
                val s = 1f - 0.06f * press.value
                scaleX = s
                scaleY = s
            }
            .then(
                if (backdrop != null) {
                    Modifier.drawBackdrop(
                        backdrop = backdrop,
                        shape = shape,
                        effects = effects,
                        highlight = highlight,
                        shadow = shadow,
                        onDrawSurface = onDrawSurface,
                    )
                } else {
                    Modifier
                        .clip(CircleShape)
                        .background(MiuixTheme.colorScheme.surfaceVariant)
                },
            )
            .clip(Capsule())
            .drawWithContent {
                drawContent()
                pressOverlay()
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.graphicsLayer { alpha = 1f - 0.4f * press.value }) {
            content()
        }
    }
}

/**
 * 通用液态玻璃胶囊容器：`vibrancy + blur(8dp) + lens(16dp, 18dp)` + 边缘高光 + 24dp 投影。
 *
 * 底栏导航条、播放底栏都走这个形体，保证两处材质一致。
 * 形体和效果 lambda 一律 remember——`drawBackdrop` 的节点元素按 lambda 身份判等，
 * 每次重组新建 lambda 会触发 invalidateDrawCache（整套取景重录 → 滚动细抖）。
 */
@Composable
fun LiquidGlassPill(
    backdrop: Backdrop?,
    modifier: Modifier = Modifier,
    containerColor: Color? = null,
    content: @Composable () -> Unit,
) {
    val isDark = MiuixTheme.colorScheme.background.luminance() < 0.5f
    val container = remember(isDark) {
        if (isDark) Color(0xFF121212).copy(alpha = 0.40f) else Color(0xFFFAFAFA).copy(alpha = 0.40f)
    }
    val shape: () -> Shape = remember { { Capsule() } }
    val effects: BackdropEffectScope.() -> Unit = remember { { liquidBarEffects() } }
    val highlight = remember { { LiquidBarHighlight } }
    val shadow = remember { { LiquidBarShadow } }
    val fill = remember(container, containerColor) { containerColor ?: container }
    val onDrawSurface: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit =
        remember(fill) { { drawRect(fill) } }

    Box(
        modifier = modifier
            .then(
                if (backdrop != null) {
                    Modifier.drawBackdrop(
                        backdrop = backdrop,
                        shape = shape,
                        effects = effects,
                        highlight = highlight,
                        shadow = shadow,
                        onDrawSurface = onDrawSurface,
                    )
                } else {
                    Modifier.background(MiuixTheme.colorScheme.surface, Capsule())
                },
            )
            .clip(Capsule()),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
